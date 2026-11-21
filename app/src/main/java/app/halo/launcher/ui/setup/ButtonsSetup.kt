package app.halo.launcher.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.data.ButtonBinding
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.Icons
import app.halo.launcher.remap.ButtonLearn
import app.halo.launcher.remap.Buttons
import app.halo.launcher.setup.DefaultLauncher
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.glass
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import kotlinx.coroutines.delay

private fun actionRes(a: String) = when (a) {
    "assistant" -> R.string.btn_assistant
    "recents" -> R.string.btn_recents
    else -> R.string.btn_home
}

private fun pressRes(p: String) = when (p) {
    Buttons.HOLD -> R.string.btn_hold
    Buttons.DOUBLE -> R.string.btn_double
    else -> R.string.btn_press
}

/** Saves [b] (replacing whatever that button + press did before). From now on the learned list applies. */
private fun save(vm: HaloViewModel, b: ButtonBinding) = vm.updateSettings { s ->
    val base = Buttons.effective(s)
    s.copy(buttons = base.filterNot { it.key == b.key && it.press == b.press } + b, assistantButton = "off")
}

private fun remove(vm: HaloViewModel, action: String) = vm.updateSettings { s ->
    s.copy(buttons = Buttons.effective(s).filterNot { it.action == action }, assistantButton = "off")
}

/** Three cards: Assistant, Recent apps, Halo home screen — each with its button(s), Learn and Remove. */
@Composable
internal fun ButtonActions(vm: HaloViewModel, settings: HaloSettings, serviceOn: Boolean) {
    val context = LocalContext.current
    val c = LocalHalo.current.colors
    var learning by remember { mutableStateOf<String?>(null) }
    val bindings = Buttons.effective(settings)
    Row(horizontalArrangement = Arrangement.spacedBy(24.px)) {
        Buttons.ACTIONS.forEach { action ->
            val mine = bindings.filter { it.action == action }
            Column(
                Modifier
                    .width(540.px)
                    .glass(RoundedCornerShape(30.px), strong = true, fillAlpha = 0.8f)
                    .padding(24.px)
                    .focusGroup(),
            ) {
                BasicText(stringResource(actionRes(action)), style = hstyle(25, FontWeight.Bold, c.text))
                Spacer(Modifier.height(6.px))
                BasicText(
                    if (mine.isEmpty()) stringResource(R.string.btn_none)
                    else mine.joinToString("  ·  ") { "${Buttons.keyName(it.key)} ${contextString(context, pressRes(it.press))}" },
                    maxLines = 1,
                    style = hstyle(20, FontWeight.Medium, if (mine.isEmpty()) c.sub else c.aqua),
                )
                Spacer(Modifier.height(14.px))
                Row(horizontalArrangement = Arrangement.spacedBy(12.px)) {
                    PillButton(stringResource(R.string.btn_learn), {
                        if (!serviceOn) DefaultLauncher.openAccessibilitySettings(context) else learning = action
                    }, icon = Icons.sliders, height = 52)
                    if (mine.isNotEmpty()) PillButton(stringResource(R.string.btn_remove), { remove(vm, action) }, icon = Icons.close, height = 52)
                }
            }
        }
    }
    learning?.let { action ->
        LearnDialog(action) { b ->
            learning = null
            if (b != null) save(vm, b)
        }
    }
}

private fun contextString(context: android.content.Context, id: Int) = app.halo.launcher.i18n.Lang.string(context, id).lowercase()

/** "Press the button you want": listens for 10 s, then asks how the button should be pressed. */
@Composable
private fun LearnDialog(action: String, onDone: (ButtonBinding?) -> Unit) {
    val c = LocalHalo.current.colors
    var round by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(10) }
    val key by ButtonLearn.key.collectAsStateWithLifecycle()
    val blocked by ButtonLearn.blocked.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(round) {
        ButtonLearn.start()
        left = 10
        while (left > 0 && ButtonLearn.key.value == null) {
            delay(1_000)
            left--
        }
    }
    DisposableEffect(Unit) { onDispose { ButtonLearn.stop() } }
    LaunchedEffect(key, left == 0) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
    BackHandler { onDone(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF03050C).copy(alpha = 0.75f))
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(1000.px)
                .glass(RoundedCornerShape(40.px), strong = true, fillAlpha = 0.94f)
                .padding(48.px),
        ) {
            BasicText(stringResource(actionRes(action)).uppercase(), style = hstyle(17, FontWeight.Bold, c.sub, tracking = 0.12f))
            Spacer(Modifier.height(10.px))
            val k = key
            when {
                k != null -> {
                    BasicText(stringResource(R.string.learn_got, Buttons.keyName(k)), style = hstyle(36, FontWeight.Bold, c.text))
                    Spacer(Modifier.height(28.px))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                        Buttons.PRESSES.forEachIndexed { i, p ->
                            PillButton(
                                stringResource(pressRes(p)),
                                { onDone(ButtonBinding(k, p, action)) },
                                primary = i == 0,
                                focusRequester = if (i == 0) focus else null,
                            )
                        }
                        PillButton(stringResource(R.string.cancel), { onDone(null) })
                    }
                }
                left == 0 -> {
                    BasicText(stringResource(R.string.learn_none), maxLines = 4, style = hstyle(26, FontWeight.SemiBold, c.text))
                    Spacer(Modifier.height(28.px))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                        PillButton(stringResource(R.string.learn_retry), { round++ }, primary = true, focusRequester = focus)
                        PillButton(stringResource(R.string.cancel), { onDone(null) })
                    }
                }
                else -> {
                    BasicText(stringResource(R.string.learn_title), style = hstyle(40, FontWeight.Bold, c.text))
                    Spacer(Modifier.height(12.px))
                    BasicText(stringResource(R.string.learn_hint, left), maxLines = 3, style = hstyle(22, FontWeight.Medium, c.sub))
                    blocked?.let { b ->
                        Spacer(Modifier.height(12.px))
                        BasicText(stringResource(R.string.learn_blocked, Buttons.keyName(b)), maxLines = 2, style = hstyle(21, FontWeight.SemiBold, Color(0xFFF2A93B)))
                    }
                    Spacer(Modifier.height(28.px))
                    PillButton(stringResource(R.string.cancel), { onDone(null) }, focusRequester = focus)
                }
            }
        }
    }
}
