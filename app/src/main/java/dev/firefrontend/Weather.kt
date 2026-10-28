package dev.firefrontend

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

enum class Sky { CLEAR, PARTLY, CLOUDY, OVERCAST }
enum class Precip { NONE, DRIZZLE, LIGHT_RAIN, RAIN, HEAVY_RAIN, LIGHT_SNOW, SNOW, HEAVY_SNOW, SLEET, HAIL }

/** What the weather icon and backdrop should show. */
data class WeatherVisual(
    val label: String,
    val sky: Sky,
    val precip: Precip = Precip.NONE,
    val thunder: Boolean = false,
    val fog: Boolean = false,
) {
    val isSnowy get() = precip == Precip.LIGHT_SNOW || precip == Precip.SNOW || precip == Precip.HEAVY_SNOW
    val isWet get() = precip != Precip.NONE && !isSnowy
}

object WeatherCodes {
    /** Maps an Open-Meteo / WMO weather code to a visual. */
    fun visual(code: Int, isDay: Boolean): WeatherVisual = when (code) {
        0 -> WeatherVisual(if (isDay) "Sunny" else "Clear", Sky.CLEAR)
        1 -> WeatherVisual("Mostly clear", Sky.PARTLY)
        2 -> WeatherVisual("Partly cloudy", Sky.PARTLY)
        3 -> WeatherVisual("Overcast", Sky.OVERCAST)
        45, 48 -> WeatherVisual("Fog", Sky.CLOUDY, fog = true)
        51 -> WeatherVisual("Light drizzle", Sky.CLOUDY, Precip.DRIZZLE)
        53 -> WeatherVisual("Drizzle", Sky.CLOUDY, Precip.DRIZZLE)
        55 -> WeatherVisual("Heavy drizzle", Sky.OVERCAST, Precip.LIGHT_RAIN)
        56, 57 -> WeatherVisual("Freezing drizzle", Sky.OVERCAST, Precip.SLEET)
        61 -> WeatherVisual("Light rain", Sky.CLOUDY, Precip.LIGHT_RAIN)
        63 -> WeatherVisual("Rain", Sky.OVERCAST, Precip.RAIN)
        65 -> WeatherVisual("Heavy rain", Sky.OVERCAST, Precip.HEAVY_RAIN)
        66, 67 -> WeatherVisual("Freezing rain", Sky.OVERCAST, Precip.SLEET)
        71 -> WeatherVisual("Light snow", Sky.CLOUDY, Precip.LIGHT_SNOW)
        73 -> WeatherVisual("Snow", Sky.OVERCAST, Precip.SNOW)
        75 -> WeatherVisual("Heavy snow", Sky.OVERCAST, Precip.HEAVY_SNOW)
        77 -> WeatherVisual("Snow grains", Sky.CLOUDY, Precip.LIGHT_SNOW)
        80 -> WeatherVisual("Light showers", Sky.PARTLY, Precip.LIGHT_RAIN)
        81 -> WeatherVisual("Showers", Sky.PARTLY, Precip.RAIN)
        82 -> WeatherVisual("Heavy showers", Sky.OVERCAST, Precip.HEAVY_RAIN)
        85 -> WeatherVisual("Light snow showers", Sky.PARTLY, Precip.LIGHT_SNOW)
        86 -> WeatherVisual("Snow showers", Sky.PARTLY, Precip.SNOW)
        95 -> WeatherVisual("Thunderstorm", Sky.OVERCAST, Precip.RAIN, thunder = true)
        96, 99 -> WeatherVisual("Thunderstorm with hail", Sky.OVERCAST, Precip.HAIL, thunder = true)
        else -> WeatherVisual("—", Sky.CLOUDY)
    }

    /** "Cold", "Hot" etc. from the feels-like temperature in °F, or null when unremarkable. */
    fun feel(feelsLikeF: Double): String? = when {
        feelsLikeF <= 32 -> "Freezing"
        feelsLikeF < 45 -> "Cold"
        feelsLikeF >= 90 -> "Hot"
        feelsLikeF >= 80 -> "Warm"
        else -> null
    }
}

/** Current weather from Open-Meteo (free, no key) for a city the user typed in Settings. */
class WeatherRepository(context: Context) {

    data class Place(val name: String, val lat: Double, val lon: Double)

    data class Current(
        val code: Int,
        val tempF: Double,
        val feelsLikeF: Double,
        val isDay: Boolean,
        val highF: Double,
        val lowF: Double,
        val fetchedAt: Long,
    )

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    var useFahrenheit: Boolean
        get() = prefs.getBoolean(KEY_FAHRENHEIT, Locale.getDefault().country in FAHRENHEIT_COUNTRIES)
        set(value) = prefs.edit().putBoolean(KEY_FAHRENHEIT, value).apply()

    fun place(): Place? {
        val name = prefs.getString(KEY_PLACE, null) ?: return null
        return Place(name, prefs.getFloat(KEY_LAT, 0f).toDouble(), prefs.getFloat(KEY_LON, 0f).toDouble())
    }

