package app.halo.launcher.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.Panel
import app.halo.launcher.R
import app.halo.launcher.data.Glyph
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import app.halo.launcher.data.ThemeMode
import kotlinx.coroutines.delay

/** Callbacks used by the side panel. */
class PanelActions(
    val close: () -> Unit,
    val goTo: (Panel) -> Unit,
    val openSettings: (String) -> Unit,
    val updateSettings: ((HaloSettings) -> HaloSettings) -> Unit,
    val startAmbient: () -> Unit,
    val arrange: () -> Unit,
    val restoreHidden: () -> Unit,
    val profiles: () -> Unit,
    /** Opens the Background picker. */
    val backgrounds: () -> Unit = {},
    /** Plays a sample of a voice. */
    val previewVoice: (String) -> Unit = {},
)

/**
 * Halo's settings, as a frosted side sheet on the right. Opaque enough that nothing behind it can
 * be read; a fixed header and a scrolling body that always keeps the focused row clear of the edges.
 * Every setting shows all of its choices (segmented controls / switches) — nothing cycles on OK.
 */
@Composable
fun SidePanel(
    page: Panel,
    settings: HaloSettings,
    hiddenCount: Int,
    mediaAccess: Boolean,
    actions: PanelActions,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = 32.px, bottom = 32.px, end = 32.px),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .width(660.px)
                .fillMaxHeight()
                .glass(RoundedCornerShape(44.px), strong = true, fillAlpha = 0.94f),
        ) {
            // Only one page exists now (the old "Quick settings" duplicated the system's own).
            SettingsPage(settings, hiddenCount, mediaAccess, actions)
        }
    }
}

