package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeboxDurationCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "freebox_duration_cache"
        private const val KEY_CACHE = "durations_json"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(freeboxSharedMediaProfileId(profileId), FEATURE)

    suspend fun getAll(): Map<String, Long> {
        return try {
            val prefs = store().data.first()
            val json = prefs[androidx.datastore.preferences.core.stringPreferencesKey(KEY_CACHE)] ?: return emptyMap()
            val obj = JSONObject(json)
            val result = mutableMapOf<String, Long>()
            obj.keys().forEach { key -> result[key] = obj.optLong(key, 0L) }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun put(path: String, durationMs: Long) {
        try {
            store().edit { preferences ->
                val key = androidx.datastore.preferences.core.stringPreferencesKey(KEY_CACHE)
                val existingJson = preferences[key] ?: "{}"
                val obj = JSONObject(existingJson)
                obj.put(path, durationMs)
                preferences[key] = obj.toString()
            }
        } catch (_: Exception) {
            // ignore cache write failures
        }
    }

    suspend fun remove(path: String) {
        try {
            store().edit { preferences ->
                val key = androidx.datastore.preferences.core.stringPreferencesKey(KEY_CACHE)
                val existingJson = preferences[key] ?: "{}"
                val obj = JSONObject(existingJson)
                obj.remove(path)
                preferences[key] = obj.toString()
            }
        } catch (_: Exception) {
            // ignore cache write failures
        }
    }
}

