package app.halo.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.halo.launcher.R
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.data.Icons
import app.halo.launcher.weather.Weather
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Full weather: now, next 12 hours, 7 days and official DWD warnings. */
@Composable
fun WeatherScreen(
    w: Weather?,
    onLocation: () -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val locale = appLocale()
    val german = stringResource(R.string.lang_code) == "de"
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .readabilityScrim()
            .padding(horizontal = 120.px, vertical = 56.px),
    ) {
        // ---- Header: now ---------------------------------------------------------
        // Big temperature with conditions beside it (not below), so the forecast fits on screen.
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (w?.fromDevice == true) {
                        HaloIcon(Icons.pin, 24.px, c.sub)
                        Spacer(Modifier.width(8.px))
                    }
                    BasicText(
                        w?.city ?: stringResource(R.string.weather_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = hstyle(28, FontWeight.SemiBold, c.sub),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        if (w != null) "${w.temperature}°" else "—",
                        style = hstyle(150, FontWeight.Light, c.text, tracking = -0.05f).copy(lineHeight = 160.tsp),
                    )
                    Spacer(Modifier.width(28.px))
                    if (w != null) HaloIcon(w.icon, 96.px, c.text, strokeWidth = 1.3f)
                    Spacer(Modifier.width(36.px))
                    Column(Modifier.weight(1f, fill = false)) {
                        BasicText(
                            if (w != null) stringResource(w.conditionsRes) else stringResource(R.string.weather_loading),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = hstyle(34, FontWeight.SemiBold, c.text),
                        )
                        val high = w?.todayMax
                        val low = w?.todayMin
                        if (high != null && low != null) {
                            BasicText(
                                stringResource(R.string.weather_high_low, high, low),
                                Modifier.padding(top = 4.px),
                                maxLines = 1,
                                style = hstyle(24, FontWeight.Medium, c.sub),
                            )
                        }
                    }
                }
                if (w != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(40.px)) {
                        w.feelsLike?.let { Stat(Icons.thermo, stringResource(R.string.weather_feels_like), "$it°") }
                        w.humidity?.let { Stat(Icons.drop, stringResource(R.string.weather_humidity), stringResource(R.string.percent, it)) }
                        w.windKmh?.let { Stat(Icons.wind, stringResource(R.string.weather_wind), stringResource(R.string.weather_wind_value, it)) }
                        w.rainChance?.let { Stat(Icons.rain, stringResource(R.string.weather_rain_today), stringResource(R.string.percent, it)) }
                    }
                }
            }
            Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.px)) {
                PillButton(stringResource(R.string.weather_change_location), onLocation, icon = Icons.pin)
                PillButton(stringResource(R.string.back), onBack, primary = true, icon = Icons.chevronLeft, focusRequester = first)
            }
        }

        // ---- Warnings (most important one; the count of the others) -------------------
        if (w != null && w.alerts.isNotEmpty()) {
            val a = w.alerts.first()
            val more = w.alerts.size - 1
            val tone = alertColor(a.severity)
            val shape = RoundedCornerShape(24.px)
            Spacer(Modifier.height(24.px))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.base.copy(alpha = 0.55f))
                    .background(tone.copy(alpha = 0.22f))
                    .border(1.5.px, tone.copy(alpha = 0.6f), shape)
                    .padding(horizontal = 26.px, vertical = 16.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.px),
            ) {
                HaloIcon(Icons.warning, 34.px, tone)
                Column(Modifier.weight(1f)) {
                    BasicText(
                        a.headline(german) + if (more > 0) "  ·  " + stringResource(R.string.weather_more_alerts, more) else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = hstyle(22, FontWeight.Bold, c.text),
                    )
                    BasicText(a.description(german), maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(18, FontWeight.Medium, c.sub))
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (w != null) {
            // ---- Next hours ---------------------------------------------------------
            Label(stringResource(R.string.weather_next_hours), Modifier.padding(start = 4.px))
            Spacer(Modifier.height(12.px))
            val anyRain = w.hourly.take(12).any { it.rainChance >= 10 }
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(32.px), fillAlpha = 0.5f)
                    .padding(horizontal = 28.px, vertical = 18.px),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                w.hourly.take(12).forEach { h ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(h.time.format(DateTimeFormatter.ofPattern("HH")), style = hstyle(19, FontWeight.SemiBold, c.sub))
                        Spacer(Modifier.height(8.px))
                        HaloIcon(Weather.iconFor(h.code, h.isDay), 40.px, c.text, strokeWidth = 1.6f)
                        Spacer(Modifier.height(8.px))
                        BasicText("${h.temp}°", style = hstyle(24, FontWeight.SemiBold, c.text))
                        if (anyRain) {
                            BasicText(if (h.rainChance >= 10) stringResource(R.string.percent, h.rainChance) else " ", style = hstyle(15, FontWeight.Medium, c.sub))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.px))

            // ---- 7 days ---------------------------------------------------------------
            Label(stringResource(R.string.weather_7_days), Modifier.padding(start = 4.px))
            Spacer(Modifier.height(12.px))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.px)) {
                val lo = w.daily.minOfOrNull { it.min } ?: 0
                val hi = w.daily.maxOfOrNull { it.max } ?: 1
                w.daily.forEachIndexed { i, d ->
                    Column(
                        Modifier
                            .weight(1f)
                            .glass(RoundedCornerShape(28.px), shadow = false, fillAlpha = 0.5f)
                            .padding(vertical = 16.px),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BasicText(
                            if (i == 0) stringResource(R.string.weather_today) else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = hstyle(20, FontWeight.SemiBold, c.text),
                        )
                        Spacer(Modifier.height(8.px))
                        HaloIcon(Weather.iconFor(d.code, true), 40.px, c.text, strokeWidth = 1.6f)
                        Spacer(Modifier.height(8.px))
                        // High and low on one line.
                        Row(verticalAlignment = Alignment.Bottom) {
                            BasicText("${d.max}°", style = hstyle(26, FontWeight.SemiBold, c.text))
                            BasicText("  ${d.min}°", style = hstyle(20, FontWeight.Medium, c.sub))
                        }
                        Spacer(Modifier.height(10.px))
                        // Temperature range bar relative to the week.
                        Box(
                            Modifier
                                .width(80.px)
                                .height(6.px)
                                .clip(RoundedCornerShape(3.px))
                                .background(c.track)
                        ) {
                            val span = (hi - lo).coerceAtLeast(1).toFloat()
                            Box(
                                Modifier
                                    .padding(start = (80f * (d.min - lo) / span).px)
                                    .width((80f * (d.max - d.min) / span).coerceAtLeast(6f).px)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.px))
                                    .background(c.text)
                            )
                        }
                        BasicText(
                            if (d.rainChance >= 10) stringResource(R.string.weather_rain_chance, d.rainChance) else " ",
                            Modifier.padding(top = 6.px),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = hstyle(15, FontWeight.Medium, c.sub),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.px))
        BasicText(
            stringResource(R.string.weather_credit),
            Modifier.padding(start = 4.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(14, FontWeight.Medium, c.sub),
        )
    }
}

@Composable
private fun Stat(icon: app.halo.launcher.data.IconSpec, label: String, value: String) {
    val c = LocalHalo.current.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.px)) {
        HaloIcon(icon, 26.px, c.sub)
        Column {
            BasicText(label, style = hstyle(16, FontWeight.Medium, c.sub))
            BasicText(value, style = hstyle(22, FontWeight.SemiBold, c.text))
        }
    }
}
