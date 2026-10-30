package app.halo.launcher.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.data.Avatars
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import app.halo.launcher.data.PROFILE_COLORS
import app.halo.launcher.data.ThemeMode
import app.halo.launcher.i18n.Lang
import app.halo.launcher.ui.collectionTitle
import app.halo.launcher.ui.Avatar
import app.halo.launcher.ui.ControlState
import app.halo.launcher.ui.HaloIcon
import app.halo.launcher.ui.HaloTextField
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.ScrollPadding
import app.halo.launcher.ui.TogglePill
import app.halo.launcher.ui.control
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import app.halo.launcher.wallpaper.WallpaperCollection
import app.halo.launcher.wallpaper.WallpaperRepository
import kotlinx.coroutines.delay
import java.util.Locale

internal val GlobeIcon = IconSpec(
    listOf(
        "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
        "M3.5 9h17M3.5 15h17",
        "M12 3c2.4 2.5 3.6 5.5 3.6 9s-1.2 6.5-3.6 9c-2.4-2.5-3.6-5.5-3.6-9s1.2-6.5 3.6-9z",
    )
)

// ==== 1. Welcome + language ========================================================

@Composable
internal fun LanguageStep(vm: HaloViewModel, settings: HaloSettings) {
    val context = LocalContext.current
    val ui = LocalContext.current.resources.configuration.locales.get(0) ?: Locale.getDefault()
    val system = remember {
        android.content.res.Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
    }
    // What is actually in effect (the per-app language can also be changed in the TV's settings).
    val current = remember(settings.language) { Lang.current(context) }
    fun choose(code: String) {
        vm.updateSettings { it.copy(language = code) }
        context.findActivity()?.let { Lang.set(it, code) }
    }
    Column {
        StepHeader(stringResource(R.string.setup_welcome_title), stringResource(R.string.setup_welcome_subtitle))
        Spacer(Modifier.height(64.px))
        GroupLabel(stringResource(R.string.setup_language_label))
        Row(horizontalArrangement = Arrangement.spacedBy(28.px)) {
            ChoiceCard(
                title = stringResource(R.string.setup_language_system),
                subtitle = system.getDisplayName(ui).replaceFirstChar { it.titlecase(ui) },
                icon = GlobeIcon,
                selected = current == "system",
                onClick = { choose("system") },
            )
            ChoiceCard(
                title = stringResource(R.string.setup_language_en),
                subtitle = Locale.ENGLISH.getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) },
                icon = null,
                selected = current == "en",
                onClick = { choose("en") },
            )
            ChoiceCard(
                title = stringResource(R.string.setup_language_de),
                subtitle = Locale.GERMAN.getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) },
                icon = null,
                selected = current == "de",
                onClick = { choose("de") },
            )
        }
        Spacer(Modifier.height(40.px))
        SetupText(stringResource(R.string.setup_welcome_hint))
    }
}

// ==== 2. Theme =====================================================================

@Composable
internal fun ThemeStep(vm: HaloViewModel, settings: HaloSettings) {
    Column {
        StepHeader(stringResource(R.string.setup_theme_title), stringResource(R.string.setup_theme_subtitle))
        Spacer(Modifier.height(64.px))
        Row(horizontalArrangement = Arrangement.spacedBy(28.px)) {
            val options = listOf(
                Triple(ThemeMode.Auto, Icons.auto, R.string.setup_theme_auto to R.string.setup_theme_auto_hint),
                Triple(ThemeMode.Dark, Icons.moon, R.string.setup_theme_dark to R.string.setup_theme_dark_hint),
                Triple(ThemeMode.Light, Icons.sun, R.string.setup_theme_light to R.string.setup_theme_light_hint),
            )
            options.forEach { (mode, icon, texts) ->
                ChoiceCard(
                    title = stringResource(texts.first),
                    subtitle = stringResource(texts.second),
                    icon = icon,
                    selected = settings.themeMode == mode,
                    onClick = { vm.updateSettings { it.copy(theme = mode.name) } },
                )
            }
        }
        Spacer(Modifier.height(40.px))
        TogglePill(
            text = stringResource(R.string.setup_motion),
            checked = settings.motion,
            onToggle = { on -> vm.updateSettings { it.copy(motion = on) } },
            icon = Icons.motion,
        )
        Spacer(Modifier.height(14.px))
        SetupText(stringResource(R.string.setup_motion_hint), Modifier.padding(start = 6.px))
    }
}

