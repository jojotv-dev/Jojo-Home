package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.FreeboxVideoMeta
import com.nuvio.tv.data.freebox.freeboxCanonicalProgrammeOverrideKey
import com.nuvio.tv.domain.model.MetaCastMember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

internal const val FREEBOX_POSTER_OVERRIDE_FEATURE = "freebox_poster_overrides"
internal const val FREEBOX_ARTWORK_PENDING_SYNC_KEY = "local_artwork_changes_pending_sync"
private const val FREEBOX_POSTER_OVERRIDE_KEY = "poster_overrides_json"
private const val FREEBOX_BACKDROP_OVERRIDE_KEY = "backdrop_overrides_json"
private val freeboxPosterOverrideKey = stringPreferencesKey(FREEBOX_POSTER_OVERRIDE_KEY)
private val freeboxBackdropOverrideKey = stringPreferencesKey(FREEBOX_BACKDROP_OVERRIDE_KEY)
private const val FREEBOX_OVERVIEW_OVERRIDE_KEY = "overview_overrides_json"
private val freeboxOverviewOverrideKey = stringPreferencesKey(FREEBOX_OVERVIEW_OVERRIDE_KEY)
private const val FREEBOX_METADATA_OVERRIDE_KEY = "metadata_overrides_json"
private val freeboxMetadataOverrideKey = stringPreferencesKey(FREEBOX_METADATA_OVERRIDE_KEY)
private val freeboxArtworkPendingSyncKey = booleanPreferencesKey(FREEBOX_ARTWORK_PENDING_SYNC_KEY)
internal const val FREEBOX_SHARED_MEDIA_PROFILE_ID = 1

internal fun freeboxSharedMediaProfileId(activeProfileId: Int): Int =
    FREEBOX_SHARED_MEDIA_PROFILE_ID

internal fun decodeFreeboxPosterOverrides(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val source = JSONObject(json)
        buildMap {
            source.keys().forEach { contentId ->
                source.optString(contentId).takeIf { it.isNotBlank() }?.let { put(contentId, it) }
            }
        }
    }.getOrDefault(emptyMap())
}

private fun encodeFreeboxPosterOverrides(overrides: Map<String, String>): String =
    JSONObject().apply {
        overrides.forEach { (contentId, posterUrl) -> put(contentId, posterUrl) }
    }.toString()

private fun withCanonicalProgrammeArtworkAliases(values: Map<String, String>): Map<String, String> =
    values.toMutableMap().apply {
        values.forEach { (key, url) ->
            val canonicalKey = freeboxCanonicalProgrammeOverrideKey(key)
                ?.takeIf { it != key && it !in values }
                ?: return@forEach
            putIfAbsent(canonicalKey, url)
        }
    }

private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)

private fun JSONArray.stringList(): List<String> = buildList {
    for (index in 0 until length()) {
        optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
    }
}

internal fun decodeFreeboxMetadataOverrides(json: String?): Map<String, FreeboxVideoMeta> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val source = JSONObject(json)
        buildMap {
            source.keys().forEach { contentId ->
                val value = source.optJSONObject(contentId) ?: return@forEach
                val castMembers = value.optJSONArray("castMembers")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val member = array.optJSONObject(index) ?: continue
                            val name = member.optionalString("name") ?: continue
                            add(
                                MetaCastMember(
                                    name = name,
                                    character = member.optionalString("character"),
                                    photo = member.optionalString("photo"),
                                    tmdbId = member.optInt("tmdbId").takeIf { it > 0 }
                                )
                            )
                        }
                    }
                }.orEmpty()
                put(
                    contentId,
                    FreeboxVideoMeta(
                        tmdbId = value.optInt("tmdbId").takeIf { it > 0 },
                        mediaType = value.optionalString("mediaType"),
                        posterUrl = value.optionalString("posterUrl"),
                        backdropUrl = value.optionalString("backdropUrl"),
                        overview = value.optionalString("overview"),
                        voteAverage = value.optDouble("voteAverage").takeUnless { it.isNaN() },
                        year = value.optionalString("year"),
                        genres = value.optJSONArray("genres")?.stringList().orEmpty(),
                        runtimeMinutes = value.optInt("runtimeMinutes").takeIf { it > 0 },
                        releaseDate = value.optionalString("releaseDate"),
                        cast = value.optJSONArray("cast")?.stringList().orEmpty(),
                        director = value.optJSONArray("director")?.stringList().orEmpty(),
                        seasonCount = value.optInt("seasonCount").takeIf { it > 0 },
                        logoUrl = value.optionalString("logoUrl"),
                        castMembers = castMembers,
                        matchedTitle = value.optionalString("matchedTitle"),
                        originalTitle = value.optionalString("originalTitle")
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())
}

