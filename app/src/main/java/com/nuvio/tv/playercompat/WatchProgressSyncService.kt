package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.NasSyncStateStore
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchProgressSyncService"
data class WatchProgressRemoteSyncResult(
    val upsertedEntries: Int,
    val deletedEntries: Int,
    val usedSnapshot: Boolean,
    val preservedLocalItems: Boolean
)

/** Narrow transport boundary, also used by storage/transport integration tests. */
internal interface WatchProgressRemote {
    suspend fun hasSession(): Boolean
    suspend fun pullWatchProgress(profileId: Int): NasWatchProgressSnapshot
    suspend fun pushWatchProgress(profileId: Int, entries: List<NasWatchProgress>)
    suspend fun deleteWatchProgressConditionally(profileId: Int, deletions: Map<String, Long>)
}

@Singleton
class WatchProgressSyncService internal constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val activeProfileId: StateFlow<Int>,
    private val nasSyncApi: WatchProgressRemote,
    private val syncStateStore: NasSyncStateStore,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Inject constructor(
        preferences: WatchProgressPreferences,
        profileManager: ProfileManager,
        api: NasIptvFavoritesApi,
        state: NasSyncStateStore
    ) : this(preferences, profileManager.activeProfileId, api, state)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deltaSyncMutex = Mutex()
    private val migrationMutex = Mutex()
    // All NAS read/write operations share one lane. Without this, a delayed
    // periodic save can finish after the exit save and replace a newer resume
    // position on the NAS.
    private val remoteIoMutex = Mutex()
    private val latestPushedProgressByKey = mutableMapOf<String, Long>()
    private data class SinglePushRetry(val atMs: Long, val delayMs: Long)
    private val singlePushRetriesByProfile = mutableMapOf<Int, SinglePushRetry>()

    /**
     * Timestamp (epoch ms) of the last successful push to remote.
     * Used by [WatchProgressPreferences.mergeRemoteEntries] to protect local entries
     * that were created after the last push - these haven't reached remote yet,
     * so their absence from a pull response does NOT mean they were deleted on
     * another device.
     */
    @Volatile
    var lastSuccessfulPushMs: Long = 0L
        private set

    /** Called after a successful push to record the sync point. */
    private suspend fun markPushSucceeded(profileId: Int) {
        singlePushRetriesByProfile.remove(profileId)
        val now = System.currentTimeMillis()
        lastSuccessfulPushMs = now
        watchProgressPreferences.setLastSuccessfulPushMs(now, profileId)
    }

    /** Restores persisted push timestamp on startup. */
    suspend fun restoreLastPushTimestamp(profileId: Int = activeProfileId.value) {
        lastSuccessfulPushMs = watchProgressPreferences.getLastSuccessfulPushMs(profileId)
    }

    suspend fun deleteFromRemote(
        keys: Collection<String>,
        profileId: Int = activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        try {
            val distinctKeys = keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (distinctKeys.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            remoteIoMutex.withLock {
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                val queuedKeys = watchProgressPreferences.pendingRemoteDeleteKeys(profileId)
                val requested = distinctKeys.filter { it in queuedKeys }.toSet()
                val pending = watchProgressPreferences.pendingRemoteDeleteIntents(profileId)
                    .filterKeys { it in requested }
                require(pending.size == requested.size) { "Progress deletion has no dated intent" }
                if (pending.isEmpty()) return@withLock // Another flush already acknowledged these keys.
                deleteFromRemoteLocked(pending, profileId)
                watchProgressPreferences.acknowledgeRemoteDeleteIntents(pending, profileId)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watch progress from remote", e)
            Result.failure(e)
        }
    }

    /** Replays deletions that could not reach the NAS when the user made them. */
    suspend fun flushPendingRemoteDeletes(
        profileId: Int = activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        try {
            remoteIoMutex.withLock { flushPendingRemoteDeletesLocked(profileId) }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun deleteFromRemoteLocked(
        intents: Map<String, WatchProgressPreferences.PendingRemoteDelete>, profileId: Int
    ) {
        require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
        require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les reprises." }
        val deletions = intents.mapValues { it.value.deletedAt }.toMutableMap()
        if (intents.keys.any { it.startsWith("wp2:") }) {
            for (entry in nasSyncApi.pullWatchProgress(profileId).entries) {
                val intent = intents[entry.toWatchProgress().storageKey] ?: continue
                deletions[entry.key] = maxOf(deletions[entry.key] ?: 0L, intent.deletedAt)
            }
        }
        // The dedicated route fails closed on an older Companion. Do not fall
        // back to the unguarded DELETE after a failed or unsupported request.
        nasSyncApi.deleteWatchProgressConditionally(profileId, deletions)
        deletions.keys.forEach { latestPushedProgressByKey.remove("$profileId:$it") }
    }

    /** Called only while remoteIoMutex is held; never reacquires the same mutex. */
    private suspend fun flushPendingRemoteDeletesLocked(profileId: Int) {
        val pending = watchProgressPreferences.pendingRemoteDeleteIntents(profileId)
        val undated = watchProgressPreferences.pendingRemoteDeleteKeys(profileId).size - pending.size
        if (undated > 0) Log.w(TAG, "Deferred $undated undated watch-progress deletions for profile $profileId; review required")
        if (pending.isEmpty()) return
        deleteFromRemoteLocked(pending, profileId)
        watchProgressPreferences.acknowledgeRemoteDeleteIntents(pending, profileId)
        Log.d(TAG, "Replayed ${pending.size} pending watch-progress deletions for profile $profileId")
    }

    suspend fun migrateExistingLocalSnapshotIfNeeded(profileId: Int): Result<Unit> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        try {
            migrationMutex.withLock {
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                if (!syncStateStore.migrationCompleted(profileId)) {
                    // Absence on this device is not a deletion. Merge first, then
                    // publish the union; only explicit pending deletes reach DELETE.
                    syncDeltaFromRemote(profileId).getOrThrow()
                    val merged = watchProgressPreferences.getAllRawEntries(profileId)
                    merged.keys.forEach { syncStateStore.enqueueProgressUpsert(profileId, it) }
                    if (merged.isNotEmpty()) pushToRemote(profileId).getOrThrow()
                    syncStateStore.markMigrationCompleted(profileId)
                    Log.i(TAG, "Merged initial NAS progress for profile $profileId (${merged.size} entries)")
                }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            syncStateStore.recordFailure(profileId, "migration", error)
            Result.failure(error)
        }
    }

    suspend fun flushPendingUpserts(profileId: Int = activeProfileId.value): Result<Unit> {
        if (syncStateStore.pendingProgressUpserts(profileId).isEmpty()) return Result.success(Unit)
        return pushToRemote(profileId)
    }

    /**
     * Push all local watch progress to the NAS.
     * Always syncs regardless of CW source — both Trakt and Nuvio Sync
     * should have up-to-date progress data.
     *
     * @param profileId The profile to push data for. Captured at call-site to
     *   prevent race conditions when the active profile changes mid-operation.
     */
    suspend fun pushToRemote(
        profileId: Int = activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        try {
            remoteIoMutex.withLock {
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                val pendingAtStart = syncStateStore.pendingProgressVersions(profileId)
                val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)
                val entries = canonicalizeForRemote(rawEntries).filterValues { progress ->
                    !(progress.position <= 1L && progress.duration <= 1L && progress.duration > 0L)
                }
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les reprises." }
                nasSyncApi.pushWatchProgress(profileId, entries.map { (key, progress) -> progress.toNasEntry(key) })
                entries.forEach { (key, progress) ->
                    latestPushedProgressByKey["$profileId:$key"] = progress.lastWatched
                }
                syncStateStore.acknowledgeProgressVersions(profileId, pendingAtStart)
                syncStateStore.recordSuccess(profileId, "watch_progress")
                Log.d(TAG, "Pushed ${entries.size} watch progress entries to NAS for profile $profileId")
                markPushSucceeded(profileId)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            syncStateStore.recordFailure(profileId, "watch_progress", e)
            Log.e(TAG, "Failed to push watch progress to remote", e)
            Result.failure(e)
        }
    }

    
    /**
     * Push a single watch progress entry to the NAS.
     * Offline retries are deferred without acknowledging the durable checkpoint.
     *
     * @param profileId The profile to push data for. Captured at call-site to
     *   prevent race conditions when the active profile changes mid-operation.
     */
    suspend fun pushSingleToRemote(
        key: String,
        progress: WatchProgress,
        profileId: Int = activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        try {
            remoteIoMutex.withLock {
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                val retry = singlePushRetriesByProfile[profileId]
                if (retry != null && monotonicTimeMs() < retry.atMs) {
                    if (key !in syncStateStore.pendingProgressUpserts(profileId)) {
                        syncStateStore.enqueueProgressUpsert(profileId, key)
                    }
                    return@withLock
                }
                val pendingAtStart = syncStateStore.pendingProgressVersions(profileId)
                    .filterKeys { it == key || it == progress.storageKey }
                // The callback may have waited behind a full push or a newer save.
                val current = watchProgressPreferences.getAllRawEntries(profileId).values
                    .filter { it.storageKey == progress.storageKey }.maxByOrNull { it.lastWatched }
                    ?: return@withLock
                val (canonicalKey, canonicalProgress) = canonicalizeProgressPair(key, current)
                val progressKey = "$profileId:$canonicalKey"
                val latestPushedAt = latestPushedProgressByKey[progressKey]
                if (latestPushedAt != null && canonicalProgress.lastWatched < latestPushedAt) {
                    Log.d(
                        TAG,
                        "Skipped stale watch progress push key=$canonicalKey " +
                            "incoming=${canonicalProgress.lastWatched} latest=$latestPushedAt"
                    )
                    return@withLock
                }
                try {
                    require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                    require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les reprises." }
                    nasSyncApi.pushWatchProgress(profileId, listOf(canonicalProgress.toNasEntry(canonicalKey)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val retryDelayMs = ((retry?.delayMs ?: 7_500L) * 2).coerceAtMost(120_000L)
                    singlePushRetriesByProfile[profileId] = SinglePushRetry(
                        monotonicTimeMs() + retryDelayMs, retryDelayMs
                    )
                    throw error
                }
                latestPushedProgressByKey[progressKey] = canonicalProgress.lastWatched
                syncStateStore.acknowledgeProgressVersions(profileId, pendingAtStart)
                syncStateStore.recordSuccess(profileId, "watch_progress")
                Log.d(
                    TAG,
                    "Pushed single watch progress entry to NAS for profile $profileId " +
                        "(key=$canonicalKey position=${canonicalProgress.position} " +
                        "lastWatched=${canonicalProgress.lastWatched})"
                )
                markPushSucceeded(profileId)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            syncStateStore.recordFailure(profileId, "watch_progress", e)
            Log.e(TAG, "Failed to push single watch progress to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull watch progress from the NAS snapshot.
     * Skips if Trakt is connected. Caller is responsible for merging into local.
     *
     * @param profileId The profile to pull data for. Captured at call-site to
     *   prevent race conditions when the active profile changes mid-operation.
     */
    suspend fun pullFromRemote(
        profileId: Int = activeProfileId.value,
        sinceLastWatched: Long? = null,
        limit: Int? = null
    ): Result<List<Pair<String, WatchProgress>>> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        try {
            remoteIoMutex.withLock {
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les reprises." }
                val snapshot = nasSyncApi.pullWatchProgress(profileId)
        require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_CHANGED" }
                val pulled = normalizePulledEntries(snapshot.entries.map { item -> item.key to item.toWatchProgress() })
                Log.d(TAG, "pullFromRemote: fetched ${pulled.size} entries from NAS for profile $profileId")
                Result.success(pulled)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watch progress from remote", e)
            Result.failure(e)
        }
    }

    suspend fun syncDeltaFromRemote(
        profileId: Int = activeProfileId.value
    ): Result<WatchProgressRemoteSyncResult> = withContext(Dispatchers.IO + com.jojotv.home.progresspilot.NasProgressMutation) {
        deltaSyncMutex.withLock {
            remoteIoMutex.withLock {
                require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                syncDeltaFromRemoteLocked(profileId)
            }
        }
    }

    private suspend fun syncDeltaFromRemoteLocked(
        profileId: Int
    ): Result<WatchProgressRemoteSyncResult> {
        return try {
            val localCount = watchProgressPreferences.getAllRawEntries(profileId).size
            Log.d(
                TAG,
                "syncDeltaFromRemote: NAS snapshot profile=$profileId localCount=$localCount lastPush=$lastSuccessfulPushMs"
            )
            require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les reprises." }
            return Result.success(syncNasSnapshot(profileId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watch progress delta from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun syncNasSnapshot(profileId: Int): WatchProgressRemoteSyncResult {
        // Apply tombstones before reading the snapshot. Otherwise an offline
        // deletion can be re-imported from the NAS by this very pull.
        flushPendingRemoteDeletesLocked(profileId)
        val snapshot = nasSyncApi.pullWatchProgress(profileId)
        require(!watchProgressPreferences.isCloud(profileId)) { "NAS_PROGRESS_SOURCE_CHANGED" }
        val normalized = normalizePulledEntries(snapshot.entries.map { item -> item.key to item.toWatchProgress() })
        val preservedFromUpserts = watchProgressPreferences.applyRemoteChanges(
            upserts = normalized.toMap(),
            deletes = snapshot.tombstones.keys,
            lastSuccessfulPushMs = watchProgressPreferences.getLastSuccessfulPushMs(profileId),
            profileId = profileId,
            deletionTimes = snapshot.tombstones
        )
        val preservedLocalItems = preservedFromUpserts
        Log.d(TAG, "syncNasSnapshot: profile=$profileId upserts=${normalized.size} deletes=${snapshot.tombstones.size} preservedLocal=$preservedLocalItems")
        return WatchProgressRemoteSyncResult(
            upsertedEntries = normalized.size,
            deletedEntries = snapshot.tombstones.size,
            usedSnapshot = true,
            preservedLocalItems = preservedLocalItems
        )
    }

    private fun WatchProgress.toNasEntry(key: String) = NasWatchProgress(
        key = key, contentId = contentId, contentType = contentType, name = name, poster = poster, backdrop = backdrop, logo = logo,
        videoId = videoId, season = season, episode = episode, episodeTitle = episodeTitle, position = position, duration = duration,
        lastWatched = lastWatched, addonBaseUrl = addonBaseUrl, progressPercent = progressPercent, source = source,
        traktPlaybackId = traktPlaybackId, traktMovieId = traktMovieId, traktShowId = traktShowId, traktEpisodeId = traktEpisodeId, description = description
    )

    private fun NasWatchProgress.toWatchProgress() = WatchProgress(
        contentId = contentId, contentType = contentType, name = name, poster = poster, backdrop = backdrop, logo = logo,
        videoId = videoId, season = season, episode = episode, episodeTitle = episodeTitle, position = position, duration = duration,
        lastWatched = lastWatched, addonBaseUrl = addonBaseUrl, progressPercent = progressPercent, source = source.ifBlank { WatchProgress.SOURCE_LOCAL },
        traktPlaybackId = traktPlaybackId, traktMovieId = traktMovieId, traktShowId = traktShowId, traktEpisodeId = traktEpisodeId, description = description
    )

    private suspend fun canonicalizeForRemote(
        rawEntries: Map<String, WatchProgress>
    ): Map<String, WatchProgress> {
        if (rawEntries.isEmpty()) return rawEntries

        val canonical = linkedMapOf<String, WatchProgress>()
        rawEntries.forEach { (key, progress) ->
            val (canonicalKey, canonicalProgress) = canonicalizeProgressPair(key, progress)
            putNewest(canonical, canonicalKey, canonicalProgress)
        }

        val withoutMirrors = canonical.toMutableMap()
        canonical.forEach { (key, progress) ->
            val isSeriesMirrorKey = key == progress.contentId &&
                isSeriesType(progress.contentType) &&
                progress.season != null &&
                progress.episode != null
            if (!isSeriesMirrorKey) return@forEach

            val season = progress.season
            val episode = progress.episode
            val episodeKey = episodeKey(
                contentId = progress.contentId,
                season = season,
                episode = episode
            )
            val episodeProgress = canonical[episodeKey] ?: return@forEach

            val exactMirror = progress.position == episodeProgress.position &&
                progress.duration == episodeProgress.duration &&
                progress.lastWatched == episodeProgress.lastWatched
            val episodeIsAtLeastAsFresh = episodeProgress.lastWatched >= progress.lastWatched - 1_000L

            if (exactMirror || episodeIsAtLeastAsFresh) {
                withoutMirrors.remove(key)
            }
        }

        return withoutMirrors
    }

    private suspend fun normalizePulledEntries(
        entries: List<Pair<String, WatchProgress>>
    ): List<Pair<String, WatchProgress>> {
        if (entries.isEmpty()) return entries

        val byKey = linkedMapOf<String, WatchProgress>()
        entries.sortedByDescending { it.second.lastWatched }
            .forEach { (key, progress) ->
                val (canonicalKey, canonicalProgress) = canonicalizeProgressPair(key, progress)
                putNewest(byKey, canonicalKey, canonicalProgress)
            }

        return byKey.entries
            .sortedByDescending { it.value.lastWatched }
            .map { it.key to it.value }
    }

    private suspend fun canonicalizeProgressPair(
        key: String,
        progress: WatchProgress
    ): Pair<String, WatchProgress> {
        return progress.storageKey to progress
    }

    private fun canonicalProgressKey(
        key: String,
        oldContentId: String,
        canonicalContentId: String,
        progress: WatchProgress
    ): String {
        val season = progress.season
        val episode = progress.episode
        return when {
            season != null && episode != null -> episodeKey(canonicalContentId, season, episode)
            key == oldContentId -> canonicalContentId
            key.startsWith("${oldContentId}_") -> key.replaceFirst(oldContentId, canonicalContentId)
            else -> canonicalContentId
        }
    }

    private fun putNewest(
        target: MutableMap<String, WatchProgress>,
        key: String,
        progress: WatchProgress
    ) {
        val existing = target[key]
        if (existing == null || progress.lastWatched > existing.lastWatched) {
            target[key] = progress
        }
    }

    private fun episodeKey(contentId: String, season: Int, episode: Int): String {
        return "${contentId}_s${season}e${episode}"
    }

    private fun isSeriesType(contentType: String): Boolean {
        return contentType.lowercase() in setOf("series", "tv")
    }

}
