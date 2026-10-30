package app.halo.launcher.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.Icons
import app.halo.launcher.setup.DefaultLauncher
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.ScrollPadding
import app.halo.launcher.ui.TogglePill
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import kotlinx.coroutines.delay

/**
 * A value that is re-read every [periodMs] while on screen (system state Halo can't observe
 * directly, e.g. "is our accessibility service on?" after the user comes back from Settings).
 */
@Composable
internal fun rememberPolled(periodMs: Long = 1500, read: () -> Boolean): Boolean {
    var value by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(periodMs)
            value = read()
        }
    }
    return value
}

@Composable
internal fun rememberServiceEnabled(): Boolean {
    val context = LocalContext.current
    return rememberPolled { DefaultLauncher.isServiceEnabled(context) }
}

// ==== 8. Make Halo your home screen ================================================

@Composable
internal fun HomeStep(vm: HaloViewModel, settings: HaloSettings) {
    val context = LocalContext.current
    val c = LocalHalo.current.colors
    val serviceOn = rememberServiceEnabled()
    val isDefault = rememberPolled { DefaultLauncher.isDefaultHome(context) }
    // Option A: only where the system offers the home role (rare on Google TV).
    val roleIntent = remember { DefaultLauncher.homeRoleRequest(context) }
    var roleRefused by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleRefused = !DefaultLauncher.isDefaultHome(context)
    }
    // "Test: press Home" — counts Home presses that reach Halo while this step is open.
    val presses by SetupNav.homePresses.collectAsStateWithLifecycle()
    val pressesAtStart = remember { SetupNav.homePresses.value }
    val tested = presses > pressesAtStart
    var showAdb by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // The commands are plain text (not focusable), so reveal them by scrolling to the end.
    LaunchedEffect(showAdb) {
        if (showAdb) {
            delay(60)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    ScrollPadding(120.px) {
        Column(Modifier.verticalScroll(scroll)) {
            StepHeader(stringResource(R.string.setup_home_title), stringResource(R.string.setup_home_subtitle))
            Spacer(Modifier.height(32.px))
            if (isDefault) {
                StatusLine(stringResource(R.string.setup_home_is_default), ok = true)
                Spacer(Modifier.height(24.px))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.px), verticalAlignment = Alignment.Top) {
                if (roleIntent != null && !isDefault) {
                    SetupPanel(Modifier.width(560.px)) {
                        Column {
                            GroupLabel(stringResource(R.string.setup_home_a_label))
                            SetupText(stringResource(R.string.setup_home_a_text))
                            Spacer(Modifier.height(22.px))
                            PillButton(
                                stringResource(R.string.setup_home_a_button),
                                { runCatching { roleLauncher.launch(roleIntent) }.onFailure { roleRefused = true } },
                                primary = true,
                                icon = Glyphs.home,
                            )
                            if (roleRefused) {
                                Spacer(Modifier.height(16.px))
                                StatusLine(stringResource(R.string.setup_home_a_refused), ok = false, size = 19)
                            }
                        }
                    }
                }
                SetupPanel(Modifier.width(if (roleIntent != null && !isDefault) 1000.px else 1300.px)) {
                    Column {
                        GroupLabel(stringResource(R.string.setup_home_b_label))
                        SetupText(stringResource(R.string.setup_home_b_text))
                        Spacer(Modifier.height(22.px))
                        TogglePill(
                            text = stringResource(R.string.setup_home_b_toggle),
                            checked = settings.homeOverride,
                            onToggle = { on ->
                                vm.updateSettings { it.copy(homeOverride = on) }
                                if (on && !serviceOn) DefaultLauncher.openAccessibilitySettings(context)
                            },
                            icon = Glyphs.home,
                        )
                        Spacer(Modifier.height(20.px))
                        ServiceStatus(serviceOn, needed = settings.homeOverride)
                        if (settings.homeOverride && !serviceOn) {
                            Spacer(Modifier.height(10.px))
                            SetupText(stringResource(R.string.setup_service_steps), size = 18)
                        }
                        if ((settings.homeOverride && serviceOn) || isDefault) {
                            Spacer(Modifier.height(20.px))
                            StatusLine(
                                stringResource(if (tested) R.string.setup_home_test_ok else R.string.setup_home_test),
                                ok = if (tested) true else null,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.px))
            // Option C: adb, collapsed.
            PillButton(
                stringResource(if (showAdb) R.string.setup_home_c_hide else R.string.setup_home_c_show),
                { showAdb = !showAdb },
                icon = Icons.code,
                height = 56,
            )
            if (showAdb) {
                Spacer(Modifier.height(20.px))
                SetupPanel(Modifier.width(1500.px)) {
                    val mono = hstyle(18, FontWeight.Medium, c.text).copy(fontFamily = FontFamily.Monospace)
                    Column(verticalArrangement = Arrangement.spacedBy(8.px)) {
                        SetupText(stringResource(R.string.setup_home_c_service), size = 19)
                        DefaultLauncher.adbServiceCommands(context).forEach { BasicText(it, style = mono) }
                        Spacer(Modifier.height(10.px))
                        SetupText(stringResource(R.string.setup_home_c_disable), size = 19)
                        DefaultLauncher.adbDisableStockCommands.forEach { BasicText(it, style = mono) }
                        Spacer(Modifier.height(10.px))
                        SetupText(stringResource(R.string.setup_home_c_undo), size = 19)
                        DefaultLauncher.adbUndoCommands.forEach { BasicText(it, style = mono) }
                    }
                }
            }
            Spacer(Modifier.height(24.px))
        }
    }
}

/** Icons only the setup uses. */
internal object Glyphs {
    val home = app.halo.launcher.data.Glyph.home.spec
}
