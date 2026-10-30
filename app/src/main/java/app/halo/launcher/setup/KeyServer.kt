package app.halo.launcher.setup

import android.os.SystemClock
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.annotation.StringRes
import app.halo.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny, short-lived web server on the TV, so the Gemini API key can be pasted on a phone instead
 * of typed with the remote. The phone scans a QR code with `http://<tv-ip>:<port>/?t=<token>`.
 *
 * Security model (home network, plain HTTP):
 *  - Binds only to the TV's own LAN IPv4 address (Wi-Fi/Ethernet), never to all interfaces.
 *  - Every request needs the random 6-character token from the QR code (SecureRandom, compared in
 *    constant time); the Host header must be the TV's address (blocks DNS-rebinding pages).
 *  - One accepted key per token, at most [MAX_ATTEMPTS] submissions, request bodies ≤ 4 KB,
 *    headers ≤ 8 KB, 10 s socket timeouts, at most 4 connections at a time.
 *  - The key is checked with Google (GET /v1beta/models) before it is handed to the TV; it is never
 *    logged or echoed back. Responses are no-store, no-referrer, framing and scripts disabled.
 *  - The server stops when the setup step closes ([stop]), after a key was accepted, or after 15 min.
 *
 * [onKey] is called (on a background thread) with a key that Google accepted.
 */
class KeyServer(context: Context, private val onKey: (String) -> Unit) {

    enum class Phase {
        /** Not running yet / stopped by the UI. */
        Idle,
        /** Listening; nobody sent a key yet. */
        Waiting,
        /** A key arrived and is being checked with Google. */
        Checking,
        /** The last key was rejected by Google (or malformed); the phone can try again. */
        Rejected,
        /** Google could not be reached to check the last key. */
        Offline,
        /** A key was accepted and handed to the TV. The server has stopped. */
        Received,
        /** 15 minutes passed (or too many attempts); the server has stopped. */
        Expired,
        /** The TV has no LAN address (not connected) or the port could not be opened. */
        NoNetwork,
    }

    data class State(val phase: Phase = Phase.Idle, val url: String? = null)

    enum class Check { Valid, Invalid, Offline }

