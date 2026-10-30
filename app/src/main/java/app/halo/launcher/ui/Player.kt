package app.halo.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.halo.launcher.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.palette.graphics.Palette
import app.halo.launcher.data.Icons
import app.halo.launcher.media.NowPlaying
import kotlinx.coroutines.delay

/**
 * Full-screen "Now playing" for whatever app is playing (Spotify, YouTube Music, Plex …).
 * Controls the app's own media session: play/pause, previous/next, ±10 s.
 */
@Composable
fun PlayerScreen(
    np: NowPlaying?,
    mediaAccess: Boolean,
    canSeek: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenApp: () -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val artColor = rememberArtColor(np?.art) ?: Color(0xFF5B6CF0)
    val glow by animateColorAsState(artColor, tween(900), label = "playerGlow")

    Box(Modifier.fillMaxSize()) {
        // Colour wash taken from the artwork (no live blur: cheap on TV GPUs).
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = if (c.isDark) 0.55f else 0.25f))
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.75f), glow.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(size.width * 0.25f, size.height * 0.45f),
                    radius = size.width * 0.7f,
                )
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.65f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (c.isDark) 0.5f else 0.15f),
                )
            )
        }

        if (np == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HaloIcon(Icons.playFilled, 96.px, c.text.copy(alpha = 0.8f))
                Spacer(Modifier.height(28.px))
                BasicText(
                    stringResource(if (mediaAccess) R.string.nothing_playing else R.string.player_access_off),
                    maxLines = 1,
                    style = hstyle(52, FontWeight.Bold, c.text, tracking = -0.02f),
                )
                Spacer(Modifier.height(12.px))
                BasicText(
                    stringResource(if (mediaAccess) R.string.player_nothing_hint else R.string.player_access_hint),
                    maxLines = 2,
                    style = hstyle(24, FontWeight.Medium, c.sub),
                )
                Spacer(Modifier.height(40.px))
                val f = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(80); runCatching { f.requestFocus() } }
                PillButton(stringResource(R.string.player_back_home), onBack, primary = true, icon = Icons.chevronLeft, focusRequester = f)
            }
            return@Box
        }

        // Smooth clock between session updates.
        var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(np.playing, np.positionAt) {
            tick = SystemClock.elapsedRealtime()
            while (np.playing) {
                delay(500)
                tick = SystemClock.elapsedRealtime()
            }
        }
        val pos = np.positionAtMs(tick)
        val progress = np.progressAt(tick)

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 160.px, vertical = 120.px),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(96.px),
        ) {
            // Artwork
            Box(
                Modifier
                    .size(600.px)
                    .shadow(48.px, RoundedCornerShape(40.px), ambientColor = glow, spotColor = Color.Black)
                    .clip(RoundedCornerShape(40.px))
                    .background(Brush.linearGradient(listOf(glow, glow.copy(alpha = 0.5f)))),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = np.art,
                    transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) },
                    label = "playerArt",
                ) { art ->
                    if (art != null) {
                        Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            HaloIcon(Icons.playFilled, 140.px, Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }

            // Info + controls
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Label(stringResource(R.string.now_playing_app, np.appLabel).uppercase(appLocale()), size = 20, color = c.sub)
                Spacer(Modifier.height(18.px))
                BasicText(
                    np.title.ifBlank { np.appLabel },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(68, FontWeight.Bold, c.text, tracking = -0.03f).copy(lineHeight = (68 * 1.08f).tsp),
                )
                if (np.artist.isNotBlank()) {
                    Spacer(Modifier.height(10.px))
                    BasicText(
                        np.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = hstyle(32, FontWeight.Medium, c.sub),
                    )
                }
                Spacer(Modifier.height(56.px))

                // Progress
                if (np.durationMs > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.px)
                            .clip(RoundedCornerShape(4.px))
                            .background(c.track)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.px))
                                .background(c.text)
                        )
                    }
                    Spacer(Modifier.height(12.px))
                    Row(Modifier.fillMaxWidth()) {
                        val t = hstyle(20, FontWeight.SemiBold, c.sub).copy(fontFeatureSettings = "tnum")
                        BasicText(formatTime(pos), style = t)
                        Spacer(Modifier.weight(1f))
                        BasicText("-" + formatTime(np.durationMs - pos), style = t)
                    }
                    Spacer(Modifier.height(44.px))
                }

                // Transport
                val playFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(80); runCatching { playFocus.requestFocus() } }
                Row(
                    Modifier.focusGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.px),
                ) {
                    RoundButton(Icons.back10, { onSeek(-10_000) }, size = 76, iconSize = 30, enabled = canSeek)
                    RoundButton(Icons.prev, onPrevious, size = 76, iconSize = 30)
                    PlayButton(np.playing, onPlayPause, playFocus)
                    RoundButton(Icons.next, onNext, size = 76, iconSize = 30)
                    RoundButton(Icons.fwd10, { onSeek(10_000) }, size = 76, iconSize = 30, enabled = canSeek)
                    Spacer(Modifier.width(24.px))
                    PillButton(
                        stringResource(R.string.player_open_app, np.appLabel),
                        onOpenApp,
                        modifier = Modifier.widthIn(max = 380.px),
                        icon = Icons.external,
                    )
                }
            }
        }

        BasicText(
            stringResource(R.string.player_back_hint),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 160.px, bottom = 56.px),
            style = hstyle(18, FontWeight.Medium, c.sub),
        )
    }
}

/** The large, always-filled play/pause button. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, focusRequester: FocusRequester) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Box(
        Modifier
            .size(116.px)
            .control(st, onClick, focusRequester = focusRequester, focusScale = 1.1f)
            .shadow(if (st.focused) 30.px else 12.px, CircleShape)
            .clip(CircleShape)
            .background(c.cta),
        contentAlignment = Alignment.Center,
    ) {
        HaloIcon(if (playing) Icons.pause else Icons.playFilled, 48.px, c.ctaText)
    }
}

/** Dominant colour of the artwork, for the background wash. */
@Composable
private fun rememberArtColor(art: ImageBitmap?): Color? =
    androidx.compose.runtime.produceState<Color?>(null, art) {
        val a = art ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val b = a.asAndroidBitmap().let {
                    if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it
                }
                val p = Palette.from(b).maximumColorCount(12).generate()
                val sw = p.vibrantSwatch ?: p.dominantSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch
                sw?.let { Color(it.rgb) }
            }.getOrNull()
        }
    }.value

private fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
