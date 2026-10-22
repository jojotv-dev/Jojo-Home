@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.nuvio.tv.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.request.CachePolicy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jojotv.home.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxFileNameOnly
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.HomeCardSize
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.homeCoverflowArtworkUrl
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.toShortSynopsis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

internal fun resolveModernHomeInitialRowKey(
    carouselRowKeys: List<String>,
    hasFreeboxVideos: Boolean,
    freeboxVideosResolved: Boolean
): String? {
    carouselRowKeys.firstOrNull { it == MODERN_CONTINUE_WATCHING_ROW_KEY }?.let { return it }
    // Videos is loaded after the first Home frame. Do not prematurely focus
    // Films while the initial Freebox directory result is still pending.
    if (!freeboxVideosResolved) return null
    if (hasFreeboxVideos) return "freebox_videos"
    carouselRowKeys.firstOrNull {
        it.endsWith("_${HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID}")
    }?.let { return it }
    carouselRowKeys.firstOrNull {
        it.endsWith("_${HomeViewModel.IPTV_SERIES_FAVORITES_CATALOG_ID}")
    }?.let { return it }
    return carouselRowKeys.firstOrNull()
}

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    focusState: HomeScreenFocusState,
    iptvBackdropOverrides: Map<String, String> = emptyMap(),
    continueWatchingReady: Boolean = true,
    enrichingItemId: String? = null,
    lastEnrichedPreview: MetaPreview? = null,
    enrichedPreviews: Map<String, MetaPreview> = emptyMap(),
    failedEnrichmentIds: Set<String> = emptySet(),
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            item.contentId(),
            item.contentType(),
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onSearchContinueWatchingPoster: ((ContinueWatchingItem) -> Unit)? = null,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onRemoveIptvFavorite: (ContinueWatchingItem) -> Unit = {},
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    continueWatchingPortraitMode: Boolean = false,
    videoPortraitMode: Boolean = false,
    movieFavoritesPortraitMode: Boolean = true,
    seriesFavoritesPortraitMode: Boolean = true,
    continueWatchingThumbnailSize: com.nuvio.tv.domain.model.ThumbnailSize = com.nuvio.tv.domain.model.ThumbnailSize.DEFAULT,
    carouselCardSize: com.nuvio.tv.domain.model.HomeCardSize = com.nuvio.tv.domain.model.HomeCardSize.DEFAULT,
    mediaRowCardSize: com.nuvio.tv.domain.model.HomeCardSize = com.nuvio.tv.domain.model.HomeCardSize.DEFAULT,
    onDeleteFreeboxProgress: ((ContinueWatchingItem) -> Unit)? = null,
    onRenameFreeboxProgress: (ContinueWatchingItem, String) -> Unit = { _, _ -> },
    onMarkFreeboxUnwatched: (ContinueWatchingItem) -> Unit = {},
    onRenameFreeboxVideo: (com.nuvio.tv.data.freebox.FreeboxFileEntry, String) -> Unit = { _, _ -> },
    onDeleteFreeboxVideo: (com.nuvio.tv.data.freebox.FreeboxFileEntry) -> Unit = {},
    onNavigateToFreebox: (String, String?, String?, com.nuvio.tv.core.tmdb.FreeboxVideoMeta?) -> Unit = { _, _, _, _ -> },
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Int, Int) -> Unit,
    scrollToTopTrigger: Int = 0,
    onRequestLazyCatalogLoad: (String) -> Unit = {},
    onRowItemFocusedCallback: (String, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    val onRowItemFocusedPassedDown = rememberUpdatedState(onRowItemFocusedCallback)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val sidebarExpanded = LocalSidebarExpanded.current
    val isSidebarExpanded = remember(sidebarExpanded) { derivedStateOf { sidebarExpanded } }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keep the carousel's persisted artwork mode independent. IPTV Films and
    // Séries are forced to landscape at the row boundary in ModernHomeRowsList;
    // this prevents a media-row fix from changing Continue Watching artwork.
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    // The former global switch is kept only for preference migration. Row
    // presentation is now controlled exclusively by the per-row key sets.
    val useDetailedHomeMediaRows = false
    val homeRowCarouselKeys = uiState.homeRowCarouselKeys
    val homeRowDetailedKeys = uiState.homeRowDetailedKeys
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    // Home navigation must never start a trailer merely because focus moved.
    // Trailers are explicitly requested from the long-press action instead.
    val effectiveAutoplayEnabled = false
    val landscapeExpandedCardMode =
        useLandscapePosters &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && !useLandscapePosters) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
    // HomeScreen now waits for the ViewModel presentation that matches the CW
    // snapshot. Rendering a synchronous fallback here exposed the legacy row
    // for one frame before the detailed coverflow preference was restored.
    val presentation = uiState.modernHomePresentation
    val carouselRows = presentation.rows

    val hasCollections = remember(uiState.homeRows) {
        uiState.homeRows.any { it is HomeRow.CollectionRow }
    }
    val hasCatalogs = uiState.catalogRows.isNotEmpty()
    if (hasCollections && !hasCatalogs && uiState.installedAddonsCount > 0 && uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    // Freebox Videos is a real Home row even when there is no Continue
    // Watching, catalog, collection or IPTV favorite row.  Returning early
    // here made the empty background win over that row; adding any resume item
    // then happened to make Videos reappear because it created a carousel row.
    if (carouselRows.list.isEmpty() && uiState.freeboxVideoEntries.isEmpty()) {
        if (uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.nuvio.tv.ui.components.HeroCarousel(
                    items = uiState.heroItems.asStable(),
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.apiType, "")
                    },
                    onItemFocus = onItemFocus
                )
            }
        } else {
            EmptyHomeGlitterBackground()
        }
        return
    }

    val carouselLookups = presentation.lookups
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val rowKeyByGlobalRowIndex = carouselLookups.rowKeyByGlobalRowIndex
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds

    // Initial placement starts at the first row. Saved-card restoration later
    // scrolls this same state to the exact row before requesting card focus.
    val verticalRowListState = rememberLazyListState()
    val isVerticalRowsScrollingState = remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    val rowListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val loadMoreRequestedTotals = remember { mutableStateMapOf<String, Int>() }

    val focusedItemByRow = remember { mutableStateMapOf<String, Int>() }
    val stableFocusedItemByRow = remember { StableRef<MutableMap<String, Int>>(focusedItemByRow) }
    val stableRowListStates = remember { StableRef<MutableMap<String, LazyListState>>(rowListStates) }
    val stableLoadMoreRequestedTotals = remember { StableRef<MutableMap<String, Int>>(loadMoreRequestedTotals) }
    if (focusedItemByRow.isEmpty() && focusState.hasSavedFocus) {
        val savedRowKey = focusState.focusedRowKey
        if (savedRowKey != null) {
            val savedItemKey = focusState.focusedItemKeyByRow[savedRowKey]
            if (savedItemKey != null) {
                val row = carouselRows.list.firstOrNull { it.key == savedRowKey }
                if (row != null) {
                    val itemIndex = row.items.list.indexOfFirst { it.key == savedItemKey }
                    if (itemIndex >= 0) {
                        focusedItemByRow[savedRowKey] = itemIndex
                    }
                }
            }
        }
    }

    val focusHolder = remember {
        object {
            var activeRowKey: String? = null
            var activeItemIndex: Int = 0
        }
    }
    val activeRowKey = remember { mutableStateOf<String?>(null) }
    val activeItemIndex = remember { mutableIntStateOf(0) }
    // Cold-start focus follows the visible Home hierarchy. It must never steal
    // focus after the user has already landed on a row.
    var initialHomeFocusApplied by rememberSaveable { mutableStateOf(false) }
    val pendingRowFocusKey = remember { mutableStateOf<String?>(null) }
    val pendingRowFocusIndex = remember { mutableStateOf<Int?>(null) }
    val pendingRowFocusNonce = remember { mutableIntStateOf(0) }
    val restoredFromSavedState = remember { mutableStateOf(false) }
    val heroItem = remember {
        val initialHero = carouselRows.list.firstOrNull()?.items?.list?.firstOrNull()?.heroPreview
        mutableStateOf<HeroPreview?>(initialHero)
    }
    val optionsItem = remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val renameOptionsItem = remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndex = remember { mutableIntStateOf(-1) }
    val lastFocusedContinueWatchingKey = remember { mutableStateOf<String?>(null) }
    val lastHeroNavigationAtMs = remember { mutableLongStateOf(0L) }
    val heroFocusSettleDelayMs = remember { mutableLongStateOf(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
    val isFastScrolling = remember { mutableStateOf(false) }
    val isRapidHorizontalNav = remember { mutableStateOf(false) }
    val focusedCatalogSelection = remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    val focusedFreeboxVideo = remember { mutableStateOf<MetaPreview?>(null) }
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    val expandedCatalogFocusKey = remember { mutableStateOf<String?>(null) }
    val focusedHeroMediaNonce = remember { mutableIntStateOf(0) }
    var endedCollectionHeroVideoPlaybackKey by remember { mutableStateOf<String?>(null) }
    val expansionInteractionNonce = remember { mutableIntStateOf(0) }


    // `scrollToItem(0, 0)` pins the first row to the physical viewport edge,
    // bypassing LazyColumn's content padding and clipping the coverflow title.
    // Keep the same header clearance as the normal initial layout.
    val modernRowHeaderInsetPx = with(LocalDensity.current) {
        MODERN_ROW_HEADER_FOCUS_INSET.roundToPx()
    }
    LaunchedEffect(scrollToTopTrigger, modernRowHeaderInsetPx) {
        if (scrollToTopTrigger > 0) {
            verticalRowListState.scrollToItem(0, -modernRowHeaderInsetPx)
        }
    }

    // A saved position from Videos/Films must never be restored over the
    // initial Continue Watching row.  Besides causing the visible jump, that
    // restored offset places the first title above the viewport and clips it.
    LaunchedEffect(initialHomeFocusApplied, modernRowHeaderInsetPx) {
        if (initialHomeFocusApplied) {
            verticalRowListState.scrollToItem(0, -modernRowHeaderInsetPx)
        }
    }

    val currentView = LocalView.current
    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(currentView)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.isScrollInProgress }
            .collect { scrolling ->
                metricsHolder.state?.putState("HomeScrolling", scrolling.toString())
            }
    }
    LaunchedEffect(enrichingItemId) {
        metricsHolder.state?.putState("HeroEnriching", (enrichingItemId != null).toString())
    }

    LaunchedEffect(
        focusedCatalogSelection.value?.focusKey,
        expansionInteractionNonce.intValue,
        shouldActivateFocusedPosterFlow,
        trailerPlaybackTarget,
        uiState.focusedPosterBackdropExpandDelaySeconds,
        verticalRowListState.isScrollInProgress
    ) {
        expandedCatalogFocusKey.value = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
        val selection = focusedCatalogSelection.value ?: return@LaunchedEffect
        if (selection.payload !is ModernPayload.Catalog) return@LaunchedEffect
        val expansionDelayMs = (uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L).coerceAtLeast(150L)
        delay(expansionDelayMs)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (shouldActivateFocusedPosterFlow &&
            !verticalRowListState.isScrollInProgress &&
            focusedCatalogSelection.value?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey.value = selection.focusKey
        }
    }

    LaunchedEffect(
        focusedCatalogSelection.value?.focusKey,
        activeRowKey.value,
        effectiveAutoplayEnabled,
        verticalRowListState.isScrollInProgress
    ) {
        if (!effectiveAutoplayEnabled) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
        val selection = focusedCatalogSelection.value ?: run {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        val payload = selection.payload as? ModernPayload.Catalog ?: run {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (selection.focusKey == lastRequestedTrailerFocusKey) return@LaunchedEffect
        delay(150)
        if (focusedCatalogSelection.value?.focusKey != selection.focusKey) return@LaunchedEffect
        onRequestTrailerPreview(
            payload.itemId,
            payload.trailerTitle,
            payload.trailerReleaseInfo,
            payload.trailerApiType
        )
        lastRequestedTrailerFocusKey = selection.focusKey
    }

    LaunchedEffect(
        carouselRows,
        uiState.freeboxVideoEntries,
        uiState.initialFreeboxVideosResolved,
        focusState.hasSavedFocus,
        continueWatchingReady
    ) {
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        val staleSelection = focusedCatalogSelection.value?.let { selection ->
            when (val payload = selection.payload) {
                is ModernPayload.Catalog -> !payload.itemId.startsWith("__placeholder_") && payload.itemId !in activeCatalogItemIds.set
                is ModernPayload.CollectionFolder -> carouselRows.list.none { row ->
                    row.items.list.any { item ->
                        (item.payload as? ModernPayload.CollectionFolder)?.focusKey == selection.focusKey
                    }
                }
                is ModernPayload.ContinueWatching -> true
            }
        } ?: false
        if (staleSelection) {
            focusedCatalogSelection.value = null
            expandedCatalogFocusKey.value = null
        }
        val currentSelection = focusedCatalogSelection.value
        val currentCatalogPayload = currentSelection?.payload as? ModernPayload.Catalog
        if (currentSelection != null && currentCatalogPayload != null && currentCatalogPayload.itemId.startsWith("__placeholder_")) {
            val activeKey = focusHolder.activeRowKey
            val activeIdx = focusHolder.activeItemIndex
            val activeRow = activeKey?.let { rowByKey.map[it] }
            val realItem = activeRow?.items?.list?.getOrNull(activeIdx)
            val realPayload = realItem?.payload as? ModernPayload.Catalog
            if (realPayload != null && !realPayload.itemId.startsWith("__placeholder_")) {
                focusedCatalogSelection.value = FocusedCatalogSelection(
                    focusKey = realPayload.focusKey,
                    payload = realPayload
                )
                lastRequestedTrailerFocusKey = null
                expandedCatalogFocusKey.value = null
            }
        }

        carouselRows.list.forEach { row ->
            if (row.items.list.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        // Never let an incomplete Home snapshot choose a catalog row as its
        // initial focus.  This is a second safety net for the startup gate:
        // it prevents a transient Films frame from becoming the focus target
        // while Continue Watching or Videos is still being restored.
        if (!continueWatchingReady) return@LaunchedEffect
        val initialRowKey = resolveModernHomeInitialRowKey(
            carouselRowKeys = carouselRows.list.map { it.key },
            hasFreeboxVideos = uiState.freeboxVideoEntries.isNotEmpty(),
            freeboxVideosResolved = uiState.initialFreeboxVideosResolved
        )
        if (!focusState.hasSavedFocus && initialRowKey == null) return@LaunchedEffect
        val canClaimInitialHomeFocus = focusHolder.activeRowKey == null &&
            activeRowKey.value == null
        val continueWatchingOwnsColdStart =
            uiState.continueWatchingItems.isNotEmpty() &&
                initialRowKey == MODERN_CONTINUE_WATCHING_ROW_KEY
        if (!initialHomeFocusApplied &&
            // On a real application start, Continue Watching is the mandated
            // landing row even if an old persisted row focus exists. Saved
            // focus remains the fallback when there is no CW content.
            (continueWatchingOwnsColdStart || !focusState.hasSavedFocus) &&
            initialRowKey != null &&
            canClaimInitialHomeFocus
        ) {
            val initialRow = rowByKey.map[initialRowKey]
            val initialItemCount = if (initialRowKey == "freebox_videos") {
                uiState.freeboxVideoEntries.size
            } else {
                initialRow?.items?.list?.size ?: 0
            }
            val initialIndex = focusedItemByRow[initialRowKey]
                ?.coerceIn(0, (initialItemCount - 1).coerceAtLeast(0))
                ?: 0
            focusHolder.activeRowKey = initialRowKey
            focusHolder.activeItemIndex = initialIndex
            activeRowKey.value = initialRowKey
            activeItemIndex.intValue = initialIndex
            focusedItemByRow[initialRowKey] = initialIndex
            heroItem.value = initialRow?.items?.getOrNull(initialIndex)?.heroPreview
                ?: initialRow?.items?.firstOrNull()?.heroPreview
            pendingRowFocusKey.value = initialRowKey
            pendingRowFocusIndex.value = initialIndex
            pendingRowFocusNonce.intValue++
            initialHomeFocusApplied = true
            restoredFromSavedState.value = true
            return@LaunchedEffect
        }

        if (!restoredFromSavedState.value && focusState.hasSavedFocus) {
            val savedRowKey = focusState.focusedRowKey ?: when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> rowKeyByGlobalRowIndex.map[focusState.focusedRowIndex]
                else -> null
            }

            if (savedRowKey == "freebox_videos" && uiState.freeboxVideoEntries.isNotEmpty()) {
                val savedVideoKey = focusState.focusedItemKeyByRow[savedRowKey]
                val savedVideoIndex = savedVideoKey?.let { key ->
                    uiState.freeboxVideoEntries.indexOfFirst { entry ->
                        freeboxContentIdForEntry(entry) == key
                    }.takeIf { it >= 0 }
                }
                val resolvedIndex = (savedVideoIndex ?: focusState.focusedItemIndex)
                    .coerceIn(0, (uiState.freeboxVideoEntries.size - 1).coerceAtLeast(0))
                focusHolder.activeRowKey = savedRowKey
                focusHolder.activeItemIndex = resolvedIndex
                activeRowKey.value = savedRowKey
                activeItemIndex.intValue = resolvedIndex
                focusedItemByRow[savedRowKey] = resolvedIndex
                pendingRowFocusKey.value = savedRowKey
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
                restoredFromSavedState.value = true
                return@LaunchedEffect
            }

            // Returning from a fiche/player must restore the saved row first.
            // Continue Watching remains only the cold-start fallback.
            val resolvedRow = savedRowKey?.let { rowByKey.map[it] }
                ?: if (savedRowKey == null) {
                    rowByKey.map[MODERN_CONTINUE_WATCHING_ROW_KEY]
                        ?: carouselRows.list.firstOrNull()
                } else {
                    null // The saved row may still be loading; wait for it.
                }
            if (resolvedRow != null) {
                val savedItemKey = focusState.focusedItemKeyByRow[resolvedRow.key]
                val resolvedIndex = if (savedItemKey != null) {
                    resolvedRow.items.list.indexOfFirst { it.key == savedItemKey }.coerceAtLeast(0)
                } else {
                    focusState.focusedItemIndex
                        .coerceAtLeast(0)
                        .coerceAtMost((resolvedRow.items.list.size - 1).coerceAtLeast(0))
                }

                focusHolder.activeRowKey = resolvedRow.key
                focusHolder.activeItemIndex = resolvedIndex
                activeRowKey.value = resolvedRow.key
                activeItemIndex.intValue = resolvedIndex
                focusedItemByRow[resolvedRow.key] = resolvedIndex
                heroItem.value = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                    ?: resolvedRow.items.firstOrNull()?.heroPreview
                pendingRowFocusKey.value = resolvedRow.key
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
                restoredFromSavedState.value = true
                return@LaunchedEffect
            }
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let { rowByKey[it] }
        val firstRow = rowByKey.map[MODERN_CONTINUE_WATCHING_ROW_KEY]
            ?: carouselRows.list.firstOrNull()

        val resolvedActive = when {
            existingActive != null -> existingActive
            hadActiveRow -> null // Wait for data to reappear
            else -> firstRow     // Initial state
        }

        if (resolvedActive != null) {
            val resolvedIndex = focusedItemByRow[resolvedActive.key]
                ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
                ?: 0
            focusHolder.activeRowKey = resolvedActive.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey.value = resolvedActive.key
            activeItemIndex.intValue = resolvedIndex
            focusedItemByRow[resolvedActive.key] = resolvedIndex
            heroItem.value = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedActive.items.firstOrNull()?.heroPreview

            if (!focusState.hasSavedFocus && !hadActiveRow) {
                pendingRowFocusKey.value = resolvedActive.key
                pendingRowFocusIndex.value = resolvedIndex
                pendingRowFocusNonce.intValue++
            }
        }

        if (!restoredFromSavedState.value && carouselRows.list.isNotEmpty()) {
            restoredFromSavedState.value = true
        }
    }

    val activeRow by remember(carouselRows, rowByKey) {
        derivedStateOf {
            val activeKey = activeRowKey.value
            if (activeKey == null) null
            else rowByKey[activeKey]
        }
    }
    val clampedActiveItemIndex by remember(activeRow) {
        derivedStateOf {
            activeRow?.let { row ->
                activeItemIndex.intValue.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val savedIdx = focusedItemByRow[row.key] ?: 0
        val clampedIndex = savedIdx.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (focusHolder.activeItemIndex != clampedIndex) {
            focusHolder.activeItemIndex = clampedIndex
            activeItemIndex.intValue = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    val activeHeroItemKey by remember(activeRow, clampedActiveItemIndex) {
        derivedStateOf {
            val row = activeRow ?: return@derivedStateOf null
            row.items.getOrNull(clampedActiveItemIndex)?.key ?: row.items.firstOrNull()?.key
        }
    }

    // Detect rapid horizontal navigation (holding DPAD left/right).
    // Activates after two successive item changes within the SAME row within 300ms.
    val lastRapidNavRowKey = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeHeroItemKey) {
        if (activeHeroItemKey == null) return@LaunchedEffect
        val currentRowKey = activeRowKey.value
        val timeSinceLast = System.currentTimeMillis() - lastHeroNavigationAtMs.longValue
        // Only activate rapid nav if we're in the same row as last navigation
        if (timeSinceLast in 1..300 && lastRapidNavRowKey.value == currentRowKey) {
            isRapidHorizontalNav.value = true
        }
        lastRapidNavRowKey.value = currentRowKey
        delay(heroFocusSettleDelayMs.longValue)
        isRapidHorizontalNav.value = false
    }

    val latestHeroRow by rememberUpdatedState(activeRow)
    val latestHeroIndex by rememberUpdatedState(clampedActiveItemIndex)
    LaunchedEffect(activeHeroItemKey, verticalRowListState) {
        if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
        val targetHeroKey = activeHeroItemKey ?: return@LaunchedEffect
        val settleDelayMs = heroFocusSettleDelayMs.longValue
        delay(settleDelayMs)
        if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
        if (System.currentTimeMillis() - lastHeroNavigationAtMs.longValue < settleDelayMs) return@LaunchedEffect
        val row = latestHeroRow ?: return@LaunchedEffect
        val latestKey = row.items.getOrNull(latestHeroIndex)?.key ?: row.items.firstOrNull()?.key
        if (latestKey != targetHeroKey) return@LaunchedEffect
        val latestHero =
            row.items.getOrNull(latestHeroIndex)?.heroPreview ?: row.items.firstOrNull()?.heroPreview
        if (latestHero != null && heroItem.value != latestHero) {
            heroItem.value = latestHero
        }
    }

    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveRowKey by rememberUpdatedState(activeRowKey.value)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestFreeboxVideoEntries by rememberUpdatedState(uiState.freeboxVideoEntries)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    val latestRowIndexByKey = rememberUpdatedState(rowIndexByKey)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowKey = row?.key
                ?: latestActiveRowKey.takeIf { it == "freebox_videos" }
            // Only save focus state if home screen actually had focus (focusedRowKey is not null)
            // This prevents saving invalid state like row -1 when sidebar was open
            if (focusedRowKey == null) {
                // Home screen didn't have focus, don't overwrite the saved state
                return@onDispose
            }

            val focusedRowIndex = focusedRowKey?.let { latestRowIndexByKey.value.map[it] } ?: -1
            val focusedItemIndex = activeItemIndex.intValue

            val focusedItemKeyByRow = latestCarouselRows
                .associate { rowState ->
                    val focusedIdx = focusedItemByRow[rowState.key] ?: 0
                    val itemKey = rowState.items.list.getOrNull(focusedIdx)?.key ?: ""
                    rowState.key to itemKey
                }
                .toMutableMap()
                .apply {
                    if (focusedRowKey == "freebox_videos") {
                        latestFreeboxVideoEntries
                            .getOrNull(focusedItemIndex)
                            ?.let { entry ->
                                this["freebox_videos"] = freeboxContentIdForEntry(entry)
                            }
                    }
                }

            val catalogRowScrollStates = latestCarouselRows
                .associate { rowState ->
                    val scrollIndex = rowListStates[rowState.key]?.firstVisibleItemIndex
                        ?: (focusedItemByRow[rowState.key] ?: 0)
                    rowState.key to scrollIndex
                }

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowKey,
                focusedItemKeyByRow,
                catalogRowScrollStates,
                focusedRowIndex,
                focusedItemIndex
            )
        }
    }

    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    // Keep portrait posters fully visible and leave enough bottom margin for
    // the row inside the TV viewport.
    val portraitModernPosterScale = 0.97f
    val landscapeModernPosterScale = 1.34f
    // Keep the carousel preset independent from the media-row preset. Compact
    // is deliberately smaller than the former standard, while standard takes
    // the former compact footprint; large remains unchanged.
    val carouselScale = when (carouselCardSize) {
        HomeCardSize.SMALL -> 0.70f
        HomeCardSize.MEDIUM -> 0.75f
        HomeCardSize.LARGE -> 1f
    }
    // Videos, Films and Séries share the media-row preset. Continue Watching
    // alone uses carouselScale below.
    val mediaScale = mediaRowCardSize.mediaRowScale
    val portraitCatalogCardWidth = portraitBaseWidth * 0.84f * portraitModernPosterScale * mediaScale
    val landscapeCatalogCardWidth = portraitBaseWidth * 1.24f * landscapeModernPosterScale * mediaScale
    val portraitCatalogCardHeight = portraitBaseHeight * 0.84f * portraitModernPosterScale * mediaScale
    val landscapeCatalogCardHeight = landscapeCatalogCardWidth / 1.77f
    val cwPortraitScale = continueWatchingThumbnailSize.cardWidth.value / 220f
    val continueWatchingCardWidth = if (continueWatchingPortraitMode) {
        (126f * cwPortraitScale * carouselScale).dp
    } else {
        continueWatchingThumbnailSize.cardWidth * carouselScale
    }
    val continueWatchingCardHeight = if (continueWatchingPortraitMode) {
        (189f * cwPortraitScale * carouselScale).dp
    } else {
        continueWatchingThumbnailSize.imageHeight * carouselScale
    }
    // Videos, Films and Séries share one landscape geometry; the shared media
    // row size is the only scale applied to those three rows.
    // Use the same exact geometry as the landscape Films/Séries cards.
    val videoCardWidth = landscapeCatalogCardWidth
    val videoCardHeight = landscapeCatalogCardHeight

    val localConfiguration = LocalConfiguration.current
    val screenWidth = localConfiguration.screenWidthDp.dp
    val screenHeight = localConfiguration.screenHeightDp.dp
    // Detailed Home media is still a browse page: its rows must coexist in
    // the viewport, rather than replacing it one category at a time.
    val isCoverflowHomeRow = false

    Box(modifier = Modifier.fillMaxSize()) {
            val posterCardCornerRadius = remember(uiState.posterCardCornerRadiusDp) { uiState.posterCardCornerRadiusDp.dp }
            val rowHorizontalPadding = 52.dp
            var heroMetadataHeightPx by remember { mutableIntStateOf(0) }

            val activeCarouselItemState = remember(carouselRows, rowByKey) {
                derivedStateOf {
                    val activeKey = activeRowKey.value
                    val row = if (activeKey == null) null
                    else rowByKey[activeKey]

                    val index = activeItemIndex.intValue
                    val clampedIdx = row?.let {
                        index.coerceIn(0, (it.items.size - 1).coerceAtLeast(0))
                    } ?: 0
                    row?.items?.getOrNull(clampedIdx)
                }
            }

            val resolvedHeroState = remember(
                activeCarouselItemState,
                enrichedPreviews,
                enrichingItemId,
                heroItem,
                uiState.heroEnrichmentEnabled,
                failedEnrichmentIds,
                uiState.freeboxVideoMetadata,
                iptvBackdropOverrides
            ) {
                derivedStateOf {
                    val activeCarouselItem = activeCarouselItemState.value
                    val activeItemId = activeCarouselItem?.metaPreview?.id
                    val enrichmentActive = enrichingItemId != null && enrichingItemId == activeItemId
                    val activeFreeboxVideo = focusedFreeboxVideo.value
                        ?.takeIf { activeRowKey.value == "freebox_videos" }
                    val activeFreeboxMeta = activeFreeboxVideo?.id
                        ?.let { uiState.freeboxVideoMetadata[it] }
                    val freeboxHero = activeFreeboxVideo?.let { item ->
                        HeroPreview(
                            title = item.name,
                            logo = item.logo,
                            description = item.description,
                            contentTypeText = "Freebox",
                            isSeries = false,
                            yearText = item.releaseInfo,
                            runtimeText = item.runtime,
                            imdbText = item.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                            ageRatingText = item.ageRating,
                            statusText = item.status,
                            countryText = item.country,
                            languageText = item.language?.uppercase(),
                            seasonCount = item.seasonCount ?: activeFreeboxMeta?.seasonCount,
                            cast = item.cast.ifEmpty { activeFreeboxMeta?.cast.orEmpty() }.asStable(),
                            director = item.director.ifEmpty { activeFreeboxMeta?.director.orEmpty() }.asStable(),
                            genres = item.genres.take(3).asStable(),
                            poster = item.poster,
                            backdrop = item.backdropUrl,
                            imageUrl = item.backdropUrl,
                            frozenBackdropUrl = item.backdropUrl,
                            frozenLogoUrl = item.logo
                        )
                    }

                    val enrichedItem = activeItemId?.let { enrichedPreviews[it] }
                    val baseHero = activeCarouselItem?.heroPreview
                    val activeIsIptvItem = activeCarouselItem?.metaPreview?.let { preview ->
                        preview.rawType == "iptv_movie" || preview.rawType == "iptv_series"
                    } == true
                    val enrichedHero = if (enrichedItem != null) {
                        HeroPreview(
                            title = enrichedItem.name,
                            logo = enrichedItem.logo ?: baseHero?.logo,
                            description = selectMostCompleteHeroDescription(
                                baseDescription = activeCarouselItem?.heroPreview?.description,
                                enrichedDescription = enrichedItem.description
                            ),
                            contentTypeText = activeCarouselItem?.heroPreview?.contentTypeText,
                            isSeries = isSeriesType(enrichedItem.apiType) || baseHero?.isSeries == true,
                            yearText = enrichedItem.releaseInfo ?: baseHero?.yearText,
                            runtimeText = enrichedItem.runtime ?: baseHero?.runtimeText,
                            imdbText = enrichedItem.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) }
                                ?: baseHero?.imdbText,
                            ageRatingText = enrichedItem.ageRating ?: baseHero?.ageRatingText,
                            statusText = enrichedItem.status ?: baseHero?.statusText,
                            countryText = enrichedItem.country ?: baseHero?.countryText,
                            languageText = enrichedItem.language?.uppercase() ?: baseHero?.languageText,
                            seasonCount = enrichedItem.seasonCount ?: baseHero?.seasonCount,
                            cast = enrichedItem.cast.ifEmpty { baseHero?.cast?.list.orEmpty() }
                                .asStable(),
                            director = enrichedItem.director.ifEmpty { baseHero?.director?.list.orEmpty() }
                                .asStable(),
                            genres = enrichedItem.genres.take(3).ifEmpty { baseHero?.genres?.list.orEmpty() }
                                .asStable(),
                            poster = enrichedItem.poster ?: baseHero?.poster,
                            backdrop = enrichedItem.backdropUrl ?: baseHero?.backdrop,
                            imageUrl = enrichedItem.backdropUrl ?: enrichedItem.poster ?: baseHero?.imageUrl,
                            frozenBackdropUrl = enrichedItem.backdropUrl ?: baseHero?.frozenBackdropUrl,
                            frozenLogoUrl = baseHero?.frozenLogoUrl ?: enrichedItem.logo
                        )
                    } else null
                    val activeIptvFavoriteBackdrop = if (activeIsIptvItem) {
                        val activePreview = activeCarouselItem?.metaPreview
                        resolveIptvFavoriteHeroBackdrop(
                            baseItem = activePreview,
                            cardImageUrl = activeCarouselItem?.imageUrl,
                            enrichedItem = enrichedItem,
                            manualBackdropUrl = activePreview?.id?.let(iptvBackdropOverrides::get)
                        )
                    } else {
                        null
                    }

                    val cwFreeboxItem = (activeCarouselItem?.payload as? ModernPayload.ContinueWatching)
                        ?.item
                        ?.let { it as? ContinueWatchingItem.InProgress }
                        ?.takeIf { it.progress.contentId.startsWith("freebox:", ignoreCase = true) }
                    val cwFreeboxContentId = cwFreeboxItem?.progress?.contentId
                    val cwFreeboxMetadata = cwFreeboxContentId?.let { contentId ->
                        uiState.freeboxVideoMetadata[contentId]
                    }
                    val cwFreeboxArtwork = cwFreeboxItem?.homeCoverflowArtworkUrl()
                    val resolvedHero = when {
                        activeCarouselItem == null -> null
                        cwFreeboxItem != null -> activeCarouselItem.heroPreview.copy(
                            description = activeCarouselItem.heroPreview.description
                                ?: cwFreeboxMetadata?.overview?.takeIf { it.isNotBlank() },
                            seasonCount = activeCarouselItem.heroPreview.seasonCount ?: cwFreeboxMetadata?.seasonCount,
                            cast = activeCarouselItem.heroPreview.cast.list
                                .ifEmpty { cwFreeboxMetadata?.cast.orEmpty() }
                                .asStable(),
                            director = activeCarouselItem.heroPreview.director.list
                                .ifEmpty { cwFreeboxMetadata?.director.orEmpty() }
                                .asStable(),
                            poster = cwFreeboxItem.progress.poster ?: activeCarouselItem.heroPreview.poster,
                            // The coverflow card and the Home background must resolve
                            // the exact same URL in the exact same order. A separate
                            // metadata fallback made the background anticipate an image
                            // that the still-loading card was not going to display.
                            backdrop = cwFreeboxArtwork,
                            imageUrl = cwFreeboxArtwork,
                            frozenBackdropUrl = cwFreeboxArtwork
                        )
                        enrichedHero != null -> enrichedHero
                        enrichmentActive -> activeCarouselItem.heroPreview
                        else -> activeCarouselItem.heroPreview
                    }

                    // Only use the real enrichmentActive flag from the ViewModel.
                    // Additionally, if enrichment is enabled but no enriched data exists yet
                    // for this item, treat as pending to avoid showing un-enriched addon data.
                    // Exception: if enrichment already failed for this item, show addon data.
                    // Also treat as pending when activeCarouselItem is null (row not yet resolved).
                    val heroEnrichmentEnabled = uiState.heroEnrichmentEnabled
                    val enrichmentFailed = activeItemId != null && activeItemId in failedEnrichmentIds
                    val activeIsFreeboxContinueWatching = cwFreeboxContentId != null
                    val effectiveEnrichmentActive = activeCarouselItem == null ||
                        shouldHideBaseHeroForEnrichment(
                            isIptvItem = activeIsIptvItem,
                            enrichmentActive = enrichmentActive,
                            enrichedHeroAvailable = enrichedHero != null
                        ) ||
                        (!activeIsIptvItem &&
                            !activeIsFreeboxContinueWatching &&
                            enrichedHero == null &&
                            activeItemId != null &&
                            heroEnrichmentEnabled &&
                            !enrichmentFailed)

                    val activeRowKeyVal = activeRowKey.value
                    val activeRow = activeRowKeyVal?.let { rowByKey[it] }
                    val activeRowFallbackBackdrop = activeRow?.items?.list?.firstNotNullOfOrNull { item ->
                        item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
                    }

                    // An IPTV title with no safe image must render no hero rather than
                    // fall through to resolvedHero/row data containing the IPTV
                    // provider banner that resolveIptvFavoriteHeroBackdrop rejected.
                    val heroBackdrop = if (activeIsIptvItem) {
                        activeIptvFavoriteBackdrop
                    } else {
                        firstNonBlank(
                            activeIptvFavoriteBackdrop,
                            freeboxHero?.backdrop,
                            freeboxHero?.imageUrl,
                            freeboxHero?.poster,
                            resolvedHero?.backdrop,
                            resolvedHero?.imageUrl,
                            resolvedHero?.poster,
                            activeRowFallbackBackdrop
                        )
                    }

                    Triple(heroBackdrop, freeboxHero ?: resolvedHero, if (freeboxHero != null) false else effectiveEnrichmentActive)
                }
            }

            val expandedFocusedSelectionState = remember {
                derivedStateOf {
                    focusedCatalogSelection.value
                        ?.takeIf { it.focusKey == expandedCatalogFocusKey.value }
                        ?.takeIf { it.payload is ModernPayload.Catalog }
                }
            }

            val heroTrailerUrlsState = remember(trailerPreviewUrls, trailerPreviewAudioUrls) {
                derivedStateOf {
                    val focusedSelection = expandedFocusedSelectionState.value
                        ?: focusedCatalogSelection.value?.takeIf {
                            val key = activeRowKey.value.orEmpty()
                            key.endsWith("_${HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID}") ||
                                key.endsWith("_${HomeViewModel.IPTV_SERIES_FAVORITES_CATALOG_ID}")
                        }
                    val itemId = (focusedSelection?.payload as? ModernPayload.Catalog)?.itemId
                    val url = itemId?.let { trailerPreviewUrls[it] }
                    val audioUrl = itemId?.let { trailerPreviewAudioUrls[it] }
                    url to audioUrl
                }
            }
            val expandedCatalogTrailerUrl = heroTrailerUrlsState.value.first
            val expandedCatalogTrailerAudioUrl = heroTrailerUrlsState.value.second
            val collectionHeroVideoUrl = (focusedCatalogSelection.value?.payload as? ModernPayload.CollectionFolder)?.heroVideoUrl
            val collectionHeroVideoPlaybackKey = remember(
                focusedCatalogSelection.value?.focusKey,
                collectionHeroVideoUrl,
                focusedHeroMediaNonce.intValue
            ) {
                val focusKey = focusedCatalogSelection.value?.focusKey
                val url = collectionHeroVideoUrl?.takeIf { it.isNotBlank() }
                if (focusKey != null && url != null) "$focusKey::${focusedHeroMediaNonce.intValue}::$url" else null
            }
            val shouldPlayCatalogHeroTrailerState = remember(
                effectiveAutoplayEnabled,
                trailerPlaybackTarget,
                heroTrailerUrlsState,
                verticalRowListState,
                isSidebarExpanded,
                isRapidHorizontalNav,
                activeRowKey.value
            ) {
                derivedStateOf {
                    val activeKey = activeRowKey.value.orEmpty()
                    val isIptvFavoriteHomeRow = activeKey.endsWith(
                        "_${HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID}"
                    ) || activeKey.endsWith(
                        "_${HomeViewModel.IPTV_SERIES_FAVORITES_CATALOG_ID}"
                    )
                    effectiveAutoplayEnabled &&
                        !isIptvFavoriteHomeRow &&
                        !isSidebarExpanded.value &&
                        !isRapidHorizontalNav.value &&
                        !verticalRowListState.isScrollInProgress &&
                        trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                        !heroTrailerUrlsState.value.first.isNullOrBlank()
                }
            }
            val shouldPlayCollectionHeroVideoState = remember(
                collectionHeroVideoUrl,
                collectionHeroVideoPlaybackKey,
                endedCollectionHeroVideoPlaybackKey,
                verticalRowListState,
                isSidebarExpanded
            ) {
                derivedStateOf {
                    !isSidebarExpanded.value &&
                        !verticalRowListState.isScrollInProgress &&
                        !collectionHeroVideoUrl.isNullOrBlank() &&
                        collectionHeroVideoPlaybackKey != null &&
                        endedCollectionHeroVideoPlaybackKey != collectionHeroVideoPlaybackKey
                }
            }
            val heroMediaDataState = remember(shouldPlayCollectionHeroVideoState, collectionHeroVideoUrl, heroTrailerUrlsState, collectionHeroVideoPlaybackKey) {
                derivedStateOf {
                    val shouldPlayCollectionHeroVideo = shouldPlayCollectionHeroVideoState.value
                    val (heroTrailerUrl, heroTrailerAudioUrl) = heroTrailerUrlsState.value
                    val url = if (shouldPlayCollectionHeroVideo) collectionHeroVideoUrl else heroTrailerUrl
                    val audioUrl = if (shouldPlayCollectionHeroVideo) null else heroTrailerAudioUrl
                    val playbackKey = if (shouldPlayCollectionHeroVideo) collectionHeroVideoPlaybackKey else heroTrailerUrl
                    Triple(url, audioUrl, playbackKey)
                }
            }
            val shouldPlayHeroTrailerState = remember(shouldPlayCatalogHeroTrailerState, shouldPlayCollectionHeroVideoState) {
                derivedStateOf { shouldPlayCatalogHeroTrailerState.value || shouldPlayCollectionHeroVideoState.value }
            }
            val heroMediaMutedState = remember(shouldPlayCollectionHeroVideoState, uiState.focusedPosterBackdropTrailerMuted) {
                derivedStateOf { shouldPlayCollectionHeroVideoState.value || uiState.focusedPosterBackdropTrailerMuted }
            }
            var heroTrailerFirstFrameRendered by remember { mutableStateOf(false) }
            LaunchedEffect(heroMediaDataState.value.third) {
                heroTrailerFirstFrameRendered = false
            }

            val isTrailerPlayingFullscreenState = remember(fullScreenBackdrop, shouldPlayHeroTrailerState, heroTrailerFirstFrameRendered) {
                derivedStateOf { fullScreenBackdrop && shouldPlayHeroTrailerState.value && heroTrailerFirstFrameRendered }
            }
            BackHandler(enabled = isTrailerPlayingFullscreenState.value) {
                focusedCatalogSelection.value = null
                expandedCatalogFocusKey.value = null
            }
            val liveHeroSceneState = remember(
                resolvedHeroState,
                shouldPlayHeroTrailerState,
                heroTrailerFirstFrameRendered,
                heroMediaDataState,
                heroMediaMutedState,
                fullScreenBackdrop
            ) {
                derivedStateOf {
                    val (heroBackdrop, resolvedHero, enrichmentActive) = resolvedHeroState.value
                    val (heroMediaUrl, heroMediaAudioUrl, heroMediaPlaybackKey) = heroMediaDataState.value
                    val preview = resolvedHero
                    ModernHeroSceneState(
                        heroBackdrop = heroBackdrop,
                        preview = preview,
                        enrichmentActive = enrichmentActive,
                        shouldPlayTrailer = shouldPlayHeroTrailerState.value,
                        trailerFirstFrameRendered = heroTrailerFirstFrameRendered,
                        trailerUrl = heroMediaUrl,
                        trailerAudioUrl = heroMediaAudioUrl,
                        trailerPlaybackKey = heroMediaPlaybackKey,
                        trailerMuted = heroMediaMutedState.value,
                        fullScreenBackdrop = fullScreenBackdrop
                    )
                }
            }
            val stableHeroSceneStateRef = remember { mutableStateOf<ModernHeroSceneState?>(null) }

            LaunchedEffect(Unit) {
                snapshotFlow {
                    val currentLive = liveHeroSceneState.value
                    val isScrolling = verticalRowListState.isScrollInProgress
                    val isRapidNav = isRapidHorizontalNav.value
                    val stable = stableHeroSceneStateRef.value
                    val stableHasPreview = stable?.preview?.title?.isNotBlank() == true
                    val liveHasPreview = currentLive.preview?.title?.isNotBlank() == true
                    when {
                        isScrolling && stableHasPreview -> stable
                        isScrolling && !stableHasPreview && liveHasPreview -> currentLive
                        isRapidNav -> currentLive.copy(preview = null, enrichmentActive = false)
                        else -> currentLive
                    }
                }.collect { currentStable ->
                    if (stableHeroSceneStateRef.value != currentStable) {
                        // Skip updates where preview is blank (transient empty state from row transitions).
                        val incomingPreview = currentStable.preview
                        if (incomingPreview != null && incomingPreview.title.isBlank()) {
                            return@collect
                        }
                        // If incoming has null preview (enrichment pending) and we're scrolling,
                        // don't overwrite a good stable preview.
                        val existingStable = stableHeroSceneStateRef.value
                        if (incomingPreview == null && existingStable?.preview?.title?.isNotBlank() == true) {
                            if (verticalRowListState.isScrollInProgress) {
                                return@collect
                            }
                        }
                        stableHeroSceneStateRef.value = currentStable
                    }
                }
            }

            val currentLiveHeroSceneStateUpdated by rememberUpdatedState(liveHeroSceneState.value)
            val isScrollInProgressUpdated by rememberUpdatedState(verticalRowListState.isScrollInProgress)
            val isRapidHorizontalNavUpdated by rememberUpdatedState(isRapidHorizontalNav.value)

            val heroSceneStateLambda = remember {
                {
                    val currentLive = currentLiveHeroSceneStateUpdated
                    val isScrolling = isScrollInProgressUpdated
                    val isRapidNav = isRapidHorizontalNavUpdated
                    val stable = stableHeroSceneStateRef.value
                    val stableHasPreview = stable?.preview?.title?.isNotBlank() == true

                    when {
                        // During vertical scroll: freeze stable to avoid flashing
                        // transient addon data before enrichment completes
                        isScrolling && stableHasPreview -> stable!!
                        // During rapid horizontal nav: freeze to avoid backdrop flashing
                        isRapidNav && stable != null -> stable
                        // Normal: show live state
                        else -> currentLive
                    }
                }
            }

            // Update stableRef from composition context (not inside lambda/read-only snapshot).
            // This runs on every recomposition and captures the latest live state with real content.
            // Only update when NOT scrolling - after scroll stops, the enrichment mechanism
            // will gate the preview through enrichmentActive in previewProvider.
            val latestLiveForStable = liveHeroSceneState.value
            if (!verticalRowListState.isScrollInProgress &&
                !isRapidHorizontalNav.value &&
                !latestLiveForStable.enrichmentActive
            ) {
                val hasNewPreview = latestLiveForStable.preview?.title?.isNotBlank() == true &&
                    stableHeroSceneStateRef.value?.preview != latestLiveForStable.preview
                val hasNewBackdrop = latestLiveForStable.heroBackdrop != null &&
                    stableHeroSceneStateRef.value?.heroBackdrop != latestLiveForStable.heroBackdrop
                if (hasNewPreview || hasNewBackdrop) {
                    stableHeroSceneStateRef.value = latestLiveForStable
                }
            }

            val isFullScreenState = remember {
                derivedStateOf { liveHeroSceneState.value.fullScreenBackdrop }
            }
            val isFullScreenLambda = remember {
                { isFullScreenState.value }
            }

            val localDensity = LocalDensity.current
            val rowsViewportHeightFraction = modernHeroRowsViewportFraction(
                useLandscapePosters = useLandscapePosters
            )
            val defaultRowsViewportHeight = remember(screenHeight, rowsViewportHeightFraction) {
                screenHeight * rowsViewportHeightFraction
            }
            val measuredHeroMetadataHeight = remember(heroMetadataHeightPx, localDensity) {
                with(localDensity) { heroMetadataHeightPx.toDp() }
            }
            val normalRowsViewportHeight = remember(
                screenHeight,
                defaultRowsViewportHeight,
                measuredHeroMetadataHeight
            ) {
                if (heroMetadataHeightPx == 0) {
                    defaultRowsViewportHeight
                } else {
                    (screenHeight - (
                        MODERN_HERO_CONTENT_TOP_PADDING +
                            measuredHeroMetadataHeight +
                            MODERN_HERO_CONTENT_TO_ROWS_GAP
                        )).coerceIn(240.dp, screenHeight)
                }
            }
            val rowsViewportHeight = if (useDetailedHomeMediaRows) screenHeight else normalRowsViewportHeight
            val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
            val rowTitleHeight = remember(rowTitleLineHeight, localDensity) {
                with(localDensity) {
                    runCatching { rowTitleLineHeight.toDp() }
                        .getOrDefault(24.dp)
                }
            }
            val heroBackdropHeight = remember(screenHeight, rowsViewportHeight, rowTitleHeight) { (screenHeight - rowsViewportHeight + rowTitleHeight + 14.dp).coerceAtMost(screenHeight) }
            // Vertical movement is owned explicitly by moveFocusToOrderedRow.
            // A focused card also emits BringIntoView on every horizontal focus
            // change; letting LazyColumn react to that request removed the
            // initial row offset and made the CW title jump upward after the
            // first Left/Right press. Keep horizontal BringIntoView inside each
            // LazyRow, but never let it scroll the vertical list implicitly.
            val verticalRowBringIntoViewSpec = remember(defaultBringIntoViewSpec) {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                object : BringIntoViewSpec {
                    override val scrollAnimationSpec: AnimationSpec<Float> =
                        defaultBringIntoViewSpec.scrollAnimationSpec

                    override fun calculateScrollDistance(
                        offset: Float,
                        size: Float,
                        containerSize: Float
                    ): Float = 0f
                }
            }
            val contentFocusRequester = LocalContentFocusRequester.current
            val heroMediaWidthPx = remember(screenWidth, localDensity, fullScreenBackdrop) {
                with(localDensity) {
                    if (fullScreenBackdrop) screenWidth.roundToPx()
                    else (screenWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx()
                }.coerceAtLeast(1)
            }
            val heroMediaHeightPx = remember(heroBackdropHeight, screenHeight, localDensity, fullScreenBackdrop) {
                with(localDensity) {
                    if (fullScreenBackdrop) screenHeight.roundToPx()
                    else heroBackdropHeight.roundToPx()
                }.coerceAtLeast(1)
            }

            val heroMediaModifier = remember(heroBackdropHeight, screenHeight, fullScreenBackdrop) {
                if (fullScreenBackdrop) {
                    Modifier.align(Alignment.TopStart).fillMaxWidth().height(screenHeight)
                } else {
                    Modifier.align(Alignment.TopEnd).offset(x = 56.dp).fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION).height(heroBackdropHeight)
                }
            }

            val heroTrailerFirstFrameRenderedUpdated by rememberUpdatedState(heroTrailerFirstFrameRendered)

            val onTrailerEndedLambda = remember {
                {
                    if (shouldPlayCollectionHeroVideoState.value && collectionHeroVideoPlaybackKey != null) {
                        endedCollectionHeroVideoPlaybackKey = collectionHeroVideoPlaybackKey
                    } else {
                        expandedCatalogFocusKey.value = null
                    }
                }
            }
            val onFirstFrameRenderedLambda = remember { { heroTrailerFirstFrameRendered = true } }

            if (!isCoverflowHomeRow) {
                ModernHeroSection(
                    heroSceneState = heroSceneStateLambda,
                    isFullScreen = isFullScreenLambda,
                    heroMediaWidthPx = heroMediaWidthPx,
                    heroMediaHeightPx = heroMediaHeightPx,
                    modifier = heroMediaModifier,
                    onTrailerEnded = onTrailerEndedLambda,
                    onFirstFrameRendered = onFirstFrameRenderedLambda
                )
            }

            val trailerContentAlphaState = animateFloatAsState(
                targetValue = if (shouldPlayHeroTrailerState.value && heroTrailerFirstFrameRenderedUpdated) 0f else 1f,
                animationSpec = tween(durationMillis = 480),
                label = "trailerContentFade"
            )

            val shouldPlayTrailerLambda = remember { { shouldPlayHeroTrailerState.value } }
            val heroTrailerRenderedLambda = remember { { heroTrailerFirstFrameRenderedUpdated } }

            val heroMetadataModifier = remember(rowHorizontalPadding) {
                Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = rowHorizontalPadding,
                        top = MODERN_HERO_CONTENT_TOP_PADDING,
                        end = 48.dp
                    )
                    .offset(y = (-5).dp)
                    .onSizeChanged { heroMetadataHeightPx = it.height }
            }

            if (!isCoverflowHomeRow && !useDetailedHomeMediaRows) {
                HeroTitleBlock(
                    previewProvider = {
                        val state = heroSceneStateLambda()
                        val focusedRow = activeRowKey.value?.let { key -> rowByKey[key] }
                        val detailsEnabled = focusedRow?.let {
                            rowMatchesConfiguredKey(it, homeRowDetailedKeys)
                        } == true || activeRowKey.value == MODERN_CONTINUE_WATCHING_ROW_KEY
                        // Freebox Videos uses its own backdrop, but does not have a
                        // hero metadata panel.  During the vertical transition the
                        // stable hero state still belongs to Continue Watching, which
                        // previously left its title (for example "Disclosure Day")
                        // on top of the selected local video.
                        if (activeRowKey.value == "freebox_videos" ||
                            !detailsEnabled ||
                            isRapidHorizontalNav.value ||
                            state.enrichmentActive
                        ) null
                        else state.preview
                    },
                    enrichmentActive = {
                        if (isRapidHorizontalNav.value) false
                        else heroSceneStateLambda().enrichmentActive
                    },
                    portraitMode = !useLandscapePosters,
                    trailerPlaying = {
                        if (isRapidHorizontalNav.value) false
                        else {
                            val state = heroSceneStateLambda()
                            shouldPlayTrailerLambda() && heroTrailerRenderedLambda()
                        }
                    },
                    modifier = heroMetadataModifier
                )
            }

            val onActiveRowKeyChangeLambda = remember { { key: String? -> focusHolder.activeRowKey = key; activeRowKey.value = key } }
            val onActiveItemIndexChangeLambda = remember { { index: Int -> focusHolder.activeItemIndex = index; activeItemIndex.intValue = index } }
            val onLastHeroNavigationAtMsChangeLambda = remember { { ms: Long -> lastHeroNavigationAtMs.longValue = ms } }
            val onHeroFocusSettleDelayChangeLambda = remember { { delay: Long -> heroFocusSettleDelayMs.longValue = delay } }
            val onLastFocusedContinueWatchingIndexChangeLambda = remember { { index: Int -> lastFocusedContinueWatchingIndex.intValue = index } }
            val onLastFocusedContinueWatchingKeyChangeLambda = remember { { key: String -> lastFocusedContinueWatchingKey.value = key } }
            val onFocusedCatalogSelectionChangeLambda = remember { { selection: FocusedCatalogSelection? -> focusedCatalogSelection.value = selection } }
            val onFocusedHeroMediaNonceChangeLambda = remember { { nonce: Int -> focusedHeroMediaNonce.intValue = nonce } }
            val onExpansionInteractionNonceChangeLambda = remember { { nonce: Int -> expansionInteractionNonce.intValue = nonce } }
            val onFastScrollingChangedLambda = remember { { scrolling: Boolean -> isFastScrolling.value = scrolling } }
            val onExpandedCatalogFocusKeyChangeLambda = remember { { key: String? -> expandedCatalogFocusKey.value = key } }
            val onBackdropInteractionLambda = remember { { expansionInteractionNonce.intValue++; Unit } }
            val onContinueWatchingOptionsLambda = remember { { item: ContinueWatchingItem -> optionsItem.value = item } }
            val latestCatalogLongPress = rememberUpdatedState(onCatalogItemLongPress)
            val onCatalogItemLongPressWithFocus = remember {
                { item: MetaPreview, addonBaseUrl: String ->
                    // Catalog actions can remove the focused card. Record the
                    // owning row and current horizontal position before the
                    // dialog changes the presentation, so recomposition never
                    // falls back to the row above.
                    val rowKey = activeRowKey.value
                    if (!rowKey.isNullOrBlank()) {
                        pendingRowFocusKey.value = rowKey
                        pendingRowFocusIndex.value = activeItemIndex.intValue.coerceAtLeast(0)
                        pendingRowFocusNonce.intValue++
                    }
                    latestCatalogLongPress.value(item, addonBaseUrl)
                }
            }
            val onPendingRowFocusClearedLambda = remember {
                {
                    pendingRowFocusKey.value = null
                    pendingRowFocusIndex.value = null
                }
            }
            val onRowItemFocusedInternalLambda = remember(onRowItemFocusedPassedDown) {
                { rowKey: String, index: Int, isCw: Boolean ->
                    onRowItemFocusedPassedDown.value.invoke(rowKey, index, isCw)
                }
            }
            val stableTrailerContentAlphaLambda = remember { { trailerContentAlphaState.value } }
            val stableExpandedTrailerPreviewUrl = remember(heroTrailerUrlsState) { { heroTrailerUrlsState.value.first } }
            val stableExpandedTrailerPreviewAudioUrl = remember(heroTrailerUrlsState) { { heroTrailerUrlsState.value.second } }
            val stableEnrichedPreviews = remember { androidx.compose.runtime.mutableStateOf(enrichedPreviews.asStable()) }
                .apply { value = enrichedPreviews.asStable() }
            val stableTrailerPreviewUrls = remember(trailerPreviewUrls) { trailerPreviewUrls.asStable() }
            val stableTrailerPreviewAudioUrls = remember(trailerPreviewAudioUrls) { trailerPreviewAudioUrls.asStable() }

            val stableOnRequestLazyCatalogLoad = remember(onRequestLazyCatalogLoad) {
                { catalogKey: String -> onRequestLazyCatalogLoad(catalogKey) }
            }
            val stableOnItemFocus = remember(onItemFocus) {
                { item: MetaPreview ->
                    focusedFreeboxVideo.value = item.takeIf {
                        it.id.startsWith("freebox:", ignoreCase = true) ||
                            it.rawType.equals("freebox", ignoreCase = true)
                    }
                    onItemFocus(item)
                }
            }
            val stableOnPreloadAdjacentItem = remember(onPreloadAdjacentItem) { { item: MetaPreview -> onPreloadAdjacentItem(item) } }

            ModernHomeRowsList(
                carouselRows = carouselRows,
                verticalRowListState = verticalRowListState,
                focusedItemByRow = stableFocusedItemByRow,
                rowListStates = stableRowListStates,
                loadMoreRequestedTotals = stableLoadMoreRequestedTotals,
                focusState = focusState,
                activeRowKey = activeRowKey,
                activeItemIndex = activeItemIndex,
                isFastScrolling = isFastScrolling,
                onFastScrollingChanged = onFastScrollingChangedLambda,
                contentFocusRequester = contentFocusRequester,
                rowsViewportHeight = rowsViewportHeight,
                fullScreenCoverflowPanelHeight = if (isCoverflowHomeRow) screenHeight else null,
                catalogBottomPadding = 0.dp,
                trailerContentAlpha = stableTrailerContentAlphaLambda,
                verticalRowBringIntoViewSpec = verticalRowBringIntoViewSpec,
                onRowItemFocusedInternal = onRowItemFocusedInternalLambda,
                onNavigateToDetail = onNavigateToDetail,
                onNavigateToFolderDetail = onNavigateToFolderDetail,
                onLoadMoreCatalog = onLoadMoreCatalog,
                onContinueWatchingClick = onContinueWatchingClick,
                onContinueWatchingDetails = onContinueWatchingDetails,
                onContinueWatchingOptions = onContinueWatchingOptionsLambda,
                onRequestLazyCatalogLoad = stableOnRequestLazyCatalogLoad,
                onBackdropInteraction = onBackdropInteractionLambda,
                onItemFocus = stableOnItemFocus,
                onPreloadAdjacentItem = stableOnPreloadAdjacentItem,
                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChangeLambda,
                isCatalogItemWatched = isCatalogItemWatched,
                onCatalogItemLongPress = onCatalogItemLongPressWithFocus,
                enrichedPreviews = stableEnrichedPreviews,
                trailerPreviewUrls = stableTrailerPreviewUrls,
                trailerPreviewAudioUrls = stableTrailerPreviewAudioUrls,
                useLandscapePosters = useLandscapePosters,
                useDetailedHomeMediaRows = useDetailedHomeMediaRows,
                homeRowCarouselKeys = homeRowCarouselKeys,
                homeRowDetailedKeys = homeRowDetailedKeys,
                showLabels = uiState.posterLabelsEnabled,
                posterCardCornerRadius = posterCardCornerRadius,
                focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                effectiveExpandEnabled = effectiveExpandEnabled,
                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                trailerPlaybackTarget = trailerPlaybackTarget,
                expandedCatalogFocusKey = expandedCatalogFocusKey,
                expandedTrailerPreviewUrl = stableExpandedTrailerPreviewUrl,
                expandedTrailerPreviewAudioUrl = stableExpandedTrailerPreviewAudioUrl,
                portraitCatalogCardWidth = portraitCatalogCardWidth,
                portraitCatalogCardHeight = portraitCatalogCardHeight,
                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                continueWatchingCardWidth = continueWatchingCardWidth,
                continueWatchingCardHeight = continueWatchingCardHeight,
                carouselCardScale = carouselScale,
                videoCardWidth = videoCardWidth,
                videoCardHeight = videoCardHeight,
                movieFavoritesPortraitMode = movieFavoritesPortraitMode,
                seriesFavoritesPortraitMode = seriesFavoritesPortraitMode,
                blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                pendingRowFocusKey = pendingRowFocusKey,
                pendingRowFocusIndex = pendingRowFocusIndex,
                pendingRowFocusNonce = pendingRowFocusNonce,
                onPendingRowFocusCleared = onPendingRowFocusClearedLambda,
                onRequestRowFocus = { rowKey, itemIndex ->
                    pendingRowFocusKey.value = rowKey
                    pendingRowFocusIndex.value = itemIndex
                    pendingRowFocusNonce.intValue++
                },
                onActiveRowKeyChange = onActiveRowKeyChangeLambda,
                onActiveItemIndexChange = onActiveItemIndexChangeLambda,
                lastHeroNavigationAtMs = lastHeroNavigationAtMs,
                onLastHeroNavigationAtMsChange = onLastHeroNavigationAtMsChangeLambda,
                onHeroFocusSettleDelayChange = onHeroFocusSettleDelayChangeLambda,
                lastFocusedContinueWatchingIndex = lastFocusedContinueWatchingIndex,
                onLastFocusedContinueWatchingIndexChange = onLastFocusedContinueWatchingIndexChangeLambda,
                lastFocusedContinueWatchingKey = lastFocusedContinueWatchingKey,
                onLastFocusedContinueWatchingKeyChange = onLastFocusedContinueWatchingKeyChangeLambda,
                focusedCatalogSelection = focusedCatalogSelection,
                onFocusedCatalogSelectionChange = onFocusedCatalogSelectionChangeLambda,
                focusedHeroMediaNonce = focusedHeroMediaNonce,
                onFocusedHeroMediaNonceChange = onFocusedHeroMediaNonceChangeLambda,
                onExpansionInteractionNonceChange = onExpansionInteractionNonceChangeLambda,
                isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                freeboxVideoEntries = uiState.freeboxVideoEntries,
                continueWatchingContentIds = uiState.continueWatchingItems
                    .filterIsInstance<ContinueWatchingItem.InProgress>()
                    .flatMap { listOf(it.progress.contentId, it.progress.videoId) }
                    .toSet(),
                onNavigateToFreebox = onNavigateToFreebox,
                freeboxVideoArtwork = uiState.freeboxVideoArtwork,
                freeboxVideoBackdrops = uiState.freeboxVideoBackdrops,
                freeboxVideoMetadata = uiState.freeboxVideoMetadata,
                freeboxVideoProbedDurations = uiState.freeboxVideoProbedDurations,
                onRenameFreeboxVideo = onRenameFreeboxVideo,
                onDeleteFreeboxVideo = onDeleteFreeboxVideo,
                modifier = Modifier.align(Alignment.BottomStart).offset(y = (-10).dp)
            )
    }

    val selectedOptionsItem = optionsItem.value
    if (selectedOptionsItem != null) {
        val selectedIsIptv = selectedOptionsItem.isIptvItem()
        val selectedIsFreebox = selectedOptionsItem.freeboxIdOrNull() != null
        val removesFavorite = selectedIsIptv

        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem.value = null },
            onRemoveFromList = if (removesFavorite) {
                {
                    val targetIndex = if (uiState.continueWatchingItems.size <= 1) null
                    else (lastFocusedContinueWatchingIndex.intValue).coerceAtMost(uiState.continueWatchingItems.size - 2).coerceAtLeast(0)
                    pendingRowFocusKey.value = if (targetIndex != null) "continue_watching" else null
                    pendingRowFocusIndex.value = targetIndex
                    pendingRowFocusNonce.intValue++
                    onRemoveContinueWatching(
                        selectedOptionsItem.contentId(),
                        selectedOptionsItem.season(),
                        selectedOptionsItem.episode(),
                        selectedOptionsItem is ContinueWatchingItem.NextUp
                    )
                    optionsItem.value = null
                }
            } else null,
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) null
                else (lastFocusedContinueWatchingIndex.intValue).coerceAtMost(uiState.continueWatchingItems.size - 2).coerceAtLeast(0)
                pendingRowFocusKey.value = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex.value = targetIndex
                pendingRowFocusNonce.intValue++
                if (removesFavorite) {
                    onRemoveIptvFavorite(selectedOptionsItem)
                } else {
                    onRemoveContinueWatching(
                        selectedOptionsItem.contentId(),
                        selectedOptionsItem.season(),
                        selectedOptionsItem.episode(),
                        selectedOptionsItem is ContinueWatchingItem.NextUp
                    )
                }
                optionsItem.value = null
            },
            removeIptvFavorite = removesFavorite,
            showDetails = true,
            onDetails = {
                onContinueWatchingDetails(selectedOptionsItem)
                optionsItem.value = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem.value = null
            },
            showPlayManually = showContinueWatchingManualPlayOption && !selectedIsIptv && !selectedIsFreebox,
            onRenameFreebox = if (selectedOptionsItem is ContinueWatchingItem.InProgress && selectedOptionsItem.progress.contentId.startsWith("freebox:")) {
                {
                    renameOptionsItem.value = selectedOptionsItem
                    optionsItem.value = null
                }
            } else null,
            onMarkAsUnwatched = if (selectedOptionsItem is ContinueWatchingItem.InProgress && selectedOptionsItem.progress.contentId.startsWith("freebox:")) {
                {
                    onMarkFreeboxUnwatched(selectedOptionsItem)
                    optionsItem.value = null
                }
            } else null,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem.value = null
            },
            onSearchPoster = if (selectedIsFreebox || selectedIsIptv) {
                onSearchContinueWatchingPoster?.let {
                    {
                        it(selectedOptionsItem)
                        optionsItem.value = null
                    }
                }
            } else {
                null
            },
            onDeleteFromFreebox = if (selectedOptionsItem is ContinueWatchingItem.InProgress && selectedOptionsItem.freeboxIdOrNull() != null) {
                {
                    onDeleteFreeboxProgress?.invoke(selectedOptionsItem)
                    optionsItem.value = null
                }
            } else null
        )
    }

    val selectedRenameItem = renameOptionsItem.value
    if (selectedRenameItem is ContinueWatchingItem.InProgress) {
        com.nuvio.tv.ui.components.FreeboxRenameDialog(
            initialName = freeboxFileNameOnly(selectedRenameItem.progress.name.ifBlank { selectedRenameItem.progress.videoId }),
            onDismiss = { renameOptionsItem.value = null },
            onConfirm = { newName ->
                onRenameFreeboxProgress(selectedRenameItem, newName)
                renameOptionsItem.value = null
            }
        )
    }
}

