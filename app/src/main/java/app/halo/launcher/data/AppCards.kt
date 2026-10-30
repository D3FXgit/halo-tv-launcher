package app.halo.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import app.halo.launcher.R
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the uniform Halo app card: a 16:9 bitmap with the app's brand gradient, a soft
 * Frutiger-Aero gloss and the app's logo (or a designed icon-bubble + wordmark lockup).
 *
 * Cards are rendered once, off the main thread, with a software [Canvas]; tiles then draw a single
 * bitmap. Corners are NOT rounded here: the tile clips the card to its own shape.
 * All methods are thread-safe (no shared mutable state except the cached typeface).
 */
object AppCards {
    const val WIDTH = 512
    const val HEIGHT = 288

    /** A rendered card plus the brand colour it was built from (ARGB). */
    class Result(val card: Bitmap, val brand: Int)

    /** Background of a card. [accent] glows from the bottom-right; [light] = pearl-white card. */
    class Look(val top: Int, val bottom: Int, val accent: Int?, val accentAlpha: Float, val light: Boolean) {
        val mid: Int get() = ColorUtils.blendARGB(top, bottom, 0.5f)
    }

    /** What sits in the middle of the card. */
    sealed interface Content {
        /** A logo/wordmark with transparent background, centred. */
        class Logo(val bitmap: Bitmap) : Content
        /** A mark (transparent) or an opaque icon shown in a glossy bubble, followed by the app name. */
        class Lockup(val mark: Bitmap, val bubble: Boolean, val name: String, val textColor: Int) : Content
        /** Only the name as a wordmark (last resort: the app has no usable artwork at all). */
        class Text(val name: String, val textColor: Int) : Content
        /** The app's own official banner artwork, full bleed (when its logo can't be cut out). */
        class Full(val bitmap: Bitmap) : Content
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF1C1C1E.toInt()
    private val GRAPHITE = Look(0xFF4A5160.toInt(), 0xFF16191F.toInt(), 0xFF7F9CFF.toInt(), 0.35f, false)
    private val PEARL = Look(0xFFFFFFFF.toInt(), 0xFFDCE1E8.toInt(), null, 0f, true)

    @Volatile private var typeface: Typeface? = null

    private fun font(context: Context): Typeface {
        typeface?.let { return it }
        val tf = runCatching { ResourcesCompat.getFont(context, R.font.manrope_700) }.getOrNull()
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        typeface = tf
        return tf
    }

    // =====================================================================================
    // High level: everything from the app's banner and icon
    // =====================================================================================

    /**
     * What a card is made of, extracted once per app (kept small so cards can be re-rendered with
     * the user's own name/colour later): the banner wordmark, the icon mark/bubble art and colours.
     */
    class Parts internal constructor(
        internal val label: String,
        internal val keyed: Bitmap?,
        internal val keyColor: Int?,
        internal val mark: Bitmap?,
        internal val markIsBubble: Boolean,
        internal val iconColor: Int?,
        /** The official banner, card-sized, kept only when its logo could not be cut out ([Content.Full]). */
        internal val banner: Bitmap? = null,
    )

    /**
     * Builds the card of an app.
     * @param banner the app's TV banner rendered to a bitmap (e.g. 640×360), or null.
     * @param icon the app's launcher icon drawable (adaptive or legacy), or null.
     */
    fun build(context: Context, packageName: String, label: String, banner: Bitmap?, icon: Drawable?): Result =
        compose(context, packageName, extract(packageName, label, banner, icon))

