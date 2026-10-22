package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.FreeboxResumeDeletionService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.FreeboxVideoMeta
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.HomeContentSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.FreeboxFrameThumbnailService
import com.nuvio.tv.data.freebox.FreeboxOsClient
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxContentIdForPath
import com.nuvio.tv.data.freebox.freeboxDurationMsFromName
import com.nuvio.tv.data.freebox.freeboxFileNameOnly
import com.nuvio.tv.data.freebox.freeboxHasFileFingerprint
import com.nuvio.tv.data.freebox.freeboxLegacyContentIdFor
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.freebox.freeboxProgrammeOverrideKey
import com.nuvio.tv.data.local.FreeboxSettingsDataStore
import com.nuvio.tv.data.local.FreeboxConnectionSettings
import com.nuvio.tv.data.local.FreeboxDeletedItemsDataStore
import com.nuvio.tv.data.local.FreeboxArtworkCacheDataStore
import com.nuvio.tv.data.local.FreeboxPosterOverrideDataStore
import com.nuvio.tv.data.local.FREEBOX_SHARED_MEDIA_PROFILE_ID
import com.nuvio.tv.data.video.VideoFrameThumbnailService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.ThumbnailSize
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.data.freebox.FreeboxEpgProgram
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import java.text.Normalizer
import kotlin.math.abs
import javax.inject.Inject

private const val FREEBOX_HOME_VIDEOS_PATH = "/Freebox/Vid\u00e9os"
private val FREEBOX_HOME_VIDEO_EXTENSIONS = setOf(
    "3g2", "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg",
    "mpg", "mts", "ts", "webm", "wmv"
)
private val FREEBOX_HOME_SEASON_EPISODE_PATTERN = Regex("\\b[sS]\\d{1,2}[eE]\\d{1,2}\\b")

