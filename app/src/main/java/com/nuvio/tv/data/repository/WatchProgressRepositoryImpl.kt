package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.sync.NasIptvFavoritesApi
import android.util.Log
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.NasSyncStateStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.freebox.freeboxFileNameOnly
import com.nuvio.tv.data.freebox.freeboxTmdbSearchQuery
import com.nuvio.tv.data.freebox.freeboxVideoDisplayTitle
import com.nuvio.tv.data.freebox.freeboxLegacyContentIdFor
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.ProfileWatchProgressUpdate
import com.nuvio.tv.domain.repository.FreeboxContinueWatchingDeletion
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val nasSyncApi: NasIptvFavoritesApi,
    private val syncStateStore: NasSyncStateStore,
    private val continueWatchingEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
        private const val OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS = 3 * 60_000L
    }

    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?,
        val runtimeMs: Long = 0L
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>,
        val runtimeMs: Long = 0L
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydratedProgressIds = mutableSetOf<String>()
    private var syncJob: Job? = null
    private var watchedItemsSyncJob: Job? = null
    private val pendingWatchedItemsLock = Any()
    private val pendingWatchedItems = linkedMapOf<WatchedItemSyncKey, WatchedItem>()
    var isSyncingFromRemote = false
    var hasCompletedInitialPull = false
    var hasCompletedInitialWatchedItemsPull = false

    init {
        // A NAS account works without the legacy Nuvio account. Restore the current
        // profile as soon as the NAS session is available, before accepting pushes.
        syncScope.launch {
            combine(nasSyncApi.sessionTokenFlow, profileManager.activeProfileId) { token, profileId -> token to profileId }
                .distinctUntilChanged()
                .collectLatest { (token, profileId) ->
                    if (token.isNullOrBlank()) return@collectLatest
                    isSyncingFromRemote = true
                    try {
                        watchProgressSyncService.migrateExistingLocalSnapshotIfNeeded(profileId).getOrElse { throw it }
                        watchProgressSyncService.flushPendingUpserts(profileId).getOrElse { throw it }
                        val result = watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
                        hasCompletedInitialPull = true
                        if (result.preservedLocalItems) watchProgressSyncService.pushToRemote(profileId)
                    } catch (error: Exception) {
                        Log.w(TAG, "NAS watch progress restore failed for profile $profileId", error)
                    } finally {
                        isSyncingFromRemote = false
                    }
                }
        }
    }

    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())

    private val optimisticContinueWatchingUpdates = MutableSharedFlow<WatchProgress>(
        replay = 1,
        extraBufferCapacity = 16
    )
    private val optimisticContinueWatchingProfileUpdates = MutableSharedFlow<ProfileWatchProgressUpdate>(
        replay = 1,
        extraBufferCapacity = 16
    )
    private val freeboxContinueWatchingDeletions = MutableSharedFlow<FreeboxContinueWatchingDeletion>(
        // Home can be recreated while Player is still finishing cleanup. Replay
        // the last confirmed deletion so neither Videos nor Continue Watching
        // can resurrect the deleted recording on return.
        replay = 1,
        extraBufferCapacity = 16
    )
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val metadataHydrationLimit = 30

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        // Capture profile ID now so the delayed push targets the correct profile
        // even if the user switches profiles during the debounce window.
        val profileId = profileManager.activeProfileId.value
        syncJob?.cancel()
        syncJob = syncScope.launch {
            if (!nasSyncApi.hasSession()) return@launch
            delay(2000)
            withContext(NonCancellable) {
                watchProgressSyncService.pushToRemote(profileId)
            }
        }
    }

    private fun triggerWatchedItemsSync(items: Collection<WatchedItem>) {
        if (items.isEmpty()) return
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialWatchedItemsPull) return
        synchronized(pendingWatchedItemsLock) {
            items.forEach { item ->
                pendingWatchedItems[item.syncKey()] = item
            }
        }
        watchedItemsSyncJob?.cancel()
        watchedItemsSyncJob = syncScope.launch {
            if (!nasSyncApi.hasSession()) return@launch
            delay(2000)
            val batch = synchronized(pendingWatchedItemsLock) {
                pendingWatchedItems.values.toList().also {
                    pendingWatchedItems.clear()
                }
            }
            if (batch.isEmpty()) return@launch
            withContext(NonCancellable) {
                watchedItemsSyncService.pushItemsToRemote(batch)
            }
        }
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            syncScope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        contentId = contentId,
                        contentType = progress.contentType
                    ) ?: return@launch
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail,
                            runtimeMs = (video.runtime ?: 0).toLong() * 60_000L
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.backdropUrl,
                    logo = meta.logo,
                    episodes = episodes,
                    runtimeMs = parseRuntimeToMs(meta.runtime)
                )
            }
        }
        return null
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        val episodeRuntimeMs = episodeMeta?.runtimeMs ?: 0L
        val runtimeMs = episodeRuntimeMs.takeIf { it > 0 } ?: metadata.runtimeMs

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            duration = if (progress.duration > 0) progress.duration
                       else if (runtimeMs > 0) runtimeMs
                       else progress.duration,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    override fun observeRemoteProgressLoaded(): Flow<Boolean> {
        return flowOf(true)
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = watchProgressPreferences.allProgress
            .onEach { items ->
                val needsArtwork = items.filter {
                    it.poster == null && it.backdrop == null && it.contentId !in hydratedProgressIds
                }
                if (needsArtwork.isNotEmpty()) {
                    syncScope.launch { hydrateProgressArtwork(needsArtwork) }
                }
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list ->
            list.filter { progress ->
                progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
            }
        }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return watchProgressPreferences.getProgress(contentId)
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return watchProgressPreferences.getAllEpisodeProgress(contentId)
    }

    override fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> {
        return flowOf(emptyList())
    }

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> {
        // Watched items are paginated and synchronized with NAS, unlike the
        // bounded playback table. They are the canonical source for next-up.
        return combine(
                        watchedItemsPreferences.allItems,
                        layoutPreferenceDataStore.nextUpFromFurthestEpisode
                    ) { items, useFurthest ->
                        items
                            .filter { item ->
                                (item.contentType.equals("series", ignoreCase = true) ||
                                    item.contentType.equals("tv", ignoreCase = true)) &&
                                    item.season != null &&
                                    item.episode != null &&
                                    item.season != 0 &&
                                    !isMalformedNextUpSeedContentId(item.contentId)
                            }
                            .groupBy { it.contentId }
                            .mapNotNull { (_, episodes) ->
                                val latest = episodes.maxWithOrNull(
                                    if (useFurthest) {
                                        compareBy<WatchedItem> { it.season ?: 0 }
                                            .thenBy { it.episode ?: 0 }
                                            .thenBy { it.watchedAt }
                                    } else {
                                        compareBy<WatchedItem> { it.watchedAt }
                                            .thenBy { it.season ?: 0 }
                                            .thenBy { it.episode ?: 0 }
                                    }
                                ) ?: return@mapNotNull null
                                WatchProgress(
                                    contentId = latest.contentId,
                                    contentType = latest.contentType,
                                    name = latest.title,
                                    poster = null,
                                    backdrop = null,
                                    logo = null,
                                    videoId = latest.contentId,
                                    season = latest.season,
                                    episode = latest.episode,
                                    episodeTitle = null,
                                    position = 1L,
                                    duration = 1L,
                                    lastWatched = latest.watchedAt,
                                    progressPercent = 100f
                                )
                            }
                    }.flowOn(Dispatchers.Default)
            .distinctUntilChanged()
    }

    private fun isOptimisticNextUpSeedCandidate(
        progress: WatchProgress,
        nowMs: Long
    ): Boolean {
        if (!progress.contentType.equals("series", ignoreCase = true)) return false
        if (!progress.isCompleted()) return false
        if (progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK) return false
        if (progress.season == null || progress.episode == null || progress.season == 0) return false
        val ageMs = nowMs - progress.lastWatched
        return ageMs in 0..OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS
    }

    private fun mergeNextUpSeeds(
        canonicalSeeds: List<WatchProgress>,
        optimisticSeeds: List<WatchProgress>
    ): List<WatchProgress> {
        val merged = linkedMapOf<String, WatchProgress>()
        canonicalSeeds.forEach { seed ->
            merged[nextUpSeedKey(seed)] = seed
        }
        optimisticSeeds.forEach { seed ->
            val key = nextUpSeedKey(seed)
            val existing = merged[key]
            if (existing == null || shouldReplaceNextUpSeed(existing, seed)) {
                merged[key] = seed
            }
        }
        return merged.values.sortedByDescending { it.lastWatched }
    }

    private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lowered = trimmed.lowercase()
        return lowered == "tmdb" ||
            lowered == "imdb" ||
            lowered == "trakt" ||
            lowered == "tmdb:" ||
            lowered == "imdb:" ||
            lowered == "trakt:"
    }

    private fun nextUpSeedKey(progress: WatchProgress): String {
        return progress.traktShowId?.let { "trakt_show:$it" }
            ?: progress.contentId.trim()
    }

    private fun shouldReplaceNextUpSeed(
        existing: WatchProgress,
        candidate: WatchProgress
    ): Boolean {
        val candidateSeason = candidate.season ?: -1
        val candidateEpisode = candidate.episode ?: -1
        val existingSeason = existing.season ?: -1
        val existingEpisode = existing.episode ?: -1
        return candidateSeason > existingSeason ||
            (
                candidateSeason == existingSeason &&
                    (
                        candidateEpisode > existingEpisode ||
                            (
                                candidateEpisode == existingEpisode &&
                                    candidate.lastWatched >= existing.lastWatched
                                )
                        )
                )
    }

    override fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress> {
        return optimisticContinueWatchingUpdates
    }

    override fun observeOptimisticContinueWatchingProfileUpdates(): Flow<ProfileWatchProgressUpdate> {
        return optimisticContinueWatchingProfileUpdates
    }

    override fun observeFreeboxContinueWatchingDeletions(): Flow<FreeboxContinueWatchingDeletion> =
        freeboxContinueWatchingDeletions

    override suspend fun notifyFreeboxContinueWatchingDeletion(
        deletion: FreeboxContinueWatchingDeletion
    ) {
        freeboxContinueWatchingDeletions.emit(deletion)
    }

    override suspend fun remapEpisodeSeed(progress: WatchProgress): WatchProgress {
        return progress
    }

    @OptIn(FlowPreview::class)
    override fun observeWatchedMovieIds(): Flow<Set<String>> {
        return combine(
                        watchProgressPreferences.allProgress,
                        watchedItemsPreferences.allItems
                    ) { progressList, watchedItems ->
                        val completedIds = mutableSetOf<String>()
                        val replayingIds = mutableSetOf<String>()
                        for (progress in progressList) {
                            if (progress.isCompleted()) {
                                completedIds.add(progress.contentId)
                            } else if (progress.position > 0L ||
                                progress.progressPercent?.let { it > 0f } == true
                            ) {
                                replayingIds.add(progress.contentId)
                            }
                        }
                        val watchedItemIds = watchedItems
                            .filter { it.season == null && it.episode == null }
                            .map { it.contentId }
                            .toSet()
                        (completedIds + watchedItemIds) - replayingIds
                    }.debounce(500)
            .distinctUntilChanged()
    }

    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        return watchedItemsPreferences.allItems.first()
            .filter { it.season != null && it.episode != null }
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.map { it.season!! to it.episode!! }.toSet() }
    }

    override suspend fun getShowIdSiblings(): Map<String, Set<String>> = emptyMap()

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> {
        val progressFlow = if (season != null && episode != null) {
            watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
        } else {
            watchProgressPreferences.getProgress(contentId)
        }
        return combine(
            progressFlow,
            watchedItemsPreferences.isWatched(contentId, season, episode)
        ) { progressEntry, itemWatched ->
            val hasStartedReplay = progressEntry?.let { entry ->
                !entry.isCompleted() &&
                    (entry.position > 0L || entry.progressPercent?.let { it > 0f } == true)
            } == true
            if (hasStartedReplay) false else (progressEntry?.isCompleted() == true) || itemWatched
        }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        // Capture profile ID now so async operations target the correct profile.
        val profileId = profileManager.activeProfileId.value
        // A player may update a Freebox position without presentation metadata.
        // Do not resurrect a previous resume image or synopsis in that case:
        // those fields can have come from a different recording and were then
        // repeatedly re-published to Home and the NAS.
        val persistedProgress = preserveExistingFreeboxPresentation(progress)
        val freeboxUpsert = if (persistedProgress.contentId.startsWith("freebox:", ignoreCase = true)) {
            watchProgressPreferences.saveFreeboxProgressReplacingEquivalent(persistedProgress)
        } else {
            watchProgressPreferences.saveProgress(persistedProgress)
            null
        }
        if (freeboxUpsert?.accepted == false) return
        val pendingKey = progressKey(persistedProgress)
        val replacedRemoteKeys = freeboxUpsert?.removedRemoteKeys.orEmpty()
        if (replacedRemoteKeys.isNotEmpty()) {
            // Keep the old path-only/fingerprinted identity from returning on a
            // later NAS pull after the local duplicate has been replaced.
            watchProgressPreferences.enqueuePendingRemoteDeletes(replacedRemoteKeys, profileId)
            if (syncRemote && nasSyncApi.hasSession()) {
                syncScope.launch(NonCancellable) {
                    val result = watchProgressSyncService.deleteFromRemote(replacedRemoteKeys.toList())
                    if (result.isSuccess) {
                        watchProgressPreferences.acknowledgeRemoteDeletes(replacedRemoteKeys, profileId)
                    } else {
                        Log.w(TAG, "Freebox duplicate progress delete deferred until the next NAS sync", result.exceptionOrNull())
                    }
                }
            }
        }
        if (syncRemote) syncStateStore.enqueueProgressUpsert(profileId, pendingKey)
        if (persistedProgress.contentId.startsWith("freebox:", ignoreCase = true)) {
            Log.d(
                TAG,
                "FREEBOX_PROGRESS_SAVED id=${persistedProgress.contentId.hashCode()} " +
                    "position=${persistedProgress.position} duration=${persistedProgress.duration} " +
                    "percent=${persistedProgress.progressPercentage} completed=${persistedProgress.isCompleted()} " +
                    "inProgress=${persistedProgress.isInProgress()}"
            )
        }
        // Update the active home immediately. DataStore will still publish the
        // persisted value, but that emission can arrive after returning from the
        // player on slower Android TV devices.
        optimisticContinueWatchingUpdates.tryEmit(persistedProgress)
        optimisticContinueWatchingProfileUpdates.tryEmit(ProfileWatchProgressUpdate(profileId, persistedProgress))

        if (syncRemote && nasSyncApi.hasSession()) {
            syncScope.launch(NonCancellable) {
                watchProgressSyncService.pushSingleToRemote(pendingKey, persistedProgress, profileId)
                    .onFailure { error ->
                        Log.w(TAG, "Failed single progress push; falling back to full sync next cycle", error)
                    }
            }
        }

        if (persistedProgress.isCompleted()) {
            val watchedItem = persistedProgress.toWatchedItem()
            watchedItemsPreferences.markAsWatched(watchedItem)
            if (syncRemote && nasSyncApi.hasSession()) {
                triggerWatchedItemsSync(listOf(watchedItem))
            }
        }
    }

    private suspend fun preserveExistingFreeboxPresentation(incoming: WatchProgress): WatchProgress {
        if (!incoming.contentId.startsWith("freebox:", ignoreCase = true)) return incoming
        val existing = watchProgressPreferences.getProgress(incoming.contentId).first() ?: return incoming
        return incoming.copy(
            name = incoming.name.takeIf { it.isNotBlank() } ?: existing.name,
            poster = incoming.poster,
            backdrop = incoming.backdrop,
            logo = incoming.logo,
            description = incoming.description?.takeIf { it.isNotBlank() },
            episodeTitle = incoming.episodeTitle ?: existing.episodeTitle
        )
    }

    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        if (progressList.isEmpty()) return
        watchProgressPreferences.saveProgressBatch(progressList)
        val profileId = profileManager.activeProfileId.value
        if (syncRemote) progressList.forEach { syncStateStore.enqueueProgressUpsert(profileId, progressKey(it)) }

        if (syncRemote && nasSyncApi.hasSession()) {
            triggerRemoteSync()
        }

        val completedWatchedItems = progressList
            .filter { it.isCompleted() }
            .map { progress -> progress.toWatchedItem() }
        if (completedWatchedItems.isNotEmpty()) {
            watchedItemsPreferences.markAsWatchedBatch(completedWatchedItems)
            if (syncRemote && nasSyncApi.hasSession()) {
                triggerWatchedItemsSync(completedWatchedItems)
            }
        }
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode)
        watchProgressPreferences.removeProgress(contentId, season, episode)
        removeFromPublishedContinueWatchingSnapshot(contentId, season, episode, profileId)
        // A later full push only upserts entries, so preserve deletion intent
        // until the NAS has acknowledged the corresponding tombstones.
        watchProgressPreferences.enqueuePendingRemoteDeletes(remoteDeleteKeys)
        if (nasSyncApi.hasSession() && remoteDeleteKeys.isNotEmpty()) {
            // Local state and the launcher bridge are already authoritative at
            // this point. Do not keep a confirmation dialog waiting on NAS I/O;
            // the durable tombstones above make this retry-safe.
            syncScope.launch {
                val result = watchProgressSyncService.deleteFromRemote(remoteDeleteKeys)
                if (result.isSuccess) watchProgressPreferences.acknowledgeRemoteDeletes(remoteDeleteKeys)
                else Log.w(TAG, "removeProgress remote delete deferred until the next NAS sync", result.exceptionOrNull())
            }
        }
        triggerRemoteSync()
    }

    override suspend fun removeFromHistory(contentId: String, videoId: String?, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode)
        watchProgressPreferences.removeProgress(contentId, season, episode)
        removeFromPublishedContinueWatchingSnapshot(contentId, season, episode, profileId)
        watchedItemsPreferences.unmarkAsWatched(contentId, season, episode)
        watchProgressPreferences.enqueuePendingRemoteDeletes(remoteDeleteKeys)
        if (nasSyncApi.hasSession() && remoteDeleteKeys.isNotEmpty()) {
            val result = watchProgressSyncService.deleteFromRemote(remoteDeleteKeys)
            if (result.isSuccess) watchProgressPreferences.acknowledgeRemoteDeletes(remoteDeleteKeys)
            else Log.w(TAG, "removeFromHistory remote delete deferred until the next NAS sync", result.exceptionOrNull())
        }
        if (nasSyncApi.hasSession()) {
            watchedItemsSyncService.deleteFromRemote(contentId, season, episode)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory watched item remote delete failed", error)
                }
        }
        triggerRemoteSync()
    }

    override suspend fun removeFromHistoryBatch(
        contentId: String,
        videoId: String?,
        episodes: List<Pair<Int, Int>>
    ) {
        if (episodes.isEmpty()) return
        // Batch local removes (single DataStore transaction each)
        watchProgressPreferences.removeProgressBatch(contentId, episodes)
        removeFromPublishedContinueWatchingSnapshot(
            contentId = contentId,
            season = null,
            episode = null,
            profileId = profileManager.activeProfileId.value
        )
        watchedItemsPreferences.unmarkAsWatchedBatch(contentId, episodes)

        val remoteDeleteKeys = episodes.flatMap { (season, episode) ->
            listOf("${contentId}_s${season}e${episode}")
        } + contentId
        watchProgressPreferences.enqueuePendingRemoteDeletes(remoteDeleteKeys)
        if (nasSyncApi.hasSession() && remoteDeleteKeys.isNotEmpty()) {
            val result = watchProgressSyncService.deleteFromRemote(remoteDeleteKeys.distinct())
            if (result.isSuccess) watchProgressPreferences.acknowledgeRemoteDeletes(remoteDeleteKeys)
            else Log.w(TAG, "removeFromHistoryBatch remote delete deferred until the next NAS sync", result.exceptionOrNull())
        }
        if (nasSyncApi.hasSession()) {
            watchedItemsSyncService.deleteFromRemoteBatch(contentId, episodes)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistoryBatch watched item remote delete failed", error)
                }
        }
        triggerRemoteSync()
    }

    override suspend fun markAsCompleted(progress: WatchProgress) {
        watchProgressPreferences.markAsCompleted(progress)
        val watchedItem = progress.toWatchedItem()
        watchedItemsPreferences.markAsWatched(watchedItem)
        triggerRemoteSync()
        triggerWatchedItemsSync(listOf(watchedItem))
    }

    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val now = System.currentTimeMillis()
        watchProgressPreferences.markAsCompletedBatch(progressList)
        val watchedItems = progressList.map { progress -> progress.toWatchedItem(watchedAt = now) }
        watchedItemsPreferences.markAsWatchedBatch(watchedItems)
        triggerRemoteSync()
        triggerWatchedItemsSync(watchedItems)
    }

    private fun WatchProgress.toWatchedItem(watchedAt: Long = System.currentTimeMillis()): WatchedItem =
        WatchedItem(
            contentId = contentId,
            contentType = contentType,
            title = name,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

    private fun WatchedItem.syncKey(): WatchedItemSyncKey =
        WatchedItemSyncKey(contentId, season, episode)

    private data class WatchedItemSyncKey(
        val contentId: String,
        val season: Int?,
        val episode: Int?
    )

    override suspend fun clearAll() {
        watchProgressPreferences.clearAll()
    }

    override fun isDroppedShow(contentId: String?): Boolean {
        return false
    }

    override suspend fun isTraktProgressActive(): Boolean = false

    private suspend fun hydrateProgressArtwork(items: List<WatchProgress>) {
        items.take(10).forEach { progress ->
            hydratedProgressIds.add(progress.contentId)
            runCatching {
                if (progress.isFreeboxProgress()) {
                    val rawFreeboxName = progress.name
                        .takeUnless { it.isLikelyGeneratedFreeboxArtworkLabel() }
                        ?: freeboxPathFromContentId(progress.contentId)
                            .takeUnless { it.isLikelyGeneratedFreeboxArtworkLabel() }
                        ?: freeboxPathFromContentId(progress.videoId)
                            .takeUnless { it.isLikelyGeneratedFreeboxArtworkLabel() }
                        ?: progress.videoId
                    val fileName = freeboxFileNameOnly(rawFreeboxName)
                    val tmdbImages = tmdbService.fetchImagesForTitleQuery(
                        query = freeboxTmdbSearchQuery(fileName),
                        mediaTypeHint = "movie"
                    )
                    // The artwork saved by the launching card is authoritative.
                    // Metadata only fills a genuinely missing slot.
                    val newPoster = progress.poster?.takeIf { it.isNotBlank() }
                        ?: tmdbImages?.posterUrl?.takeIf(String::isNotBlank)
                    val newBackdrop = progress.backdrop?.takeIf { it.isNotBlank() }
                        ?: tmdbImages?.backdropUrl?.takeIf(String::isNotBlank)
                    val updated = progress.copy(
                        name = fileName,
                        poster = newPoster,
                        backdrop = newBackdrop
                    )
                    if (updated != progress) {
                        watchProgressPreferences.saveProgress(updated)
                    }
                    return@runCatching
                }

                val metadata = fetchContentMetadata(
                    contentId = progress.contentId,
                    contentType = progress.contentType
                ) ?: return@runCatching
                val episodeRuntimeMs = if (progress.season != null && progress.episode != null)
                    metadata.episodes[progress.season to progress.episode]?.runtimeMs ?: 0L
                else 0L
                val durationMs = progress.duration.takeIf { it > 0 }
                    ?: episodeRuntimeMs.takeIf { it > 0 }
                    ?: metadata.runtimeMs

                // If addon returned no backdrop or poster, fall back to TMDB
                var backdropToSave = progress.backdrop ?: metadata.backdrop
                var posterToSave = progress.poster ?: metadata.poster
                if (backdropToSave == null && posterToSave == null) {
                    val tmdbImages = tmdbService.fetchImdbImages(progress.contentId, progress.contentType)
                    backdropToSave = tmdbImages?.backdropUrl
                    posterToSave = tmdbImages?.posterUrl
                }

                val hasNewData = posterToSave != null || backdropToSave != null
                    || metadata.logo != null || durationMs > 0
                if (hasNewData) {
                    watchProgressPreferences.saveProgress(
                        progress.copy(
                            poster = posterToSave,
                            backdrop = backdropToSave,
                            logo = progress.logo ?: metadata.logo,
                            name = progress.name.takeIf { it.isNotBlank() && it != progress.contentId }
                                ?: metadata.name ?: progress.name,
                            duration = if (durationMs > 0) durationMs else progress.duration
                        )
                    )
                }
            }.onFailure { Log.w(TAG, "Progress artwork hydration failed for ${progress.contentId}", it) }
        }
    }

    private fun WatchProgress.isFreeboxProgress(): Boolean =
        contentId.startsWith("freebox:") || videoId.startsWith("freebox:") || contentType.equals("freebox", ignoreCase = true)

    private fun String.isLikelyGeneratedFreeboxArtworkLabel(): Boolean {
        val value = trim()
        return value.isBlank() || value.matches(Regex("\\d{10,}"))
    }

    private fun parseRuntimeToMs(raw: String?): Long {
        val minutes = raw?.trim()?.toLongOrNull() ?: return 0L
        return minutes * 60_000L
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private suspend fun resolveRemoteDeleteKeys(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<String> {
        val rawEntries = watchProgressPreferences.getAllRawEntries()
        val keys = if (season != null && episode != null) {
            listOf("${contentId}_s${season}e${episode}", contentId)
        } else {
            val matchingLocalKeys = rawEntries
                .filter { (key, progress) ->
                    key == contentId ||
                        key.startsWith("${contentId}_") ||
                        isSameFreeboxProgressTarget(key, progress, contentId)
                }
                .keys
            matchingLocalKeys + contentId
        }
        val freeboxLegacyKeys = if (contentId.startsWith("freebox:", ignoreCase = true)) {
            val legacyContentId = freeboxLegacyContentIdFor(contentId)
            if (season != null && episode != null) {
                listOf("${legacyContentId}_s${season}e${episode}", legacyContentId)
            } else {
                listOf(legacyContentId)
            }
        } else {
            emptyList()
        }
        val resolvedKeys = (keys + freeboxLegacyKeys)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return resolvedKeys
    }

    private suspend fun removeFromPublishedContinueWatchingSnapshot(
        contentId: String,
        season: Int?,
        episode: Int?,
        profileId: Int
    ) {
        val cached = continueWatchingEnrichmentCache.getInProgressSnapshot(profileId)
        val targetFreeboxPath = contentId
            .takeIf { it.startsWith("freebox:", ignoreCase = true) }
            ?.let(::freeboxPathFromContentId)
            .orEmpty()
        val filtered = cached.filterNot { item ->
            val sameContent = item.contentId == contentId ||
                (targetFreeboxPath.isNotBlank() &&
                    item.contentId.startsWith("freebox:", ignoreCase = true) &&
                    freeboxPathFromContentId(item.contentId) == targetFreeboxPath)
            sameContent &&
                (season == null || item.season == season) &&
                (episode == null || item.episode == episode)
        }
        // Write even when both lists are empty. This initializes an authoritative
        // empty bridge snapshot and prevents stale Preview/Watch Next fallback.
        continueWatchingEnrichmentCache.saveInProgressSnapshot(
            items = filtered,
            force = true,
            profileId = profileId
        )
    }

    private fun isSameFreeboxProgressTarget(
        key: String,
        progress: WatchProgress,
        targetContentId: String
    ): Boolean {
        if (!targetContentId.startsWith("freebox:", ignoreCase = true)) return false
        val targetPath = freeboxPathFromContentId(targetContentId)
        if (targetPath.isBlank()) return false
        val keyPath = if (key.startsWith("freebox:", ignoreCase = true)) {
            freeboxPathFromContentId(key.withoutEpisodeProgressSuffix())
        } else {
            null
        }
        val progressContentPath = progress.contentId
            .takeIf { it.startsWith("freebox:", ignoreCase = true) }
            ?.let(::freeboxPathFromContentId)
        val progressVideoPath = progress.videoId
            .takeIf { it.startsWith("freebox:", ignoreCase = true) }
            ?.let(::freeboxPathFromContentId)
        return keyPath == targetPath ||
            progressContentPath == targetPath ||
            progressVideoPath == targetPath
    }

    private fun String.withoutEpisodeProgressSuffix(): String =
        replace(Regex("_s\\d+e\\d+$"), "")

}



