package app.halo.launcher.wallpaper

import app.halo.launcher.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** One picture of the library. [full] is a ~1920 px wide copy, [thumb] a small one for the picker. */
@Immutable
@Serializable
data class Picture(val full: String, val thumb: String, val credit: String) {
    /** Stable id of the picture (what Profile.wallpaperPin stores). */
    val key: String get() = full
}

/**
 * A decoded background, ready to draw. [image] is a software ARGB_8888 bitmap that exactly
 * covers 1920×1080 (one side is 1920 or 1080, the other ≥), already prepared for drawing.
 */
@Immutable
data class Wallpaper(val image: ImageBitmap, val credit: String, val key: String)

/** Where a collection's pictures come from (all keyless, all freely licensed or public domain). */
sealed interface Feed {
    /** NASA Image and Video Library search (images-api.nasa.gov). Public domain unless noted; credited. */
    data class Nasa(val query: String) : Feed

    /** Files directly in a Wikimedia Commons category (each picture credited with author and licence). */
    data class Commons(val category: String) : Feed

    /**
     * Wikimedia Commons' picture of the day for the last [days] days, from Wikipedia's
     * featured-content feed (api.wikimedia.org, falling back to en.wikipedia.org's REST API).
     */
    data class PictureOfTheDay(val days: Int = 45) : Feed

    /** Lorem Picsum catalogue (Unsplash photographs, Unsplash licence). */
    data object Picsum : Feed
}

/** A collection of the picture library. */
@Immutable
class WallpaperCollection(
    val id: String,
    val title: String,
    val description: String,
    val icon: IconSpec,
    val feeds: List<Feed> = emptyList(),
    /** Pictures used when the APIs can't be reached (Unsplash photos via Picsum: no attribution needed). */
    val fallback: List<Picture> = emptyList(),
    /** How long a downloaded picture list stays fresh. */
    val ttlHours: Int = 7 * 24,
)

/**
 * Halo's picture library.
 *
 * Each collection is a list of [Picture]s gathered from keyless, freely licensed sources (Wikimedia
 * Commons incl. its picture of the day, NASA's image library, Lorem Picsum) and cached on disk as a
 * small JSON manifest. Every picture carries a credit line (title, author, licence, source) that the
 * UI shows with it.
 * Rotation walks through a collection in a shuffled, non-repeating order whose position survives
 * restarts; the next picture is downloaded and decoded ahead of time.
 *
 * Network etiquette: per-host concurrency limits and pacing (Wikimedia: 1 API call at a time,
 * ≤2 parallel image downloads), at most 3 downloads overall, retries with backoff on 429/5xx that
 * honour Retry-After, short negative caching of failing URLs and hosts, and a User-Agent naming the
 * app and its project page as Wikimedia's User-Agent policy requires. Diagnostics go to logcat with the tag "HaloWall".
 */
