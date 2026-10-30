package app.halo.launcher.data

import android.content.ComponentName
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.Serializable

/** An installed, launchable app. [key] is the flattened component name. */
@Immutable
data class AppEntry(
    val key: String,
    val packageName: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val autoTint: Color,
    /** Dominant colour of the app's banner (drives the background glow), if it has one. */
    val bannerTint: Color? = null,
    /** Apple-TV style logo: the adaptive icon's foreground (or the plain icon), transparent background. */
    val logo: ImageBitmap? = null,
    /** Background colour for the logo box: the adaptive icon's background colour, else from the icon. */
    val logoBg: Color? = null,
    /**
     * Uniform 16:9 Halo app card (brand background + centred logo/wordmark + gloss), pre-rendered once
     * at load so tiles only draw one bitmap. Null until rendered.
     */
    val card: ImageBitmap? = null,
    /** Brand colour of the app (from the curated brand list, the banner or the icon). */
    val brand: Color? = null,
    val component: ComponentName,
    val isTvApp: Boolean,
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = this@AppEntry.component
        addCategory(if (isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

/**
 * How a tile is drawn.
 * Card = uniform Halo app card (default for every app).
 * Logo = Apple TV style: a coloured box with the app's logo in the middle.
 */
enum class TileSource { Card, Logo, Glass, Banner, Icon }

enum class TileLayout { Both, Glyph, Name }

/** User overrides for a tile. Null fields fall back to the defaults. */
@Serializable
data class TileStyle(
    val source: String? = null,
    val glyph: String? = null,
    /** "#RRGGBB" or "clear". */
    val tint: String? = null,
    val layout: String? = null,
    val label: String? = null,
    /** Logo box background "#RRGGBB" (null = automatic from the app's icon). */
    val bg: String? = null,
    /** Logo size inside the box, 0.4–0.9. */
    val logoScale: Float? = null,
    /** Borrow the artwork (card, banner, logo, icon, colours) of another installed app, by its key. */
    val artFrom: String? = null,
)

/** Final, resolved look of a tile. */
@Immutable
data class Tile(
    val app: AppEntry,
    val source: TileSource,
    val glyph: Glyph,
    /** null = clear glass. */
    val tint: Color?,
    val layout: TileLayout,
    val label: String,
    /** Box colour for [TileSource.Logo]. */
    val bg: Color = Color(0xFF2C2C2E),
    val logoScale: Float = 0.62f,
)

@Serializable
data class HomeLayout(
    val favourites: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val styles: Map<String, TileStyle> = emptyMap(),
    val initialised: Boolean = false,
    /** Most recently opened apps first (opened from Halo). */
    val recent: List<String> = emptyList(),
    /** How often each app was opened from Halo. */
    val launches: Map<String, Int> = emptyMap(),
)

/** What the first (top) row shows. */
enum class TopRow(val title: String) {
    Favourites("Favourites"), Recent("Recently used"), Popular("Most used");
    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: Favourites
    }
}

enum class ThemeMode { Auto, Dark, Light }

@Serializable
data class HaloSettings(
    val theme: String = ThemeMode.Auto.name,
    val motion: Boolean = true,
    /** 0 = ambient mode off. */
    val ambientMinutes: Int = 5,
    /** Used only when [useDeviceLocation] is off. */
    val city: String = "",
    /** Weather follows where the TV is (system location, else the internet connection's location). */
    val useDeviceLocation: Boolean = true,
    val clock24h: Boolean = true,
    val showWeather: Boolean = true,
    /** Halo's speaking voice: a Gemini voice name, or "system" for the TV's own text-to-speech. */
    val voice: String = "Sulafat",
    /**
     * Default look of app tiles: "halo" (uniform Halo cards), "banner" (each app's own TV banner,
     * like the stock launcher) or "icon" (the app icon). Per-app choices in Customize still win.
     */
    val tileLook: String = "halo",
    /**
     * Remote button that opens the Halo assistant over any app (needs the Halo service):
     * "off" or "star" (the remote's star button).
     */
    val assistantButton: String = "off",
    /** Navigation sounds (the TV's own focus tick and click). */
    val sounds: Boolean = true,
    /** The one-time setup wizard has been completed (or skipped). */
    val setupDone: Boolean = false,
    /** Interface + assistant language: "system", "en" or "de". */
    val language: String = "system",
    /** The user's own Gemini API key (entered in setup – on the TV or from a phone via QR code). */
    val geminiKey: String = "",
    /** Bring Halo back whenever the stock launcher opens (needs the Halo accessibility service). */
    val homeOverride: Boolean = false,
    /** Package opened by the remote's YouTube button (null = leave the button alone). */
    val youtubeButtonTarget: String? = null,
    val profiles: List<Profile> = listOf(Profile(MAIN_PROFILE, "Me", 0)),
    val activeProfile: String = MAIN_PROFILE,
) {
    val themeMode: ThemeMode get() = runCatching { ThemeMode.valueOf(theme) }.getOrDefault(ThemeMode.Auto)

    val profile: Profile
        get() = profiles.firstOrNull { it.id == activeProfile } ?: profiles.firstOrNull() ?: Profile(MAIN_PROFILE, "Me", 0)
}

/** A Halo profile. Each profile has its own favourites, order, hidden apps and icons. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Index into [PROFILE_COLORS]. */
    val color: Int = 0,
    /** Avatar id from [Avatars] (null = coloured initial). */
    val avatar: String? = null,
    /** Background id from the wallpaper library ("scene" = animated Halo scene). */
    val wallpaper: String = "scene",
    /** [TopRow] name. */
    val topRow: String = TopRow.Favourites.name,
    /** Minutes between background pictures (0 = keep one picture). */
    val wallpaperMinutes: Int = 15,
    /** A specific picture chosen from the library (its key), null = rotate through the collection. */
    val wallpaperPin: String? = null,
    /** Show the artwork of what's playing as the background while media plays. */
    val mediaBackdrop: Boolean = true,
) {
    val initial: String get() = name.trim().take(1).uppercase().ifEmpty { "?" }
    val colors: Pair<Color, Color> get() = PROFILE_COLORS[Math.floorMod(color, PROFILE_COLORS.size)]
}

const val MAIN_PROFILE = "main"
const val MAX_PROFILES = 6

val PROFILE_COLORS: List<Pair<Color, Color>> = listOf(
    Color(0xFF5B6CF0) to Color(0xFF9B5DE5),
    Color(0xFF2BB3A3) to Color(0xFF3BA7E0),
    Color(0xFFE8A33D) to Color(0xFFE46A8B),
    Color(0xFF6FA77A) to Color(0xFF2BB3A3),
    Color(0xFFE46A8B) to Color(0xFF9B5DE5),
    Color(0xFF5E6B82) to Color(0xFF3A3F4B),
)

const val MAX_FAVOURITES = 5
