package app.halo.launcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The user's own pictures, kept only in Halo's private storage on the TV:
 *  - wallpapers ("My pictures" collection): JPEGs, long side ≤ 2560 px
 *  - app icons: 16:9 PNG cards (640×360), one per app key; the uploaded picture is cropped when it
 *    is wide, otherwise centred on a graphite card so logos and square icons look right.
 * Sent from a phone through [app.halo.launcher.setup.UploadServer].
 */
object MyMedia {
    const val MAX_UPLOAD = 25L * 1024 * 1024
    private const val WALL_MAX = 2560
    private const val ICON_W = 640
    private const val ICON_H = 360
    private const val MAX_WALLPAPERS = 200

    /** Bumped on every change, so screens and the picture list refresh. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private fun wallDir(context: Context) = File(context.filesDir, "my_wallpapers").apply { mkdirs() }
    private fun iconDir(context: Context) = File(context.filesDir, "my_icons").apply { mkdirs() }

    /** Own wallpapers, newest first. */
    fun wallpapers(context: Context): List<File> =
        wallDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun wallpaper(context: Context, name: String): File? =
        File(wallDir(context), name).takeIf { safeName(name) && it.isFile }

    fun iconFile(context: Context, appKey: String): File = File(iconDir(context), sha1(appKey) + ".png")

    fun hasIcon(context: Context, appKey: String): Boolean = iconFile(context, appKey).isFile

    /** Saves a picture as an own wallpaper; returns its file name. Throws [IOException] if it isn't an image. */
    fun saveWallpaper(context: Context, bytes: ByteArray): String {
        if (wallpapers(context).size >= MAX_WALLPAPERS) throw IOException("too many pictures")
        val bmp = decode(bytes, WALL_MAX) ?: throw IOException("not an image")
        val name = "w" + System.currentTimeMillis().toString(36) + (0..999).random() + ".jpg"
        val file = File(wallDir(context), name)
        writeAtomically(file) { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bmp.recycle()
        bump()
        return name
    }

    fun deleteWallpaper(context: Context, name: String): Boolean =
        (wallpaper(context, name)?.delete() == true).also { if (it) bump() }

    /** Saves an own icon for [appKey] (a 16:9 card built from the picture). */
    fun saveIcon(context: Context, appKey: String, bytes: ByteArray) {
        val src = decode(bytes, 1280) ?: throw IOException("not an image")
        val card = Bitmap.createBitmap(ICON_W, ICON_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(card)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val aspect = src.width.toFloat() / src.height
        if (aspect >= 1.5f) {
            // Wide picture (a banner): fill the card, centre-cropped.
            val s = max(ICON_W.toFloat() / src.width, ICON_H.toFloat() / src.height)
            val w = src.width * s
            val h = src.height * s
            c.drawBitmap(src, null, RectF((ICON_W - w) / 2, (ICON_H - h) / 2, (ICON_W + w) / 2, (ICON_H + h) / 2), p)
        } else {
            // Logo or square icon: centred on graphite.
            p.shader = LinearGradient(0f, 0f, ICON_W.toFloat(), ICON_H.toFloat(), 0xFF3B404A.toInt(), 0xFF14161B.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, ICON_W.toFloat(), ICON_H.toFloat(), p)
            p.shader = null
            val s = min(ICON_W * 0.72f / src.width, ICON_H * 0.72f / src.height)
            val w = src.width * s
            val h = src.height * s
            c.drawBitmap(src, null, RectF((ICON_W - w) / 2, (ICON_H - h) / 2, (ICON_W + w) / 2, (ICON_H + h) / 2), p)
        }
        src.recycle()
        writeAtomically(iconFile(context, appKey)) { out -> card.compress(Bitmap.CompressFormat.PNG, 100, out) }
        card.recycle()
        bump()
    }

    fun deleteIcon(context: Context, appKey: String): Boolean =
        iconFile(context, appKey).delete().also { if (it) bump() }

    /** Loads an own icon (16:9 card), or null. */
    fun loadIcon(context: Context, appKey: String): Bitmap? =
        iconFile(context, appKey).takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    // ---- helpers ------------------------------------------------------------------------

    private fun bump() {
        _version.value = System.currentTimeMillis()
    }

    private fun safeName(name: String) = name.matches(Regex("^[a-z0-9]{1,40}\\.jpg$"))

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Decodes [bytes] (JPEG, PNG, WebP, HEIF…) with the long side ≤ [maxSide], upright per EXIF. */
    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val long = max(bmp.width, bmp.height)
        if (long > maxSide) {
            val s = maxSide.toFloat() / long
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    private fun writeAtomically(file: File, write: (java.io.OutputStream) -> Boolean) {
        val tmp = File(file.parentFile, file.name + ".part")
        try {
            val ok = tmp.outputStream().use { write(it) }
            if (!ok || !tmp.renameTo(file)) throw IOException("could not save")
        } finally {
            tmp.delete()
        }
    }
}
