package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

internal data class FreeboxArtworkCacheSnapshot(
    val posters: Map<String, String> = emptyMap(),
    val backdrops: Map<String, String> = emptyMap()
)

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    keys().forEach { key ->
        optString(key).takeIf(String::isNotBlank)?.let { put(key, it) }
    }
}

internal fun decodeFreeboxArtworkCache(value: String?): FreeboxArtworkCacheSnapshot {
    if (value.isNullOrBlank()) return FreeboxArtworkCacheSnapshot()
    return runCatching {
        val root = JSONObject(value)
        FreeboxArtworkCacheSnapshot(
            posters = root.optJSONObject("posters")?.toStringMap().orEmpty(),
            backdrops = root.optJSONObject("backdrops")?.toStringMap().orEmpty()
        )
    }.getOrDefault(FreeboxArtworkCacheSnapshot())
}

private fun encodeFreeboxArtworkCache(snapshot: FreeboxArtworkCacheSnapshot): String =
    JSONObject().apply {
        put("posters", JSONObject(snapshot.posters))
        put("backdrops", JSONObject(snapshot.backdrops))
    }.toString()

/**
 * Device-local cache for automatically resolved Freebox artwork.
 *
 * Manual choices remain in [FreeboxPosterOverrideDataStore]. This cache only
 * lets Home paint the last known stable image before EPG/TMDB/frame enrichment
 * starts again in the background.
 */
@Singleton
class FreeboxArtworkCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE = "freebox_artwork_cache"
        const val KEY_CACHE = "artwork_json"
        val cacheKey = stringPreferencesKey(KEY_CACHE)
    }

    private fun store() = factory.get(
        freeboxSharedMediaProfileId(profileManager.activeProfileId.value),
        FEATURE
    )

    internal suspend fun getAll(): FreeboxArtworkCacheSnapshot = runCatching {
        decodeFreeboxArtworkCache(store().data.first()[cacheKey])
    }.getOrDefault(FreeboxArtworkCacheSnapshot())

    internal suspend fun putAll(posters: Map<String, String>, backdrops: Map<String, String>) {
        if (posters.isEmpty() && backdrops.isEmpty()) return
        runCatching {
            store().edit { preferences ->
                val current = decodeFreeboxArtworkCache(preferences[cacheKey])
                preferences[cacheKey] = encodeFreeboxArtworkCache(
                    FreeboxArtworkCacheSnapshot(
                        posters = current.posters + posters.filterValues(String::isNotBlank),
                        backdrops = current.backdrops + backdrops.filterValues(String::isNotBlank)
                    )
                )
            }
        }
    }

    internal suspend fun remove(contentIds: Collection<String>) {
        val ids = contentIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.isEmpty()) return
        runCatching {
            store().edit { preferences ->
                val current = decodeFreeboxArtworkCache(preferences[cacheKey])
                preferences[cacheKey] = encodeFreeboxArtworkCache(
                    FreeboxArtworkCacheSnapshot(
                        posters = current.posters - ids,
                        backdrops = current.backdrops - ids
                    )
                )
            }
        }
    }
}

