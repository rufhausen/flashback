package dev.gtaylor.flashback.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "flashback_settings")

class SettingsRepository(
    private val context: Context,
) {
    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("settings")
        val json = Json { ignoreUnknownKeys = true }
    }

    val settings: Flow<FlashbackSettings> =
        context.dataStore.data.map { prefs ->
            prefs[SETTINGS_KEY]
                ?.let { runCatching { json.decodeFromString<FlashbackSettings>(it) }.getOrNull() }
                ?: FlashbackSettings()
        }

    /** Applies [transform] atomically and returns the saved settings. */
    suspend fun update(transform: (FlashbackSettings) -> FlashbackSettings): FlashbackSettings {
        lateinit var updated: FlashbackSettings
        context.dataStore.edit { prefs ->
            val current =
                prefs[SETTINGS_KEY]
                    ?.let { runCatching { json.decodeFromString<FlashbackSettings>(it) }.getOrNull() }
                    ?: FlashbackSettings()
            updated = transform(current)
            prefs[SETTINGS_KEY] = json.encodeToString(updated)
        }
        return updated
    }
}
