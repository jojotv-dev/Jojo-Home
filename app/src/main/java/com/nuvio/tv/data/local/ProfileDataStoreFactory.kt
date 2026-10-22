package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val PROFILE_SCOPED_FEATURES = setOf(
    "addon_preferences",
    "animeskip_settings",
    "audio_delay_route_preference",
    "debrid_settings",
    "experience_settings",
    "iptv_settings",
    "layout_settings",
    "mdblist_settings",
    "player_settings",
    "plugin_settings",
    "search_history",
    "theme_settings",
    "tmdb_settings",
    "track_preference",
    "trailer_settings",
    "watch_progress_preferences",
    "watched_items_preferences",
    "watched_series_cache",
    "binge_group_cache",
    "library_preferences",
    "collections",
    "trakt_auth_store",
    "trakt_settings",
    "startup_sync_state"
)

private val PROFILE_SCOPED_FEATURES_COPIED_FROM_PRIMARY = setOf(
    "addon_preferences",
    "animeskip_settings",
    "audio_delay_route_preference",
    "debrid_settings",
    "experience_settings",
    "iptv_settings",
    "layout_settings",
    "mdblist_settings",
    "player_settings",
    "plugin_settings",
    "search_history",
    "theme_settings",
    "tmdb_settings",
    "track_preference",
    "trailer_settings"
)

internal fun profileDataStoreOwnerId(profileId: Int, featureName: String): Int =
    if (profileId == 1 || featureName in PROFILE_SCOPED_FEATURES) profileId else 1

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val ownerProfileId = profileDataStoreOwnerId(profileId, featureName)
        val fileName = if (ownerProfileId == 1) featureName else "${featureName}_p$ownerProfileId"
        synchronized(lock) {
            cache[fileName]?.let { return it.store }
            copyPrimaryPreferencesIfNeeded(ownerProfileId, featureName, fileName)
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p$profileId"
        val keysToRemove = synchronized(lock) {
            cache.keys.filter { it.endsWith(suffix) }
        }
        for (key in keysToRemove) {
            val scoped = synchronized(lock) { cache.remove(key) } ?: continue
            runCatching { scoped.store.edit { it.clear() } }
            // Cancel the scope so DataStore releases the file from its active-files
            // registry. Wait for completion before any subsequent get() can create
            // a fresh DataStore for the same path.
            scoped.job.cancel()
            scoped.job.join()
        }
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        deletedProfileIds.remove(profileId)
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — resetting to empty preferences")
                corruptedFileNames.add(fileName)
                emptyPreferences()
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )
        val scoped = ScopedDataStore(store, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    private fun copyPrimaryPreferencesIfNeeded(
        ownerProfileId: Int,
        featureName: String,
        fileName: String
    ) {
        if (ownerProfileId == 1 || featureName !in PROFILE_SCOPED_FEATURES_COPIED_FROM_PRIMARY) return

        val profileFile = context.preferencesDataStoreFile(fileName)
        if (profileFile.exists()) return

        val primaryFile = context.preferencesDataStoreFile(featureName)
        if (!primaryFile.exists()) return

        runCatching {
            profileFile.parentFile?.mkdirs()
            primaryFile.copyTo(profileFile, overwrite = false)
        }.onFailure { error ->
            Log.w(
                "ProfileDataStoreFactory",
                "Unable to seed $fileName from primary $featureName: ${error.message}"
            )
        }
    }
}