private fun normalizeFreeboxProgrammeTitle(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private fun isUsableFreeboxMetadataQuery(value: String): Boolean {
    val normalized = normalizeFreeboxProgrammeTitle(value)
    if (normalized.length < 4) return false
    return normalized !in setOf(
        "video",
        "videos",
        "enregistrement",
        "enregistrements",
        "recording",
        "recordings",
        "freebox"
    )
}

/**
 * A Freebox EPG match identifies the exact broadcast.  It must therefore be
 * kept intact: a title-only TMDB search can resolve to an unrelated programme.
 * TMDB is a fallback only when Freebox OS has no EPG match at all.
 */
internal fun mergeFreeboxVideoMetadata(
    epg: FreeboxVideoMeta?,
    tmdb: FreeboxVideoMeta?
): FreeboxVideoMeta? {
    return epg ?: tmdb
}

/**
 * Keep the exact EPG programme as the recording identity, while filling fields
 * that Freebox OS does not expose (ratings, runtime and credits) from a
 * high-confidence TMDB title match.
 */
internal fun enrichFreeboxVideoMetadata(
    epg: FreeboxVideoMeta?,
    tmdb: FreeboxVideoMeta?
): FreeboxVideoMeta? {
    if (epg == null) return tmdb
    if (tmdb == null) return epg
    return epg.copy(
        tmdbId = tmdb.tmdbId,
        mediaType = tmdb.mediaType,
        posterUrl = epg.posterUrl ?: tmdb.posterUrl,
        // Freebox EPG artwork is often a portrait programme thumbnail. TMDB
        // is the authoritative source for the landscape backdrop used by
        // Films/Séries, while the EPG poster remains available for details.
        backdropUrl = tmdb.backdropUrl ?: epg.backdropUrl,
        overview = epg.overview ?: tmdb.overview,
        voteAverage = epg.voteAverage ?: tmdb.voteAverage,
        year = epg.year ?: tmdb.year,
        genres = epg.genres.ifEmpty { tmdb.genres },
        runtimeMinutes = epg.runtimeMinutes ?: tmdb.runtimeMinutes,
        cast = epg.cast.ifEmpty { tmdb.cast },
        director = epg.director.ifEmpty { tmdb.director },
        seasonCount = epg.seasonCount ?: tmdb.seasonCount,
        logoUrl = epg.logoUrl ?: tmdb.logoUrl,
        castMembers = epg.castMembers.ifEmpty { tmdb.castMembers },
        artworkCandidates = epg.artworkCandidates.ifEmpty { tmdb.artworkCandidates }
    )
}

internal fun freeboxTmdbMediaTypeHint(fileName: String, programme: FreeboxEpgProgram?): String {
    if (FREEBOX_HOME_SEASON_EPISODE_PATTERN.containsMatchIn(fileName)) return "tv"
    if (programme == null) return "unknown"
    val epgKind = listOfNotNull(programme.genre, programme.category)
        .joinToString(" ")
        .lowercase(java.util.Locale.ROOT)
    return if (
        Regex("""\b(film|cinema|cinéma|movie|telefilm|téléfilm)\b""")
            .containsMatchIn(epgKind)
    ) {
        "movie"
    } else {
        // A native EPG identity describes a television programme. This avoids
        // resolving a same-title cinema result when movie and TV scores tie.
        "tv"
    }
}

private data class FreeboxPresentationOverrideSnapshot(
    val posters: Map<String, String>,
    val backdrops: Map<String, String>,
    val overviews: Map<String, String>,
    val metadata: Map<String, FreeboxVideoMeta>
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext internal val appContext: Context,
    internal val addonRepository: AddonRepository,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val collectionsDataStore: CollectionsDataStore,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val tmdbSettingsDataStore: TmdbSettingsDataStore,
    internal val mdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val traktSettingsDataStore: HomeContentSettingsDataStore,
    internal val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val mdbListRepository: MDBListRepository,
    internal val trailerService: TrailerService,
    internal val watchedItemsPreferences: WatchedItemsPreferences,
    internal val freeboxOsClient: FreeboxOsClient,
    internal val freeboxFrameThumbnailService: FreeboxFrameThumbnailService,
    internal val videoFrameThumbnailService: VideoFrameThumbnailService,
    internal val freeboxSettingsDataStore: FreeboxSettingsDataStore,
    internal val freeboxDeletedItemsDataStore: FreeboxDeletedItemsDataStore,
    internal val freeboxDurationCacheDataStore: com.nuvio.tv.data.local.FreeboxDurationCacheDataStore,
    internal val freeboxArtworkCacheDataStore: FreeboxArtworkCacheDataStore,
    internal val freeboxPosterOverrideDataStore: FreeboxPosterOverrideDataStore,
    internal val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    internal val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    internal val tvRecommendationManager: TvRecommendationManager,
    private val startupSyncService: StartupSyncService,
    private val freeboxResumeDeletionService: FreeboxResumeDeletionService
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        internal const val STARTUP_GRACE_PERIOD_MS = 1_500L
        internal const val CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS = 1_000L
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 8
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 8
        private const val MAX_FREEBOX_DURATION_PROBES = 2
        // A recurring title can appear several times in the EPG week. Never use
        // an episode from another broadcast as its metadata.
        private const val MAX_FREEBOX_EPG_MATCH_DISTANCE_MS = 8L * 60L * 60L * 1000L
        private val FREEBOX_DURATION_RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L)
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val freeboxDurationProbeSemaphore = Semaphore(MAX_FREEBOX_DURATION_PROBES)
    private val freeboxDurationProbesInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile internal var freeboxAutomaticArtwork: Map<String, String> = emptyMap()
    @Volatile internal var freeboxAutomaticBackdrops: Map<String, String> = emptyMap()
    @Volatile private var freeboxAutomaticMetadata: Map<String, FreeboxVideoMeta> = emptyMap()
    @Volatile internal var selectedMetadataOverrides: Map<String, FreeboxVideoMeta> = emptyMap()
    private val selectedMetadataHydrationAttempts = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile internal var freeboxPosterOverrides: Map<String, String> = emptyMap()
    @Volatile private var freeboxObservationStarted = false
    @Volatile private var freeboxArtworkObserversStarted = false
    @Volatile private var deferredHomeObserversStarted = false
    private var freeboxBridgeArtworkJob: Job? = null


    /** Lets work that competes with composition start only after the first Home frame is visible. */
    fun notifyHomeRendered(loadFreeboxVideos: Boolean = true) {
        startupSyncService.notifyHomeRendered()
        startDeferredHomeObservers()
        if (loadFreeboxVideos) {
            startFreeboxArtworkObservers()
            if (!freeboxObservationStarted) {
                freeboxObservationStarted = true
                observeFreeboxVideos()
            }
        }
    }
    @Volatile internal var freeboxOverviewOverrides: Map<String, String> = emptyMap()

    /**
     * Presentation resolved by Home for one exact Freebox recording.  Unlike a
     * Continue Watching snapshot, every value here is keyed by its Freebox
     * path, so it is safe to pass straight to the player on resume.
     */
    internal fun freeboxPresentationForPlayback(contentId: String): FreeboxVideoMeta? {
        val expectedPath = freeboxPathFromContentId(contentId)
        fun <T> valueFor(values: Map<String, T>): T? =
            values[contentId] ?: values.entries.firstOrNull { (key, _) ->
                freeboxPathFromContentId(key) == expectedPath
            }?.value

        val selectedMetadata = valueFor(selectedMetadataOverrides)
        val metadata = selectedMetadata ?: valueFor(freeboxAutomaticMetadata)
        val poster = valueFor(freeboxPosterOverrides)
            ?: valueFor(freeboxAutomaticArtwork)
            ?: metadata?.posterUrl
        val backdrop = valueFor(freeboxBackdropOverrides)
            ?: valueFor(freeboxAutomaticBackdrops)
            ?: metadata?.backdropUrl
        val overview = if (selectedMetadata != null) {
            selectedMetadata.overview
        } else {
            valueFor(freeboxOverviewOverrides) ?: metadata?.overview
        }

        return metadata?.copy(
            posterUrl = poster,
            backdropUrl = backdrop,
            overview = overview
        ) ?: if (!poster.isNullOrBlank() || !backdrop.isNullOrBlank() || !overview.isNullOrBlank()) {
            FreeboxVideoMeta(
                tmdbId = null,
                mediaType = null,
                posterUrl = poster,
                backdropUrl = backdrop,
                overview = overview,
                voteAverage = null,
                year = null,
                genres = emptyList(),
                runtimeMinutes = null
            )
        } else null
    }
    @Volatile internal var freeboxBackdropOverrides: Map<String, String> = emptyMap()

    internal val _movieWatchedStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val movieWatchedStatus: StateFlow<Map<String, Boolean>> = _movieWatchedStatus.asStateFlow()

    // Pending batch of watched status updates â€” debounced before emission.
    internal val _pendingWatchedBatch = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** True once the CW pipeline has completed its first emission (items or empty). */
    internal val _initialCwResolved = MutableStateFlow(false)
    val initialCwResolved: StateFlow<Boolean> = _initialCwResolved.asStateFlow()
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    private val _scrollToTopTrigger = MutableStateFlow(0)
    val scrollToTopTrigger: StateFlow<Int> = _scrollToTopTrigger.asStateFlow()

    internal val _currentLocaleTag = MutableStateFlow(LocaleCache.localeTag)

    fun notifyLocaleChanged() {
        val tag = LocaleCache.localeTag
        if (_currentLocaleTag.value != tag) {
            _currentLocaleTag.value = tag
        }
    }

    fun requestScrollToTop() {
        clearFocusState()
        _gridFocusState.value = HomeScreenFocusState()
        _scrollToTopTrigger.value++
    }

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val _lastEnrichedPreview = MutableStateFlow<MetaPreview?>(null)
    val lastEnrichedPreview: StateFlow<MetaPreview?> = _lastEnrichedPreview.asStateFlow()

    internal val _enrichedPreviews = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = _enrichedPreviews.asStateFlow()

    /** Items for which enrichment was attempted but produced no enriched data. */
    internal val _failedEnrichmentIds = MutableStateFlow<Set<String>>(emptySet())
    val failedEnrichmentIds: StateFlow<Set<String>> = _failedEnrichmentIds.asStateFlow()

    internal val catalogStateLock = Any()
    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogItemKeyIndex = mutableMapOf<String, MutableSet<String>>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var collectionsCache: List<Collection> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var followAddonsOrderEnabled: Boolean = false
    internal var customCatalogTitles: Map<String, String> = emptyMap()
    internal var currentHeroCatalogKeys: List<String> = emptyList()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    internal val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    internal val trailerPreviewLoadingIds = mutableSetOf<String>()
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var trailerPreviewJob: Job? = null
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentMdbListSettings: MDBListSettings = MDBListSettings()
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal var heroItemOrder: List<String> = emptyList()
    internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val prefetchedTmdbIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val cwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val cwMetaNegativeCacheTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    /** Ultra-light cache for badge evaluation: contentId â†’ set of aired (season, episode) pairs. */
    internal val cwBadgeEpisodeCache = Collections.synchronizedMap(mutableMapOf<String, Set<Pair<Int, Int>>?>())
    /** Per-series earliest upcoming season release date (epochMs) for smart TTL scheduling. */
    internal val cwBadgeNextSeasonMs = Collections.synchronizedMap(mutableMapOf<String, Long>())
    /** Snapshot of watchedShowEpisodes keys from the last badge evaluation cycle. */
    @Volatile
    internal var cwLastBadgeEpisodeKeys: Set<String> = emptySet()
    internal val cwTmdbIdCache = Collections.synchronizedMap(mutableMapOf<String, String?>())
    internal val cwNextUpResolutionCache = Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val cwNextUpNegativeCacheTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    internal val discoveredOlderNextUpItems = Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val cwLastProcessedNextUpContentIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val cwEnrichedNextUpOverlay = Collections.synchronizedMap(mutableMapOf<String, NextUpInfo>())
    /** In-memory cache of enriched InProgress items per contentId+episode key. */
    internal val cwEnrichedInProgressOverlay = Collections.synchronizedMap(mutableMapOf<String, ContinueWatchingItem.InProgress>())
    /** Bumped to force the CW pipeline to re-run (e.g. after cache clear). */
    internal val cwPipelineRefreshTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Tracks the active CW pipeline coroutine so it can be cancelled on profile switch. */
    internal var cwPipelineJob: Job? = null
    /** Relays a just-saved local playback entry to the home pipeline. */
    internal var cwOptimisticProgressJob: Job? = null
    /** Relays confirmed Freebox deletions to Home without waiting for debounce. */
    internal var cwDeletionJob: Job? = null
    internal val fullyWatchedSeriesIds get() = watchedSeriesStateHolder
    internal var tmdbEnrichFocusJob: Job? = null
    internal var pendingTmdbEnrichItemId: String? = null
    /** Item that was focused during startup grace period â€” will be enriched once grace ends. */
    internal var deferredEnrichItem: MetaPreview? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var movieWatchedBatchJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var seriesWatchedObserverJob: Job? = null
    internal var libraryTabsObserverJob: Job? = null
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    internal var posterStatusObservationEnabled: Boolean = false
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT
    internal val startupStartedAtMs: Long = SystemClock.elapsedRealtime()
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null

    // Lazy catalog loading
    internal val eagerCatalogLoadCount: Int = 4
    internal val lazyLoadRequestedKeys = Collections.synchronizedSet(mutableSetOf<String>())
    internal val pendingLazyCatalogs = linkedMapOf<String, Pair<Addon, CatalogDescriptor>>()
    /** All placeholder descriptors for homeRow construction. */
    internal data class PlaceholderDescriptor(
        val catalogKey: String,
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val apiType: String,
        val displayTitle: String
    )
    internal val placeholderDescriptors = mutableListOf<PlaceholderDescriptor>()
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        // Accumulates individual watched status changes and flushes them as a single
        // update after 150ms of inactivity, preventing N separate recompositions.
        viewModelScope.launch {
            _pendingWatchedBatch
                .debounce(150L)
                .collect { batch ->
                    if (batch.isNotEmpty()) {
                        val snapshot = _pendingWatchedBatch.value
                        _pendingWatchedBatch.value = emptyMap()
                        if (snapshot.isNotEmpty()) {
                            _movieWatchedStatus.update { current -> current + snapshot }
                        }
                    }
                }
        }

        observeStartupAuthNotice()
        viewModelScope.launch {
            profileManager.activeProfileReady.first { it }
            // Start only the data needed by the first Home frame. Starting every
            // settings/catalog observer together here blocks the main thread
            // for seconds after profile selection on Android TV.
            // The first Home frame needs the complete layout snapshot, not only
            // the selected layout enum. Publishing `layoutPreferencesReady`
            // before the detailed-row preference was restored exposed the old
            // thumbnail row, then replaced it with the coverflow a moment later.
            observeLayoutPreferences()
            observeModernHomePresentation()
            loadContinueWatching()

            // Freebox Videos used to start only from notifyHomeRendered(), after
            // the Home stability gate had waited for Continue Watching. On the
            // TV that gate accounts for the repeatable ~21 second blank-thumbnail
            // delay. Both observers launch asynchronous IO work, so start them as
            // soon as the active profile is ready: directory listing and cached
            // artwork now overlap startup instead of beginning after it.
            startFreeboxArtworkObservers()
            if (!freeboxObservationStarted) {
                freeboxObservationStarted = true
                observeFreeboxVideos()
            }

            // Clear CW state when profile changes so items don't leak between profiles.
            var previousProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { newId ->
                if (newId != previousProfileId) {
                    previousProfileId = newId
                    // Cancel old pipeline â€” prevents racing writes from stale coroutines.
                    cwPipelineJob?.cancel()
                    cwPipelineJob = null
                    cwOptimisticProgressJob?.cancel()
                    cwOptimisticProgressJob = null
                    cwDeletionJob?.cancel()
                    cwDeletionJob = null
                    // Clear all in-memory CW caches so data from the previous
                    // profile doesn't leak into the new one.
                    cwMetaCache.clear()
                    cwMetaNegativeCacheTimestamps.clear()
                    cwBadgeEpisodeCache.clear()
                    cwBadgeNextSeasonMs.clear()
                    cwTmdbIdCache.clear()
                    cwNextUpResolutionCache.clear()
                    cwNextUpNegativeCacheTimestamps.clear()
                    discoveredOlderNextUpItems.clear()
                    cwLastProcessedNextUpContentIds.clear()
                    cwEnrichedNextUpOverlay.clear()
                    cwEnrichedInProgressOverlay.clear()
                    cwLastBadgeEpisodeKeys = emptySet()
                    _uiState.update {
                        it.copy(
                            layoutPreferencesReady = false,
                            continueWatchingItems = emptyList()
                        )
                    }
                    clearFocusState()
                    _gridFocusState.value = HomeScreenFocusState()
                    // Reset so the new profile's pipeline signals first completion correctly.
                    _initialCwResolved.value = false
                    loadContinueWatching()
                    // Clear watched badges so they don't leak between profiles.
                    watchedSeriesStateHolder.update(emptySet())
                    _movieWatchedStatus.value = emptyMap()
                    _pendingWatchedBatch.value = emptyMap()
                    _uiState.update { it.copy(movieWatchedStatus = emptyMap()) }
                }
            }
        }
        viewModelScope.launch {
            delay(STARTUP_GRACE_PERIOD_MS)
            startupGracePeriodActive = false
            // Trigger enrichment for the initial focused item once grace ends.
            deferredEnrichItem?.let { item ->
                deferredEnrichItem = null
                onItemFocusPipeline(item)
            }
        }

        // Observe manual cache clear from Advanced settings.
        viewModelScope.launch {
            var lastSeen = cwEnrichmentCache.cacheCleared.value
            cwEnrichmentCache.cacheCleared.collect { version ->
                if (version != lastSeen) {
                    lastSeen = version
                    clearAllCwInMemoryCaches()
                }
            }
        }
    }

    private fun clearAllCwInMemoryCaches() {
        cwMetaCache.clear()
        cwMetaNegativeCacheTimestamps.clear()
        cwBadgeEpisodeCache.clear()
        cwBadgeNextSeasonMs.clear()
        cwTmdbIdCache.clear()
        cwNextUpResolutionCache.clear()
        cwNextUpNegativeCacheTimestamps.clear()
        discoveredOlderNextUpItems.clear()
        cwLastProcessedNextUpContentIds.clear()
        cwEnrichedNextUpOverlay.clear()
        cwEnrichedInProgressOverlay.clear()
        cwLastBadgeEpisodeKeys = emptySet()
        watchedSeriesStateHolder.clearValidationState()
        _uiState.update { it.copy(continueWatchingItems = emptyList()) }
        // Bump trigger so the pipeline's collectLatest restarts with fresh state.
        cwPipelineRefreshTrigger.value++
    }

    internal fun remainingStartupGraceMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!startupGracePeriodActive) return 0L
        return (STARTUP_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs)).coerceAtLeast(0L)
    }

    internal fun remainingContinueWatchingEnrichmentGraceMs(
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        return (CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs))
            .coerceAtLeast(0L)
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeModernHomePresentation() = observeModernHomePresentationPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    private fun observeContinueWatchingSortMode() {
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.continueWatchingSortMode
                .distinctUntilChanged()
                .collect { mode ->
                    continueWatchingSortMode = mode
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    // Clear caches so the new sort is applied immediately on next pipeline run
                    clearAllCwInMemoryCaches()
                }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurContinueWatchingNextUp
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(useEpisodeThumbnailsInCw = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingThumbnailSize
                .distinctUntilChanged()
                .collect { size ->
                    _uiState.update { it.copy(continueWatchingThumbnailSize = size) }
                }
        }
        // When "next up from furthest episode" changes, clear CW caches and retrigger pipeline
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
                .distinctUntilChanged()
                .collect {
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    clearAllCwInMemoryCaches()
                }
        }
    }

    fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadFollowAddonsOrder() = loadFollowAddonsOrderPipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun loadCustomCatalogTitles() = loadCustomCatalogTitlesPipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

    private fun observeMdbListSettings() {
        viewModelScope.launch {
            mdbListSettingsDataStore.settings
                .distinctUntilChanged()
                .collectLatest { settings ->
                    currentMdbListSettings = settings
                }
        }
    }

    /**
     * When the watch-progress source changes (e.g. Trakt login/logout, or
     * switching between Trakt and Nuvio Sync), clear the CW disk cache and
     * in-memory state so items from the old source don't leak into the new one.
     */
    private fun observeProgressSourceChanges() {
        viewModelScope.launch {
            var previousSource: com.nuvio.tv.data.local.ProgressSyncSource? = null
            traktSettingsDataStore.watchProgressSource
                .distinctUntilChanged()
                .collect { source ->
                    if (previousSource != null && previousSource != source) {
                        // Source changed â€” clear CW caches to prevent mixing.
                        cwMetaCache.clear()
                        cwEnrichedNextUpOverlay.clear()
                        cwEnrichedInProgressOverlay.clear()
                        discoveredOlderNextUpItems.clear()
                        cwLastProcessedNextUpContentIds.clear()
                        // Clear disk cache for current profile.
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { cwEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true) }
                            runCatching { cwEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true) }
                        }
                        // Reload CW from fresh source.
                        loadContinueWatching()
                    }
                    previousSource = source
                }
        }
    }

    private fun observeStartupAuthNotice() {
        viewModelScope.launch {
            authSessionNoticeDataStore.pendingNotice.collect { notice ->
                if (notice == null) return@collect
                _uiState.update { state ->
                    if (state.startupAuthNotice == notice) state else state.copy(startupAuthNotice = notice)
                }
                startupAuthNoticeJob?.cancel()
                startupAuthNoticeJob = viewModelScope.launch {
                    delay(3200)
                    clearStartupAuthNotice(notice)
                }
                authSessionNoticeDataStore.consumeNotice(notice)
            }
        }
    }

    private fun clearStartupAuthNotice(notice: StartupAuthNotice) {
        _uiState.update { state ->
            if (state.startupAuthNotice == notice) {
                state.copy(startupAuthNotice = null)
            } else {
                state
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() {
        val cacheProfileId = profileManager.activeProfileId.value
        // Immediately restore last known CW from disk cache for instant display.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot(cacheProfileId) }.getOrDefault(emptyList())
            val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot(cacheProfileId) }.getOrDefault(emptyList())
            if (cachedInProgress.isEmpty() && cachedNextUp.isEmpty()) {
                // A missing disk cache is not a completed Continue Watching
                // snapshot.  Let the pipeline publish its first real result;
                // otherwise Home opens on Videos and visibly rebuilds when
                // progress arrives a few seconds later.
                return@launch
            }
            val dismissedNextUp = traktSettingsDataStore.dismissedNextUpKeys.first()
            // Render cached items immediately â€” don't wait for Trakt/allProgress.
            // The pipeline will replace these with live data once it completes.
            val inProgressItems = cachedInProgress
                .filter { !it.contentId.isNullOrBlank() && !watchProgressRepository.isDroppedShow(it.contentId) }
                // The launcher/NAS snapshot can contain the complete playback
                // history, not only resumable items.  Never resurrect an item
                // that was already watched past the completion threshold on a
                // newly installed device.
                .filter { cached ->
                    val fraction = cached.progressPercent?.div(100f)
                        ?: if (cached.duration > 0L) {
                            cached.position.toFloat() / cached.duration.toFloat()
                        } else {
                            0f
                        }
                    fraction < WatchProgress.completionThreshold(cached.contentType, cached.episode)
                }
                .map { cached ->
                    ContinueWatchingItem.InProgress(
                        progress = com.nuvio.tv.domain.model.WatchProgress(
                            contentId = cached.contentId,
                            contentType = cached.contentType,
                            name = cached.name,
                            poster = cached.poster,
                            backdrop = cached.backdrop,
                            logo = cached.logo,
                            videoId = cached.videoId,
                            season = cached.season,
                            episode = cached.episode,
                            episodeTitle = cached.episodeTitle,
                            position = cached.position,
                            duration = cached.duration,
                            lastWatched = cached.lastWatched,
                            progressPercent = cached.progressPercent
                        ),
                        episodeThumbnail = cached.episodeThumbnail,
                        episodeDescription = cached.episodeDescription,
                        episodeImdbRating = cached.episodeImdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo
                    )
                }
            val nextUpItems = cachedNextUp
                .filter { !watchProgressRepository.isDroppedShow(it.contentId) }
                .filter { nextUpDismissKey(it.contentId, it.seedSeason, it.seedEpisode) !in dismissedNextUp }
                .map { cached ->
                ContinueWatchingItem.NextUp(
                    info = NextUpInfo(
                        contentId = cached.contentId,
                        contentType = cached.contentType,
                        name = cached.name,
                        poster = cached.poster,
                        backdrop = cached.backdrop,
                        logo = cached.logo,
                        videoId = cached.videoId,
                        season = cached.season,
                        episode = cached.episode,
                        episodeTitle = cached.episodeTitle,
                        episodeDescription = cached.episodeDescription,
                        thumbnail = cached.thumbnail,
                        released = cached.released,
                        hasAired = cached.hasAired,
                        airDateLabel = cached.airDateLabel,
                        lastWatched = cached.lastWatched,
                        imdbRating = cached.imdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo,
                        sortTimestamp = cached.sortTimestamp,
                        releaseTimestamp = cached.releaseTimestamp,
                        isReleaseAlert = cached.isReleaseAlert,
                        isNewSeasonRelease = cached.isNewSeasonRelease,
                        seedSeason = cached.seedSeason,
                        seedEpisode = cached.seedEpisode
                    )
                )
            }
            val items = mergeContinueWatchingItems(
                inProgressItems = inProgressItems,
                nextUpItems = nextUpItems,
                mode = layoutPreferenceDataStore.continueWatchingSortMode.first()
            )
            if (profileManager.activeProfileId.value == cacheProfileId) {
                _uiState.update { it.copy(continueWatchingItems = items) }
                // The disk snapshot is already a complete, displayable CW row.
                // Release Home now instead of waiting for the remote/NAS refresh,
                // which can continue enriching the same cards in the background.
                if (items.isNotEmpty()) {
                    _initialCwResolved.value = true
                }
            }
        }
        loadContinueWatchingPipeline()
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    private fun observeCollections() = observeCollectionsPipeline()

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use a moderate debounce so near-simultaneous
                // catalog arrivals are batched into a single heavy update pass.
                !hasRenderedFirstCatalog && hasAnyCatalogRows() -> {
                    hasRenderedFirstCatalog = true
                    150L
                }
                // During bulk loading, batch aggressively â€” placeholders are
                // already visible so the user won't notice the delay.
                pendingCatalogLoads > 8 -> 300L
                pendingCatalogLoads > 3 -> 250L
                pendingCatalogLoads > 0 -> 200L
                else -> 80L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    /**
     * Called from the UI when a placeholder catalog row becomes visible.
     */
    fun requestLazyCatalogLoad(catalogKey: String) {
        if (!lazyLoadRequestedKeys.add(catalogKey)) return
        val pair = synchronized(catalogStateLock) {
            pendingLazyCatalogs.remove(catalogKey)
        } ?: return
        val (addon, catalog) = pair
        val generation = catalogLoadGeneration
        pendingCatalogLoads = (pendingCatalogLoads + 1)
        loadCatalogPipeline(addon, catalog, generation)
    }

    /**
     * Load all pending lazy catalogs at once. Used when switching to GRID layout
     * which needs all catalogs available upfront.
     */
    internal fun loadAllPendingLazyCatalogs() {
        val pending = synchronized(catalogStateLock) {
            val copy = pendingLazyCatalogs.toMap()
            pendingLazyCatalogs.clear()
            copy
        }
        if (pending.isEmpty()) return
        val generation = catalogLoadGeneration
        pending.forEach { (key, pair) ->
            if (lazyLoadRequestedKeys.add(key)) {
                val (addon, catalog) = pair
                pendingCatalogLoads = (pendingCatalogLoads + 1)
                loadCatalogPipeline(addon, catalog, generation)
            }
        }
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    internal var posterStatusReconcileJob: Job? = null

    private fun schedulePosterStatusReconcile(rows: List<CatalogRow>) =
        schedulePosterStatusReconcilePipeline(rows)

    private fun reconcilePosterStatusObservers(rows: List<CatalogRow>) =
        reconcilePosterStatusObserversPipeline(rows)

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> = enrichHeroItemsPipeline(items, settings)

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> = replaceGridHeroItemsPipeline(gridItems, heroItems)

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String =
        heroEnrichmentSignaturePipeline(items, settings)

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    // When true, the next saveFocusState call is suppressed and the flag
    // is reset.  Used during layout switches to prevent the outgoing
    // layout's onDispose from poisoning the incoming layout's focus state.
    internal var suppressFocusSave: Boolean = false

    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowKey: String?,
        focusedItemKeyByRow: Map<String, String>,
        catalogRowScrollStates: Map<String, Int>,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        if (suppressFocusSave) {
            suppressFocusSave = false
            return
        }
        val nextState = _focusState.value.copy(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowKey = focusedRowKey,
            focusedItemKeyByRow = focusedItemKeyByRow,
            catalogRowScrollStates = catalogRowScrollStates,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    /**
     * Updates the stable focus target for a specific row.
     */
    fun updateFocusedItemKey(rowKey: String, itemKey: String) {
        _focusState.update { state ->
            val nextMap = state.focusedItemKeyByRow.toMutableMap()
            if (nextMap[rowKey] == itemKey) return@update state
            nextMap[rowKey] = itemKey
            state.copy(focusedItemKeyByRow = nextMap)
        }
    }

    /**
     * Updates the currently focused row key.
     */
    fun updateFocusedRowKey(rowKey: String?) {
        _focusState.update { state ->
            if (state.focusedRowKey == rowKey) state else state.copy(focusedRowKey = rowKey)
        }
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    /**
     * Saves the grid layout focus and scroll state.
     */
    fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0,
        focusedItemKey: String? = null
    ) {
        _gridFocusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            focusedItemKey = focusedItemKey,
            hasSavedFocus = true
        )
    }

    private suspend fun probeMissingFreeboxDurations(
        settings: com.nuvio.tv.data.local.FreeboxConnectionSettings,
        videos: List<com.nuvio.tv.data.freebox.FreeboxFileEntry>
    ) {
        try {
            val cached = freeboxDurationCacheDataStore.getAll().filterValues { it > 0L }
            // Applique immediatement ce qui est deja en cache
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(freeboxVideoProbedDurations = it.freeboxVideoProbedDurations + cached) }
            }
            // Always probe the current file, even when Freebox reports a
            // duration and even when the path already exists in our cache.
            // Recordings are commonly replaced under the same name/path; the
            // previous implementation then kept the old duration forever.
            val toProbe = videos
            toProbe.forEach { entry ->
                // Include the file fingerprint so a replacement at the same
                // path is allowed to probe independently of the old file.
                val probeKey = "${entry.path}|${entry.size ?: 0L}|${entry.modifiedMs ?: 0L}"
                if (!freeboxDurationProbesInFlight.add(probeKey)) return@forEach
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    freeboxDurationProbeSemaphore.acquire()
                    try {
                        var duration: Long? = null
                        FREEBOX_DURATION_RETRY_DELAYS_MS.forEach { retryDelayMs ->
                            if (duration != null) return@forEach
                            if (retryDelayMs > 0L) delay(retryDelayMs)
                            duration = freeboxOsClient.probeDurationMs(settings, entry)
                                ?.takeIf { it > 0L }
                        }
                        duration?.let { resolvedDuration ->
                            freeboxDurationCacheDataStore.put(entry.path, resolvedDuration)
                            _uiState.update { state ->
                                state.copy(
                                    freeboxVideoProbedDurations =
                                        state.freeboxVideoProbedDurations + (entry.path to resolvedDuration)
                                )
                            }
                        } ?: Log.w(TAG, "Duree Freebox introuvable apres relances: ${entry.path}")
                    } finally {
                        freeboxDurationProbeSemaphore.release()
                        freeboxDurationProbesInFlight.remove(probeKey)
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de preparer le sondage des durees Freebox", error)
        }
    }

    fun renameFreeboxVideo(entry: FreeboxFileEntry, newName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            renameFreeboxEntry(entry = entry, newName = newName, sourceProgress = null)
        }
    }

    fun renameFreeboxContinueWatching(item: ContinueWatchingItem, newName: String) {
        val progress = (item as? ContinueWatchingItem.InProgress)?.progress ?: return
        if (!progress.contentId.startsWith("freebox:")) return
        val path = freeboxPathFromContentId(progress.contentId)
        if (path.isBlank()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            renameFreeboxEntry(
                entry = FreeboxFileEntry(
                    name = freeboxFileNameOnly(path),
                    path = path,
                    encodedPath = null,
                    isDirectory = false,
                    durationMs = progress.duration.takeIf { it > 0L }
                ),
                newName = newName,
                sourceProgress = progress
            )
        }
    }

    fun markFreeboxAsUnwatched(item: ContinueWatchingItem) {
        val progress = (item as? ContinueWatchingItem.InProgress)?.progress ?: return
        if (!progress.contentId.startsWith("freebox:")) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            watchProgressRepository.removeProgress(progress.contentId, progress.season, progress.episode)
            pruneStaleFreeboxFromContinueWatchingCache(progress.contentId)
        }
    }

    private suspend fun renameFreeboxEntry(
        entry: FreeboxFileEntry,
        newName: String,
        sourceProgress: WatchProgress?
    ) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank() || trimmedName == entry.name) return
        try {
            val settings = freeboxSettingsDataStore.settings.first()
            if (!settings.hasSavedConnection) return
            val token = freeboxOsClient.openSession(settings).getOrNull()?.sessionToken ?: return
            val settingsWithToken = settings.copy(sessionToken = token)
            freeboxOsClient.rename(settingsWithToken, entry.path, entry.encodedPath, trimmedName).getOrThrow()

            val oldContentId = sourceProgress?.contentId ?: freeboxContentIdForEntry(entry)
            val newPath = renamedFreeboxPath(entry.path, trimmedName)
            val renamedEntry = entry.copy(name = trimmedName, path = newPath, encodedPath = null)
            val newContentId = freeboxContentIdForEntry(renamedEntry)
            val legacyNewContentId = freeboxContentIdForPath(newPath)
            freeboxPosterOverrideDataStore.move(oldContentId, newContentId)
            val allProgress = watchProgressRepository.allProgress.first()
            val progressToMigrate = sourceProgress
                ?: allProgress.firstOrNull { progress ->
                    progress.contentId == oldContentId || freeboxPathFromContentId(progress.contentId) == entry.path
                }

            if (progressToMigrate != null) {
                val migrated = progressToMigrate.copy(
                    contentId = newContentId,
                    videoId = newContentId,
                    name = trimmedName,
                    duration = progressToMigrate.duration.takeIf { it > 0L }
                        ?: entry.durationMs?.takeIf { it > 0L }
                        ?: 0L
                )
                watchProgressRepository.saveProgress(migrated)
                if (progressToMigrate.contentId != newContentId) {
                    watchProgressRepository.removeProgress(progressToMigrate.contentId, progressToMigrate.season, progressToMigrate.episode)
                    pruneStaleFreeboxFromContinueWatchingCache(progressToMigrate.contentId)
                }
            }

            _uiState.update { state ->
                val movedArtwork = state.freeboxVideoArtwork[oldContentId]
                    ?: state.freeboxVideoArtwork[legacyNewContentId]
                val artwork = state.freeboxVideoArtwork
                    .minus(oldContentId)
                    .minus(legacyNewContentId)
                    .let { current -> if (movedArtwork != null) current + (newContentId to movedArtwork) else current }
                val movedDuration = state.freeboxVideoProbedDurations[entry.path]
                val durations = state.freeboxVideoProbedDurations
                    .minus(entry.path)
                    .let { current -> if (movedDuration != null) current + (newPath to movedDuration) else current }
                state.copy(
                    freeboxVideoEntries = state.freeboxVideoEntries.map { current ->
                        if (current.path == entry.path) renamedEntry else current
                    },
                    freeboxVideoArtwork = artwork,
                    freeboxVideoProbedDurations = durations
                )
            }
            loadFreeboxVideos()
        } catch (error: Exception) {
            Log.w(TAG, "Impossible de renommer le fichier Freebox: ${entry.path}", error)
        }
    }

    private fun renamedFreeboxPath(path: String, newName: String): String {
        val directory = path.substringBeforeLast("/", missingDelimiterValue = "")
        return if (directory.isBlank()) newName else "$directory/$newName"
    }

    fun deleteFreeboxVideoFromHome(entry: FreeboxFileEntry) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val contentId = freeboxContentIdForEntry(entry)
            val legacyContentId = freeboxLegacyContentIdFor(contentId)
            freeboxResumeDeletionService.delete(entry).onSuccess {
                // Only a confirmed remote deletion may remove visible state.
                pruneFreeboxAutomaticPresentation(setOf(contentId, legacyContentId))
                _uiState.update { state ->
                    state.copy(
                        freeboxVideoEntries = state.freeboxVideoEntries.filterNot { current ->
                            current.path == entry.path
                        },
                        freeboxVideoArtwork = state.freeboxVideoArtwork
                            .minus(contentId)
                            .minus(legacyContentId),
                        freeboxVideoBackdrops = state.freeboxVideoBackdrops
                            .minus(contentId)
                            .minus(legacyContentId),
                        freeboxVideoProbedDurations = state.freeboxVideoProbedDurations.minus(entry.path),
                        continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                            item is ContinueWatchingItem.InProgress &&
                                item.progress.matchesDeletedFreeboxEntry(entry, contentId, legacyContentId)
                        }
                    )
                }
                pruneStaleFreeboxFromContinueWatchingCache(contentId)
                pruneStaleFreeboxFromContinueWatchingCache(legacyContentId)
                if (entry.isDirectory) {
                    pruneStaleFreeboxTreeFromContinueWatchingCache(entry.path)
                }

                loadFreeboxVideos()
            }.onFailure { error ->
                Log.w(TAG, "Impossible de supprimer le fichier Freebox depuis l'accueil: ${entry.path}", error)
                _uiState.update { it.copy(error = error.message ?: "Erreur lors de la suppression Freebox.") }
                loadFreeboxVideos()
            }
        }
    }

    private fun publishFreeboxArtwork() {
        val artwork = freeboxAutomaticArtwork + freeboxPosterOverrides
        val backdrops = freeboxAutomaticBackdrops + freeboxBackdropOverrides
        fun artworkFor(
            contentId: String,
            name: String,
            isDirectory: Boolean,
            values: Map<String, String>
        ): String? {
            return values[contentId]
                ?: values[freeboxProgrammeOverrideKey(name, isDirectory)]
                ?: values.entries.firstOrNull {
                    freeboxPathFromContentId(it.key) == freeboxPathFromContentId(contentId)
                }?.value
        }
        fun metadataFor(
            contentId: String,
            name: String,
            isDirectory: Boolean
        ): FreeboxVideoMeta? =
            selectedMetadataOverrides[contentId]
                ?: selectedMetadataOverrides[freeboxProgrammeOverrideKey(name, isDirectory)]
                ?: selectedMetadataOverrides.entries.firstOrNull { (key, _) ->
                    freeboxPathFromContentId(key) == freeboxPathFromContentId(contentId)
                }?.value
                ?: freeboxAutomaticMetadata[contentId]
        _uiState.update { state ->
            val enrichedCw = state.continueWatchingItems.map { item ->
                if (item is ContinueWatchingItem.InProgress &&
                    (item.progress.contentType.equals("freebox", ignoreCase = true) ||
                        item.progress.contentId.startsWith("freebox:", ignoreCase = true))
                ) {
                    val contentId = item.progress.contentId
                    val metadata = metadataFor(contentId, item.progress.name, false)
                    item.copy(
                        progress = item.progress.copy(
                            // The stable programme key deliberately survives
                            // deletion of yesterday's file and is reused by the
                            // next daily or weekly recording of the same title.
                            poster = artworkFor(contentId, item.progress.name, false, artwork)
                                ?.takeIf(String::isNotBlank)
                                ?: item.progress.poster?.takeIf(String::isNotBlank),
                            backdrop = artworkFor(contentId, item.progress.name, false, backdrops)
                                ?.takeIf(String::isNotBlank)
                                ?: artworkFor(contentId, item.progress.name, false, artwork)
                                    ?.takeIf(String::isNotBlank)
                                ?: item.progress.backdrop?.takeIf(String::isNotBlank),
                        ),
                        episodeDescription = if (
                            selectedMetadataOverrides[contentId] != null ||
                            selectedMetadataOverrides[freeboxProgrammeOverrideKey(item.progress.name, false)] != null
                        ) metadata?.overview else freeboxOverviewOverrides[contentId] ?: metadata?.overview,
                        genres = metadata?.genres
                            ?.takeIf { it.isNotEmpty() }
                            ?: item.genres,
                        cast = metadata?.cast?.takeIf { it.isNotEmpty() } ?: item.cast,
                        director = metadata?.director?.takeIf { it.isNotEmpty() } ?: item.director,
                        releaseInfo = metadata?.year ?: item.releaseInfo,
                        logo = metadata?.logoUrl ?: item.logo,
                        castMembers = metadata?.castMembers?.takeIf { it.isNotEmpty() }
                            ?: item.castMembers
                    )
                } else if (item is ContinueWatchingItem.InProgress) {
                    val metadata = selectedMetadataOverrides[item.progress.contentId]
                        ?: selectedMetadataOverrides[item.progress.videoId]
                    if (metadata == null) item else item.copy(
                        progress = item.progress.copy(
                            poster = freeboxPosterOverrides[item.progress.contentId]
                                ?: item.progress.poster
                                ?: metadata.posterUrl,
                            backdrop = freeboxBackdropOverrides[item.progress.contentId]
                                ?: item.progress.backdrop
                                ?: metadata.backdropUrl,
                            description = metadata.overview ?: item.progress.description
                        ),
                        episodeDescription = metadata.overview ?: item.episodeDescription,
                        genres = metadata.genres.ifEmpty { item.genres },
                        cast = metadata.cast.ifEmpty { item.cast },
                        director = metadata.director.ifEmpty { item.director },
                        releaseInfo = metadata.year ?: item.releaseInfo,
                        logo = metadata.logoUrl ?: item.logo,
                        castMembers = metadata.castMembers.ifEmpty { item.castMembers }
                    )
                } else {
                    item
                }
            }
            state.copy(
                freeboxVideoArtwork = artwork,
                freeboxVideoBackdrops = backdrops,
                freeboxVideoMetadata = buildMap {
                    freeboxAutomaticMetadata.forEach { (key, meta) ->
                        put(
                            key,
                            freeboxOverviewOverrides[key]
                                ?.takeIf { it.isNotBlank() }
                                ?.let { customOverview -> meta.copy(overview = customOverview) }
                                ?: meta
                        )
                    }
                    selectedMetadataOverrides.forEach { (key, selectedMeta) ->
                        put(
                            key,
                            selectedMeta.copy(
                                posterUrl = freeboxPosterOverrides[key] ?: selectedMeta.posterUrl,
                                backdropUrl = freeboxBackdropOverrides[key] ?: selectedMeta.backdropUrl,
                                overview = selectedMeta.overview
                            )
                        )
                    }
                    state.freeboxVideoEntries.forEach { entry ->
                        val contentId = com.nuvio.tv.data.freebox.freeboxContentIdForEntry(entry)
                        val selectedMeta = metadataFor(
                            contentId,
                            entry.name,
                            entry.isDirectory
                        ) ?: return@forEach
                        if (selectedMetadataOverrides[contentId] != null ||
                            selectedMetadataOverrides[freeboxProgrammeOverrideKey(entry.name, entry.isDirectory)] != null
                        ) {
                            put(contentId, selectedMeta.copy(
                                posterUrl = artworkFor(
                                    contentId,
                                    entry.name,
                                    entry.isDirectory,
                                    artwork
                                ) ?: selectedMeta.posterUrl,
                                backdropUrl = artworkFor(
                                    contentId,
                                    entry.name,
                                    entry.isDirectory,
                                    backdrops
                                ) ?: selectedMeta.backdropUrl
                            ))
                        }
                    }
                    freeboxOverviewOverrides.forEach { (key, customOverview) ->
                        if (key !in this && customOverview.isNotBlank()) {
                            put(
                                key,
                                FreeboxVideoMeta(
                                    tmdbId = null,
                                    mediaType = null,
                                    posterUrl = freeboxPosterOverrides[key] ?: freeboxAutomaticArtwork[key],
                                    backdropUrl = freeboxBackdropOverrides[key] ?: freeboxAutomaticBackdrops[key],
                                    overview = customOverview,
                                    voteAverage = null,
                                    year = null,
                                    genres = emptyList(),
                                    runtimeMinutes = null
                                )
                            )
                        }
                    }
                },
                continueWatchingItems = enrichedCw
            )
        }
        val bridgeArtwork = _uiState.value.continueWatchingItems
            .asSequence()
            .filterIsInstance<ContinueWatchingItem.InProgress>()
            .map { it.progress }
            .filter { progress ->
                progress.contentType.equals("freebox", ignoreCase = true) ||
                    progress.contentId.startsWith("freebox:", ignoreCase = true)
            }
            .associate { progress ->
                progress.contentId to (progress.poster to progress.backdrop)
        }
        if (bridgeArtwork.isNotEmpty()) {
            freeboxBridgeArtworkJob?.cancel()
            freeboxBridgeArtworkJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                cwEnrichmentCache.updateInProgressArtworkByContentId(bridgeArtwork)
            }
        }
    }

    internal fun pruneFreeboxAutomaticPresentation(contentIds: Set<String>) {
        if (contentIds.isEmpty()) return
        freeboxAutomaticArtwork = freeboxAutomaticArtwork - contentIds
        freeboxAutomaticBackdrops = freeboxAutomaticBackdrops - contentIds
        freeboxAutomaticMetadata = freeboxAutomaticMetadata - contentIds
    }

    private fun startDeferredHomeObservers() {
        if (deferredHomeObserversStarted) return
        deferredHomeObserversStarted = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            watchedSeriesStateHolder.loadFromDisk()
        }
        observeExternalMetaPrefetchPreference()
        observeContinueWatchingSortMode()
        loadHomeCatalogOrderPreference()
        loadFollowAddonsOrder()
        loadDisabledHomeCatalogPreference()
        loadCustomCatalogTitles()
        observeLibraryState()
        observeTmdbSettings()
        observeMdbListSettings()
        observeBlurUnwatchedEpisodes()
        observeProgressSourceChanges()
        observeCollections()
        observeInstalledAddons()
        viewModelScope.launch {
            _uiState
                .map { it.continueWatchingItems }
                .distinctUntilChanged()
                .collect { items ->
                    runCatching {
                        tvRecommendationManager.updateWatchNextFromCwItems(
                            profileId = profileManager.activeProfileId.value,
                            items = items
                        )
                    }
                }
        }
    }

    /**
     * NAS settings are restored after the first Home frame.  Observing the
     * connection here is essential: a one-shot read sees the temporary empty
     * settings and leaves the Videos row permanently absent until an app restart.
     */
    private fun observeFreeboxVideos() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            freeboxSettingsDataStore.settings
                // Only a real connection identity can invalidate Videos.
                // Browser view/sort settings, folder visibility and renewed
                // session state all share this DataStore; observing the whole
                // object made these unrelated writes restart frame extraction
                // while the user was simply navigating Home.
                .map { settings ->
                    settings to listOf(
                        settings.name,
                        settings.address,
                        settings.username,
                        settings.password,
                        settings.appToken,
                    )
                }
                .distinctUntilChanged { previous, next -> previous.second == next.second }
                .collectLatest { (settings, _) ->
                    if (settings.hasSavedConnection) {
                        // A real connection change invalidates the in-memory
                        // catalogue.  A lifecycle resume does not.
                        freeboxVideosLoadedForSession = false
                        loadFreeboxVideos(settings)
                    } else {
                        freeboxVideosLoadedForSession = false
                        _uiState.update { state ->
                            state.copy(
                                freeboxVideoEntries = emptyList(),
                                freeboxVideoArtwork = emptyMap(),
                                freeboxVideoBackdrops = emptyMap(),
                                freeboxVideoMetadata = emptyMap(),
                                initialFreeboxVideosResolved = true
                            )
                        }
                    }
                }
        }
    }

    /** Refresh Freebox only after Home has been shown at least once. */
    fun refreshFreeboxVideosAfterHomeRendered() {
        if (!freeboxObservationStarted) return
        // Home can resume whenever the user returns from another menu.  The
        // observer above already loaded the catalogue; do not rebuild the row
        // (and therefore do not replace its thumbnails) on every resume.
        if (!freeboxVideosLoadedForSession) {
            ensureFreeboxVideosForHome()
            return
        }
        reconcileExternallyDeletedFreeboxVideos()
    }

    /**
     * Observe the three artwork stores in one coroutine and publish one merged
     * state. The reads stay asynchronous, so starting this with the profile does
     * not block composition and lets cached thumbnails reach the first Home.
     */
    private fun startFreeboxArtworkObservers() {
        if (freeboxArtworkObserversStarted) return
        freeboxArtworkObserversStarted = true
        viewModelScope.launch {
            combine(
                freeboxPosterOverrideDataStore.overrides,
                freeboxPosterOverrideDataStore.backdropOverrides,
                freeboxPosterOverrideDataStore.overviewOverrides,
                freeboxPosterOverrideDataStore.metadataOverrides
            ) { posterOverrides, backdropOverrides, overviewOverrides, metadataOverrides ->
                FreeboxPresentationOverrideSnapshot(
                    posters = posterOverrides,
                    backdrops = backdropOverrides,
                    overviews = overviewOverrides,
                    metadata = metadataOverrides
                )
            }.collectLatest { overrides ->
                freeboxPosterOverrides = overrides.posters
                freeboxBackdropOverrides = overrides.backdrops
                freeboxOverviewOverrides = overrides.overviews
                selectedMetadataOverrides = overrides.metadata
                publishFreeboxArtwork()
                hydrateSelectedMetadataOverrides(overrides.metadata)
            }
        }
    }

    private fun hydrateSelectedMetadataOverrides(overrides: Map<String, FreeboxVideoMeta>) {
        overrides.entries
            .groupBy { (_, metadata) -> metadata.tmdbId to metadata.mediaType }
            .forEach { (identity, entries) ->
                val tmdbId = identity.first ?: return@forEach
                val mediaType = identity.second ?: return@forEach
                val attemptKey = "$mediaType:$tmdbId"
                if (!selectedMetadataHydrationAttempts.add(attemptKey)) return@forEach
                val seed = entries.first().value
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val metadata = runCatching {
                        tmdbMetadataService.fetchMetadataForSelectedIdentity(seed, "fr-FR")
                    }.getOrNull() ?: return@launch
                    val keys = overrides.filterValues { value ->
                        value.tmdbId == tmdbId && value.mediaType == mediaType
                    }.keys
                    freeboxPosterOverrideDataStore.setMetadataForContentIds(keys, metadata)
                    metadata.overview?.takeIf(String::isNotBlank)?.let { overview ->
                        freeboxPosterOverrideDataStore.setOverviewForContentIds(keys, overview)
                    }
                    profileSettingsSyncService.pushProfileToRemote(FREEBOX_SHARED_MEDIA_PROFILE_ID)
                }
            }
    }

    /**
     * The first ON_RESUME can happen before Home installs its lifecycle observer.
     * Wait for the NAS-restored Freebox settings and load the Videos row directly,
     * rather than relying on a later player return to cause the first refresh.
     */
    private var guaranteedFreeboxHomeLoadJob: Job? = null
    private var freeboxExternalDeletionReconcileJob: Job? = null
    @Volatile private var freeboxVideosLoadedForSession = false

    fun ensureFreeboxVideosForHome() {
        if (freeboxVideosLoadedForSession) return
        if (guaranteedFreeboxHomeLoadJob?.isActive == true) return
        guaranteedFreeboxHomeLoadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val settings = freeboxSettingsDataStore.settings
                .filter { it.hasSavedConnection }
                .first()
            loadFreeboxVideos(settings)
        }
    }

    fun loadFreeboxVideos() {
        // This public entry point is used by explicit user actions (refresh,
        // delete, rename).  Those actions are allowed to invalidate the row.
        freeboxVideosLoadedForSession = false
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            loadFreeboxVideos(freeboxSettingsDataStore.settings.first())
        }
    }

    /**
     * Lightweight refresh check. It lists the Freebox Videos directory and
     * reloads the row only when a file is added, removed, or replaced. This is
     * also called periodically while Home is resumed because Freebox has no
     * filesystem change notification API.
     */
    private fun reconcileExternallyDeletedFreeboxVideos() {
        if (freeboxExternalDeletionReconcileJob?.isActive == true) return
        freeboxExternalDeletionReconcileJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val settings = freeboxSettingsDataStore.settings.first()
            if (!settings.hasSavedConnection) return@launch
            val token = freeboxOsClient.openSession(settings).getOrNull()?.sessionToken
                ?.takeIf(String::isNotBlank)
                ?: return@launch
            val settingsWithToken = settings.copy(sessionToken = token)
            val rootEntries = freeboxOsClient
                .listDirectory(settingsWithToken, FREEBOX_HOME_VIDEOS_PATH)
                .getOrNull()
                ?: return@launch
            val deletedFreeboxVideos = freeboxDeletedItemsDataStore.getAll()
            val remoteSignature = rootEntries
                .filter { it.isDirectory || it.isFreeboxHomeVideoFile() }
                .filterNot { it.isDeletedFreeboxHomeVideo(deletedFreeboxVideos) }
                .map { entry ->
                    "${entry.path.trim()}|${entry.size ?: 0L}|${entry.modifiedMs ?: 0L}|${entry.isDirectory}"
                }
                .toSet()
            val localSignature = _uiState.value.freeboxVideoEntries
                .map { entry ->
                    "${entry.path.trim()}|${entry.size ?: 0L}|${entry.modifiedMs ?: 0L}|${entry.isDirectory}"
                }
                .toSet()
            if (remoteSignature != localSignature) {
                freeboxVideosLoadedForSession = false
                loadFreeboxVideos(settingsWithToken)
                return@launch
            }
            reconcileMissingFreeboxContinueWatching(settingsWithToken, rootEntries)
        }
    }

    private fun launchMissingFreeboxContinueWatchingReconciliation(
        settings: FreeboxConnectionSettings,
        rootEntries: List<FreeboxFileEntry>
    ) {
        if (freeboxExternalDeletionReconcileJob?.isActive == true) return
        freeboxExternalDeletionReconcileJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            reconcileMissingFreeboxContinueWatching(settings, rootEntries)
        }
    }

    /**
     * Removes a resume only when an authoritative listing of its parent folder
     * succeeds and the exact file path is absent. Failed/partial network reads
     * never delete local state.
     */
    private suspend fun reconcileMissingFreeboxContinueWatching(
        settings: FreeboxConnectionSettings,
        rootEntries: List<FreeboxFileEntry>
    ) {
        fun normalizePath(value: String): String = value
            .trim()
            .replace('\\', '/')
            .replace(Regex("/+"), "/")
            .trimEnd('/')

        val rootPath = normalizePath(FREEBOX_HOME_VIDEOS_PATH)
        val rootEntryPaths = rootEntries.map { normalizePath(it.path) }.toSet()
        val progressByPath = watchProgressRepository.allProgress.first()
            .mapNotNull { progress ->
                val freeboxId = sequenceOf(progress.videoId, progress.contentId)
                    .firstOrNull { it.startsWith("freebox:", ignoreCase = true) }
                    ?: return@mapNotNull null
                val path = normalizePath(freeboxPathFromContentId(freeboxId))
                if (path == rootPath || !freeboxPathIsInDeletedFolder(path, rootPath)) {
                    return@mapNotNull null
                }
                Triple(path, freeboxId, progress)
            }
            .groupBy { it.first }
            .mapValues { (_, entries) -> entries.maxByOrNull { it.third.lastWatched }!! }
        if (progressByPath.isEmpty()) return

        val knownDirectoryEntries = mutableMapOf(rootPath to rootEntryPaths)
        val parentPaths = progressByPath.keys
            .map { it.substringBeforeLast('/', missingDelimiterValue = rootPath) }
            .filter { it != rootPath }
            .distinct()
        parentPaths.forEach { parentPath ->
            val relativePath = parentPath.removePrefix("$rootPath/")
            val topLevelPath = "$rootPath/${relativePath.substringBefore('/')}"
            // If the top-level folder itself disappeared, the successful root
            // listing already proves that every recording below it is absent.
            if (topLevelPath !in rootEntryPaths) return@forEach
            freeboxOsClient.listDirectory(settings, parentPath)
                .getOrNull()
                ?.let { entries ->
                    knownDirectoryEntries[parentPath] = entries
                        .map { normalizePath(it.path) }
                        .toSet()
                }
        }

        progressByPath.values.forEach { (path, freeboxId, _) ->
            val parentPath = path.substringBeforeLast('/', missingDelimiterValue = rootPath)
            val relativePath = path.removePrefix("$rootPath/")
            val topLevelPath = "$rootPath/${relativePath.substringBefore('/')}"
            val topLevelWasDeleted = topLevelPath !in rootEntryPaths
            val parentListing = knownDirectoryEntries[parentPath]
            val exactFileWasDeleted = parentListing != null && path !in parentListing
            if (!topLevelWasDeleted && !exactFileWasDeleted) return@forEach

            Log.i(TAG, "Suppression Freebox externe détectée : $path")
            watchProgressRepository.removeProgress(freeboxId, null, null)
            freeboxDurationCacheDataStore.remove(path)
            watchProgressRepository.notifyFreeboxContinueWatchingDeletion(
                com.nuvio.tv.domain.repository.FreeboxContinueWatchingDeletion(
                    profileId = profileManager.activeProfileId.value,
                    contentId = freeboxId,
                    legacyContentId = freeboxLegacyContentIdFor(freeboxId),
                    path = path,
                    season = null,
                    episode = null
                )
            )
        }
    }

    private suspend fun loadFreeboxVideos(
        settings: FreeboxConnectionSettings,
        retryAttempt: Int = 0,
    ) {
        try {
            if (!settings.hasSavedConnection) {
                Log.w(TAG, "Vidéos Freebox ignorées : aucune connexion enregistrée")
                return
            }
            val sessionResult = freeboxOsClient.openSession(settings)
            val token = sessionResult.getOrNull()?.sessionToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Session Freebox indisponible (essai ${retryAttempt + 1}/3)", sessionResult.exceptionOrNull())
                retryFreeboxVideos(settings, retryAttempt, sessionResult.exceptionOrNull())
                return
            }
                val settingsWithToken = settings.copy(sessionToken = token)
                val deletedFreeboxVideos = freeboxDeletedItemsDataStore.getAll()
                val directoryResult = freeboxOsClient.listDirectory(settingsWithToken, FREEBOX_HOME_VIDEOS_PATH)
                directoryResult
                    .onSuccess { rootEntries ->
                        // Mark the catalogue as loaded before starting the
                        // slower metadata/artwork enrichment.  Returning to
                        // Home during that enrichment must not start a second
                        // directory load and swap the visible thumbnails.
                        freeboxVideosLoadedForSession = true
                        launchMissingFreeboxContinueWatchingReconciliation(
                            settingsWithToken,
                            rootEntries
                        )
                        // A successful delete is confirmed against the Freebox
                        // before this marker is written.  Some Freebox directory
                        // responses can nevertheless remain stale briefly: do not
                        // erase the marker merely because such a response still
                        // contains the exact same file, or it reappears in Vidéos.
                        // Only a different fingerprint at the same path proves
                        // that a new recording replaced the deleted one.
                        rootEntries
                            .filter { entry ->
                                !entry.isDirectory && entry.isFreeboxHomeVideoFile()
                            }
                            .filter { entry ->
                                val deletedContentId = deletedFreeboxVideos[entry.path.trim()]
                                deletedContentId != null &&
                                    freeboxHasFileFingerprint(deletedContentId) &&
                                    !entry.isDeletedFreeboxHomeVideo(deletedFreeboxVideos)
                            }
                            .forEach { entry ->
                                freeboxDeletedItemsDataStore.forget(entry.path)
                            }
                        val videos = rootEntries
                            .filter { entry -> entry.isDirectory || entry.isFreeboxHomeVideoFile() }
                            .filterNot { entry -> entry.isDeletedFreeboxHomeVideo(deletedFreeboxVideos) }
                            .sortedWith(
                                compareByDescending<FreeboxFileEntry> { it.isDirectory }
                                    .thenBy(String.CASE_INSENSITIVE_ORDER) { freeboxFileNameOnly(it.name) }
                            )
                        val playableVideos = videos.filter { !it.isDirectory }
                        // The directory listing is enough to render the Videos
                        // row. Do not make its first appearance wait for the
                        // profile artwork stores below: after a profile/NAS
                        // transition those flows can take several seconds to
                        // emit, even though Freebox already answered.
                        _uiState.update {
                            it.copy(
                                freeboxVideoEntries = videos,
                                initialFreeboxVideosResolved = true
                            )
                        }
                        publishFreeboxArtwork()
                        // Read both durable stores after exposing the row. The
                        // initial catalogue is already visible; these values
                        // only refine its artwork and metadata.
                        val persistedManualPosters = freeboxPosterOverrideDataStore.overrides.first()
                        val persistedManualBackdrops = freeboxPosterOverrideDataStore.backdropOverrides.first()
                        freeboxPosterOverrides = persistedManualPosters
                        freeboxBackdropOverrides = persistedManualBackdrops
                        val cachedArtwork = freeboxArtworkCacheDataStore.getAll()
                        fun resolveRememberedArtwork(
                            values: Map<String, String>,
                            entry: FreeboxFileEntry
                        ): String? {
                            fun usable(value: String?): String? {
                                val url = value?.takeIf(String::isNotBlank) ?: return null
                                if (!url.startsWith("file:", ignoreCase = true)) return url
                                return url.takeIf {
                                    runCatching {
                                        java.io.File(java.net.URI(url)).let { file ->
                                            file.isFile && file.length() > 0L
                                        }
                                    }.getOrDefault(false)
                                }
                            }
                            val contentId = freeboxContentIdForEntry(entry)
                            val programmeKey = freeboxProgrammeOverrideKey(entry.name, entry.isDirectory)
                            return usable(values[contentId])
                                ?: usable(values[programmeKey])
                                ?: values.entries.firstOrNull { (key, value) ->
                                    usable(value) != null &&
                                    freeboxPathFromContentId(key) == freeboxPathFromContentId(contentId)
                                }?.value?.let(::usable)
                        }
                        val cachedPostersForRow = buildMap {
                            videos.forEach { entry ->
                                val contentId = freeboxContentIdForEntry(entry)
                                val remembered = resolveRememberedArtwork(persistedManualPosters, entry)
                                    ?: resolveRememberedArtwork(cachedArtwork.posters, entry)
                                    // Migrate frames created by previous app
                                    // versions without opening any decoder.
                                    ?: if (!entry.isDirectory) {
                                        freeboxFrameThumbnailService.cachedThumbnailUri(entry)
                                    } else null
                                remembered?.takeIf(String::isNotBlank)?.let { put(contentId, it) }
                            }
                        }
                        val cachedBackdropsForRow = buildMap {
                            videos.forEach { entry ->
                                val contentId = freeboxContentIdForEntry(entry)
                                val remembered = resolveRememberedArtwork(persistedManualBackdrops, entry)
                                    ?: resolveRememberedArtwork(cachedArtwork.backdrops, entry)
                                remembered?.takeIf(String::isNotBlank)?.let { put(contentId, it) }
                            }
                        }
                        val reusableCachedPosters = cachedArtwork.posters.filterKeys {
                            it.startsWith("freebox-programme:")
                        }
                        val reusableCachedBackdrops = cachedArtwork.backdrops.filterKeys {
                            it.startsWith("freebox-programme:")
                        }
                        // Paint the last stable image immediately. Automatic
                        // enrichment below must never gate the visible row.
                        freeboxAutomaticArtwork = freeboxAutomaticArtwork +
                            reusableCachedPosters + cachedPostersForRow
                        freeboxAutomaticBackdrops = freeboxAutomaticBackdrops +
                            reusableCachedBackdrops + cachedBackdropsForRow
                        _uiState.update {
                            it.copy(
                                freeboxVideoEntries = videos,
                                initialFreeboxVideosResolved = true
                            )
                        }
                        publishFreeboxArtwork()
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { migrateFreeboxRenamedFiles(playableVideos) }
                        // Durations are part of the visible card title. They
                        // must not wait for thumbnail extraction: a failed or
                        // slow frame request used to leave every Videos title
                        // without its duration indefinitely.
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            probeMissingFreeboxDurations(settingsWithToken, playableVideos)
                        }
                        // Charger les artworks TMDB en arriere-plan
                        val artworkMap = mutableMapOf<String, String>()
                        val backdropMap = mutableMapOf<String, String>()
                        val nextLaunchArtworkMap = mutableMapOf<String, String>()
                        val nextLaunchBackdropMap = mutableMapOf<String, String>()
                        val metadataMap = mutableMapOf<String, FreeboxVideoMeta>()
                        // Recording metadata is resolved through the native
                        // by_time endpoint. Avoid the legacy broad endpoint
                        // probing here: it creates a burst of rejected calls
                        // before the useful request and can trigger rate_limit.
                        val recentProgrammes = emptyList<FreeboxEpgProgram>()
                        videos.forEach { entry ->
                            try {
                                val contentId = freeboxContentIdForEntry(entry)
                                val programmeKey = freeboxProgrammeOverrideKey(entry.name, entry.isDirectory)
                                fun resolveFreeboxOverride(values: Map<String, String>): String? =
                                    values[contentId]
                                        ?: values[programmeKey]
                                        ?: values.entries.firstOrNull { (key, _) ->
                                            freeboxPathFromContentId(key) == freeboxPathFromContentId(contentId)
                                        }?.value
                                val manualPoster = resolveFreeboxOverride(freeboxPosterOverrides)
                                val manualBackdrop = resolveFreeboxOverride(freeboxBackdropOverrides)
                                val hasManualArtwork = manualPoster != null || manualBackdrop != null
                                // A manual selection is already the user's
                                // final artwork. Do not wait for EPG/TMDB
                                // enrichment before restoring it after reload.
                                val metadataSource = if (!hasManualArtwork && entry.isDirectory) {
                                    freeboxOsClient.listDirectory(settingsWithToken, entry.path)
                                        .getOrNull()
                                        ?.firstOrNull { child -> child.isFreeboxHomeVideoFile() }
                                } else if (!hasManualArtwork) {
                                    entry
                                } else null
                                val querySource = metadataSource ?: entry
                                val query = if (entry.isDirectory) {
                                    com.nuvio.tv.data.freebox.freeboxFolderTmdbSearchQuery(entry.name)
                                } else {
                                    com.nuvio.tv.data.freebox.freeboxTmdbSearchQuery(querySource.name)
                                }
                                val usableMetadataQuery = isUsableFreeboxMetadataQuery(query)
                                val programme = if (usableMetadataQuery && !hasManualArtwork) {
                                    findFreeboxProgramme(
                                        programmes = recentProgrammes,
                                        title = query,
                                        referenceTimeMs = querySource.recordingStartForEpg()
                                    ) ?: loadFreeboxProgrammeAtRecordingTime(
                                        settings = settingsWithToken,
                                        entry = querySource,
                                        title = query
                                    )
                                } else {
                                    null
                                }
                                val detailedProgramme = programme?.let { compactProgramme ->
                                    freeboxOsClient.loadTvProgrammeDetails(settingsWithToken, compactProgramme)
                                        .getOrNull()
                                        ?: compactProgramme
                                }
                                val epgMeta = detailedProgramme?.let {
                                    FreeboxVideoMeta(
                                        posterUrl = it.imageUrl,
                                        backdropUrl = null,
                                        overview = it.description.takeIf(String::isNotBlank),
                                        voteAverage = null,
                                        year = null,
                                        genres = it.genre?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                                    )
                                }
                                // A matching EPG programme remains the source of truth for
                                // the recording synopsis and artwork. TMDB is only queried
                                // through its strict title score and fills missing detail
                                // fields (credits, rating, runtime) without replacing EPG data.
                                val tmdbMeta = if (usableMetadataQuery && !hasManualArtwork) {
                                    tmdbService.fetchMetadataForTitleQuery(
                                        query = query,
                                        mediaTypeHint = freeboxTmdbMediaTypeHint(
                                            fileName = querySource.name,
                                            programme = detailedProgramme
                                        ),
                                        durationMinutes = querySource.durationMs
                                            ?.takeIf { it > 0L }
                                            ?.div(60_000L)
                                            ?.toInt()
                                            ?: freeboxDurationMsFromName(querySource.name)
                                                ?.div(60_000L)
                                                ?.toInt(),
                                        yearHint = Regex("\\b(?:19|20)\\d{2}\\b")
                                            .find(querySource.name)
                                            ?.value
                                            ?.toIntOrNull()
                                    )
                                } else {
                                    null
                                }
                                val selectedMeta = selectedMetadataOverrides[contentId]
                                    ?: selectedMetadataOverrides[programmeKey]
                                val meta = selectedMeta ?: enrichFreeboxVideoMetadata(epgMeta, tmdbMeta)
                                // File fingerprints are part of the content id;
                                // after a provider update size/mtime may change.
                                // Resolve the user's override by exact id, stable
                                // programme key, or the underlying Freebox path.
                                // A manually selected TMDB overview belongs to this exact recording.
                                // Do not reuse it for the following episode: the Freebox EPG synopsis
                                // must remain the current one for weekly programmes.
                                val manualOverview = selectedMeta?.overview
                                    ?: freeboxOverviewOverrides[contentId]
                                // Keep programme/provider artwork as the source of truth.
                                // A video frame is only a last-resort fallback when no
                                // original or manually selected poster can be resolved.
                                val resolvedArtwork = manualPoster
                                     ?: meta?.posterUrl
                                    ?: cachedPostersForRow[contentId]
                                    ?: metadataSource?.let { freeboxFrameThumbnailService.thumbnailUri(it) }
                                // Once Home displayed a cached thumbnail, freeze
                                // it for this session. A newly resolved official
                                // image is cached for the next launch instead of
                                // causing the row to refresh under the user's eyes.
                                val artwork = manualPoster
                                    ?: cachedPostersForRow[contentId]
                                    ?: resolvedArtwork
                                if (!artwork.isNullOrBlank()) {
                                    artworkMap[contentId] = artwork
                                }
                                resolvedArtwork?.takeIf { manualPoster == null }?.let {
                                    nextLaunchArtworkMap[contentId] = it
                                    nextLaunchArtworkMap[programmeKey] = it
                                }
                                val resolvedBackdrop = manualBackdrop
                                    ?: meta?.backdropUrl?.takeIf { it != resolvedArtwork }
                                    ?: cachedBackdropsForRow[contentId]
                                val backdrop = manualBackdrop
                                    ?: cachedBackdropsForRow[contentId]
                                    ?: resolvedBackdrop
                                resolvedBackdrop?.takeIf { manualBackdrop == null }?.let {
                                    nextLaunchBackdropMap[contentId] = it
                                    nextLaunchBackdropMap[programmeKey] = it
                                }
                                if (meta != null) {
                                    metadataMap[contentId] = manualOverview
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { meta.copy(overview = it, posterUrl = artwork, backdropUrl = backdrop) }
                                        ?: meta.copy(posterUrl = artwork, backdropUrl = backdrop)
                                } else if (!manualOverview.isNullOrBlank()) {
                                    metadataMap[contentId] = FreeboxVideoMeta(
                                        posterUrl = artwork,
                                        backdropUrl = backdrop,
                                        overview = manualOverview,
                                        voteAverage = null,
                                        year = null,
                                        genres = emptyList()
                                    )
                                }
                                if (!backdrop.isNullOrBlank()) {
                                    backdropMap[contentId] = backdrop
                                }
                            } catch (_: Exception) {}
                        }
                        // Persist only automatic results. Manual choices are
                        // already stored separately and retain higher priority.
                        freeboxArtworkCacheDataStore.putAll(
                            posters = nextLaunchArtworkMap,
                            backdrops = nextLaunchBackdropMap
                        )
                        // One atomic visual update per directory load. Publishing
                        // after every recording made the row flicker and caused
                        // Coil to restart requests continuously for many seconds.
                        // Cached cards never change during this session; only
                        // genuinely uncached cards are filled by this batch.
                        freeboxAutomaticArtwork = freeboxAutomaticArtwork +
                            artworkMap.filterKeys { it !in cachedPostersForRow }
                        freeboxAutomaticBackdrops = freeboxAutomaticBackdrops +
                            backdropMap.filterKeys { it !in cachedBackdropsForRow }
                        freeboxAutomaticMetadata = freeboxAutomaticMetadata + metadataMap
                        publishFreeboxArtwork()
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Impossible de lire le dossier Vidéos Freebox", error)
                    }
                if (directoryResult.isFailure) {
                    retryFreeboxVideos(settings, retryAttempt, directoryResult.exceptionOrNull())
                }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de charger les vidéos Freebox depuis l'accueil", e)
            retryFreeboxVideos(settings, retryAttempt, e)
        }
    }

    private fun retryFreeboxVideos(
        settings: FreeboxConnectionSettings,
        retryAttempt: Int,
        error: Throwable? = null,
    ) {
        // A missing Freebox Explorer grant is permanent until the owner validates
        // JojoTV on the Freebox. Retrying it only spams the server and logcat.
        if (retryAttempt >= 2 || error?.message?.contains("insufficient_rights") == true) {
            // An empty/failed first lookup is still a resolved Videos row for
            // startup focus: Home can now safely fall through to Films.
            _uiState.update { it.copy(initialFreeboxVideosResolved = true) }
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            delay((retryAttempt + 1) * 1_500L)
            loadFreeboxVideos(settings, retryAttempt + 1)
        }
    }

    private suspend fun migrateFreeboxRenamedFiles(entries: List<FreeboxFileEntry>) {
        val allProgress = watchProgressRepository.allProgress.first()
        val freeboxProgress = allProgress.filter { it.contentId.startsWith("freebox:") && it.position > 0L }
        if (freeboxProgress.isEmpty()) return
        val currentEntries = entries.filter { !it.isDirectory }
        val currentByPath = currentEntries.associateBy { it.path.trim() }
        val currentPaths = currentByPath.keys
        val checkedDirectories = freeboxCheckedDirectories(entries)
        val orphaned = freeboxProgress.filter { progress ->
            val current = currentByPath[freeboxPathFromContentId(progress.contentId)]
            if (current == null) {
                true
            } else {
                val currentId = freeboxContentIdForEntry(current)
                progress.contentId != currentId ||
                    (progress.contentId == freeboxLegacyContentIdFor(progress.contentId) &&
                        !progress.isCompatibleLegacyFreeboxProgress(current))
            }
        }
        if (orphaned.isEmpty()) return
        val byDir = currentEntries.groupBy { it.path.substringBeforeLast("/") }
        orphaned.forEach { progress ->
            val pp = freeboxPathFromContentId(progress.contentId)
            currentByPath[pp]?.let { current ->
                val newId = freeboxContentIdForEntry(current)
                val legacyId = freeboxLegacyContentIdFor(progress.contentId)
                if (progress.contentId == legacyId &&
                    newId != progress.contentId &&
                    progress.isCompatibleLegacyFreeboxProgress(current)
                ) {
                    val migrated = progress.copy(
                        contentId = newId,
                        videoId = newId,
                        name = freeboxFileNameOnly(current.path),
                        duration = progress.duration.takeIf { it > 0L }
                            ?: current.durationMs?.takeIf { it > 0L }
                            ?: 0L
                    )
                    watchProgressRepository.saveProgress(migrated)
                }
                watchProgressRepository.removeProgress(progress.contentId)
                pruneStaleFreeboxFromContinueWatchingCache(progress.contentId)
                return@forEach
            }
            val dir = pp.substringBeforeLast("/")
            val pname = pp.substringAfterLast("/").substringBeforeLast(".").lowercase().trim()
            val candidates = byDir[dir].orEmpty()
            val best = candidates.maxByOrNull { nameSimilarityHome(pname, it.path.substringAfterLast("/").substringBeforeLast(".").lowercase().trim()) }
            val bname = best?.path?.substringAfterLast("/")?.substringBeforeLast(".")?.lowercase()?.trim()
            if (best == null || bname == null || nameSimilarityHome(pname, bname) < 0.6) {
                if (progress.isStaleFreeboxProgress(currentPaths, checkedDirectories)) {
                    watchProgressRepository.removeProgress(progress.contentId)
                    pruneStaleFreeboxFromContinueWatchingCache(progress.contentId)
                }
                return@forEach
            }
            val newId = freeboxContentIdForEntry(best)
            if (allProgress.any { it.contentId == newId && it.position > 0L }) {
                watchProgressRepository.removeProgress(progress.contentId)
                pruneStaleFreeboxFromContinueWatchingCache(progress.contentId)
                return@forEach
            }
            val migrated = progress.copy(contentId = newId, videoId = newId, name = freeboxFileNameOnly(best.path))
            watchProgressRepository.saveProgress(migrated)
            watchProgressRepository.removeProgress(progress.contentId)
            pruneStaleFreeboxFromContinueWatchingCache(progress.contentId)
        }
    }

    private suspend fun pruneStaleFreeboxFromContinueWatchingCache(contentId: String) {
        cwEnrichedInProgressOverlay.remove(contentId)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    item is ContinueWatchingItem.InProgress && item.progress.contentId == contentId
                }
            )
        }
        val cached = runCatching { cwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
        if (cached.none { it.contentId == contentId }) return
        cwEnrichmentCache.saveInProgressSnapshot(
            items = cached.filterNot { it.contentId == contentId },
            force = true
        )
    }

    private suspend fun pruneStaleFreeboxTreeFromContinueWatchingCache(folderPath: String) {
        val normalizedFolder = folderPath.trim().trimEnd('/')
        if (normalizedFolder.isBlank()) return
        val overlayKeysToRemove = synchronized(cwEnrichedInProgressOverlay) {
            cwEnrichedInProgressOverlay.keys.filter { contentId ->
                freeboxPathIsInDeletedFolder(freeboxPathFromContentId(contentId), normalizedFolder)
            }
        }
        overlayKeysToRemove.forEach(cwEnrichedInProgressOverlay::remove)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    item is ContinueWatchingItem.InProgress &&
                        freeboxPathIsInDeletedFolder(
                            freeboxPathFromContentId(item.progress.contentId),
                            normalizedFolder
                        )
                }
            )
        }
        val cached = runCatching { cwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
        if (cached.none { freeboxPathIsInDeletedFolder(freeboxPathFromContentId(it.contentId), normalizedFolder) }) return
        cwEnrichmentCache.saveInProgressSnapshot(
            items = cached.filterNot {
                freeboxPathIsInDeletedFolder(freeboxPathFromContentId(it.contentId), normalizedFolder)
            },
            force = true
        )
    }

    private fun nameSimilarityHome(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        fun bigrams(s: String) = (0 until s.length - 1).map { s.substring(it, it + 2) }
        val ba = bigrams(a); val bb = bigrams(b).toMutableList(); var inter = 0
        ba.forEach { bg -> val i = bb.indexOf(bg); if (i >= 0) { inter++; bb.removeAt(i) } }
        return 2.0 * inter / (ba.size + bigrams(b).size)
    }

    private fun findFreeboxProgramme(
        programmes: List<FreeboxEpgProgram>,
        title: String,
        referenceTimeMs: Long?
    ): FreeboxEpgProgram? {
        val normalizedTitle = normalizeFreeboxProgrammeTitle(title)
        if (normalizedTitle.isBlank()) return null
        return programmes
            .asSequence()
            .mapNotNull { programme ->
                val normalizedProgrammeTitle = normalizeFreeboxProgrammeTitle(programme.title)
                val exact = normalizedProgrammeTitle == normalizedTitle
                // A recording has a timestamp. At that timestamp, a near title
                // is still not an identity: the Freebox guide can contain
                // similarly named programmes on different channels. Accept
                // approximate names only when no recording time is available;
                // otherwise one unrelated EPG synopsis was propagated to every
                // Home video card and then into the player.
                val related = referenceTimeMs == null &&
                    normalizedTitle.length >= 5 && normalizedProgrammeTitle.length >= 5 && (
                    normalizedProgrammeTitle.contains(normalizedTitle) ||
                    normalizedTitle.contains(normalizedProgrammeTitle) ||
                    nameSimilarityHome(normalizedProgrammeTitle, normalizedTitle) >= 0.88
                    )
                if (!exact && !related) return@mapNotNull null
                val titleScore = if (exact) 0L else 1L
                val timeScore = referenceTimeMs?.let { kotlin.math.abs(programme.startTime - it) } ?: 0L
                if (referenceTimeMs != null && timeScore > MAX_FREEBOX_EPG_MATCH_DISTANCE_MS) {
                    return@mapNotNull null
                }
                programme to (titleScore to timeScore)
            }
            .minWithOrNull(compareBy<Pair<FreeboxEpgProgram, Pair<Long, Long>>> { it.second.first }
                .thenBy { it.second.second })
            ?.first
    }

    /**
     * A recording often leaves the current seven-day guide before the user opens
     * the Videos row. Ask the native EPG at its original time instead of relying
     * on the current guide window.
     */
    private suspend fun loadFreeboxProgrammeAtRecordingTime(
        settings: FreeboxConnectionSettings,
        entry: FreeboxFileEntry,
        title: String
    ): FreeboxEpgProgram? {
        val recordingStart = entry.recordingStartForEpg() ?: return null
        // The EPG client intentionally serializes requests to protect the
        // Freebox. The former three-position fallback therefore made one Home
        // row wait several seconds per recording. The computed start is the
        // only timestamp that is both specific to this recording and needed to
        // obtain its first artwork; detailed fallback can happen later on
        // demand instead of blocking the Home row.
        val snapshot = freeboxOsClient.loadTvGuideAt(settings, recordingStart).getOrNull()
            ?: return null
        return findFreeboxProgramme(snapshot.programmes, title, recordingStart)
    }

    /**
     * Freebox timestamps describe the end of a downloaded recording.  The
     * metadata probe runs asynchronously, so use the duration written in the
     * recording filename until [FreeboxFileEntry.durationMs] becomes available.
     */
    private fun FreeboxFileEntry.recordingStartForEpg(): Long? {
        val recordingEnd = modifiedMs?.takeIf { it > 0L } ?: return null
        val duration = durationMs?.takeIf { it > 0L } ?: freeboxDurationMsFromName(name)
        return duration?.let { (recordingEnd - it).coerceAtLeast(0L) } ?: recordingEnd
    }

    private fun FreeboxFileEntry.isFreeboxHomeVideoFile(): Boolean {
        // The Freebox API can return an encoded or normalized path while the
        // display name has no usable suffix.  Use the same resilient lookup as
        // the Freebox browser, otherwise a valid file gets silently excluded
        // from the Home "Vidéos" row.
        val extension = listOf(name, path, encodedPath.orEmpty())
            .firstNotNullOfOrNull { candidate ->
                candidate
                    .substringBefore('?', missingDelimiterValue = candidate)
                    .substringAfterLast('/', missingDelimiterValue = candidate)
                    .substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
                    .takeIf { it.isNotBlank() }
            }
            .orEmpty()
        return extension in FREEBOX_HOME_VIDEO_EXTENSIONS
    }

    private fun FreeboxFileEntry.isDeletedFreeboxHomeVideo(deletedItems: Map<String, String>): Boolean {
        if (isDirectory) return false
        val deletedContentId = deletedItems[path.trim()] ?: return false
        val currentContentId = freeboxContentIdForEntry(this)
        val currentLegacyContentId = freeboxLegacyContentIdFor(currentContentId)
        return deletedContentId == currentContentId || deletedContentId == currentLegacyContentId
    }

    private fun WatchProgress.matchesDeletedFreeboxEntry(
        entry: FreeboxFileEntry,
        contentId: String,
        legacyContentId: String
    ): Boolean {
        if (this.contentId == contentId || this.contentId == legacyContentId) return true
        val progressPath = freeboxPathFromContentId(this.contentId)
        val deletedPath = entry.path.trim().trimEnd('/')
        return if (entry.isDirectory) {
            freeboxPathIsInDeletedFolder(progressPath, deletedPath)
        } else {
            progressPath == entry.path
        }
    }

    private fun freeboxPathIsInDeletedFolder(path: String, folderPath: String): Boolean {
        val normalizedPath = path.trim().trimEnd('/')
        val normalizedFolder = folderPath.trim().trimEnd('/')
        return normalizedPath.isNotBlank() &&
            normalizedFolder.isNotBlank() &&
            (normalizedPath == normalizedFolder || normalizedPath.startsWith("$normalizedFolder/"))
    }

    override fun onCleared() {
        startupAuthNoticeJob?.cancel()
        posterStatusReconcileJob?.cancel()
        movieWatchedBatchJob?.cancel()
        seriesWatchedObserverJob?.cancel()
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}

