package app.halo.launcher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.halo.launcher.R

/**
 * The Halo design is drawn on a 1920×1080 canvas. [Ui.k] converts one design
 * pixel into dp for the current screen, so every number from the design file
 * can be used unchanged (e.g. `320.px`).
 */
object Ui {
    var k: Float = 0.5f
    var fontScale: Float = 1f
}

val Int.px: Dp get() = (this * Ui.k).dp
val Float.px: Dp get() = (this * Ui.k).dp
val Double.px: Dp get() = (this.toFloat() * Ui.k).dp
val Int.tsp: TextUnit get() = (this * Ui.k / Ui.fontScale).sp
val Float.tsp: TextUnit get() = (this * Ui.k / Ui.fontScale).sp

val Manrope = FontFamily(
    Font(R.font.manrope_300, FontWeight.Light),
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
)

fun hstyle(size: Int, weight: FontWeight = FontWeight.Medium, color: Color = Color.Unspecified, tracking: Float = 0f) =
    TextStyle(
        fontFamily = Manrope,
        fontSize = size.tsp,
        fontWeight = weight,
        color = color,
        letterSpacing = tracking.em,
    )

/** All colours of the interface. `light` blends from 0 (night) to 1 (day). */
@Immutable
data class HaloColors(
    val light: Float,
    val text: Color,
    val sub: Color,
    val track: Color,
    val glass: Color,
    val glassStrong: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val rimLine: Color,
    val shadow: Color,
    val sheen: Color,
    val cta: Color,
    val ctaText: Color,
    val pill: Color,
    val dimScrim: Color,
    val base: Color,
    /** Aero accent: a clear sky/aqua blue used for reflections and highlights. */
    val aqua: Color,
) {
    val isDark: Boolean get() = light < 0.5f

    companion object {
        private val Dark = HaloColors(
            light = 0f,
            text = Color(0xFFF5F6F8),
            sub = Color(0xB8F5F6F8),
            track = Color(0x2EFFFFFF),
            glass = Color(0x1FBFE6FF),
            glassStrong = Color(0x30C8EBFF),
            rimTop = Color(0xB3FFFFFF),
            rimBottom = Color(0x1FFFFFFF),
            rimLine = Color(0x1AFFFFFF),
            shadow = Color(0x8C000000),
            sheen = Color(0x38FFFFFF),
            cta = Color(0xFFF5F6F8),
            ctaText = Color(0xFF0A0C12),
            pill = Color(0x1AFFFFFF),
            dimScrim = Color(0x5905060D),
            base = Color(0xFF070A18),
            aqua = Color(0xFF6FD3FF),
        )
        private val Light = HaloColors(
            light = 1f,
            text = Color(0xFF12141A),
            sub = Color(0xFF3E4350),
            track = Color(0x2412141A),
            glass = Color(0x73F2FAFF),
            glassStrong = Color(0x99F7FCFF),
            rimTop = Color(0xF2FFFFFF),
            rimBottom = Color(0x80FFFFFF),
            rimLine = Color(0x80FFFFFF),
            shadow = Color(0x4D1E2846),
            sheen = Color(0x99FFFFFF),
            cta = Color(0xFF12141A),
            ctaText = Color(0xFFFFFFFF),
            pill = Color(0x0F12141A),
            dimScrim = Color(0x59EEF2F7),
            base = Color(0xFF7FA6DC),
            aqua = Color(0xFF2FA8E8),
        )

        fun at(t: Float): HaloColors {
            val a = Dark
            val b = Light
            return HaloColors(
                light = t,
                text = lerp(a.text, b.text, t),
                sub = lerp(a.sub, b.sub, t),
                track = lerp(a.track, b.track, t),
                glass = lerp(a.glass, b.glass, t),
                glassStrong = lerp(a.glassStrong, b.glassStrong, t),
                rimTop = lerp(a.rimTop, b.rimTop, t),
                rimBottom = lerp(a.rimBottom, b.rimBottom, t),
                rimLine = lerp(a.rimLine, b.rimLine, t),
                shadow = lerp(a.shadow, b.shadow, t),
                sheen = lerp(a.sheen, b.sheen, t),
                cta = lerp(a.cta, b.cta, t),
                ctaText = lerp(a.ctaText, b.ctaText, t),
                pill = lerp(a.pill, b.pill, t),
                dimScrim = lerp(a.dimScrim, b.dimScrim, t),
                base = lerp(a.base, b.base, t),
                aqua = lerp(a.aqua, b.aqua, t),
            )
        }
    }
}

@Immutable
data class HaloTheme(val colors: HaloColors, val motion: Boolean)

val LocalHalo = staticCompositionLocalOf { HaloTheme(HaloColors.at(0f), true) }

/**
 * Day/night light level (0 night → 1 day), animated. Only the wallpaper reads it, so a theme
 * change cross-fades the sky without recomposing the whole UI every frame.
 */
val LocalSkyLight = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

@Composable
fun HaloThemeProvider(dark: Boolean, motion: Boolean, content: @Composable () -> Unit) {
    val light = animateFloatAsState(
        targetValue = if (dark) 0f else 1f,
        animationSpec = tween(1600),
        label = "theme",
    )
    // UI colours snap; the backdrop cross-fades via LocalSkyLight.
    val theme = remember(dark, motion) { HaloTheme(HaloColors.at(if (dark) 0f else 1f), motion) }
    CompositionLocalProvider(LocalHalo provides theme, LocalSkyLight provides light, content = content)
}

/** Shared spring presets, tuned after tvOS (see research notes). */
object Motion {
    fun <T> focusIn() = spring<T>(dampingRatio = 0.78f, stiffness = 420f)
    fun <T> focusOut() = spring<T>(dampingRatio = 0.68f, stiffness = 300f)
    fun <T> wobble() = spring<T>(dampingRatio = 0.42f, stiffness = 260f)
    fun <T> bounce() = spring<T>(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)
    fun <T> gentle() = spring<T>(dampingRatio = 1f, stiffness = Spring.StiffnessLow)
}

val Zero = 0.dp
