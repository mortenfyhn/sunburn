package no.fyhn.uvindex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class Location(
    val name: String,
    val country: String? = null,
    val admin1: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(name, admin1?.takeIf { it != name }, country)
            .joinToString(", ")
}

data class HourUv(val localTime: LocalDateTime, val uv: Double)

/** Best-effort ZoneId from a stored timezone string, falling back to the
 *  device default when missing or unparseable. */
fun Location.zoneOrSystem(): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

private val json = Json { ignoreUnknownKeys = true }

// Nominatim's usage policy requires a User-Agent that identifies the
// application (default HTTP-client UAs are blocked). The package name is
// enough; no personal contact info is embedded.
private const val USER_AGENT = "UvIndex/1.0 (no.fyhn.uvindex)"

private const val HTTP_TIMEOUT_MS = 8_000

/**
 * 24 hourly UV values for the location's local calendar day (00–23).
 *
 * Source: currentuvindex.com — free, no key, CC BY 4.0. It exposes `history`
 * (past 24h), `now`, and `forecast` (≈120h ahead) in UTC, which gives us the
 * full local day regardless of when the app is opened. Values match yr.no /
 * met.no within ≤ 0.1 UVI in spot checks (currentuvindex.com and met.no both
 * model real-world cloud-adjusted UV).
 */
suspend fun fetchForecast(loc: Location): List<HourUv> = withContext(Dispatchers.IO) {
    val url = "https://currentuvindex.com/api/v1/uvi" +
        "?latitude=${loc.latitude}&longitude=${loc.longitude}"
    val body = httpGet(url)
    val parsed = json.decodeFromString<UvResponse>(body)
    if (!parsed.ok) throw java.io.IOException("currentuvindex returned ok=false")

    val zone = loc.zoneOrSystem()
    val today = LocalDate.now(zone)

    // Combine history + now + forecast (later sources win when hours overlap).
    val byLocalHour = mutableMapOf<Int, Double>()
    parsed.history?.forEach { it.assignTo(byLocalHour, zone, today) }
    parsed.now?.assignTo(byLocalHour, zone, today)
    parsed.forecast?.forEach { it.assignTo(byLocalHour, zone, today) }

    (0..23).map { hour ->
        val uv = byLocalHour[hour] ?: 0.0
        HourUv(LocalDateTime.of(today, LocalTime.of(hour, 0)), uv)
    }
}

private fun UvSample.assignTo(out: MutableMap<Int, Double>, zone: ZoneId, today: LocalDate) {
    val local = Instant.parse(time).atZone(zone).toLocalDateTime()
    if (local.toLocalDate() == today) out[local.hour] = uvi
}

@Serializable
private data class UvResponse(
    val ok: Boolean,
    val now: UvSample? = null,
    val forecast: List<UvSample>? = null,
    val history: List<UvSample>? = null,
)

@Serializable
private data class UvSample(val time: String, val uvi: Double)

@Serializable
private data class NominatimResult(
    @SerialName("display_name") val displayName: String,
    val lat: String,
    val lon: String,
)

/**
 * Search via Nominatim (OpenStreetMap). Chosen over Open-Meteo's geocoder
 * because it covers tiny Norwegian places (small islands, hamlets) that
 * Open-Meteo drops, and tolerates ASCII variants like "tromso" → Tromsø.
 *
 * If [bias] is given, a viewbox around it nudges Nominatim to rank nearby
 * places higher — so "sula" returns Sula in Frøya before Sula in Montana when
 * the user's last picked location is in Norway. Soft bias only (bounded=0),
 * so far-away searches still work.
 *
 * Nominatim doesn't return a timezone — we fall back to the device's system
 * zone when rendering, which is correct for in-country use.
 */
suspend fun searchLocations(query: String, bias: Location? = null): List<Location> = withContext(Dispatchers.IO) {
    val q = URLEncoder.encode(query, "UTF-8")
    val box = bias?.let {
        val span = 5.0
        "&viewbox=${it.longitude - span},${it.latitude + span}," +
            "${it.longitude + span},${it.latitude - span}&bounded=0"
    } ?: ""
    val url = "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=10$box"
    val body = httpGet(url)
    val parsed = json.decodeFromString<List<NominatimResult>>(body)
    parsed.map { r ->
        Location(
            name = cleanDisplayName(r.displayName),
            country = null,
            admin1 = null,
            latitude = r.lat.toDouble(),
            longitude = r.lon.toDouble(),
            timezone = null,
        )
    }
}

/** Strip numeric postcode parts from Nominatim's display_name. */
private fun cleanDisplayName(s: String): String =
    s.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.matches(Regex("\\d{3,6}")) }
        .joinToString(", ")

private fun httpGet(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = HTTP_TIMEOUT_MS
        readTimeout = HTTP_TIMEOUT_MS
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", USER_AGENT)
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw java.io.IOException("HTTP $code from $url")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
