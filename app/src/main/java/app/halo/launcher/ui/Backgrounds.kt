package app.halo.launcher.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.halo.launcher.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.data.Glyph
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import app.halo.launcher.wallpaper.Picture
import app.halo.launcher.wallpaper.Wallpaper
import app.halo.launcher.wallpaper.WallpaperCollection
import app.halo.launcher.wallpaper.WallpaperRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// ==== Photo background =============================================================

/**
 * A photo background: cross-fade between pictures and a readability scrim that is baked into
 * the bitmap once (off the main thread), so a frame costs a single bitmap draw.
 *
 * [animate] = a very slow Ken Burns pan (transform only, read inside graphicsLayer). It keeps the
 * window redrawing, so it is off by default and additionally needs the motion setting.
 */
@Composable
fun PhotoBackdrop(wallpaper: Wallpaper, modifier: Modifier = Modifier, animate: Boolean = false) {
    val halo = LocalHalo.current
    val dark = halo.colors.isDark
    val kenBurns = animate && halo.motion
    val progress: State<Float> = if (kenBurns) {
        rememberInfiniteTransition(label = "kenburns").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "kb",
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    // Own cross-fade: the incoming picture fades in over the outgoing one (alpha applied per draw,
    // no offscreen buffer); afterwards the outgoing one is dropped.
    var shown by remember { mutableStateOf(wallpaper) }
    var previous by remember { mutableStateOf<Wallpaper?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(wallpaper) {
        if (wallpaper == shown) return@LaunchedEffect
        previous = shown
        shown = wallpaper
        fade.snapTo(0f)
        fade.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        previous = null
    }

    Box(modifier.clipToBounds()) {
        val prev = previous
        val cur = shown
        // One call site, so a layer (and its baked bitmap) survives moving from "current" to "previous".
        for (w in listOfNotNull(prev, cur)) {
            key(w.key) { PhotoLayer(w, dark, progress, kenBurns, if (w === cur && prev != null) fade else null) }
        }
        if (cur.credit.isNotBlank()) {
            BasicText(
                cur.credit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.px, bottom = 18.px)
                    .width(900.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(14, FontWeight.Medium, Color.White.copy(alpha = 0.55f)).copy(textAlign = TextAlign.End),
            )
        }
    }
}

@Composable
private fun PhotoLayer(
    w: Wallpaper,
    dark: Boolean,
    progress: State<Float>,
    kenBurns: Boolean,
    fade: Animatable<Float, AnimationVector1D>?,
) {
    // Picture + scrim → one GPU bitmap, made once per (picture, theme) on a background thread.
    val baked by produceState<ImageBitmap?>(null, w.image, dark) {
        value = withContext(Dispatchers.Default) { runCatching { bakeScrim(w.image, dark) }.getOrNull() }
    }
    val dir = remember(w.key) { if (w.key.hashCode() and 1 == 0) 1f else -1f }
    val layer = Modifier
        .fillMaxSize()
        .graphicsLayer {
            if (kenBurns) {
                val p = progress.value
                val s = 1.04f + 0.06f * p
                scaleX = s
                scaleY = s
                translationX = (p - 0.5f) * size.width * 0.025f * dir
                translationY = (0.5f - p) * size.height * 0.012f
            }
            if (fade != null) {
                alpha = fade.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
        }
    val img = baked
    if (img != null) {
        Image(bitmap = img, contentDescription = null, modifier = layer, contentScale = ContentScale.Crop)
    } else {
        // Until the baked copy is ready: raw picture + live scrim.
        Box(layer) {
            Image(bitmap = w.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val a = scrimAlphas(dark)
                        val scrim = Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = a[0]),
                            0.45f to Color.Black.copy(alpha = a[1]),
                            1f to Color.Black.copy(alpha = a[2]),
                        )
                        onDrawBehind { drawRect(scrim) }
                    }
            )
        }
    }
}

/** Scrim alpha at the top, at 45 % and at the bottom (darker where the rows are). */
private fun scrimAlphas(dark: Boolean): FloatArray =
    if (dark) floatArrayOf(0.35f, 0.15f, 0.78f) else floatArrayOf(0.12f, 0.05f, 0.45f)

