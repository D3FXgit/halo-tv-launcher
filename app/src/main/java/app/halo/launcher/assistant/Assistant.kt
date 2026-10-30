package app.halo.launcher.assistant

import app.halo.launcher.ui.setup.SetupStep
import app.halo.launcher.data.TileStyle
import app.halo.launcher.data.TileSource
import app.halo.launcher.data.PROFILE_COLORS
import app.halo.launcher.data.MAX_PROFILES
import app.halo.launcher.data.MAIN_PROFILE
import app.halo.launcher.data.Glyph
import app.halo.launcher.data.Avatars
import android.content.Context
import android.os.Build
import android.provider.Settings
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.HomeModel
import app.halo.launcher.Panel
import app.halo.launcher.Screen
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.ThemeMode
import app.halo.launcher.data.Tile
import app.halo.launcher.data.TopRow
import app.halo.launcher.i18n.Lang
import app.halo.launcher.media.NowPlaying
import app.halo.launcher.ui.HaloActions
import app.halo.launcher.ui.collectionTitle
import app.halo.launcher.ui.intervalText
import app.halo.launcher.ui.profileName
import app.halo.launcher.ui.themeRes
import app.halo.launcher.ui.topRowTitleRes
import app.halo.launcher.wallpaper.WallpaperRepository
import app.halo.launcher.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What "Ask Halo" can do: Gemini function declarations + their implementation. */
object Assistant {

    private val settingsSections = mapOf(
        "main" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
    )

    /** Names of the settings pages above, as the user sees them (search shortcuts use the same). */
    private val settingsSectionNames = mapOf(
        "main" to R.string.shortcut_all_settings,
        "wifi" to R.string.shortcut_wifi,
        "bluetooth" to R.string.shortcut_bluetooth,
        "display" to R.string.shortcut_display,
        "sound" to R.string.shortcut_sound,
        "apps" to R.string.shortcut_apps,
        "accessibility" to R.string.shortcut_accessibility,
        "date" to R.string.shortcut_date,
        "language" to R.string.shortcut_language,
        "storage" to R.string.shortcut_storage,
        "about" to R.string.shortcut_about,
        "developer" to R.string.shortcut_developer,
    )

    /** Names of Halo's screens for the "done" chips. */
    private val screenNames = mapOf(
        "home" to R.string.act_screen_home,
        "arrange" to R.string.arrange_title,
        "profiles" to R.string.settings_profiles,
        "appearance" to R.string.halo_settings,
        "search" to R.string.act_screen_search,
        "weather" to R.string.weather_title,
        "backgrounds" to R.string.bg_title,
        "player" to R.string.now_playing,
    )

