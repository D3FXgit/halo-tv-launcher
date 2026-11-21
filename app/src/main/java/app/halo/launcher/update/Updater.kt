package app.halo.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.halo.launcher.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates over the air from the project's GitHub releases: checks the latest release, and when it is
 * newer, asks the user; the APK is downloaded into Halo's private storage, checked (same app, newer
 * version, signed with the same key as the installed Halo) and handed to Android's package installer,
 * which asks the user to confirm. Nothing is installed silently.
 */
class Updater(context: Context, private val scope: CoroutineScope) {
    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Android needs "Install unknown apps" for Halo first. */
        data class NeedsPermission(val release: Release) : State
        data object Installing : State
        data class Failed(val reason: Reason, val detail: String = "") : State
    }

    enum class Reason { Offline, NoRelease, Download, WrongSignature, NotNewer, Install }

    companion object {
        private const val TAG = "HaloUpdate"
        private const val MAX_APK = 150L * 1024 * 1024
        private const val DAY = 24 * 3_600_000L

        /** "v1.2.10" → [1, 2, 10]. */
        fun parse(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }

        fun newer(candidate: String, current: String): Boolean {
            val a = parse(candidate)
            val b = parse(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("halo_update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** True while the "new version" prompt should be on screen. */
    val prompt = MutableStateFlow(false)

    private val _auto = MutableStateFlow(prefs.getBoolean("auto", true))
    /** Check once a day on its own. */
    val auto: StateFlow<Boolean> = _auto

    private var job: Job? = null

    /** owner/repo from the project URL (github.com only); null = updates are off in this build. */
    private val repo: String? = BuildConfig.PROJECT_URL.trim().removeSuffix("/").let { url ->
        Regex("^https://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)$").find(url)?.groupValues?.get(1)
    }

    val enabled: Boolean get() = repo != null

    fun setAuto(on: Boolean) {
        _auto.value = on
        prefs.edit().putBoolean("auto", on).apply()
    }

    /** Daily automatic check (release builds; a debug build is signed differently and couldn't update). */
    fun checkAutomatically() {
        if (!enabled || !_auto.value || BuildConfig.DEV_HOOKS) return
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0L) < DAY) return
        check(manual = false)
    }

    /** Checks GitHub; a manual check always shows its result, an automatic one only a new version. */
    fun check(manual: Boolean) {
        if (!enabled || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            if (manual) _state.value = State.Checking
            val r = try {
                latest()
            } catch (e: Exception) {
                Log.w(TAG, "check failed: ${e.message}")
                if (manual) _state.value = State.Failed(if (e is IOException) Reason.Offline else Reason.NoRelease)
                return@launch
            }
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            if (r == null || !newer(r.version, BuildConfig.VERSION_NAME)) {
                if (manual) _state.value = State.UpToDate
                return@launch
            }
            if (!manual && prefs.getString("skipped", null) == r.version) return@launch
            _state.value = State.Available(r)
            prompt.value = true
        }
    }

    fun later() {
        prompt.value = false
    }

    fun skip(r: Release) {
        prefs.edit().putString("skipped", r.version).apply()
        prompt.value = false
        _state.value = State.Idle
    }

    fun dismiss() {
        prompt.value = false
        if (_state.value !is State.Downloading && _state.value !is State.Installing) _state.value = State.Idle
    }

    private fun latest(): Release? {
        val c = open("https://api.github.com/repos/$repo/releases/latest", json = true)
        val root = try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }) as JsonObject
        } finally {
            c.disconnect()
        }
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (root["draft"]?.jsonPrimitive?.contentOrNull == "true" || root["prerelease"]?.jsonPrimitive?.contentOrNull == "true") return null
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "Halo.apk" }
            ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true }
            ?: return null
        val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!url.startsWith("https://github.com/")) return null
        return Release(
            version = tag.removePrefix("v").removePrefix("V"),
            notes = plainNotes(root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            apkUrl = url,
            size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /** Release notes without markdown symbols, at most a few lines (they are shown on the TV). */
    private fun plainNotes(md: String): String = md.lines()
        .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().replace(Regex("^[-*] "), "• ").replace("**", "").replace("`", "") }
        .filter { it.isNotEmpty() }
        .take(10)
        .joinToString("\n")
        .take(900)

    private fun open(url: String, json: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Halo/${BuildConfig.VERSION_NAME} (Android TV launcher)")
            if (json) setRequestProperty("Accept", "application/vnd.github+json")
        }

    // ---- download and install ----------------------------------------------------------

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" page for Halo. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun update(r: Release) {
        if (job?.isActive == true) return
        if (!canInstall()) {
            _state.value = State.NeedsPermission(r)
            return
        }
        job = scope.launch(Dispatchers.IO) {
            val file = try {
                download(r)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                _state.value = State.Failed(Reason.Download)
                return@launch
            }
            verify(file)?.let { reason ->
                file.delete()
                _state.value = State.Failed(reason)
                return@launch
            }
            _state.value = State.Installing
            try {
                install(file)
            } catch (e: Exception) {
                Log.w(TAG, "install failed: ${e.message}")
                _state.value = State.Failed(Reason.Install, e.message.orEmpty())
            }
        }
    }

    private suspend fun download(r: Release): File {
        val dir = File(app.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val out = File(dir, "Halo-${r.version}.apk")
        val c = open(r.apkUrl)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            if (total > MAX_APK) throw IOException("too large")
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK) throw IOException("too large")
                        o.write(buf, 0, n)
                        val p = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (p != last) {
                            last = p
                            _state.value = State.Downloading(r, p)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    /** Null = fine; otherwise why the file must not be installed. */
    @Suppress("DEPRECATION")
    private fun verify(file: File): Reason? {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(file.path, flags) ?: return Reason.Download
        if (info.packageName != app.packageName) return Reason.WrongSignature
        val installed = pm.getPackageInfo(app.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (newCode <= oldCode) return Reason.NotNewer
        fun certs(p: android.content.pm.PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
            return sigs.orEmpty().map { s ->
                MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val a = certs(info)
        if (a.isEmpty() || a != certs(installed)) return Reason.WrongSignature
        return null
    }

    private suspend fun install(file: File) = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("halo.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java).setPackage(app.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    /** Called by [InstallReceiver] with the installer's result. */
    internal fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle
            else -> _state.value = State.Failed(Reason.Install, message.orEmpty())
        }
    }
}
