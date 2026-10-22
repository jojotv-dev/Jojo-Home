package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchedItemsSyncService"
data class WatchedItemsRemoteSyncResult(
    val upsertedItems: Int,
    val deletedItems: Int,
    val usedSnapshot: Boolean,
    val preservedLocalItems: Boolean
)

@Singleton
class WatchedItemsSyncService @Inject constructor(
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager,
    private val nasSyncApi: NasIptvFavoritesApi
) {
    private val deltaSyncMutex = Mutex()

    /**
     * Timestamp of the last successful push to remote.
     * Used to protect local items created after this point from being
     * removed during pull (they haven't reached remote yet).
     */
    @Volatile
    var lastSuccessfulPushMs: Long = 0L
        private set

    fun markPushSucceeded() {
        val now = System.currentTimeMillis()
        lastSuccessfulPushMs = now
        CoroutineScope(Dispatchers.IO).launch {
            watchedItemsPreferences.setLastSuccessfulPushMs(now)
        }
    }

    suspend fun restoreLastPushTimestamp() {
        lastSuccessfulPushMs = watchedItemsPreferences.getLastSuccessfulPushMs()
    }


    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val items = watchedItemsPreferences.getAllItems()
            Log.d(TAG, "pushToRemote: ${items.size} watched items to push")
            pushItemsToRemote(items, updateLastSuccessfulPush = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched items to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pushItemsToRemote(
        items: Collection<WatchedItem>,
        updateLastSuccessfulPush: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (items.isEmpty()) return@withContext Result.success(Unit)
            Log.d(TAG, "pushItemsToRemote: ${items.size} watched items to push")
            val profileId = profileManager.activeProfileId.value
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les éléments vus." }
            nasSyncApi.pushWatchedItems(profileId, items.mapNotNull(::watchedItemForNasSync))
            Log.d(TAG, "Pushed ${items.size} watched items to NAS for profile $profileId")
            if (updateLastSuccessfulPush) {
                markPushSucceeded()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched item batch to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<List<WatchedItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "pullFromRemote: starting full watched items snapshot for profile $profileId")
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les éléments vus." }
            Result.success(nasSyncApi.pullWatchedItems(profileId).entries.map { it.toWatchedItem() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items from remote", e)
            Result.failure(e)
        }
    }

    suspend fun syncDeltaFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchedItemsRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            syncDeltaFromRemoteLocked(profileId)
        }
    }

    private suspend fun syncDeltaFromRemoteLocked(
        profileId: Int
    ): Result<WatchedItemsRemoteSyncResult> {
        return try {
            val localCount = watchedItemsPreferences.getAllItems().size
            Log.d(
                TAG,
                "syncDeltaFromRemote: NAS snapshot profile=$profileId localCount=$localCount lastPush=$lastSuccessfulPushMs"
            )
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les éléments vus." }
            return syncNasSnapshot(profileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items delta from remote", e)
            Result.failure(e)
        }
    }

    suspend fun deleteFromRemote(
        contentId: String,
        season: Int?,
        episode: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les éléments vus." }
            nasSyncApi.deleteWatchedItems(profileId, listOf(NasWatchedItem(contentId, "local", "deleted", season, episode, System.currentTimeMillis())))
            Log.d(TAG, "Deleted watched item from NAS: $contentId s=$season e=$episode for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watched item from remote", e)
            Result.failure(e)
        }
    }

    suspend fun deleteFromRemoteBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (episodes.isEmpty()) return@withContext Result.success(Unit)

            val profileId = profileManager.activeProfileId.value
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les éléments vus." }
            nasSyncApi.deleteWatchedItems(profileId, episodes.map { (season, episode) ->
                NasWatchedItem(contentId, "local", "deleted", season, episode, System.currentTimeMillis())
            })
            Log.d(TAG, "Batch deleted ${episodes.size} watched items from NAS for $contentId profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch delete watched items from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun syncNasSnapshot(profileId: Int): Result<WatchedItemsRemoteSyncResult> {
        return try {
            val snapshot = nasSyncApi.pullWatchedItems(profileId)
            val localByKey = watchedItemsPreferences.getAllItems().associateBy { it.keyString() }
            val upserts = snapshot.entries.map { it.toWatchedItem() }.filter { remote ->
                (localByKey[remote.keyString()]?.watchedAt ?: 0L) <= remote.watchedAt
            }
            val deletes = snapshot.tombstones.mapNotNull { (key, deletedAt) ->
                key.toWatchedItemKey()?.takeIf { parsed -> (localByKey[key]?.watchedAt ?: 0L) <= deletedAt }
            }
            watchedItemsPreferences.applyRemoteChanges(upserts, deletes, profileId)
            val local = watchedItemsPreferences.getAllItems()
            nasSyncApi.pushWatchedItems(profileId, local.mapNotNull(::watchedItemForNasSync))
            markPushSucceeded()
            Result.success(WatchedItemsRemoteSyncResult(upserts.size, deletes.size, usedSnapshot = true, preservedLocalItems = false))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synchronize watched items with NAS", e)
            Result.failure(e)
        }
    }

    private fun NasWatchedItem.toWatchedItem() = WatchedItem(contentId, contentType, title, season, episode, watchedAt)
    private fun WatchedItem.keyString() = "$contentId|${season ?: ""}|${episode ?: ""}"
    private fun String.toWatchedItemKey(): Triple<String, Int?, Int?>? {
        val values = split("|")
        if (values.size != 3 || values[0].isBlank()) return null
        return Triple(values[0], values[1].toIntOrNull(), values[2].toIntOrNull())
    }
}