    fun tools(home: HomeModel, s: HaloSettings): List<Tool> = listOf(
        Tool("open_app", "Open (launch) an installed app on the TV.",
            mapOf("name" to ("App name from the installed apps list" to null))),
        Tool("media_control", "Control what is currently playing.",
            mapOf("action" to ("What to do" to listOf("play", "pause", "toggle", "next", "previous")))),
        Tool("show_now_playing", "Show the full-screen now-playing view."),
        Tool("set_theme", "Change the launcher's colour theme.",
            mapOf("theme" to ("auto follows sunrise/sunset" to listOf("auto", "dark", "light")))),
        Tool("switch_profile", "Switch to another Halo profile (who's watching).",
            mapOf("name" to ("Profile name" to s.profiles.map { it.name }.filter { it.isNotBlank() }.distinct().ifEmpty { null }))),
        Tool("set_background", "Change the home-screen background to a picture collection (scene = animated Halo scene). " +
            WallpaperRepository.collections.joinToString("; ") { "${it.id}: ${it.title}" } + ".",
            mapOf("source" to ("Picture collection" to WallpaperRepository.collections.map { it.id }))),
        Tool("next_background", "Show the next background picture now."),
        Tool("set_background_interval", "How often the background picture changes, in minutes (0 = keep one picture, 1440 = daily).",
            mapOf("minutes" to ("Minutes between pictures" to WallpaperRepository.INTERVALS.map { it.toString() }))),
        Tool("open_settings", "Open a page of the TV's system settings.",
            mapOf("section" to ("Settings page" to settingsSections.keys.toList()))),
        Tool("set_weather_city", "Set the weather location. Use 'auto' to follow the TV's location.",
            mapOf("city" to ("City name or 'auto'" to null))),
        Tool("add_to_favourites", "Pin an app to the favourites row.",
            mapOf("name" to ("App name" to null))),
        Tool("remove_from_favourites", "Remove an app from the favourites row.",
            mapOf("name" to ("App name" to null))),
        Tool("set_top_row", "Choose what the first row of the home screen shows.",
            mapOf("mode" to ("Row content" to TopRow.entries.map { it.name.lowercase() }))),
        Tool("open_screen", "Open a Halo screen.",
            mapOf("screen" to ("Screen" to listOf("home", "arrange", "profiles", "appearance", "search", "weather", "backgrounds", "player")))),
        Tool("start_screensaver", "Start the ambient screensaver now."),
        Tool("change_setting",
            "Change one of Halo's settings. Allowed values: " + SETTING_VALUES.entries.joinToString("; ") { (k, v) -> "$k = $v" } + ".",
            mapOf(
                "setting" to ("Which setting" to SETTING_VALUES.keys.toList()),
                "value" to ("New value (see the allowed values)" to null),
            )),
        Tool("customize_app",
            "Change how one app's tile looks on the home screen. Give only what should change. " +
                "look: halo_card (uniform Halo card), logo (logo on a coloured box), glass (symbol on glass), banner (the app's own TV banner), icon (the app icon). " +
                "colour: #RRGGBB (convert colour names yourself). symbol (for the glass look): ${Glyph.entries.joinToString { it.name }}. " +
                "artwork_from: another installed app whose banner/logo/card this app should wear. reset=yes restores the default look.",
            mapOf(
                "name" to ("The app to change" to null),
                "look" to ("Tile look" to listOf("halo_card", "logo", "glass", "banner", "icon")),
                "label" to ("Name shown on the tile" to null),
                "colour" to ("Card or box colour, #RRGGBB" to null),
                "symbol" to ("Symbol for the glass look" to Glyph.entries.map { it.name }),
                "artwork_from" to ("Installed app whose artwork to use" to null),
                "reset" to ("yes = back to the default look" to listOf("yes", "no")),
            ),
            required = listOf("name")),
        Tool("arrange_app", "Move, hide or uninstall an app on the home screen. Uninstall opens the TV's own confirmation dialog.",
            mapOf(
                "name" to ("The app" to null),
                "action" to ("What to do" to listOf("move_left", "move_right", "move_first", "move_last", "hide", "uninstall", "customize_on_screen")),
            )),
        Tool("show_hidden_apps", "Bring all hidden apps back to the home screen."),
        Tool("manage_profile",
            "Create, rename, delete or style a Halo profile. Avatars: ${Avatars.all.joinToString { it.id }}. Colours: 0–${PROFILE_COLORS.size - 1}.",
            mapOf(
                "action" to ("What to do" to listOf("create", "rename", "delete", "set_avatar", "set_colour")),
                "name" to ("Existing profile name (for create: the new profile's name)" to null),
                "value" to ("New name, avatar id or colour number" to null),
            ),
            required = listOf("action", "name")),
        Tool("set_volume", "Change the TV's volume.",
            mapOf("action" to ("What to do" to listOf("up", "down", "mute", "unmute")))),
        Tool("run_setup", "Open Halo's setup wizard, either completely or at one step.",
            mapOf("step" to ("Step (all = the whole wizard)" to listOf("all") + SetupStep.entries.filter { it != SetupStep.Done }.map { it.name.lowercase() }))),
    )

    /** change_setting: setting → allowed values (shown to the model). */
    private val SETTING_VALUES: Map<String, String> = linkedMapOf(
        "motion_effects" to "on|off",
        "clock_24h" to "on|off",
        "navigation_sounds" to "on|off",
        "show_weather" to "on|off",
        "screensaver_after_minutes" to "0 (off)|2|5|10|15|30",
        "tile_style" to "halo (uniform Halo cards)|banner (the apps' own TV banners)|icon (app icons)",
        "song_artwork_background" to "on|off (artwork of what's playing as background)",
        "language" to "system|en|de (Halo's interface and voice language)",
        "voice" to Speaker.VOICES.joinToString("|") { it.first },
        "assistant_button" to "off|star (the remote's star button opens Halo's assistant over any app)",
        "keep_halo_as_home" to "on|off (bring Halo back when the TV opens its own home screen)",
        "youtube_button" to "off|<installed app name> (app the remote's YouTube button opens)",
    )