@Composable
private fun ModernHeroSynopsisBlock(
    previewProvider: () -> HeroPreview?,
    modifier: Modifier = Modifier
) {
    val description = previewProvider()
        ?.description
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return

    Box(
        modifier = modifier
            // Reserve a stable layout slot for navigation, while letting the
            // visible synopsis panel stop at the end of short descriptions.
            .height(MODERN_HERO_SYNOPSIS_HEIGHT)
    ) {
        val synopsisStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = (MaterialTheme.typography.bodyMedium.fontSize.value - MODERN_HERO_SYNOPSIS_FONT_REDUCTION.value).sp,
            lineHeight = (MaterialTheme.typography.bodyMedium.lineHeight.value - MODERN_HERO_SYNOPSIS_FONT_REDUCTION.value).sp
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.34f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = description.toShortSynopsis(),
                style = synopsisStyle,
                color = NuvioColors.TextPrimary,
                maxLines = modernHeroSynopsisMaxLines(),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ModernHeroSection(
    heroSceneState: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    heroMediaWidthPx: Int,
    heroMediaHeightPx: Int,
    modifier: Modifier,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val bgColor = NuvioColors.Background
    ModernHeroScene(
        state = heroSceneState,
        isFullScreen = isFullScreen,
        bgColor = bgColor,
        modifier = modifier.then(if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier),
        requestWidthPx = heroMediaWidthPx,
        requestHeightPx = heroMediaHeightPx,
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
    )
}