/** Crops [src] to exactly 1920×1080, draws the scrim over it and moves the result to the GPU. */
private fun bakeScrim(src: ImageBitmap, dark: Boolean): ImageBitmap {
    val w = WallpaperRepository.FULL_W
    val h = WallpaperRepository.FULL_H
    val raw = src.asAndroidBitmap()
    val soft = if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val k = max(w / soft.width.toFloat(), h / soft.height.toFloat())
    val dw = soft.width * k
    val dh = soft.height * k
    canvas.drawBitmap(
        soft,
        null,
        RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
    val a = scrimAlphas(dark)
    val colors = IntArray(3) { android.graphics.Color.argb((a[it] * 255).roundToInt(), 0, 0, 0) }
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    if (soft !== raw) soft.recycle()
    val hw = runCatching { out.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
    return if (hw != null) {
        out.recycle()
        hw.asImageBitmap()
    } else {
        out.prepareToDraw()
        out.asImageBitmap()
    }
}

// ==== Picker =======================================================================

private val shuffleIcon = IconSpec(
    listOf(
        "M3 7h3.5c2 0 3.2 1 4.3 2.6l2.4 4.8c1.1 1.6 2.3 2.6 4.3 2.6H21",
        "M3 17h3.5c1.6 0 2.7-.6 3.6-1.7M14.4 8.7c.9-1.1 2-1.7 3.6-1.7H21",
        "M18.5 4.5L21 7l-2.5 2.5M18.5 14.5L21 17l-2.5 2.5",
    )
)

/**
 * Choose the profile's background: a collection (top row), how often it changes (chips) and
 * optionally one exact picture (grid). Remote / D-pad only.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackgroundsScreen(
    repo: WallpaperRepository,
    selected: String,
    minutes: Int,
    pin: String?,
    mediaBackdrop: Boolean,
    current: Wallpaper?,
    onSelect: (collection: String) -> Unit,
    onPin: (collection: String, key: String) -> Unit,
    onRotate: () -> Unit,
    onInterval: (minutes: Int) -> Unit,
    onMediaBackdrop: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    /** Opens the "send from your phone" panel for own pictures. */
    onAddFromPhone: () -> Unit = {},
) {
    val c = LocalHalo.current.colors
    val cols = WallpaperRepository.collections
    val col = WallpaperRepository.of(selected)
    val isScene = col.id == WallpaperRepository.SCENE
    val selIndex = cols.indexOfFirst { it.id == col.id }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    // Start at the list's beginning (keeps the 120 px gutter) unless the selected card would not be
    // composed there; focus requests only reach composed items.
    val colState = rememberLazyListState(initialFirstVisibleItemIndex = if (selIndex <= 4) 0 else selIndex - 1)
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    // Own pictures change while the phone sends them: reload that list on every change.
    val mine by app.halo.launcher.wallpaper.MyMedia.version.collectAsStateWithLifecycle()
    val packs by repo.packs.collectAsStateWithLifecycle()
    val packVersion by repo.packVersion.collectAsStateWithLifecycle()
    val pictures by produceState<List<Picture>?>(null, col.id, if (col.id == WallpaperRepository.MINE) mine else 0L, packVersion) {
        value = null
        value = if (col.id == WallpaperRepository.SCENE) {
            emptyList()
        } else {
            runCatching { repo.pictures(col.id) }.getOrDefault(emptyList())
        }
    }
    var hint by remember { mutableStateOf<String?>(null) }
    val favKeys by produceState(emptySet<String>(), packVersion) {
        value = withContext(Dispatchers.IO) { repo.favouriteKeys() }
    }
    /** The picture whose options (hold OK) are open. */
    var menuFor by remember { mutableStateOf<Picture?>(null) }
    val holdHint = stringResource(R.string.bg_hold_hint)

    // Light scrim: the chosen picture stays visible as a preview, but the text on it is readable.
    // ScrollPadding keeps focused cards 120 px from the screen edges (aligned with the header).
    Box(Modifier.fillMaxSize()) {
    ScrollPadding(120.px) {
    Column(
        Modifier
            .fillMaxSize()
            .readabilityScrim(strength = 0.8f)
            .padding(top = 64.px, bottom = 36.px),
    ) {
        // ---- Header ------------------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 120.px),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    stringResource(R.string.bg_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(56, FontWeight.Bold, c.text, tracking = -0.02f),
                )
                BasicText(
                    stringResource(R.string.bg_subtitle),
                    Modifier.padding(top = 8.px, end = 24.px),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(22, FontWeight.Medium, c.sub),
                )
            }
            Row(
                Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TogglePill(
                    stringResource(R.string.bg_song_artwork),
                    checked = mediaBackdrop,
                    onToggle = onMediaBackdrop,
                    icon = Glyph.music.spec,
                )
                PillButton(stringResource(R.string.upload_add_pictures), onAddFromPhone, icon = Icons.camera)
                if (!isScene) PillButton(stringResource(R.string.bg_next), onNext, icon = Icons.chevronRight)
                // Streamed by default; "Download to TV" keeps the collection on the TV (works offline).
                if (!isScene && col.id != WallpaperRepository.MINE && col.id != WallpaperRepository.FAVS) {
                    val st = packs[col.id]
                    val downloaded = remember(col.id, packVersion) { repo.isDownloaded(col.id) }
                    val complete = remember(col.id, packVersion) { repo.isPackComplete(col.id) }
                    when {
                        st?.queued == true -> PillButton(stringResource(R.string.bg_waiting), {}, icon = Icons.chevronDown)
                        st?.running == true -> PillButton(stringResource(R.string.bg_downloading, st.done, st.total), {}, icon = Icons.chevronDown)
                        !complete -> PillButton(stringResource(R.string.bg_download), { repo.downloadPack(col.id) }, icon = Icons.chevronDown)
                    }
                    if (downloaded && st?.running != true && st?.queued != true) {
                        PillButton(stringResource(R.string.bg_remove_pack), { repo.removePack(col.id) }, icon = Icons.trash)
                    }
                }
                PillButton(stringResource(R.string.done), onBack, primary = true, icon = Icons.check)
            }
        }

        // ---- Collections -------------------------------------------------------------
        LazyRow(
            state = colState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.px)
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 120.px, vertical = 22.px),
            horizontalArrangement = Arrangement.spacedBy(26.px),
        ) {
            itemsIndexed(cols, key = { _, item -> item.id }) { i, item ->
                CollectionCard(
                    repo = repo,
                    col = item,
                    selected = item.id == col.id,
                    live = if (item.id == col.id) current else null,
                    focusRequester = if (i == selIndex) first else null,
                    onClick = { onSelect(item.id) },
                )
            }
        }

        // ---- Interval ----------------------------------------------------------------
        Row(
            Modifier
                .padding(start = 120.px, end = 120.px, top = 4.px)
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.px),
        ) {
            BasicText(
                stringResource(R.string.bg_change),
                Modifier.width(210.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(22, FontWeight.SemiBold, if (isScene) c.sub.copy(alpha = 0.6f) else c.text),
            )
            WallpaperRepository.INTERVALS.forEach { m ->
                PillButton(intervalText(m), { onInterval(m) }, primary = m == minutes && !isScene, enabled = !isScene, height = 52)
            }
        }

        // ---- Pictures ----------------------------------------------------------------
        val list = pictures
        val title = collectionTitle(col)
        val packState = packs[col.id]
        val info = when {
            isScene -> stringResource(R.string.bg_scene_info)
            packState?.queued == true -> stringResource(R.string.bg_pack_waiting, title)
            packState?.running == true -> stringResource(R.string.bg_pack_downloading, title, packState.done, packState.total)
            packState?.failed == true -> stringResource(R.string.bg_pack_failed, title)
            col.id == WallpaperRepository.FAVS && list?.isEmpty() == true -> stringResource(R.string.bg_favs_empty)
            list == null -> stringResource(R.string.bg_loading, title)
            list.isEmpty() -> stringResource(R.string.bg_empty, title)
            else -> hint ?: pluralResource(R.plurals.bg_info, list.size, title, list.size, collectionDescription(col))
        }
        BasicText(
            info,
            Modifier.padding(start = 120.px, end = 120.px, top = 26.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(20, FontWeight.Medium, c.sub),
        )
        if (!isScene && list != null && list.isNotEmpty()) {
            key(col.id) {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(430.px)
                        .focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 120.px, vertical = 20.px),
                    horizontalArrangement = Arrangement.spacedBy(22.px),
                    verticalArrangement = Arrangement.spacedBy(22.px),
                ) {
                    item(key = "rotate", span = { GridItemSpan(maxLineSpan) }) {
                        RotateCard(
                            count = list.size,
                            minutes = minutes,
                            active = pin == null,
                            onFocus = { hint = null },
                            onClick = onRotate,
                        )
                    }
                    items(list, key = { it.key }) { p ->
                        ThumbCard(
                            repo = repo,
                            picture = p,
                            pinned = p.key == pin,
                            favourite = col.id != WallpaperRepository.FAVS && p.key in favKeys,
                            onFocus = { hint = listOf(p.credit, holdHint).filter { it.isNotBlank() }.joinToString("  ·  ") },
                            onClick = { onPin(col.id, p.key) },
                            onLongClick = { menuFor = p },
                        )
                    }
                }
            }
        }
    }
    }
    menuFor?.let { p ->
        PictureMenu(
            repo = repo,
            picture = p,
            inFavourites = col.id == WallpaperRepository.FAVS,
            favourite = p.key in favKeys,
            onShow = {
                menuFor = null
                onPin(col.id, p.key)
            },
            onRemove = {
                menuFor = null
                if (p.key == pin) onRotate()
                repo.removePicture(col.id, p)
            },
            onClose = { menuFor = null },
        )
    }
    }
}