    fun savePlace(place: Place) {
        prefs.edit()
            .putString(KEY_PLACE, place.name)
            .putFloat(KEY_LAT, place.lat.toFloat())
            .putFloat(KEY_LON, place.lon.toFloat())
            .remove(KEY_CACHE)
            .apply()
    }

    /**
     * Network call: looks up a city name or postal code.
     *
     * UK postcodes go to postcodes.io (Open-Meteo doesn't know them). Everything
     * else tries Open-Meteo first, then OpenStreetMap, which covers most other
     * countries' postcodes.
     */
    fun geocode(query: String): Place? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val ukCode = q.uppercase().replace(Regex("\\s+"), "")
        if (UK_FULL_POSTCODE.matches(ukCode) || UK_OUTWARD_CODE.matches(ukCode)) {
            ukPostcode(ukCode)?.let { return it }
        }
        return openMeteo(q) ?: openStreetMap(q)
    }

    private fun ukPostcode(code: String): Place? = try {
        val full = UK_FULL_POSTCODE.matches(code)
        val url = if (full) "https://api.postcodes.io/postcodes/$code" else "https://api.postcodes.io/outcodes/$code"
        val r = JSONObject(get(url)).getJSONObject("result")
        val area = if (full) r.optString("admin_district") else r.optJSONArray("admin_district")?.optString(0)
        val label = if (full) r.getString("postcode") else r.getString("outcode")
        Place(listOfNotNull(area?.takeIf { it.isNotBlank() }, label).joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude"))
    } catch (e: Exception) {
        null
    }

    private fun openStreetMap(query: String): Place? = try {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(query, "UTF-8")
        // Nominatim's usage policy asks every client to identify itself.
        val results = org.json.JSONArray(get(url, userAgent = USER_AGENT))
        if (results.length() == 0) {
            null
        } else {
            val r = results.getJSONObject(0)
            val name = r.getString("display_name").split(", ").take(2).joinToString(", ")
            Place(name, r.getString("lat").toDouble(), r.getString("lon").toDouble())
        }
    } catch (e: Exception) {
        null
    }

    private fun openMeteo(query: String): Place? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" +
            URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(get(url)).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val region = r.optString("admin1").takeIf { it.isNotBlank() && it != r.getString("name") }
        val name = listOfNotNull(r.getString("name"), region).joinToString(", ")
        return Place(name, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    fun cached(): Current? {
        val json = prefs.getString(KEY_CACHE, null) ?: return null
        return try {
            val o = JSONObject(json)
            Current(
                o.getInt("code"), o.getDouble("temp"), o.getDouble("feels"), o.getBoolean("day"),
                o.getDouble("hi"), o.getDouble("lo"), o.getLong("at"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isStale(): Boolean {
        val c = cached() ?: return true
        return System.currentTimeMillis() - c.fetchedAt > STALE_MS
    }

    /** Network call: fetches current conditions for the saved place. Always stored in °F. */
    fun refresh(): Current? {
        val place = place() ?: return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1" +
            "&temperature_unit=fahrenheit&timezone=auto"
        val root = JSONObject(get(url))
        val cur = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val c = Current(
            code = cur.getInt("weather_code"),
            tempF = cur.getDouble("temperature_2m"),
            feelsLikeF = cur.getDouble("apparent_temperature"),
            isDay = cur.getInt("is_day") == 1,
            highF = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lowF = daily.getJSONArray("temperature_2m_min").getDouble(0),
            fetchedAt = System.currentTimeMillis(),
        )
        prefs.edit().putString(
            KEY_CACHE,
            JSONObject()
                .put("code", c.code).put("temp", c.tempF).put("feels", c.feelsLikeF).put("day", c.isDay)
                .put("hi", c.highF).put("lo", c.lowF).put("at", c.fetchedAt)
                .toString(),
        ).apply()
        return c
    }

    fun format(tempF: Double): String {
        val value = if (useFahrenheit) tempF else (tempF - 32) * 5 / 9
        return "${value.roundToInt()}°"
    }

    private fun get(url: String, userAgent: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val KEY_PLACE = "place"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_CACHE = "current"
        const val KEY_FAHRENHEIT = "fahrenheit"
        const val STALE_MS = 30 * 60 * 1000L
        const val TIMEOUT_MS = 8000
        val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
        // Spaces removed and uppercased before matching: "SW1A 1AA" → "SW1A1AA", "M1" → outward code only.
        val UK_FULL_POSTCODE = Regex("^[A-Z]{1,2}\\d[A-Z\\d]?\\d[A-Z]{2}$")
        val UK_OUTWARD_CODE = Regex("^[A-Z]{1,2}\\d[A-Z\\d]?$")
        const val USER_AGENT = "FireFrontend/0.1 (+https://github.com/jackSeigerman/FireFrontend)"
    }
}