    /**
     * The model's instructions. [context] decides the answer language: Halo's interface language
     * (German when the interface is German, else English; "system" = what the TV's language
     * resolved to). The facts below are given in English for the model; it answers in [language].
     */
    fun systemPrompt(context: Context, home: HomeModel, s: HaloSettings, w: Weather?, np: NowPlaying?): String = buildString {
        val german = Lang.isGerman(context)
        val language = if (german) "German" else "English"
        val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct().joinToString(" ")
        appendLine(
            "You are Halo, the voice assistant built into the user's TV home screen " +
                "(an Android TV / Google TV device" + (if (device.isNotBlank()) ", $device" else "") + ")."
        )
        appendLine("Act with the tools whenever the user wants something done; you may call several tools.")
        appendLine(
            "Always answer in $language" + (if (german) " (Deutsch, informal \"du\")" else "") +
                ", the language of the TV's interface, even if the request or the data below is in another language. " +
                "Keep app names, titles and proper names as they are."
        )
        appendLine("Your text reply is read aloud: at most two short sentences, no markdown.")
        appendLine("For general questions (facts, recommendations, what to watch) just answer briefly.")
        appendLine(
            "You can operate the whole launcher: every Halo setting, tile looks, layout, profiles, backgrounds, weather location. " +
                "The TV's own system settings (time zone, date, Wi-Fi, picture, sound output, language of the TV) cannot be changed by you: " +
                "open the right system settings page with open_settings and say what to change there. " +
                "Ask before deleting a profile or uninstalling an app unless the user clearly asked for exactly that."
        )
        appendLine(
            "Current Halo settings: theme ${s.theme.lowercase()}, tile style ${s.tileLook}, motion ${onOff(s.motion)}, 24h clock ${onOff(s.clock24h)}, " +
                "navigation sounds ${onOff(s.sounds)}, weather ${if (!s.showWeather) "hidden" else if (s.useDeviceLocation) "TV location" else s.city}, " +
                "screensaver after ${s.ambientMinutes} min, language ${s.language}, voice ${s.voice}, background ${s.profile.wallpaper} " +
                "(changes every ${s.profile.wallpaperMinutes} min), song artwork background ${onOff(s.profile.mediaBackdrop)}, " +
                "assistant button ${s.assistantButton}, keep Halo as home ${onOff(s.homeOverride)}, " +
                "YouTube button ${s.youtubeButtonTarget?.let { pkg -> home.all.firstOrNull { it.app.packageName == pkg }?.label ?: pkg } ?: "unchanged"}, " +
                "time zone ${java.util.TimeZone.getDefault().id}."
        )
        appendLine("Hidden apps: ${home.hidden.joinToString { it.label }.ifEmpty { "none" }}.")
        appendLine("Now: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}.")
        appendLine("Profile: ${profileName(context, s.profile)}. Profiles: ${s.profiles.joinToString { profileName(context, it) }}.")
        appendLine("Installed apps: ${home.all.joinToString { it.label }}.")
        appendLine("Favourites: ${home.favourites.joinToString { it.label }}.")
        if (w != null) {
            appendLine(
                "Weather in ${w.city}: ${w.temperature}°C, ${Weather.describe(context, w.code)}, today ${w.todayMin ?: "?"}–${w.todayMax ?: "?"}°C, " +
                    "rain chance ${w.rainChance ?: "?"}%. Next days: " +
                    w.daily.drop(1).joinToString { "${it.date.dayOfWeek}: ${it.min}–${it.max}°C ${Weather.describe(context, it.code)}" } +
                    (if (w.alerts.isNotEmpty()) ". Warnings: " + w.alerts.joinToString { it.headline(german) } else "")
            )
        }
        if (np != null) appendLine("Now playing: ${np.title} – ${np.artist} (${np.appLabel}), ${if (np.playing) "playing" else "paused"}.")
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")

    fun findApp(home: HomeModel, name: String): Tile? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return home.all.firstOrNull { norm(it.label) == n || norm(it.app.label) == n }
            ?: home.all.firstOrNull { norm(it.label).startsWith(n) || norm(it.app.label).startsWith(n) }
            ?: home.all.firstOrNull { norm(it.label).contains(n) || n.contains(norm(it.label)) }
    }

