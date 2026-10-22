package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.StartupSyncPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.NasSyncStateStore
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.data.remote.nas.NasAvatarRepository
import com.nuvio.tv.domain.model.LibrarySourceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"
private const val FORCE_RESYNC_MIN_INTERVAL_MS = 30_000L
private const val FULL_STARTUP_PULL_TTL_MS = 6 * 60 * 60 * 1000L
private const val FOREGROUND_WATCH_STATE_SYNC_MIN_INTERVAL_MS = 2_500L
private const val FOREGROUND_WATCH_STATE_POLL_INTERVAL_MS = 4_000L

@Singleton
class StartupSyncService @Inject constructor(
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
    private val collectionSyncService: CollectionSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val profileSyncService: ProfileSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val profileManager: ProfileManager,
    private val startupSyncPreferences: StartupSyncPreferences,
    private val nasSyncApi: NasIptvFavoritesApi,
    private val syncStateStore: NasSyncStateStore,
    private val nasAvatarRepository: NasAvatarRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var preHomePrioritySyncJob: Job? = null
    private var foregroundWatchStateJob: Job? = null
    private var foregroundWatchStatePollingJob: Job? = null
    private var lastPulledKey: String? = null
    private var lastPulledIncludedProfileSettings: Boolean = false
    private var lastPulledAtMs: Long = 0L
    @Volatile
    private var lastForegroundWatchStateProfileId: Int? = null
    @Volatile
    private var lastForegroundWatchStateSyncAtMs: Long = 0L
    @Volatile
    private var forceSyncRequested: Boolean = false
    @Volatile
    private var forceSyncIncludesProfileSettings: Boolean = true
    @Volatile
    private var pendingResyncKey: String? = null
    @Volatile
    private var pendingResyncIncludesProfileSettings: Boolean = false
    /**
     * A complete NAS pull writes many DataStore and Room records. On the TV's
     * CPU that competes with Compose during the first frame, leaving Home black
     * for several seconds. Keep the initial pull pending until Home has drawn.
     */
    @Volatile
    private var homeHasRenderedThisSession: Boolean = false
    @Volatile
    private var appIsInForeground: Boolean = false

    init {
        scope.launch {
            nasSyncApi.sessionTokenFlow.collect { token ->
                if (!token.isNullOrBlank()) {
                    syncStateStore.bindAccount(nasSyncApi.accountEmailFlow.first().orEmpty())
                    if (!homeHasRenderedThisSession) {
                        schedulePreHomePrioritySync()
                        return@collect
                    }
                    val email = nasSyncApi.accountEmailFlow.first().orEmpty()
                    val force = forceSyncRequested
                    val includeProfileSettings = if (force) forceSyncIncludesProfileSettings else true
                    val started = scheduleStartupPull("nas:$email", force = force, includeProfileSettings = includeProfileSettings)
                    if (force && started) forceSyncRequested = false
                } else {
                    startupPullJob?.cancel()
                    startupPullJob = null
                    preHomePrioritySyncJob?.cancel()
                    preHomePrioritySyncJob = null
                    lastPulledKey = null
                    lastPulledIncludedProfileSettings = false
                    lastPulledAtMs = 0L
                    forceSyncRequested = false
                    forceSyncIncludesProfileSettings = true
                    pendingResyncKey = null
                    pendingResyncIncludesProfileSettings = false
                    homeHasRenderedThisSession = false
                }
            }
        }
    }

    private fun schedulePreHomePrioritySync() {
        if (preHomePrioritySyncJob?.isActive == true) return
        preHomePrioritySyncJob = scope.launch {
            var profileId = profileManager.activeProfileId.value
            try {
                // The pre-Home fast path must obey the same profile ordering as
                // the full startup path.  Otherwise a fresh TV can read/publish
                // profile 1 before the NAS profile directory is restored.
                profileSyncService.pullFromRemote().getOrElse { throw it }
                profileId = profileManager.activeProfileId.value
                watchProgressSyncService.restoreLastPushTimestamp()
                watchProgressSyncService.migrateExistingLocalSnapshotIfNeeded(profileId).getOrElse { throw it }
                watchProgressSyncService.flushPendingUpserts(profileId).getOrElse { throw it }
                watchProgressRepository.isSyncingFromRemote = true
                watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
                watchProgressRepository.hasCompletedInitialPull = true
                // Do not advance the global change-journal revision here: this
                // fast path deliberately restores only priority data. Advancing
                // it would hide pending library/catalog/addon/plugin changes
                // from the complete foreground dispatcher.
                Log.d(TAG, "Pre-Home priority NAS sync completed for profile $profileId")
            } catch (error: Exception) {
                syncStateStore.recordFailure(profileId, "pre_home", error)
                Log.w(TAG, "Pre-Home priority NAS sync failed for profile $profileId", error)
            } finally {
                watchProgressRepository.isSyncingFromRemote = false
            }
        }
    }

    fun requestSyncNow(includeProfileSettings: Boolean = true) {
        forceSyncRequested = true
        forceSyncIncludesProfileSettings = forceSyncIncludesProfileSettings || includeProfileSettings
        scope.launch {
            if (!homeHasRenderedThisSession) return@launch
            if (nasSyncApi.hasSession()) {
                val started = scheduleStartupPull("nas:${nasSyncApi.accountEmailFlow.first().orEmpty()}", force = true, includeProfileSettings = includeProfileSettings)
                if (started) forceSyncRequested = false
            }
        }
    }

    fun requestForegroundSync() {
        if (!homeHasRenderedThisSession) return
        scope.launch {
            if (nasSyncApi.hasSession()) scheduleStartupPull("nas:${nasSyncApi.accountEmailFlow.first().orEmpty()}", force = false, includeProfileSettings = true)
        }
        requestForegroundWatchStateSync()
    }

    /** Called after Home has drawn usable content for the first time. */
    fun notifyHomeRendered() {
        if (homeHasRenderedThisSession) return
        homeHasRenderedThisSession = true
        startForegroundWatchStatePollingIfNeeded()
        scope.launch {
            if (!nasSyncApi.hasSession()) return@launch
            val includeProfileSettings = if (forceSyncRequested) {
                forceSyncIncludesProfileSettings
            } else {
                true
            }
            val started = scheduleStartupPull(
                userId = "nas:${nasSyncApi.accountEmailFlow.first().orEmpty()}",
                force = forceSyncRequested,
                includeProfileSettings = includeProfileSettings
            )
            if (forceSyncRequested && started) forceSyncRequested = false
        }
    }

    /**
     * Reconcile only the small watch-progress delta while the app remains
     * visible. A second TV can then publish a resume point without requiring
     * this TV to leave and reopen JojoTV. The complete startup sync is never
     * repeated here, so Home rendering and IPTV work stay unaffected.
     */
    fun onAppForegroundChanged(isForeground: Boolean) {
        appIsInForeground = isForeground
        if (isForeground) {
            startForegroundWatchStatePollingIfNeeded()
        } else {
            foregroundWatchStatePollingJob?.cancel()
            foregroundWatchStatePollingJob = null
        }
    }

    private fun startForegroundWatchStatePollingIfNeeded() {
        if (!appIsInForeground || !homeHasRenderedThisSession) return
        if (foregroundWatchStatePollingJob?.isActive == true) return
        foregroundWatchStatePollingJob = scope.launch {
            requestForegroundWatchStateSync()
            while (isActive) {
                delay(FOREGROUND_WATCH_STATE_POLL_INTERVAL_MS)
                requestForegroundWatchStateSync()
            }
        }
    }

    /**
     * A complete foreground sync is deliberately skipped while its cache is fresh,
     * but Continue Watching cannot be skipped: another TV may have advanced an
     * episode in the meantime. Keep this small pull separate from IPTV/catalogue
     * work so resuming JojoTV imports the latest NAS position without startup churn.
     */
    private fun requestForegroundWatchStateSync() {
        val profileId = profileManager.activeProfileId.value
        val now = SystemClock.elapsedRealtime()
        if (
            lastForegroundWatchStateProfileId == profileId &&
            now - lastForegroundWatchStateSyncAtMs < FOREGROUND_WATCH_STATE_SYNC_MIN_INTERVAL_MS
        ) return
        // A complete startup pull already includes watch progress. Avoid toggling
        // the shared remote-sync flag while that wider transaction is in flight.
        if (startupPullJob?.isActive == true || foregroundWatchStateJob?.isActive == true) return

        foregroundWatchStateJob = scope.launch {
            if (!nasSyncApi.hasSession()) return@launch
            try {
                watchProgressSyncService.flushPendingUpserts(profileId).getOrElse { throw it }
                watchProgressSyncService.flushPendingRemoteDeletes(profileId).getOrElse { throw it }
                val knownRevision = syncStateStore.revision(profileId)
                val changes = nasSyncApi.pullChanges(profileId, knownRevision)
                val plan = buildForegroundNasSyncPlan(
                    knownRevision = knownRevision,
                    changes = changes,
                    localDeviceId = syncStateStore.deviceId
                )
                var watchResult: WatchProgressRemoteSyncResult? = null
                if (plan.pullProfiles) {
                    profileSyncService.pullFromRemote().getOrElse { throw it }
                }
                if (plan.pullWatchProgress) {
                    watchProgressRepository.isSyncingFromRemote = true
                    watchResult = watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
                    watchProgressRepository.hasCompletedInitialPull = true
                }
                if (plan.pullWatchedItems) {
                    pullWatchedItemsDelta(profileId, failOnError = true)
                }
                if (plan.profileBlobKeys.isNotEmpty()) {
                    pullChangedProfileBlobs(profileId, plan.profileBlobKeys)
                }
                syncStateStore.setRevision(profileId, changes.revision)
                syncStateStore.recordSuccess(profileId, "changes", changes.revision)
                lastForegroundWatchStateProfileId = profileId
                lastForegroundWatchStateSyncAtMs = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "Foreground NAS sync: profile=$profileId revision=${changes.revision} domains=${plan.foreignDomains} blobs=${plan.profileBlobKeys} " +
                        "watchUpserts=${watchResult?.upsertedEntries ?: 0} watchDeletes=${watchResult?.deletedEntries ?: 0}"
                )
                // This is a foreground *pull*.  Local playback writes already
                // push immediately, while pushing every polling cycle turns one
                // stale local entry into a pull -> push loop and can repeatedly
                // re-publish an obsolete resume position.
            } catch (error: Exception) {
                syncStateStore.recordFailure(profileId, "changes", error)
                Log.w(TAG, "Foreground watch-state sync failed for profile $profileId", error)
            } finally {
                watchProgressRepository.isSyncingFromRemote = false
            }
        }
    }

    fun requestAddonSyncNow() {
        scope.launch {
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Manual addon sync requested for profile $profileId")

            addonRepository.isSyncingFromRemote = true
            try {
                val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }

                addonRepository.reconcileWithRemoteAddonUrls(
                    remoteUrls = remoteAddonUrls,
                    removeMissingLocal = true
                )

                Log.d(TAG, "Manual addon sync pulled ${remoteAddonUrls.size} addons for profile $profileId")
            } catch (e: Exception) {
                Log.e(TAG, "Manual addon sync failed", e)
            } finally {
                addonRepository.isSyncingFromRemote = false
            }
        }
    }


    private fun pullKey(userId: String): String {
        val profileId = profileManager.activeProfileId.value
        return "${userId}_p${profileId}"
    }

    private fun scheduleStartupPull(
        userId: String,
        force: Boolean = false,
        includeProfileSettings: Boolean = true
    ): Boolean {
        val key = pullKey(userId)
        val now = SystemClock.elapsedRealtime()
        val sameKey = lastPulledKey == key
        val coversProfileSettings = !includeProfileSettings || lastPulledIncludedProfileSettings
        if (!force && sameKey && coversProfileSettings) {
            return false
        }
        if (
            force &&
            sameKey &&
            coversProfileSettings &&
            startupPullJob?.isActive != true &&
            now - lastPulledAtMs < FORCE_RESYNC_MIN_INTERVAL_MS
        ) {
            return false
        }
        // Never cancel an active sync — it may be mid-write to DataStore.
        // Instead, schedule a follow-up sync after the current one finishes.
        if (startupPullJob?.isActive == true) {
            if (force) {
                pendingResyncKey = key
                pendingResyncIncludesProfileSettings =
                    pendingResyncIncludesProfileSettings || includeProfileSettings
            }
            return false
        }

        startupPullJob = scope.launch {
            val maxAttempts = 3
            var syncCompleted = false
            for (attempt in 1..maxAttempts) {
                val result = pullRemoteData(
                    userId = userId,
                    force = force,
                    includeProfileSettings = includeProfileSettings
                )
                if (result.isSuccess) {
                    lastPulledKey = key
                    lastPulledIncludedProfileSettings = includeProfileSettings
                    lastPulledAtMs = SystemClock.elapsedRealtime()
                    syncCompleted = true
                    break
                }

                Log.w(TAG, "Startup sync attempt $attempt failed for key=$key", result.exceptionOrNull())
                if (attempt < maxAttempts) {
                    delay(3000)
                }
            }
            
            val resyncKey = pendingResyncKey
            if (resyncKey != null) {
                val resyncIncludesProfileSettings = pendingResyncIncludesProfileSettings
                pendingResyncKey = null
                pendingResyncIncludesProfileSettings = false
                if (
                    !syncCompleted ||
                    resyncKey != lastPulledKey ||
                    (resyncIncludesProfileSettings && !lastPulledIncludedProfileSettings)
                ) {
                    scheduleStartupPull(
                        userId = userId,
                        force = true,
                        includeProfileSettings = resyncIncludesProfileSettings
                    )
                }
            }
        }
        return true
    }

    private suspend fun pullRemoteData(
        userId: String,
        force: Boolean,
        includeProfileSettings: Boolean
    ): Result<Unit> {
        try {
            // Establish the NAS profile directory before touching any
            // profile-scoped data.  A fresh TV otherwise starts the priority
            // sync with its local default profile and can publish/read data
            // under the wrong profile before the remote profile list arrives.
            profileSyncService.pullFromRemote().getOrElse { throw it }
            val profileId = profileManager.activeProfileId.value
            val syncState = startupSyncPreferences.getState(profileId)
            val canUseWarmSync = !force &&
                lastPulledKey == pullKey(userId) &&
                lastPulledAtMs > 0L &&
                syncState.lastFullPullUserId == userId &&
                syncState.lastFullPullAtMs > 0L &&
                System.currentTimeMillis() - syncState.lastFullPullAtMs < FULL_STARTUP_PULL_TTL_MS &&
                (!includeProfileSettings || syncState.lastFullPullIncludedProfileSettings)

            if (canUseWarmSync) {
                return pullWarmRemoteData(
                    profileId = profileId,
                    userId = userId,
                    includeProfileSettings = includeProfileSettings
                )
            }

            Log.d(TAG, "Pulling remote data for profile $profileId")

            val snapshotRevision = nasSyncApi
                .pullChanges(profileId, syncStateStore.revision(profileId))
                .revision
            watchProgressSyncService.restoreLastPushTimestamp()
            watchedItemsSyncService.restoreLastPushTimestamp()
            // Migrate an existing device before applying the NAS snapshot. This
            // is what transfers the former Supabase/local history from the .150
            // instead of letting an incomplete NAS snapshot hide it forever.
            watchProgressSyncService.migrateExistingLocalSnapshotIfNeeded(profileId).getOrElse { throw it }
            watchProgressSyncService.flushPendingUpserts(profileId).getOrElse { throw it }
            pullPriorityRemoteData(
                profileId = profileId,
                includeProfileSettings = includeProfileSettings,
                warm = false
            )
            // The snapshot pull above is a complete read of the remote
            // profile.  Record the revision observed before the read only
            // after every domain has completed successfully.  This prevents
            // repeated full pulls while still leaving events created during
            // the read for the next cycle.
            syncStateStore.recordSuccess(profileId, "changes", snapshotRevision)
            startupSyncPreferences.markFullPull(
                profileId = profileId,
                userId = userId,
                includeProfileSettings = includeProfileSettings
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "Startup sync failed", e)
            return Result.failure(e)
        }
    }

    private suspend fun pullWarmRemoteData(
        profileId: Int,
        userId: String,
        includeProfileSettings: Boolean
    ): Result<Unit> {
        try {
            Log.d(TAG, "Running warm remote sync for profile $profileId")
            watchProgressSyncService.restoreLastPushTimestamp()
            watchedItemsSyncService.restoreLastPushTimestamp()
            pullPriorityRemoteData(
                profileId = profileId,
                includeProfileSettings = includeProfileSettings,
                warm = true
            )
            startupSyncPreferences.markFullPull(
                profileId = profileId,
                userId = userId,
                includeProfileSettings = includeProfileSettings
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "Warm startup sync failed", e)
            return Result.failure(e)
        }
    }

    private suspend fun pullPriorityRemoteData(
        profileId: Int,
        includeProfileSettings: Boolean,
        warm: Boolean
    ) {
        runPriorityStartupSync(
            pullProfileSettings = { },
            pullWatchState = {
                pullWatchedItemsDelta(profileId)
                syncWatchProgressDelta(
                    profileId = profileId,
                    pushUnsynced = true,
                    failureMessage = if (warm) {
                        "Failed to sync warm watch progress, continuing"
                    } else {
                        "Failed to sync watch progress, continuing"
                    }
                )
            },
            pullBroadData = {
                pullBroadRemoteData(profileId)
            }
        )
    }

    private suspend fun pullBroadRemoteData(profileId: Int) {
        // Profiles are pulled before priority data in pullRemoteData().  Keep
        // this phase limited to the remaining broad domains so a second
        // profile pull cannot race with profile-scoped merges.
        Log.d(TAG, "Remote profile directory already loaded")

        coroutineScope {
            val libraryJob = async {
                // The NAS copy is authoritative; never defer library synchronization to Trakt.
                libraryRepository.isSyncingFromRemote = true
                try {
                    val remoteLibraryItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Pulled ${remoteLibraryItems.size} library items from remote")
                    libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                    libraryRepository.hasCompletedInitialPull = true
                    Log.d(TAG, "Reconciled local library with ${remoteLibraryItems.size} remote items")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull library", e)
                    libraryRepository.hasCompletedInitialPull = true
                    throw e
                } finally {
                    libraryRepository.isSyncingFromRemote = false
                }
            }

            val pluginJob = async {
                pluginManager.isSyncingFromRemote = true
                try {
                    val remotePlugins = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
                    pluginManager.reconcileWithRemoteRepoUrls(
                        remotePlugins = remotePlugins,
                        removeMissingLocal = true
                    )
                    Log.d(TAG, "Pulled ${remotePlugins.size} plugin repos from remote for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull plugins from remote", e)
                    throw e
                } finally {
                    pluginManager.isSyncingFromRemote = false
                    pluginManager.flushPendingSync()
                }
            }

            val addonJob = async {
                addonRepository.isSyncingFromRemote = true
                try {
                    val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
                    addonRepository.reconcileWithRemoteAddonUrls(
                        remoteUrls = remoteAddonUrls,
                        removeMissingLocal = true
                    )
                    Log.d(TAG, "Pulled ${remoteAddonUrls.size} addons from remote for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull addons from remote", e)
                    throw e
                } finally {
                    addonRepository.isSyncingFromRemote = false
                }
            }

            val collectionJob = async {
                try {
                    val applied = collectionSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Collections pull completed for profile $profileId (applied=$applied)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull collections from remote", e)
                    throw e
                }
            }

            val homeCatalogJob = async {
                try {
                    val applied = homeCatalogSettingsSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Home catalog settings pull completed for profile $profileId (applied=$applied)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull home catalog settings from remote", e)
                    throw e
                }
            }

            pluginJob.await()
            addonJob.await()
            collectionJob.await()
            homeCatalogJob.await()
            libraryJob.await()
        }
    }

    private suspend fun pullWatchedItemsDelta(profileId: Int, failOnError: Boolean = false) {
        try {
            Log.d(TAG, "Starting watched items delta sync for profile $profileId")
            val watchedItemsResult = watchedItemsSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            Log.d(
                TAG,
                "Watched items sync applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes (snapshot=${watchedItemsResult.usedSnapshot})"
            )
            if (watchedItemsResult.preservedLocalItems) {
                Log.d(TAG, "Detected unsynced watched items, pushing to remote")
                watchedItemsSyncService.pushToRemote()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items, continuing with other syncs", e)
            if (failOnError) throw e
        }
    }

    private suspend fun pullChangedProfileBlobs(profileId: Int, keys: Set<String>) {
        for (key in keys) {
            when (key) {
                NAS_BLOB_LIBRARY -> {
                    libraryRepository.isSyncingFromRemote = true
                    try {
                        val remoteItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                        libraryPreferences.mergeRemoteItems(remoteItems)
                        libraryRepository.hasCompletedInitialPull = true
                    } finally {
                        libraryRepository.isSyncingFromRemote = false
                    }
                }
                NAS_BLOB_COLLECTIONS -> collectionSyncService.pullFromRemote().getOrElse { throw it }
                NAS_BLOB_HOME_CATALOG -> homeCatalogSettingsSyncService.pullFromRemote().getOrElse { throw it }
                NAS_BLOB_ADDONS -> {
                    addonRepository.isSyncingFromRemote = true
                    try {
                        val remoteUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
                        addonRepository.reconcileWithRemoteAddonUrls(remoteUrls, removeMissingLocal = true)
                    } finally {
                        addonRepository.isSyncingFromRemote = false
                    }
                }
                NAS_BLOB_PLUGINS -> {
                    pluginManager.isSyncingFromRemote = true
                    try {
                        val remoteUrls = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
                        pluginManager.reconcileWithRemoteRepoUrls(remoteUrls, removeMissingLocal = true)
                    } finally {
                        pluginManager.isSyncingFromRemote = false
                        pluginManager.flushPendingSync()
                    }
                }
                NAS_BLOB_AVATAR_CATALOG -> {
                    nasAvatarRepository.invalidateCache()
                    nasAvatarRepository.getAvatarCatalog()
                }
            }
        }
    }

    private suspend fun syncWatchProgressDelta(
        profileId: Int,
        pushUnsynced: Boolean,
        failureMessage: String
    ): Result<Unit> {
        watchProgressRepository.isSyncingFromRemote = true
        try {
            val syncResult = watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialPull = true
            Log.d(
                TAG,
                "Watch progress sync applied ${syncResult.upsertedEntries} upserts and ${syncResult.deletedEntries} deletes (snapshot=${syncResult.usedSnapshot})"
            )
            if (pushUnsynced && syncResult.preservedLocalItems) {
                Log.d(TAG, "Detected unsynced watch progress, pushing to remote")
                watchProgressSyncService.pushToRemote(profileId)
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, failureMessage, e)
            return Result.failure(e)
        } finally {
            watchProgressRepository.isSyncingFromRemote = false
        }
    }
}
