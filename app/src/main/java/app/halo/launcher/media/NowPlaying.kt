package app.halo.launcher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.StateFlow

/**
 * Exists only so Android lets Halo read media sessions.
 * Grant once with:
 * adb shell cmd notification allow_listener app.halo.launcher/app.halo.launcher.media.MediaListenerService
 */
class MediaListenerService : NotificationListenerService()

@Immutable
data class NowPlaying(
    val title: String,
    val artist: String,
    val appLabel: String,
    val packageName: String,
    val art: ImageBitmap?,
    /** Full-resolution artwork (downloaded from the session's art URI), when available. */
    val artHi: ImageBitmap? = null,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** elapsedRealtime of [positionMs]. */
    val positionAt: Long,
    val speed: Float,
) {
    /** Current playback position, extrapolated between session updates. */
    fun positionAtMs(nowElapsed: Long): Long {
        val pos = if (playing) positionMs + ((nowElapsed - positionAt) * speed).toLong() else positionMs
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }

    fun progressAt(nowElapsed: Long): Float {
        if (durationMs <= 0) return 0f
        return (positionAtMs(nowElapsed).toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

class NowPlayingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val msm = appContext.getSystemService(MediaSessionManager::class.java)!!
    private val listenerComponent = ComponentName(appContext, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state

    /** False until notification access has been granted via adb. */
    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess

    private var controller: MediaController? = null
    private var started = false
    private var artCacheKey: Any? = null
    private var artCache: ImageBitmap? = null
    private var appLabelCache: Pair<String, String>? = null

    /** High-res artwork downloaded from METADATA_KEY_*_ART_URI (https only). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hiUri: String? = null
    private var hiArt: ImageBitmap? = null
    private var hiJob: Job? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    fun start() {
        if (started) {
            refreshSessions()
            return
        }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            _hasAccess.value = true
            refreshSessions()
        } catch (_: SecurityException) {
            _hasAccess.value = false
        }
    }

    fun stop() {
        if (started) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        controller?.unregisterCallback(controllerCallback)
        controller = null
        hiJob?.cancel()
        hiUri = null
    }

    private fun refreshSessions() {
        try {
            pick(msm.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            _hasAccess.value = false
        }
    }

    private fun pick(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            best?.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            _state.value = null
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val rawArt: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = if (rawArt === artCacheKey) artCache else {
            artCacheKey = rawArt
            artCache = rawArt?.let { b ->
                runCatching {
                    val s = 720f / maxOf(b.width, b.height)
                    (if (s < 1f) b.scale((b.width * s).toInt(), (b.height * s).toInt()) else b).asImageBitmap()
                }.getOrNull()
            }
            artCache
        }
        val ps = c.playbackState
        val appLabel = appLabelCache?.takeIf { it.first == c.packageName }?.second ?: runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName).also { appLabelCache = c.packageName to it }

        val uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri != hiUri) {
            hiUri = uri
            hiArt = null
            hiJob?.cancel()
            if (uri != null && uri.startsWith("https://")) {
                hiJob = io.launch {
                    val bmp = runCatching { download(uri) }.getOrNull() ?: return@launch
                    handler.post {
                        if (hiUri == uri) {
                            hiArt = bmp
                            publish()
                        }
                    }
                }
            }
        }

        val next = NowPlaying(
            title = title,
            artist = artist,
            appLabel = appLabel,
            packageName = c.packageName,
            art = art,
            artHi = hiArt,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            positionMs = ps?.position ?: 0L,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
        // Position-only updates (the extrapolated position barely moved) don't need a new state:
        // every emission recomposes the home screen.
        val prev = _state.value
        val now = SystemClock.elapsedRealtime()
        if (prev != null &&
            next.copy(positionMs = 0, positionAt = 0) == prev.copy(positionMs = 0, positionAt = 0) &&
            kotlin.math.abs(next.positionAtMs(now) - prev.positionAtMs(now)) < 1500
        ) return
        _state.value = next
    }

    /** Downloads and decodes an artwork URL, capped at 1920 px wide. */
    private fun download(url: String): ImageBitmap? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) return null
            if (c.contentLengthLong > 8L shl 20) return null
            val bytes = c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > 8 shl 20) return null
                }
                buf.toByteArray()
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 1920 || opts.outHeight / (sample * 2) >= 1080) sample *= 2
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val s = minOf(1920f / bmp.width, 1080f / bmp.height, 1f)
            if (s < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            bmp.prepareToDraw()
            return bmp.asImageBitmap()
        } finally {
            c.disconnect()
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    /** Jumps forward/back by [deltaMs] (e.g. ±10 s), within the track. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val np = _state.value ?: return
        val target = np.positionAtMs(SystemClock.elapsedRealtime()) + deltaMs
        val max = if (np.durationMs > 0) np.durationMs else Long.MAX_VALUE
        c.transportControls.seekTo(target.coerceIn(0L, max))
    }

    /** Whether the playing app allows seeking. */
    val canSeek: Boolean
        get() = (controller?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
}
