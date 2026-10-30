package app.halo.launcher.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.halo.launcher.data.IconSpec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin


// ---- Icons --------------------------------------------------------------------

private val vectorCache = HashMap<Pair<IconSpec, Float>, ImageVector>()

private fun vectorFor(spec: IconSpec, strokeWidth: Float): ImageVector =
    vectorCache.getOrPut(spec to strokeWidth) {
        val b = ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        )
        spec.paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = if (spec.filled) SolidColor(Color.White) else null,
                stroke = if (spec.filled) null else SolidColor(Color.White),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }

@Composable
fun HaloIcon(spec: IconSpec, size: Dp, color: Color, modifier: Modifier = Modifier, strokeWidth: Float = 2f) {
    val painter = rememberVectorPainter(vectorFor(spec, strokeWidth))
    Image(
        painter = painter,
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

// ---- Keys ---------------------------------------------------------------------

fun KeyEvent.isSelect(): Boolean = when (nativeKeyEvent.keyCode) {
    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
    NativeKeyEvent.KEYCODE_BUTTON_A, NativeKeyEvent.KEYCODE_BUTTON_SELECT -> true
    else -> false
}

val KeyEvent.isDown: Boolean get() = nativeKeyEvent.action == NativeKeyEvent.ACTION_DOWN
val KeyEvent.code: Int get() = nativeKeyEvent.keyCode

/** Remembers the last D-pad direction, so tiles know where focus came from. */
object FocusDirection {
    /** -1 = left, 1 = right, -2 = up, 2 = down, 0 = none. */
    @Volatile var last: Int = 0

    fun record(keyCode: Int) {
        last = when (keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_LEFT -> -1
            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> 1
            NativeKeyEvent.KEYCODE_DPAD_UP -> -2
            NativeKeyEvent.KEYCODE_DPAD_DOWN -> 2
            else -> last
        }
    }
}

/**
 * Click + long-press for remote controls. Long press fires after ~0.55 s of
 * holding OK (the remote sends key repeats while held).
 */
@Composable
fun Modifier.remoteClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onPressedChange: (Boolean) -> Unit = {},
): Modifier {
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    return this
        .onKeyEvent { e ->
            if (!e.isSelect()) return@onKeyEvent false
            val n = e.nativeKeyEvent
            when (n.action) {
                NativeKeyEvent.ACTION_DOWN -> {
                    if (n.repeatCount == 0) {
                        down = true
                        longFired = false
                        onPressedChange(true)
                    } else if (onLongClick != null && down && !longFired &&
                        (n.isLongPress || n.eventTime - n.downTime > 550)
                    ) {
                        longFired = true
                        onPressedChange(false)
                        onLongClick()
                    }
                    true
                }
                NativeKeyEvent.ACTION_UP -> {
                    val fire = down && !longFired
                    down = false
                    onPressedChange(false)
                    if (fire) onClick()
                    true
                }
                else -> false
            }
        }
        .focusable()
}

// ---- Glass --------------------------------------------------------------------

/**
 * Halo glass, "Frutiger Aero meets tvOS": a translucent cool veil, a glossy highlight over the
 * top half, a soft floor reflection and a bright hairline rim. No live blur (too heavy for TV
 * GPUs); every brush is built once per size (drawWithCache), so it costs nothing per frame.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    strong: Boolean = false,
    shadow: Boolean = true,
    veil: Color? = null,
    /**
     * Opacity of the dark/light base under the veil. Null = the default see-through glass.
     * Set it (e.g. 0.92) for "frosted surfaces" that carry text: panels, editors, dialogs. Such
     * surfaces also get a soft gloss band at the top only, instead of the hard 42 % highlight line.
     */
    fillAlpha: Float? = null,
): Modifier {
    val c = LocalHalo.current.colors
    val veilColor = veil ?: if (strong) c.glassStrong else c.glass
    val fill = veilColor.compositeOver(c.base.copy(alpha = fillAlpha ?: if (strong) 0.55f else 0.32f))
    val dark = c.isDark
    val surface = fillAlpha != null
    var m = this
    if (shadow) m = m.shadow(20.px, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
    return m.clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val gloss = if (surface) {
            // Soft highlight over the top ~140 design px, no hard edge running through the content.
            val band = (140f * Ui.k * density / size.height.coerceAtLeast(1f)).coerceIn(0.04f, 0.42f)
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.16f else 0.45f),
                band to Color.Transparent,
                1f to Color.Transparent,
            )
        } else {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.20f else 0.55f),
                0.42f to Color.White.copy(alpha = if (dark) 0.05f else 0.16f),
                0.43f to Color.Transparent,
                1f to Color.Transparent,
            )
        }
        val floor = Brush.verticalGradient(
            0f to Color.Transparent, 0.7f to Color.Transparent,
            1f to c.aqua.copy(alpha = if (dark) 0.14f else 0.22f),
        )
        val sheen = Brush.radialGradient(
            listOf(c.sheen, Color.Transparent),
            center = Offset(size.width * 0.15f, -size.height * 0.2f),
            radius = maxOf(size.width * 1.2f, size.height * 0.9f) * 0.55f,
        )
        val rim = Brush.verticalGradient(0f to c.rimTop, 0.2f to c.rimLine, 0.8f to c.rimLine, 1f to c.rimBottom)
        val w = 1.5f * Ui.k * density
        onDrawBehind {
            drawRect(fill)
            drawRect(sheen)
            drawRect(gloss)
            drawRect(floor)
            drawOutline(outline, rim, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 2))
        }
    }
}

