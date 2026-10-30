package app.halo.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.halo.launcher.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Now playing" ambience: the artwork of what's playing becomes the home background.
 *  • wide artwork (YouTube / SmartTube thumbnails) is shown sharp, full screen
 *  • square artwork (Spotify covers) becomes a soft colour field (shrunk to a few pixels and
 *    stretched back up with bilinear filtering – a blur for free)
 * The readability scrim is baked into the image once, off the main thread, so on screen this
 * is a single static full-screen image: nothing redraws while idle.
 */
@Composable
fun MediaBackdrop(np: NowPlaying, modifier: Modifier = Modifier) {
    val art = np.artHi ?: np.art ?: return
    Box(modifier) {
        Crossfade(targetState = art, animationSpec = tween(1200), label = "mediaArt") { image ->
            val baked by produceState<ImageBitmap?>(null, image) {
                value = withContext(Dispatchers.Default) { runCatching { bake(image) }.getOrNull() }
            }
            baked?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Box(Modifier.fillMaxSize())
        }
    }
}

private const val W = 1920
private const val H = 1080

/** Crops/scales the artwork to 1920×1080 (or a soft field for square covers) + scrim, once. */
private fun bake(image: ImageBitmap): ImageBitmap {
    val src0 = image.asAndroidBitmap()
    val src = if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) else src0
    val wide = src.width >= src.height * 1.4f
    val source: Bitmap = if (wide) src else softField(src)
    val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val cv = Canvas(out)
    val s = maxOf(W / source.width.toFloat(), H / source.height.toFloat())
    val dw = source.width * s
    val dh = source.height * s
    cv.drawBitmap(source, null, RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
    fun a(x: Float) = android.graphics.Color.argb((x * 255).toInt(), 0, 0, 0)
    val p = Paint()
    p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(a(0.30f), a(0.18f), a(0.80f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
    p.shader = LinearGradient(0f, 0f, W * 0.55f, 0f, a(0.35f), 0, Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
    val hw = out.copy(Bitmap.Config.HARDWARE, false)
    return (hw ?: out.also { it.prepareToDraw() }).asImageBitmap()
}

/**
 * A smooth, blurred colour field from a cover: shrink to 48 px, box-blur three times, then
 * enlarge in ×2 bilinear steps (a single huge bilinear jump shows blocky steps on a TV).
 */
private fun softField(src: Bitmap): Bitmap {
    val w = 48
    val h = (48f * src.height / src.width).toInt().coerceIn(8, 96)
    var b = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, 4) }
    b.setPixels(px, 0, w, 0, 0, w, h)
    while (b.width * 2 <= W) {
        b = Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, true)
    }
    return b
}

/** Separable box blur (radius r, edges clamped) on ARGB pixels. */
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, stride: Int, step: Int) {
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var bb = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = src[l * stride + j * step]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bb += c and 0xFF
                }
                val n = 2 * r + 1
                dst[l * stride + i * step] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bb / n)
            }
        }
    }
    pass(px, tmp, w, h, w, 1)   // rows
    pass(tmp, px, h, w, 1, w)   // columns
}
