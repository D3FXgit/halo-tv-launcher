package app.halo.launcher.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.halo.launcher.BuildConfig
import app.halo.launcher.R
import app.halo.launcher.data.Icons
import app.halo.launcher.weather.Weather
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlin.math.cos
import kotlin.math.roundToInt

/** The optional home-screen widgets, in the order they are offered in Settings. */
object HomeWidgets {
    const val FORECAST = "forecast"
    const val CALENDAR = "calendar"
    const val SUN_MOON = "sunmoon"
    const val WORLD_CLOCK = "worldclock"
    const val COUNTDOWN = "countdown"
    const val DEVICE = "device"
    val ALL = listOf(FORECAST, CALENDAR, SUN_MOON, WORLD_CLOCK, COUNTDOWN, DEVICE)

    /** Widget sizes offered in Settings (text and card size). */
    val SCALES = listOf(0.85f, 1f, 1.2f, 1.4f)

    /** Time zones for the world clock (Settings cycles through them). */
    val ZONES = listOf(
        "America/New_York", "America/Los_Angeles", "America/Sao_Paulo", "Europe/London", "Europe/Berlin",
        "Europe/Istanbul", "Asia/Dubai", "Asia/Kolkata", "Asia/Bangkok", "Asia/Shanghai", "Asia/Tokyo", "Australia/Sydney",
    )

    /** "America/New_York" → "New York". */
    fun city(zone: String) = zone.substringAfterLast('/').replace('_', ' ')

    fun titleRes(id: String): Int = when (id) {
        FORECAST -> R.string.widget_forecast
        CALENDAR -> R.string.widget_calendar
        SUN_MOON -> R.string.widget_sun_moon
        WORLD_CLOCK -> R.string.widget_world_clock
        COUNTDOWN -> R.string.widget_countdown
        else -> R.string.widget_device
    }
}

/** Sizes scaled by the widget size setting. */
private fun Int.sc(scale: Float): Int = (this * scale).roundToInt()

/** A widget's glass card: as tall as the clock, [width] wide (both follow the widget size). */
@Composable
fun WidgetCard(scale: Float, height: Dp, width: Int = 300, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(width.sc(scale).px)
            .height(height)
            .glass(RoundedCornerShape(36.px))
            .padding(24.sc(scale).px),
        verticalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

@Composable
private fun WidgetTitle(text: String, scale: Float) {
    val c = LocalHalo.current.colors
    BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(17.sc(scale), FontWeight.SemiBold, c.sub))
}

@Composable
fun HomeWidget(id: String, scale: Float, height: Dp, weather: Weather?, clock24h: Boolean, worldZone: String) {
    when (id) {
        HomeWidgets.FORECAST -> ForecastWidget(weather, scale, height)
        HomeWidgets.CALENDAR -> CalendarWidget(scale, height)
        HomeWidgets.SUN_MOON -> SunMoonWidget(weather, scale, height, clock24h)
        HomeWidgets.WORLD_CLOCK -> WorldClockWidget(worldZone, scale, height, clock24h)
        HomeWidgets.COUNTDOWN -> CountdownWidget(scale, height)
        HomeWidgets.DEVICE -> DeviceWidget(scale, height)
    }
}

// ---- Forecast ---------------------------------------------------------------------------------

@Composable
private fun ForecastWidget(w: Weather?, scale: Float, height: Dp) {
    val c = LocalHalo.current.colors
    val locale = appLocale()
    WidgetCard(scale, height) {
        WidgetTitle(stringResource(R.string.widget_forecast), scale)
        val days = w?.daily.orEmpty().drop(1).take(3)
        if (days.isEmpty()) {
            BasicText(stringResource(R.string.weather_finding), style = hstyle(17.sc(scale), FontWeight.Medium, c.sub))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.sc(scale).px)) {
            days.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        Modifier.width(72.sc(scale).px),
                        style = hstyle(23.sc(scale), FontWeight.SemiBold, c.text),
                    )
                    HaloIcon(Weather.iconFor(d.code, true), 34.sc(scale).px, c.text, strokeWidth = 1.7f)
                    Spacer(Modifier.weight(1f))
                    BasicText("${d.max}°", style = hstyle(23.sc(scale), FontWeight.Bold, c.text))
                    Spacer(Modifier.width(8.sc(scale).px))
                    BasicText("${d.min}°", style = hstyle(23.sc(scale), FontWeight.Medium, c.sub))
                }
            }
        }
    }
}