/**
 * Full-screen readability scrim for screens drawn over a photo or artwork (Weather, Search,
 * Backgrounds, Profiles): darkens (dark theme) or frosts (light theme) the picture so text on it
 * stays legible. Static gradient, built once per size.
 */
@Composable
fun Modifier.readabilityScrim(strength: Float = 1f): Modifier {
    val dark = LocalHalo.current.colors.isDark
    return drawWithCache {
        val tint = if (dark) Color(0xFF03050C) else Color(0xFFF2F6FC)
        val k = (if (dark) 1f else 0.75f) * strength
        val b = Brush.verticalGradient(
            0f to tint.copy(alpha = (0.55f * k).coerceIn(0f, 1f)),
            0.45f to tint.copy(alpha = (0.35f * k).coerceIn(0f, 1f)),
            1f to tint.copy(alpha = (0.62f * k).coerceIn(0f, 1f)),
        )
        onDrawBehind { drawRect(b) }
    }
}

/**
 * Keeps the focused item at least [pad] away from the edges of any scrolling container inside
 * [content] (lazy rows/grids, verticalScroll), so focus never sits flush against or under an edge
 * and the next item always peeks in. Default behaviour scrolls only "just enough".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollPadding(pad: Dp, content: @Composable () -> Unit) {
    val padPx = with(LocalDensity.current) { pad.toPx() }
    val spec = remember(padPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val start = padPx.coerceAtMost(containerSize / 2f)
                val end = containerSize - start
                return when {
                    size > end - start -> offset - start
                    offset < start -> offset - start
                    offset + size > end -> offset + size - end
                    else -> 0f
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** radial-gradient(120% 90% at 15% -20%, sheen, transparent 55%) */
fun DrawScope.drawSheen(sheen: Color) {
    val center = Offset(size.width * 0.15f, -size.height * 0.2f)
    val r = maxOf(size.width * 1.2f, size.height * 0.9f) * 0.55f
    drawRect(Brush.radialGradient(listOf(sheen, Color.Transparent), center = center, radius = r))
}

/** A CSS-style `linear-gradient(<angle>deg, ...)` over the current draw area. */
fun DrawScope.cssLinear(angleDeg: Float, vararg stops: Pair<Float, Color>, shift: Float = 0f): Brush {
    val a = Math.toRadians(angleDeg.toDouble())
    val dx = sin(a).toFloat()
    val dy = -cos(a).toFloat()
    val len = abs(size.width * dx) + abs(size.height * dy)
    val cx = size.width / 2 + dx * len * shift
    val cy = size.height / 2 + dy * len * shift
    return Brush.linearGradient(
        colorStops = *stops,
        start = Offset(cx - dx * len / 2, cy - dy * len / 2),
        end = Offset(cx + dx * len / 2, cy + dy * len / 2),
    )
}

// ---- Layout -------------------------------------------------------------------

/**
 * Lets a child draw [v] beyond its slot vertically without taking up the space,
 * so focused tiles can grow and glow inside clipped rows.
 */
fun Modifier.bleed(v: Dp, h: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val vp = v.roundToPx()
    val hp = h.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * vp else constraints.maxHeight,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * hp else constraints.maxWidth,
        )
    )
    layout((placeable.width - 2 * hp).coerceAtLeast(0), (placeable.height - 2 * vp).coerceAtLeast(0)) {
        placeable.place(-hp, -vp)
    }
}