@Composable
private fun SettingsPage(settings: HaloSettings, hiddenCount: Int, mediaAccess: Boolean, a: PanelActions) {
    val c = LocalHalo.current.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }

    Column(Modifier.fillMaxSize()) {
        // ---- Fixed header ------------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 44.px, end = 32.px, top = 36.px, bottom = 20.px),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label("HALO", size = 16, color = c.sub)
                BasicText(
                    stringResource(R.string.settings_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(42, FontWeight.Bold, c.text, tracking = -0.02f),
                )
            }
            RoundButton(Icons.close, a.close, size = 56, background = SolidColor(c.pill))
        }
        // Hairline under the header once content scrolls beneath it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.px)
                .background(if (scrolled) c.rimLine else Color.Transparent)
        )

        // ---- Scrolling body ----------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScrollPadding(140.px) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(start = 44.px, end = 44.px, top = 16.px, bottom = 48.px),
                ) {
                    // Appearance
                    SectionTitle(stringResource(R.string.settings_appearance), top = false)
                    ChoiceCaption(
                        stringResource(R.string.settings_theme),
                        if (settings.themeMode == ThemeMode.Auto) stringResource(R.string.settings_theme_auto_hint) else null,
                    )
                    Segmented(
                        options = ThemeMode.entries.map { stringResource(themeRes(it)) },
                        selected = settings.themeMode.ordinal,
                        onSelect = { i -> a.updateSettings { it.copy(theme = ThemeMode.entries[i].name) } },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = first,
                    )
                    Spacer(Modifier.height(14.px))
                    Rows {
                        ToggleRow(stringResource(R.string.settings_motion), Icons.motion, stringResource(R.string.settings_motion_hint), settings.motion) {
                            a.updateSettings { s -> s.copy(motion = !s.motion) }
                        }
                        ToggleRow(stringResource(R.string.settings_clock24), Icons.clock, null, settings.clock24h) {
                            a.updateSettings { s -> s.copy(clock24h = !s.clock24h) }
                        }
                        ToggleRow(stringResource(R.string.settings_sounds), Glyph.music.spec, stringResource(R.string.settings_sounds_hint), settings.sounds) {
                            a.updateSettings { s -> s.copy(sounds = !s.sounds) }
                        }
                    }

                    // Home screen
                    SectionTitle(stringResource(R.string.settings_home_screen))
                    Rows {
                        NavRow(stringResource(R.string.bg_title), Icons.wallpaper, onClick = a.backgrounds)
                        NavRow(stringResource(R.string.arrange_title), Icons.arrange, onClick = a.arrange)
                        if (hiddenCount > 0) {
                            NavRow(
                                stringResource(R.string.settings_restore_hidden),
                                Icons.hide,
                                value = pluralResource(R.plurals.settings_hidden_count, hiddenCount, hiddenCount),
                                onClick = a.restoreHidden,
                            )
                        }
                        ToggleRow(stringResource(R.string.settings_show_weather), Icons.cloud, null, settings.showWeather) {
                            a.updateSettings { s -> s.copy(showWeather = !s.showWeather) }
                        }
                    }
                    Spacer(Modifier.height(14.px))
                    ChoiceCaption(stringResource(R.string.settings_tile_look), stringResource(R.string.settings_tile_look_hint))
                    val looks = listOf("halo", "banner", "icon")
                    Segmented(
                        options = listOf(
                            stringResource(R.string.settings_tile_halo),
                            stringResource(R.string.settings_tile_banner),
                            stringResource(R.string.settings_tile_icon),
                        ),
                        selected = looks.indexOf(settings.tileLook).coerceAtLeast(0),
                        onSelect = { i -> a.updateSettings { it.copy(tileLook = looks[i]) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.px))
                    ChoiceCaption(stringResource(R.string.settings_weather_location), null)
                    Segmented(
                        options = listOf(stringResource(R.string.settings_weather_device), stringResource(R.string.settings_weather_city)),
                        selected = if (settings.useDeviceLocation) 0 else 1,
                        onSelect = { i -> a.updateSettings { it.copy(useDeviceLocation = i == 0) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!settings.useDeviceLocation) {
                        Spacer(Modifier.height(12.px))
                        HaloTextField(
                            settings.city,
                            placeholder = stringResource(R.string.settings_city_placeholder),
                            leading = Icons.pin,
                        ) { v -> a.updateSettings { it.copy(city = v.trim()) } }
                    }

                    // Assistant voice
                    SectionTitle(stringResource(R.string.settings_voice_section))
                    ChoiceCaption(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_hint))
                    Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
                        app.halo.launcher.assistant.Speaker.VOICES.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.px)) {
                                pair.forEach { (name, character) ->
                                    val on = settings.voice == name
                                    PillButton(
                                        text = stringResource(
                                            R.string.voice_option,
                                            if (name == app.halo.launcher.assistant.Speaker.SYSTEM_VOICE) stringResource(R.string.voice_system_name) else name,
                                            stringResource(character),
                                        ),
                                        onClick = {
                                            a.updateSettings { it.copy(voice = name) }
                                            a.previewVoice(name)
                                        },
                                        primary = on,
                                        height = 58,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(14.px))
                    ChoiceCaption(stringResource(R.string.settings_assistant_button), stringResource(R.string.settings_assistant_button_hint))
                    val buttons = listOf("off", "star")
                    Segmented(
                        options = buttons.map { stringResource(app.halo.launcher.assistant.Assistant.assistantButtonRes(it)) },
                        selected = buttons.indexOf(settings.assistantButton).coerceAtLeast(0),
                        onSelect = { i -> a.updateSettings { it.copy(assistantButton = buttons[i]) } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Screensaver
                    SectionTitle(stringResource(R.string.shortcut_screensaver))
                    ChoiceCaption(stringResource(R.string.settings_start_after), stringResource(R.string.settings_screensaver_hint))
                    val steps = listOf(0, 2, 5, 10, 15, 30)
                    Segmented(
                        options = steps.map { if (it == 0) stringResource(R.string.off) else stringResource(R.string.interval_minutes, it) },
                        selected = steps.indexOf(settings.ambientMinutes),
                        onSelect = { i -> a.updateSettings { it.copy(ambientMinutes = steps[i]) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.px))
                    Rows {
                        NavRow(stringResource(R.string.settings_screensaver_now), Icons.screensaver, trailingIcon = Icons.playFilled, onClick = a.startAmbient)
                    }

                    // ---- Setup rows (added with the setup wizard) ----
                    SectionTitle(stringResource(R.string.settings_setup))
                    Rows {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val serviceOn = remember(settings) { app.halo.launcher.setup.DefaultLauncher.isServiceEnabled(ctx) }
                        val isDefaultHome = remember(settings) { app.halo.launcher.setup.DefaultLauncher.isDefaultHome(ctx) }
                        val remoteApp = remember(settings.youtubeButtonTarget) {
                            app.halo.launcher.setup.DefaultLauncher.appLabel(ctx, settings.youtubeButtonTarget)
                        }
                        // What is in effect (the per-app language can also be changed in the TV's settings).
                        val language = remember(settings.language) { app.halo.launcher.i18n.Lang.current(ctx) }
                        NavRow(
                            stringResource(R.string.settings_language),
                            Icons.language,
                            value = when (language) {
                                "en" -> stringResource(R.string.setup_language_en)
                                "de" -> stringResource(R.string.setup_language_de)
                                else -> stringResource(R.string.settings_language_system)
                            },
                            onClick = { app.halo.launcher.ui.setup.SetupNav.open(app.halo.launcher.ui.setup.SetupStep.Language, single = true) },
                        )
                        NavRow(
                            stringResource(R.string.settings_assistant_key),
                            Icons.assistant,
                            value = stringResource(if (settings.geminiKey.isNotBlank()) R.string.settings_key_saved else R.string.settings_key_missing),
                            onClick = { app.halo.launcher.ui.setup.SetupNav.open(app.halo.launcher.ui.setup.SetupStep.Assistant, single = true) },
                        )
                        NavRow(
                            stringResource(R.string.settings_home_button),
                            Glyph.home.spec,
                            value = stringResource(
                                when {
                                    isDefaultHome -> R.string.settings_home_opens_halo
                                    settings.homeOverride && serviceOn -> R.string.settings_home_brings_back
                                    settings.homeOverride -> R.string.needs_setup
                                    else -> R.string.off
                                }
                            ),
                            onClick = { app.halo.launcher.ui.setup.SetupNav.open(app.halo.launcher.ui.setup.SetupStep.Home, single = true) },
                        )
                        NavRow(
                            stringResource(R.string.settings_remote_buttons),
                            Icons.sliders,
                            value = when {
                                settings.youtubeButtonTarget == null -> "YouTube" // brand name
                                !serviceOn -> stringResource(R.string.needs_setup)
                                else -> remoteApp ?: settings.youtubeButtonTarget
                            },
                            onClick = { app.halo.launcher.ui.setup.SetupNav.open(app.halo.launcher.ui.setup.SetupStep.Remote, single = true) },
                        )
                        NavRow(
                            stringResource(R.string.settings_run_setup),
                            Icons.rocket,
                            onClick = { app.halo.launcher.ui.setup.SetupNav.open(app.halo.launcher.ui.setup.SetupStep.Language, single = false) },
                        )
                    }
                    // ---- end setup rows ----

                    // More
                    SectionTitle(stringResource(R.string.settings_more))
                    Rows {
                        NavRow(stringResource(R.string.settings_profiles), Icons.user, value = profileName(settings.profile), onClick = a.profiles)
                        StatusRow(
                            stringResource(R.string.now_playing),
                            Glyph.music.spec,
                            value = stringResource(if (mediaAccess) R.string.on else R.string.needs_setup),
                            ok = mediaAccess,
                            note = if (mediaAccess) null else stringResource(R.string.settings_media_note),
                        )
                        NavRow(
                            stringResource(R.string.settings_android_tv),
                            Icons.settings,
                            trailingIcon = Icons.external,
                            onClick = { a.openSettings(Settings.ACTION_SETTINGS) },
                        )
                    }
                }
            }
        }
    }
}

// ---- Building blocks --------------------------------------------------------------

@Composable
private fun SectionTitle(text: String, top: Boolean = true) {
    val c = LocalHalo.current.colors
    BasicText(
        text.uppercase(appLocale()),
        Modifier.padding(top = if (top) 40.px else 4.px, bottom = 14.px, start = 4.px),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = hstyle(16, FontWeight.Bold, c.sub, tracking = 0.12f),
    )
}

@Composable
private fun ChoiceCaption(title: String, hint: String?) {
    val c = LocalHalo.current.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.px, end = 4.px, bottom = 10.px),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicText(
            title,
            Modifier.widthIn(max = if (hint != null) 300.px else 560.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(21, FontWeight.SemiBold, c.text),
        )
        if (hint != null) {
            Spacer(Modifier.width(12.px))
            BasicText(
                hint,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(16, FontWeight.Medium, c.sub),
            )
        }
    }
}

