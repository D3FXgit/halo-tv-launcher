package app.halo.launcher.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloApp
import app.halo.launcher.HaloViewModel
import app.halo.launcher.MainActivity
import app.halo.launcher.R
import app.halo.launcher.data.AppEntry
import app.halo.launcher.data.Icons
import app.halo.launcher.i18n.Lang
import app.halo.launcher.ui.AssistantCard
import app.halo.launcher.ui.HaloActions
import app.halo.launcher.ui.HaloThemeProvider
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.MicButton
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.Ui
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import kotlinx.coroutines.delay

/**
 * The Halo assistant over whatever is on screen (like the Gemini overlay on Pixel phones):
 * a glowing edge and a glass card at the bottom, listening right away. Opened by holding the
 * remote's Home button (ButtonRemapService). It shares the launcher's [HaloViewModel] (see
 * [HaloApp]), so everything the assistant changes shows up on the home screen at once.
 * Closes with Back, after a quiet moment once Halo has answered, or when an app or a Halo
 * screen is opened.
 */
class AssistantOverlayActivity : ComponentActivity(), HaloActions {

    private val vm: HaloViewModel get() = (application as HaloApp).vm

    /** Bumped when the overlay is called again while open: listen again. */
    private val listenSignal = mutableIntStateOf(0)

    /** True while the microphone permission dialog is up (don't close on onStop then). */
    var askingPermission = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = resources.configuration
        Ui.k = minOf(cfg.screenWidthDp / 1920f, cfg.screenHeightDp / 1080f).takeIf { it > 0f } ?: 0.5f
        Ui.fontScale = cfg.fontScale
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        vm.resetAssistant()
        setContent {
            AssistantOverlay(
                vm = vm,
                actions = this,
                listenSignal = listenSignal.intValue,
                onPermissionDialog = { askingPermission = it },
                onClose = { close() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.resetAssistant()
        listenSignal.intValue++
    }

    override fun onStop() {
        super.onStop()
        // Another app or Home came to the front: the overlay is done.
        if (!askingPermission && !isChangingConfigurations && !isFinishing) finish()
    }

    private fun close() {
        finish()
    }

    // ---- HaloActions: what the assistant can do from here --------------------------------

    override fun launch(app: AppEntry, bounds: Rect) {
        try {
            startActivity(app.launchIntent())
        } catch (_: Exception) {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback != null) runCatching { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else Toast.makeText(this, getString(R.string.toast_cant_open, app.label), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun openSettings(action: String) {
        val ok = runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    override fun openPackage(packageName: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (i != null) runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    override fun uninstall(packageName: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    override fun setLanguage(code: String) {
        vm.updateSettings { it.copy(language = code) }
        Lang.set(this, code)
    }

    override fun showHalo() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    // Keep the screen the assistant just opened (MainActivity treats other new intents as Home).
                    .putExtra(MainActivity.EXTRA_KEEP_SCREEN, true)
            )
        }
        finish()
    }
}

@Composable
private fun AssistantOverlay(
    vm: HaloViewModel,
    actions: HaloActions,
    listenSignal: Int,
    onPermissionDialog: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val a by vm.assistant.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    val micFocus = remember { FocusRequester() }
    var shown by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val noSpeech = stringResource(R.string.search_no_speech)
    val micNeeded = stringResource(R.string.search_mic_needed)

    fun listen() {
        if (!voice.available) {
            vm.setAssistant { it.copy(error = noSpeech) }
            return
        }
        vm.setAssistant { it.copy(listening = true, heard = "", error = null, reply = null, done = emptyList()) }
        voice.start(object : VoiceInput.Listener {
            override fun onListening(active: Boolean) = vm.setAssistant { it.copy(listening = active) }
            override fun onPartial(text: String) = vm.setAssistant { it.copy(heard = text) }
            override fun onLevel(level: Float) = vm.setAssistant { it.copy(level = level) }
            override fun onResult(text: String) = vm.ask(text, actions)
            override fun onError(message: String) = vm.setAssistant { it.copy(listening = false, error = message) }
        })
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionDialog(false)
        if (granted) listen() else vm.setAssistant { it.copy(error = micNeeded) }
    }

    fun startVoice() {
        lastInput = SystemClock.elapsedRealtime()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) listen() else {
            onPermissionDialog(true)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(listenSignal) {
        shown = true
        delay(220)
        runCatching { micFocus.requestFocus() }
        startVoice()
    }

    // Once Halo has answered and nothing happens for a while, the overlay goes away by itself.
    val settled = !a.listening && !a.thinking && (a.reply != null || a.error != null)
    LaunchedEffect(settled, lastInput) {
        if (settled) {
            delay(15_000)
            onClose()
        }
    }

    BackHandler { onClose() }

    HaloThemeProvider(dark = true, motion = settings.motion) {
        val c = LocalHalo.current.colors
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown) lastInput = SystemClock.elapsedRealtime()
                    false
                },
        ) {
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 3 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 },
            ) {
                Box(Modifier.fillMaxWidth().height(620.px)) {
                    // Darkens the bottom of whatever is on screen so the card stays readable.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.45f to Color.Black.copy(alpha = 0.55f),
                                    1f to Color.Black.copy(alpha = 0.85f),
                                )
                            )
                    )
                    GlowEdge(
                        active = a.listening || a.thinking,
                        level = a.level,
                        motion = settings.motion,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 70.px),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val card = if (!a.listening && !a.thinking && a.reply == null && a.error == null && a.heard.isBlank()) a.copy(listening = true) else a
                        AssistantCard(card, Modifier.width(1400.px))
                        Spacer(Modifier.height(26.px))
                        Row(
                            Modifier.focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(18.px),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MicButton(a.listening, a.level, micFocus) {
                                if (a.listening) {
                                    voice.stop()
                                    vm.setAssistant { it.copy(listening = false) }
                                } else startVoice()
                            }
                            PillButton(stringResource(R.string.overlay_open_halo), { actions.showHalo() }, icon = app.halo.launcher.data.Glyph.home.spec, height = 64)
                            PillButton(stringResource(R.string.overlay_close), onClose, icon = Icons.close, height = 64)
                        }
                        Spacer(Modifier.height(16.px))
                        BasicText(
                            stringResource(if (a.listening) R.string.overlay_listening else R.string.overlay_hint),
                            style = hstyle(19, FontWeight.Medium, c.sub),
                        )
                    }
                }
            }
        }
    }
}

/** The glowing bottom edge: a slow aurora sweep that brightens with the voice level. */
@Composable
private fun GlowEdge(active: Boolean, level: Float, motion: Boolean, modifier: Modifier = Modifier) {
    val strength by animateFloatAsState(if (active) 0.55f + level * 0.45f else 0.35f, tween(160), label = "glow")
    val shift = if (motion) {
        val inf = rememberInfiniteTransition(label = "edge")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "sweep").value
    } else 0.5f
    val colors = listOf(Color(0xFF3BA7E0), Color(0xFF5B6CF0), Color(0xFF9B5DE5), Color(0xFFE55D9B), Color(0xFF3BA7E0))
    Box(modifier.fillMaxWidth().height(180.px)) {
        // Soft light rising from the edge.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = strength }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color(0xFF7A5CF0).copy(alpha = 0.55f),
                    )
                )
        )
        // The bright line itself.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(8.px)
                .graphicsLayer { alpha = 0.6f + strength * 0.4f }
                .background(
                    Brush.horizontalGradient(
                        colors,
                        startX = -2000f + shift * 2000f,
                        endX = 2000f + shift * 2000f,
                    )
                )
        )
    }
}
