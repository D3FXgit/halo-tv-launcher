package app.halo.launcher.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Colours of the "Halo Ambient Backdrop" component (design file), night → day. */
@Immutable
private data class Sky(
    val s1: Color, val s2: Color, val s3: Color, val s4: Color,
    val sun: Color, val sunOp: Float,
    val r1: Color, val r2: Color, val r3: Color,
    val mist: Float, val mist2: Float, val stars: Float,
    val scrim: Color, val scrimMid: Float, val scrimEnd: Float,
) {
    companion object {
        val Night = Sky(
            Color(0xFF070A18), Color(0xFF141B3D), Color(0xFF33295A), Color(0xFF6E4867),
            Color(0xFFF2A66A), 0.5f,
            Color(0xFF2B2752), Color(0xFF191734), Color(0xFF0C0C1C),
            0.08f, 0.05f, 0.6f,
            Color(0xFF05060D), 0.6f, 0.92f,
        )
        val Day = Sky(
            Color(0xFF7FA6DC), Color(0xFFA9C6EA), Color(0xFFD6E3F1), Color(0xFFF3EEE6),
            Color(0xFFFFF1CF), 0.9f,
            Color(0xFFA3B5D2), Color(0xFF8499BB), Color(0xFF64799E),
            0.5f, 0.35f, 0f,
            Color(0xFFEEF2F7), 0.55f, 0.85f,
        )

        fun at(t: Float): Sky {
            fun f(a: Float, b: Float) = a + (b - a) * t
            val a = Night
            val b = Day
            return Sky(
                lerp(a.s1, b.s1, t), lerp(a.s2, b.s2, t), lerp(a.s3, b.s3, t), lerp(a.s4, b.s4, t),
                lerp(a.sun, b.sun, t), f(a.sunOp, b.sunOp),
                lerp(a.r1, b.r1, t), lerp(a.r2, b.r2, t), lerp(a.r3, b.r3, t),
                f(a.mist, b.mist), f(a.mist2, b.mist2), f(a.stars, b.stars),
                lerp(a.scrim, b.scrim, t), f(a.scrimMid, b.scrimMid), f(a.scrimEnd, b.scrimEnd),
            )
        }
    }
}

private val STARS = listOf(
    Triple(180f, 120f, 1.6f), Triple(410f, 210f, 1.1f), Triple(620f, 90f, 1.4f), Triple(860f, 170f, 1f),
    Triple(1040f, 70f, 1.5f), Triple(1230f, 200f, 1.1f), Triple(1460f, 110f, 1.3f), Triple(1680f, 230f, 1f),
    Triple(1820f, 80f, 1.5f), Triple(300f, 320f, 0.9f), Triple(760f, 300f, 1f), Triple(1580f, 330f, 0.9f),
)

private const val RIDGE1 = "M0 690 L120 640 L240 668 L380 588 L520 648 L640 600 L760 640 L900 556 L1040 628 L1180 580 L1320 640 L1460 598 L1600 650 L1760 588 L1920 640 L1920 1080 L0 1080 Z"
private const val RIDGE2 = "M0 790 C200 730 320 770 460 740 S760 690 900 748 S1200 798 1380 728 S1700 708 1920 768 L1920 1080 L0 1080 Z"
private const val RIDGE3 = "M0 890 C260 840 520 908 760 868 S1240 828 1500 888 S1800 906 1920 878 L1920 1080 L0 1080 Z"

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

/** A soft white ellipse, pre-blurred once (feGaussianBlur σ=38) at quarter resolution. */
private class Mist(val rx: Float, val ry: Float) {
    val pad = 120f
    val scale = 0.25f
    val image: ImageBitmap = run {
        val w = ((rx * 2 + pad * 2) * scale).roundToInt()
        val h = ((ry * 2 + pad * 2) * scale).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            maskFilter = BlurMaskFilter(65f * scale, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(RectF(pad * scale, pad * scale, (pad + rx * 2) * scale, (pad + ry * 2) * scale), paint)
        bmp.asImageBitmap()
    }

    fun DrawScope.drawAt(cx: Float, cy: Float, alpha: Float) {
        val w = rx * 2 + pad * 2
        val h = ry * 2 + pad * 2
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}

/**
 * The Halo scene wallpaper: sky gradient, stars, sun, three ridges with mist and a bottom scrim.
 *
 * Performance (the TV GPU is fill-rate bound): the whole scene is rendered ONCE per theme
 * (night + day) into a half-resolution bitmap. On screen that is a single full-screen image
 * moved by graphicsLayer (slow drift + parallax), instead of ~9 animated full-screen layers.
 * During a day/night change the two images cross-fade; otherwise only one is drawn.
 *
 * @param control parallax and lift from the home screen (colour accent is ignored).
 * @param drama 1 = home screen; >1 exaggerates the drift for ambient mode.
 */
@Composable
fun Backdrop(
    modifier: Modifier = Modifier,
    control: BackdropControl? = null,
    scrim: Float = 1f,
    drama: Float = 1f,
) {
    val theme = LocalHalo.current
    val light = LocalSkyLight.current.value
    // Infinite drift only in ambient mode: on Home the window must be able to stop redrawing.
    val animate = theme.motion && drama > 1f

    val night = rememberScene(day = false, scrim = scrim, needed = light < 0.999f)
    val day = rememberScene(day = true, scrim = scrim, needed = light > 0.001f)

    // Read only inside graphicsLayer blocks → no recomposition, no redraw.
    val par = animateFloatAsState(control?.parallax ?: 0f, Motion.gentle(), label = "parallax")
    val lift = animateFloatAsState(control?.lift ?: 0f, Motion.gentle(), label = "lift")
    val drift: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "sky").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween((60000 / drama).toInt(), easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "drift",
        )
    } else remember { mutableFloatStateOf(0.5f) }