@Composable
private fun Rows(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.px), content = content)
}

/** A full-width settings row: icon · title (+ subtitle) · trailing. White when focused. */
@Composable
private fun SettingRow(
    title: String,
    icon: IconSpec?,
    subtitle: String?,
    onClick: (() -> Unit)?,
    trailing: @Composable (fg: Color, focused: Boolean) -> Unit,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(22.px)
    val focused = st.focused
    val fg = if (focused) c.ctaText else c.text
    val sub = if (focused) c.ctaText.copy(alpha = 0.66f) else c.sub
    val normal = secondaryFill()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.control(st, onClick, focusScale = 1.03f) else Modifier)
            .heightIn(min = 76.px)
            .clip(shape)
            .background(if (focused) c.cta else normal)
            .then(if (focused) Modifier.aeroGloss() else Modifier.border(1.px, c.rimLine, shape))
            .padding(horizontal = 22.px, vertical = 12.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            HaloIcon(icon, 26.px, if (focused) fg else c.text.copy(alpha = 0.85f), strokeWidth = 1.8f)
            Spacer(Modifier.width(18.px))
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(22, FontWeight.SemiBold, fg))
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    Modifier.padding(top = 2.px),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(16, FontWeight.Medium, sub),
                )
            }
        }
        Spacer(Modifier.width(12.px))
        trailing(fg, focused)
    }
}

