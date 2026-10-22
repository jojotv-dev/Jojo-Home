package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeboxDeletedItemsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "freebox_deleted_items"
        private const val KEY_DELETED = "deleted_items_json"
        private val deletedKey = stringPreferencesKey(KEY_DELETED)
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(freeboxSharedMediaProfileId(profileId), FEATURE)

    suspend fun getAll(): Map<String, String> {
        return try {
            val json = store().data.first()[deletedKey] ?: return emptyMap()
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { path ->
                    obj.optString(path).takeIf { it.isNotBlank() }?.let { contentId ->
                        put(path, contentId)
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun remember(path: String, contentId: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank() || contentId.isBlank()) return
        try {
            store().edit { preferences ->
                val obj = JSONObject(preferences[deletedKey] ?: "{}")
                obj.put(normalizedPath, contentId)
                preferences[deletedKey] = obj.toString()
            }
        } catch (_: Exception) {
            // Best-effort cleanup marker.
        }
    }

    suspend fun forget(path: String) {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return
        try {
            store().edit { preferences ->
                val obj = JSONObject(preferences[deletedKey] ?: "{}")
                obj.remove(normalizedPath)
                preferences[deletedKey] = obj.toString()
            }
        } catch (_: Exception) {
            // Best-effort cleanup marker.
        }
    }
}

