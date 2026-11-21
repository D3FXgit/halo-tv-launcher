package app.halo.launcher.ui.setup

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.Icons
import app.halo.launcher.setup.DefaultLauncher
import app.halo.launcher.ui.HomeWidget
import app.halo.launcher.ui.HomeWidgets
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.Segmented
import app.halo.launcher.ui.TogglePill
import app.halo.launcher.ui.WeatherCard
import app.halo.launcher.ui.appLocale
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import app.halo.launcher.ui.rememberNow
import app.halo.launcher.ui.tsp
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

// ---- Widgets: which ones, and how big (with a live preview) ------------------------------------

@Composable
internal fun WidgetsStep(vm: HaloViewModel, settings: HaloSettings) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { first.requestFocus() }
    }
    Column {
        StepHeader(stringResource(R.string.setup_widgets_title), stringResource(R.string.setup_widgets_subtitle))
        Spacer(Modifier.height(40.px))
        Row(horizontalArrangement = Arrangement.spacedBy(40.px)) {
            Column(Modifier.width(700.px).focusGroup()) {
                GroupLabel(stringResource(R.string.settings_widget_size))
                Segmented(
                    options = listOf(
                        stringResource(R.string.size_small),
                        stringResource(R.string.size_normal),
                        stringResource(R.string.size_large),
                        stringResource(R.string.size_xlarge),
                    ),
                    selected = HomeWidgets.SCALES.indexOfFirst { kotlin.math.abs(it - settings.widgetScale) < 0.01f }.coerceAtLeast(0),
                    onSelect = { i -> vm.updateSettings { it.copy(widgetScale = HomeWidgets.SCALES[i]) } },
                    focusRequester = first,
                )
                Spacer(Modifier.height(32.px))
                GroupLabel(stringResource(R.string.settings_widgets))
                val all = listOf("weather") + HomeWidgets.ALL
                all.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.px), modifier = Modifier.height(80.px)) {
                        pair.forEach { id ->
                            val on = if (id == "weather") settings.showWeather else id in settings.widgets
                            TogglePill(
                                text = if (id == "weather") stringResource(R.string.settings_show_weather) else stringResource(HomeWidgets.titleRes(id)),
                                checked = on,
                                onToggle = { v ->
                                    vm.updateSettings { s ->
                                        if (id == "weather") s.copy(showWeather = v)
                                        else s.copy(widgets = if (v) (s.widgets - id) + id else s.widgets - id)
                                    }
                                },
                                modifier = Modifier.width(342.px),
                            )
                        }
                    }
                }
            }
            // Preview: the home screen's clock and widgets at the chosen size, shrunk to fit.
            SetupPanel {
                Column {
                    GroupLabel(stringResource(R.string.setup_widgets_preview))
                    WidgetPreview(vm, settings)
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(vm: HaloViewModel, settings: HaloSettings) {
    val c = LocalHalo.current.colors
    val weather by vm.weather.state.collectAsStateWithLifecycle()
    val s = settings.widgetScale
    val locale = appLocale()
    val now = rememberNow()
    val time = now.format(DateTimeFormatter.ofPattern(if (settings.clock24h) "HH:mm" else "h:mm", locale))
    val date = now.format(DateTimeFormatter.ofPattern(stringResource(R.string.date_long_pattern), locale))
    val shown = ((if (settings.showWeather) listOf("weather") else emptyList()) + settings.widgets).take(2)
    var clockH by remember { mutableIntStateOf(0) }
    // Drawn at full home-screen size, then scaled down by half (not focusable).
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .size(820.px, 300.px)
            .clipToBounds()
            .focusProperties { canFocus = false; onEnter = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        // Whatever room the panel really gets: shrink the full-size home row to fit it.
        val k = minOf(maxWidth / 1720.px, maxHeight / 600.px)
        Row(
            Modifier
                .requiredSize(1720.px, 600.px)
                .graphicsLayer { scaleX = k; scaleY = k },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.px),
        ) {
            Column(Modifier.onSizeChanged { clockH = it.height }) {
                BasicText(time, style = hstyle((184 * s).toInt(), FontWeight.Light, c.text, tracking = -0.05f).copy(lineHeight = (184 * s * 0.9f).toInt().tsp))
                BasicText(date, style = hstyle((28 * s).toInt(), FontWeight.Medium, c.sub))
            }
            Spacer(Modifier.weight(1f))
            val h = with(LocalDensity.current) { if (clockH > 0) clockH.toDp() else 250.px }
            shown.forEach { id ->
                if (id == "weather") WeatherCard(weather, h, s) {} else HomeWidget(id, s, h, weather, settings.clock24h, settings.worldZone)
            }
        }
    }
    Spacer(Modifier.height(8.px))
    SetupText(stringResource(R.string.setup_widgets_preview_hint), size = 18, maxLines = 2, modifier = Modifier.width(820.px))
}

// ---- Permissions: everything Halo may ask for, in one place ------------------------------------

@Composable
internal fun PermissionsStep(vm: HaloViewModel, settings: HaloSettings) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Re-check while the TV's settings pages come and go.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            vm.nowPlaying.start()
            tick++
        }
    }
    val service = remember(tick) { DefaultLauncher.isServiceEnabled(context) }
    val media by vm.nowPlaying.hasAccess.collectAsStateWithLifecycle()
    val usage = remember(tick) { vm.hasUsageAccess() }
    val install = remember(tick) { vm.updates.canInstall() }
    val auto by vm.updates.auto.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { first.requestFocus() }
    }
    fun open(action: String, pkgUri: Boolean = false): Boolean = runCatching {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkgUri) i.data = android.net.Uri.parse("package:${context.packageName}")
        context.startActivity(i)
    }.isSuccess
    var mediaHelp by remember { mutableStateOf(false) }

    Column {
        StepHeader(stringResource(R.string.setup_perm_title), stringResource(R.string.setup_perm_subtitle))
        Spacer(Modifier.height(36.px))
        Column(verticalArrangement = Arrangement.spacedBy(24.px)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.px)) {
                PermissionCard(
                    title = stringResource(R.string.setup_perm_service),
                    text = stringResource(R.string.setup_perm_service_text),
                    ok = service,
                    button = stringResource(R.string.setup_perm_open),
                    onClick = { DefaultLauncher.openAccessibilitySettings(context) },
                    focusRequester = first,
                )
                PermissionCard(
                    title = stringResource(R.string.setup_perm_media),
                    text = if (mediaHelp) stringResource(R.string.setup_perm_media_adb) else stringResource(R.string.setup_perm_media_text),
                    ok = media,
                    button = stringResource(R.string.setup_perm_open),
                    onClick = {
                        if (!open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) mediaHelp = true
                    },
                    code = if (mediaHelp && !media) "adb shell cmd notification allow_listener ${ComponentName(context, "app.halo.launcher.media.MediaListenerService").flattenToString()}" else null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.px)) {
                PermissionCard(
                    title = stringResource(R.string.setup_perm_usage),
                    text = stringResource(R.string.setup_perm_usage_text),
                    ok = usage,
                    button = stringResource(R.string.setup_perm_open),
                    onClick = { if (!open(Settings.ACTION_USAGE_ACCESS_SETTINGS)) open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri = true) },
                )
                PermissionCard(
                    title = stringResource(R.string.setup_perm_updates),
                    text = stringResource(R.string.setup_perm_updates_text),
                    ok = install,
                    button = stringResource(R.string.setup_perm_open),
                    onClick = { runCatching { context.startActivity(vm.updates.permissionIntent()) } },
                    extra = {
                        TogglePill(
                            text = stringResource(R.string.settings_updates_auto),
                            checked = auto,
                            onToggle = { vm.updates.setAuto(it) },
                            height = 56,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    text: String,
    ok: Boolean,
    button: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    code: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val c = LocalHalo.current.colors
    SetupPanel(Modifier.width(820.px).height(330.px)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, Modifier.weight(1f), style = hstyle(28, FontWeight.Bold, c.text))
                StatusLine(stringResource(if (ok) R.string.setup_perm_on else R.string.setup_perm_off), ok = ok, size = 19)
            }
            Spacer(Modifier.height(10.px))
            SetupText(text, size = 19, maxLines = 4)
            if (code != null) {
                Spacer(Modifier.height(8.px))
                BasicText(code, maxLines = 2, style = hstyle(15, FontWeight.SemiBold, c.text).copy(fontFamily = FontFamily.Monospace))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
                if (!ok) PillButton(button, onClick, icon = Icons.external, height = 56, focusRequester = focusRequester)
                else PillButton(stringResource(R.string.setup_perm_change), onClick, height = 56, focusRequester = focusRequester)
                extra?.invoke()
            }
        }
    }
}
