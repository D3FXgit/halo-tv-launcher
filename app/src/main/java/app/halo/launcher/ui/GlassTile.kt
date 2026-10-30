package app.halo.launcher.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.halo.launcher.data.Tile
import app.halo.launcher.data.TileLayout
import app.halo.launcher.data.TileSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Which D-pad directions have nowhere to go (tile does a rubber-band nudge instead). */
data class Edges(val left: Boolean = false, val right: Boolean = false, val up: Boolean = false, val down: Boolean = false) {
    companion object {
        val None = Edges()
    }
}

/**
 * A Halo tile in the "Frutiger Aero meets tvOS" style:
 *  • the app card (pre-rendered bitmap) under a static glass gloss + rim
 *  • focus: spring scale + lift, neutral soft shadow, crisp white ring, one glare sweep
 *  • sway: the tile tilts in the direction focus arrived from and settles back
 *  • press squish on OK, rubber-band nudge at row ends, edit-mode jiggle
 *
 * Performance: all gradients are built once per size in drawWithCache; per-frame work is
 * limited to graphicsLayer properties (GPU transforms) and a translated cached brush.
 * No coloured glow behind tiles (it fought with photo backgrounds).
 */
@Composable
fun GlassTile(
    tile: Tile,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    edges: Edges = Edges.None,
    held: Boolean = false,
    dim: Boolean = false,
    jiggle: Boolean = false,
    /** Read only by the focused tile, so idle changes don't recompose every tile. */
    idle: () -> Boolean = { false },
    @Suppress("UNUSED_PARAMETER") blur: Boolean = true,
    showcase: Boolean = false,
    focusedScale: Float = 1.1f,
    /** White focus ring (Halo home rows). */
    ring: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: (Rect) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
) {
    val theme = LocalHalo.current
    val dark = theme.colors.isDark
    val motion = theme.motion
    val scope = rememberCoroutineScope()

    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // Plain holder (not snapshot state); bounds are computed only when clicked.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val on = focused || showcase
    val isIdle = on && idle()

    val targetScale = when {
        held -> 1.14f
        isIdle -> focusedScale + 0.02f
        on -> focusedScale
        else -> 1f
    } * (if (pressed) 0.95f else 1f)
    val scale by animateFloatAsState(
        if (motion || !on) targetScale else focusedScale,
        if (on || held) Motion.focusIn() else Motion.focusOut(),
        label = "scale",
    )
    val lift by animateFloatAsState(if (held) -10f else if (on) -6f else 0f, Motion.focusIn(), label = "lift")
    val focusA by animateFloatAsState(if (on || held) 1f else 0f, tween(260), label = "focus")
    val alpha by animateFloatAsState(if (dim) 0.42f else 1f, tween(300), label = "dim")

    val tiltY = remember { Animatable(0f) }      // degrees
    val tiltX = remember { Animatable(0f) }
    val nudgeX = remember { Animatable(0f) }     // design px
    val nudgeY = remember { Animatable(0f) }
    val glare = remember { Animatable(-1f) }     // sweep position: -1 (off left) → 1 (off right)
    var wiggle by remember { mutableFloatStateOf(0f) }

    fun sweep() = scope.launch {
        glare.snapTo(-1f)
        glare.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        glare.snapTo(-1f)
    }

    LaunchedEffect(focused) {
        if (focused) {
            onFocus()
            if (!motion) return@LaunchedEffect
            val d = FocusDirection.last
            val dx = when (d) { -1 -> -1f; 1 -> 1f; else -> 0f }
            val dy = when (d) { -2 -> -1f; 2 -> 1f; else -> 0f }
            launch { tiltY.snapTo(4f * dx); tiltY.animateTo(0f, Motion.wobble()) }
            launch { tiltX.snapTo(-3f * dy); tiltX.animateTo(0f, Motion.wobble()) }
            sweep()
        }
    }

    // Idle: an occasional glint on the focused tile (no per-frame loop).
    LaunchedEffect(on, isIdle, motion) {
        if (!(on && motion && (isIdle || showcase))) return@LaunchedEffect
        // A few glints, then rest: an idle Home must stop redrawing completely.
        repeat(2) {
            delay(6500)
            sweep()
        }
    }

    // Edit-mode jiggle with a per-tile phase.
    LaunchedEffect(jiggle, held, motion) {
        if (!(jiggle && motion) || held) {
            wiggle = 0f
            return@LaunchedEffect
        }
        val phase = (tile.app.key.hashCode() % 628) / 100.0
        while (true) {
            withFrameMillis { t -> wiggle = sin(t / 1000.0 * 2 * PI / 0.28 + phase).toFloat() }
        }
    }

    fun bounce(dx: Float, dy: Float) {
        if (!motion) return
        scope.launch {
            nudgeX.animateTo(14f * dx, tween(90))
            nudgeX.animateTo(0f, Motion.bounce())
        }
        scope.launch {
            nudgeY.animateTo(10f * dy, tween(90))
            nudgeY.animateTo(0f, Motion.bounce())
        }
    }

    val radius = height * 0.14f
    val shape = RoundedCornerShape(radius.px)
    val bodyColor = tileBody(tile, dark)

    Box(
        modifier = modifier
            .size(width.px, height.px)
            .onPlaced { coords[0] = it }
            .graphicsLayer {
                val k = Ui.k * density
                scaleX = scale
                scaleY = scale
                // 2D sway (a slide, not a 3D rotation: perspective breaks the cheap rounded clip).
                translationX = (nudgeX.value + tiltY.value * 1.5f) * k
                translationY = (lift + nudgeY.value - tiltX.value * 1.5f) * k
                rotationZ = wiggle * 1.3f
                this.alpha = alpha
                // Neutral, soft shadow only while focused – never a coloured glow.
                shadowElevation = 22f * focusA * k
                this.shape = shape
                clip = true
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (onKey?.invoke(e) == true) return@onKeyEvent true
                if (!e.isDown) return@onKeyEvent false
                when (e.code) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> if (edges.left) { bounce(-1f, 0f); true } else false
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> if (edges.right) { bounce(1f, 0f); true } else false
                    NativeKeyEvent.KEYCODE_DPAD_UP -> if (edges.up) { bounce(0f, -1f); true } else false
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> if (edges.down) { bounce(0f, 1f); true } else false
                    else -> false
                }
            }
            .remoteClickable(
                onClick = { onClick(coords[0]?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero) },
                onLongClick = onLongClick,
                onPressedChange = { pressed = it },
            )
            .drawWithCache {
                val k = Ui.k * density
                val r = CornerRadius(radius * k)
                val body: Brush? = bodyColor?.let { b ->
                    Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.lerp(b, Color.White, 0.14f),
                        0.55f to b,
                        1f to androidx.compose.ui.graphics.lerp(b, Color.Black, 0.22f),
                    )
                }
                // Aero gloss: a soft highlight over the top ~half, brightest at the very top.
                val gloss = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.26f else 0.38f),
                    0.46f to Color.White.copy(alpha = 0.06f),
                    0.47f to Color.Transparent,
                    1f to Color.Transparent,
                )
                val floor = Brush.verticalGradient(
                    0f to Color.Transparent, 0.72f to Color.Transparent,
                    1f to Color.White.copy(alpha = if (dark) 0.10f else 0.22f),
                )
                val rim = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.75f), 0.18f to Color.White.copy(alpha = 0.16f),
                    0.85f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.30f),
                )
                val bandW = size.width * 0.55f
                val glareBrush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = if (dark) 0.28f else 0.5f),
                    1f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(bandW, size.height * 0.35f),
                )
                val stroke = 1.5f * k
                val ringW = 4.5f * k
                val ringColor = if (dark || tile.source == TileSource.Card || tile.source == TileSource.Banner) Color.White else Color(0xFF12141A)
                onDrawWithContent {
                    if (body != null) drawRect(body)
                    drawContent()
                    // Halo cards have their gloss baked in; don't double it.
                    if (tile.source != TileSource.Card) {
                        drawRect(gloss)
                        drawRect(floor)
                    }
                    val g = glare.value
                    if (g > -1f && focusA > 0f) {
                        val travel = size.width + bandW * 2
                        translate(left = -bandW + (g + 1f) / 2f * travel - bandW / 2) {
                            drawRect(glareBrush, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(bandW, size.height), alpha = focusA)
                        }
                    }
                    drawRoundRect(brush = rim, cornerRadius = r, style = Stroke(stroke))
                    if ((ring || tile.source == TileSource.Card || tile.source == TileSource.Banner) && focusA > 0.01f && !held) {
                        drawRoundRect(
                            color = ringColor.copy(alpha = focusA),
                            topLeft = Offset(ringW / 2, ringW / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - ringW, size.height - ringW),
                            cornerRadius = CornerRadius(r.x - ringW / 2),
                            style = Stroke(ringW),
                        )
                    }
                }
            },
    ) {
        TileContent(tile = tile, width = width, height = height, dark = dark, modifier = Modifier)
    }
}