    companion object {
        const val PREFERRED_PORT = 8765
        private const val TOKEN_LEN = 10
        /** No 0/O, 1/l/i: easy to read and type if the QR code can't be scanned. */
        private const val TOKEN_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
        private const val MAX_BODY = 4 * 1024
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_ATTEMPTS = 10
        private const val LIFETIME_MS = 15 * 60_000L
        private const val SOCKET_TIMEOUT_MS = 10_000
        /** Whole-request budget per connection (stops slow clients from holding a slot). */
        private const val REQUEST_BUDGET_MS = 15_000L
        /** Wrong-token requests before the server shuts down (guessing protection). */
        private const val MAX_BAD_TOKENS = 20
        const val KEY_PAGE = "https://aistudio.google.com/apikey"
        private val KEY_FORMAT = Regex("^[A-Za-z0-9_.\\-]{20,200}$")

        /** Plausible API key (before asking Google). */
        fun looksLikeKey(key: String): Boolean = KEY_FORMAT.matches(key.trim())

        /**
         * Asks Google whether [key] works: GET /v1beta/models with the key header. Blocking — call
         * from a background thread. 200 (or 429 = valid key, quota used up) → [Check.Valid].
         */
        fun check(key: String): Check {
            val k = key.trim()
            if (!looksLikeKey(k)) return Check.Invalid
            return try {
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8_000
                    c.readTimeout = 8_000
                    c.requestMethod = "GET"
                    c.setRequestProperty("x-goog-api-key", k)
                    c.useCaches = false
                    val code = c.responseCode
                    runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } }
                    when (code) {
                        200, 429 -> Check.Valid
                        400, 401, 403 -> Check.Invalid
                        else -> Check.Offline
                    }
                } finally {
                    c.disconnect()
                }
            } catch (_: IOException) {
                Check.Offline
            } catch (_: RuntimeException) {
                Check.Offline
            }
        }
    }

    private val app = context.applicationContext
    /** The UI's context (already in the interface language) – used for the TV's own language. */
    private val baseConfig = Configuration(context.resources.configuration)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""
    @Volatile private var hostHeader: String = ""
    private val accepted = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val attempts = AtomicInteger(0)
    private val badTokens = AtomicInteger(0)
    /** Bumped by start()/stop(): a key check that finishes after a stop is dropped. */
    private val generation = AtomicInteger(0)
    private val connections = Semaphore(4)

    /** Starts (or restarts with a fresh token) the server. Safe to call from the main thread. */
    fun start() {
        stop()
        generation.incrementAndGet()
        accepted.set(false)
        busy.set(false)
        attempts.set(0)
        badTokens.set(0)
        token = newToken()
        _state.value = State(Phase.Idle)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val ip = lanAddress()
            val socket = ip?.let { open(it) }
            if (ip == null || socket == null) {
                _state.value = State(Phase.NoNetwork)
                return@launch
            }
            server = socket
            val host = ip.hostAddress ?: ""
            hostHeader = "$host:${socket.localPort}"
            _state.value = State(Phase.Waiting, "http://$hostHeader/?t=$token")
            launch {
                delay(LIFETIME_MS)
                expire()
            }
            acceptLoop(socket, this)
        }
    }

    /** Stops listening and forgets the token. The last phase stays visible unless it was "live". */
    fun stop() {
        generation.incrementAndGet()
        closeSocket()
        scope?.cancel()
        scope = null
        val p = _state.value.phase
        if (p == Phase.Waiting || p == Phase.Checking || p == Phase.Rejected || p == Phase.Offline) {
            _state.value = State(Phase.Idle)
        }
    }

    private fun expire() {
        closeSocket()
        if (_state.value.phase != Phase.Received) _state.value = State(Phase.Expired)
    }

    private fun closeSocket() {
        runCatching { server?.close() }
        server = null
    }

    private fun newToken(): String {
        val r = SecureRandom()
        return buildString { repeat(TOKEN_LEN) { append(TOKEN_ALPHABET[r.nextInt(TOKEN_ALPHABET.length)]) } }
    }

    private fun open(ip: InetAddress): ServerSocket? {
        for (port in (PREFERRED_PORT..PREFERRED_PORT + 5) + 0) {
            val s = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ip, port), 8)
                }
            }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    /**
     * The TV's private LAN IPv4 address (10/8, 172.16/12, 192.168/16) – never a public or carrier
     * address, so the page is only reachable inside the home network.
     */
    private fun lanAddress(): Inet4Address? {
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val lp = cm?.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()?.let { return it }
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()
    }

    private fun acceptLoop(socket: ServerSocket, scope: CoroutineScope) {
        while (scope.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            if (!connections.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            scope.launch {
                try {
                    client.use { handle(it) }
                } catch (_: Exception) {
                    // Broken connection or timeout: nothing to report.
                } finally {
                    connections.release()
                }
            }
        }
    }

    // ---- HTTP -------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun handle(s: Socket) {
        s.soTimeout = SOCKET_TIMEOUT_MS
        val deadline = SystemClock.elapsedRealtime() + REQUEST_BUDGET_MS
        val input = s.getInputStream()
        val head = readHead(s, input, deadline) ?: return respond(s, 400, null)
        val req = parseHead(head) ?: return respond(s, 400, null)
        val res = resourcesFor(req.headers["accept-language"])

        // Only requests addressed to the TV's own IP:port (blocks DNS-rebinding pages).
        val host = req.headers["host"].orEmpty()
        if (host != hostHeader) return respond(s, 400, page(res, R.string.setup_web_expired_heading, R.string.setup_web_expired_text))

        when {
            req.path == "/favicon.ico" -> respond(s, 204, null)
            req.path == "/" && req.method == "GET" -> {
                when {
                    !tokenOk(req.query["t"]) -> badToken(s, res) ?: respond(s, 403, page(res, R.string.setup_web_expired_heading, R.string.setup_web_expired_text))
                    accepted.get() -> respond(s, 410, page(res, R.string.setup_web_used_heading, R.string.setup_web_used_text))
                    else -> respond(s, 200, form(res))
                }
            }
            req.path == "/key" && req.method == "POST" -> handleKey(s, input, req, res, deadline)
            req.path == "/" || req.path == "/key" -> respond(s, 405, null)
            else -> respond(s, 404, null)
        }
    }

    private fun handleKey(s: Socket, input: InputStream, req: Request, res: Resources, deadline: Long) {
        val gen = generation.get()
        val length = req.headers["content-length"]?.trim()?.toIntOrNull()
        if (length == null || length < 0) return respond(s, 400, null)
        if (length > MAX_BODY) return respond(s, 413, page(res, R.string.setup_web_bad_heading, R.string.setup_web_too_large, retry = true))
        val body = readBody(s, input, length, deadline) ?: return respond(s, 400, null)
        val form = parseForm(body)
        if (!tokenOk(form["t"])) return badToken(s, res) ?: respond(s, 403, page(res, R.string.setup_web_expired_heading, R.string.setup_web_expired_text))
        if (accepted.get()) return respond(s, 410, page(res, R.string.setup_web_used_heading, R.string.setup_web_used_text))
        if (attempts.incrementAndGet() > MAX_ATTEMPTS) {
            respond(s, 429, page(res, R.string.setup_web_expired_heading, R.string.setup_web_too_many))
            expire()
            return
        }
        if (!busy.compareAndSet(false, true)) return respond(s, 409, page(res, R.string.setup_web_bad_heading, R.string.setup_web_busy_text, retry = true))
        try {
            val key = form["key"].orEmpty().trim()
            if (!looksLikeKey(key)) {
                _state.value = _state.value.copy(phase = Phase.Rejected)
                return respond(s, 400, page(res, R.string.setup_web_bad_heading, R.string.setup_web_format_text, retry = true))
            }
            _state.value = _state.value.copy(phase = Phase.Checking)
            val result = check(key)
            // The wizard step closed while Google was checking: drop the key.
            if (gen != generation.get()) return respond(s, 410, page(res, R.string.setup_web_expired_heading, R.string.setup_web_expired_text))
            when (result) {
                Check.Valid -> {
                    if (!accepted.compareAndSet(false, true)) {
                        return respond(s, 410, page(res, R.string.setup_web_used_heading, R.string.setup_web_used_text))
                    }
                    onKey(key)
                    _state.value = State(Phase.Received)
                    respond(s, 200, page(res, R.string.setup_web_ok_heading, R.string.setup_web_ok_text, ok = true))
                    closeSocket()
                }
                Check.Invalid -> {
                    _state.value = _state.value.copy(phase = Phase.Rejected)
                    respond(s, 400, page(res, R.string.setup_web_bad_heading, R.string.setup_web_bad_text, retry = true))
                }
                Check.Offline -> {
                    _state.value = _state.value.copy(phase = Phase.Offline)
                    respond(s, 502, page(res, R.string.setup_web_offline_heading, R.string.setup_web_offline_text, retry = true))
                }
            }
        } finally {
            busy.set(false)
        }
    }

    /** Counts a wrong token; after [MAX_BAD_TOKENS] the server stops. Returns Unit if it answered. */
    private fun badToken(s: Socket, res: Resources): Unit? {
        if (badTokens.incrementAndGet() < MAX_BAD_TOKENS) return null
        respond(s, 429, page(res, R.string.setup_web_expired_heading, R.string.setup_web_too_many))
        expire()
        return Unit
    }

    /** Remaining time of this request's budget as a socket timeout; false when it is used up. */
    private fun budget(s: Socket, deadline: Long): Boolean {
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0) return false
        s.soTimeout = left.coerceAtMost(SOCKET_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
        return true
    }

    private fun tokenOk(t: String?): Boolean {
        val expected = token
        if (t == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(t.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    /** Reads up to the blank line that ends the headers; null if it is too long or the client hangs up. */
    private fun readHead(s: Socket, input: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream(1024)
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buf.size() < MAX_HEAD) {
            if (buf.size() % 256 == 0 && !budget(s, deadline)) return null
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return buf.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun readBody(s: Socket, input: InputStream, length: Int, deadline: Long): String? {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            if (!budget(s, deadline)) return null
            val n = input.read(out, read, length - read)
            if (n < 0) return null
            read += n
        }
        return String(out, Charsets.UTF_8)
    }

    private fun parseHead(head: String): Request? {
        val lines = head.split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null
        val target = parts[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
        }
        return Request(
            method = parts[0],
            path = target.substringBefore('?'),
            query = parseForm(target.substringAfter('?', "")),
            headers = headers,
        )
    }

    private fun parseForm(s: String): Map<String, String> = s.split('&').mapNotNull { pair ->
        if (pair.isEmpty()) return@mapNotNull null
        val k = pair.substringBefore('=')
        val v = pair.substringAfter('=', "")
        runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
    }.toMap()

    private fun respond(s: Socket, code: Int, html: String?) {
        val body = html?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            410 -> "Gone"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            if (html != null) append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    // ---- Pages ------------------------------------------------------------------------

    /** English or German, following the phone's browser language. */
    private fun resourcesFor(acceptLanguage: String?): Resources {
        val first = acceptLanguage?.split(',')?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val lang = if (first.startsWith("de")) "de" else "en"
        val cfg = Configuration(baseConfig)
        cfg.setLocale(Locale.forLanguageTag(lang))
        return app.createConfigurationContext(cfg).resources
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun shell(res: Resources, lang: String, content: String): String = """
<!doctype html>
<html lang="$lang"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${esc(res.getString(R.string.setup_web_title))}</title>
<style>
:root{color-scheme:light dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;font:17px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
color:#12141a;background:linear-gradient(160deg,#bfe6ff 0%,#7fa6dc 55%,#9be3b0 100%);
display:flex;align-items:center;justify-content:center;padding:20px}
.card{width:100%;max-width:440px;background:rgba(255,255,255,.82);border:1px solid rgba(255,255,255,.95);
border-radius:28px;padding:28px 24px;box-shadow:0 18px 50px rgba(30,40,70,.25)}
.brand{font-weight:700;letter-spacing:.14em;font-size:13px;color:#3e4350}
h1{font-size:26px;line-height:1.2;margin:6px 0 10px}
p{margin:0 0 16px;color:#3e4350}
label{display:block;font-weight:600;margin:4px 0 8px}
input[type=text]{width:100%;font:inherit;font-size:17px;padding:14px 16px;border-radius:16px;border:2px solid #b9c6da;background:#fff;color:#12141a}
input[type=text]:focus{outline:none;border-color:#2fa8e8}
button,.btn{display:block;width:100%;margin-top:16px;font:inherit;font-weight:700;font-size:18px;padding:15px;border:0;border-radius:999px;
color:#fff;background:linear-gradient(180deg,#5cc4f5,#2fa8e8);text-align:center;text-decoration:none}
a{color:#1b78b8}
.ok{font-size:44px;line-height:1}
.small{font-size:14px;margin-top:18px}
@media (prefers-color-scheme:dark){body{color:#f5f6f8;background:linear-gradient(160deg,#0b1430,#070a18 60%,#0f2a2a)}
.card{background:rgba(20,26,44,.86);border-color:rgba(255,255,255,.12)}p,.brand{color:#b8bcc6}
input[type=text]{background:#0e1426;color:#f5f6f8;border-color:#334}a{color:#6fd3ff}}
</style></head><body><main class="card"><div class="brand">HALO</div>
$content
</main></body></html>
""".trimIndent()

    private fun langOf(res: Resources): String = res.configuration.locales.get(0)?.language ?: "en"

    private fun form(res: Resources): String {
        val content = """
<h1>${esc(res.getString(R.string.setup_web_heading))}</h1>
<p>${esc(res.getString(R.string.setup_web_intro))}</p>
<form method="post" action="/key" autocomplete="off">
<input type="hidden" name="t" value="${esc(token)}">
<label for="k">${esc(res.getString(R.string.setup_web_label))}</label>
<input id="k" name="key" type="text" required minlength="20" maxlength="200" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="API key">
<button type="submit">${esc(res.getString(R.string.setup_web_send))}</button>
</form>
<p class="small">${esc(res.getString(R.string.setup_web_get_key))} <a href="$KEY_PAGE" target="_blank" rel="noopener noreferrer">aistudio.google.com/apikey</a></p>
<p class="small">${esc(res.getString(R.string.setup_web_footer))}</p>
""".trimIndent()
        return shell(res, langOf(res), content)
    }

    private fun page(res: Resources, @StringRes heading: Int, @StringRes text: Int, ok: Boolean = false, retry: Boolean = false): String {
        val again = if (retry && token.isNotEmpty()) {
            "<a class=\"btn\" href=\"/?t=${esc(token)}\">${esc(res.getString(R.string.setup_web_try_again))}</a>"
        } else ""
        val content = """
${if (ok) "<div class=\"ok\">✓</div>" else ""}
<h1>${esc(res.getString(heading))}</h1>
<p>${esc(res.getString(text))}</p>
$again
""".trimIndent()
        return shell(res, langOf(res), content)
    }
}
