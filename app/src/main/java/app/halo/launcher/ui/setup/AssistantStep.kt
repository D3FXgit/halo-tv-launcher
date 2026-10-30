package app.halo.launcher.ui.setup

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.assistant.Speaker
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.Icons
import app.halo.launcher.setup.KeyServer
import app.halo.launcher.setup.Qr
import app.halo.launcher.ui.HaloIcon
import app.halo.launcher.ui.HaloTextField
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.ScrollPadding
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of checking a key typed on the TV. */
private enum class Typed { Idle, Checking, Saved, Invalid, Offline }

// ==== 7. Assistant (Gemini API key + voice) =========================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantStep(vm: HaloViewModel, settings: HaloSettings) {
    val context = LocalContext.current
    val c = LocalHalo.current.colors
    val scope = rememberCoroutineScope()

    // The local web page for the phone: runs only while this step is on screen and Halo is in
    // front. It starts by itself only when no key is saved yet; otherwise on request.
    val server = remember { KeyServer(context) { key -> vm.setGeminiKey(key) } }
    var serverWanted by remember { mutableStateOf(settings.geminiKey.isBlank()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(server, lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (serverWanted) server.start()
                Lifecycle.Event.ON_STOP -> server.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            server.stop()
        }
    }
    fun startServer() {
        serverWanted = true
        server.start()
    }
    val st by server.state.collectAsStateWithLifecycle()
    val qr = remember(st.url) { st.url?.let { Qr.encode(it) } }

    var typed by remember { mutableStateOf(Typed.Idle) }
    var pending by remember { mutableStateOf("") }
    var lastChecked by remember { mutableStateOf("") }
    val hasKey = settings.geminiKey.isNotBlank()

    fun checkTyped(raw: String) {
        val key = raw.trim()
        if (key.isEmpty() || key == lastChecked) return
        lastChecked = key
        pending = key
        typed = Typed.Checking
        scope.launch {
            val r = withContext(Dispatchers.IO) { KeyServer.check(key) }
            typed = when (r) {
                KeyServer.Check.Valid -> {
                    vm.setGeminiKey(key)
                    Typed.Saved
                }
                KeyServer.Check.Invalid -> Typed.Invalid
                KeyServer.Check.Offline -> Typed.Offline
            }
        }
    }

    val scroll = rememberScrollState()
    ScrollPadding(120.px) {
        Column(Modifier.verticalScroll(scroll)) {
            StepHeader(stringResource(R.string.setup_assistant_title), stringResource(R.string.setup_assistant_subtitle))
            Spacer(Modifier.height(18.px))
            StatusLine(
                stringResource(if (hasKey) R.string.setup_assistant_key_saved else R.string.setup_assistant_key_missing),
                ok = if (hasKey) true else null,
            )
            Spacer(Modifier.height(26.px))
            Row(horizontalArrangement = Arrangement.spacedBy(36.px), verticalAlignment = Alignment.Top) {
                // ---- From a phone: QR code + address ------------------------------------
                SetupPanel(Modifier.width(800.px)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.px)) {
                        Box(
                            Modifier
                                .size(300.px)
                                .clip(RoundedCornerShape(24.px))
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            val img = qr
                            if (img != null && st.phase != KeyServer.Phase.Received && st.phase != KeyServer.Phase.Expired) {
                                Image(
                                    bitmap = img,
                                    contentDescription = st.url,
                                    modifier = Modifier.fillMaxSize().padding(10.px),
                                    filterQuality = FilterQuality.None,
                                )
                            } else {
                                HaloIcon(
                                    if (st.phase == KeyServer.Phase.Received) Icons.check else Icons.wifi,
                                    96.px,
                                    Color(0xFF0A0C12).copy(alpha = 0.55f),
                                    strokeWidth = 1.6f,
                                )
                            }
                        }
                        Column(Modifier.width(400.px)) {
                            GroupLabel(stringResource(R.string.setup_assistant_phone_label))
                            SetupText(stringResource(R.string.setup_assistant_phone_text), maxLines = 3)
                            Spacer(Modifier.height(16.px))
                            val url = st.url
                            if (url != null && st.phase != KeyServer.Phase.Received && st.phase != KeyServer.Phase.Expired) {
                                BasicText(
                                    url.removePrefix("http://"),
                                    maxLines = 2,
                                    style = hstyle(21, FontWeight.Bold, c.text).copy(fontFamily = FontFamily.Monospace),
                                )
                                Spacer(Modifier.height(16.px))
                            }
                            val (msg, ok) = when (st.phase) {
                                KeyServer.Phase.Idle -> (if (serverWanted) R.string.setup_assistant_starting else R.string.setup_assistant_phone_off) to null
                                KeyServer.Phase.Waiting -> R.string.setup_assistant_waiting to null
                                KeyServer.Phase.Checking -> R.string.setup_assistant_checking to null
                                KeyServer.Phase.Rejected -> R.string.setup_assistant_rejected to false
                                KeyServer.Phase.Offline -> R.string.setup_assistant_offline to false
                                KeyServer.Phase.Received -> R.string.setup_assistant_received to true
                                KeyServer.Phase.Expired -> R.string.setup_assistant_expired to false
                                KeyServer.Phase.NoNetwork -> R.string.setup_assistant_no_network to false
                            }
                            StatusLine(stringResource(msg), ok = ok, size = 20)
                            if (st.phase == KeyServer.Phase.Expired || st.phase == KeyServer.Phase.NoNetwork ||
                                st.phase == KeyServer.Phase.Received || (st.phase == KeyServer.Phase.Idle && !serverWanted)
                            ) {
                                Spacer(Modifier.height(16.px))
                                PillButton(stringResource(R.string.setup_assistant_new_code), { startServer() }, icon = Icons.wifi, height = 56)
                            }
                        }
                    }
                }
                // ---- On the TV: type or paste ---------------------------------------------
                SetupPanel(Modifier.width(800.px)) {
                    Column {
                        GroupLabel(stringResource(R.string.setup_assistant_tv_label))
                        HaloTextField(
                            value = "",
                            modifier = Modifier.width(736.px),
                            placeholder = stringResource(R.string.setup_assistant_tv_hint),
                            leading = Icons.assistant,
                            onCommit = { v -> checkTyped(v) },
                        )
                        Spacer(Modifier.height(14.px))
                        when (typed) {
                            Typed.Idle -> SetupText(stringResource(R.string.setup_assistant_tv_text), size = 19)
                            Typed.Checking -> StatusLine(stringResource(R.string.setup_assistant_tv_checking), ok = null, size = 20)
                            Typed.Saved -> StatusLine(stringResource(R.string.setup_assistant_tv_saved), ok = true, size = 20)
                            Typed.Invalid -> StatusLine(stringResource(R.string.setup_assistant_tv_invalid), ok = false, size = 20)
                            Typed.Offline -> {
                                StatusLine(stringResource(R.string.setup_assistant_tv_offline), ok = false, size = 20)
                                Spacer(Modifier.height(12.px))
                                PillButton(
                                    stringResource(R.string.setup_assistant_save_anyway),
                                    { vm.setGeminiKey(pending); typed = Typed.Saved },
                                    height = 56,
                                )
                            }
                        }
                        Spacer(Modifier.height(22.px))
                        SetupText(stringResource(R.string.setup_assistant_get_key), size = 19)
                        BasicText(
                            "aistudio.google.com/apikey",
                            Modifier.padding(top = 4.px),
                            style = hstyle(21, FontWeight.Bold, c.aqua),
                        )
                        if (hasKey) {
                            Spacer(Modifier.height(18.px))
                            PillButton(
                                stringResource(R.string.setup_assistant_remove),
                                {
                                    vm.setGeminiKey("")
                                    typed = Typed.Idle
                                    lastChecked = ""
                                },
                                icon = Icons.trash,
                                height = 56,
                            )
                        }
                    }
                }
            }
            // ---- Voice ----------------------------------------------------------------------
            Spacer(Modifier.height(30.px))
            GroupLabel(stringResource(R.string.setup_voice_label))
            SetupText(stringResource(R.string.setup_voice_hint), Modifier.padding(start = 4.px, bottom = 14.px), size = 19)
            FlowRow(
                Modifier.width(1636.px).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.px),
                verticalArrangement = Arrangement.spacedBy(12.px),
                maxItemsInEachRow = 5,
            ) {
                Speaker.VOICES.forEach { (name, _) ->
                    PillButton(
                        text = if (name == Speaker.SYSTEM_VOICE) stringResource(R.string.setup_voice_system) else name,
                        onClick = {
                            vm.updateSettings { it.copy(voice = name) }
                            vm.previewVoice(name)
                        },
                        primary = settings.voice == name,
                        height = 58,
                        modifier = Modifier.width(310.px),
                    )
                }
            }
            Spacer(Modifier.height(24.px))
        }
    }
}