internal fun freeboxCheckedDirectories(entries: List<FreeboxFileEntry>): Set<String> =
    entries
        .filter { !it.isDirectory }
        .mapTo(mutableSetOf()) { it.path.trim().substringBeforeLast("/", missingDelimiterValue = "") }
        .filterTo(mutableSetOf()) { it.isNotBlank() }

internal fun com.nuvio.tv.domain.model.WatchProgress.isStaleFreeboxProgress(
    currentPaths: Set<String>,
    checkedDirectories: Set<String>
): Boolean {
    if (!contentId.startsWith("freebox:")) return false
    val path = freeboxPathFromContentId(contentId)
    if (path.isBlank() || path in currentPaths) return false
    val parent = path.substringBeforeLast("/", missingDelimiterValue = "")
    return parent.isNotBlank() && parent in checkedDirectories
}

internal fun com.nuvio.tv.domain.model.WatchProgress.isCompatibleLegacyFreeboxProgress(
    current: FreeboxFileEntry
): Boolean {
    val currentDuration = current.durationMs?.takeIf { it > 0L }
    if (duration > 0L && currentDuration != null &&
        kotlin.math.abs(duration - currentDuration) > 5_000L
    ) {
        return false
    }

    val currentModifiedMs = current.modifiedMs?.takeIf { it > 0L }
    if (lastWatched > 0L && currentModifiedMs != null) {
        return currentModifiedMs <= lastWatched + 60_000L
    }

    return duration > 0L && currentDuration != null
}
