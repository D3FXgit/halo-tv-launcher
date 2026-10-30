package app.halo.launcher

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.halo.launcher.data.AppEntry
import app.halo.launcher.data.AppRepository
import app.halo.launcher.data.Glyph
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.HomeLayout
import app.halo.launcher.data.KnownApps
import app.halo.launcher.data.MAIN_PROFILE
import app.halo.launcher.data.MAX_FAVOURITES
import app.halo.launcher.data.MAX_PROFILES
import app.halo.launcher.data.Prefs
import app.halo.launcher.data.Profile
import app.halo.launcher.data.TopRow
import app.halo.launcher.assistant.Assistant
import app.halo.launcher.assistant.GeminiClient
import app.halo.launcher.assistant.Speaker
import app.halo.launcher.i18n.Lang
import app.halo.launcher.setup.DefaultLauncher
import app.halo.launcher.ui.HaloActions
import app.halo.launcher.ui.setup.SetupNav
import app.halo.launcher.ui.setup.SetupRequest
import app.halo.launcher.ui.setup.SetupStep
import app.halo.launcher.wallpaper.WallpaperRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import app.halo.launcher.data.Tile
import app.halo.launcher.data.TileLayout
import app.halo.launcher.data.TileSource
import app.halo.launcher.data.TileStyle
import app.halo.launcher.media.NowPlayingRepository
import app.halo.launcher.weather.WeatherRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Arrange(val startHeld: String? = null) : Screen
    data class Customize(val key: String, val returnTo: Screen) : Screen
    data object Search : Screen
    data object Profiles : Screen
    data object Player : Screen
    data object VoiceSearch : Screen
    data object Weather : Screen
    data object Backgrounds : Screen
}

enum class Panel { None, Quick, Appearance }

data class HomeModel(
    val favourites: List<Tile> = emptyList(),
    val apps: List<Tile> = emptyList(),
    val hidden: List<Tile> = emptyList(),
    val all: List<Tile> = emptyList(),
    val ready: Boolean = false,
    /** What the first row shows (favourites, recently used or most used). */
    val topRow: List<Tile> = emptyList(),
    val topRowMode: TopRow = TopRow.Favourites,
    /** The second row: every other visible app. */
    val rest: List<Tile> = emptyList(),
)