// ==== 3. Profile ===================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileStep(vm: HaloViewModel, settings: HaloSettings) {
    val c = LocalHalo.current.colors
    val profile = settings.profile
    Column {
        StepHeader(stringResource(R.string.setup_profile_title), stringResource(R.string.setup_profile_subtitle))
        Spacer(Modifier.height(48.px))
        Row(horizontalArrangement = Arrangement.spacedBy(64.px)) {
            // Preview, name and colour.
            Column(Modifier.width(560.px), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(profile, 200)
                Spacer(Modifier.height(28.px))
                HaloTextField(
                    value = profile.name,
                    modifier = Modifier.width(520.px),
                    placeholder = stringResource(R.string.setup_profile_name_hint),
                    leading = Icons.user,
                ) { v ->
                    val name = v.trim().take(20)
                    if (name.isNotEmpty() && name != profile.name) vm.updateActiveProfile { it.copy(name = name) }
                }
                Spacer(Modifier.height(10.px))
                SetupText(stringResource(R.string.setup_profile_name_tip), size = 18, maxLines = 1)
                Spacer(Modifier.height(28.px))
                Row(
                    Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(14.px),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PROFILE_COLORS.forEachIndexed { i, _ ->
                        ColourDot(i, Math.floorMod(profile.color, PROFILE_COLORS.size) == i) {
                            vm.updateActiveProfile { it.copy(color = i) }
                        }
                    }
                }
            }
            // Picture.
            Column {
                GroupLabel(stringResource(R.string.setup_profile_picture))
                FlowRow(
                    Modifier.width(1040.px).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(14.px),
                    verticalArrangement = Arrangement.spacedBy(14.px),
                    maxItemsInEachRow = 10,
                ) {
                    AvatarCell(null, profile.avatar == null, profile.initial) { vm.updateActiveProfile { it.copy(avatar = null) } }
                    Avatars.all.forEach { a ->
                        AvatarCell(a, a.id == profile.avatar, null) { vm.updateActiveProfile { it.copy(avatar = a.id) } }
                    }
                }
                Spacer(Modifier.height(14.px))
                BasicText(stringResource(R.string.setup_profile_credit), style = hstyle(15, FontWeight.Medium, c.sub))
            }
        }
    }
}