/** Options for one picture (hold OK on it): show it, favourite / unfavourite, remove it. */
@Composable
private fun PictureMenu(
    repo: WallpaperRepository,
    picture: Picture,
    inFavourites: Boolean,
    favourite: Boolean,
    onShow: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    androidx.activity.compose.BackHandler { onClose() }
    val img by produceState(repo.cachedThumbnail(picture.key), picture.key) {
        if (value == null) value = repo.thumbnail(picture)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF03050C).copy(alpha = 0.7f))
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .glass(RoundedCornerShape(36.px), strong = true, fillAlpha = 0.92f)
                .padding(40.px),
            horizontalArrangement = Arrangement.spacedBy(40.px),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(480.px, 270.px).clip(RoundedCornerShape(22.px)).background(c.pill)) {
                img?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.width(420.px), verticalArrangement = Arrangement.spacedBy(14.px)) {
                if (picture.credit.isNotBlank()) {
                    BasicText(
                        picture.credit,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = hstyle(19, FontWeight.Medium, c.sub),
                    )
                }
                PillButton(
                    stringResource(R.string.bg_show_picture),
                    onShow,
                    primary = true,
                    icon = Icons.check,
                    focusRequester = first,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!inFavourites) {
                    PillButton(
                        stringResource(if (favourite) R.string.bg_unfavourite else R.string.bg_favourite),
                        {
                            if (busy) return@PillButton
                            if (favourite) {
                                repo.removeFavourite(picture.key)
                                onClose()
                            } else {
                                busy = true
                                scope.launch {
                                    repo.addFavourite(picture)
                                    busy = false
                                    onClose()
                                }
                            }
                        },
                        icon = if (favourite) Icons.close else app.halo.launcher.data.Glyph.heart.spec,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PillButton(
                    stringResource(if (inFavourites) R.string.bg_unfavourite else R.string.bg_remove_picture),
                    onRemove,
                    icon = Icons.trash,
                    modifier = Modifier.fillMaxWidth(),
                )
                PillButton(stringResource(R.string.cancel), onClose, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CollectionCard(
    repo: WallpaperRepository,
    col: WallpaperCollection,
    selected: Boolean,
    live: Wallpaper?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(26.px)
    val packs by repo.packs.collectAsStateWithLifecycle()
    val packVersion by repo.packVersion.collectAsStateWithLifecycle()
    val downloaded = remember(col.id, packVersion) {
        col.id != WallpaperRepository.SCENE && col.id != WallpaperRepository.MINE && col.id != WallpaperRepository.FAVS && repo.isDownloaded(col.id)
    }
    val pack = packs[col.id]
    val preview by produceState<ImageBitmap?>(null, col.id, packVersion) {
        if (col.id != WallpaperRepository.SCENE) {
            var wait = 10_000L
            repeat(3) {
                value = repo.preview(col.id)
                if (value != null) return@produceState
                delay(wait)
                wait *= 3
            }
        }
    }
    Column(
        Modifier
            .width(256.px)
            .control(st, onClick, focusRequester = focusRequester, focusScale = 1.07f),
    ) {
        Box(
            Modifier
                .size(256.px, 144.px)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF2E6FD8), Color(0xFF7BC8F0), Color(0xFF9BE3B0))))
                .then(
                    if (st.focused || selected) {
                        Modifier.border(if (st.focused) 5.px else 3.px, if (st.focused) c.cta else c.aqua, shape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            val img = live?.image ?: preview
            if (img != null) {
                Image(bitmap = img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                HaloIcon(col.icon, 52.px, Color.White.copy(alpha = 0.92f), strokeWidth = 1.6f)
            }
            if (st.focused) Box(Modifier.fillMaxSize().drawBehind { drawSheen(c.sheen) })
            if (selected) CheckBadge(Modifier.align(Alignment.TopEnd).padding(10.px))
            // Not on the TV yet: a download badge, or the download progress.
            if (downloaded || pack?.running == true || pack?.queued == true) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.px)
                        .clip(RoundedCornerShape(16.px))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 12.px, vertical = 6.px),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pack?.queued == true) {
                        BasicText(stringResource(R.string.bg_waiting), style = hstyle(16, FontWeight.Bold, Color.White))
                    } else if (pack?.running == true) {
                        BasicText(
                            stringResource(R.string.bg_downloading, pack.done, pack.total),
                            style = hstyle(16, FontWeight.Bold, Color.White),
                        )
                    } else {
                        // On the TV (works offline).
                        HaloIcon(Icons.chevronDown, 18.px, Color.White)
                        BasicText(
                            stringResource(R.string.bg_on_tv),
                            Modifier.padding(start = 6.px),
                            style = hstyle(16, FontWeight.Bold, Color.White),
                        )
                    }
                }
            }
        }
        BasicText(
            collectionTitle(col),
            Modifier.padding(top = 12.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(22, if (selected) FontWeight.Bold else FontWeight.SemiBold, c.text),
        )
    }
}

