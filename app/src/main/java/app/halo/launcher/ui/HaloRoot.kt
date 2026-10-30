package app.halo.launcher.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import app.halo.launcher.data.MAX_PROFILES
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.Panel
import app.halo.launcher.Screen
import app.halo.launcher.data.ThemeMode
import app.halo.launcher.weather.Weather
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime

/** Night = between sunset and sunrise (from the weather service), else 19:00–07:00. */
fun isNight(mode: ThemeMode, weather: Weather?, now: LocalDateTime = LocalDateTime.now()): Boolean = when (mode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.Auto -> {
        val t = now.toLocalTime()
        val rise = weather?.sunrise?.toLocalTime() ?: LocalTime.of(7, 0)
        val set = weather?.sunset?.toLocalTime() ?: LocalTime.of(19, 0)
        t.isBefore(rise) || !t.isBefore(set)
    }
}

@Composable
fun HaloRoot(vm: HaloViewModel, actions: HaloActions) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val panel by vm.panel.collectAsStateWithLifecycle()
    val ambient by vm.ambient.collectAsStateWithLifecycle()
    // Not read here: only the focused tile reads it (see GlassTile).
    val idleState = vm.idle.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.state.collectAsStateWithLifecycle()
    val mediaAccess by vm.nowPlaying.hasAccess.collectAsStateWithLifecycle()
    val weather by vm.weather.state.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpapers.current.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val launch: (app.halo.launcher.data.Tile, androidx.compose.ui.geometry.Rect) -> Unit = { t, b ->
        vm.recordLaunch(t.app.key)
        actions.launch(t.app, b)
    }

    val night by produceState(isNight(settings.themeMode, weather), settings.themeMode, weather) {
        while (true) {
            value = isNight(settings.themeMode, weather)
            delay(60_000)
        }
    }

    var homeSignal by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.homeEvents.collect { homeSignal++ } }
    LaunchedEffect(panel, ambient) { if (panel == Panel.None && !ambient) homeSignal++ }

    BackHandler(enabled = true) { vm.back() }

    HaloThemeProvider(dark = night, motion = settings.motion) {
        val backdrop = remember { BackdropControl() }
        val c = LocalHalo.current.colors

        // While ambient fully covers Home, Home is hidden (alpha 0 → not drawn at all).
        val ambientState = remember { MutableTransitionState(false) }
        ambientState.targetState = ambient
        val hideHome = ambientState.isIdle && ambientState.currentState
        val homeLayer = Modifier.graphicsLayer { alpha = if (hideHome) 0f else 1f }

        // ---- Setup wizard gate: first run (setupDone = false) or opened from Settings ----
        val setupRequest by vm.setupRequest.collectAsStateWithLifecycle()
        val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
        if (!settingsReady) Box(Modifier.fillMaxSize().background(c.base))
        else if (!settings.setupDone || setupRequest != null) app.halo.launcher.ui.setup.SetupWizard(vm, actions, setupRequest)
        else run {
            Box(Modifier.fillMaxSize().background(c.base)) {
                // While something plays (or paused < 15 min), its artwork is the background.
                val np = nowPlaying
                var recentlyPlayed by remember { mutableStateOf(false) }
                val playedPkg = remember { arrayOfNulls<String>(1) }
                LaunchedEffect(np?.playing, np?.packageName) {
                    when {
                        np?.playing == true -> { recentlyPlayed = true; playedPkg[0] = np.packageName }
                        np != null && np.packageName == playedPkg[0] -> { delay(15 * 60_000L); recentlyPlayed = false }
                        else -> recentlyPlayed = false
                    }
                }
                // Last artwork, so the background can fade out instead of cutting when the session ends.
                val lastNp = remember { arrayOfNulls<app.halo.launcher.media.NowPlaying>(1) }
                if (np != null) lastNp[0] = np
                val media = np?.takeIf {
                    settings.profile.mediaBackdrop && (it.artHi ?: it.art) != null && (it.playing || recentlyPlayed)
                }
                val photo = wallpaper
                val bg = when {
                    media != null -> 2
                    photo != null && settings.profile.wallpaper != "scene" -> 1
                    else -> 0
                }
                Crossfade(targetState = bg, modifier = homeLayer, animationSpec = tween(900), label = "bg") { kind ->
                    when (kind) {
                        2 -> (nowPlaying ?: lastNp[0])?.let { MediaBackdrop(it, Modifier.fillMaxSize()) }
                            ?: Box(Modifier.fillMaxSize())
                        1 -> wallpaper?.let { PhotoBackdrop(it, Modifier.fillMaxSize()) }
                            ?: Box(Modifier.fillMaxSize())
                        else -> Backdrop(
                            modifier = Modifier.fillMaxSize(),
                            control = if (screen == Screen.Home) backdrop else null,
                        )
                    }
                }

                Box(Modifier.fillMaxSize().then(homeLayer)) {
                    AnimatedContent(
                        targetState = screen,
                        contentKey = { it::class },
                        transitionSpec = {
                            // Plain cross-fade: scale transitions cost two full-screen offscreen layers.
                            fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                        },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Home -> HomeScreen(
                                topRow = home.topRow,
                                topRowMode = home.topRowMode,
                                apps = home.rest,
                                ready = home.ready,
                                idle = { idleState.value },
                                profile = settings.profile,
                                themeMode = settings.themeMode,
                                clock24h = settings.clock24h,
                                showWeather = settings.showWeather,
                                nowPlaying = nowPlaying,
                                mediaAccess = mediaAccess,
                                weather = weather,
                                backdrop = backdrop,
                                homeSignal = homeSignal,
                                onLaunch = launch,
                                onArrange = { key -> vm.open(Screen.Arrange(key)) },
                                // The stock Google TV settings, not Halo's own.
                                onQuickSettings = { actions.openSettings(Settings.ACTION_SETTINGS) },
                                onAppearance = { vm.panel.value = Panel.Appearance },
                                onProfiles = { vm.open(Screen.Profiles) },
                                onSearch = { vm.resetAssistant(); vm.open(Screen.Search) },
                                onVoice = { vm.resetAssistant(); vm.open(Screen.VoiceSearch) },
                                onPlayStore = { actions.openPackage("com.android.vending") },
                                onBackgrounds = { vm.open(Screen.Backgrounds) },
                                onWeather = { vm.open(Screen.Weather) },
                                onCycleTopRow = {
                                    vm.updateActiveProfile { p ->
                                        val m = app.halo.launcher.data.TopRow.of(p.topRow)
                                        p.copy(topRow = app.halo.launcher.data.TopRow.entries[(m.ordinal + 1) % app.halo.launcher.data.TopRow.entries.size].name)
                                    }
                                },
                                onToggleTheme = {
                                    val m = settings.themeMode
                                    vm.updateSettings { it.copy(theme = ThemeMode.entries[(m.ordinal + 1) % 3].name) }
                                },
                                onPlayPause = { vm.nowPlaying.playPause() },
                                onNext = { vm.nowPlaying.next() },
                                onPrevious = { vm.nowPlaying.previous() },
                                onOpenMedia = { vm.open(Screen.Player) },
                            )
                            Screen.Weather -> WeatherScreen(
                                w = weather,
                                onLocation = { vm.open(Screen.Home); vm.panel.value = Panel.Appearance },
                                onBack = { vm.open(Screen.Home) },
                            )
                            Screen.Backgrounds -> BackgroundsScreen(
                                repo = vm.wallpapers,
                                selected = settings.profile.wallpaper,
                                minutes = settings.profile.wallpaperMinutes,
                                pin = settings.profile.wallpaperPin,
                                mediaBackdrop = settings.profile.mediaBackdrop,
                                current = wallpaper,
                                onSelect = { id -> vm.updateActiveProfile { it.copy(wallpaper = id, wallpaperPin = null) } },
                                onPin = { id, key -> vm.updateActiveProfile { it.copy(wallpaper = id, wallpaperPin = key) } },
                                onRotate = { vm.updateActiveProfile { it.copy(wallpaperPin = null) } },
                                onInterval = { m ->
                                    // Choosing an interval means "rotate" (except "Keep one", which keeps a pin).
                                    vm.updateActiveProfile { it.copy(wallpaperMinutes = m, wallpaperPin = if (m == 0) it.wallpaperPin else null) }
                                },
                                onMediaBackdrop = { on -> vm.updateActiveProfile { it.copy(mediaBackdrop = on) } },
                                onNext = {
                                    // A pinned picture is released, then rotation moves on.
                                    if (settings.profile.wallpaperPin != null) vm.updateActiveProfile { it.copy(wallpaperPin = null) }
                                    vm.wallpapers.skip()
                                },
                                onBack = { vm.open(Screen.Home) },
                            )
                            is Screen.Arrange -> ArrangeScreen(
                                favourites = home.favourites,
                                apps = home.apps,
                                hiddenCount = home.hidden.size,
                                startHeld = s.startHeld,
                                onMove = vm::move,
                                onSwapRow = vm::swapRow,
                                onHide = vm::hide,
                                onRestoreHidden = vm::restoreHidden,
                                onCustomize = { key -> vm.open(Screen.Customize(key, Screen.Arrange(null))) },
                                onUninstall = { pkg -> actions.uninstall(pkg) },
                                onDone = { vm.open(Screen.Home) },
                            )
                            is Screen.Customize -> CustomizeScreen(
                                tiles = home.favourites + home.apps,
                                startKey = s.key,
                                styleOf = vm::styleOf,
                                resolve = { t, st -> vm.resolve(t.app, st) },
                                allApps = vm.appsRepo.apps.collectAsStateWithLifecycle().value,
                                onSave = vm::saveStyle,
                                onBack = { vm.open(s.returnTo) },
                            )
                            Screen.Search, Screen.VoiceSearch -> SearchScreen(
                                tiles = home.favourites + home.apps,
                                assistant = assistant,
                                startWithVoice = s == Screen.VoiceSearch,
                                onAsk = { q -> vm.ask(q, actions) },
                                onAssistant = vm::setAssistant,
                                shortcuts = remember {
                                    systemShortcuts(
                                        open = actions::openSettings,
                                        halo = { what ->
                                            when (what) {
                                                "arrange" -> vm.open(Screen.Arrange(null))
                                                "appearance" -> { vm.open(Screen.Home); vm.panel.value = Panel.Appearance }
                                                else -> actions.openSettings(Settings.ACTION_SETTINGS)
                                            }
                                        },
                                    )
                                },
                                onLaunch = launch,
                            )
                            Screen.Player -> PlayerScreen(
                                np = nowPlaying,
                                mediaAccess = mediaAccess,
                                canSeek = vm.nowPlaying.canSeek,
                                onPlayPause = { vm.nowPlaying.playPause() },
                                onNext = { vm.nowPlaying.next() },
                                onPrevious = { vm.nowPlaying.previous() },
                                onSeek = { vm.nowPlaying.seekBy(it) },
                                onOpenApp = { nowPlaying?.let { actions.openPackage(it.packageName) } },
                                onBack = { vm.open(Screen.Home) },
                            )
                            Screen.Profiles -> ProfilesScreen(
                                profiles = settings.profiles,
                                activeId = settings.profile.id,
                                canAdd = settings.profiles.size < MAX_PROFILES,
                                onSelect = vm::switchProfile,
                                onAdd = vm::addProfile,
                                onUpdate = vm::updateProfile,
                                onRemove = vm::removeProfile,
                                onAppearance = { vm.open(Screen.Home); vm.panel.value = Panel.Appearance },
                            )
                        }
                    }
                }

                // Side panel (Quick settings / Launcher appearance).
                AnimatedVisibility(
                    visible = panel != Panel.None,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250)),
                ) {
                    // Pushes Home back: light on the left (context stays visible), dark behind the panel.
                    val scrim = remember(c.isDark) {
                        val ink = androidx.compose.ui.graphics.Color(0xFF03050C)
                        val k = if (c.isDark) 1f else 0.7f
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to ink.copy(alpha = 0.40f * k),
                            0.55f to ink.copy(alpha = 0.55f * k),
                            1f to ink.copy(alpha = 0.78f * k),
                        )
                    }
                    Box(Modifier.fillMaxSize().background(scrim))
                }
                AnimatedVisibility(
                    visible = panel != Panel.None,
                    enter = slideInHorizontally(Motion.focusIn()) { it / 2 } + fadeIn(tween(250)),
                    exit = slideOutHorizontally(tween(260)) { it / 2 } + fadeOut(tween(200)),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusProperties { onExit = { cancelFocusChange() } }
                            .focusGroup(),
                    ) {
                        SidePanel(
                            page = Panel.Appearance,
                            settings = settings,
                            hiddenCount = home.hidden.size,
                            mediaAccess = mediaAccess,
                            actions = remember {
                                PanelActions(
                                    close = { vm.panel.value = Panel.None },
                                    goTo = { vm.panel.value = it },
                                    openSettings = actions::openSettings,
                                    updateSettings = vm::updateSettings,
                                    startAmbient = vm::startAmbient,
                                    arrange = { vm.open(Screen.Arrange(null)) },
                                    restoreHidden = vm::restoreHidden,
                                    profiles = { vm.open(Screen.Profiles) },
                                    backgrounds = { vm.open(Screen.Backgrounds) },
                                    previewVoice = { v -> vm.previewVoice(v) },
                                )
                            },
                        )
                    }
                }

                // Ambient mode on top of everything.
                AnimatedVisibility(
                    visibleState = ambientState,
                    enter = fadeIn(tween(1400)),
                    exit = fadeOut(tween(500)),
                ) {
                    AmbientScreen(settings.clock24h, weather, nowPlaying)
                }
            }
        }
    }
}
