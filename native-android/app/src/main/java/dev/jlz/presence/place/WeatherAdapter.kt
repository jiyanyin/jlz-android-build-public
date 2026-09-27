package dev.jlz.presence.place

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherState(
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val precipitationMm: Double?,
    val rainMm: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?,
    val observedAtMs: Long,
    val source: String = "open-meteo"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("temperature_c", temperatureC)
        .put("apparent_temperature_c", apparentTemperatureC)
        .put("precipitation_mm", precipitationMm)
        .put("rain_mm", rainMm)
        .put("weather_code", weatherCode)
        .put("wind_speed_kmh", windSpeedKmh)
        .put("observed_at_ms", observedAtMs)
        .put("source", source)
}

class WeatherAdapter {
    @Volatile private var cached: WeatherState? = null
    @Volatile private var cachedLat: Double? = null
    @Volatile private var cachedLon: Double? = null
    @Volatile private var cachedAtMs: Long = 0L

    fun current(location: Location?): WeatherState? {
        if (location == null) return null
        val now = System.currentTimeMillis()
        val prior = cached
        if (
            prior != null &&
            now - cachedAtMs < 20 * 60_000L &&
            cachedLat != null &&
            cachedLon != null &&
            kotlin.math.abs(location.latitude - cachedLat!!) < 0.08 &&
            kotlin.math.abs(location.longitude - cachedLon!!) < 0.08
        ) {
            return prior
        }

        val lat = String.format(Locale.US, "%.5f", location.latitude)
        val lon = String.format(Locale.US, "%.5f", location.longitude)
        val endpoint =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=" + lat +
                "&longitude=" + lon +
                "&current=temperature_2m,apparent_temperature,precipitation,rain,weather_code,wind_speed_10m" +
                "&timezone=auto"

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val current = JSONObject(text).optJSONObject("current") ?: return null
            WeatherState(
                temperatureC = current.optDouble("temperature_2m").takeUnless { it.isNaN() },
                apparentTemperatureC = current.optDouble("apparent_temperature").takeUnless { it.isNaN() },
                precipitationMm = current.optDouble("precipitation").takeUnless { it.isNaN() },
                rainMm = current.optDouble("rain").takeUnless { it.isNaN() },
                weatherCode = if (current.has("weather_code")) current.optInt("weather_code") else null,
                windSpeedKmh = current.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
                observedAtMs = now
            ).also {
                cached = it
                cachedLat = location.latitude
                cachedLon = location.longitude
                cachedAtMs = now
            }
        } finally {
            conn.disconnect()
        }
    }
}
