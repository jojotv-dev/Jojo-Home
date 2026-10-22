package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.ui.ExperimentalComposeUiApi
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.ui.screens.freebox.FreeboxDetailLaunchState
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.ui.components.FreeboxVideosSection
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.recompositionHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun rowMatchesConfiguredKey(row: HeroCarouselRow, configuredKeys: Set<String>): Boolean {
    if (row.key in configuredKeys) return true
    val normalizedTitle = row.title.trim().lowercase()
    return configuredKeys.any { key ->
        when (key) {
            "movies" -> normalizedTitle == "films" || normalizedTitle == "movies"
            "series" -> normalizedTitle == "séries" || normalizedTitle == "series"
            else -> false
        }
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
internal fun ModernHomeRowsList(
    isVerticalRowsScrollingState: State<Boolean>,
    carouselRows: StableList<HeroCarouselRow>,
    verticalRowListState: LazyListState,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    focusState: HomeScreenFocusState,
    activeRowKey: State<String?>,
    activeItemIndex: State<Int>,
    isFastScrolling: State<Boolean>,
    onFastScrollingChanged: (Boolean) -> Unit,
    contentFocusRequester: FocusRequester,
    rowsViewportHeight: Dp,
    fullScreenCoverflowPanelHeight: Dp? = null,
    catalogBottomPadding: Dp,
    trailerContentAlpha: () -> Float,
    verticalRowBringIntoViewSpec: BringIntoViewSpec,
    onRowItemFocusedInternal: (String, Int, Boolean) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    onRequestLazyCatalogLoad: (String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
    trailerPreviewUrls: StableMap<String, String>,
    trailerPreviewAudioUrls: StableMap<String, String>,
    useLandscapePosters: Boolean,
    useDetailedHomeMediaRows: Boolean,
    homeRowCarouselKeys: Set<String> = emptySet(),
    homeRowDetailedKeys: Set<String> = emptySet(),
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: State<String?>,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    carouselCardScale: Float,
    videoCardWidth: Dp,
    videoCardHeight: Dp,
    movieFavoritesPortraitMode: Boolean,
    seriesFavoritesPortraitMode: Boolean,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    pendingRowFocusKey: State<String?>,
    pendingRowFocusIndex: State<Int?>,
    pendingRowFocusNonce: State<Int>,
    onPendingRowFocusCleared: () -> Unit,
    onRequestRowFocus: (String, Int) -> Unit,
    onActiveRowKeyChange: (String?) -> Unit,
    onActiveItemIndexChange: (Int) -> Unit,
    lastHeroNavigationAtMs: State<Long>,
    onLastHeroNavigationAtMsChange: (Long) -> Unit,
    onHeroFocusSettleDelayChange: (Long) -> Unit,
    lastFocusedContinueWatchingIndex: State<Int>,
    onLastFocusedContinueWatchingIndexChange: (Int) -> Unit,
    lastFocusedContinueWatchingKey: State<String?>,
    onLastFocusedContinueWatchingKeyChange: (String) -> Unit,
    focusedCatalogSelection: State<FocusedCatalogSelection?>,
    onFocusedCatalogSelectionChange: (FocusedCatalogSelection?) -> Unit,
    focusedHeroMediaNonce: State<Int>,
    onFocusedHeroMediaNonceChange: (Int) -> Unit,
    onExpansionInteractionNonceChange: (Int) -> Unit,
    freeboxVideoEntries: List<com.nuvio.tv.data.freebox.FreeboxFileEntry> = emptyList(),
    continueWatchingContentIds: Set<String> = emptySet(),
    onNavigateToFreebox: (String, String?, String?, com.nuvio.tv.core.tmdb.FreeboxVideoMeta?) -> Unit = { _, _, _, _ -> },
    freeboxVideoArtwork: Map<String, String> = emptyMap(),
    freeboxVideoBackdrops: Map<String, String> = emptyMap(),
    freeboxVideoMetadata: Map<String, com.nuvio.tv.core.tmdb.FreeboxVideoMeta> = emptyMap(),
    freeboxVideoProbedDurations: Map<String, Long> = emptyMap(),
    onDeleteFreeboxVideo: (com.nuvio.tv.data.freebox.FreeboxFileEntry) -> Unit = {},
    onRenameFreeboxVideo: (com.nuvio.tv.data.freebox.FreeboxFileEntry, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Unwrap StableRef wrappers for internal use (not passed to child composables)
    val focusedItemByRowMap = focusedItemByRow.value
    val rowListStatesMap = rowListStates.value
    val loadMoreRequestedTotalsMap = loadMoreRequestedTotals.value

    val latestOnActiveRowKeyChange = rememberUpdatedState(onActiveRowKeyChange)
    val latestOnActiveItemIndexChange = rememberUpdatedState(onActiveItemIndexChange)
    val latestOnRequestRowFocus = rememberUpdatedState(onRequestRowFocus)

    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val stableItemFocusRequestersByRow = remember { mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>() }
    // Keep the last row that actually owned a focused card separate from the
    // active navigation target. The latter can briefly change while the
    // sidebar is collapsing, which made a return from Films land on Videos.
    val lastFocusedRowKey = remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val context = LocalContext.current
    val rowNavigationScope = rememberCoroutineScope()
    val verticalPrefetchImageLoader = context.imageLoader
    val movieFavoritesRowSuffix = "_${HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID}"
    val seriesFavoritesRowSuffix = "_${HomeViewModel.IPTV_SERIES_FAVORITES_CATALOG_ID}"
    val orderedRows = remember(carouselRows, freeboxVideoEntries, useDetailedHomeMediaRows) {
        val rows = carouselRows.list
        val continueWatchingRows = rows.filter { it.key == MODERN_CONTINUE_WATCHING_ROW_KEY }
        val movieFavoriteRows = rows.filter { it.key.endsWith(movieFavoritesRowSuffix) }
        val seriesFavoriteRows = rows.filter { it.key.endsWith(seriesFavoritesRowSuffix) }
        val prioritizedKeys = (continueWatchingRows + movieFavoriteRows + seriesFavoriteRows)
            .mapTo(mutableSetOf()) { it.key }

        buildList<Pair<String, HeroCarouselRow?>> {
            continueWatchingRows.forEach { add(it.key to it) }
            if (freeboxVideoEntries.isNotEmpty()) add("freebox_videos" to null)
            movieFavoriteRows.forEach { add(it.key to it) }
            seriesFavoriteRows.forEach { add(it.key to it) }
            rows.filterNot { it.key in prioritizedKeys }.forEach { add(it.key to it) }
        }
    }
    // Freebox Videos is rendered by its own component, but must use the same
    // requester registry as the other Home rows. Creating these requesters
    // before the LazyColumn composes means Continue Watching can target the
    // first Videos card directly on the very first DOWN press.
    val freeboxFocusRequestersByPath = remember { mutableMapOf<String, FocusRequester>() }
    val freeboxEntryKeys = remember(freeboxVideoEntries) {
        freeboxVideoEntries.map { it.path }
    }
    val freeboxFocusRequesters = remember(freeboxEntryKeys) {
        freeboxEntryKeys.map { path ->
            freeboxFocusRequestersByPath.getOrPut(path) { FocusRequester() }
        }
    }

    // Restore the vertical row before asking its card for focus. Vertical
    // BringIntoView is intentionally disabled for normal horizontal movement,
    // so restoration must position the LazyColumn explicitly.
    LaunchedEffect(
        pendingRowFocusNonce.value,
        orderedRows,
        freeboxFocusRequesters.size,
    ) {
        val targetKey = pendingRowFocusKey.value ?: return@LaunchedEffect
        val targetPosition = orderedRows.indexOfFirst { it.first == targetKey }
        if (targetPosition < 0) return@LaunchedEffect
        val targetOffset = if (targetPosition == 0) {
            -with(density) { MODERN_ROW_HEADER_FOCUS_INSET.roundToPx() }
        } else {
            0
        }
        // A D-pad hand-off can set a pending focus target even when the next
        // row is already fully readable. Repositioning it unconditionally
        // aligns the last row to the top and exposes the artificial bottom
        // padding as a large empty area. Keep the current Films + Series
        // framing whenever the destination is already visible.
        val layoutInfo = verticalRowListState.layoutInfo
        val targetAlreadyFramed = layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == targetPosition }
            ?.let { item ->
                val visibleStart = maxOf(item.offset, layoutInfo.viewportStartOffset)
                val visibleEnd = minOf(item.offset + item.size, layoutInfo.viewportEndOffset)
                (visibleEnd - visibleStart).coerceAtLeast(0) >= item.size * 0.8f
            } == true
        if (!targetAlreadyFramed) {
            verticalRowListState.scrollToItem(targetPosition, targetOffset)
        }

        if (targetKey == "freebox_videos") {
            val targetIndex = (pendingRowFocusIndex.value ?: 0)
                .coerceIn(0, (freeboxFocusRequesters.size - 1).coerceAtLeast(0))
            var restored = false
            repeat(4) {
                withFrameNanos { }
                if (!restored) {
                    restored = freeboxFocusRequesters.getOrNull(targetIndex)
                        ?.let { requester -> runCatching { requester.requestFocus() }.getOrDefault(false) }
                        ?: false
                }
            }
            if (restored) onPendingRowFocusCleared()
        }
    }
    fun showFreeboxDetails(video: com.nuvio.tv.data.freebox.FreeboxFileEntry) {
        if (!video.isDirectory) {
            val contentId = freeboxContentIdForEntry(video)
            val metadata = freeboxVideoMetadata[contentId]
            val target = FreeboxDetailLaunchState.prepare(
                entry = video,
                metadata = metadata,
                artworkUrl = freeboxVideoArtwork[contentId],
                backdropUrl = freeboxVideoBackdrops[contentId]
            )
            // Keep the TMDB fiche for its synopsis/cast, but carry the exact
            // local file as the source.  Without this marker a metadata item
            // detected as "series" falls through to the addon resolver.
            onNavigateToDetail(
                target.itemId,
                target.itemType,
                "freebox:$contentId"
            )
        }
    }
    fun moveFocusToOrderedRow(targetPosition: Int): Boolean {
        // No row exists at the boundary: do not consume the D-pad event. This
        // lets the surrounding Home chrome/sidebar handle the boundary instead
        // of trapping focus on the last or first content row.
        val target = orderedRows.getOrNull(targetPosition) ?: return false
        val targetKey = target.first
        val targetRow = target.second
        val targetLastIndex = targetRow?.items?.list?.lastIndex
            ?: freeboxFocusRequesters.lastIndex
        val requestedIndex = when {
            targetRow == null -> 0
            targetKey == MODERN_CONTINUE_WATCHING_ROW_KEY -> {
                val savedKey = lastFocusedContinueWatchingKey.value
                val savedKeyIndex = savedKey?.let { key ->
                    targetRow.items.list.indexOfFirst { it.key == key }.takeIf { it >= 0 }
                }
                savedKeyIndex ?: lastFocusedContinueWatchingIndex.value.coerceAtLeast(0)
            }
            else -> focusedItemByRowMap[targetKey] ?: 0
        }.coerceIn(0, targetLastIndex.coerceAtLeast(0))

        focusedItemByRowMap[targetKey] = requestedIndex
        latestOnActiveRowKeyChange.value(targetKey)
        latestOnActiveItemIndexChange.value(requestedIndex)
        if (targetRow != null) {
            latestOnRequestRowFocus.value(targetKey, requestedIndex)
        } else {
            // Freebox Videos has its own card registry and no pending-target
            // observer. Clear an older pending catalog target before moving.
            onPendingRowFocusCleared()
        }

        // If the destination row is already readable in the current frame,
        // moving focus must not also recenter the LazyColumn. This preserves
        // the Films + Séries composition shown before Séries receives focus.
        val rowLayoutInfo = verticalRowListState.layoutInfo
        val targetAlreadyFramed = rowLayoutInfo.visibleItemsInfo
            .firstOrNull { it.index == targetPosition }
            ?.let { item ->
                val visibleStart = maxOf(item.offset, rowLayoutInfo.viewportStartOffset)
                val visibleEnd = minOf(
                    item.offset + item.size,
                    rowLayoutInfo.viewportEndOffset
                )
                (visibleEnd - visibleStart).coerceAtLeast(0) >= item.size * 0.8f
            } == true

        rowNavigationScope.launch {
            val targetScrollOffset = if (targetPosition == 0) {
                -with(density) { MODERN_ROW_HEADER_FOCUS_INSET.roundToPx() }
            } else {
                0
            }
            if (!targetAlreadyFramed) {
                verticalRowListState.scrollToItem(targetPosition, targetScrollOffset)
            }
            var focusTransferred = false
            repeat(3) {
                withFrameNanos { }
                if (!focusTransferred) {
                    val requester = if (targetRow == null) {
                        freeboxFocusRequesters.getOrNull(requestedIndex)
                    } else {
                        stableItemFocusRequestersByRow[targetKey]?.value?.get(requestedIndex)
                    }
                    focusTransferred = requester != null &&
                        runCatching { requester.requestFocus() }.getOrDefault(false)
                }
            }
            // The newly focused card emits its own bring-into-view request in
            // the same frame. On TV that request can win the race while keeping
            // the previous row's viewport, even though focus really moved.
            // Re-assert the target row after focus ownership has settled.
            if (!targetAlreadyFramed) {
                withFrameNanos { }
                verticalRowListState.scrollToItem(targetPosition, targetScrollOffset)
            }
        }
        // The target requester is resolved only after scrolling and one or
        // more composition frames.  It is therefore not valid to report this
        // D-pad event as handled yet: doing so traps focus on the Videos card
        // whenever artwork/duration updates are replacing the target row.
        // The coroutine still performs the ordered hand-off as soon as the
        // requester attaches, while `false` preserves Compose's immediate
        // spatial navigation fallback for this key press.
        return false
    }
    val latestOrderedRowsForLazy = rememberUpdatedState(orderedRows)

    // The focused card is the only authority for vertical movement.  Forcing
    // `scrollToItem` here raced Compose's bring-into-view request every time
    // the D-pad crossed a row, causing jumps and leaving the preceding title
    // partially off-screen.  The Home screen still positions the initial
    // "Continuer à regarder" row once; subsequent navigation is natural.

    LaunchedEffect(verticalPrefetchImageLoader, density) {
        val prefetchAheadRows = 1
        val prefetchItemsPerRow = 1
        snapshotFlow {
            verticalRowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .debounce(120L) // VERTICAL_PREFETCH_DEBOUNCE_MS
            .collect { lastVisibleRowIndex ->
                withContext(Dispatchers.IO) {
                    for (rowOffset in 1..prefetchAheadRows) {
                        val row = latestOrderedRowsForLazy.value.getOrNull(lastVisibleRowIndex + rowOffset)?.second ?: continue
                        for (i in 0 until minOf(prefetchItemsPerRow, row.items.list.size)) {
                            val item = row.items.list[i]
                            val url = item.imageUrl ?: continue
                            val isIptvFavoriteRow = row.key.endsWith(movieFavoritesRowSuffix) ||
                                row.key.endsWith(seriesFavoritesRowSuffix)
                            val metrics = if (isIptvFavoriteRow) {
                                null
                            } else {
                                item.catalogCardRequestMetrics(
                                    useLandscapePosters = useLandscapePosters,
                                    portraitCardWidth = portraitCatalogCardWidth,
                                    portraitCardHeight = portraitCatalogCardHeight,
                                    landscapeCardWidth = landscapeCatalogCardWidth,
                                    landscapeCardHeight = landscapeCatalogCardHeight,
                                    expandEnabled = effectiveExpandEnabled
                                )
                            }
                            val wPx = with(density) { (metrics?.width ?: videoCardWidth).roundToPx() }
                            val hPx = with(density) { (metrics?.height ?: videoCardHeight).roundToPx() }
                            val cacheKey = "jojotv-prefetch/v2/${item.key}|${row.key}|${url}_${wPx}x${hPx}"
                            if (verticalPrefetchImageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null) continue
                            verticalPrefetchImageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .memoryCacheKey(cacheKey)
                                    .size(width = wPx, height = hPx)
                                    .build()
                            )
                        }
                    }
                }
            }
    }

    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    LaunchedEffect(verticalRowListState) {
        val prefetchAheadForLazy = 1
        snapshotFlow {
            val scrolling = verticalRowListState.isScrollInProgress
            val info = verticalRowListState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(scrolling, firstVisible, lastVisible)
        }.collect { (scrolling, firstVisible, lastVisible) ->
            if (scrolling || lastVisible < 0) return@collect
            delay(150)
            if (verticalRowListState.isScrollInProgress) return@collect
            val rows = latestOrderedRowsForLazy.value
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + prefetchAheadForLazy)) {
                val row = rows.getOrNull(idx)?.second ?: continue
                if (row.isLoading && row.items.list.firstOrNull()?.imageUrl == "placeholder://empty") {
                    latestOnRequestLazyCatalogLoad.value(row.key)
                }
            }
        }
    }

    // Secondary trigger: when scroll settles after focus-driven BringIntoView,
    // check again for placeholder rows that need loading. The primary snapshotFlow
    // above may miss this if visible indices didn't change.
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) return@collect
                delay(200)
                if (verticalRowListState.isScrollInProgress) return@collect
                val rows = latestOrderedRowsForLazy.value
                val info = verticalRowListState.layoutInfo
                val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: return@collect
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + 1)) {
                    val row = rows.getOrNull(idx)?.second ?: continue
                    if (row.isLoading && row.items.list.firstOrNull()?.imageUrl == "placeholder://empty") {
                        latestOnRequestLazyCatalogLoad.value(row.key)
                    }
                }
            }
    }

    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec,
        LocalFastScrollActive provides isFastScrolling,
        LocalVerticalRowsScrolling provides isVerticalRowsScrollingState
    ) {
        LazyColumn(
            state = verticalRowListState,
            modifier = modifier
                .fillMaxWidth()
                .recompositionHighlighter()
                .height(rowsViewportHeight)
                .padding(bottom = catalogBottomPadding)
                .clipToBounds()
                .graphicsLayer { alpha = trailerContentAlpha() }
                .focusRequester(contentFocusRequester)
                // Resolve the target when focus actually comes back from the
                // sidebar. Passing a requester captured during composition
                // kept the initial Videos row as the fallback even after Films
                // or Series had become active.
                .focusRestorer {
                    val targetRowKey = lastFocusedRowKey.value ?: activeRowKey.value
                    val targetItemIndex = targetRowKey?.let {
                        focusedItemByRowMap[it] ?: 0
                    } ?: 0
                    // Re-enter the exact card that owned focus before the
                    // sidebar opened. Returning only the row requester lets
                    // the nested restorer fall back to the first card, or to
                    // a spatially adjacent row such as Films.
                    stableItemFocusRequestersByRow[targetRowKey]
                        ?.value
                        ?.get(targetItemIndex)
                        ?: targetRowKey?.let(rowFocusRequesters::get)
                        ?: FocusRequester.Default
                }
                .dpadVerticalFastScroll(
                    scrollableState = verticalRowListState,
                    onFastScrollingChanged = onFastScrollingChanged,
                    shouldHaltForward = {
                        val info = verticalRowListState.layoutInfo
                        val lastIdx = orderedRows.size - 1
                        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
                        lastIdx >= 0 && lastVisible != null &&
                            lastVisible.offset + lastVisible.size <= info.viewportEndOffset
                    },
                    resolveVerticalLanding = { sign ->
                        val layoutInfo = verticalRowListState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        val lastIdx = orderedRows.size - 1
                        val viewportEnd = layoutInfo.viewportEndOffset
                        val lastRowAtBottom = lastIdx >= 0 &&
                            visibleItems.lastOrNull { it.index == lastIdx }?.let {
                                it.offset + it.size <= viewportEnd
                            } == true
                        val upwardTopRow: LazyListItemInfo? = if (sign < 0) {
                            visibleItems.firstOrNull()?.takeIf {
                                it.offset > -it.size / 2
                            }
                        } else null
                        val targetRowIndex = when {
                            lastRowAtBottom -> lastIdx
                            upwardTopRow != null -> upwardTopRow.index
                            else ->
                                visibleItems.firstOrNull { it.offset >= 0 }?.index
                                    ?: visibleItems.firstOrNull()?.index
                                    ?: verticalRowListState.firstVisibleItemIndex
                        }
                        val targetEntry = orderedRows.getOrNull(targetRowIndex)
                        if (targetEntry == null) null
                        else {
                            val (targetKey, targetRow) = targetEntry
                            if (targetRow == null) {
                                latestOnActiveRowKeyChange.value(targetKey)
                                latestOnActiveItemIndexChange.value(0)
                                targetKey
                            } else {
                                val savedIdx = (focusedItemByRowMap[targetRow.key] ?: 0)
                                    .coerceIn(0, (targetRow.items.list.size - 1).coerceAtLeast(0))
                                latestOnActiveRowKeyChange.value(targetRow.key)
                                latestOnActiveItemIndexChange.value(savedIdx)

                                val targetItemKey = targetRow.items.list.getOrNull(savedIdx)?.key
                                    ?: "${targetRow.key}_$savedIdx"
                                targetItemKey
                            }
                        }
                    },
                ),
            // The first row is rendered at the top edge of the full-screen list.
            // Reserve the same header inset used by BringIntoView so its title is
            // never clipped by the screen/list bounds when Home opens at row 0.
            contentPadding = PaddingValues(
                top = MODERN_ROW_HEADER_FOCUS_INSET,
                bottom = rowsViewportHeight
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            itemsIndexed(
                items = orderedRows,
                key = { _, entry -> entry.first },
                contentType = { _, entry -> entry.second?.apiType ?: "freebox_videos" }
            ) { index, entry ->
                val row = entry.second
                if (row == null) {
                    val freeboxEntryFocusRequester = rowFocusRequesters.getOrPut("freebox_videos") { FocusRequester() }
                    FreeboxVideosSection(
                        entries = freeboxVideoEntries,
                        probedDurations = freeboxVideoProbedDurations,
                        onItemClick = { video ->
                            if (video.isDirectory) {
                                onNavigateToFreebox("freebox_folder:${video.path}", null, null, null)
                            } else {
                                // A normal Videos click always opens the information
                                // sheet first. Direct resume remains exclusive to CW.
                                showFreeboxDetails(video)
                            }
                        },
                        artworkMap = freeboxVideoArtwork,
                        backdropMap = freeboxVideoBackdrops,
                        metadataMap = freeboxVideoMetadata,
                        continueWatchingIds = continueWatchingContentIds,
                        cardWidth = videoCardWidth,
                        imageHeight = videoCardHeight,
                        // ContinueWatchingCard is the renderer used by the
                        // Videos row and applies its legacy 1.5x density factor
                        // internally. Films/Séries use the same effective
                        // geometry, so keep that factor here as well; the
                        // persisted media-row preset is already in the base
                        // width/height and remains shared by all three rows.
                        cardSizeMultiplier = 1.5f,
                        horizontalPadding = 52.dp,
                        itemSpacing = 12.dp,
                        onShowDetails = { video ->
                            showFreeboxDetails(video)
                        },
                        onDeleteFromFreebox = { video -> onDeleteFreeboxVideo(video) },
                        onRenameFreebox = onRenameFreeboxVideo,
                        fullScreenCoverflowPanelHeight = fullScreenCoverflowPanelHeight,
                        useCoverflow = "freebox_videos" in homeRowCarouselKeys,
                        onItemFocused = { itemIndex, item ->
                            lastFocusedRowKey.value = "freebox_videos"
                            val rowBecameActive = activeRowKey.value != "freebox_videos"
                            val itemChanged = activeItemIndex.value != itemIndex

                            if (rowBecameActive || itemChanged) {
                                val now = System.currentTimeMillis()
                                val timeSinceLastHeroNav = now - lastHeroNavigationAtMs.value
                                onHeroFocusSettleDelayChange(
                                    if (lastHeroNavigationAtMs.value != 0L &&
                                        timeSinceLastHeroNav in 1 until 130L // MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                    ) 400L // MODERN_HERO_RAPID_NAV_SETTLE_MS
                                    else 450L // MODERN_HERO_FOCUS_DEBOUNCE_MS
                                )
                                onLastHeroNavigationAtMsChange(now)
                                onActiveRowKeyChange("freebox_videos")
                                onActiveItemIndexChange(itemIndex)
                            }

                            if (focusedItemByRowMap["freebox_videos"] != itemIndex) {
                                focusedItemByRowMap["freebox_videos"] = itemIndex
                            }
                            onItemFocus(item)
                        },
                        entryFocusRequester = freeboxEntryFocusRequester,
                        externalFocusRequesters = freeboxFocusRequesters,
                        upFocusRequester = null,
                        onMoveUp = { moveFocusToOrderedRow(index - 1) },
                        // Do not capture the next row's container requester here.
                        // At this point its cards may not have registered their
                        // requesters yet; requesting the container can report
                        // success while focusRestorer sends focus back to CW.
                        downFocusRequester = null,
                        onMoveDown = { moveFocusToOrderedRow(index + 1) },
                    )
                    return@itemsIndexed
                }
                val stableOnContinueWatchingOptions = remember(onContinueWatchingOptions) {
                    { item: ContinueWatchingItem -> onContinueWatchingOptions(item) }
                }
                    val stableOnRowItemFocused = remember {
                        { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                        lastFocusedRowKey.value = rowKey
                        val rowBecameActive = activeRowKey.value != rowKey
                        val itemChanged = activeItemIndex.value != index

                        if (rowBecameActive || itemChanged) {
                            val now = System.currentTimeMillis()
                            val timeSinceLastHeroNav = now - lastHeroNavigationAtMs.value
                            onHeroFocusSettleDelayChange(
                                if (lastHeroNavigationAtMs.value != 0L &&
                                    timeSinceLastHeroNav in 1 until 130L // MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                ) 400L // MODERN_HERO_RAPID_NAV_SETTLE_MS
                                else 450L // MODERN_HERO_FOCUS_DEBOUNCE_MS
                            )
                            onLastHeroNavigationAtMsChange(now)
                            onActiveRowKeyChange(rowKey)
                            onActiveItemIndexChange(index)
                        }

                        // Always keep the focusedItemByRow map in sync for ALL rows
                        if (focusedItemByRowMap[rowKey] != index) {
                            focusedItemByRowMap[rowKey] = index
                        }

                        if (isContinueWatchingRow) {
                            if (lastFocusedContinueWatchingIndex.value != index) {
                                onLastFocusedContinueWatchingIndexChange(index)
                            }
                            if (lastFocusedContinueWatchingKey.value != rowKey) {
                                onLastFocusedContinueWatchingKeyChange(rowKey)
                            }
                            if (focusedCatalogSelection.value != null) {
                                onFocusedCatalogSelectionChange(null)
                            }
                        }
                        onRowItemFocusedInternal(rowKey, index, isContinueWatchingRow)
                    }
                }
                val isActiveRowLambda = remember(row.key) {
                    { row.key == activeRowKey.value }
                }
                val stableOnCatalogSelectionFocused = remember {
                    { selection: FocusedCatalogSelection ->
                        val isCollectionFolder = selection.payload is ModernPayload.CollectionFolder
                        if (focusedCatalogSelection.value != selection || isCollectionFolder) {
                            onFocusedCatalogSelectionChange(selection)
                            if (isCollectionFolder) {
                                onFocusedHeroMediaNonceChange(focusedHeroMediaNonce.value + 1)
                            }
                        }
                    }
                }
                val isIptvFavoriteHomeRow = row.key.endsWith(movieFavoritesRowSuffix) ||
                    row.key.endsWith(seriesFavoritesRowSuffix)
                // Favorite rows use the same fixed landscape cards as Videos;
                // never turn them into a focused/expanded carousel.
                // IPTV Films/Séries deliberately share the exact same card
                // renderer as the Videos row, including its title strip.
                val useIptvVideoCardTreatment = isIptvFavoriteHomeRow
                val useDetailedIptvFavoriteRow = false
                ModernRowSection(
                    row = row,
                    isActiveRow = isActiveRowLambda,
                    rowFocusRequester = rowFocusRequesters.getOrPut(row.key) { FocusRequester() },
                    // Never let Compose pick an unrelated screen element at
                    // the top/bottom boundary of the Home rows.
                    upFocusRequester = null,
                    downFocusRequester = null,
                    onMoveUp = { moveFocusToOrderedRow(index - 1) },
                    onMoveDown = { moveFocusToOrderedRow(index + 1) },
                    rowTitleBottom = 14.dp,
                    defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                    focusStateCatalogRowScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                    focusedItemByRow = focusedItemByRow,
                    rowListStates = rowListStates,
                    loadMoreRequestedTotals = loadMoreRequestedTotals,
                    pendingRowFocusKey = pendingRowFocusKey,
                    pendingRowFocusIndex = pendingRowFocusIndex,
                    pendingRowFocusNonce = pendingRowFocusNonce,
                    onPendingRowFocusCleared = onPendingRowFocusCleared,
                    onRowItemFocused = stableOnRowItemFocused,
                    useLandscapePosters = if (isIptvFavoriteHomeRow) true else useLandscapePosters,
                    useVideoCardTreatment = useIptvVideoCardTreatment,
                    isIptvFavoriteRow = isIptvFavoriteHomeRow,
                    useDetailedContinueWatching = useDetailedHomeMediaRows,
                    useCarousel = rowMatchesConfiguredKey(
                        row,
                        homeRowCarouselKeys
                    ),
                    showFocusedDetails = rowMatchesConfiguredKey(
                        row,
                        homeRowDetailedKeys
                    ),
                    fullScreenCoverflowPanelHeight = fullScreenCoverflowPanelHeight,
                    showLabels = showLabels,
                    posterCardCornerRadius = posterCardCornerRadius,
                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                    effectiveExpandEnabled = effectiveExpandEnabled && !isIptvFavoriteHomeRow,
                    effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                    trailerPlaybackTarget = trailerPlaybackTarget,
                    expandedCatalogFocusKey = expandedCatalogFocusKey,
                    expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                    expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                    portraitCatalogCardWidth = if (isIptvFavoriteHomeRow) videoCardWidth else portraitCatalogCardWidth,
                    portraitCatalogCardHeight = if (isIptvFavoriteHomeRow) videoCardHeight else portraitCatalogCardHeight,
                    landscapeCatalogCardWidth = if (isIptvFavoriteHomeRow) videoCardWidth else landscapeCatalogCardWidth,
                    landscapeCatalogCardHeight = if (isIptvFavoriteHomeRow) videoCardHeight else landscapeCatalogCardHeight,
                    continueWatchingCardWidth = continueWatchingCardWidth,
                    continueWatchingCardHeight = continueWatchingCardHeight,
                    carouselCardScale = carouselCardScale,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onContinueWatchingDetails = onContinueWatchingDetails,
                    onContinueWatchingOptions = stableOnContinueWatchingOptions,
                    isCatalogItemWatched = isCatalogItemWatched,
                    onCatalogItemLongPress = onCatalogItemLongPress,
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = onPreloadAdjacentItem,
                    enrichedPreviews = enrichedPreviews,
                    onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToFolderDetail = onNavigateToFolderDetail,
                    onLoadMoreCatalog = onLoadMoreCatalog,
                    onBackdropInteraction = onBackdropInteraction,
                    onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                    // All rows use the same LazyColumn spacing. Do not apply
                    // per-row offsets: they change the visual gap without
                    // changing layout bounds and make adjacent rows overlap.
                    modifier = Modifier,
                    isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                    itemFocusRequesters = stableItemFocusRequestersByRow.getOrPut(row.key) {
                        StableRef(mutableMapOf())
                    }
                )
            }

        }
    }
}