// ---- Calendar ---------------------------------------------------------------------------------

@Composable
private fun CalendarWidget(scale: Float, height: Dp) {
    val c = LocalHalo.current.colors
    val locale = appLocale()
    val today = rememberNow().toLocalDate()
    val week = WeekFields.of(locale)
    val first = today.withDayOfMonth(1)
    // Days before the 1st in the first week row.
    val lead = Math.floorMod(first.dayOfWeek.value - week.firstDayOfWeek.value, 7)
    val days = today.lengthOfMonth()
    val weekNo = today.get(WeekFields.ISO.weekOfWeekBasedYear())
    WidgetCard(scale, height, width = 320) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) },
                Modifier.weight(1f),
                maxLines = 1,
                style = hstyle(19.sc(scale), FontWeight.Bold, c.text),
            )
            BasicText(stringResource(R.string.widget_week, weekNo), style = hstyle(15.sc(scale), FontWeight.SemiBold, c.sub))
        }
        val cell = 34.sc(scale)
        Column {
            Row {
                for (i in 0 until 7) {
                    val dow = week.firstDayOfWeek.plus(i.toLong())
                    BasicText(
                        dow.getDisplayName(TextStyle.NARROW, locale),
                        Modifier.width(cell.px),
                        style = hstyle(13.sc(scale), FontWeight.SemiBold, c.sub).copy(textAlign = TextAlign.Center),
                    )
                }
            }
            val rows = (lead + days + 6) / 7
            for (r in 0 until rows) {
                Row {
                    for (col in 0 until 7) {
                        val n = r * 7 + col - lead + 1
                        val isToday = n == today.dayOfMonth
                        Box(Modifier.size(cell.px, (cell * 0.72f).roundToInt().px), contentAlignment = Alignment.Center) {
                            if (isToday) {
                                Box(
                                    Modifier
                                        .size((cell * 0.72f).roundToInt().px)
                                        .clip(CircleShape)
                                        .background(c.aqua)
                                )
                            }
                            if (n in 1..days) {
                                BasicText(
                                    "$n",
                                    style = hstyle(13.sc(scale), if (isToday) FontWeight.Bold else FontWeight.Medium, if (isToday) Color.White else c.text)
                                        .copy(textAlign = TextAlign.Center),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Sun & moon -------------------------------------------------------------------------------

/** Moon phase 0..1 (0 = new moon, 0.5 = full moon), from a known new moon and the synodic month. */
fun moonPhase(at: ZonedDateTime = ZonedDateTime.now()): Double {
    val ref = ZonedDateTime.of(2000, 1, 6, 18, 14, 0, 0, ZoneId.of("UTC"))
    val days = Duration.between(ref, at).toMinutes() / 1440.0
    val p = (days / 29.530588853) % 1.0
    return if (p < 0) p + 1 else p
}

@Composable
private fun SunMoonWidget(w: Weather?, scale: Float, height: Dp, clock24h: Boolean) {
    val c = LocalHalo.current.colors
    val fmt = remember(clock24h) { DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "h:mm a") }
    val phase = remember(rememberNow().toLocalDate()) { moonPhase() }
    val phaseName = stringResource(
        when (((phase * 8) + 0.5).toInt() % 8) {
            0 -> R.string.moon_new
            1 -> R.string.moon_waxing_crescent
            2 -> R.string.moon_first_quarter
            3 -> R.string.moon_waxing_gibbous
            4 -> R.string.moon_full
            5 -> R.string.moon_waning_gibbous
            6 -> R.string.moon_last_quarter
            else -> R.string.moon_waning_crescent
        }
    )
    WidgetCard(scale, height) {
        WidgetTitle(stringResource(R.string.widget_sun_moon), scale)
        Column(verticalArrangement = Arrangement.spacedBy(8.sc(scale).px)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(Icons.sun, 24.sc(scale).px, Color(0xFFFFC46B), strokeWidth = 1.8f)
                Spacer(Modifier.width(10.sc(scale).px))
                BasicText(
                    "${w?.sunrise?.format(fmt) ?: "–"}  ↓ ${w?.sunset?.format(fmt) ?: "–"}",
                    style = hstyle(19.sc(scale), FontWeight.SemiBold, c.text),
                )
            }
            val rise = w?.sunrise
            val set = w?.sunset
            if (rise != null && set != null) {
                val mins = Duration.between(rise, set).toMinutes()
                BasicText(
                    stringResource(R.string.widget_daylight, mins / 60, mins % 60),
                    style = hstyle(16.sc(scale), FontWeight.Medium, c.sub),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonIcon(phase, 44.sc(scale))
            Spacer(Modifier.width(12.sc(scale).px))
            BasicText(phaseName, maxLines = 2, style = hstyle(18.sc(scale), FontWeight.SemiBold, c.text))
        }
    }
}

/** The moon with its lit part for [phase] (0 = new, 0.5 = full). */
@Composable
private fun MoonIcon(phase: Double, sizePx: Int) {
    Canvas(Modifier.size(sizePx.px)) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF2A2F3A), r, center)
        // Lit part: half disc on the lit side plus an ellipse whose width follows the phase.
        val waxing = phase < 0.5
        val k = cos(phase * 2 * Math.PI).toFloat() // 1 new … -1 full
        val path = androidx.compose.ui.graphics.Path().apply {
            val steps = 48
            for (i in 0..steps) {
                val a = Math.PI * i / steps - Math.PI / 2
                val x = (r * kotlin.math.cos(a)).toFloat() * (if (waxing) 1f else -1f)
                val y = (r * kotlin.math.sin(a)).toFloat()
                if (i == 0) moveTo(center.x + x, center.y + y) else lineTo(center.x + x, center.y + y)
            }
            for (i in steps downTo 0) {
                val a = Math.PI * i / steps - Math.PI / 2
                val x = (r * kotlin.math.cos(a)).toFloat() * k * (if (waxing) 1f else -1f)
                val y = (r * kotlin.math.sin(a)).toFloat()
                lineTo(center.x + x, center.y + y)
            }
            close()
        }
        clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }) {
            drawPath(path, Color(0xFFF4EBD0))
        }
    }
}

// ---- World clock ------------------------------------------------------------------------------

@Composable
private fun WorldClockWidget(zone: String, scale: Float, height: Dp, clock24h: Boolean) {
    val c = LocalHalo.current.colors
    val locale = appLocale()
    val localNow = rememberNow()
    val zid = remember(zone) { runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC")) }
    val there = ZonedDateTime.now(zid)
    val here = ZonedDateTime.now()
    val diffMin = (there.offset.totalSeconds - here.offset.totalSeconds) / 60
    val dayDiff = ChronoUnit.DAYS.between(localNow.toLocalDate(), there.toLocalDate())
    val fmt = remember(clock24h, locale) { DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "h:mm", locale) }
    WidgetCard(scale, height) {
        WidgetTitle(HomeWidgets.city(zone), scale)
        BasicText(there.format(fmt), style = hstyle(64.sc(scale), FontWeight.Light, c.text, tracking = -0.04f).copy(lineHeight = 64.sc(scale).tsp))
        val offset = (if (diffMin >= 0) "+" else "−") + (kotlin.math.abs(diffMin) / 60) +
            (if (kotlin.math.abs(diffMin) % 60 != 0) ":%02d".format(kotlin.math.abs(diffMin) % 60) else "") + " h"
        BasicText(
            when {
                dayDiff > 0 -> stringResource(R.string.widget_tomorrow, offset)
                dayDiff < 0 -> stringResource(R.string.widget_yesterday, offset)
                else -> stringResource(R.string.widget_today, offset)
            },
            style = hstyle(17.sc(scale), FontWeight.Medium, c.sub),
        )
    }
}

// ---- Countdown --------------------------------------------------------------------------------

/** Easter Sunday (anonymous Gregorian algorithm). */
private fun easter(year: Int): LocalDate {
    val a = year % 19; val b = year / 100; val c = year % 100; val d = b / 4; val e = b % 4
    val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, month, day)
}

/** The next of: Christmas Eve, New Year, Easter, Halloween (today counts). */
private fun nextEvent(today: LocalDate): Pair<Int, LocalDate> {
    val y = today.year
    val candidates = listOf(
        R.string.event_christmas to LocalDate.of(y, 12, 24),
        R.string.event_new_year to LocalDate.of(y + 1, 1, 1),
        R.string.event_easter to easter(y),
        R.string.event_easter to easter(y + 1),
        R.string.event_halloween to LocalDate.of(y, 10, 31),
    )
    return candidates.filter { !it.second.isBefore(today) }.minBy { it.second }
}

@Composable
private fun CountdownWidget(scale: Float, height: Dp) {
    val c = LocalHalo.current.colors
    val today = rememberNow().toLocalDate()
    val (name, date) = remember(today) { nextEvent(today) }
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    WidgetCard(scale, height) {
        WidgetTitle(stringResource(name), scale)
        BasicText(
            if (days == 0) "🎉" else "$days",
            style = hstyle(72.sc(scale), FontWeight.Light, c.text, tracking = -0.04f).copy(lineHeight = 72.sc(scale).tsp),
        )
        BasicText(
            if (days == 0) stringResource(R.string.widget_countdown_today) else pluralResource(R.plurals.widget_days_left, days, days),
            style = hstyle(17.sc(scale), FontWeight.Medium, c.sub),
        )
    }
}

// ---- Device status ----------------------------------------------------------------------------

@Composable
private fun DeviceWidget(scale: Float, height: Dp) {
    val c = LocalHalo.current.colors
    val context = LocalContext.current
    val status by produceState(Triple("", false, ""), context) {
        while (true) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val all = cm?.allNetworks.orEmpty().mapNotNull { cm?.getNetworkCapabilities(it) }
            val vpn = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val link = when {
                all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> "Ethernet"
                all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "Wi‑Fi"
                else -> ""
            }
            val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val free = fs?.let { "%.1f".format(it.availableBytes / 1e9) } ?: "?"
            val total = fs?.let { "%.0f".format(it.totalBytes / 1e9) } ?: "?"
            value = Triple(link, vpn, "$free|$total")
            delay(30_000)
        }
    }
    WidgetCard(scale, height) {
        WidgetTitle(stringResource(R.string.widget_device), scale)
        Column(verticalArrangement = Arrangement.spacedBy(10.sc(scale).px)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(Icons.wifi, 22.sc(scale).px, c.text, strokeWidth = 1.8f)
                Spacer(Modifier.width(10.sc(scale).px))
                BasicText(
                    status.first.ifEmpty { stringResource(R.string.widget_offline) },
                    style = hstyle(19.sc(scale), FontWeight.SemiBold, c.text),
                )
                if (status.second) {
                    Spacer(Modifier.width(10.sc(scale).px))
                    BasicText("VPN", style = hstyle(15.sc(scale), FontWeight.Bold, c.aqua))
                }
            }
            val (free, total) = status.third.split('|').let { it.getOrElse(0) { "?" } to it.getOrElse(1) { "?" } }
            BasicText(stringResource(R.string.widget_storage, free, total), style = hstyle(17.sc(scale), FontWeight.Medium, c.sub))
        }
        BasicText("Halo ${BuildConfig.VERSION_NAME}", style = hstyle(15.sc(scale), FontWeight.Medium, c.sub))
    }
}
