package app.halo.launcher.setup

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.util.Log
import app.halo.launcher.BuildConfig
import app.halo.launcher.R
import app.halo.launcher.wallpaper.MyMedia
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small web page on the TV, in the home network only, for sending the user's own pictures from a
 * phone: wallpapers ("My pictures") and app icons. Opened by scanning a QR code on the TV; runs only
 * while that QR panel is on screen.
 *
 * Safety (same approach as [KeyServer]): bound to the TV's private LAN address only, random token in
 * the URL (counted failures shut it down), Host header check, at most 4 connections, a time budget
 * per request, 25 MB per picture, 30 minutes lifetime. Pictures are decoded and re-encoded on the TV
 * (never stored as sent) and kept in Halo's private storage.
 */
class UploadServer(
    context: Context,
    /** Installed apps for the icon picker: key → label. */
    private val apps: () -> List<Pair<String, String>>,
    /** An own icon was saved (or removed: [saved] = false) for an app key. */
    private val onIcon: (key: String, saved: Boolean) -> Unit,
) {
    enum class Phase { Idle, Waiting, NoNetwork, Expired }

    data class State(val phase: Phase = Phase.Idle, val url: String? = null, val received: Int = 0)

    companion object {
        private const val TAG = "HaloUpload"
        const val PREFERRED_PORT = 8771
        private const val TOKEN_LEN = 10
        private const val TOKEN_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
        private const val MAX_HEAD = 8 * 1024
        private const val LIFETIME_MS = 30 * 60_000L
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val REQUEST_BUDGET_MS = 180_000L
        private const val MAX_BAD_TOKENS = 20
    }

    private val app = context.applicationContext
    private val baseConfig = Configuration(context.resources.configuration)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String = ""
    @Volatile private var hostHeader: String = ""
    private val badTokens = AtomicInteger(0)
    private val connections = Semaphore(4)
    /** Pictures are processed one at a time (decoding big photos needs memory). */
    private val busy = Semaphore(1)

    /** Starts (or restarts with a fresh token). [app] = app key to preselect on the icon page. */
    fun start(preselect: String? = null) {
        stop()
        badTokens.set(0)
        token = newToken()
        _state.value = State(Phase.Idle)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val ip = lanAddress()
            val socket = ip?.let { open(it) }
            if (ip == null || socket == null) {
                Log.w(TAG, "no LAN address or port (ip=${ip?.hostAddress})")
                _state.value = State(Phase.NoNetwork)
                return@launch
            }
            server = socket
            hostHeader = "${ip.hostAddress}:${socket.localPort}"
            val extra = preselect?.let { "&app=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            _state.value = State(Phase.Waiting, "http://$hostHeader/?t=$token$extra")
            if (BuildConfig.DEV_HOOKS) Log.i(TAG, "listening on http://$hostHeader/?t=$token")
            launch {
                delay(LIFETIME_MS)
                closeSocket()
                _state.value = _state.value.copy(phase = Phase.Expired, url = null)
            }
            acceptLoop(socket, this)
        }
    }

    fun stop() {
        closeSocket()
        scope?.cancel()
        scope = null
        token = ""
        if (_state.value.phase == Phase.Waiting) _state.value = State(Phase.Idle)
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

    private fun lanAddress(): Inet4Address? = Lan.address(app)

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
                    // Broken connection or timeout.
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
        // The request line and headers must arrive quickly; only picture bodies get the long budget.
        val head = readHead(s, input, SystemClock.elapsedRealtime() + 15_000L) ?: return
        val req = parseHead(head) ?: return respond(s, 400, "text/plain", null)
        if (BuildConfig.DEV_HOOKS) Log.i(TAG, "${req.method} ${req.path} from ${s.inetAddress?.hostAddress} host=${req.headers["host"]}")
        if (req.headers["host"].orEmpty() != hostHeader) {
            Log.w(TAG, "wrong Host header: ${req.headers["host"]}")
            return respond(s, 400, "text/plain", null)
        }
        val res = resourcesFor(req.headers["accept-language"])
        if (!tokenOk(req.query["t"])) {
            if (badTokens.incrementAndGet() >= MAX_BAD_TOKENS) closeSocket()
            return respond(s, 403, "text/html; charset=utf-8", message(res, R.string.upload_web_expired).toByteArray())
        }
        when {
            req.method == "GET" && req.path == "/" -> respond(s, 200, "text/html; charset=utf-8", page(res, req.query["app"]).toByteArray())
            req.method == "GET" && req.path == "/api/state" -> respond(s, 200, "application/json", stateJson().toByteArray())
            req.method == "GET" && req.path == "/img/wall" -> {
                val f = req.query["name"]?.let { MyMedia.wallpaper(app, it) } ?: return respond(s, 404, "text/plain", null)
                respond(s, 200, "image/jpeg", f.readBytes())
            }
            req.method == "GET" && req.path == "/img/icon" -> {
                val key = req.query["app"].orEmpty()
                val f = MyMedia.iconFile(app, key).takeIf { it.isFile } ?: return respond(s, 404, "text/plain", null)
                respond(s, 200, "image/png", f.readBytes())
            }
            req.method == "POST" && (req.path == "/api/wall" || req.path == "/api/icon") -> upload(s, input, req, deadline, res)
            req.method == "POST" && req.path == "/api/delete" -> {
                val ok = when (req.query["kind"]) {
                    "wall" -> MyMedia.deleteWallpaper(app, req.query["name"].orEmpty())
                    "icon" -> {
                        val key = req.query["app"].orEmpty()
                        MyMedia.deleteIcon(app, key).also { if (it) onIcon(key, false) }
                    }
                    else -> false
                }
                respond(s, if (ok) 200 else 404, "application/json", """{"ok":$ok}""".toByteArray())
            }
            else -> respond(s, 404, "text/plain", null)
        }
    }

    private fun upload(s: Socket, input: InputStream, req: Request, deadline: Long, res: Resources) {
        val length = req.headers["content-length"]?.trim()?.toLongOrNull()
        if (length == null || length <= 0) return error(s, 400, res.getString(R.string.upload_web_err_empty))
        if (length > MyMedia.MAX_UPLOAD) return error(s, 413, res.getString(R.string.upload_web_err_large))
        val icon = req.path == "/api/icon"
        val key = req.query["app"].orEmpty()
        if (icon && apps().none { it.first == key }) return error(s, 400, res.getString(R.string.upload_web_err_app))
        val body = readBody(s, input, length.toInt(), deadline) ?: return error(s, 400, res.getString(R.string.upload_web_err_empty))
        busy.acquire()
        try {
            if (icon) {
                MyMedia.saveIcon(app, key, body)
                onIcon(key, true)
            } else {
                MyMedia.saveWallpaper(app, body)
            }
            _state.value = _state.value.copy(received = _state.value.received + 1)
            respond(s, 200, "application/json", """{"ok":true}""".toByteArray())
        } catch (e: OutOfMemoryError) {
            error(s, 413, res.getString(R.string.upload_web_err_large))
        } catch (e: Exception) {
            Log.w(TAG, "upload failed: ${e.javaClass.simpleName}")
            error(s, 415, res.getString(R.string.upload_web_err_image))
        } finally {
            busy.release()
        }
    }

    private fun error(s: Socket, code: Int, message: String) =
        respond(s, code, "application/json", """{"ok":false,"error":"${jsonEsc(message)}"}""".toByteArray())

    private fun stateJson(): String {
        val walls = MyMedia.wallpapers(app).joinToString(",") { "\"${jsonEsc(it.name)}\"" }
        val list = apps().sortedBy { it.second.lowercase() }.joinToString(",") { (k, l) ->
            """{"key":"${jsonEsc(k)}","label":"${jsonEsc(l)}","own":${MyMedia.hasIcon(app, k)}}"""
        }
        return """{"walls":[$walls],"apps":[$list]}"""
    }

    private fun tokenOk(t: String?): Boolean {
        val expected = token
        if (t == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(t.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun budget(s: Socket, deadline: Long): Boolean {
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0) return false
        s.soTimeout = left.coerceAtMost(SOCKET_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
        return true
    }

    private fun readHead(s: Socket, input: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream(1024)
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buf.size() < MAX_HEAD) {
            if (buf.size() % 256 == 0 && !budget(s, deadline)) return null
            val b = input.read()
            if (b < 0) return null
            // A TLS handshake (browsers that try https:// first): close at once so they fall back to http://.
            if (buf.size() == 0 && b == 0x16) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return buf.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun readBody(s: Socket, input: InputStream, length: Int, deadline: Long): ByteArray? {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            if (!budget(s, deadline)) return null
            val n = input.read(out, read, minOf(64 * 1024, length - read))
            if (n < 0) return null
            read += n
        }
        return out
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
        return Request(parts[0], target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers)
    }

    private fun parseQuery(s: String): Map<String, String> = s.split('&').mapNotNull { pair ->
        if (pair.isEmpty()) return@mapNotNull null
        runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
    }.toMap()

    private fun respond(s: Socket, code: Int, type: String, body: ByteArray?) {
        val b = body ?: ByteArray(0)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(b.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(b)
        out.flush()
    }

    // ---- Page ---------------------------------------------------------------------------

    /** English, German or Turkish, following the phone's browser language. */
    private fun resourcesFor(acceptLanguage: String?): Resources {
        val first = acceptLanguage?.split(',')?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val lang = when {
            first.startsWith("de") -> "de"
            first.startsWith("tr") -> "tr"
            else -> "en"
        }
        val cfg = Configuration(baseConfig)
        cfg.setLocale(Locale.forLanguageTag(lang))
        return app.createConfigurationContext(cfg).resources
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(ch)
        }
    }

    private fun jsonEsc(s: String): String = buildString(s.length) {
        for (ch in s) when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\")
            ch < ' ' -> append(String.format("\\u%04x", ch.code))
            ch == '<' -> append("\\u003c")
            else -> append(ch)
        }
    }

    private fun style() = """
:root{color-scheme:light dark}*{box-sizing:border-box}
body{margin:0;font:17px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#12141a;
background:linear-gradient(160deg,#bfe6ff 0%,#7fa6dc 55%,#9be3b0 100%);min-height:100vh;padding:20px}
.card{max-width:560px;margin:0 auto 18px;background:rgba(255,255,255,.84);border:1px solid rgba(255,255,255,.95);
border-radius:26px;padding:22px 20px;box-shadow:0 18px 50px rgba(30,40,70,.22)}
.brand{font-weight:700;letter-spacing:.14em;font-size:13px;color:#3e4350}
h1{font-size:26px;line-height:1.2;margin:4px 0 6px}h2{font-size:20px;margin:0 0 6px}
p{margin:0 0 12px;color:#3e4350}
.btn{display:block;width:100%;margin-top:10px;font:inherit;font-weight:700;font-size:17px;padding:14px;border:0;border-radius:999px;
color:#fff;background:linear-gradient(180deg,#5cc4f5,#2fa8e8);text-align:center;cursor:pointer}
select{width:100%;font:inherit;padding:12px 14px;border-radius:14px;border:2px solid #b9c6da;background:#fff;color:#12141a}
input[type=file]{display:none}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:12px}
.tile{position:relative;aspect-ratio:16/9;border-radius:12px;overflow:hidden;background:#0001}
.tile img{width:100%;height:100%;object-fit:cover;display:block}
.x{position:absolute;top:4px;right:4px;width:30px;height:30px;border-radius:50%;border:0;background:rgba(0,0,0,.6);color:#fff;font-size:18px;line-height:30px;padding:0;cursor:pointer}
.icon{margin-top:12px;aspect-ratio:16/9;max-width:320px;border-radius:14px;overflow:hidden;background:#0002}
.icon img{width:100%;height:100%;object-fit:cover;display:block}
.status{margin-top:10px;font-weight:600;min-height:1.5em}
.link{background:none;border:0;color:#1b78b8;font:inherit;padding:8px 0;cursor:pointer;text-decoration:underline}
@media (prefers-color-scheme:dark){body{color:#f5f6f8;background:linear-gradient(160deg,#0b1430,#070a18 60%,#0f2a2a)}
.card{background:rgba(20,26,44,.88);border-color:rgba(255,255,255,.12)}p,.brand{color:#b8bcc6}
select{background:#0e1426;color:#f5f6f8;border-color:#334}.link{color:#6fd3ff}}
""".trimIndent()

    private fun message(res: Resources, id: Int): String {
        val lang = res.configuration.locales.get(0)?.language ?: "en"
        return """<!doctype html><html lang="$lang"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Halo</title><style>${style()}</style></head><body><main class="card"><div class="brand">HALO</div><p>${esc(res.getString(id))}</p></main></body></html>"""
    }

    private fun page(res: Resources, preselect: String?): String {
        val lang = res.configuration.locales.get(0)?.language ?: "en"
        fun t(id: Int) = esc(res.getString(id))
        // Texts for the script (JSON-escaped, so they are safe inside <script>).
        val js = mapOf(
            "uploading" to res.getString(R.string.upload_web_uploading),
            "done" to res.getString(R.string.upload_web_done),
            "failed" to res.getString(R.string.upload_web_failed),
            "remove" to res.getString(R.string.upload_web_remove),
            "none" to res.getString(R.string.upload_web_no_icon),
        ).entries.joinToString(",") { (k, v) -> "\"$k\":\"${jsonEsc(v)}\"" }
        val pre = jsonEsc(preselect.orEmpty())
        return """<!doctype html>
<html lang="$lang"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>${t(R.string.upload_web_title)}</title><style>${style()}</style></head>
<body>
<main class="card"><div class="brand">HALO</div><h1>${t(R.string.upload_web_title)}</h1><p>${t(R.string.upload_web_intro)}</p></main>
<section class="card" id="iconCard">
<h2>${t(R.string.upload_web_icons)}</h2><p>${t(R.string.upload_web_icons_hint)}</p>
<select id="app"></select>
<div class="icon" id="iconPrev"></div>
<label class="btn" for="iconFile">${t(R.string.upload_web_choose_icon)}</label><input id="iconFile" type="file" accept="image/*">
<button class="link" id="iconDel" type="button">${t(R.string.upload_web_reset_icon)}</button>
<div class="status" id="iconStatus"></div>
</section>
<section class="card">
<h2>${t(R.string.upload_web_walls)}</h2><p>${t(R.string.upload_web_walls_hint)}</p>
<label class="btn" for="wallFile">${t(R.string.upload_web_choose_walls)}</label><input id="wallFile" type="file" accept="image/*" multiple>
<div class="status" id="wallStatus"></div>
<div class="grid" id="walls"></div>
</section>
<script>
const T={$js};const PRE="$pre";
const tok=new URLSearchParams(location.search).get('t');
const q=(p,o)=>p+(p.includes('?')?'&':'?')+'t='+encodeURIComponent(tok)+(o||'');
const ${'$'}=id=>document.getElementById(id);
let state={walls:[],apps:[]};
function fmt(s,a){return s.replace('%1${'$'}d',a[0]).replace('%2${'$'}d',a[1]).replace('%1${'$'}s',a[0]);}
async function load(){const r=await fetch(q('/api/state'));state=await r.json();render();}
function render(){
 const sel=${'$'}('app');const cur=sel.value||PRE;sel.innerHTML='';
 for(const a of state.apps){const o=document.createElement('option');o.value=a.key;o.textContent=a.label+(a.own?' ★':'');sel.appendChild(o);}
 if(cur)sel.value=cur;showIcon();
 const g=${'$'}('walls');g.innerHTML='';
 for(const n of state.walls){const d=document.createElement('div');d.className='tile';const i=document.createElement('img');i.loading='lazy';i.src=q('/img/wall?name='+encodeURIComponent(n));
  const x=document.createElement('button');x.className='x';x.type='button';x.textContent='✕';x.title=T.remove;x.onclick=async()=>{await fetch(q('/api/delete?kind=wall&name='+encodeURIComponent(n)),{method:'POST'});load();};
  d.appendChild(i);d.appendChild(x);g.appendChild(d);}
}
function showIcon(){const k=${'$'}('app').value;const a=state.apps.find(x=>x.key===k);const p=${'$'}('iconPrev');p.innerHTML='';
 ${'$'}('iconDel').style.display=a&&a.own?'block':'none';
 if(a&&a.own){const i=document.createElement('img');i.src=q('/img/icon?app='+encodeURIComponent(k),'&v='+Date.now());p.appendChild(i);}}
${'$'}('app').onchange=showIcon;
async function send(path,file){const r=await fetch(q(path),{method:'POST',headers:{'Content-Type':file.type||'application/octet-stream'},body:file});
 let j={};try{j=await r.json();}catch(e){}if(!r.ok||!j.ok)throw new Error(j.error||('HTTP '+r.status));}
${'$'}('wallFile').onchange=async e=>{const fs=[...e.target.files];const st=${'$'}('wallStatus');let ok=0;
 for(let i=0;i<fs.length;i++){st.textContent=fmt(T.uploading,[i+1,fs.length]);try{await send('/api/wall',fs[i]);ok++;}catch(err){st.textContent=fmt(T.failed,[err.message]);await new Promise(r=>setTimeout(r,1500));}}
 st.textContent=ok?T.done:st.textContent;e.target.value='';load();};
${'$'}('iconFile').onchange=async e=>{const f=e.target.files[0];if(!f)return;const st=${'$'}('iconStatus');st.textContent=fmt(T.uploading,[1,1]);
 try{await send('/api/icon?app='+encodeURIComponent(${'$'}('app').value),f);st.textContent=T.done;}catch(err){st.textContent=fmt(T.failed,[err.message]);}
 e.target.value='';load();};
${'$'}('iconDel').onclick=async()=>{await fetch(q('/api/delete?kind=icon&app='+encodeURIComponent(${'$'}('app').value)),{method:'POST'});${'$'}('iconStatus').textContent=T.none;load();};
load();
</script>
</body></html>"""
    }
}