private fun encodeFreeboxMetadataOverrides(overrides: Map<String, FreeboxVideoMeta>): String =
    JSONObject().apply {
        overrides.forEach { (contentId, metadata) ->
            put(contentId, JSONObject().apply {
                metadata.tmdbId?.let { put("tmdbId", it) }
                metadata.mediaType?.let { put("mediaType", it) }
                metadata.posterUrl?.let { put("posterUrl", it) }
                metadata.backdropUrl?.let { put("backdropUrl", it) }
                metadata.overview?.let { put("overview", it) }
                metadata.voteAverage?.let { put("voteAverage", it) }
                metadata.year?.let { put("year", it) }
                put("genres", JSONArray(metadata.genres))
                metadata.runtimeMinutes?.let { put("runtimeMinutes", it) }
                metadata.releaseDate?.let { put("releaseDate", it) }
                put("cast", JSONArray(metadata.cast))
                put("director", JSONArray(metadata.director))
                metadata.seasonCount?.let { put("seasonCount", it) }
                metadata.logoUrl?.let { put("logoUrl", it) }
                metadata.matchedTitle?.let { put("matchedTitle", it) }
                metadata.originalTitle?.let { put("originalTitle", it) }
                put("castMembers", JSONArray().apply {
                    metadata.castMembers.forEach { member ->
                        put(JSONObject().apply {
                            put("name", member.name)
                            member.character?.let { put("character", it) }
                            member.photo?.let { put("photo", it) }
                            member.tmdbId?.let { put("tmdbId", it) }
                        })
                    }
                })
            })
        }
    }.toString()

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FreeboxPosterOverrideDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val visualAssetCache: SyncedVisualAssetCache
) {
    // A DataStore edit can legitimately wait behind another profile write. The
    // picker must nevertheless update Home on the same interaction, so expose
    // the pending choice immediately and retire it once DataStore confirms it.
    private val optimisticPosterOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    private val optimisticBackdropOverrides = MutableStateFlow<Map<String, String>>(emptyMap())

    private val rawOverrides: Flow<Map<String, String>> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            val sharedProfileId = freeboxSharedMediaProfileId(profileId)
            factory.get(sharedProfileId, FREEBOX_POSTER_OVERRIDE_FEATURE).data
                .map { preferences -> preferences.toPosterOverrides() }
                .catch { emit(emptyMap()) }
        }

    private val rawBackdropOverrides: Flow<Map<String, String>> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            val sharedProfileId = freeboxSharedMediaProfileId(profileId)
            factory.get(sharedProfileId, FREEBOX_POSTER_OVERRIDE_FEATURE).data
                .map { preferences -> decodeFreeboxPosterOverrides(preferences[freeboxBackdropOverrideKey]) }
                .catch { emit(emptyMap()) }
        }

    val overviewOverrides: Flow<Map<String, String>> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            val sharedProfileId = freeboxSharedMediaProfileId(profileId)
            factory.get(sharedProfileId, FREEBOX_POSTER_OVERRIDE_FEATURE).data
                .map { preferences -> decodeFreeboxPosterOverrides(preferences[freeboxOverviewOverrideKey]) }
                .catch { emit(emptyMap()) }
        }

    val metadataOverrides: Flow<Map<String, FreeboxVideoMeta>> = profileManager.activeProfileId
        .flatMapLatest { profileId ->
            val sharedProfileId = freeboxSharedMediaProfileId(profileId)
            factory.get(sharedProfileId, FREEBOX_POSTER_OVERRIDE_FEATURE).data
                .map { preferences -> decodeFreeboxMetadataOverrides(preferences[freeboxMetadataOverrideKey]) }
                .catch { emit(emptyMap()) }
        }

    /** Resolved maps prefer the NAS-backed local copy, while raw settings keep the source URL portable. */
    val overrides: Flow<Map<String, String>> = combine(
        rawOverrides,
        visualAssetCache.revision,
        optimisticPosterOverrides
    ) { values, _, optimistic ->
        (withCanonicalProgrammeArtworkAliases(values) + optimistic)
            .mapValues { (_, url) -> visualAssetCache.resolve(url) }
    }

    val backdropOverrides: Flow<Map<String, String>> = combine(
        rawBackdropOverrides,
        visualAssetCache.revision,
        optimisticBackdropOverrides
    ) { values, _, optimistic ->
        (withCanonicalProgrammeArtworkAliases(values) + optimistic)
            .mapValues { (_, url) -> visualAssetCache.resolve(url) }
    }

    suspend fun selectedArtworkUrls(): List<String> =
        (rawOverrides.first().values + rawBackdropOverrides.first().values)
            .map(String::trim).filter(String::isNotEmpty).distinct()

    suspend fun set(contentId: String, posterUrl: String) {
        setForContentIds(listOf(contentId), posterUrl)
    }

    suspend fun setForContentIds(contentIds: Collection<String>, posterUrl: String) {
        val ids = contentIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.isEmpty()) return
        optimisticPosterOverrides.update { current ->
            current + ids.associateWith { posterUrl }
        }
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        try {
            factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
                val updated = decodeFreeboxPosterOverrides(preferences[freeboxPosterOverrideKey]).toMutableMap()
                ids.forEach { contentId -> updated[contentId] = posterUrl }
                preferences[freeboxPosterOverrideKey] = encodeFreeboxPosterOverrides(updated)
                preferences[freeboxArtworkPendingSyncKey] = true
            }
            rawOverrides.first { persisted -> ids.all { persisted[it] == posterUrl } }
        } finally {
            optimisticPosterOverrides.update { current ->
                current.filterNot { (contentId, url) -> contentId in ids && url == posterUrl }
            }
        }
    }

    suspend fun setBackdrop(contentId: String, backdropUrl: String) {
        setBackdropForContentIds(listOf(contentId), backdropUrl)
    }

    suspend fun setBackdropForContentIds(contentIds: Collection<String>, backdropUrl: String) {
        val ids = contentIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.isEmpty()) return
        optimisticBackdropOverrides.update { current ->
            current + ids.associateWith { backdropUrl }
        }
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        try {
            factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
                val updated = decodeFreeboxPosterOverrides(preferences[freeboxBackdropOverrideKey]).toMutableMap()
                ids.forEach { contentId -> updated[contentId] = backdropUrl }
                preferences[freeboxBackdropOverrideKey] = encodeFreeboxPosterOverrides(updated)
                preferences[freeboxArtworkPendingSyncKey] = true
            }
            rawBackdropOverrides.first { persisted -> ids.all { persisted[it] == backdropUrl } }
        } finally {
            optimisticBackdropOverrides.update { current ->
                current.filterNot { (contentId, url) -> contentId in ids && url == backdropUrl }
            }
        }
    }

    suspend fun setOverview(contentId: String, overview: String) {
        setOverviewForContentIds(listOf(contentId), overview)
    }

    suspend fun setOverviewForContentIds(contentIds: Collection<String>, overview: String) {
        val ids = contentIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.isEmpty()) return
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
            val updated = decodeFreeboxPosterOverrides(preferences[freeboxOverviewOverrideKey]).toMutableMap()
            ids.forEach { contentId -> updated[contentId] = overview }
            preferences[freeboxOverviewOverrideKey] = encodeFreeboxPosterOverrides(updated)
            preferences[freeboxArtworkPendingSyncKey] = true
        }
    }

    suspend fun setMetadataForContentIds(
        contentIds: Collection<String>,
        metadata: FreeboxVideoMeta
    ) {
        val ids = contentIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.isEmpty()) return
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
            val updated = decodeFreeboxMetadataOverrides(preferences[freeboxMetadataOverrideKey]).toMutableMap()
            ids.forEach { contentId -> updated[contentId] = metadata }
            preferences[freeboxMetadataOverrideKey] = encodeFreeboxMetadataOverrides(updated)
            preferences[freeboxArtworkPendingSyncKey] = true
        }
    }

    /**
     * Removes only the presentation identity attached to an artwork choice.
     * The artwork itself remains selected. This is required when the user
     * replaces a TMDB image with a frame extracted from the recording: keeping
     * the previous TMDB overview would associate two different programmes.
     */
    suspend fun clearMetadataForContentIds(contentIds: Collection<String>) {
        val ids = contentIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.isEmpty()) return
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
            val overviews = decodeFreeboxPosterOverrides(preferences[freeboxOverviewOverrideKey]).toMutableMap()
            val metadata = decodeFreeboxMetadataOverrides(preferences[freeboxMetadataOverrideKey]).toMutableMap()
            ids.forEach { contentId ->
                overviews.remove(contentId)
                metadata.remove(contentId)
            }
            preferences[freeboxOverviewOverrideKey] = encodeFreeboxPosterOverrides(overviews)
            preferences[freeboxMetadataOverrideKey] = encodeFreeboxMetadataOverrides(metadata)
            preferences[freeboxArtworkPendingSyncKey] = true
        }
    }

    suspend fun move(fromContentId: String, toContentId: String) {
        if (fromContentId == toContentId) return
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
            val updated = decodeFreeboxPosterOverrides(preferences[freeboxPosterOverrideKey]).toMutableMap()
            val posterUrl = updated.remove(fromContentId) ?: return@edit
            updated[toContentId] = posterUrl
            preferences[freeboxPosterOverrideKey] = encodeFreeboxPosterOverrides(updated)

            val backdrops = decodeFreeboxPosterOverrides(preferences[freeboxBackdropOverrideKey]).toMutableMap()
            backdrops.remove(fromContentId)?.let { backdrops[toContentId] = it }
            preferences[freeboxBackdropOverrideKey] = encodeFreeboxPosterOverrides(backdrops)

            val overviews = decodeFreeboxPosterOverrides(preferences[freeboxOverviewOverrideKey]).toMutableMap()
            overviews.remove(fromContentId)?.let { overviews[toContentId] = it }
            preferences[freeboxOverviewOverrideKey] = encodeFreeboxPosterOverrides(overviews)

            val metadata = decodeFreeboxMetadataOverrides(preferences[freeboxMetadataOverrideKey]).toMutableMap()
            metadata.remove(fromContentId)?.let { metadata[toContentId] = it }
            preferences[freeboxMetadataOverrideKey] = encodeFreeboxMetadataOverrides(metadata)
            preferences[freeboxArtworkPendingSyncKey] = true
        }
    }

    suspend fun remove(contentIds: Collection<String>) {
        val ids = contentIds.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return
        val profileId = freeboxSharedMediaProfileId(profileManager.activeProfileId.value)
        factory.get(profileId, FREEBOX_POSTER_OVERRIDE_FEATURE).edit { preferences ->
            val posters = decodeFreeboxPosterOverrides(preferences[freeboxPosterOverrideKey]).toMutableMap()
            val backdrops = decodeFreeboxPosterOverrides(preferences[freeboxBackdropOverrideKey]).toMutableMap()
            val overviews = decodeFreeboxPosterOverrides(preferences[freeboxOverviewOverrideKey]).toMutableMap()
            val metadata = decodeFreeboxMetadataOverrides(preferences[freeboxMetadataOverrideKey]).toMutableMap()
            ids.forEach { contentId ->
                posters.remove(contentId)
                backdrops.remove(contentId)
                overviews.remove(contentId)
                metadata.remove(contentId)
            }
            preferences[freeboxPosterOverrideKey] = encodeFreeboxPosterOverrides(posters)
            preferences[freeboxBackdropOverrideKey] = encodeFreeboxPosterOverrides(backdrops)
            preferences[freeboxOverviewOverrideKey] = encodeFreeboxPosterOverrides(overviews)
            preferences[freeboxMetadataOverrideKey] = encodeFreeboxMetadataOverrides(metadata)
            preferences[freeboxArtworkPendingSyncKey] = true
        }
    }

    private fun Preferences.toPosterOverrides(): Map<String, String> =
        decodeFreeboxPosterOverrides(this[freeboxPosterOverrideKey])
}
