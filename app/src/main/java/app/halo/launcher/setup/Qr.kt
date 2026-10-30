package app.halo.launcher.setup

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes for the setup wizard (zxing core, no Android extras).
 *
 * The bitmap has exactly one pixel per QR module (plus a 2-module quiet zone); draw it with
 * `FilterQuality.None` so it scales up crisp. Dark modules are near-black on white, which every
 * phone camera reads, also from a glossy TV screen.
 */
object Qr {
    fun encode(text: String): ImageBitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) px[row + x] = if (m.get(x, y)) DARK else LIGHT
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()

    private const val DARK = 0xFF0A0C12.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()
}
