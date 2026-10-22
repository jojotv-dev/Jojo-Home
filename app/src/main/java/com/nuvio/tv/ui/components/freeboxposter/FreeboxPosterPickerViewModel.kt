package com.nuvio.tv.ui.components.freeboxposter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbPosterCandidate
import com.nuvio.tv.core.tmdb.TmdbArtworkType
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.FreeboxFrameThumbnailService
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxFolderTmdbSearchQuery
import com.nuvio.tv.data.freebox.freeboxLegacyContentIdFor
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.freebox.freeboxProgrammeOverrideKey
import com.nuvio.tv.data.freebox.freeboxTmdbSearchQuery
import com.nuvio.tv.data.local.FreeboxPosterOverrideDataStore
import com.nuvio.tv.data.local.SyncedVisualAssetCache
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FreeboxPosterPickerState(
    val entry: FreeboxFileEntry? = null,
    val posters: List<TmdbPosterCandidate> = emptyList(),
    val currentPosterUrl: String? = null,
    val currentBackdropUrl: String? = null,
    val artworkType: TmdbArtworkType = TmdbArtworkType.LANDSCAPE,
    val allowPortrait: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false
)

@HiltViewModel
class FreeboxPosterPickerViewModel @Inject constructor(
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val frameThumbnailService: FreeboxFrameThumbnailService,
    private val posterOverrideDataStore: FreeboxPosterOverrideDataStore,
    private val visualAssetCache: SyncedVisualAssetCache,
    private val watchProgressRepository: WatchProgressRepository,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache
) : ViewModel() {
    private val _state = MutableStateFlow(FreeboxPosterPickerState())
    val state: StateFlow<FreeboxPosterPickerState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var selectionGeneration = 0L

    fun open(
        entry: FreeboxFileEntry,
        currentPosterUrl: String?,
        currentBackdropUrl: String? = null,
        artworkType: TmdbArtworkType = TmdbArtworkType.LANDSCAPE,
        allowPortrait: Boolean = false
    ) {
        loadJob?.cancel()
        selectionGeneration += 1
        _state.value = FreeboxPosterPickerState(
            entry = entry,
            currentPosterUrl = currentPosterUrl,
            currentBackdropUrl = currentBackdropUrl,
            artworkType = artworkType,
            allowPortrait = allowPortrait,
            isLoading = true
        )
        val contentId = freeboxContentIdForEntry(entry)
        loadJob = viewModelScope.launch {
            val existingCandidates = listOfNotNull(
                currentPosterUrl?.takeIf { artworkType == TmdbArtworkType.PORTRAIT }?.let {
                    TmdbPosterCandidate(
                        url = it,
                        language = null,
                        title = "Image actuelle",
                        artworkType = TmdbArtworkType.PORTRAIT
                    )
                },
                currentBackdropUrl?.takeIf { artworkType == TmdbArtworkType.LANDSCAPE }?.let {
                    TmdbPosterCandidate(
                        url = it,
                        language = null,
                        title = "Fond actuel",
                        artworkType = TmdbArtworkType.LANDSCAPE
                    )
                }
            ).distinctBy { it.artworkType to it.url }
            _state.update { current ->
                if (current.entry?.let(::freeboxContentIdForEntry) != contentId) current
                else current.copy(
                    posters = existingCandidates,
                    // For a video, keep the loading state until the five-frame
                    // strip is ready instead of presenting a misleading single
                    // choice while extraction is still running.
                    isLoading = !entry.isDirectory
                )
            }
            val freeboxQuery = if (entry.isDirectory) {
                freeboxFolderTmdbSearchQuery(entry.name)
            } else {
                freeboxTmdbSearchQuery(entry.name)
            }
            coroutineScope {
                val frameDeferred = async {
                    if (entry.isDirectory || artworkType == TmdbArtworkType.PORTRAIT) {
                        return@async emptyList()
                    }
                    runCatching {
                        frameThumbnailService.thumbnailChoices(
                            entry = entry,
                            minimumCount = 5,
                        )
                    }
                        .getOrDefault(emptyList())
                        .mapIndexed { index, uri -> uri.toFramePosterCandidate(index + 1) }
                }
                val movieDeferred = async {
                    if (allowPortrait && artworkType == TmdbArtworkType.PORTRAIT) {
                        return@async emptyList()
                    }
                    runCatching {
                        tmdbService.fetchPosterCandidatesForTitleQuery(
                            query = freeboxQuery,
                            mediaTypeHint = "movie",
                            artworkType = artworkType,
                            durationMinutes = entry.durationMs?.takeIf { it > 0L }?.div(60_000L)?.toInt(),
                            yearHint = Regex("\\b(?:19|20)\\d{2}\\b").find(entry.name)?.value?.toIntOrNull()
                        )
                    }.getOrDefault(emptyList())
                }
                val tvDeferred = async {
                    runCatching {
                        tmdbService.fetchPosterCandidatesForTitleQuery(
                            query = freeboxQuery,
                            mediaTypeHint = "tv",
                            artworkType = artworkType,
                            durationMinutes = entry.durationMs?.takeIf { it > 0L }?.div(60_000L)?.toInt(),
                            yearHint = Regex("\\b(?:19|20)\\d{2}\\b").find(entry.name)?.value?.toIntOrNull()
                        )
                    }.getOrDefault(emptyList())
                }

                // TMDB and the first decoded frame are both allowed to dismiss
                // the spinner. Do not hide already available TMDB artwork while
                // the complete local frame strip is still being extracted.
                val tmdbPosters = (movieDeferred.await() + tvDeferred.await())
                    .distinctBy { it.artworkType to it.url }
                _state.update { current ->
                    if (current.entry?.let(::freeboxContentIdForEntry) != contentId) current
                    else {
                        val tmdbByArtwork = tmdbPosters.associateBy { it.artworkType to it.url }
                        val merged = (current.posters + tmdbPosters)
                            .distinctBy { it.artworkType to it.url }
                            .map { candidate ->
                                // If the current image is also returned by TMDB,
                                // keep TMDB's candidate because it carries the
                                // identity needed for synopsis/cast persistence.
                                tmdbByArtwork[candidate.artworkType to candidate.url] ?: candidate
                            }
                        current.copy(
                            posters = merged,
                            // Keep loading until a result for the selected
                            // artwork format arrives.
                            isLoading = merged.none { it.artworkType == artworkType }
                        )
                    }
                }

                val framePosters = frameDeferred.await()
                _state.update { current ->
                    if (current.entry?.let(::freeboxContentIdForEntry) != contentId) current
                    else current.copy(
                        posters = (current.posters + framePosters.filter {
                            it.artworkType == artworkType
                        }).distinctBy { it.artworkType to it.url },
                        isLoading = false
                    )
                }
            }
        }
    }

    fun changeArtworkType(artworkType: TmdbArtworkType) {
        val current = _state.value
        val entry = current.entry ?: return
        if (current.artworkType == artworkType ||
            (artworkType == TmdbArtworkType.PORTRAIT && !current.allowPortrait)
        ) return
        open(
            entry = entry,
            currentPosterUrl = current.currentPosterUrl,
            currentBackdropUrl = current.currentBackdropUrl,
            artworkType = artworkType,
            allowPortrait = current.allowPortrait
        )
    }

    fun select(poster: TmdbPosterCandidate) {
        val pickerState = _state.value
        val entry = pickerState.entry ?: return
        if (pickerState.isSaving) return
        val selectionToken = ++selectionGeneration
        loadJob?.cancel()
        // Dispose the dialog before changing Home artwork. This gives the TV
        // window one frame to restore the card focus and makes the click feel
        // immediate even when DataStore or NAS are busy.
        _state.value = FreeboxPosterPickerState()
        viewModelScope.launch {
            delay(50L)
            val savedLocally = runCatching {
                val contentId = freeboxContentIdForEntry(entry)
                val programmeKey = freeboxProgrammeOverrideKey(entry.name, entry.isDirectory)
                val artworkKeys = listOf(contentId, programmeKey)
                if (poster.artworkType == TmdbArtworkType.LANDSCAPE) {
                    posterOverrideDataStore.setBackdropForContentIds(artworkKeys, poster.url)
                } else {
                    posterOverrideDataStore.setForContentIds(artworkKeys, poster.url)
                }
                if (poster.tmdbId != null || poster.mediaType == "local") {
                    // Never let a previous selection's synopsis survive a new
                    // TMDB identity or a frame extracted from the recording.
                    posterOverrideDataStore.clearMetadataForContentIds(artworkKeys)
                }
                poster.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    posterOverrideDataStore.setOverviewForContentIds(artworkKeys, overview)
                }
                tmdbMetadataService.metadataIdentityForSelectedCandidate(poster)?.let { identity ->
                    // Persist the exact id before dismissing the dialog. If the
                    // app is closed while credits are still loading, the next
                    // launch can resume that exact lookup instead of guessing.
                    posterOverrideDataStore.setMetadataForContentIds(artworkKeys, identity)
                }
            }.isSuccess
            if (savedLocally) {
                // Artwork must reach the visible card before slow metadata
                // enrichment; otherwise a selection appears stuck for seconds.
                runCatching { updateExistingFreeboxProgressArtwork(entry, poster) }
                // The normal debounced settings observer performs one NAS push
                // after these writes settle. Explicit pushes here used to race
                // that observer and serialize up to three complete profile syncs.
                // The selected URL and TMDB identity are already persisted
                // above. Enrichment and NAS mirroring must not block the
                // visible choice: both can finish after Home has restored it.
                val metadataIds = listOf(
                    freeboxContentIdForEntry(entry),
                    freeboxProgrammeOverrideKey(entry.name, entry.isDirectory)
                )
                viewModelScope.launch {
                    if (selectionToken != selectionGeneration) return@launch
                    runCatching {
                        tmdbMetadataService.fetchMetadataForSelectedCandidate(poster)
                    }.getOrNull()?.let { metadata ->
                        if (selectionToken != selectionGeneration) return@let
                        posterOverrideDataStore.setMetadataForContentIds(
                            contentIds = metadataIds,
                            metadata = metadata
                        )
                        metadata.overview?.takeIf(String::isNotBlank)?.let { overview ->
                            posterOverrideDataStore.setOverviewForContentIds(metadataIds, overview)
                        }
                    }
                }
                // Finish the local copy before this selection coroutine ends.
                // The source is often a frame in the app cache; if the process
                // is killed while the previous fire-and-forget upload is still
                // running, the persisted override can point to an image that
                // no longer exists on the next launch.
                runCatching { visualAssetCache.cacheAndUpload(poster.url) }
            } else {
                _state.value = pickerState.copy(
                    isLoading = false,
                    isSaving = false,
                    saveFailed = true
                )
            }
        }
    }

    fun dismiss() {
        loadJob?.cancel()
        _state.value = FreeboxPosterPickerState()
    }

    private suspend fun updateExistingFreeboxProgressArtwork(
        entry: FreeboxFileEntry,
        poster: TmdbPosterCandidate
    ) {
        val contentId = freeboxContentIdForEntry(entry)
        val legacyContentId = freeboxLegacyContentIdFor(contentId)
        val path = freeboxPathFromContentId(contentId)
        val programmeKey = freeboxProgrammeOverrideKey(entry.name, entry.isDirectory)
        watchProgressRepository.allProgress.first()
            .filter { progress ->
                progress.contentId == contentId ||
                    progress.contentId == legacyContentId ||
                    freeboxPathFromContentId(progress.contentId) == path ||
                    (progress.contentId.startsWith("freebox:", ignoreCase = true) &&
                        freeboxProgrammeOverrideKey(progress.name, false) == programmeKey)
            }
            .forEach { progress ->
                val updated = if (poster.artworkType == TmdbArtworkType.LANDSCAPE) {
                    progress.copy(backdrop = poster.url)
                } else {
                    progress.copy(poster = poster.url)
                }
                // The shared artwork override is the synchronized authority.
                // Saving the local progress must not start a second remote sync
                // for every matching Continue Watching identity.
                watchProgressRepository.saveProgress(updated, syncRemote = false)
                cwEnrichmentCache.updateInProgressArtwork(
                    contentId = updated.contentId,
                    videoId = updated.videoId,
                    season = updated.season,
                    episode = updated.episode,
                    poster = updated.poster,
                    backdrop = updated.backdrop,
                    useBackdropAsThumbnail = poster.artworkType == TmdbArtworkType.LANDSCAPE
                )
            }
    }
}

private fun String.toFramePosterCandidate(index: Int): TmdbPosterCandidate {
    val title = "Image extraite de la vidéo $index"
    return TmdbPosterCandidate(
        url = this,
        language = null,
        title = title,
        mediaType = "local",
        artworkType = TmdbArtworkType.LANDSCAPE
    )
}
