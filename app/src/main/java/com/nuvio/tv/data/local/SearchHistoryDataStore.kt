package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        internal const val FEATURE = "search_history"
        internal const val RECENT_SEARCHES_KEY = "recent_searches"
        internal const val RECENT_SEARCHES_UPDATED_AT_KEY = "recent_searches_updated_at"
        internal const val RECENT_SEARCHES_CLEARED_AT_KEY = "recent_searches_cleared_at"
        private const val DEFAULT_MAX_RECENT_SEARCHES = 8
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val recentSearchesKey = stringPreferencesKey(RECENT_SEARCHES_KEY)
    private val recentSearchesUpdatedAtKey = longPreferencesKey(RECENT_SEARCHES_UPDATED_AT_KEY)
    private val recentSearchesClearedAtKey = longPreferencesKey(RECENT_SEARCHES_CLEARED_AT_KEY)

    private fun recentSearches(profileId: Int): Flow<List<String>> =
        factory.get(profileId, FEATURE).data.map { prefs ->
            parseRecentSearches(prefs[recentSearchesKey])
        }

    val recentSearches: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        recentSearches(profileId)
    }

    suspend fun saveRecentSearch(
        query: String,
        maxItems: Int = DEFAULT_MAX_RECENT_SEARCHES,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return

        val current = recentSearches(profileId).first()
        val updated = buildList {
            add(normalized)
            addAll(current.filterNot { it.equals(normalized, ignoreCase = true) })
        }.take(maxItems.coerceAtLeast(1))

        store(profileId).edit { prefs ->
            prefs[recentSearchesKey] = gson.toJson(updated)
            prefs[recentSearchesUpdatedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun clearRecentSearches(profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs.remove(recentSearchesKey)
            prefs[recentSearchesClearedAtKey] = System.currentTimeMillis()
        }
    }

    private fun parseRecentSearches(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(raw, type).orEmpty()
            normalizeRecentSearches(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeRecentSearches(items: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        return items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { value -> seen.add(value.lowercase()) }
            .toList()
    }
}

