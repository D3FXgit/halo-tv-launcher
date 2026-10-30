package app.halo.launcher.ui

import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import app.halo.launcher.AssistantState
import app.halo.launcher.R
import app.halo.launcher.assistant.VoiceInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.annotation.StringRes
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import app.halo.launcher.data.Tile
import kotlinx.coroutines.delay
import java.text.Normalizer

/**
 * A searchable destination that isn't an app. [title] is a string resource (shown and searched in
 * the interface language); [keywords] add English and German search words (lower case, no accents).
 */
data class Shortcut(@StringRes val title: Int, val icon: IconSpec, val keywords: String, val run: () -> Unit)

fun systemShortcuts(open: (String) -> Unit, halo: (String) -> Unit): List<Shortcut> = listOf(
    Shortcut(R.string.shortcut_wifi, Icons.wifi, "wifi wlan internet network netzwerk ethernet lan") { open(Settings.ACTION_WIFI_SETTINGS) },
    Shortcut(R.string.shortcut_bluetooth, Icons.bluetooth, "bluetooth remote fernbedienung controller headphones kopfhorer accessories zubehor pair koppeln") { open(Settings.ACTION_BLUETOOTH_SETTINGS) },
    Shortcut(R.string.shortcut_display, Icons.monitor, "display anzeige bild picture resolution auflosung hdr frame rate bildrate match") { open(Settings.ACTION_DISPLAY_SETTINGS) },
    Shortcut(R.string.shortcut_sound, Icons.speaker, "sound ton audio volume lautstarke dolby atmos") { open(Settings.ACTION_SOUND_SETTINGS) },
    Shortcut(R.string.shortcut_apps, Icons.apps, "apps applications anwendungen uninstall deinstallieren storage speicher permissions berechtigungen") { open(Settings.ACTION_APPLICATION_SETTINGS) },
    Shortcut(R.string.shortcut_date, Icons.clock, "date datum time uhrzeit zeit clock uhr timezone zeitzone") { open(Settings.ACTION_DATE_SETTINGS) },
    Shortcut(R.string.shortcut_language, Icons.language, "language sprache locale keyboard tastatur") { open(Settings.ACTION_LOCALE_SETTINGS) },
    Shortcut(R.string.shortcut_accessibility, Icons.accessibility, "accessibility bedienungshilfen barrierefreiheit subtitles untertitel captions contrast kontrast") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
    Shortcut(R.string.shortcut_storage, Icons.storage, "storage speicher space platz memory") { open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) },
    Shortcut(R.string.shortcut_screensaver, Icons.screensaver, "screensaver bildschirmschoner ambient daydream") { open(Settings.ACTION_DREAM_SETTINGS) },
    Shortcut(R.string.shortcut_about, Icons.info, "about info uber device gerat update system version") { open(Settings.ACTION_DEVICE_INFO_SETTINGS) },
    Shortcut(R.string.shortcut_developer, Icons.code, "developer entwickler entwickleroptionen adb debugging usb wireless") { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
    Shortcut(R.string.shortcut_all_settings, Icons.settings, "settings einstellungen system") { open(Settings.ACTION_SETTINGS) },
    Shortcut(R.string.arrange_title, Icons.arrange, "arrange anordnen order reihenfolge favourites favorites favoriten hide ausblenden move verschieben halo startbildschirm") { halo("arrange") },
    Shortcut(R.string.halo_settings, Icons.palette, "launcher appearance darstellung theme design dark dunkel light hell motion ambient screensaver bildschirmschoner clock uhr weather wetter city stadt halo settings einstellungen") { halo("appearance") },
)

