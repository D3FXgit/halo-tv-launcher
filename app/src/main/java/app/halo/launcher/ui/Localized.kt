package app.halo.launcher.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.halo.launcher.R
import app.halo.launcher.data.MAIN_PROFILE
import app.halo.launcher.data.Profile
import app.halo.launcher.data.ThemeMode
import app.halo.launcher.data.TopRow
import app.halo.launcher.i18n.Lang
import app.halo.launcher.wallpaper.WallpaperCollection
import java.util.Locale

/*
 * Small helpers that turn Halo's data (collection ids, intervals, modes …) into text in the
 * interface language. Composable variants read the current configuration; the Context variants
 * are for non-UI code (assistant, ViewModel) and go through [Lang.string].
 */

/** Locale for dates and times in the interface language (e.g. "Mittwoch, 29. Oktober"). */
@Composable
fun appLocale(): Locale {
    val configured = LocalConfiguration.current.locales.get(0)
    return Lang.localeFor(stringResource(R.string.lang_code), configured)
}

/** Plural string (pluralStringResource without the experimental opt-in). */
@Composable
fun pluralResource(@PluralsRes id: Int, count: Int, vararg args: Any): String {
    LocalConfiguration.current // recompose when the configuration changes
    return LocalContext.current.resources.getQuantityString(id, count, *args)
}

// ---- Background collections ---------------------------------------------------------

@StringRes
fun collectionTitleRes(id: String): Int = when (id) {
    "scene" -> R.string.col_scene_title
    "daily" -> R.string.col_daily_title
    "landscapes" -> R.string.col_landscapes_title
    "forest" -> R.string.col_forest_title
    "aero" -> R.string.col_aero_title
    "space" -> R.string.col_space_title
    "ocean" -> R.string.col_ocean_title
    "night" -> R.string.col_night_title
    "photos" -> R.string.col_photos_title
    else -> 0
}

@StringRes
fun collectionDescriptionRes(id: String): Int = when (id) {
    "scene" -> R.string.col_scene_desc
    "daily" -> R.string.col_daily_desc
    "landscapes" -> R.string.col_landscapes_desc
    "forest" -> R.string.col_forest_desc
    "aero" -> R.string.col_aero_desc
    "space" -> R.string.col_space_desc
    "ocean" -> R.string.col_ocean_desc
    "night" -> R.string.col_night_desc
    "photos" -> R.string.col_photos_desc
    else -> 0
}

/** Collection name in the interface language (unknown collections keep their own title). */
@Composable
fun collectionTitle(col: WallpaperCollection): String =
    collectionTitleRes(col.id).let { if (it != 0) stringResource(it) else col.title }

@Composable
fun collectionDescription(col: WallpaperCollection): String =
    collectionDescriptionRes(col.id).let { if (it != 0) stringResource(it) else col.description }

fun collectionTitle(context: Context, col: WallpaperCollection): String =
    collectionTitleRes(col.id).let { if (it != 0) Lang.string(context, it) else col.title }

// ---- Background interval ----------------------------------------------------------------

/** "Keep one", "5 min", "1 h", "Daily" … */
@Composable
fun intervalText(minutes: Int): String = when (minutes) {
    0 -> stringResource(R.string.interval_keep)
    60 -> stringResource(R.string.interval_hour)
    1440 -> stringResource(R.string.interval_daily)
    else -> stringResource(R.string.interval_minutes, minutes)
}

fun intervalText(context: Context, minutes: Int): String = when (minutes) {
    0 -> Lang.string(context, R.string.interval_keep)
    60 -> Lang.string(context, R.string.interval_hour)
    1440 -> Lang.string(context, R.string.interval_daily)
    else -> Lang.string(context, R.string.interval_minutes, minutes)
}

// ---- Modes ----------------------------------------------------------------------------------

@StringRes
fun topRowTitleRes(mode: TopRow): Int = when (mode) {
    TopRow.Favourites -> R.string.toprow_favourites
    TopRow.Recent -> R.string.toprow_recent
    TopRow.Popular -> R.string.toprow_popular
}

@StringRes
fun themeRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.Auto -> R.string.theme_auto
    ThemeMode.Dark -> R.string.theme_dark
    ThemeMode.Light -> R.string.theme_light
}

/** The main profile's default name ("Me") follows the interface language until it is renamed. */
@Composable
fun profileName(p: Profile): String =
    if (p.id == MAIN_PROFILE && p.name == DEFAULT_PROFILE_NAME) stringResource(R.string.profile_default_name) else p.name

fun profileName(context: Context, p: Profile): String =
    if (p.id == MAIN_PROFILE && p.name == DEFAULT_PROFILE_NAME) Lang.string(context, R.string.profile_default_name) else p.name

/** HaloSettings' built-in name of the main profile (data/Models.kt). */
const val DEFAULT_PROFILE_NAME = "Me"
