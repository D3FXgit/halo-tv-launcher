package app.halo.launcher.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import app.halo.launcher.R
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import app.halo.launcher.i18n.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime

@Immutable
data class Weather(
    val city: String,
    val temperature: Int,
    val code: Int,
    val isDay: Boolean,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    /** True when the place comes from the device location rather than a typed city. */
    val fromDevice: Boolean = false,
    val feelsLike: Int? = null,
    val humidity: Int? = null,
    val windKmh: Int? = null,
    val todayMax: Int? = null,
    val todayMin: Int? = null,
    val rainChance: Int? = null,
    val hourly: List<Hour> = emptyList(),
    val daily: List<Day> = emptyList(),
    /** Official DWD warnings (via Bright Sky). */
    val alerts: List<Alert> = emptyList(),
) {
    @Immutable
    data class Hour(val time: LocalDateTime, val temp: Int, val code: Int, val rainChance: Int, val isDay: Boolean)

    @Immutable
    data class Day(val date: java.time.LocalDate, val max: Int, val min: Int, val code: Int, val rainChance: Int)

    /**
     * An official DWD warning, in German and (when DWD provides it) English, so it follows the
     * interface language even after a language switch.
     * severity: minor, moderate, severe, extreme (DWD yellow, orange, red, violet).
     */
    @Immutable
    data class Alert(
        val headlineDe: String?,
        val headlineEn: String?,
        val descriptionDe: String?,
        val descriptionEn: String?,
        val severity: String,
        val expires: String?,
    ) {
        fun headline(german: Boolean): String =
            (if (german) headlineDe ?: headlineEn else headlineEn ?: headlineDe).orEmpty()

        fun description(german: Boolean): String =
            (if (german) descriptionDe ?: descriptionEn else descriptionEn ?: descriptionDe).orEmpty()
    }

    /** The current conditions ("Partly cloudy") as a string resource. */
    @get:StringRes
    val conditionsRes: Int get() = describeRes(code)
    val icon: IconSpec get() = iconFor(code, isDay)

    companion object {
        /** Weather code (WMO, as used by Open-Meteo) → condition text. */
        @StringRes
        fun describeRes(code: Int): Int = when (code) {
            0 -> R.string.weather_clear
            1 -> R.string.weather_mostly_clear
            2 -> R.string.weather_partly_cloudy
            3 -> R.string.weather_overcast
            45, 48 -> R.string.weather_fog
            51, 53, 55, 56, 57 -> R.string.weather_drizzle
            61, 63, 65, 66, 67 -> R.string.weather_rain
            71, 73, 75, 77 -> R.string.weather_snow
            80, 81, 82 -> R.string.weather_showers
            85, 86 -> R.string.weather_snow_showers
            95, 96, 99 -> R.string.weather_thunderstorm
            else -> R.string.weather_unknown
        }

        /** Condition text in Halo's interface language (non-UI code; composables use stringResource). */
        fun describe(context: Context, code: Int): String = Lang.string(context, describeRes(code))

        fun iconFor(code: Int, day: Boolean): IconSpec = when (code) {
            0, 1 -> if (day) Icons.clear else Icons.moon
            2 -> Icons.partly
            3 -> Icons.cloud
            45, 48 -> Icons.fog
            in 51..67, 80, 81, 82 -> Icons.rain
            in 71..77, 85, 86 -> Icons.snow
            95, 96, 99 -> Icons.storm
            else -> Icons.cloud
        }
    }
}

/**
 * Weather from Open-Meteo (free, no API key). Refreshes every 30 minutes.
 *
 * Location, in order of preference:
 *  1. the TV's own location (Android location services, needs the coarse-location permission),
 *  2. the location of the internet connection (ipwho.is),
 *  3. a city typed in by the user, when device location is turned off.
 */