@Composable
private fun RotateCard(count: Int, minutes: Int, active: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(26.px)
    LaunchedEffect(st.focused) { if (st.focused) onFocus() }
    Box(
        Modifier
            .width(300.px)
            .height(382.px)
            .control(st, onClick, focusScale = 1.05f)
            .glass(shape, strong = true, shadow = false)
            .then(
                if (st.focused || active) {
                    Modifier.border(if (st.focused) 5.px else 3.px, if (st.focused) c.cta else c.aqua, shape)
                } else {
                    Modifier
                }
            )
            .padding(28.px),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            HaloIcon(shuffleIcon, 56.px, c.text, strokeWidth = 1.8f)
            BasicText(
                stringResource(R.string.bg_rotate),
                Modifier.padding(top = 18.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(30, FontWeight.Bold, c.text),
            )
            BasicText(
                when {
                    minutes == 0 -> pluralResource(R.plurals.bg_rotate_keep, count, count)
                    minutes >= 1440 -> pluralResource(R.plurals.bg_rotate_daily, count, count)
                    else -> pluralResource(R.plurals.bg_rotate_every, count, count, intervalText(minutes))
                },
                Modifier.padding(top = 6.px),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(19, FontWeight.Medium, c.sub),
            )
        }
        if (active) CheckBadge(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ThumbCard(
    repo: WallpaperRepository,
    picture: Picture,
    pinned: Boolean,
    favourite: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(20.px)
    // Loaded lazily per visible cell; cancelled when the cell leaves the screen.
    val img by produceState(repo.cachedThumbnail(picture.key), picture.key) {
        // A few patient retries (the repository backs off on rate limits).
        var wait = 5_000L
        repeat(3) {
            if (value != null) return@produceState
            value = repo.thumbnail(picture)
            if (value == null) {
                delay(wait)
                wait *= 4
            }
        }
    }
    LaunchedEffect(st.focused) { if (st.focused) onFocus() }
    Box(
        Modifier
            .size(320.px, 180.px)
            .control(st, onClick, focusScale = 1.08f, onLongClick = onLongClick)
            .clip(shape)
            .background(c.pill)
            .then(
                if (st.focused || pinned) {
                    Modifier.border(if (st.focused) 5.px else 3.px, if (st.focused) c.cta else c.aqua, shape)
                } else {
                    Modifier
                }
            ),
    ) {
        val bmp = img
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (st.focused) Box(Modifier.fillMaxSize().drawBehind { drawSheen(c.sheen) })
        if (pinned) CheckBadge(Modifier.align(Alignment.TopEnd).padding(10.px))
        if (favourite) {
            HaloIcon(
                app.halo.launcher.data.IconSpec(app.halo.launcher.data.Glyph.heart.spec.paths, filled = true),
                30.px,
                Color(0xFFFF6B8A),
                Modifier.align(Alignment.BottomStart).padding(10.px),
            )
        }
    }
}

@Composable
private fun CheckBadge(modifier: Modifier = Modifier) {
    val c = LocalHalo.current.colors
    Box(
        modifier
            .size(40.px)
            .clip(RoundedCornerShape(20.px))
            .background(c.aqua)
            .border(2.px, Color.White.copy(alpha = 0.85f), RoundedCornerShape(20.px)),
        contentAlignment = Alignment.Center,
    ) { HaloIcon(Icons.check, 24.px, Color.White, strokeWidth = 2.6f) }
}