    /** Extracts the logo parts of an app (the expensive pixel work). */
    fun extract(packageName: String, label: String, banner: Bitmap?, icon: Drawable?): Parts {
        val mode = KnownApps.brand(packageName)?.logo ?: CardLogo.Auto
        val keyed = if (mode == CardLogo.Auto || mode == CardLogo.Mark) banner?.let { runCatching { keyBanner(it) }.getOrNull() } else null
        val art = icon?.let { runCatching { iconArt(it) }.getOrNull() }
        // Keep the parts small: the card draws the wordmark at most ~350 px wide.
        val keyedLogo = keyed?.logo?.let { b ->
            if (b.width > 420) Bitmap.createScaledBitmap(b, 420, max(1, b.height * 420 / b.width), true) else b
        }
        // Banner whose logo can't be cut out (photo/gradient artwork): keep it, card-sized, for a full-bleed card.
        val full = if (keyed == null && (mode == CardLogo.Auto || mode == CardLogo.Mark)) banner?.let { coverScaled(it) } else null
        return Parts(
            label = label,
            keyed = keyedLogo,
            keyColor = keyed?.key,
            mark = art?.bitmap,
            markIsBubble = art?.bubble == true,
            iconColor = art?.bg ?: art?.bitmap?.let { paletteColor(it) } ?: full?.let { paletteColor(it) },
            banner = full,
        )
    }

    /** [b] centre-cropped and scaled to exactly the card size. */
    private fun coverScaled(b: Bitmap): Bitmap? = runCatching {
        val s = max(WIDTH.toFloat() / b.width, HEIGHT.toFloat() / b.height)
        val sw = (WIDTH / s).toInt().coerceIn(1, b.width)
        val sh = (HEIGHT / s).toInt().coerceIn(1, b.height)
        val crop = Bitmap.createBitmap(b, (b.width - sw) / 2, (b.height - sh) / 2, sw, sh)
        Bitmap.createScaledBitmap(crop, WIDTH, HEIGHT, true)
    }.getOrNull()

    /**
     * Renders the card from [parts].
     * @param name custom wordmark text (null = automatic). A custom name always shows: a wordmark-only
     *   card then becomes a mark + name lockup (or the name alone if the app has no icon).
     * @param color custom background colour, ARGB (null = curated/automatic brand colour).
     */
    fun compose(context: Context, packageName: String, parts: Parts, name: String? = null, color: Int? = null): Result {
        val brand = KnownApps.brand(packageName)
        val custom = name?.trim()?.takeIf { it.isNotEmpty() }
        val title = (custom ?: brand?.name ?: KnownApps.lookup(packageName)?.label ?: parts.label).trim().ifEmpty { parts.label }
        val mode = brand?.logo ?: CardLogo.Auto

        val useKeyed = parts.keyed != null && custom == null && mode != CardLogo.Text
        val mark = parts.mark
        // The logo that must stay readable (bubbles are opaque and carry their own background).
        var logo: Bitmap? = if (useKeyed) parts.keyed else mark?.takeIf { !parts.markIsBubble }
        val source: Int = (if (useKeyed) parts.keyColor else null) ?: parts.iconColor ?: parts.keyColor ?: KnownApps.Slate.toArgb()

        val keepLook = color != null || brand != null
        var look = when {
            color != null -> lookForUser(color)
            brand != null -> Look(brand.top.toArgb(), brand.bottom.toArgb(), brand.accent?.toArgb(), 0.55f, brand.light)
            else -> lookFor(source, logo?.let { meanLuminance(it) })
        }
        if (logo != null) {
            val fixed = ensureContrast(logo, look, keepLook)
            logo = fixed.first
            look = fixed.second
        }
        val darkText = ColorUtils.calculateLuminance(look.mid) > 0.45
        val textColor = brand?.text?.toArgb()?.takeIf { color == null && look.light } ?: if (darkText) INK else WHITE

        val content: Content = when {
            mode == CardLogo.Text -> Content.Text(title, textColor)
            // Official artwork unless the user asked for their own name or colour.
            parts.banner != null && custom == null && color == null -> Content.Full(parts.banner)
            useKeyed && logo != null -> {
                // A square-ish banner logo is just the icon on a plain banner: add the name.
                val aspect = logo.width.toFloat() / logo.height
                if (mode == CardLogo.Auto && aspect < 1.7f) Content.Lockup(logo, false, title, textColor)
                else Content.Logo(logo)
            }
            mark != null && parts.markIsBubble -> Content.Lockup(mark, true, title, textColor)
            logo != null ->
                if (mode == CardLogo.Mark && custom == null) Content.Logo(logo) else Content.Lockup(logo, false, title, textColor)
            else -> Content.Text(title, textColor)
        }
        val card = render(context, look, content, seed = packageName.hashCode())
        val brandColor = color ?: brand?.accent?.toArgb() ?: look.accent?.takeIf { brand != null } ?: look.mid
        return Result(card, brandColor or 0xFF000000.toInt())
    }