class WallpaperRepository(context: Context, private val scope: CoroutineScope) {
    companion object {
        const val SCENE = "scene"
        private const val TAG = "HaloWall"

        /** Minutes between pictures the user can choose (0 = keep one picture). */
        val INTERVALS: List<Int> = listOf(0, 1, 5, 15, 30, 60, 1440)

        private val treeIcon = IconSpec(listOf("M12 2.5l6 8h-3.2l4.2 6H5l4.2-6H6z", "M12 16.5v5"))
        private val waveIcon = IconSpec(listOf("M3 10c2-2 4-2 6 0s4 2 6 0 4-2 6 0", "M3 15.5c2-2 4-2 6 0s4 2 6 0 4-2 6 0"))


        private fun picsum(vararg ids: Int): List<Picture> = ids.map { id ->
            Picture("https://picsum.photos/id/$id/1920/1080.jpg", "https://picsum.photos/id/$id/400/225.jpg", "Unsplash · Lorem Picsum")
        }

        /** Last resort so a collection never ends up with just one picture. */
        private val genericFallback = picsum(10, 11, 15, 16, 17, 28, 29, 43, 46, 47, 49, 54)


        val collections: List<WallpaperCollection> = listOf(
            WallpaperCollection(SCENE, "Halo scene", "Animated mountains that follow the time of day", Icons.screensaver),
            WallpaperCollection(
                "daily", "Picture of the day", "Wikimedia Commons’ picture of the day — the last few weeks", Icons.image,
                feeds = listOf(Feed.PictureOfTheDay(days = 45)),
                ttlHours = 12,
            ),
            WallpaperCollection(
                "landscapes", "Landscapes", "Mountains, lakes and valleys", Icons.wallpaper,
                feeds = listOf(
                    Feed.Commons("Featured pictures of landscapes"),
                    Feed.Commons("Featured pictures of mountains"),
                    Feed.Commons("Featured pictures of lakes"),
                    Feed.Commons("Quality images of valleys"),
                    Feed.Commons("Quality images of canyons"),
                    Feed.Commons("Featured pictures of sunsets"),
                ),
            ),
            WallpaperCollection(
                "forest", "Forests & Nature", "Woods, rivers and wild places", treeIcon,
                feeds = listOf(
                    Feed.Commons("Featured pictures of habitats"),
                    Feed.Commons("Featured pictures of watercourses"),
                    Feed.Commons("Featured pictures of waterfalls"),
                    Feed.Commons("Featured pictures of bodies of water"),
                    Feed.Commons("Quality images of rivers"),
                    Feed.Commons("Quality images of nature"),
                ),
            ),
            WallpaperCollection(
                "aero", "Aero", "Bright skies, clouds, clear water and auroras", Icons.partly,
                feeds = listOf(
                    Feed.Commons("Featured pictures of clouds"),
                    Feed.Commons("Featured pictures of atmospheric optical phenomena"),
                    Feed.Commons("Featured pictures of islands"),
                    Feed.Commons("Featured pictures of lakes"),
                    Feed.Commons("Featured pictures of sunsets"),
                    Feed.Nasa("aurora"),
                ),
            ),
            WallpaperCollection(
                "space", "Space", "Nebulae, galaxies and the Milky Way — NASA, Hubble, Webb", Icons.rocket,
                feeds = listOf(
                    Feed.Nasa("nebula"),
                    Feed.Nasa("galaxy"),
                    Feed.Nasa("hubble"),
                    Feed.Nasa("milky way"),
                    Feed.Nasa("james webb"),
                    Feed.Commons("Featured pictures of astronomy"),
                    Feed.Commons("James Webb Space Telescope images of galaxies"),
                    Feed.Commons("James Webb Space Telescope images of nebulae"),
                ),
            ),
            WallpaperCollection(
                "ocean", "Ocean & Coast", "Coastlines, beaches, lighthouses and open sea", waveIcon,
                feeds = listOf(
                    Feed.Commons("Featured pictures of coasts"),
                    Feed.Commons("Quality images of coasts"),
                    Feed.Commons("Quality images of oceans and seas"),
                    Feed.Commons("Featured pictures of islands"),
                    Feed.Commons("Featured pictures of bodies of water"),
                ),
            ),
            WallpaperCollection(
                "night", "Cities & Night", "Skylines, old towns and city lights", Icons.moon,
                feeds = listOf(
                    Feed.Commons("Featured night photography"),
                    Feed.Commons("Quality night photography"),
                    Feed.Commons("Quality night photography in Germany"),
                    Feed.Commons("Featured pictures of Prague"),
                    Feed.Commons("Featured pictures of Kraków"),
                    Feed.Commons("Featured pictures of Wrocław"),
                    Feed.Commons("Featured pictures of Athens"),
                ),
            ),
            WallpaperCollection(
                "photos", "Photography", "Hundreds of Unsplash photos", Icons.camera,
                feeds = listOf(Feed.Picsum),
                fallback = genericFallback,
            ),
        )

        /** Unknown ids fall back to the scene ("potd" was the old picture of the day). */
        fun of(id: String?): WallpaperCollection =
            collections.firstOrNull { it.id == id }
                ?: (if (id == "potd") collections.first { it.id == "daily" } else collections.first())


        const val FULL_W = 1920
        const val FULL_H = 1080
        private const val THUMB_W = 400
        private const val THUMB_H = 225
        private const val MAX_PICTURES = 400
        private const val MAX_DOWNLOAD = 30L * 1024 * 1024
        /** v6: Bing and wallhaven were removed; older caches must not keep their pictures. */
        private const val MANIFEST_VERSION = 6
    }

    private data class Config(val id: String, val minutes: Int, val pin: String?)

    private class Manifest(val at: Long, val pictures: List<Picture>)

    @Serializable
    private data class ManifestFile(val at: Long, val pictures: List<Picture>)

    private class NasaLink(val href: String, val rel: String, val w: Int, val h: Int)

    private class HttpException(val code: Int, val retryAfterMs: Long?, url: String) : IOException("HTTP $code for $url")

    private sealed interface Decoded {
        class Ok(val image: ImageBitmap) : Decoded
        /** Portrait or too small: never suitable as a background. */
        data object Unsuitable : Decoded
        /** Not an image (captive portal page, truncated file …). */
        data object Invalid : Decoded
        /** Temporary trouble (out of memory …). */
        data object Failed : Decoded
    }

    private val dir = File(context.applicationContext.filesDir, "wallpapers").apply { mkdirs() }
    private val thumbDir = File(dir, "thumbs").apply { mkdirs() }
    private val manifestDir = File(dir, "manifests").apply { mkdirs() }
    private val prefs = context.applicationContext.getSharedPreferences("halo_wallpaper", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Wikimedia's User-Agent policy wants the app name, version and a contact URL. */
    private val ua = "Halo/${BuildConfig.VERSION_NAME} (Android TV launcher" +
        BuildConfig.PROJECT_URL.takeIf { it.isNotBlank() }?.let { "; +$it" }.orEmpty() + ")"

    private val _current = MutableStateFlow<Wallpaper?>(null)

    /** The background to show (null = Halo scene, or nothing loaded yet). */
    val current: StateFlow<Wallpaper?> = _current

    @Volatile private var config = Config(SCENE, 15, null)
    private var job: Job? = null
    @Volatile private var prefetched: Wallpaper? = null
    private val pendingSkip = AtomicBoolean(false)
    private val skipSignal = Channel<Unit>(Channel.CONFLATED)

    private val manifests = ConcurrentHashMap<String, Manifest>()
    private val manifestLocks = ConcurrentHashMap<String, Mutex>()
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val bad: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(prefs.getStringSet("bad3", emptySet()).orEmpty())
    }
    private val thumbs = LruCache<String, ImageBitmap>(72)