@Composable
private fun AvatarCell(a: Avatars.Avatar?, selected: Boolean, initial: String?, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Box(
        Modifier
            .size(88.px)
            .control(st, onClick, focusScale = 1.18f)
            .clip(CircleShape)
            .background(if (st.focused) c.cta.copy(alpha = 0.35f) else c.pill)
            .then(if (st.focused || selected) Modifier.border(if (st.focused) 4.px else 3.px, if (st.focused) c.cta else c.aqua, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (a != null) {
            Image(painterResource(a.res), contentDescription = a.name, modifier = Modifier.size(68.px))
        } else {
            BasicText(initial ?: "Aa", style = hstyle(30, FontWeight.Bold, c.text))
        }
    }
}

@Composable
private fun ColourDot(index: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val (a, b) = PROFILE_COLORS[index]
    val brush = remember(index) { Brush.linearGradient(listOf(a, b)) }
    Box(
        Modifier
            .control(st, onClick, focusScale = 1.18f)
            .size(56.px)
            .then(if (selected || st.focused) Modifier.border(3.px, if (st.focused) c.cta else c.aqua, CircleShape) else Modifier)
            .padding(if (selected || st.focused) 6.px else 0.px)
            .clip(CircleShape)
            .background(brush),
    )
}

// ==== 4. Weather ===================================================================

@Composable
internal fun WeatherStep(vm: HaloViewModel, settings: HaloSettings) {
    val context = LocalContext.current
    val weather by vm.weather.state.collectAsStateWithLifecycle()
    fun granted() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permission = ok
        asked = true
        vm.weather.refresh()
    }
    fun useDevice() {
        vm.updateSettings { it.copy(useDeviceLocation = true, showWeather = true) }
        if (!granted()) runCatching { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }.onFailure { asked = true }
    }
    Column {
        StepHeader(stringResource(R.string.setup_weather_title), stringResource(R.string.setup_weather_subtitle))
        Spacer(Modifier.height(56.px))
        Row(horizontalArrangement = Arrangement.spacedBy(28.px)) {
            ChoiceCard(
                title = stringResource(R.string.setup_weather_device),
                subtitle = stringResource(if (permission) R.string.setup_weather_device_on else R.string.setup_weather_device_hint),
                icon = Icons.pin,
                selected = settings.useDeviceLocation,
                onClick = { useDevice() },
                width = 480,
            )
            ChoiceCard(
                title = stringResource(R.string.setup_weather_city),
                subtitle = stringResource(R.string.setup_weather_city_hint),
                icon = Icons.cloud,
                selected = !settings.useDeviceLocation,
                onClick = { vm.updateSettings { it.copy(useDeviceLocation = false, showWeather = true) } },
                width = 480,
            )
        }
        Spacer(Modifier.height(32.px))
        if (!settings.useDeviceLocation) {
            HaloTextField(
                value = settings.city,
                modifier = Modifier.width(988.px),
                placeholder = stringResource(R.string.setup_weather_city_placeholder),
                leading = Icons.pin,
            ) { v -> vm.updateSettings { it.copy(city = v.trim()) } }
            Spacer(Modifier.height(24.px))
        }
        val w = weather
        when {
            settings.useDeviceLocation && asked && !permission ->
                StatusLine(stringResource(R.string.setup_weather_denied), ok = false)
            !settings.showWeather -> StatusLine(stringResource(R.string.setup_weather_hidden), ok = null)
            w != null -> StatusLine(stringResource(R.string.setup_weather_now, w.city, w.temperature), ok = true)
            else -> StatusLine(stringResource(R.string.setup_weather_loading), ok = null)
        }
        Spacer(Modifier.height(28.px))
        TogglePill(
            text = stringResource(R.string.setup_weather_show),
            checked = settings.showWeather,
            onToggle = { on -> vm.updateSettings { it.copy(showWeather = on) } },
            icon = Icons.cloud,
        )
    }
}

// ==== 5. Background ================================================================

@Composable
internal fun intervalLabel(minutes: Int): String = when (minutes) {
    0 -> stringResource(R.string.setup_interval_keep)
    60 -> stringResource(R.string.setup_interval_hour)
    1440 -> stringResource(R.string.setup_interval_daily)
    else -> stringResource(R.string.setup_interval_minutes, minutes)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BackgroundStep(vm: HaloViewModel, settings: HaloSettings) {
    val c = LocalHalo.current.colors
    val profile = settings.profile
    val cols = WallpaperRepository.collections
    val selected = WallpaperRepository.of(profile.wallpaper)
    val isScene = selected.id == WallpaperRepository.SCENE
    val selIndex = cols.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (selIndex <= 3) 0 else selIndex - 1)
    Column {
        StepHeader(stringResource(R.string.setup_background_title), stringResource(R.string.setup_background_subtitle))
        Spacer(Modifier.height(28.px))
        // The cards scroll edge to edge, aligned with the 120 px gutter of the wizard.
        ScrollPadding(120.px) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .padding(start = 0.px)
                    .fillMaxWidth()
                    .focusRestorer(),
                contentPadding = PaddingValues(vertical = 22.px, horizontal = 4.px),
                horizontalArrangement = Arrangement.spacedBy(26.px),
            ) {
                itemsIndexed(cols, key = { _, item -> item.id }) { _, item ->
                    CollectionCard(vm.wallpapers, item, item.id == selected.id) {
                        vm.updateActiveProfile { it.copy(wallpaper = item.id, wallpaperPin = null) }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.px))
        Row(
            Modifier.focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.px),
        ) {
            BasicText(
                stringResource(R.string.setup_background_interval),
                Modifier.width(250.px),
                maxLines = 1,
                style = hstyle(22, FontWeight.SemiBold, if (isScene) c.sub.copy(alpha = 0.6f) else c.text),
            )
            WallpaperRepository.INTERVALS.forEach { m ->
                PillButton(
                    intervalLabel(m),
                    { vm.updateActiveProfile { it.copy(wallpaperMinutes = m, wallpaperPin = if (m == 0) it.wallpaperPin else null) } },
                    primary = m == profile.wallpaperMinutes && !isScene,
                    enabled = !isScene,
                    height = 56,
                )
            }
        }
        Spacer(Modifier.height(22.px))
        SetupText(
            stringResource(if (isScene) R.string.setup_background_scene_hint else R.string.setup_background_more),
            Modifier.padding(start = 4.px),
        )
    }
}

