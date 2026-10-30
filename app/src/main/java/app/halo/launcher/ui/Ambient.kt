package app.halo.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.halo.launcher.R
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.media.NowPlaying
import app.halo.launcher.weather.Weather
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient mode: the backdrop alone, slowly drifting, with a big clock that
 * wanders a few pixels every minute (burn-in protection).
 */
@Composable
fun AmbientScreen(clock24h: Boolean, weather: Weather?, nowPlaying: NowPlaying?) {
    val c = LocalHalo.current.colors
    val now = rememberNow()
    // Date in the interface language ("Mittwoch, 29. Oktober" / "Wednesday 29 October").
    val locale = appLocale()
    val datePattern = stringResource(R.string.date_long_pattern)
    val timeFmt = remember(clock24h, locale) { DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "h:mm", locale) }
    val dateFmt = remember(locale, datePattern) { DateTimeFormatter.ofPattern(datePattern, locale) }
    val time = now.format(timeFmt)
    val date = now.format(dateFmt)

    // Burn-in drift: a slow Lissajous path, one step per minute.
    val m = now.hour * 60 + now.minute
    val dx by animateFloatAsState((sin(m * 0.7) * 40).toFloat(), tween(4000), label = "dx")
    val dy by animateFloatAsState((cos(m * 0.43) * 26).toFloat(), tween(4000), label = "dy")

    Box(Modifier.fillMaxSize()) {
        val control = remember { BackdropControl() }
        LaunchedEffect(m) { control.parallax = (sin(m * 0.05) * 0.8).toFloat() }
        Backdrop(Modifier.fillMaxSize(), control = control, scrim = 0.6f, drama = 2.2f)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 120.px, bottom = 120.px)
                .graphicsLayer {
                    translationX = dx * Ui.k * density
                    translationY = dy * Ui.k * density
                },
        ) {
            RollingText(
                time,
                hstyle(240, FontWeight.Light, c.text, tracking = -0.05f).copy(
                    lineHeight = (240 * 0.9f).tsp,
                    fontFeatureSettings = "tnum",
                ),
            )
            Row(Modifier.padding(start = 10.px, top = 16.px), horizontalArrangement = Arrangement.spacedBy(18.px)) {
                BasicText(date, style = hstyle(32, FontWeight.Medium, c.sub))
                if (weather != null) {
                    BasicText(
                        "·  ${weather.temperature}°  ${stringResource(weather.conditionsRes)}",
                        maxLines = 1,
                        style = hstyle(32, FontWeight.Medium, c.sub),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = nowPlaying != null,
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(800)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 120.px, bottom = 120.px),
        ) {
            val np = nowPlaying ?: return@AnimatedVisibility
            Row(
                Modifier
                    .width(520.px)
                    .glass(RoundedCornerShape(32.px))
                    .padding(20.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.px),
            ) {
                if (np.art != null) {
                    Image(
                        np.art, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(104.px).clip(RoundedCornerShape(18.px)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    BasicText(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(24, FontWeight.SemiBold, c.text))
                    BasicText(np.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(19, FontWeight.Medium, c.sub))
                }
            }
        }
    }
}