    Box(modifier) {
        if (light < 0.999f && night != null) SceneImage(night, 1f, par, lift, drift, animate, drama)
        if (light > 0.001f && day != null) SceneImage(day, light, par, lift, drift, animate, drama)
    }
}

/** Scenes are rendered once per process, off the main thread. */
private val sceneCache = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, Float>, ImageBitmap>()

@Composable
private fun rememberScene(day: Boolean, scrim: Float, needed: Boolean): ImageBitmap? =
    produceState(sceneCache[day to scrim], day, scrim, needed) {
        if (value == null && needed) value = withContext(Dispatchers.Default) {
            sceneCache.getOrPut(day to scrim) { renderScene(if (day) Sky.Day else Sky.Night, scrim) }
        }
    }.value

@Composable
private fun SceneImage(
    image: ImageBitmap,
    alpha: Float,
    par: State<Float>,
    lift: State<Float>,
    drift: State<Float>,
    motion: Boolean,
    drama: Float,
) {
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                val d = drift.value - 0.5f
                val s = 1.08f + (if (motion) 0.02f * drama * drift.value else 0f)
                scaleX = s
                scaleY = s
                translationX = (-par.value * 0.012f + d * 0.02f * drama) * size.width
                translationY = (lift.value * 0.012f) * size.height
            },
    )
}

private const val SCENE_W = 960
private const val SCENE_H = 540

/** Draws the design-file scene (1920×1080 coordinates) into a half-resolution bitmap. */
private fun renderScene(sky: Sky, scrim: Float): ImageBitmap {
    val img = ImageBitmap(SCENE_W, SCENE_H)
    val canvas = androidx.compose.ui.graphics.Canvas(img)
    val ridges = listOf(parse(RIDGE1), parse(RIDGE2), parse(RIDGE3))
    val mist1 = Mist(1100f, 46f)
    val mist2 = Mist(1000f, 40f)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(SCENE_W.toFloat(), SCENE_H.toFloat())) {
        scale(SCENE_W / 1920f, SCENE_H / 1080f, pivot = Offset.Zero) {
            drawRect(
                Brush.verticalGradient(0f to sky.s1, 0.42f to sky.s2, 0.66f to sky.s3, 0.8f to sky.s4, startY = 0f, endY = 1080f),
                size = Size(1920f, 1080f),
            )
            if (sky.stars > 0.01f) STARS.forEach { (x, y, r) -> drawCircle(Color.White.copy(alpha = sky.stars), r * 1.4f, Offset(x, y)) }
            val c = Offset(1380f, 650f)
            drawCircle(
                Brush.radialGradient(listOf(sky.sun.copy(alpha = sky.sunOp), sky.sun.copy(alpha = 0f)), center = c, radius = 570f),
                radius = 570f, center = c,
            )
            drawPath(ridges[0], sky.r1)
            with(mist1) { drawAt(900f, 720f, sky.mist) }
            drawPath(ridges[1], sky.r2)
            with(mist2) { drawAt(1100f, 820f, sky.mist2) }
            drawPath(ridges[2], sky.r3)
            if (scrim > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.78f to sky.scrim.copy(alpha = sky.scrimMid * scrim),
                        1f to sky.scrim.copy(alpha = sky.scrimEnd * scrim),
                        startY = 0f, endY = 1080f,
                    ),
                    size = Size(1920f, 1080f),
                )
            }
        }
    }
    img.prepareToDraw()
    return img
}
