package app.halo.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Lists launchable apps and keeps the list fresh when apps are installed or removed.
 *
 * - Everything runs off the main thread in ONE loader job: refresh requests only mark the list
 *   dirty, the loader re-runs until it is clean (never cancelled, so work is never thrown away).
 * - Entries are built in parallel (bounded), then each app's Halo card is pre-rendered once
 *   ([AppCards]). If the cards take longer than [CARD_BUDGET_MS] the list is published without them
 *   first and again once they are ready. Each finished entry is cached as soon as its card is done.
 * - Cache hits reuse the identical [AppEntry], so an unchanged list compares equal and the
 *   StateFlow does not emit (no recomposition, no texture re-upload).
 * - Memory: banner/icon/logo/card are converted to HARDWARE bitmaps (GPU only, no Java-heap copy);
 *   the banner is kept downscaled. Only the small extracted logo parts stay in software memory, to
 *   re-render cards with a custom name/colour ([customCard]).
 */
class AppRepository(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    /** Finished entries (with cards) by [AppEntry.key], stamped with the package's lastUpdateTime. */
    private class Cached(val stamp: Long, val entry: AppEntry)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** Extracted logo parts per app key, to re-render cards with a custom name/colour. */
    private val parts = ConcurrentHashMap<String, AppCards.Parts>()

    private data class CustomKey(val key: String, val name: String?, val color: Int?)

    /** Small LRU of custom cards. Access-ordered; guarded by synchronizing on itself. */
    private val customCards = object : LinkedHashMap<CustomKey, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CustomKey, ImageBitmap>?): Boolean = size > 16
    }

    /** Loader state, guarded by [lock]. */
    private val lock = Any()
    private var dirty = false
    private var running = false
    private var lastLocale: String? = null

    /** Bounds the parallel bitmap work so the UI thread keeps its CPU share on the weak TV SoC. */
    private val workers = Semaphore(3)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
    }

    init {
        runCatching { launcherApps?.registerCallback(callback, Handler(Looper.getMainLooper())) }
        refresh()
    }

    fun close() {
        runCatching { launcherApps?.unregisterCallback(callback) }
    }

    private fun changed(vararg packages: String) {
        val set = packages.toHashSet()
        cache.entries.removeAll { it.value.entry.packageName in set }
        parts.keys.removeAll { it.substringBefore('/') in set }
        synchronized(customCards) { customCards.keys.removeAll { it.key.substringBefore('/') in set } }
        refresh()
    }

    /**
     * Requests a reload. Cheap when nothing changed (cache hits only).
     * [full] = true drops every cached entry and card (labels and cards are rebuilt). A changed
     * system locale is detected automatically and also triggers a full reload.
     */
    fun refresh(full: Boolean = false) {
        val locale = runCatching { appContext.resources.configuration.locales[0].toLanguageTag() }.getOrNull()
        synchronized(lock) {
            if (full || (lastLocale != null && locale != lastLocale)) {
                cache.clear()
                parts.clear()
                synchronized(customCards) { customCards.clear() }
            }
            lastLocale = locale
            dirty = true
            if (running) return
            running = true
        }
        scope.launch(Dispatchers.IO) {
            delay(150) // coalesce bursts of package events
            while (true) {
                synchronized(lock) {
                    if (!dirty) {
                        running = false
                        return@launch
                    }
                    dirty = false
                }
                try {
                    load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    synchronized(lock) { running = false }
                    throw e
                } catch (t: Throwable) {
                    // Keep the loader alive; the next refresh tries again.
                }
            }
        }
    }

    /** A built entry plus the sources its card is rendered from (only for freshly built entries). */
    private class Built(
        val entry: AppEntry,
        val stamp: Long,
        val banner: Bitmap? = null,
        val icon: Drawable? = null,
        /** Built just now (not from the cache), so its card still has to be rendered. */
        val fresh: Boolean = false,
    )

    private fun stampOf(packageName: String): Long =
        runCatching { pm.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)

    private suspend fun load() = coroutineScope {
        val found = query()
        val built = found.map { (ri, tv) ->
            async(Dispatchers.IO) {
                val key = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToShortString()
                val stamp = stampOf(ri.activityInfo.packageName)
                val cached = cache[key]
                if (cached != null && cached.stamp == stamp) Built(cached.entry, stamp)
                else workers.withPermit { runCatching { build(ri, tv, stamp) }.getOrNull() }
            }
        }.awaitAll().filterNotNull()
            .sortedWith(compareBy<Built> { KnownApps.rank(it.entry.packageName) }.thenBy { it.entry.label.lowercase() })

        // Forget apps that are gone.
        val keys = built.mapTo(HashSet()) { it.entry.key }
        cache.keys.retainAll(keys)
        parts.keys.retainAll(keys)

        val todo = built.filter { it.fresh }
        if (todo.isEmpty()) {
            publish(built)
            return@coroutineScope
        }
        val rendering = async(Dispatchers.Default) {
            todo.map { b ->
                async {
                    workers.withPermit {
                        val done = renderCard(b)
                        // Cache right away, so the work survives even if a later load starts over.
                        cache[done.entry.key] = Cached(done.stamp, done.entry)
                        done.entry.key to done
                    }
                }
            }.awaitAll().toMap()
        }
        // Cards ready quickly: show the list once, with cards. Otherwise show it now and add cards after.
        val quick = withTimeoutOrNull(CARD_BUDGET_MS) { rendering.await() }
        if (quick == null) publish(built)
        val done = quick ?: rendering.await()
        publish(built.map { b -> done[b.entry.key] ?: b })
    }

    /** Renders the card of a freshly built entry and drops the big card sources afterwards. */
    private fun renderCard(b: Built): Built {
        val card = runCatching {
            val p = AppCards.extract(b.entry.packageName, b.entry.label, b.banner, b.icon)
            parts[b.entry.key] = p
            AppCards.compose(appContext, b.entry.packageName, p)
        }.getOrNull()
        val entry = if (card != null) b.entry.copy(card = toHardware(card.card).asImageBitmap(), brand = Color(card.brand)) else b.entry
        return Built(entry, b.stamp)
    }

    private fun publish(list: List<Built>) {
        _apps.value = list.map { it.entry }
        _loaded.value = true
    }

    /** The app's automatic card (as rendered at load), independent of any custom card on [AppEntry.card]. */
    fun baseCard(key: String): ImageBitmap? = cache[key]?.entry?.card

    /**
     * The app's card with a custom [name] and/or [color] (ARGB), rendered on the calling thread on a
     * cache miss (~20–40 ms) and kept in a small LRU. With neither set, the automatic card.
     * Null if the app's logo parts are not loaded yet.
     */
    fun customCard(app: AppEntry, name: String?, color: Int?): ImageBitmap? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        if (n == null && color == null) return baseCard(app.key) ?: app.card
        val ck = CustomKey(app.key, n, color)
        synchronized(customCards) { customCards[ck] }?.let { return it }
        val p = parts[app.key] ?: return null
        val bmp = runCatching {
            toHardware(AppCards.compose(appContext, app.packageName, p, n, color).card).asImageBitmap()
        }.getOrNull() ?: return null
        synchronized(customCards) { customCards[ck] = bmp }
        return bmp
    }

    private fun query(): List<Pair<ResolveInfo, Boolean>> {
        val own = appContext.packageName
        val seen = HashSet<String>()
        val out = ArrayList<Pair<ResolveInfo, Boolean>>()

        fun add(ri: ResolveInfo, tv: Boolean) {
            val ai = ri.activityInfo ?: return
            if (ai.packageName == own) return
            if (!seen.add(ai.packageName)) return // one tile per app
            out += ri to tv
        }

        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        ).forEach { add(it, tv = true) }
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).forEach { add(it, tv = false) }

        // Google TV hides "Settings" from the app list; add it so it can be pinned.
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0) }.getOrNull()?.let { add(it, tv = false) }
        return out
    }

    private fun build(ri: ResolveInfo, tv: Boolean, stamp: Long): Built {
        val ai = ri.activityInfo
        val component = ComponentName(ai.packageName, ai.name)
        val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }

        val bannerDrawable: Drawable? = runCatching { pm.getActivityBanner(component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(ai.packageName) }.getOrNull()
        // Full size for the card's logo extraction (released after the card is rendered) ...
        val bannerBitmap = bannerDrawable?.let { d -> runCatching { d.toBitmap(640, 360, Bitmap.Config.ARGB_8888) }.getOrNull() }
        val bannerTint = bannerBitmap?.let { bmp ->
            runCatching {
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val sw = p.vibrantSwatch ?: p.dominantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch
                sw?.let { Color(it.rgb) }
            }.getOrNull()
        }
        // ... and a smaller GPU copy for the Banner tile style.
        val bannerImage = bannerBitmap?.let { b ->
            runCatching { toHardware(Bitmap.createScaledBitmap(b, 400, 225, true)).asImageBitmap() }.getOrNull()
        }

        val iconDrawable = runCatching { ri.loadIcon(pm) }.getOrNull()
        val iconBitmap = iconDrawable?.let { d -> runCatching { d.toBitmap(192, 192, Bitmap.Config.ARGB_8888) }.getOrNull() }
        // One palette pass over the icon serves both the glass tint and the legacy logo box colour.
        val iconPalette = iconBitmap?.let { bmp -> runCatching { Palette.from(bmp).maximumColorCount(16).generate() }.getOrNull() }
        val tint = iconPalette?.let { p ->
            (p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.let { Color(it.rgb) }
        } ?: KnownApps.Slate
        val (logoBitmap, logoBg) = buildLogo(iconDrawable, iconBitmap, iconPalette)
        val iconImage = iconBitmap?.let { toHardware(it).asImageBitmap() }
        val logoImage = when {
            logoBitmap == null -> null
            logoBitmap === iconBitmap -> iconImage
            else -> toHardware(logoBitmap).asImageBitmap()
        }

        val entry = AppEntry(
            key = component.flattenToShortString(),
            packageName = ai.packageName,
            label = label,
            banner = bannerImage,
            icon = iconImage,
            autoTint = tint,
            bannerTint = bannerTint,
            logo = logoImage,
            logoBg = logoBg,
            component = component,
            isTvApp = tv,
        )
        return Built(entry, stamp, bannerBitmap, iconDrawable, fresh = true)
    }

    /**
     * Apple-TV style logo for the "box with logo" tile.
     * Adaptive icons: the foreground layer (inner 2/3 safe zone) + the background layer's colour.
     * Legacy icons: the icon itself + its dominant colour.
     */
    private fun buildLogo(d: Drawable?, iconBitmap: Bitmap?, palette: Palette?): Pair<Bitmap?, Color?> {
        if (d is AdaptiveIconDrawable) {
            val result = runCatching {
                val size = 288
                val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.foreground?.let { fg ->
                    fg.setBounds(0, 0, size, size)
                    fg.draw(android.graphics.Canvas(full))
                }
                val inset = size / 6 // keep the 72dp safe zone of the 108dp canvas
                val logo = Bitmap.createBitmap(full, inset, inset, size - 2 * inset, size - 2 * inset)
                val bgColor = when (val bg = d.background) {
                    is ColorDrawable -> Color(bg.color)
                    null -> null
                    else -> runCatching {
                        val b = bg.toBitmap(48, 48)
                        val p = Palette.from(b).maximumColorCount(8).generate()
                        (p.dominantSwatch ?: p.vibrantSwatch)?.let { Color(it.rgb) }
                    }.getOrNull()
                }
                logo to bgColor
            }.getOrNull()
            if (result != null) return result
        }
        val bmp = iconBitmap ?: return null to null
        val bg = palette?.let { p -> (p.darkVibrantSwatch ?: p.dominantSwatch ?: p.darkMutedSwatch)?.let { Color(it.rgb) } }
        return bmp to bg
    }

    private companion object {
        /** How long the first list may wait for its cards before it is shown without them. */
        const val CARD_BUDGET_MS = 1200L

        /**
         * GPU-only copy (no second copy in the Java heap). Such bitmaps can only be drawn by the
         * hardware renderer (Compose is fine), never read or drawn into a software Canvas.
         */
        fun toHardware(b: Bitmap): Bitmap =
            runCatching { b.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: b
    }
}