class WeatherRepository(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow<Weather?>(null)
    val state: StateFlow<Weather?> = _state

    private data class Config(val enabled: Boolean, val auto: Boolean, val city: String)
    private data class Place(val lat: Double, val lon: Double, val name: String)

    private var config: Config? = null
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun configure(enabled: Boolean, auto: Boolean, city: String) {
        val c = Config(enabled, auto || city.isBlank(), city.trim())
        if (c == config && job?.isActive == true) return
        config = c
        restart()
    }

    /** Call after the location permission was granted, to use the better position at once. */
    fun refresh() = restart()

    private fun restart() {
        val c = config ?: return
        job?.cancel()
        if (!c.enabled) {
            _state.value = null
            return
        }
        job = scope.launch(Dispatchers.IO) {
            while (true) {
                val ok = runCatching { _state.value = fetch(c) }.isSuccess
                delay(if (ok) 30 * 60_000L else 2 * 60_000L)
            }
        }
    }

    private fun get(url: String): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.parseToJsonElement(body).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** Newest last-known fix from any enabled provider, if the permission was granted. */
    @SuppressLint("MissingPermission")
    private fun systemLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        // Place names in the interface language ("München" / "Munich").
        val a = Geocoder(app, Lang.locale(app)).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()

    private fun ipPlace(): Place {
        val o = get("https://ipwho.is/?fields=success,city,latitude,longitude")
        require(o.str("success") != "false") { "ip lookup failed" }
        return Place(o.str("latitude")!!.toDouble(), o.str("longitude")!!.toDouble(), o.str("city") ?: "")
    }

    private fun cityPlace(city: String): Place {
        val q = URLEncoder.encode(city, "UTF-8")
        val geo = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=${Lang.effective(app)}&format=json")
        val p = geo["results"]!!.jsonArray.first().jsonObject
        return Place(p.str("latitude")!!.toDouble(), p.str("longitude")!!.toDouble(), p.str("name") ?: city)
    }

    private fun locate(c: Config): Place {
        if (!c.auto) return cityPlace(c.city)
        val loc = systemLocation()
        if (loc != null) {
            val name = placeName(loc.latitude, loc.longitude).orEmpty()
            return Place(loc.latitude, loc.longitude, name)
        }
        return runCatching { ipPlace() }.getOrElse {
            if (c.city.isNotBlank()) cityPlace(c.city) else throw it
        }
    }

    private fun JsonObject.arr(key: String) = this[key]?.jsonArray
    private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    private fun kotlinx.serialization.json.JsonArray.numAt(i: Int): Double? =
        getOrNull(i)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

    private fun fetch(c: Config): Weather {
        val place = locate(c)
        val f = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=13" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                "&forecast_days=7&timezone=auto"
        )
        val cur = f["current"]!!.jsonObject
        val daily = f["daily"]?.jsonObject
        fun dayTime(key: String) = runCatching {
            LocalDateTime.parse(daily!![key]!!.jsonArray.first().jsonPrimitive.content)
        }.getOrNull()

        val hours = runCatching {
            val h = f["hourly"]!!.jsonObject
            val times = h.arr("time")!!
            (1 until times.size).map { i ->
                Weather.Hour(
                    time = LocalDateTime.parse(times[i].jsonPrimitive.content),
                    temp = Math.round(h.arr("temperature_2m")!!.numAt(i) ?: 0.0).toInt(),
                    code = (h.arr("weather_code")!!.numAt(i) ?: 0.0).toInt(),
                    rainChance = (h.arr("precipitation_probability")?.numAt(i) ?: 0.0).toInt(),
                    isDay = (h.arr("is_day")?.numAt(i) ?: 1.0) > 0.5,
                )
            }
        }.getOrDefault(emptyList())

        val days = runCatching {
            val d = daily!!
            val times = d.arr("time")!!
            times.indices.map { i ->
                Weather.Day(
                    date = java.time.LocalDate.parse(times[i].jsonPrimitive.content),
                    max = Math.round(d.arr("temperature_2m_max")!!.numAt(i) ?: 0.0).toInt(),
                    min = Math.round(d.arr("temperature_2m_min")!!.numAt(i) ?: 0.0).toInt(),
                    code = (d.arr("weather_code")!!.numAt(i) ?: 0.0).toInt(),
                    rainChance = (d.arr("precipitation_probability_max")?.numAt(i) ?: 0.0).toInt(),
                )
            }
        }.getOrDefault(emptyList())

        return Weather(
            city = place.name.ifBlank { Lang.string(app, R.string.weather_here) },
            temperature = Math.round(cur.num("temperature_2m") ?: 0.0).toInt(),
            code = (cur.num("weather_code") ?: 0.0).toInt(),
            isDay = cur.num("is_day") == 1.0,
            sunrise = dayTime("sunrise"),
            sunset = dayTime("sunset"),
            fromDevice = c.auto,
            feelsLike = cur.num("apparent_temperature")?.let { Math.round(it).toInt() },
            humidity = cur.num("relative_humidity_2m")?.toInt(),
            windKmh = cur.num("wind_speed_10m")?.let { Math.round(it).toInt() },
            todayMax = days.firstOrNull()?.max,
            todayMin = days.firstOrNull()?.min,
            rainChance = days.firstOrNull()?.rainChance,
            hourly = hours,
            daily = days,
            // DWD warnings only exist for Germany: don't send other locations to Bright Sky.
            alerts = if (place.lat in 47.2..55.1 && place.lon in 5.8..15.1) runCatching { alerts(place) }.getOrDefault(emptyList()) else emptyList(),
        )
    }

    private var warnCell: Pair<Place, String>? = null

    /** Official warnings of the Deutscher Wetterdienst for the place (Germany only). */
    private fun alerts(place: Place): List<Weather.Alert> {
        val cell = warnCell?.takeIf { it.first == place }?.second
        val url = if (cell != null) "https://api.brightsky.dev/alerts?warn_cell_id=$cell"
        else "https://api.brightsky.dev/alerts?lat=${place.lat}&lon=${place.lon}"
        val o = get(url)
        o["location"]?.jsonObject?.str("warn_cell_id")?.let { warnCell = place to it }
        return o.arr("alerts").orEmpty().mapNotNull { e ->
            val a = e.jsonObject
            if (a.str("status") == "test") return@mapNotNull null
            val headlineDe = a.str("headline_de")?.takeIf { it.isNotBlank() }
            val headlineEn = a.str("headline_en")?.takeIf { it.isNotBlank() }
            if (headlineDe == null && headlineEn == null) return@mapNotNull null
            Weather.Alert(
                headlineDe = headlineDe,
                headlineEn = headlineEn,
                descriptionDe = a.str("description_de")?.takeIf { it.isNotBlank() },
                descriptionEn = a.str("description_en")?.takeIf { it.isNotBlank() },
                severity = a.str("severity") ?: "minor",
                expires = a.str("expires"),
            )
        }
    }
}