@Composable
private fun CollectionCard(repo: WallpaperRepository, col: WallpaperCollection, selected: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(26.px)
    val preview by produceState<ImageBitmap?>(null, col.id) {
        if (col.id != WallpaperRepository.SCENE) {
            repeat(2) {
                value = runCatching { repo.preview(col.id) }.getOrNull()
                if (value != null) return@produceState
                delay(10_000)
            }
        }
    }
    Column(Modifier.width(300.px).control(st, onClick, focusScale = 1.07f)) {
        Box(
            Modifier
                .size(300.px, 169.px)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF2E6FD8), Color(0xFF7BC8F0), Color(0xFF9BE3B0))))
                .then(
                    if (st.focused || selected) Modifier.border(if (st.focused) 5.px else 4.px, if (st.focused) c.cta else c.aqua, shape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            val img = preview
            if (img != null) {
                Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                HaloIcon(col.icon, 56.px, Color.White.copy(alpha = 0.92f), strokeWidth = 1.6f)
            }
            if (selected) CheckBadge(Modifier.align(Alignment.TopEnd).padding(10.px))
        }
        BasicText(
            collectionTitle(col),
            Modifier.padding(top = 12.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(22, if (selected) FontWeight.Bold else FontWeight.SemiBold, c.text),
        )
    }
}

// ==== 10. Done =====================================================================

@Composable
internal fun DoneStep(vm: HaloViewModel, settings: HaloSettings) {
    val context = LocalContext.current
    val home by vm.home.collectAsStateWithLifecycle()
    val ui = context.resources.configuration.locales.get(0) ?: Locale.getDefault()
    val serviceOn = remember { app.halo.launcher.setup.DefaultLauncher.isServiceEnabled(context) }
    val isHome = remember { app.halo.launcher.setup.DefaultLauncher.isDefaultHome(context) }

    val language = when (app.halo.launcher.i18n.Lang.current(context)) {
        "en" -> stringResource(R.string.setup_language_en)
        "de" -> stringResource(R.string.setup_language_de)
        else -> stringResource(R.string.setup_language_system) + " · " +
            (android.content.res.Resources.getSystem().configuration.locales.get(0) ?: ui).getDisplayLanguage(ui)
    }
    val theme = stringResource(
        when (settings.themeMode) {
            ThemeMode.Auto -> R.string.setup_theme_auto
            ThemeMode.Dark -> R.string.setup_theme_dark
            ThemeMode.Light -> R.string.setup_theme_light
        }
    )
    val weather = when {
        !settings.showWeather -> stringResource(R.string.setup_summary_off)
        settings.useDeviceLocation -> stringResource(R.string.setup_weather_device)
        settings.city.isBlank() -> stringResource(R.string.setup_summary_not_set)
        else -> settings.city
    }
    val background = collectionTitle(WallpaperRepository.of(settings.profile.wallpaper))
    val assistant = stringResource(if (settings.geminiKey.isNotBlank()) R.string.setup_summary_key_saved else R.string.setup_summary_not_set)
    val homeButton = stringResource(
        when {
            isHome -> R.string.setup_summary_home_default
            settings.homeOverride && serviceOn -> R.string.setup_summary_home_override
            settings.homeOverride -> R.string.setup_summary_needs_service
            else -> R.string.setup_summary_not_set
        }
    )
    val remote = settings.youtubeButtonTarget?.let { pkg ->
        val label = app.halo.launcher.setup.DefaultLauncher.appLabel(context, pkg) ?: pkg
        if (serviceOn) label else stringResource(R.string.setup_summary_remote_needs_service, label)
    } ?: stringResource(R.string.setup_summary_remote_unchanged)

    val rows = listOf(
        stringResource(R.string.setup_summary_language) to language,
        stringResource(R.string.setup_summary_theme) to theme,
        stringResource(R.string.setup_summary_profile) to settings.profile.name,
        stringResource(R.string.setup_summary_weather) to weather,
        stringResource(R.string.setup_summary_background) to background,
        stringResource(R.string.setup_summary_favourites) to stringResource(R.string.setup_summary_favourites_value, home.favourites.size),
        stringResource(R.string.setup_summary_assistant) to assistant,
        stringResource(R.string.setup_summary_home) to homeButton,
        stringResource(R.string.setup_summary_remote) to remote,
    )
    val c = LocalHalo.current.colors
    Column {
        StepHeader(stringResource(R.string.setup_done_title, settings.profile.name), stringResource(R.string.setup_done_subtitle))
        Spacer(Modifier.height(40.px))
        Row(horizontalArrangement = Arrangement.spacedBy(48.px), verticalAlignment = Alignment.Top) {
            Avatar(settings.profile, 180)
            SetupPanel(Modifier.width(1100.px)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.px)) {
                    rows.forEach { (label, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(label, Modifier.width(320.px), maxLines = 1, style = hstyle(22, FontWeight.SemiBold, c.sub))
                            BasicText(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(22, FontWeight.SemiBold, c.text))
                        }
                    }
                }
            }
        }
    }
}
