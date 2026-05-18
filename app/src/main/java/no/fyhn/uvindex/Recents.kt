package no.fyhn.uvindex

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "prefs")
private val RECENTS_KEY = stringPreferencesKey("recents")
private val SELECTED_KEY = stringPreferencesKey("selected")

private const val MAX_RECENTS = 5

private val json = Json { ignoreUnknownKeys = true }
private val locationListSerializer = ListSerializer(Location.serializer())

class Recents(private val context: Context) {

    val selected: Flow<Location?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_KEY]?.let { runCatching { json.decodeFromString(Location.serializer(), it) }.getOrNull() }
    }

    val list: Flow<List<Location>> = context.dataStore.data.map { prefs ->
        prefs[RECENTS_KEY]?.let { runCatching { json.decodeFromString(locationListSerializer, it) }.getOrNull() }
            .orEmpty()
    }

    suspend fun select(loc: Location) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_KEY] = json.encodeToString(Location.serializer(), loc)
            val existing = prefs[RECENTS_KEY]
                ?.let { runCatching { json.decodeFromString(locationListSerializer, it) }.getOrNull() }
                .orEmpty()
            val deduped = listOf(loc) + existing.filter { !it.sameCoords(loc) }
            val trimmed = deduped.take(MAX_RECENTS)
            prefs[RECENTS_KEY] = json.encodeToString(locationListSerializer, trimmed)
        }
    }

    suspend fun delete(loc: Location) {
        context.dataStore.edit { prefs ->
            val existing = prefs[RECENTS_KEY]
                ?.let { runCatching { json.decodeFromString(locationListSerializer, it) }.getOrNull() }
                .orEmpty()
            val filtered = existing.filter { !it.sameCoords(loc) }
            prefs[RECENTS_KEY] = json.encodeToString(locationListSerializer, filtered)
        }
    }
}

private fun Location.sameCoords(other: Location): Boolean =
    kotlin.math.abs(latitude - other.latitude) < 1e-4 &&
        kotlin.math.abs(longitude - other.longitude) < 1e-4
