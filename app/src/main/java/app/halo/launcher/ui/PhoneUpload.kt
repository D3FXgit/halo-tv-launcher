package app.halo.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.R
import app.halo.launcher.UploadTarget
import app.halo.launcher.data.Icons
import app.halo.launcher.setup.Qr
import app.halo.launcher.setup.UploadServer
import kotlinx.coroutines.delay

/**
 * "Send from your phone": a QR code for the TV's little upload page (own wallpapers and app icons).
 * The page server runs only while this panel is open and Halo is in front.
 */
@Composable
fun PhoneUploadPanel(
    target: UploadTarget,
    apps: () -> List<Pair<String, String>>,
    onIcon: (key: String, saved: Boolean) -> Unit,
    onClose: (received: Int) -> Unit,
) {
    val context = LocalContext.current
    val c = LocalHalo.current.colors
    val preselect = (target as? UploadTarget.Icon)?.key
    val server = remember { UploadServer(context, apps, onIcon) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(server, lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> server.start(preselect)
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
    val st by server.state.collectAsStateWithLifecycle()
    val vpn = remember(st.url) { app.halo.launcher.setup.Lan.vpnActive(context) }
    val qr = remember(st.url) { st.url?.let { Qr.encode(it) } }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { close.requestFocus() }
    }
    BackHandler { onClose(st.received) }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF03050C).copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .width(1320.px)
                .glass(RoundedCornerShape(40.px), strong = true, fillAlpha = 0.92f)
                .padding(48.px),
            horizontalArrangement = Arrangement.spacedBy(48.px),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(380.px).clip(RoundedCornerShape(28.px)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val img = qr
                if (img != null && st.phase == UploadServer.Phase.Waiting) {
                    Image(img, st.url, Modifier.fillMaxSize().padding(14.px), filterQuality = FilterQuality.None)
                } else {
                    HaloIcon(Icons.wifi, 110.px, Color(0xFF0A0C12).copy(alpha = 0.5f), strokeWidth = 1.6f)
                }
            }
            Column(Modifier.width(780.px)) {
                BasicText(
                    when (target) {
                        UploadTarget.Pictures -> stringResource(R.string.upload_title_pictures)
                        is UploadTarget.Icon -> stringResource(R.string.upload_title_icon, target.label)
                    },
                    style = hstyle(40, FontWeight.Bold, c.text, tracking = -0.01f),
                )
                Spacer(Modifier.height(16.px))
                BasicText(stringResource(R.string.upload_scan), style = hstyle(23, FontWeight.Medium, c.sub))
                Spacer(Modifier.height(20.px))
                st.url?.takeIf { st.phase == UploadServer.Phase.Waiting }?.let { url ->
                    BasicText(
                        url.removePrefix("http://"),
                        maxLines = 2,
                        style = hstyle(22, FontWeight.Bold, c.text).copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(16.px))
                }
                BasicText(
                    when {
                        st.phase == UploadServer.Phase.NoNetwork -> stringResource(R.string.upload_no_network)
                        st.phase == UploadServer.Phase.Expired -> stringResource(R.string.upload_expired)
                        st.received > 0 -> stringResource(R.string.upload_received, st.received)
                        else -> stringResource(R.string.upload_waiting)
                    },
                    style = hstyle(22, FontWeight.SemiBold, if (st.received > 0) c.aqua else c.sub),
                )
                if (vpn && st.phase == UploadServer.Phase.Waiting && st.received == 0) {
                    Spacer(Modifier.height(14.px))
                    BasicText(stringResource(R.string.qr_vpn_hint), maxLines = 3, style = hstyle(19, FontWeight.Medium, Color(0xFFFFC46B)))
                }
                Spacer(Modifier.height(30.px))
                PillButton(
                    stringResource(R.string.done),
                    { onClose(st.received) },
                    primary = true,
                    icon = Icons.check,
                    focusRequester = close,
                    modifier = Modifier.width(320.px),
                )
            }
        }
    }
}
