package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionSyncService"

@Singleton
class CollectionSyncService @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore,
    private val profileManager: ProfileManager,
    private val nasSyncApi: NasIptvFavoritesApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    /** Push local collections JSON to the NAS. */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val json = collectionsDataStore.exportCurrentProfileJson()
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les collections." }
            nasSyncApi.pushProfileBlobJson(profileId, "collections", json ?: "[]")
            Log.d(TAG, "Pushed collections to NAS for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push collections to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull remote collections JSON and apply locally.
     * Returns true if local state was updated.
     */
    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les collections." }
            val remoteJson = nasSyncApi.pullProfileBlobJson(profileId, "collections")
                ?: return@withContext Result.success(false)
            val remoteCollections = collectionsDataStore.importFromJson(remoteJson)
            val localCollections = collectionsDataStore.getCurrentCollections()
            if (remoteCollections.isEmpty() && localCollections.isNotEmpty()) return@withContext Result.success(false)
            if (remoteJson == (collectionsDataStore.exportCurrentProfileJson() ?: "")) return@withContext Result.success(false)
            isSyncingFromRemote = true
            try {
                collectionsDataStore.setCollections(remoteCollections)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Applied ${remoteCollections.size} NAS collections for profile $profileId")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull collections from remote", e)
            Result.failure(e)
        }
    }

    /**
     * Trigger a debounced push to remote after a local change.
     */
    fun triggerPush() {
        if (isSyncingFromRemote) return
        pushJob?.cancel()
        pushJob = scope.launch {
            if (!nasSyncApi.hasSession()) return@launch
            delay(500)
            pushToRemote()
        }
    }
}