    // =====================================================================================
    // Low level: draw one card
    // =====================================================================================

    /** Renders a card ([WIDTH]×[HEIGHT], ARGB_8888, already [Bitmap.prepareToDraw]n). */
    fun render(context: Context, look: Look, content: Content, seed: Int = 0): Bitmap {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val k = h / 288f
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        // 1. Brand gradient, lighter top-left -> deeper bottom-right.
        p.shader = LinearGradient(0f, 0f, w, h, look.top, look.bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)

        // 2. Soft light from the top-left + brand accent glow from the bottom-right.
        if (!look.light) glow(c, p, 0.15f * w, -0.10f * h, 0.85f * w, ColorUtils.blendARGB(look.top, WHITE, 0.35f), 0.35f)
        look.accent?.let { glow(c, p, 0.95f * w, 1.15f * h, 0.75f * w, it, look.accentAlpha) }

        // 3. A few faint bubbles (deterministic per app).
        val rnd = java.util.Random(seed.toLong())
        val bubbleK = if (look.light) 0.5f else 1f
        repeat(3) {
            val cx = (0.05f + 0.90f * rnd.nextFloat()) * w
            val cy = (0.15f + 0.80f * rnd.nextFloat()) * h
            val r = (0.10f + 0.12f * rnd.nextFloat()) * h
            p.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(white(0.012f * bubbleK), white(0.014f * bubbleK), white(0.026f * bubbleK), white(0.057f * bubbleK)),
                floatArrayOf(0f, 0.5f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f * k
            p.color = white(0.055f * bubbleK)
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL
        }

        // 4. Floor reflection.
        glow(c, p, 0.5f * w, 1.25f * h, 0.62f * w, WHITE, if (look.light) 0.10f else 0.16f)

        if (content is Content.Full) {
            // Official banner, centre-cropped to the card; the gloss below makes it match the set.
            val b = content.bitmap
            val s = max(w / b.width, h / b.height)
            val dw = b.width * s
            val dh = b.height * s
            c.drawBitmap(b, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
        // 5. Content on its own layer, so one blurred shadow covers logo, bubble and text.
        val fg = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        drawContent(context, Canvas(fg), content, look, w, h, k)
        val blur = Paint().apply { maskFilter = BlurMaskFilter(9f * k, BlurMaskFilter.Blur.NORMAL) }
        val offset = IntArray(2)
        val shadow = fg.extractAlpha(blur, offset)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); alpha = if (look.light) 56 else 115 }
        c.drawBitmap(shadow, offset[0].toFloat(), offset[1] + 5f * k, sp)
        shadow.recycle()
        c.drawBitmap(fg, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        fg.recycle()
        }

        // 6. Curved gloss across the top ~46% (Frutiger Aero), with a softened edge.
        val glossK = if (look.light) 0.6f else 1f
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, 0.46f * h, white(0.22f * glossK), white(0.03f * glossK), Shader.TileMode.CLAMP)
            maskFilter = BlurMaskFilter(2.5f * k, BlurMaskFilter.Blur.NORMAL)
        }
        val ex = 0.5f * w
        val ey = -0.62f * h
        c.drawOval(RectF(ex - 1.0f * w, ey - 1.08f * h, ex + 1.0f * w, ey + 1.08f * h), gp)

        out.prepareToDraw()
        return out
    }