/** State of the "Ask Halo" assistant. */
data class AssistantState(
    val listening: Boolean = false,
    val level: Float = 0f,
    val heard: String = "",
    val thinking: Boolean = false,
    val reply: String? = null,
    val error: String? = null,
    val done: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HaloViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val appsRepo = AppRepository(app, viewModelScope)
    val nowPlaying = NowPlayingRepository(app)
    val weather = WeatherRepository(app, viewModelScope)

    val settings: StateFlow<HaloSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, HaloSettings())

    // ---- setup wizard (state) ---------------------------------------------------
    /** False until the stored settings were read once (so the wizard never flashes for set-up users). */
    val settingsReady: StateFlow<Boolean> =
        prefs.settings.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Explicit wizard request (Settings rows / "Run setup again"); the first run needs none. */
    val setupRequest: StateFlow<SetupRequest?> = SetupNav.request
    /** Current wizard step (index into SetupStep.entries); survives activity recreates. */
    val setupStep: StateFlow<Int> = SetupNav.step
    // ---- end setup wizard (state) -----------------------------------------------

    private val activeProfile: String get() = settings.value.profile.id

    /** The active profile's layout; switches when the profile changes. */
    private val layout: StateFlow<HomeLayout> =
        settings.map { it.profile.id }
            .distinctUntilChanged()
            .flatMapLatest { prefs.layout(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout())

    val home: StateFlow<HomeModel> = combine(
        appsRepo.apps, layout, appsRepo.loaded, settings.map { TopRow.of(it.profile.topRow) }.distinctUntilChanged(),
        settings.map { it.tileLook }.distinctUntilChanged(),
    ) { apps, l, loaded, mode, _ ->
        buildHome(apps, l, loaded, mode)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, HomeModel())

    val wallpapers = WallpaperRepository(app, viewModelScope)
    val speaker by lazy { Speaker(app, { settings.value.geminiKey.trim() }) { settings.value.voice } }

    /** A string in Halo's interface language (the application context isn't wrapped on Android 9–12). */
    private fun str(id: Int, vararg args: Any): String =
        Lang.string(getApplication<Application>(), id, *args)

    /** Plays a short sample in [voice] (Settings → Voice), in the interface language. */
    fun previewVoice(voice: String) = speaker.say(str(R.string.voice_preview), voice)
    private val gemini = GeminiClient { settings.value.geminiKey.trim() }
    val assistant = MutableStateFlow(AssistantState())
    private var chat: List<JsonObject> = emptyList()

    val screen = MutableStateFlow<Screen>(Screen.Home)
    val panel = MutableStateFlow(Panel.None)
    val ambient = MutableStateFlow(false)
    val idle = MutableStateFlow(false)

    /** Fires when Home is pressed while Halo is already showing. */
    private val _homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homeEvents: SharedFlow<Unit> = _homeEvents

    private var lastInput = SystemClock.elapsedRealtime()

    init {
        // First start of each profile: pick sensible favourites and save them.
        viewModelScope.launch {
            appsRepo.loaded.first { it }
            settings.map { it.profile.id }.distinctUntilChanged().collect { id ->
                val apps = appsRepo.apps.value
                prefs.updateLayout(id) { l ->
                    if (l.initialised || apps.isEmpty()) l else {
                        val known = apps.filter { KnownApps.lookup(it.packageName) != null }
                        val favs = (known + apps).distinct().take(MAX_FAVOURITES).map { it.key }
                        l.copy(
                            favourites = favs,
                            apps = apps.map { it.key }.filterNot { it in favs },
                            initialised = true,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            settings.collect { weather.configure(it.showWeather, it.useDeviceLocation, it.city) }
        }
        // Setup wizard: mirror the accessibility-service options into SharedPreferences
        // (the service can't read DataStore synchronously). Stored values only, never the defaults.
        viewModelScope.launch {
            val sp = getApplication<Application>().getSharedPreferences(
                DefaultLauncher.SERVICE_PREFS, android.content.Context.MODE_PRIVATE,
            )
            prefs.settings.map { Triple(it.homeOverride, it.youtubeButtonTarget, it.assistantButton) }.distinctUntilChanged().collect { (home, yt, button) ->
                sp.edit()
                    .putBoolean(DefaultLauncher.KEY_HOME_OVERRIDE, home)
                    .putString(DefaultLauncher.KEY_YOUTUBE_TARGET, yt)
                    .putString(DefaultLauncher.KEY_ASSISTANT_BUTTON, button)
                    .apply()
            }
        }
        viewModelScope.launch {
            // Collection, interval and pinned picture take effect immediately.
            settings.map { Triple(it.profile.wallpaper, it.profile.wallpaperMinutes, it.profile.wallpaperPin) }
                .distinctUntilChanged()
                .collect { (id, minutes, pin) -> wallpapers.configure(id, minutes, pin) }
        }
        // Idle + ambient timer (only while Halo itself is in front).
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val quiet = SystemClock.elapsedRealtime() - lastInput
                idle.value = quiet > 8_000
                val mins = settings.value.ambientMinutes
                if (foreground && mins > 0 && !ambient.value && quiet > mins * 60_000L &&
                    screen.value == Screen.Home && panel.value == Panel.None &&
                    settings.value.setupDone && setupRequest.value == null // never over the setup wizard
                ) ambient.value = true
            }
        }
    }

    private fun buildHome(apps: List<AppEntry>, l: HomeLayout, loaded: Boolean, mode: TopRow): HomeModel {
        val byKey = apps.associateBy { it.key }
        val tile = { key: String -> byKey[key]?.let { resolve(it, l.styles[key]) } }
        val favs = l.favourites.mapNotNull(tile).take(MAX_FAVOURITES)
        val hiddenKeys = l.hidden.toSet()
        val placed = (l.favourites + l.hidden).toSet()
        val ordered = l.apps.filter { it in byKey && it !in placed }
        val newcomers = apps.map { it.key }.filter { it !in placed && it !in ordered }
        val rest = (ordered + newcomers).mapNotNull(tile)
        val hidden = l.hidden.filter { it in hiddenKeys }.mapNotNull(tile)
        val visible = favs + rest
        val byKeyTile = visible.associateBy { it.app.key }
        val top = when (mode) {
            TopRow.Favourites -> favs
            TopRow.Recent -> l.recent.mapNotNull { byKeyTile[it] }.take(8)
            TopRow.Popular -> l.launches.entries.sortedByDescending { it.value }.mapNotNull { byKeyTile[it.key] }.take(8)
        }
        // Nothing recent yet → fall back to favourites (and say so on the chip).
        val effective = if (top.isEmpty()) TopRow.Favourites else mode
        val shown = if (top.isEmpty()) favs else top
        val second = if (effective == TopRow.Favourites) rest else visible.filterNot { t -> shown.any { it.app.key == t.app.key } }
        return HomeModel(
            favs, rest, hidden, favs + rest + hidden, ready = loaded,
            topRow = shown, topRowMode = effective, rest = second,
        )
    }

    fun resolve(appIn: AppEntry, style: TileStyle?): Tile {
        // Always start from the installed app itself (Customize passes already-resolved tiles,
        // whose app may carry borrowed artwork).
        val app0 = appsRepo.apps.value.firstOrNull { it.key == appIn.key } ?: appIn
        // Artwork borrowed from another installed app (e.g. SmartTube wearing YouTube's card):
        // visuals come from that app, launching/identity stay with this one.
        val donor = style?.artFrom?.takeIf { it != app0.key }?.let { k -> appsRepo.apps.value.firstOrNull { it.key == k } }
        val app = if (donor == null) app0 else app0.copy(
            banner = donor.banner, icon = donor.icon, logo = donor.logo, logoBg = donor.logoBg,
            card = donor.card, brand = donor.brand, bannerTint = donor.bannerTint, autoTint = donor.autoTint,
        )
        val known = KnownApps.lookup((donor ?: app0).packageName)
        // Like Google TV: show the app's own banner whenever it ships one.
        // Apple TV style by default: the app's logo on a coloured box.
        // Every app gets the uniform Halo card (pre-rendered); older styles stay selectable.
        val defaultSource = when {
            settings.value.tileLook == "banner" && app.banner != null -> TileSource.Banner
            settings.value.tileLook == "icon" && app.icon != null -> TileSource.Icon
            app.card != null -> TileSource.Card
            app.logo != null -> TileSource.Logo
            app.banner != null -> TileSource.Banner
            known != null -> TileSource.Glass
            else -> TileSource.Icon
        }
        var source = style?.source?.let { s -> TileSource.entries.firstOrNull { it.name == s } } ?: defaultSource
        if (source == TileSource.Banner && app.banner == null) source = TileSource.Icon
        if (source == TileSource.Icon && app.icon == null) source = TileSource.Glass
        if (source == TileSource.Logo && app.logo == null) source = TileSource.Glass
        if (source == TileSource.Card && app.card == null) source = if (app.logo != null) TileSource.Logo else TileSource.Glass
        val (tintSet, tint) = KnownApps.parseTint(style?.tint)
        // Curated labels are brand names; generic ones ("Settings", "Files", "Photos") would stay English,
        // so for those the app's own (system-localized) label wins.
        val label = style?.label?.takeIf { it.isNotBlank() }
            ?: KnownApps.lookup(app0.packageName)?.label?.takeIf { it !in GENERIC_LABELS }
            ?: app0.label
        val cardColor = if (source == TileSource.Card) cardColorOf(style) else null
        return Tile(
            app = if (source != TileSource.Card) app
            else if (donor == null) withCard(app, style, cardColor)
            else app.copy(card = withCard(donor, style, cardColor).card),
            source = source,
            glyph = Glyph.of(style?.glyph) ?: known?.glyph ?: KnownApps.guessGlyph(app.label),
            tint = when {
                tintSet -> tint
                source == TileSource.Card -> cardColor?.let { androidx.compose.ui.graphics.Color(it) } ?: app.brand ?: known?.tint ?: app.autoTint
                source == TileSource.Banner -> app.bannerTint ?: known?.tint ?: app.autoTint
                else -> known?.tint ?: app.autoTint
            },
            layout = style?.layout?.let { s -> TileLayout.entries.firstOrNull { it.name == s } } ?: TileLayout.Both,
            label = label,
            bg = KnownApps.parseTint(style?.bg).second ?: app.logoBg ?: known?.tint ?: app.autoTint,
            logoScale = (style?.logoScale ?: 0.62f).coerceIn(0.35f, 0.95f),
        )
    }

    /** Custom card colour of a tile (TileStyle.bg, "#RRGGBB") as ARGB, or null. */
    private fun cardColorOf(style: TileStyle?): Int? =
        style?.bg?.takeIf { it.startsWith("#") }?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }

    /**
     * [app] carrying the right card for its style: the automatic card, or (custom name/colour set)
     * a re-rendered one from the repository's LRU. The automatic card is looked up by key, because
     * [app] may already be a copy that carries a custom card (Customize resolves resolved tiles).
     */
    private fun withCard(app: AppEntry, style: TileStyle?, color: Int?): AppEntry {
        val name = style?.label?.takeIf { it.isNotBlank() }
        val card = if (name == null && color == null) appsRepo.baseCard(app.key) ?: app.card
        else appsRepo.customCard(app, name, color) ?: appsRepo.baseCard(app.key) ?: app.card
        return if (card === app.card) app else app.copy(card = card)
    }

    // ---- input / navigation -------------------------------------------------

    /** True between Activity onStart and onStop. */
    @Volatile var foreground = false

    fun onUserInteraction() {
        lastInput = SystemClock.elapsedRealtime()
        idle.value = false
    }

    /** Returns true if the key press was used to leave ambient mode. */
    fun wakeFromAmbient(): Boolean {
        onUserInteraction()
        if (ambient.value) {
            ambient.value = false
            return true
        }
        return false
    }

    fun onHomePressed() {
        onUserInteraction()
        // Setup wizard: count presses (Home step's "press Home" test); Home closes a step opened
        // standalone from Settings, except the Home step itself (that's where the test runs).
        SetupNav.homePresses.value++
        val req = setupRequest.value
        if (req != null && req.single && req.start != SetupStep.Home) closeSetup()
        ambient.value = false
        panel.value = Panel.None
        screen.value = Screen.Home
        _homeEvents.tryEmit(Unit)
    }

    fun startAmbient() {
        panel.value = Panel.None
        ambient.value = true
    }

    /** Back button. Returns false when there is nothing to go back from. */
    fun back(): Boolean {
        when {
            ambient.value -> ambient.value = false
            panel.value != Panel.None -> panel.value = Panel.None
            screen.value is Screen.Customize -> screen.value = (screen.value as Screen.Customize).returnTo
            screen.value != Screen.Home -> screen.value = Screen.Home
            else -> return false
        }
        return true
    }

    fun open(s: Screen) {
        panel.value = Panel.None
        screen.value = s
    }

    // ---- layout edits -------------------------------------------------------

    private fun edit(f: (HomeLayout) -> HomeLayout) {
        val id = activeProfile
        viewModelScope.launch { prefs.updateLayout(id, f) }
    }

    /** Writes the visible order into the layout (newcomers become explicit). */
    private fun snapshot(l: HomeLayout): HomeLayout {
        // Built from the stored layout itself (not the UI state), so back-to-back edits don't overwrite each other.
        val h = buildHome(appsRepo.apps.value, l, true, TopRow.Favourites)
        return l.copy(
            favourites = h.favourites.map { it.app.key },
            apps = h.apps.map { it.app.key },
            hidden = h.hidden.map { it.app.key },
        )
    }

    fun move(key: String, delta: Int) = edit { raw ->
        val l = snapshot(raw)
        fun MutableList<String>.shift(): Boolean {
            val i = indexOf(key)
            val j = i + delta
            if (i < 0 || j !in indices) return false
            add(j, removeAt(i))
            return true
        }
        val favs = l.favourites.toMutableList()
        val apps = l.apps.toMutableList()
        if (!favs.shift()) apps.shift()
        l.copy(favourites = favs, apps = apps)
    }

    /** Moves between Favourites and Apps. Returns false if favourites are full. */
    fun swapRow(key: String): Boolean {
        val h = home.value
        val inFav = h.favourites.any { it.app.key == key }
        if (!inFav && h.favourites.size >= MAX_FAVOURITES) return false
        edit { raw ->
            val l = snapshot(raw)
            when {
                key in l.favourites -> l.copy(favourites = l.favourites - key, apps = listOf(key) + (l.apps - key))
                l.favourites.size >= MAX_FAVOURITES -> l // full: re-checked against the stored layout
                else -> l.copy(favourites = l.favourites + key, apps = l.apps - key)
            }
        }
        return true
    }

    fun hide(key: String) = edit { raw ->
        val l = snapshot(raw)
        l.copy(favourites = l.favourites - key, apps = l.apps - key, hidden = l.hidden - key + key)
    }

    fun restoreHidden() = edit { raw ->
        val l = snapshot(raw)
        l.copy(apps = l.apps + l.hidden, hidden = emptyList())
    }

    fun saveStyle(key: String, style: TileStyle?) = edit { l ->
        l.copy(styles = if (style == null) l.styles - key else l.styles + (key to style))
    }

    fun styleOf(key: String): TileStyle? = layout.value.styles[key]

    fun updateSettings(f: (HaloSettings) -> HaloSettings) {
        viewModelScope.launch { prefs.updateSettings(f) }
    }

    /** Remembers launches for the "Recently used" / "Most used" first row. */
    fun recordLaunch(key: String) = edit { l ->
        l.copy(
            recent = (listOf(key) + l.recent.filterNot { it == key }).take(20),
            launches = l.launches + (key to ((l.launches[key] ?: 0) + 1)),
        )
    }

    fun addFavourite(key: String): Boolean {
        val h = home.value
        if (h.favourites.any { it.app.key == key }) return true
        if (h.favourites.size >= MAX_FAVOURITES) return false
        edit { raw ->
            val l = snapshot(raw)
            if (key in l.favourites || l.favourites.size >= MAX_FAVOURITES) l
            else l.copy(favourites = l.favourites + key, apps = l.apps - key)
        }
        return true
    }

    fun updateActiveProfile(f: (Profile) -> Profile) {
        val id = activeProfile
        updateSettings { s -> s.copy(profiles = s.profiles.map { if (it.id == id) f(it) else it }) }
    }

    // ---- assistant --------------------------------------------------------------

    val assistantConfigured: Boolean get() = gemini.configured

    fun setAssistant(f: (AssistantState) -> AssistantState) {
        assistant.update(f)
    }

    fun resetAssistant() {
        speaker.stop()
        chat = emptyList()
        assistant.value = AssistantState()
    }

    /** Sends a request (typed or spoken) to Gemini, which may act on the launcher through tools. */
    fun ask(text: String, actions: HaloActions) {
        val q = text.trim()
        if (q.isEmpty()) return
        speaker.stop() // creates the text-to-speech engine early so the reply can be spoken
        setAssistant { it.copy(heard = q, thinking = true, reply = null, error = null, done = emptyList()) }
        // Instant path: the request is just an app name.
        val direct = home.value.all.firstOrNull { it.label.equals(q, true) || it.app.label.equals(q, true) }
        if (direct != null) {
            setAssistant { it.copy(thinking = false, reply = str(R.string.assistant_opening, direct.label)) }
            openApp(direct, actions)
            return
        }
        if (!gemini.configured) {
            setAssistant {
                it.copy(thinking = false, error = str(R.string.assistant_no_key))
            }
            return
        }
        viewModelScope.launch {
            val tools = Assistant.tools(home.value, settings.value)
            runCatching {
                gemini.ask(Assistant.systemPrompt(getApplication<Application>(), home.value, settings.value, weather.state.value, nowPlaying.state.value),
                    GeminiClient.trimHistory(chat, 16), q, tools) { name, args ->
                    val r = Assistant.execute(name, args, this@HaloViewModel, actions)
                    setAssistant { it.copy(done = it.done + r.first) }
                    r.second
                }
            }.onSuccess { (raw, history) ->
                chat = history
                val reply = localizedReply(raw)
                setAssistant { it.copy(thinking = false, reply = reply) }
                speaker.say(reply)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                setAssistant { it.copy(thinking = false, error = assistantError(e)) }
            }
        }
    }

    /** GeminiClient's own fallback replies are English; show (and speak) them in Halo's language. */
    private fun localizedReply(reply: String): String = when (reply.trim()) {
        "" , "Done." -> str(R.string.assistant_done)
        "Sorry, I have no answer to that." -> str(R.string.assistant_no_answer)
        "Sorry, I couldn’t finish that answer." -> str(R.string.assistant_incomplete)
        else -> reply
    }

    /** A short, localized error line (Google's API messages are English and technical). */
    private fun assistantError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException -> str(R.string.assistant_offline)
            msg.contains("API key", ignoreCase = true) || msg.contains("API_KEY", ignoreCase = true) ->
                str(R.string.assistant_bad_key)
            msg.contains("quota", ignoreCase = true) || msg.contains("exhausted", ignoreCase = true) ||
                msg.contains("HTTP 429") -> str(R.string.assistant_quota)
            msg.isNotBlank() -> str(R.string.assistant_error_detail, msg.take(160))
            else -> str(R.string.assistant_error)
        }
    }

    fun openApp(t: Tile, actions: HaloActions) {
        recordLaunch(t.app.key)
        actions.launch(t.app, androidx.compose.ui.geometry.Rect.Zero)
    }

    // ---- profiles -------------------------------------------------------------

    fun switchProfile(id: String) {
        updateSettings { it.copy(activeProfile = id) }
        open(Screen.Home)
    }

    /** Adds a profile and returns its id, or null when the maximum is reached. */
    fun addProfile(): String? {
        val s = settings.value
        if (s.profiles.size >= MAX_PROFILES) return null
        val id = "p" + System.currentTimeMillis().toString(36)
        updateSettings { cur ->
            cur.copy(profiles = cur.profiles + Profile(id, str(R.string.profile_new_name, cur.profiles.size + 1), cur.profiles.size))
        }
        return id
    }

    fun updateProfile(p: Profile) = updateSettings { s ->
        s.copy(profiles = s.profiles.map { if (it.id == p.id) p else it })
    }

    /** The main profile can be renamed but not removed. */
    fun removeProfile(id: String) {
        if (id == MAIN_PROFILE) return
        updateSettings { s ->
            s.copy(
                profiles = s.profiles.filterNot { it.id == id },
                activeProfile = if (s.activeProfile == id) MAIN_PROFILE else s.activeProfile,
            )
        }
        viewModelScope.launch { prefs.deleteLayout(id) }
    }

    // ---- setup wizard (actions) -------------------------------------------------

    /** Opens the wizard at [step]; [single] = just that step, standalone (from Settings). */
    fun openSetup(step: SetupStep, single: Boolean) {
        // A single step returns to the Settings panel it was opened from; the full wizard doesn't.
        if (!single) panel.value = Panel.None
        SetupNav.open(step, single)
    }

    /** Leaves a wizard that was opened explicitly (the first run can only be finished). */
    fun closeSetup() {
        SetupNav.request.value = null
    }

    fun setSetupStep(index: Int) {
        SetupNav.step.value =
            index.coerceIn(0, SetupStep.entries.size - 1)
    }

    /** "Start using Halo" (or "Skip setup"): marks setup done and shows Home. */
    fun finishSetup() {
        updateSettings { it.copy(setupDone = true) }
        closeSetup()
        panel.value = Panel.None
        screen.value = Screen.Home
        onUserInteraction()
    }

    /** Saves (or with "" removes) the user's Gemini API key. */
    fun setGeminiKey(key: String) = updateSettings { it.copy(geminiKey = key.trim()) }

    // ---- end setup wizard (actions) ---------------------------------------------

    private companion object {
        /** KnownApps labels that are plain words rather than brand names. */
        val GENERIC_LABELS = setOf("Settings", "Files", "Photos")
    }

    override fun onCleared() {
        runCatching { speaker.shutdown() }
        appsRepo.close()
        nowPlaying.stop()
    }
}
