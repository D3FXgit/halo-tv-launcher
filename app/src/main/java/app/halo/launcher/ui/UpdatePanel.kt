package app.halo.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.BuildConfig
import app.halo.launcher.R
import app.halo.launcher.data.Icons
import app.halo.launcher.update.Updater
import kotlinx.coroutines.delay

/** "A new version of Halo is available": the update prompt and its download / install progress. */
@Composable
fun UpdatePanel(updater: Updater) {
    val st by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = LocalHalo.current.colors
    val first = remember { FocusRequester() }
    val s = st
    if (s is Updater.State.Idle || s is Updater.State.Checking || s is Updater.State.UpToDate) {
        LaunchedEffect(Unit) { updater.later() }
        return
    }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(s::class) {
        // Home may take focus back right after the panel appears: retry until the panel has it.
        repeat(15) {
            delay(120)
            if (inside && it > 0) return@LaunchedEffect
            runCatching { first.requestFocus() }
        }
    }
    BackHandler { updater.later() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF03050C).copy(alpha = 0.72f))
            .onFocusChanged { inside = it.hasFocus }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(1100.px)
                .glass(RoundedCornerShape(40.px), strong = true, fillAlpha = 0.92f)
                .padding(52.px),
        ) {
            Label("HALO", size = 16, color = c.sub)
            val title = when (s) {
                is Updater.State.Available -> stringResource(R.string.upd_title, s.release.version)
                is Updater.State.Downloading -> stringResource(R.string.upd_downloading, s.percent)
                is Updater.State.NeedsPermission -> stringResource(R.string.upd_permission_title)
                is Updater.State.Installing -> stringResource(R.string.upd_installing)
                is Updater.State.Failed -> stringResource(R.string.upd_failed_title)
                else -> ""
            }
            BasicText(title, style = hstyle(42, FontWeight.Bold, c.text, tracking = -0.01f))
            Spacer(Modifier.height(14.px))
            val body = when (s) {
                is Updater.State.Available -> stringResource(R.string.upd_current, BuildConfig.VERSION_NAME)
                is Updater.State.Downloading -> stringResource(R.string.upd_downloading_hint)
                is Updater.State.NeedsPermission -> stringResource(R.string.upd_permission)
                is Updater.State.Installing -> stringResource(R.string.upd_installing_hint)
                is Updater.State.Failed -> stringResource(
                    when (s.reason) {
                        Updater.Reason.Offline -> R.string.upd_err_offline
                        Updater.Reason.NoRelease -> R.string.upd_err_offline
                        Updater.Reason.Download -> R.string.upd_err_download
                        Updater.Reason.WrongSignature -> R.string.upd_err_signature
                        Updater.Reason.NotNewer -> R.string.upd_err_not_newer
                        Updater.Reason.Install -> R.string.upd_err_install
                    }
                )
                else -> ""
            }
            BasicText(body, maxLines = 4, overflow = TextOverflow.Ellipsis, style = hstyle(23, FontWeight.Medium, c.sub))
            if (s is Updater.State.Available && s.release.notes.isNotBlank()) {
                Spacer(Modifier.height(22.px))
                BasicText(
                    s.release.notes,
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.px))
                        .background(c.pill)
                        .padding(26.px),
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(20, FontWeight.Medium, c.text),
                )
            }
            if (s is Updater.State.Downloading) {
                Spacer(Modifier.height(26.px))
                Box(Modifier.fillMaxWidth().height(14.px).clip(RoundedCornerShape(7.px)).background(c.track)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(s.percent / 100f).background(c.aqua))
                }
            }
            Spacer(Modifier.height(34.px))
            Row(horizontalArrangement = Arrangement.spacedBy(16.px)) {
                when (s) {
                    is Updater.State.Available -> {
                        PillButton(stringResource(R.string.upd_now), { updater.update(s.release) }, primary = true, icon = Icons.chevronDown, focusRequester = first)
                        PillButton(stringResource(R.string.upd_later), { updater.later() })
                        PillButton(stringResource(R.string.upd_skip), { updater.skip(s.release) })
                    }
                    is Updater.State.NeedsPermission -> {
                        PillButton(stringResource(R.string.upd_open_settings), { runCatching { context.startActivity(updater.permissionIntent()) } }, primary = true, icon = Icons.external, focusRequester = first)
                        PillButton(stringResource(R.string.upd_retry), { updater.update(s.release) }, icon = Icons.check)
                        PillButton(stringResource(R.string.upd_later), { updater.later() })
                    }
                    is Updater.State.Failed -> {
                        PillButton(stringResource(R.string.upd_close), { updater.dismiss() }, primary = true, focusRequester = first)
                    }
                    else -> {
                        PillButton(stringResource(R.string.upd_hide), { updater.later() }, primary = true, focusRequester = first)
                    }
                }
            }
        }
    }
}