/** Opens something: a screen (chevron), the system (external icon) or an action ([trailingIcon]). */
@Composable
private fun NavRow(
    title: String,
    icon: IconSpec?,
    value: String? = null,
    trailingIcon: IconSpec = Icons.chevronRight,
    onClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    SettingRow(title, icon, null, onClick) { fg, focused ->
        if (value != null) {
            BasicText(
                value,
                Modifier.padding(end = 12.px).widthIn(max = 200.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(19, FontWeight.Medium, if (focused) fg.copy(alpha = 0.7f) else c.sub),
            )
        }
        HaloIcon(trailingIcon, 22.px, fg)
    }
}

/** On/off setting: one OK flips it, the switch shows the state. */
@Composable
private fun ToggleRow(title: String, icon: IconSpec?, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    SettingRow(title, icon, subtitle, onToggle) { _, focused ->
        ToggleSwitch(checked, onLight = focused)
    }
}

/** Read-only status line (not focusable). */
@Composable
private fun StatusRow(title: String, icon: IconSpec?, value: String, ok: Boolean, note: String?) {
    val c = LocalHalo.current.colors
    SettingRow(title, icon, note, null) { _, _ ->
        Box(
            Modifier
                .size(12.px)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF34C77B) else Color(0xFFF2A93B))
        )
        Spacer(Modifier.width(10.px))
        BasicText(
            value,
            Modifier.widthIn(max = 220.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(19, FontWeight.SemiBold, c.sub),
        )
    }
}

/** Single-line text field that opens the TV keyboard when OK is pressed. */
@Composable
fun HaloTextField(
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    commitOnChange: Boolean = false,
    leading: IconSpec? = null,
    height: Int = 64,
    onCommit: (String) -> Unit,
) {
    val c = LocalHalo.current.colors
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape((height / 2).px)
    val normal = secondaryFill()
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            if (commitOnChange) onCommit(it)
        },
        singleLine = true,
        textStyle = hstyle(22, FontWeight.Medium, c.text),
        cursorBrush = SolidColor(c.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onCommit(text)
            keyboard?.hide()
        }),
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && text != value && !commitOnChange) onCommit(text)
            }
            .onPreviewKeyEvent { e ->
                if (e.isSelect() && e.isDown) {
                    keyboard?.show()
                    true
                } else false
            },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(height.px)
                    .clip(shape)
                    .background(if (focused) c.cta.copy(alpha = 0.16f).compositeOver(normal) else normal)
                    .border(if (focused) 3.px else 1.px, if (focused) c.cta else c.rimLine, shape)
                    .padding(horizontal = 22.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.px),
            ) {
                if (leading != null) HaloIcon(leading, 24.px, c.sub)
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        BasicText(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(22, FontWeight.Medium, c.sub))
                    }
                    inner()
                }
            }
        },
    )
}