    /** Runs a tool call. Returns (what to show the user, result for the model). */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        vm: HaloViewModel,
        actions: HaloActions,
    ): Pair<String, String> = withContext(Dispatchers.Main) {
        val home = vm.home.value
        // What the user sees ("done" chips) is in Halo's language; what the model gets stays English.
        val ctx: Context = vm.getApplication<android.app.Application>()
        fun t(id: Int, vararg fa: Any): String = Lang.string(ctx, id, *fa)
        when (name) {
            "open_app" -> {
                val app = findApp(home, args["name"].orEmpty()) ?: return@withContext t(R.string.act_app_not_found) to "not installed"
                vm.openApp(app, actions)
                t(R.string.act_opened, app.label) to "opened ${app.label}"
            }
            "media_control" -> {
                val np = vm.nowPlaying
                val playing = np.state.value?.playing == true
                val label = when (args["action"]) {
                    "toggle" -> { np.playPause(); R.string.act_media_toggle }
                    "play" -> { if (!playing) np.playPause(); R.string.act_media_play }
                    "pause" -> { if (playing) np.playPause(); R.string.act_media_pause }
                    "next" -> { np.next(); R.string.act_media_next }
                    "previous" -> { np.previous(); R.string.act_media_previous }
                    else -> return@withContext t(R.string.act_unknown) to "unknown media action"
                }
                t(label) to "ok"
            }
            "show_now_playing" -> {
                vm.open(Screen.Player)
                actions.showHalo()
                t(R.string.now_playing) to "shown"
            }
            "set_theme" -> {
                val mode = when (args["theme"]) { "dark" -> ThemeMode.Dark; "light" -> ThemeMode.Light; else -> ThemeMode.Auto }
                vm.updateSettings { it.copy(theme = mode.name) }
                t(R.string.act_theme, t(themeRes(mode))) to "ok"
            }
            "switch_profile" -> {
                val p = vm.settings.value.profiles.firstOrNull {
                    it.name.equals(args["name"], true) || profileName(ctx, it).equals(args["name"], true)
                } ?: return@withContext t(R.string.act_profile_not_found) to "no such profile"
                vm.switchProfile(p.id)
                t(R.string.act_profile, profileName(ctx, p)) to "switched"
            }
            "set_background" -> {
                val src = WallpaperRepository.of(args["source"])
                vm.updateActiveProfile { it.copy(wallpaper = src.id, wallpaperPin = null) }
                t(R.string.act_background, collectionTitle(ctx, src)) to "ok"
            }
            "next_background" -> {
                val p = vm.settings.value.profile
                if (p.wallpaper == WallpaperRepository.SCENE) return@withContext t(R.string.act_scene_no_pictures) to "background is the animated scene; set a collection first"
                if (p.wallpaperPin != null) vm.updateActiveProfile { it.copy(wallpaperPin = null) }
                vm.wallpapers.skip()
                t(R.string.act_next_background) to "ok"
            }
            "set_background_interval" -> {
                val m = args["minutes"]?.trim()?.toDoubleOrNull()?.toInt()
                    ?.takeIf { v -> v in WallpaperRepository.INTERVALS }
                    ?: return@withContext t(R.string.act_unknown_interval) to "use one of ${WallpaperRepository.INTERVALS.joinToString()}"
                vm.updateActiveProfile { it.copy(wallpaperMinutes = m, wallpaperPin = if (m == 0) it.wallpaperPin else null) }
                t(R.string.act_background, intervalText(ctx, m)) to "ok"
            }
            "open_settings" -> {
                actions.openSettings(settingsSections[args["section"]] ?: Settings.ACTION_SETTINGS)
                t(settingsSectionNames[args["section"]] ?: R.string.shortcut_all_settings) to "opened"
            }
            "set_weather_city" -> {
                val city = args["city"].orEmpty().trim()
                if (city.equals("auto", true) || city.isEmpty()) vm.updateSettings { it.copy(useDeviceLocation = true) }
                else vm.updateSettings { it.copy(useDeviceLocation = false, city = city) }
                val place = if (city.equals("auto", true) || city.isEmpty()) t(R.string.settings_weather_device) else city
                t(R.string.act_weather, place) to "ok"
            }
            "add_to_favourites" -> {
                val app = findApp(home, args["name"].orEmpty()) ?: return@withContext t(R.string.act_app_not_found) to "not installed"
                if (vm.addFavourite(app.app.key)) t(R.string.act_pinned, app.label) to "ok"
                else t(R.string.act_favourites_full) to "favourites full (max 5)"
            }
            "remove_from_favourites" -> {
                val app = findApp(home, args["name"].orEmpty()) ?: return@withContext t(R.string.act_app_not_found) to "not installed"
                if (home.favourites.any { it.app.key == app.app.key }) vm.swapRow(app.app.key)
                t(R.string.act_unpinned, app.label) to "ok"
            }
            "set_top_row" -> {
                val mode = TopRow.entries.firstOrNull { it.name.equals(args["mode"], true) } ?: TopRow.Favourites
                vm.updateActiveProfile { it.copy(topRow = mode.name) }
                t(R.string.act_first_row, t(topRowTitleRes(mode))) to "ok"
            }
            "open_screen" -> {
                when (args["screen"]) {
                    "arrange" -> vm.open(Screen.Arrange(null))
                    "profiles" -> vm.open(Screen.Profiles)
                    "appearance" -> { vm.open(Screen.Home); vm.panel.value = Panel.Appearance }
                    "search" -> vm.open(Screen.Search)
                    "weather" -> vm.open(Screen.Weather)
                    "backgrounds" -> vm.open(Screen.Backgrounds)
                    "player" -> vm.open(Screen.Player)
                    else -> vm.open(Screen.Home)
                }
                actions.showHalo()
                t(R.string.act_opened, t(screenNames[args["screen"]] ?: R.string.act_screen_home)) to "ok"
            }
            "start_screensaver" -> {
                vm.startAmbient()
                actions.showHalo()
                t(R.string.shortcut_screensaver) to "ok"
            }
            "change_setting" -> changeSetting(args["setting"].orEmpty(), args["value"].orEmpty().trim(), vm, actions, ::t)
            "customize_app" -> {
                val app = findApp(home, args["name"].orEmpty()) ?: return@withContext t(R.string.act_app_not_found) to "not installed"
                val key = app.app.key
                if (args["reset"].equals("yes", true)) {
                    vm.saveStyle(key, null)
                    return@withContext t(R.string.act_tile_reset, app.label) to "reset"
                }
                var st = vm.styleOf(key) ?: TileStyle()
                args["look"]?.let { l ->
                    val src = when (l) {
                        "halo_card" -> TileSource.Card; "logo" -> TileSource.Logo; "glass" -> TileSource.Glass
                        "banner" -> TileSource.Banner; "icon" -> TileSource.Icon; else -> null
                    } ?: return@withContext t(R.string.act_unknown) to "unknown look"
                    st = st.copy(source = src.name)
                }
                args["label"]?.trim()?.takeIf { it.isNotEmpty() }?.let { st = st.copy(label = it.take(40)) }
                args["colour"]?.trim()?.let { c ->
                    val hex = (if (c.startsWith("#")) c else "#$c").uppercase()
                    if (!Regex("^#[0-9A-F]{6}$").matches(hex)) return@withContext t(R.string.act_unknown) to "colour must be #RRGGBB"
                    st = st.copy(bg = hex, tint = hex)
                }
                args["symbol"]?.let { g ->
                    val glyph = Glyph.of(g.trim()) ?: return@withContext t(R.string.act_unknown) to "unknown symbol"
                    st = st.copy(glyph = glyph.name, source = st.source ?: TileSource.Glass.name)
                }
                args["artwork_from"]?.trim()?.takeIf { it.isNotEmpty() }?.let { n ->
                    val donor = findApp(home, n) ?: return@withContext t(R.string.act_app_not_found) to "artwork app not installed"
                    st = st.copy(artFrom = donor.app.key)
                }
                vm.saveStyle(key, st)
                t(R.string.act_tile_changed, app.label) to "ok"
            }
            "arrange_app" -> {
                val app = findApp(home, args["name"].orEmpty()) ?: return@withContext t(R.string.act_app_not_found) to "not installed"
                val key = app.app.key
                when (args["action"]) {
                    "move_left" -> vm.move(key, -1)
                    "move_right" -> vm.move(key, 1)
                    "move_first" -> repeat(60) { vm.move(key, -1) }
                    "move_last" -> repeat(60) { vm.move(key, 1) }
                    "hide" -> vm.hide(key)
                    "uninstall" -> {
                        actions.uninstall(app.app.packageName)
                        return@withContext t(R.string.act_uninstall, app.label) to "the TV asks the user to confirm"
                    }
                    "customize_on_screen" -> {
                        vm.open(Screen.Customize(key, Screen.Home))
                        actions.showHalo()
                        return@withContext t(R.string.act_opened, t(R.string.customize_title)) to "opened"
                    }
                    else -> return@withContext t(R.string.act_unknown) to "unknown action"
                }
                t(if (args["action"] == "hide") R.string.act_hidden else R.string.act_moved, app.label) to "ok"
            }
            "show_hidden_apps" -> {
                vm.restoreHidden()
                t(R.string.settings_restore_hidden) to "ok"
            }
            "manage_profile" -> manageProfile(args["action"].orEmpty(), args["name"].orEmpty().trim(), args["value"].orEmpty().trim(), vm, ::t)
            "set_volume" -> {
                val am = ctx.getSystemService(android.media.AudioManager::class.java)
                    ?: return@withContext t(R.string.act_unknown) to "no audio"
                val dir = when (args["action"]) {
                    "up" -> android.media.AudioManager.ADJUST_RAISE
                    "down" -> android.media.AudioManager.ADJUST_LOWER
                    "mute" -> android.media.AudioManager.ADJUST_MUTE
                    else -> android.media.AudioManager.ADJUST_UNMUTE
                }
                runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI) }
                t(R.string.act_volume) to "ok"
            }
            "run_setup" -> {
                val step = SetupStep.entries.firstOrNull { it.name.equals(args["step"], true) }
                if (step == null) vm.openSetup(SetupStep.Language, single = false) else vm.openSetup(step, single = true)
                actions.showHalo()
                t(R.string.act_opened, t(R.string.setup_brand)) to "opened"
            }
            else -> t(R.string.act_unknown) to "unknown tool"
        }
    }

    fun assistantButtonRes(v: String): Int = when (v) {
        "star" -> R.string.settings_assistant_button_star
        else -> R.string.off
    }

    private fun parseOnOff(v: String): Boolean? = when (v.lowercase()) {
        "on", "true", "yes", "an", "ein", "1" -> true
        "off", "false", "no", "aus", "0" -> false
        else -> null
    }

    private fun changeSetting(
        setting: String,
        value: String,
        vm: HaloViewModel,
        actions: HaloActions,
        t: (Int, Array<out Any>) -> String,
    ): Pair<String, String> {
        fun tt(id: Int, vararg a: Any) = t(id, a)
        val bad = tt(R.string.act_unknown) to "invalid value; allowed: ${SETTING_VALUES[setting] ?: "?"}"
        fun flag(apply: (Boolean) -> Unit): Pair<String, String> {
            val b = parseOnOff(value) ?: return bad
            apply(b)
            return tt(R.string.act_setting, tt(if (b) R.string.act_on else R.string.act_off)) to "ok"
        }
        return when (setting) {
            "motion_effects" -> flag { b -> vm.updateSettings { it.copy(motion = b) } }
            "clock_24h" -> flag { b -> vm.updateSettings { it.copy(clock24h = b) } }
            "navigation_sounds" -> flag { b -> vm.updateSettings { it.copy(sounds = b) } }
            "show_weather" -> flag { b -> vm.updateSettings { it.copy(showWeather = b) } }
            "song_artwork_background" -> flag { b -> vm.updateActiveProfile { it.copy(mediaBackdrop = b) } }
            "assistant_button" -> {
                val v = value.lowercase().takeIf { it in listOf("off", "star") } ?: return bad
                vm.updateSettings { it.copy(assistantButton = v) }
                tt(R.string.act_setting, tt(assistantButtonRes(v))) to "ok"
            }
            "keep_halo_as_home" -> flag { b -> vm.updateSettings { it.copy(homeOverride = b) } }
            "screensaver_after_minutes" -> {
                val m = value.toDoubleOrNull()?.toInt()?.takeIf { it in listOf(0, 2, 5, 10, 15, 30) } ?: return bad
                vm.updateSettings { it.copy(ambientMinutes = m) }
                tt(R.string.act_setting, if (m == 0) tt(R.string.off) else tt(R.string.interval_minutes, m)) to "ok"
            }
            "tile_style" -> {
                val look = value.lowercase().takeIf { it in listOf("halo", "banner", "icon") } ?: return bad
                vm.updateSettings { it.copy(tileLook = look) }
                tt(R.string.act_setting, tt(when (look) {
                    "banner" -> R.string.settings_tile_banner; "icon" -> R.string.settings_tile_icon; else -> R.string.settings_tile_halo
                })) to "ok"
            }
            "language" -> {
                val code = value.lowercase().takeIf { it in Lang.CODES } ?: return bad
                actions.setLanguage(code)
                tt(R.string.act_setting, tt(when (code) {
                    "en" -> R.string.setup_language_en; "de" -> R.string.setup_language_de; else -> R.string.setup_language_system
                })) to "ok (Halo now answers in the new language)"
            }
            "voice" -> {
                val v = Speaker.VOICES.firstOrNull { it.first.equals(value, true) }?.first ?: return bad
                vm.updateSettings { it.copy(voice = v) }
                tt(R.string.act_setting, if (v == Speaker.SYSTEM_VOICE) tt(R.string.setup_voice_system) else v) to "ok"
            }
            "youtube_button" -> {
                if (parseOnOff(value) == false || value.equals("youtube", true)) {
                    vm.updateSettings { it.copy(youtubeButtonTarget = null) }
                    return tt(R.string.act_setting, "YouTube") to "ok"
                }
                val app = findApp(vm.home.value, value) ?: return tt(R.string.act_app_not_found) to "not installed"
                vm.updateSettings { it.copy(youtubeButtonTarget = app.app.packageName) }
                tt(R.string.act_setting, app.label) to "ok"
            }
            else -> tt(R.string.act_unknown) to "unknown setting"
        }
    }

    private fun manageProfile(
        action: String,
        name: String,
        value: String,
        vm: HaloViewModel,
        t: (Int, Array<out Any>) -> String,
    ): Pair<String, String> {
        fun tt(id: Int, vararg a: Any) = t(id, a)
        val ctx: Context = vm.getApplication<android.app.Application>()
        val s = vm.settings.value
        fun find() = s.profiles.firstOrNull { it.name.equals(name, true) || profileName(ctx, it).equals(name, true) }
        return when (action) {
            "create" -> {
                val id = vm.addProfile() ?: return tt(R.string.act_profiles_full) to "maximum of $MAX_PROFILES profiles reached"
                val p = vm.settings.value.profiles.first { it.id == id }
                val n = name.ifBlank { p.name }.take(24)
                vm.updateProfile(p.copy(name = n))
                tt(R.string.act_profile_created, n) to "created (not switched to it)"
            }
            "rename" -> {
                val p = find() ?: return tt(R.string.act_profile_not_found) to "no such profile"
                if (value.isBlank()) return tt(R.string.act_unknown) to "value = new name needed"
                vm.updateProfile(p.copy(name = value.take(24)))
                tt(R.string.act_profile_changed, value.take(24)) to "ok"
            }
            "delete" -> {
                val p = find() ?: return tt(R.string.act_profile_not_found) to "no such profile"
                if (p.id == MAIN_PROFILE) return tt(R.string.act_profile_main) to "the main profile can't be deleted"
                vm.removeProfile(p.id)
                tt(R.string.act_profile_deleted, profileName(ctx, p)) to "deleted"
            }
            "set_avatar" -> {
                val p = find() ?: return tt(R.string.act_profile_not_found) to "no such profile"
                val a = Avatars.all.firstOrNull { it.id.equals(value, true) || it.name.equals(value, true) }
                    ?: return tt(R.string.act_unknown) to "unknown avatar; use one of ${Avatars.all.joinToString { it.id }}"
                vm.updateProfile(p.copy(avatar = a.id))
                tt(R.string.act_profile_changed, profileName(ctx, p)) to "ok"
            }
            "set_colour" -> {
                val p = find() ?: return tt(R.string.act_profile_not_found) to "no such profile"
                val c = value.toDoubleOrNull()?.toInt()?.takeIf { it in PROFILE_COLORS.indices } ?: return tt(R.string.act_unknown) to "colour 0–${PROFILE_COLORS.size - 1}"
                vm.updateProfile(p.copy(color = c))
                tt(R.string.act_profile_changed, profileName(ctx, p)) to "ok"
            }
            else -> tt(R.string.act_unknown) to "unknown action"
        }
    }
}
