package no.fyhn.uvindex

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prefs")
private val RECENTS_KEY = stringPreferencesKey("recents")
private val SELECTED_KEY = stringPreferencesKey("selected")
private val CACHE_KEY = stringPreferencesKey("forecast_cache")

private const val MAX_RECENTS = 5

private val json = Json { ignoreUnknownKeys = true }
private val locationListSerializer = ListSerializer(Location.serializer())

// The cache stores one day's hourly UV values keyed by location + date.
// Date is the local calendar day in the location's zone, so an out-of-day
// cache (yesterday's curve) gets rejected automatically.
@Serializable
private data class CachedForecast(
    val latitude: Double,
    val longitude: Double,
    val date: String,
    val hours: List<CachedHour>,
)

@Serializable
private data class CachedHour(val hour: Int, val uv: Double)

/** Decode the stored recents list, or empty if missing / malformed. */
private fun Preferences.recents(): List<Location> =
    this[RECENTS_KEY]?.let {
        runCatching { json.decodeFromString(locationListSerializer, it) }.getOrNull()
    }.orEmpty()

class Recents(private val context: Context) {

    val selected: Flow<Location?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_KEY]?.let { runCatching { json.decodeFromString(Location.serializer(), it) }.getOrNull() }
    }

    val list: Flow<List<Location>> = context.dataStore.data.map { it.recents() }

    suspend fun select(loc: Location) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_KEY] = json.encodeToString(Location.serializer(), loc)
            val deduped = listOf(loc) + prefs.recents().filter { !it.sameCoords(loc) }
            prefs[RECENTS_KEY] = json.encodeToString(locationListSerializer, deduped.take(MAX_RECENTS))
        }
    }

    suspend fun delete(loc: Location) {
        context.dataStore.edit { prefs ->
            val filtered = prefs.recents().filter { !it.sameCoords(loc) }
            prefs[RECENTS_KEY] = json.encodeToString(locationListSerializer, filtered)
        }
    }

    suspend fun saveCache(loc: Location, hours: List<HourUv>) {
        val today = LocalDate.now(loc.zoneOrSystem()).toString()
        val cached = CachedForecast(
            latitude = loc.latitude,
            longitude = loc.longitude,
            date = today,
            hours = hours.map { CachedHour(it.localTime.hour, it.uv) },
        )
        context.dataStore.edit { prefs ->
            prefs[CACHE_KEY] = json.encodeToString(CachedForecast.serializer(), cached)
        }
    }

    /** Returns cached hours for [loc] if the cache is for the same coords and the
     *  same local calendar day, otherwise null. No staleness window is applied —
     *  the forecast is for a calendar day so it's valid all day, even after a
     *  morning fetch becomes hours old. */
    suspend fun loadCache(loc: Location): List<HourUv>? {
        val raw = context.dataStore.data.first()[CACHE_KEY] ?: return null
        val cached = runCatching { json.decodeFromString(CachedForecast.serializer(), raw) }.getOrNull()
            ?: return null
        if (!loc.sameCoords(cached.latitude, cached.longitude)) return null
        val today = LocalDate.now(loc.zoneOrSystem()).toString()
        if (cached.date != today) return null
        val date = LocalDate.parse(cached.date)
        return cached.hours.map { HourUv(LocalDateTime.of(date, LocalTime.of(it.hour, 0)), it.uv) }
    }
}

private fun Location.sameCoords(other: Location): Boolean =
    sameCoords(other.latitude, other.longitude)

private fun Location.sameCoords(lat: Double, lon: Double): Boolean =
    kotlin.math.abs(latitude - lat) < 1e-4 &&
        kotlin.math.abs(longitude - lon) < 1e-4