    private fun drawContent(context: Context, c: Canvas, content: Content, look: Look, w: Float, h: Float, k: Float) {
        val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (content) {
            is Content.Logo -> {
                val b = content.bitmap
                val s = min(0.68f * w / b.width, 0.46f * h / b.height)
                val dw = b.width * s
                val dh = b.height * s
                c.drawBitmap(b, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), bp)
            }
            is Content.Lockup -> {
                val m = content.mark
                val markH = 0.42f * h
                val markW = if (content.bubble) markH else min(markH * m.width / m.height, 1.4f * markH)
                val markHd = if (content.bubble) markH else markW * m.height / m.width
                val gap = 0.065f * h
                val tp = textPaint(context, content.textColor, 0.17f * h)
                val avail = 0.84f * w - markW - gap
                val text = fitText(content.name, tp, avail, 0.115f * h)
                val tw = tp.measureText(text)
                val x0 = (w - (markW + gap + tw)) / 2f
                val rect = RectF(x0, (h - markHd) / 2f, x0 + markW, (h + markHd) / 2f)
                if (content.bubble) drawBubble(c, m, rect, look.light, k) else c.drawBitmap(m, null, rect, bp)
                c.drawText(text, x0 + markW + gap, baseline(tp, h / 2f), tp)
            }
            is Content.Full -> Unit // drawn directly in render()
            is Content.Text -> {
                val tp = textPaint(context, content.textColor, 0.24f * h)
                val text = fitText(content.name, tp, 0.80f * w, 0.13f * h)
                c.drawText(text, (w - tp.measureText(text)) / 2f, baseline(tp, h / 2f), tp)
            }
        }
    }

    /** The icon inside a glossy rounded "bubble" (squircle-ish rounded square). */
    private fun drawBubble(c: Canvas, icon: Bitmap, r: RectF, light: Boolean, k: Float) {
        val s = r.width()
        val radius = 0.24f * s
        val path = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        val save = c.save()
        c.clipPath(path)
        c.drawBitmap(icon, null, r, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        // Glossy cap over the upper half.
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, r.top, 0f, r.top + 0.5f * s, white(0.40f), white(0.08f), Shader.TileMode.CLAMP)
        }
        val cx = r.centerX()
        val cy = r.top - 0.28f * s
        c.drawOval(RectF(cx - 0.75f * s, cy - 0.78f * s, cx + 0.75f * s, cy + 0.78f * s), gp)
        // Soft inner light at the bottom.
        val bottomGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, r.bottom + 0.15f * s, 0.6f * s, white(0.18f), white(0f), Shader.TileMode.CLAMP)
        }
        c.drawRect(r, bottomGlow)
        c.restoreToCount(save)
        // Rim.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * k
            shader = LinearGradient(
                0f, r.top, 0f, r.bottom,
                white(if (light) 0.9f else 0.45f), if (light) 0x26000000 else white(0.10f),
                Shader.TileMode.CLAMP,
            )
        }
        val inset = 1f * k
        c.drawRoundRect(RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), radius - inset, radius - inset, rim)
    }

    private fun textPaint(context: Context, color: Int, size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = font(context)
        this.color = color
        textSize = size
        letterSpacing = -0.01f
    }

    /** Shrinks the text size down to [minSize] to fit [avail], then ellipsizes. */
    private fun fitText(text: String, tp: TextPaint, avail: Float, minSize: Float): String {
        while (tp.measureText(text) > avail && tp.textSize > minSize) tp.textSize = max(minSize, tp.textSize * 0.94f)
        return if (tp.measureText(text) <= avail) text
        else TextUtils.ellipsize(text, tp, avail, TextUtils.TruncateAt.END).toString()
    }

    /** Baseline that centres the cap height on [cy]. */
    private fun baseline(tp: TextPaint, cy: Float): Float {
        val b = Rect()
        tp.getTextBounds("H", 0, 1, b)
        return cy + b.height() / 2f
    }

    private fun glow(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        // Smoothstep falloff.
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val colors = IntArray(stops.size) { i ->
            val t = 1f - stops[i]
            ColorUtils.setAlphaComponent(color, (255f * alpha * t * t * (3f - 2f * t)).toInt().coerceIn(0, 255))
        }
        p.shader = RadialGradient(cx, cy, r, colors, stops, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), p)
        p.shader = null
    }

    private fun white(a: Float): Int = ColorUtils.setAlphaComponent(WHITE, (a * 255f).toInt().coerceIn(0, 255))

    // =====================================================================================
    // Brand colours
    // =====================================================================================

    /** Card background from one source colour; keeps white logos readable, avoids muddy greys. */
    fun lookFor(color: Int, logoLum: Float?): Look {
        val parts = FloatArray(3)
        ColorUtils.colorToHSL(color or 0xFF000000.toInt(), parts)
        val hue = parts[0]
        var s = parts[1]
        var l = parts[2]
        val logoLight = logoLum?.let { it > 0.45f }
        if (l > 0.86f && logoLight != true) return PEARL
        if (s < 0.14f || (s < 0.30f && l < 0.30f)) return GRAPHITE
        s = s.coerceAtLeast(0.32f)
        l = l.coerceIn(0.22f, if (logoLight == false) 0.62f else 0.50f)
        val top = hsl(hue - 6f, s, min(l + 0.10f, 0.66f))
        val bottom = hsl(hue + 4f, min(s * 1.05f, 1f), max(l - 0.17f, 0.07f))
        val accent = hsl(hue + 28f, s, min(l + 0.18f, 0.70f))
        return Look(top, bottom, accent, 0.40f, false)
    }

    /** Card background for a colour the user picked: follows the colour closely (no clamping). */
    fun lookForUser(color: Int): Look {
        val parts = FloatArray(3)
        ColorUtils.colorToHSL(color or 0xFF000000.toInt(), parts)
        val hue = parts[0]
        val s = parts[1]
        val l = parts[2]
        if (l > 0.88f && s < 0.4f) return PEARL
        if (s < 0.12f) {
            return Look(hsl(hue, s, min(l + 0.12f, 0.95f)), hsl(hue, s, max(l - 0.14f, 0.03f)), null, 0f, l > 0.62f)
        }
        val top = hsl(hue - 6f, s, min(l + 0.10f, 0.92f))
        val bottom = hsl(hue + 4f, min(s * 1.05f, 1f), max(l - 0.17f, 0.04f))
        val accent = hsl(hue + 28f, s, min(l + 0.18f, 0.85f))
        return Look(top, bottom, accent, 0.35f, l > 0.62f)
    }

    private fun hsl(h: Float, s: Float, l: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))

    private fun paletteColor(b: Bitmap): Int? = runCatching {
        val p = Palette.from(b).maximumColorCount(16).generate()
        (p.vibrantSwatch ?: p.dominantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch)?.rgb
    }.getOrNull()

    /**
     * Makes sure [logo] reads on [look]: on dark cards dark neutral parts turn light (dark-mode logo),
     * then the whole logo turns white if still needed; a white logo on a pearl card moves to graphite.
     */
    private fun ensureContrast(logo: Bitmap, look: Look, keepLook: Boolean): Pair<Bitmap, Look> {
        val cardLum = ColorUtils.calculateLuminance(look.mid).toFloat()
        var lum = meanLuminance(logo)
        if (ratio(lum, cardLum) >= 1.8f) return logo to look
        if (look.light) return if (keepLook) silhouette(logo, INK) to look else logo to GRAPHITE
        if (cardLum > 0.4f) return silhouette(logo, INK) to look
        val inverted = invertNeutrals(logo)
        lum = meanLuminance(inverted)
        if (ratio(lum, cardLum) >= 1.8f) return inverted to look
        return silhouette(logo, WHITE) to look
    }

    private fun ratio(a: Float, b: Float): Float = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)

    // =====================================================================================
    // Pixel helpers
    // =====================================================================================

    private fun pixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun lin(c: Int): Float {
        val v = c / 255f
        return v * v * (0.7f + 0.3f * v) // ~ sRGB -> linear, cheap
    }

    /** Alpha-weighted mean relative luminance of the visible pixels (0..1). */
    private fun meanLuminance(b: Bitmap): Float {
        val px = pixels(b)
        var sum = 0.0
        var wsum = 0.0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val a = (p ushr 24) / 255f
            if (a > 0.05f) {
                val y = 0.2126f * lin((p shr 16) and 0xFF) + 0.7152f * lin((p shr 8) and 0xFF) + 0.0722f * lin(p and 0xFF)
                sum += y * a
                wsum += a
            }
            i += 2
        }
        return if (wsum <= 0.0) 1f else (sum / wsum).toFloat()
    }

    /** Low-saturation (grey/black) pixels get their lightness inverted; colours stay. */
    private fun invertNeutrals(b: Bitmap): Bitmap {
        val px = pixels(b)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            val mx = max(r, max(g, bl))
            val mn = min(r, min(g, bl))
            val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            if (sat < 0.25f && mx < 150) px[i] = (a shl 24) or ((255 - r) shl 16) or ((255 - g) shl 8) or (255 - bl)
        }
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    private fun silhouette(b: Bitmap, color: Int): Bitmap {
        val px = pixels(b)
        val rgb = color and 0x00FFFFFF
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or rgb
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /** Crops to the visible pixels (alpha > 16) plus a small margin; null if empty. */
    private fun trim(b: Bitmap): Bitmap? {
        val w = b.width
        val h = b.height
        val px = pixels(b)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        val pad = 2
        minX = max(0, minX - pad); minY = max(0, minY - pad)
        maxX = min(w - 1, maxX + pad); maxY = min(h - 1, maxY + pad)
        return Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    // =====================================================================================
    // Logo extraction
    // =====================================================================================

    private class Keyed(val logo: Bitmap, val key: Int?)

    /**
     * Extracts the logo from a banner with a plain background: detects the background colour on the
     * border and removes it ("colour to alpha"), keeping anti-aliased edges. Null for photo/gradient banners.
     */
    private fun keyBanner(src: Bitmap): Keyed? {
        val w = src.width
        val h = src.height
        if (w < 32 || h < 18) return null
        val px = pixels(src)
        val m = max(2, h / 40)
        val samples = ArrayList<Int>(2 * (w / 4) + 2 * (h / 4) + 8)
        var x = 0
        while (x < w) { samples += px[m * w + x]; samples += px[(h - 1 - m) * w + x]; x += 4 }
        var y = 0
        while (y < h) { samples += px[y * w + m]; samples += px[y * w + (w - 1 - m)]; y += 4 }

        // Transparent banner background: the banner already is a logo.
        val clear = samples.count { (it ushr 24) < 40 }
        if (clear > samples.size * 0.9f) {
            val t = trim(src) ?: return null
            if (t.width * t.height > 0.92f * w * h) return null
            return Keyed(t, paletteColor(t))
        }

        // Most common (quantised) border colour.
        val counts = HashMap<Int, Int>()
        for (s in samples) {
            val q = (((s shr 16) and 0xF0) shl 4) or ((s shr 8) and 0xF0) or ((s and 0xF0) shr 4)
            counts[q] = (counts[q] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return null
        val ref = samples.first { s -> ((((s shr 16) and 0xF0) shl 4) or ((s shr 8) and 0xF0) or ((s and 0xF0) shr 4)) == best }
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (s in samples) {
            if ((s ushr 24) < 200) continue
            if (maxDiff(s, ref) <= 26) { sr += (s shr 16) and 0xFF; sg += (s shr 8) and 0xFF; sb += s and 0xFF; n++ }
        }
        if (n < samples.size * 0.85f) return null
        val kr = (sr / n).toInt(); val kg = (sg / n).toInt(); val kb = (sb / n).toInt()
        val key = 0xFF000000.toInt() or (kr shl 16) or (kg shl 8) or kb
        // Background noise/gradient on the border raises the cut-off, so a textured or vignetted
        // background does not turn into a translucent haze around the logo.
        var noise = 0L
        var nn = 0
        for (s in samples) if ((s ushr 24) >= 200 && maxDiff(s, ref) <= 26) { noise += maxDiff(s, key); nn++ }
        val cut = (0.08f + 3f * (if (nn == 0) 0f else noise.toFloat() / nn) / 255f).coerceAtMost(0.30f)

        // Colour to alpha.
        val out = IntArray(px.size)
        var sumA = 0.0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var haze = 0
        var solid = 0
        for (i in px.indices) {
            val p = px[i]
            val pa = (p ushr 24) / 255f
            if (pa <= 0f) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val a = max(chanAlpha(r, kr), max(chanAlpha(g, kg), chanAlpha(b, kb)))
            if (a <= cut) continue
            val fr = (((r - kr) / a) + kr).toInt().coerceIn(0, 255)
            val fg = (((g - kg) / a) + kg).toInt().coerceIn(0, 255)
            val fb = (((b - kb) / a) + kb).toInt().coerceIn(0, 255)
            val oa = ((a - cut) / (1f - cut) * pa).coerceIn(0f, 1f)
            val ai = (oa * 255f).toInt()
            out[i] = (ai shl 24) or (fr shl 16) or (fg shl 8) or fb
            sumA += oa
            if (ai < 115) { if (ai > 10) haze++ } else solid++
            if (ai >= 115) {
                val xx = i % w
                val yy = i / w
                if (xx < minX) minX = xx
                if (xx > maxX) maxX = xx
                if (yy < minY) minY = yy
                if (yy > maxY) maxY = yy
            }
        }
        if (maxX < minX || maxY < minY) return null
        val area = (w * h).toFloat()
        val coverage = (sumA / area).toFloat()
        val boxArea = (maxX - minX + 1) * (maxY - minY + 1)
        if (coverage < 0.008f || coverage > 0.45f || boxArea > 0.92f * area) return null
        // Mostly translucent = background texture, not a logo.
        if (haze > 2 * solid) return null
        val pad = 2
        minX = max(0, minX - pad); minY = max(0, minY - pad)
        maxX = min(w - 1, maxX + pad); maxY = min(h - 1, maxY + pad)
        val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val logo = Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
        return Keyed(logo, key)
    }

    private fun chanAlpha(p: Int, k: Int): Float = when {
        p > k -> (p - k) / (255f - k)
        p < k -> (k - p) / k.toFloat()
        else -> 0f
    }

    private fun maxDiff(a: Int, b: Int): Int = max(
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        max(kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), kotlin.math.abs((a and 0xFF) - (b and 0xFF))),
    )

    /** [bitmap] is a transparent mark, or (bubble = true) an opaque square icon for a bubble. */
    private class IconArt(val bitmap: Bitmap, val bubble: Boolean, val bg: Int?)

    private fun iconArt(d: Drawable): IconArt? {
        if (d is AdaptiveIconDrawable) {
            val size = 384
            val inset = size / 6 // the 72dp safe zone of the 108dp canvas
            val bgColor: Int? = when (val bg = d.background) {
                is ColorDrawable -> bg.color
                null -> null
                else -> runCatching { paletteColor(bg.toBitmap(48, 48)) }.getOrNull()
            }
            val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.foreground?.let { fg ->
                fg.setBounds(0, 0, size, size)
                fg.draw(Canvas(full))
            }
            val safe = Bitmap.createBitmap(full, inset, inset, size - 2 * inset, size - 2 * inset)
            val mark = if (opaqueFraction(safe) > 0.80f) null else trim(safe)
            if (mark != null) return IconArt(mark, false, bgColor)
            // Full-bleed foreground (e.g. a picture): show the whole icon in a bubble.
            val sq = 256
            val bubble = Bitmap.createBitmap(sq, sq, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bubble)
            val ext = sq / 4 // 72dp visible area -> 108dp layer bounds
            d.background?.let { it.setBounds(-ext, -ext, sq + ext, sq + ext); it.draw(cv) }
            d.foreground?.let { it.setBounds(-ext, -ext, sq + ext, sq + ext); it.draw(cv) }
            return IconArt(bubble, true, bgColor ?: paletteColor(bubble))
        }
        val bmp = d.toBitmap(256, 256, Bitmap.Config.ARGB_8888)
        return if (opaqueCorners(bmp)) {
            IconArt(bmp, true, paletteColor(bmp))
        } else {
            val mark = trim(bmp) ?: return null
            IconArt(mark, false, paletteColor(mark))
        }
    }

    private fun opaqueFraction(b: Bitmap): Float {
        val px = pixels(b)
        var n = 0
        var i = 0
        var total = 0
        while (i < px.size) { if ((px[i] ushr 24) > 200) n++; total++; i += 2 }
        return if (total == 0) 0f else n / total.toFloat()
    }

    /** A legacy icon with its own square background (no transparent corners). */
    private fun opaqueCorners(b: Bitmap): Boolean {
        val i = (b.width * 0.06f).toInt()
        val w = b.width - 1 - i
        val h = b.height - 1 - i
        return listOf(b.getPixel(i, i), b.getPixel(w, i), b.getPixel(i, h), b.getPixel(w, h)).all { (it ushr 24) > 230 }
    }
}