private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/**
 * "Search or ask Halo": type or speak. Typing filters apps and settings live;
 * OK on "Ask Halo" (or speaking) sends the request to the Gemini assistant,
 * which can open apps, control playback, change settings and answer questions.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    tiles: List<Tile>,
    shortcuts: List<Shortcut>,
    assistant: AssistantState,
    startWithVoice: Boolean,
    onAsk: (String) -> Unit,
    onAssistant: ((AssistantState) -> AssistantState) -> Unit,
    onLaunch: (Tile, Rect) -> Unit,
) {
    val c = LocalHalo.current.colors
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    val noSpeech = stringResource(R.string.search_no_speech)
    val micNeeded = stringResource(R.string.search_mic_needed)

    fun listen() {
        keyboard?.hide()
        if (!voice.available) {
            onAssistant { it.copy(error = noSpeech) }
            return
        }
        voice.start(object : VoiceInput.Listener {
            override fun onListening(active: Boolean) = onAssistant { it.copy(listening = active, error = null) }
            override fun onPartial(text: String) { query = text }
            override fun onLevel(level: Float) = onAssistant { it.copy(level = level) }
            override fun onResult(text: String) {
                query = text
                onAsk(text)
            }
            override fun onError(message: String) = onAssistant { it.copy(listening = false, error = message) }
        })
        onAssistant { it.copy(listening = true, error = null, reply = null) }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else onAssistant { it.copy(error = micNeeded) }
    }

    fun startVoice() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        delay(120)
        if (startWithVoice) {
            runCatching { micFocus.requestFocus() }
            startVoice()
        } else {
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }

    val q = norm(query.trim())
    val apps = if (q.isEmpty()) tiles else tiles.filter { norm(it.label).contains(q) || norm(it.app.label).contains(q) }
    // Titles in the interface language; keywords cover both languages.
    val titles = shortcuts.map { norm(stringResource(it.title)) }
    val found = if (q.isEmpty()) shortcuts.take(8) else shortcuts.filterIndexed { i, s ->
        q.split(' ').filter { it.isNotEmpty() }.all { w -> titles[i].contains(w) || s.keywords.contains(w) }
    }

    Column(Modifier.fillMaxSize().readabilityScrim().padding(top = 64.px)) {
        Row(
            Modifier.padding(horizontal = 96.px).focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.px),
        ) {
            HaloTextField(
                value = query,
                modifier = Modifier.width(980.px),
                placeholder = stringResource(if (assistant.listening) R.string.search_listening else R.string.search_placeholder),
                focusRequester = field,
                commitOnChange = true,
                leading = Icons.search,
                height = 76,
            ) { query = it }
            MicButton(assistant.listening, assistant.level, micFocus) {
                if (assistant.listening) voice.stop().also { onAssistant { it.copy(listening = false) } } else startVoice()
            }
            PillButton(stringResource(R.string.search_ask), { onAsk(query) }, primary = true, icon = Icons.assistant, height = 76, enabled = query.isNotBlank())
        }

        // ---- Assistant answer ------------------------------------------------------
        val showCard = assistant.thinking || assistant.reply != null || assistant.error != null || assistant.listening
        if (showCard) {
            Spacer(Modifier.height(28.px))
            AssistantCard(assistant)
        }

        Spacer(Modifier.height(if (showCard) 28.px else 56.px))
        Label(stringResource(if (apps.isEmpty()) R.string.search_no_apps else R.string.apps), Modifier.padding(start = 96.px))
        Spacer(Modifier.height(16.px))
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(124.px).bleed(48.px).focusRestorer(),
            contentPadding = PaddingValues(horizontal = 96.px, vertical = 48.px),
            horizontalArrangement = Arrangement.spacedBy(28.px),
        ) {
            itemsIndexed(apps, key = { _, t -> t.app.key }) { i, t ->
                GlassTile(
                    tile = t, width = 220, height = 124, blur = false, ring = true,
                    edges = Edges(left = i == 0, right = i == apps.lastIndex),
                    onClick = { b -> onLaunch(t, b) },
                )
            }
        }
        Spacer(Modifier.height(40.px))
        Label(stringResource(if (found.isEmpty()) R.string.search_no_settings else R.string.search_settings), Modifier.padding(start = 96.px))
        Spacer(Modifier.height(16.px))
        FlowRow(
            Modifier.padding(horizontal = 96.px),
            horizontalArrangement = Arrangement.spacedBy(16.px),
            verticalArrangement = Arrangement.spacedBy(16.px),
        ) {
            found.forEach { s -> ShortcutChip(s) }
        }
    }
}

/** Gradient mic button that pulses with the voice level while listening. */
@Composable
internal fun MicButton(listening: Boolean, level: Float, focusRequester: FocusRequester, onClick: () -> Unit) {
    val pulse by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, tween(90), label = "mic")
    Box(contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier
                    .size(76.px)
                    .graphicsLayer {
                        scaleX = pulse * 1.25f
                        scaleY = pulse * 1.25f
                        alpha = 0.35f
                    }
                    .clip(CircleShape)
                    .background(Color(0xFF9B5DE5))
            )
        }
        RoundButton(
            Icons.mic, onClick, size = 76, iconSize = 30, focusRequester = focusRequester,
            background = Brush.linearGradient(listOf(Color(0xFF5B6CF0), Color(0xFF9B5DE5))),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantCard(a: AssistantState, modifier: Modifier = Modifier.padding(horizontal = 96.px).width(1500.px)) {
    val c = LocalHalo.current.colors
    Row(
        modifier
            .glass(RoundedCornerShape(36.px), strong = true, fillAlpha = 0.88f)
            .padding(horizontal = 32.px, vertical = 26.px),
        horizontalArrangement = Arrangement.spacedBy(24.px),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(56.px)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF5B6CF0), Color(0xFF9B5DE5)))),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(Icons.assistant, 30.px, Color.White) }
        Column(Modifier.weight(1f)) {
            if (a.heard.isNotBlank()) {
                BasicText(stringResource(R.string.search_heard, a.heard), maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(20, FontWeight.Medium, c.sub))
                Spacer(Modifier.height(8.px))
            }
            when {
                a.listening -> BasicText(stringResource(R.string.search_listening), style = hstyle(30, FontWeight.SemiBold, c.text))
                a.thinking -> ThinkingDots()
                a.error != null -> BasicText(a.error, maxLines = 3, overflow = TextOverflow.Ellipsis, style = hstyle(26, FontWeight.SemiBold, Color(0xFFF2994A)))
                a.reply != null -> BasicText(a.reply, maxLines = 4, overflow = TextOverflow.Ellipsis, style = hstyle(30, FontWeight.SemiBold, c.text))
            }
            if (a.done.isNotEmpty()) {
                Spacer(Modifier.height(14.px))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.px),
                    verticalArrangement = Arrangement.spacedBy(10.px),
                ) {
                    a.done.takeLast(4).forEach { d ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(18.px))
                                .background(c.pill)
                                .padding(horizontal = 14.px, vertical = 8.px),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HaloIcon(Icons.check, 18.px, c.text)
                            Spacer(Modifier.width(8.px))
                            BasicText(
                                d,
                                Modifier.widthIn(max = 520.px),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = hstyle(17, FontWeight.SemiBold, c.text),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThinkingDots() {
    val c = LocalHalo.current.colors
    val inf = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(10.px), modifier = Modifier.padding(vertical = 10.px)) {
        repeat(3) { i ->
            val a = inf.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse),
                label = "d$i",
            )
            Box(
                Modifier
                    .size(16.px)
                    .graphicsLayer { alpha = a.value }
                    .clip(CircleShape)
                    .background(c.text)
            )
        }
    }
}

@Composable
private fun ShortcutChip(s: Shortcut) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(30.px)
    Row(
        Modifier
            .control(st, s.run, focusScale = 1.06f)
            .height(64.px)
            .then(
                if (st.focused) Modifier.clip(shape).background(c.cta).aeroGloss()
                else Modifier.glass(shape, shadow = false, fillAlpha = 0.5f)
            )
            .border(1.px, Color.White.copy(alpha = 0.08f), shape)
            .padding(PaddingValues(start = 22.px, end = 28.px)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.px),
    ) {
        val fg = if (st.focused) c.ctaText else c.text
        HaloIcon(s.icon, 24.px, fg)
        BasicText(stringResource(s.title), maxLines = 1, style = hstyle(20, FontWeight.SemiBold, fg))
    }
}