/** Solid body colour behind the content, or null when the content covers the tile. */
private fun tileBody(tile: Tile, dark: Boolean): Color? = when (tile.source) {
    TileSource.Card, TileSource.Banner -> null
    TileSource.Logo -> tile.bg
    else -> tile.tint?.let { if (dark) it.copy(alpha = 0.85f) else androidx.compose.ui.graphics.lerp(Color.White, it, 0.35f) }
        ?: if (dark) Color(0x33FFFFFF) else Color(0xB3FFFFFF)
}

@Composable
private fun TileContent(tile: Tile, width: Int, height: Int, dark: Boolean, modifier: Modifier) {
    val tint = tile.tint
    when (tile.source) {
        TileSource.Card -> {
            val card = tile.app.card
            if (card != null) {
                Image(
                    bitmap = card,
                    contentDescription = tile.label,
                    contentScale = ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return
        }
        TileSource.Logo -> {
            val logo = tile.app.logo
            if (logo != null) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = logo,
                        contentDescription = tile.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size((height * tile.logoScale).px),
                    )
                }
            }
            return
        }
        TileSource.Banner -> {
            val b = tile.app.banner
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = tile.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return
        }
        else -> Unit
    }
    val both = tile.layout == TileLayout.Both
    val showGlyph = tile.layout != TileLayout.Name
    val showName = tile.layout != TileLayout.Glyph
    val glyphColor = when {
        dark -> Color.White
        tint == null -> Color(0xFF12141A)
        else -> Color(tint.red * 0.62f, tint.green * 0.62f, tint.blue * 0.62f)
    }
    val textColor = if (dark) Color.White else Color(0xFF12141A)
    val glyphSize = (height * if (both) 0.3f else 0.5f)
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = (width * 0.07f).px),
        horizontalArrangement = Arrangement.spacedBy((height * 0.08f).px, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showGlyph) {
            if (tile.source == TileSource.Icon && tile.app.icon != null) {
                Image(
                    bitmap = tile.app.icon,
                    contentDescription = null,
                    modifier = Modifier.size((glyphSize * 1.25f).px),
                )
            } else {
                HaloIcon(
                    spec = tile.glyph.spec,
                    size = glyphSize.px,
                    color = glyphColor,
                    strokeWidth = if (height > 150) 1.6f else 1.8f,
                )
            }
        }
        if (showName) {
            BasicText(
                text = tile.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(
                    size = (height * if (both) 0.145f else 0.17f).toInt(),
                    weight = FontWeight.SemiBold,
                    color = textColor,
                    tracking = -0.01f,
                ).copy(
                    shadow = if (dark) Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 1f), 2f) else null,
                ),
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Small helper so a 0-dp spacer compiles everywhere. */
internal val ZeroDp = 0.dp

/** Keeps a tile's last D-pad repeat from being treated as a long press elsewhere. */
internal suspend fun settle() = delay(16)