    // Network etiquette.
    private val thumbGate = Semaphore(2)
    private val downloadGate = Semaphore(3)
    private val hostGates = ConcurrentHashMap<String, Semaphore>()
    private val hostLast = ConcurrentHashMap<String, Long>()
    private val hostCooldown = ConcurrentHashMap<String, Long>()
    private val failedUrls = ConcurrentHashMap<String, Long>()

    init {
        scope.launch(Dispatchers.IO) {
            // Leftovers of interrupted downloads.
            listOf(dir, thumbDir).forEach { d -> d.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        }
    }

    // ---- public API ---------------------------------------------------------------

    /**
     * Applies a profile's background settings: collection id, minutes between pictures
     * (0 = keep one) and an optional pinned picture key. An unchanged configuration is ignored;
     * a restart keeps the picture that is on screen unless it is due.
     */
    @Synchronized
    fun configure(collection: String, minutes: Int, pin: String?) {
        val cfg = Config(of(collection).id, minutes.coerceAtLeast(0), pin?.takeIf { it.isNotBlank() && !isRetired(it) })
        if (cfg == config && (job?.isActive == true || cfg.id == SCENE)) return
        config = cfg
        job?.cancel()
        job = null
        if (cfg.id == SCENE) {
            _current.value = null
            prefetched = null
            return
        }
        job = scope.launch(Dispatchers.IO) {
            if (cfg.pin != null) runPinned(cfg) else runRotation(cfg)
        }
    }

    /** Pins saved by older versions may point at sources that were removed (Bing, wallhaven.cc). */
    private fun isRetired(key: String): Boolean {
        val host = hostOf(key)
        return host.endsWith("bing.com") || host.endsWith("wallhaven.cc")
    }

    /** Switches collection (rotating, current interval). */
    fun select(id: String) = configure(id, config.minutes, null)

    /**
     * Next picture now (also used by the voice assistant). While a picture is pinned the
     * request waits until rotation resumes (clear the pin to apply it).
     */
    fun skip() {
        if (config.id == SCENE) return
        pendingSkip.set(true)
        skipSignal.trySend(Unit)
    }

    /** The collection's pictures (cached manifest, else downloaded). Empty for the Halo scene. */
    suspend fun pictures(collectionId: String): List<Picture> = withContext(Dispatchers.IO) {
        val col = of(collectionId)
        if (col.id == SCENE) emptyList() else manifest(col).filterNot { it.key in bad }
    }

    /** A thumbnail already in memory (no I/O). */
    fun cachedThumbnail(key: String): ImageBitmap? = thumbs.get(key)

    /** A small (≈400×225) picture for the picker; cached in memory and on disk. Null on failure. */
    suspend fun thumbnail(p: Picture): ImageBitmap? {
        thumbs.get(p.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            thumbGate.withPermit {
                thumbs.get(p.key) ?: run {
                    val full = fileFor(p.full, dir)
                    val file = if (full.exists()) {
                        full
                    } else {
                        try {
                            download(p.thumb, thumbDir)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (file == null) {
                        null
                    } else {
                        when (val d = decode(file, THUMB_W, THUMB_H, requireLandscape = false, hardware = true)) {
                            is Decoded.Ok -> d.image.also { thumbs.put(p.key, it) }
                            Decoded.Invalid -> {
                                file.delete()
                                null
                            }
                            else -> null
                        }
                    }
                }
            }
        }
    }

    /** The collection's picture of the day (thumbnail), for the collection cards. */
    suspend fun preview(collectionId: String): ImageBitmap? {
        val list = pictures(collectionId)
        if (list.isEmpty()) return null
        val start = Math.floorMod(LocalDate.now().toEpochDay(), list.size.toLong()).toInt()
        // Try a few in case one is unreachable.
        for (k in 0 until minOf(3, list.size)) {
            thumbnail(list[(start + k) % list.size])?.let { return it }
        }
        return null
    }

    // ---- rotation -----------------------------------------------------------------

    private suspend fun runPinned(cfg: Config) {
        val col = of(cfg.id)
        val pin = cfg.pin ?: return
        prefetched = null
        while (currentCoroutineContext().isActive) {
            val list = runCatching { manifest(col) }.getOrDefault(emptyList())
            val p = list.firstOrNull { it.key == pin } ?: Picture(pin, pin, "")
            val shown = _current.value?.takeIf { it.key == p.key } ?: load(p)
            if (shown != null) {
                _current.value = shown
                awaitCancellation()
            }
            delay(60_000)
        }
    }

    private suspend fun runRotation(cfg: Config) {
        val col = of(cfg.id)
        val id = col.id
        val interval = cfg.minutes * 60_000L
        while (currentCoroutineContext().isActive) {
            // Shuffle over the whole (unfiltered) list; broken pictures are skipped when picked,
            // so a newly broken picture doesn't reshuffle the cycle.
            val list = runCatching { manifest(col) }.getOrDefault(emptyList())
            if (list.none { it.key !in bad }) {
                Log.w(TAG, "$id: no usable pictures, retrying in 60 s")
                delay(60_000)
                continue
            }
            val size = list.size
            var seed = prefs.getLong("$id.seed", 0L)
            var pos = prefs.getInt("$id.pos", -1)
            var at = prefs.getLong("$id.at", 0L)
            if (seed == 0L) {
                seed = newSeed()
                pos = -1
                at = 0L
            } else if (prefs.getInt("$id.size", -1) != size) {
                // The list was refreshed: new shuffle, but keep the picture on screen and its time.
                seed = newSeed()
                val curKey = _current.value?.key
                val ord = order(seed, size)
                pos = if (curKey == null) -1 else (0 until size).firstOrNull { list[ord[it]].key == curKey } ?: -1
            }
            val now = System.currentTimeMillis()
            val skipNow = pendingSkip.getAndSet(false)
            val advance = skipNow || pos < 0 || pos >= size || (interval > 0 && now - at >= interval)
            if (advance) pos++

            // Find a picture that loads.
            var shown: Wallpaper? = null
            var tries = 0
            var steps = 0
            while (shown == null && tries < 6 && steps < size * 2 && currentCoroutineContext().isActive) {
                if (pos >= size) {
                    pos = 0
                    seed++
                }
                val p = list[order(seed, size)[pos]]
                steps++
                if (p.key in bad) {
                    pos++
                    continue
                }
                shown = _current.value?.takeIf { it.key == p.key } ?: takePrefetched(p) ?: load(p)
                if (shown == null) {
                    pos++
                    tries++
                }
            }
            // Offline / sources blocked: show any picture that is already on disk.
            if (shown == null) {
                if (pos >= size) {
                    pos = 0
                    seed++
                }
                val ord = order(seed, size)
                for (k in 0 until size) {
                    val i = (pos + k) % size
                    val p = list[ord[i]]
                    if (p.key !in bad && fileFor(p.full, dir).exists()) {
                        shown = load(p)
                        if (shown != null) {
                            pos = i
                            break
                        }
                    }
                }
            }
            if (shown == null) {
                Log.w(TAG, "$id: nothing could be loaded, retrying in 60 s")
                delay(60_000)
                continue
            }
            if (!currentCoroutineContext().isActive) return
            _current.value = shown
            if (advance || at == 0L) at = now
            prefs.edit()
                .putLong("$id.seed", seed)
                .putInt("$id.size", size)
                .putInt("$id.pos", pos)
                .putLong("$id.at", at)
                .apply()

            if (interval > 0) {
                // Download + decode the next picture now, so switching is instant.
                val next = nextUsable(list, seed, pos)
                if (next != null && next.key != shown.key && prefetched?.key != next.key) {
                    prefetched = null // release the old one before decoding a new one
                    prefetched = load(next)
                }
            } else {
                prefetched = null
            }

            val wait = if (interval > 0) (interval - (System.currentTimeMillis() - at)).coerceAtLeast(1_000L) else Long.MAX_VALUE
            if (wait == Long.MAX_VALUE) skipSignal.receive() else withTimeoutOrNull(wait) { skipSignal.receive() }
        }
    }

    private fun newSeed(): Long = Random.nextLong().let { if (it == 0L) 1L else it }

    private fun nextUsable(list: List<Picture>, seed: Long, pos: Int): Picture? {
        val size = list.size
        var s = seed
        var i = pos + 1
        repeat(size) {
            if (i >= size) {
                i = 0
                s++
            }
            val q = list[order(s, size)[i]]
            if (q.key !in bad) return q
            i++
        }
        return null
    }

    private var orderCache: Triple<Long, Int, IntArray>? = null

    /** Shuffled order for one cycle through a collection of [size] pictures. */
    @Synchronized
    private fun order(seed: Long, size: Int): IntArray {
        orderCache?.let { (s, n, o) -> if (s == seed && n == size) return o }
        val o = (0 until size).shuffled(Random(seed)).toIntArray()
        orderCache = Triple(seed, size, o)
        return o
    }

    private fun takePrefetched(p: Picture): Wallpaper? {
        val w = prefetched ?: return null
        if (w.key != p.key) return null
        prefetched = null
        return w
    }

    private fun markBad(key: String) {
        bad += key
        prefs.edit().putStringSet("bad3", HashSet(bad)).apply()
    }

    private suspend fun load(p: Picture): Wallpaper? {
        val file = try {
            download(p.full, dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // already logged; temporary
        }
        return when (val d = decode(file, FULL_W, FULL_H, requireLandscape = true, hardware = false)) {
            is Decoded.Ok -> Wallpaper(d.image, p.credit, p.key)
            Decoded.Unsuitable -> {
                Log.i(TAG, "unsuitable (portrait/small): ${p.full.take(160)}")
                file.delete()
                markBad(p.key)
                null
            }
            Decoded.Invalid -> {
                Log.w(TAG, "not an image: ${p.full.take(160)}")
                file.delete()
                failedUrls[p.full] = System.currentTimeMillis() + 10 * 60_000L
                null
            }
            Decoded.Failed -> null
        }
    }

    // ---- manifests ----------------------------------------------------------------

    private suspend fun manifest(col: WallpaperCollection): List<Picture> {
        val lock = manifestLocks.getOrPut(col.id) { Mutex() }
        return lock.withLock {
            val ttl = col.ttlHours * 3_600_000L
            val now = System.currentTimeMillis()
            val mem = manifests[col.id]
            if (mem != null && now - mem.at < ttl) return@withLock mem.pictures
            val file = File(manifestDir, "${col.id}.v$MANIFEST_VERSION.json")
            val disk = runCatching {
                json.decodeFromString(ManifestFile.serializer(), file.readText())
            }.getOrNull()?.takeIf { it.pictures.isNotEmpty() }
            if (disk != null && now - disk.at < ttl) {
                manifests[col.id] = Manifest(disk.at, disk.pictures)
                Log.i(TAG, "${col.id}: ${disk.pictures.size} pictures (disk cache)")
                return@withLock disk.pictures
            }
            val (fresh, complete) = fetch(col)
            val result: List<Picture>
            if (fresh.size >= 12) {
                // Some feeds failed → refresh sooner (6 h) instead of after the full TTL.
                val at = if (complete) now else now - ttl + minOf(ttl, 6 * 3_600_000L)
                runCatching { file.writeText(json.encodeToString(ManifestFile.serializer(), ManifestFile(at, fresh))) }
                manifests[col.id] = Manifest(at, fresh)
                result = fresh
            } else {
                // Sources unreachable: use what we have, then the curated lists; retry in ~30 minutes.
                var list = if (disk != null && disk.pictures.size >= fresh.size) disk.pictures
                else (fresh + col.fallback).distinctBy { it.key }
                if (list.size < 12) list = (list + genericFallback).distinctBy { it.key }
                manifests[col.id] = Manifest(now - ttl + 30 * 60_000L, list)
                result = list
            }
            Log.i(TAG, "${col.id}: ${result.size} pictures (fetched ${fresh.size}, complete=$complete)")
            result
        }
    }

    /** All feeds of a collection, interleaved so the picker shows a mix. Second = no feed failed. */
    private suspend fun fetch(col: WallpaperCollection): Pair<List<Picture>, Boolean> {
        val perFeed = ArrayList<List<Picture>>()
        var complete = true
        for (feed in col.feeds) {
            val got: List<Picture> = try {
                when (feed) {
                    is Feed.Nasa -> nasa(feed.query)
                    is Feed.Commons -> commonsCategory(feed.category)
                    is Feed.PictureOfTheDay -> pictureOfTheDay(feed.days)
                    Feed.Picsum -> picsumList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete = false
                Log.w(TAG, "${col.id}: feed $feed failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
            Log.i(TAG, "${col.id}: feed $feed → ${got.size}")
            perFeed += got
        }
        val out = LinkedHashMap<String, Picture>()
        var i = 0
        while (out.size < MAX_PICTURES && perFeed.any { i < it.size }) {
            for (l in perFeed) if (i < l.size && out.size < MAX_PICTURES) out.putIfAbsent(l[i].key, l[i])
            i++
        }
        return out.values.toList() to complete
    }

    // ---- sources ------------------------------------------------------------------

    /**
     * Files in a Commons category:
     * `commons.wikimedia.org/w/api.php?action=query&generator=categorymembers&gcmtype=file&prop=imageinfo&iiprop=url|size|mime|extmetadata`.
     * Landscape JPEGs ≥ 1600 px; thumbnails at Wikimedia's standard widths (1920 / 500 px). The credit
     * names the file, its author and its licence (as most Commons licences require attribution).
     */
    private suspend fun commonsCategory(category: String): List<Picture> {
        val out = ArrayList<Picture>()
        var cont: Map<String, String> = emptyMap()
        var requests = 0
        do {
            val b = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
                .appendQueryParameter("action", "query")
                .appendQueryParameter("format", "json")
                .appendQueryParameter("formatversion", "2")
                .appendQueryParameter("maxlag", "5")
                .appendQueryParameter("generator", "categorymembers")
                .appendQueryParameter("gcmtitle", "Category:$category")
                .appendQueryParameter("gcmtype", "file")
                .appendQueryParameter("gcmlimit", "200")
                .appendQueryParameter("prop", "imageinfo")
                .appendQueryParameter("iiprop", "url|size|mime|extmetadata")
                .appendQueryParameter("iiextmetadatafilter", "Artist|LicenseShortName")
            for ((k, v) in cont) b.appendQueryParameter(k, v)
            val root = getJson(b.build().toString()).obj() ?: break
            root.obj("error")?.let { err -> throw IOException("api error ${err.str("code")}: ${err.str("info")}") }
            val pages = root.obj("query")?.get("pages") as? JsonArray
            pages?.forEach { pe ->
                val page = pe.obj() ?: return@forEach
                val info = (page["imageinfo"] as? JsonArray)?.firstOrNull()?.obj() ?: return@forEach
                val url = info.str("url") ?: return@forEach
                val w = info.int("width")
                val h = info.int("height")
                if (info.str("mime") != "image/jpeg") return@forEach
                if (w < 1600 || h <= 0 || w < h * 1.3f || w > h * 2.6f) return@forEach
                val title = page.str("title")?.removePrefix("File:")?.substringBeforeLast('.')?.take(90) ?: "Commons"
                val meta = info.obj("extmetadata")
                val artist = meta?.obj("Artist")?.str("value")?.let { plainText(it) }?.take(60)
                val licence = meta?.obj("LicenseShortName")?.str("value")?.let { plainText(it) }?.take(30)
                out += Picture(commonsThumb(url, w, FULL_W), commonsThumb(url, w, 500), commonsCredit(title, artist, licence))
            }
            val c = root["continue"] as? JsonObject
            cont = c?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            requests++
        } while (cont.isNotEmpty() && requests < 3 && out.size < MAX_PICTURES)
        return out
    }

    /** upload.wikimedia.org/wikipedia/commons/a/ab/F.jpg → …/commons/thumb/a/ab/F.jpg/1920px-F.jpg */
    private fun commonsThumb(url0: String, width: Int, target: Int): String {
        // The API adds tracking parameters (?utm_source=…); they must not end up inside the path.
        val url = url0.substringBefore('?')
        if (width <= target) return url
        val marker = "/wikipedia/commons/"
        val i = url.indexOf(marker)
        if (i < 0) return url
        val rest = url.substring(i + marker.length)
        val name = rest.substringAfterLast('/')
        return url.substring(0, i) + marker + "thumb/" + rest + "/" + target + "px-" + name
    }

    /** NASA Image and Video Library: `images-api.nasa.gov/search?q=…&media_type=image&page_size=100`. */
    private val NASA_SPACE = listOf(
        "nebula", "galaxy", "galaxies", "star cluster", "star-forming", "stars", "hubble", "webb", "jwst", "milky way",
        "supernova", "planetary", "cosmic", "telescope image", "deep field", "aurora", "jupiter", "saturn", "neptune",
        "the moon", "lunar surface", "mars surface", "from the international space station", "earth from space", "comet",
    )
    private val NASA_PEOPLE = listOf(
        "administrator", "press conference", "briefing", "ceremony", "speaks", "speaking", "award", "visit", "meeting",
        "portrait", "headshot", "employees", "students", "hearing", "senator", "congress", "delegation", "celebration",
        "event", "panel", "interview", "media day", "launch control", "mission control", "astronaut", "crew member",
        "workers", "technicians", "engineers", "employee", "officials", "guests", "group photo", "speaker",
    )

    private suspend fun nasa(query: String): List<Picture> {
        val url = Uri.parse("https://images-api.nasa.gov/search").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("media_type", "image")
            .appendQueryParameter("page_size", "100")
            .build().toString()
        val items = getJson(url).obj()?.obj("collection")?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { ie ->
            val item = ie.obj() ?: return@mapNotNull null
            val data = (item["data"] as? JsonArray)?.firstOrNull()?.obj() ?: return@mapNotNull null
            if (data.str("media_type") != "image") return@mapNotNull null
            // Only real space imagery: NASA's library also holds press photos, portraits and events.
            val text = buildString {
                append(data.str("title").orEmpty()); append(' ')
                append(data.str("description").orEmpty().take(600)); append(' ')
                (data["keywords"] as? JsonArray)?.forEach { k -> append(k.toString()); append(' ') }
            }.lowercase()
            if (NASA_PEOPLE.any { it in text } || NASA_SPACE.none { it in text }) return@mapNotNull null
            val links = (item["links"] as? JsonArray)?.mapNotNull { le ->
                val l = le.obj() ?: return@mapNotNull null
                val href = l.str("href") ?: return@mapNotNull null
                if (l.str("render") != "image") return@mapNotNull null
                NasaLink(href, l.str("rel").orEmpty(), l.int("width"), l.int("height"))
            }.orEmpty()
            val biggest = links.maxByOrNull { it.w } ?: return@mapNotNull null
            if (biggest.w < 1600 || biggest.h <= 0) return@mapNotNull null
            if (biggest.w < biggest.h * 1.3f || biggest.w > biggest.h * 2.6f) return@mapNotNull null
            val jpgs = links.filter { it.href.endsWith(".jpg", ignoreCase = true) && it.rel != "preview" }
            val full = jpgs.filter { it.w in 1600..3000 }.minByOrNull { abs(it.w - 1920) }
                ?: jpgs.filter { it.w in 1600..6000 }.minByOrNull { it.w }
                ?: return@mapNotNull null
            val thumb = links.firstOrNull { it.rel == "preview" }
                ?: links.filter { it.w >= 320 }.minByOrNull { it.w }
                ?: full
            val title = data.str("title")?.trim().orEmpty()
            val who = (data.str("secondary_creator") ?: data.str("center"))?.trim()?.takeIf { it.isNotEmpty() }
            Picture(full.href, thumb.href, listOfNotNull(title.ifEmpty { null }, who, "NASA").joinToString(" · "))
        }
    }

    /** "Title · Author · Licence · Wikimedia Commons", leaving out what is unknown. */
    private fun commonsCredit(title: String, artist: String?, licence: String?): String =
        listOfNotNull(
            title.takeIf { it.isNotBlank() },
            artist?.takeIf { it.isNotBlank() },
            licence?.takeIf { it.isNotBlank() },
            "Wikimedia Commons",
        ).joinToString(" · ")

    /** HTML from Wikimedia metadata (author links etc.) → one line of plain text. */
    private fun plainText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Wikimedia Commons' picture of the day for the last [days] days, from Wikipedia's featured feed:
     * `api.wikimedia.org/feed/v1/wikipedia/en/featured/YYYY/MM/DD`, falling back to
     * `en.wikipedia.org/api/rest_v1/feed/featured/YYYY/MM/DD`. Landscape JPEGs ≥ 1600 px only;
     * the original is fetched as a 1920 px thumbnail. Requests run in parallel within the per-host limits.
     */
    private suspend fun pictureOfTheDay(days: Int): List<Picture> = coroutineScope {
        val today = LocalDate.now(ZoneOffset.UTC)
        val primaryDown = AtomicBoolean(false)
        val failures = AtomicInteger(0)
        val perDay = (0 until days.coerceIn(1, 90)).map { back ->
            async {
                val d = today.minusDays(back.toLong())
                val path = d.toString().replace('-', '/') // ISO yyyy-MM-dd → yyyy/MM/dd
                val primary = "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/$path"
                val fallback = "https://en.wikipedia.org/api/rest_v1/feed/featured/$path"
                val root: JsonObject? = try {
                    if (primaryDown.get()) {
                        getJson(fallback).obj()
                    } else {
                        try {
                            getJson(primary).obj()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            primaryDown.set(true)
                            getJson(fallback).obj()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    null
                }
                root?.obj("image")?.let { potdPicture(it) }
            }
        }.awaitAll()
        val out = perDay.filterNotNull().distinctBy { it.key }
        if (out.isEmpty() && failures.get() > 0) throw IOException("picture of the day unreachable")
        out
    }

    /** One featured-feed "image" object → a [Picture] (null when portrait, small or not a JPEG). */
    private fun potdPicture(img: JsonObject): Picture? {
        val original = img.obj("image") ?: return null
        val url = original.str("source") ?: return null
        val w = original.int("width")
        val h = original.int("height")
        val ext = url.substringBefore('?').substringAfterLast('.').lowercase()
        if (ext != "jpg" && ext != "jpeg") return null
        if (w < 1600 || h <= 0 || w < h * 1.25f || w > h * 2.6f) return null
        val title = img.str("title")?.removePrefix("File:")?.substringBeforeLast('.')?.take(90).orEmpty()
        val artist = img.obj("artist")?.let { it.str("text") ?: it.str("html")?.let { html -> plainText(html) } }?.take(60)
        val licence = img.obj("license")?.str("type")?.take(30)
        return Picture(
            commonsThumb(url, w, FULL_W),
            commonsThumb(url, w, 500),
            commonsCredit(title, artist?.let { oneLine(it) }, licence),
        )
    }

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** Lorem Picsum's catalogue: `picsum.photos/v2/list?page=…&limit=100`, landscape photos only. */
    private suspend fun picsumList(): List<Picture> {
        val out = ArrayList<Picture>()
        for (page in 1..4) {
            val arr = getJson("https://picsum.photos/v2/list?page=$page&limit=100") as? JsonArray ?: break
            arr.forEach { e ->
                val o = e.obj() ?: return@forEach
                val w = o.int("width")
                val h = o.int("height")
                if (h <= 0 || w < h * 1.3f) return@forEach
                val id = o.str("id") ?: return@forEach
                val author = o.str("author").orEmpty()
                out += Picture(
                    "https://picsum.photos/id/$id/1920/1080.jpg",
                    "https://picsum.photos/id/$id/400/225.jpg",
                    "Photo: $author · Unsplash",
                )
            }
        }
        return out
    }

    // ---- network ------------------------------------------------------------------

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrNull().orEmpty()

    private fun gateFor(host: String): Semaphore = hostGates.getOrPut(host) {
        Semaphore(
            when {
                host == "commons.wikimedia.org" -> 1 // Action API: one request at a time
                host.endsWith("wikimedia.org") -> 2  // upload / api.wikimedia.org: ≤ 2 parallel
                host.endsWith("wikipedia.org") -> 2  // REST API fallback for the picture of the day
                else -> 3
            }
        )
    }

    private fun minGapMs(host: String): Long = when (host) {
        "commons.wikimedia.org" -> 400L
        else -> 0L
    }

    private suspend fun pace(host: String) {
        val gap = minGapMs(host)
        if (gap <= 0) return
        val wait = (hostLast[host] ?: 0L) + gap - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        hostLast[host] = System.currentTimeMillis()
    }

    /**
     * GET with per-host limits and pacing, retries with backoff on 429 / 5xx / network errors
     * (honouring Retry-After), and short negative caching of failing URLs and hosts.
     */
    private suspend fun <T> request(url: String, read: suspend (HttpURLConnection) -> T): T {
        val host = hostOf(url)
        var attempt = 0
        while (true) {
            val now = System.currentTimeMillis()
            failedUrls[url]?.let { until -> if (until > now) throw IOException("recently failed, skipped: ${url.take(160)}") }
            hostCooldown[host]?.let { until -> if (until > now) throw IOException("$host cooling down for ${(until - now) / 1000} s") }
            currentCoroutineContext().ensureActive()
            var retryIn = 0L
            try {
                return gateFor(host).withPermit {
                    pace(host)
                    val c = open(url)
                    try {
                        read(c)
                    } finally {
                        c.disconnect()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                Log.w(TAG, "HTTP ${e.code} (attempt ${attempt + 1}) ${url.take(200)}")
                val t = System.currentTimeMillis()
                if (e.code == 429 || e.code == 503 || e.code in 500..599) {
                    val backoff = e.retryAfterMs ?: (5_000L shl attempt)
                    if (attempt >= 2 || backoff > 30_000L) {
                        if (e.code == 429 || e.code == 503) {
                            hostCooldown[host] = t + backoff.coerceIn(30_000L, 15 * 60_000L)
                            Log.w(TAG, "$host rate-limited, pausing ${backoff.coerceIn(30_000L, 15 * 60_000L) / 1000} s")
                        }
                        failedUrls[url] = t + 5 * 60_000L
                        throw e
                    }
                    retryIn = backoff
                } else {
                    // 403 usually = User-Agent / Referer trouble, 404 = gone.
                    failedUrls[url] = t + if (e.code == 404 || e.code == 410) 6 * 3_600_000L else 10 * 60_000L
                    throw e
                }
            } catch (e: Exception) {
                Log.w(TAG, "${e.javaClass.simpleName}: ${e.message} (attempt ${attempt + 1}) ${url.take(200)}")
                if (attempt >= 1) {
                    failedUrls[url] = System.currentTimeMillis() + 2 * 60_000L
                    throw e
                }
                retryIn = 2_000L
            }
            attempt++
            delay(retryIn)
        }
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", ua)
                if (u.host.endsWith("wikimedia.org")) setRequestProperty("Referer", "https://commons.wikimedia.org/")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("redirect without location: $url")
                u = URL(u, loc)
            } else {
                if (code !in 200..299) {
                    val retry = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                    c.disconnect()
                    throw HttpException(code, retry, u.toString().take(200))
                }
                return c
            }
        }
        throw IOException("too many redirects: $url")
    }

    private suspend fun getJson(url: String): JsonElement = request(url) { c ->
        json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() })
    }

    private fun fileFor(url: String, folder: File): File {
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".jpg"
        return File(folder, name)
    }

    /** Downloads [url] into [folder] once (one download per file at a time); returns the cached file. */
    private suspend fun download(url: String, folder: File): File {
        val file = fileFor(url, folder)
        if (file.exists()) return file
        val lock = fileLocks.getOrPut(file.name) { Mutex() }
        return lock.withLock {
            if (file.exists()) return@withLock file
            downloadGate.withPermit {
                request<Unit>(url) { c ->
                    val type = c.contentType.orEmpty()
                    if (type.startsWith("text/") || type.contains("json")) throw IOException("not an image ($type): ${url.take(160)}")
                    if (c.contentLengthLong > MAX_DOWNLOAD) throw IOException("too large: ${url.take(160)}")
                    val tmp = File.createTempFile("halo_dl_", ".part", folder)
                    try {
                        c.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_DOWNLOAD) throw IOException("too large: ${url.take(160)}")
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        if (!tmp.renameTo(file)) throw IOException("rename failed")
                    } finally {
                        tmp.delete()
                    }
                    Unit
                }
            }
            trim(folder, if (folder == thumbDir) 40L * 1024 * 1024 else 150L * 1024 * 1024)
            file
        }
    }

    /**
     * Decodes [file] so it exactly covers [maxW]×[maxH] (never larger): inSampleSize first, then
     * one filtered scale. [hardware] moves the pixels to the GPU (thumbnails); full-size
     * backgrounds stay software bitmaps so they can be composited (scrim baking) and are prepared
     * for drawing here, off the main thread.
     */
    private fun decode(file: File, maxW: Int, maxH: Int, requireLandscape: Boolean, hardware: Boolean): Decoded {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return Decoded.Invalid
            if (requireLandscape && (w < h * 1.15f || w < 1000)) return Decoded.Unsuitable
            val scale = maxOf(maxW.toFloat() / w, maxH.toFloat() / h).coerceAtMost(1f)
            val tw = (w * scale).roundToInt().coerceAtLeast(1)
            val th = (h * scale).roundToInt().coerceAtLeast(1)
            var sample = 1
            while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return Decoded.Invalid
            if (abs(bmp.width - tw) > 2 || abs(bmp.height - th) > 2) {
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            if (hardware) {
                val hw = runCatching { bmp.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
                if (hw != null) {
                    bmp.recycle()
                    return Decoded.Ok(hw.asImageBitmap())
                }
            }
            bmp.prepareToDraw()
            return Decoded.Ok(bmp.asImageBitmap())
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.javaClass.simpleName} ${file.name}")
            return Decoded.Failed
        }
    }

    /** Keeps a cache folder under [limit] bytes (oldest first). */
    private fun trim(folder: File, limit: Long) {
        val files = folder.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < limit) break
            total -= f.length()
            f.delete()
        }
    }

    // ---- json helpers ---------------------------------------------------------------

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String): Int = str(k)?.toDoubleOrNull()?.toInt() ?: 0
}
