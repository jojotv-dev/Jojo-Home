@file:OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import com.nuvio.tv.domain.model.PosterShape
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.jojotv.home.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.HomeCoverflowItem
import com.nuvio.tv.ui.components.HomeCoverflowSection
import com.nuvio.tv.ui.components.HomeSectionTitleChip
import com.nuvio.tv.ui.components.toHomeCoverflowItem
import com.nuvio.tv.ui.components.FavoriteInProgressBadge
import com.nuvio.tv.ui.components.FAVORITE_IN_PROGRESS_BADGE_TEXT
import com.nuvio.tv.ui.components.LocalFavoriteInProgressContentIds
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.components.rememberArtworkBackedCardGlow
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.ThemeColors
import kotlin.math.abs
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce

private const val MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS = 140L
private const val POSTER_PREFETCH_DISTANCE = 2
private const val NESTED_PREFETCH_COUNT = 2

internal val LocalVerticalRowsScrolling = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * True while the user is actively "fast-scrolling" — i.e. holding DPAD_LEFT/RIGHT or
 * DPAD_UP/DOWN and the LazyColumn-level key handler has taken over to drag the list
 * programmatically instead of letting [androidx.compose.ui.focus.FocusManager.moveFocus]
 * pull focus card-by-card. Cards use this to suppress their focus chrome (border / glow /
 * GIF) during the drag; the chrome snaps back onto whichever card focus lands on when
 * the user releases the key. Defaults to `false`, so any card used outside a modern
 * home row keeps its normal focus visuals.
 */
internal val LocalFastScrollActive = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    isTargetItem: Boolean = false,
    cardWidth: Dp,
    imageHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = remember(payload.item) { payload.item.withHomeDisplayTitle() }
    val onClick = remember(item) { { onContinueWatchingClick(item) } }
    val onLongPress = remember(item) { { onShowOptions(item) } }
    var focusEventId by remember { mutableIntStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)

    LaunchedEffect(focusEventId, isCardFocused) {
        if (focusEventId == 0 || !isCardFocused) return@LaunchedEffect

        // Update global focus state immediately so "self-claiming" logic in other items
        // knows this item is now the one in charge.
        latestOnFocused()

        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) return@LaunchedEffect
    }

    LaunchedEffect(isTargetItem) {
        if (isTargetItem && !isCardFocused) {
            runCatching { requester.requestFocus() }
        }
    }

    ContinueWatchingCard(
        item = item,
        onClick = onClick,
        onLongPress = onLongPress,
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        useEpisodeThumbnails = useEpisodeThumbnails,
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged {
                isCardFocused = it.isFocused
                if (it.isFocused) {
                    focusEventId += 1
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    item: ModernCarouselItem,
    payload: ModernPayload,
    requester: FocusRequester,
    isTargetItem: Boolean = false,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    placeholderShimmerOffsetState: State<Float>?,
    posterCardCornerRadius: Dp,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    isBackdropExpanded: () -> Boolean,
    useVideoCardTreatment: Boolean = false,
    isIptvFavoriteRow: Boolean = false,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    isFocusTarget: Boolean = false,
    onFocused: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
    modifier: Modifier = Modifier
) {
    val focusKey = when (payload) {
        is ModernPayload.Catalog -> payload.focusKey
        is ModernPayload.CollectionFolder -> payload.focusKey
        is ModernPayload.ContinueWatching -> error("Unsupported payload for ModernCatalogRowItem")
    }

    val metaPreview = item.metaPreview
    val isWatched = metaPreview?.let { isCatalogItemWatched(it) } ?: false
    val enrichedMeta by remember {
        derivedStateOf { (payload as? ModernPayload.Catalog)?.itemId?.let { enrichedPreviews.value.map[it] } }
    }
    val enrichedLogoUrl = enrichedMeta?.logo
    val enrichedPosterUrl = enrichedMeta?.poster?.takeIf { it.isNotBlank() }
    val enrichedBackdropUrl = enrichedMeta?.background?.takeIf { it.isNotBlank() }
        ?: enrichedMeta?.landscapePoster?.takeIf { it.isNotBlank() }

    var focusEventId by remember { mutableIntStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnPreloadAdjacentItem by rememberUpdatedState(onPreloadAdjacentItem)
    val latestOnCatalogSelectionFocused by rememberUpdatedState(onCatalogSelectionFocused)

    // Bump focusEventId to re-trigger selection reporting.
    //LaunchedEffect(focusKey, isTargetItem) {
    //    if (isTargetItem && isCardFocused) {
    //        focusEventId++
    //    }
    //}

    LaunchedEffect(focusEventId, isCardFocused, focusKey) {
        if (focusEventId == 0 || !isCardFocused) {
            return@LaunchedEffect
        }

        // Update global focus state immediately so "self-claiming" logic in other items
        // knows this item is now the one in charge.
        latestOnFocused()

        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) {
            return@LaunchedEffect
        }

        // Heavy "settled" work (trailers, enrichment) remains debounced.
        item.metaPreview?.let { latestOnItemFocus(it) }
        latestOnPreloadAdjacentItem()
        when (payload) {
            is ModernPayload.Catalog -> {
                if (!payload.itemId.startsWith("__placeholder_")) {
                    latestOnCatalogSelectionFocused(
                        FocusedCatalogSelection(
                            focusKey = focusKey,
                            payload = payload
                        )
                    )
                }
            }
            is ModernPayload.CollectionFolder -> {
                latestOnCatalogSelectionFocused(
                    FocusedCatalogSelection(
                        focusKey = focusKey,
                        payload = payload
                    )
                )
            }
            is ModernPayload.ContinueWatching -> Unit
        }
    }

    LaunchedEffect(isTargetItem) {
        if (isTargetItem && !isCardFocused) {
            runCatching { requester.requestFocus() }
        }
    }

    val effectiveTrailerPlaybackTarget = trailerPlaybackTarget
    val suppressCardExpansionForHeroTrailer =
        effectiveAutoplayEnabled &&
                effectiveTrailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    val effectiveBackdropExpanded by remember(isBackdropExpanded, suppressCardExpansionForHeroTrailer) {
        derivedStateOf { isBackdropExpanded() && !suppressCardExpansionForHeroTrailer }
    }

    val isSidebarExpanded = LocalSidebarExpanded.current
    // Trailer playback is now an explicit long-press action on the Home card.
    val playTrailerInExpandedCard = false
    val trailerPreviewUrl: String? = null
    val trailerPreviewAudioUrl: String? = null
    // Video treatment changes the artwork behavior, not the card orientation.
    // The three Home media rows must keep the shared landscape geometry.
    val effectiveUseLandscapePosters = useLandscapePosters
    val cardMetrics = remember(
        item,
        effectiveUseLandscapePosters,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        item.catalogCardMetrics(
            useLandscapePosters = effectiveUseLandscapePosters,
            portraitCardWidth = portraitCatalogCardWidth,
            portraitCardHeight = portraitCatalogCardHeight,
            landscapeCardWidth = landscapeCatalogCardWidth,
            landscapeCardHeight = landscapeCatalogCardHeight
        )
    }

    ModernCarouselCard(
        item = item,
        useLandscapeOverlayTreatment = effectiveUseLandscapePosters,
        useVideoCardTreatment = useVideoCardTreatment,
        showLabels = showLabels,
        placeholderShimmerOffsetState = placeholderShimmerOffsetState,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = cardMetrics.width,
        cardHeight = cardMetrics.height,
        modifier = modifier,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = effectiveBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        isWatched = isWatched,
        isIptvFavoriteRow = isIptvFavoriteRow,
        enrichedMeta = enrichedMeta,
        enrichedLogoUrl = enrichedLogoUrl,
        enrichedPosterUrl = enrichedPosterUrl,
        enrichedBackdropUrl = enrichedBackdropUrl,
        focusRequester = requester,
        onFocused = {
            focusEventId += 1
        },
        onFocusStateChanged = { focused ->
            isCardFocused = focused
        },
        onClick = {
            latestOnFocused()
            item.metaPreview?.let { latestOnItemFocus(it) }
            when (payload) {
                is ModernPayload.Catalog -> {
                    if (!payload.itemId.startsWith("__placeholder_")) {
                        onNavigateToDetail(
                            payload.itemId,
                            payload.itemType,
                            payload.addonBaseUrl
                        )
                    }
                }
                is ModernPayload.CollectionFolder -> onNavigateToFolderDetail(
                    payload.collectionId,
                    payload.folderId
                )
                is ModernPayload.ContinueWatching -> Unit
            }
        },
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction,
        onTrailerEnded = { onExpandedCatalogFocusKeyChange(null) }
    )

}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    isActiveRow: () -> Boolean,
    rowFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    isVerticalRowsScrollingState: State<Boolean>,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollIndex: Int,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    pendingRowFocusKey: State<String?>,
    pendingRowFocusIndex: State<Int?>,
    pendingRowFocusNonce: State<Int>,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    useVideoCardTreatment: Boolean = false,
    isIptvFavoriteRow: Boolean = false,
    useDetailedContinueWatching: Boolean = false,
    useCarousel: Boolean = false,
    showFocusedDetails: Boolean = false,
    fullScreenCoverflowPanelHeight: Dp? = null,
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
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>> = androidx.compose.runtime.mutableStateOf(StableMap()),
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    itemFocusRequesters: StableRef<MutableMap<Int, FocusRequester>> = StableRef(mutableMapOf()),
    modifier: Modifier = Modifier
) {
    // Unwrap StableRef wrappers
    @Suppress("NAME_SHADOWING") val focusedItemByRow = focusedItemByRow.value
    @Suppress("NAME_SHADOWING") val rowListStates = rowListStates.value
    @Suppress("NAME_SHADOWING") val loadMoreRequestedTotals = loadMoreRequestedTotals.value
    @Suppress("NAME_SHADOWING") val itemFocusRequesters = itemFocusRequesters.value
    val rowKey = row.key

    // Per-row derived state: only invalidates when THIS row's focused index
    // changes, not when any other row's index changes in the shared map.
    val rowFocusedIndex = remember(rowKey) {
        derivedStateOf { focusedItemByRow[rowKey] ?: 0 }
    }

    // The detailed Home option gives every media row its own panel; rows never
    // share selection, metadata or cards.
    val useCoverflow = useCarousel || (useDetailedContinueWatching &&
        rowKey == MODERN_CONTINUE_WATCHING_ROW_KEY)
    if (useCoverflow) {
        val coverflowItems = row.items.list.mapNotNull { carouselItem ->
            when (val payload = carouselItem.payload) {
                is ModernPayload.ContinueWatching -> payload.item.toHomeCoverflowItem(
                    // A short press on Continue Watching is always a resume
                    // action. Details remain available from the long-press
                    // menu; metadata routing can send IPTV items to the old
                    // addon stream screen.
                    onClick = { onContinueWatchingClick(payload.item) },
                    onLongPress = { onContinueWatchingOptions(payload.item) }
                )
                is ModernPayload.Catalog -> carouselItem.metaPreview?.let { preview ->
                    HomeCoverflowItem(
                        key = carouselItem.key,
                        title = preview.name,
                        meta = listOfNotNull(
                            preview.genres.firstOrNull()?.takeIf { it.isNotBlank() },
                            preview.releaseInfo?.takeIf { it.isNotBlank() },
                            preview.runtime?.takeIf { it.isNotBlank() }
                        ).joinToString(" • "),
                        cast = preview.cast,
                        director = preview.director,
                        synopsis = preview.description,
                        artworkUrl = preview.landscapePoster ?: preview.background ?: preview.poster,
                        portraitArtworkUrl = preview.poster,
                        onClick = { onNavigateToDetail(payload.itemId, payload.itemType, payload.addonBaseUrl) },
                        onLongPress = { onCatalogItemLongPress(preview, payload.addonBaseUrl) }
                    )
                }
                is ModernPayload.CollectionFolder -> null
            }
        }
        if (coverflowItems.isNotEmpty()) {
            val focusRequesters = remember(rowKey, coverflowItems.size) {
                List(coverflowItems.size) { index ->
                    itemFocusRequesters.getOrPut(index) { FocusRequester() }
                }
            }
            HomeCoverflowSection(
                items = coverflowItems,
                sectionTitle = row.title,
                cardScale = carouselCardScale,
                modifier = modifier,
                entryFocusRequester = rowFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                initialFocusedIndex = rowFocusedIndex.value,
                focusRequesters = focusRequesters,
                canRequestInitialFocus = isActiveRow(),
                onItemFocused = { index ->
                    onRowItemFocused(rowKey, index, true)
                    row.items.list.getOrNull(index)?.metaPreview?.let(onItemFocus)
                }
            )
            return
        }
    }

    // Blocks vertical focus exit during placeholder→data transition.
    val blockingFocusExit = remember { mutableStateOf(false) }
    Column(
        modifier = modifier.then(
            if (blockingFocusExit.value) {
                Modifier.focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
            } else Modifier
        )
    ) {
        val headlineMediumStyle = MaterialTheme.typography.headlineMedium
        val rowTitleStyle = remember(headlineMediumStyle) {
            headlineMediumStyle.copy(fontWeight = FontWeight.Normal)
        }
        val rowTitle = row.title
        // NuvioColors is backed by the active Compose theme, so it must be
        // read from the composable scope rather than from remember's lambda.
        HomeSectionTitleChip(
            title = rowTitle,
            modifier = Modifier.padding(start = 52.dp, bottom = rowTitleBottom)
        )

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollIndex,
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = NESTED_PREFETCH_COUNT)
            )
        }

        val firstItemKey = row.items.list.firstOrNull()?.key

        // When placeholder items are replaced by real data and this row
        // is the active row, re-request focus on the first real item.
        val firstItemImageUrl = row.items.list.firstOrNull()?.imageUrl
        val wasPlaceholderRef = remember { mutableStateOf(row.isLoading && firstItemImageUrl == "placeholder://empty") }
        val needsFocusRestore = remember { mutableStateOf(false) }

        LaunchedEffect(row.isLoading, firstItemImageUrl, isActiveRow) {
            val wasPlaceholder = wasPlaceholderRef.value
            val isNowReal = !row.isLoading || firstItemImageUrl != "placeholder://empty"
            if (wasPlaceholder && isNowReal && isActiveRow()) {
                needsFocusRestore.value = true
                blockingFocusExit.value = true
            }
            wasPlaceholderRef.value = row.isLoading && firstItemImageUrl == "placeholder://empty"
        }

        // Restore focus after placeholder→data transition.
        LaunchedEffect(needsFocusRestore.value, row.key) {
            if (!needsFocusRestore.value) return@LaunchedEffect
            needsFocusRestore.value = false
            blockingFocusExit.value = false
        }

        val isRowScrollingState = remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key, pendingRowFocusNonce.value) {
            if (pendingRowFocusKey.value != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex.value ?: 0)
                .coerceIn(0, (row.items.list.size - 1).coerceAtLeast(0))
            if (!rowListState.isScrollInProgress) {
                runCatching { rowListState.scrollToItem(targetIndex) }
            }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val rowStartPadding = 52.dp
        val context = LocalContext.current
        val imageLoader = context.imageLoader

        val rowItemCount = row.items.list.size
        LaunchedEffect(
            row.key,
            isActiveRow,
            isVerticalRowsScrollingState,
            rowItemCount,
            portraitCatalogCardWidth,
            portraitCatalogCardHeight,
            landscapeCatalogCardWidth,
            landscapeCatalogCardHeight,
            continueWatchingCardWidth,
            continueWatchingCardHeight
        ) {
            if (!isActiveRow() || isVerticalRowsScrollingState.value) return@LaunchedEffect
            // Continue Watching is the first visible Home row.  Its cards use
            // a 1.5x decode target in ContinueWatchingCard, so prefetch with
            // that exact target and key.  The former unscaled, custom key was
            // never consumed by the card and every launch downloaded again.
            if (row.items.list.none { it.payload is ModernPayload.ContinueWatching }) {
                delay(150) // Avoid request bursts while moving between catalog rows.
            }
            val cwWidthPx = with(density) { (continueWatchingCardWidth * 1.5f).roundToPx() }
            val cwHeightPx = with(density) { (continueWatchingCardHeight * 1.5f).roundToPx() }
            fun imageUrlAndKey(item: ModernCarouselItem): Pair<String, String>? {
                val url = item.imageUrl ?: return null
                return when (item.payload) {
                    is ModernPayload.Catalog -> {
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        val widthPx = with(density) { metrics.width.roundToPx() }
                        val heightPx = with(density) { metrics.height.roundToPx() }
                        url to "jojotv-prefetch/v2/${item.key}|catalog|${url}_${widthPx}x${heightPx}"
                    }
                    is ModernPayload.CollectionFolder -> {
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        val widthPx = with(density) { metrics.width.roundToPx() }
                        val heightPx = with(density) { metrics.height.roundToPx() }
                        url to "jojotv-prefetch/v2/${item.key}|collection|${url}_${widthPx}x${heightPx}"
                    }
                    is ModernPayload.ContinueWatching -> url to "${url}_${cwWidthPx}x${cwHeightPx}_blurfalse"
                }
            }
            fun enqueueIfNeeded(item: ModernCarouselItem, widthPx: Int, heightPx: Int) {
                val (url, cacheKey) = imageUrlAndKey(item) ?: return
                if (imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null) return
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(url)
                        .size(width = widthPx, height = heightPx)
                        .build()
                )
            }
            // Prefetch initial visible + ahead items immediately when row appears
            val items = currentRowState.value.items.list
            withContext(Dispatchers.IO) {
                for (i in 0 until minOf(POSTER_PREFETCH_DISTANCE, items.size)) {
                    val item = items.getOrNull(i) ?: continue
                    val (wPx, hPx) = when (item.payload) {
                        is ModernPayload.Catalog -> {
                            val metrics = item.catalogCardMetrics(
                                useLandscapePosters = useLandscapePosters,
                                portraitCardWidth = portraitCatalogCardWidth,
                                portraitCardHeight = portraitCatalogCardHeight,
                                landscapeCardWidth = landscapeCatalogCardWidth,
                                landscapeCardHeight = landscapeCatalogCardHeight
                            )
                            with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                        }
                        is ModernPayload.CollectionFolder -> {
                            val metrics = item.catalogCardMetrics(
                                useLandscapePosters = useLandscapePosters,
                                portraitCardWidth = portraitCatalogCardWidth,
                                portraitCardHeight = portraitCatalogCardHeight,
                                landscapeCardWidth = landscapeCatalogCardWidth,
                                landscapeCardHeight = landscapeCatalogCardHeight
                            )
                            with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                        }
                        is ModernPayload.ContinueWatching -> cwWidthPx to cwHeightPx
                    }
                    enqueueIfNeeded(item, wPx, hPx)
                }
            }

            snapshotFlow {
                rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }
                .distinctUntilChanged()
                .collect { lastVisibleIndex ->
                    val currentItems = currentRowState.value.items.list
                    withContext(Dispatchers.IO) {
                        for (i in (lastVisibleIndex + 1)..(lastVisibleIndex + POSTER_PREFETCH_DISTANCE)) {
                            val item = currentItems.getOrNull(i) ?: continue
                            val (wPx, hPx) = when (item.payload) {
                                is ModernPayload.Catalog -> {
                                    val metrics = item.catalogCardMetrics(
                                        useLandscapePosters = useLandscapePosters,
                                        portraitCardWidth = portraitCatalogCardWidth,
                                        portraitCardHeight = portraitCatalogCardHeight,
                                        landscapeCardWidth = landscapeCatalogCardWidth,
                                        landscapeCardHeight = landscapeCatalogCardHeight
                                    )
                                    with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                                }
                                is ModernPayload.CollectionFolder -> {
                                    val metrics = item.catalogCardMetrics(
                                        useLandscapePosters = useLandscapePosters,
                                        portraitCardWidth = portraitCatalogCardWidth,
                                        portraitCardHeight = portraitCatalogCardHeight,
                                        landscapeCardWidth = landscapeCatalogCardWidth,
                                        landscapeCardHeight = landscapeCatalogCardHeight
                                    )
                                    with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                                }
                                is ModernPayload.ContinueWatching -> cwWidthPx to cwHeightPx
                            }
                            enqueueIfNeeded(item, wPx, hPx)
                        }
                    }
                }
        }

        val layoutDirection = LocalLayoutDirection.current
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding, isRtl) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    if (isRtl) {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = containerSize - parentStartOffsetPx.toFloat()
                        val targetForTrailingEdge =
                            if (childSmallerThanParent && initialTarget < childSize) {
                                childSize
                            } else {
                                initialTarget
                            }
                        return (offset + size) - targetForTrailingEdge
                    } else {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = parentStartOffsetPx.toFloat()
                        val spaceAvailable = containerSize - initialTarget

                        val targetForLeadingEdge =
                            if (childSmallerThanParent && spaceAvailable < childSize) {
                                containerSize - childSize
                            } else {
                                initialTarget
                            }

                        return offset - targetForLeadingEdge
                    }
                }
            }
        }

        // When a poster in this row expands, ensure it scrolls fully into view.
        var isExpansionScrollActive by remember { mutableStateOf(false) }
        val expandedCardWidthPx = with(density) {
            if (useLandscapePosters) {
                landscapeCatalogCardWidth.roundToPx()
            } else {
                (portraitCatalogCardHeight * (16f / 9f)).roundToPx()
            }
        }
        LaunchedEffect(row.key, effectiveExpandEnabled, rowItemCount) {
            if (!effectiveExpandEnabled) return@LaunchedEffect
            snapshotFlow { expandedCatalogFocusKey.value }
                .collect { expandedKey ->
                    if (expandedKey == null) return@collect
                    val lastIndex = row.items.list.lastIndex
                    if (lastIndex < 0) return@collect
                    // Find the index of the expanded item in this row
                    val expandedIndex = row.items.list.indexOfFirst { item ->
                        when (val p = item.payload) {
                            is ModernPayload.Catalog -> p.focusKey == expandedKey
                            is ModernPayload.CollectionFolder -> p.focusKey == expandedKey
                            else -> false
                        }
                    }
                    if (expandedIndex < 0) return@collect
                    // Only act on the last two items in the row
                    if (expandedIndex < lastIndex - 1) return@collect
                    // Small delay so the item is still in visible layout info
                    delay(50)
                    // Calculate overshoot using the known final expanded width rather than
                    // the mid-animation layout size which underestimates the trailing edge.
                    val layoutInfo = rowListState.layoutInfo
                    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == expandedIndex }
                        ?: return@collect
                    val viewportEnd = layoutInfo.viewportEndOffset
                    val itemEndExpanded = itemInfo.offset + expandedCardWidthPx
                    if (itemEndExpanded > viewportEnd) {
                        // Scroll just enough to reveal the trailing edge plus a small margin.
                        // Flag prevents isBackdropExpandedLambda from collapsing during this scroll.
                        val overshoot = itemEndExpanded - viewportEnd + with(density) { 15.dp.roundToPx() }
                        isExpansionScrollActive = true
                        rowListState.animateScrollBy(overshoot.toFloat())
                        isExpansionScrollActive = false
                    }
                }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            val usesPlaceholderShimmer = row.isLoading &&
                row.items.list.firstOrNull()?.imageUrl?.startsWith("placeholder://") == true
            val placeholderShimmerOffsetState = if (usesPlaceholderShimmer) {
                rememberPlaceholderShimmerOffsetState(label = "placeholderShimmer")
            } else {
                null
            }

            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .recompositionHighlighter()
                    .focusRequester(rowFocusRequester)
                    .focusProperties {
                        up = if (onMoveUp != null) FocusRequester.Cancel
                            else upFocusRequester ?: FocusRequester.Default
                        down = if (onMoveDown != null) FocusRequester.Cancel
                            else downFocusRequester ?: FocusRequester.Default
                    }
                    .focusRestorer {
                        val savedIdx = rowFocusedIndex.value
                        itemFocusRequesters[savedIdx]
                            ?: itemFocusRequesters[0]
                            ?: FocusRequester.Default
                    }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val currentIndex = rowFocusedIndex.value
                        when (event.key) {
                            Key.DirectionUp -> onMoveUp?.invoke()
                                ?: upFocusRequester?.let { requester ->
                                    runCatching { requester.requestFocus() }.getOrDefault(false)
                                }
                                ?: false
                            Key.DirectionDown -> onMoveDown?.invoke()
                                ?: downFocusRequester?.let { requester ->
                                    runCatching { requester.requestFocus() }.getOrDefault(false)
                                }
                                ?: false
                            Key.DirectionLeft -> when {
                                currentIndex > 0 -> runCatching {
                                    itemFocusRequesters[currentIndex - 1]?.requestFocus()
                                }.getOrDefault(false) ?: false
                                row.items.list.getOrNull(currentIndex)?.payload is ModernPayload.CollectionFolder ->
                                    runCatching {
                                        itemFocusRequesters[row.items.list.lastIndex]?.requestFocus()
                                    }.getOrDefault(false)
                                else -> false // only non-folder rows may hand focus to the sidebar
                            }
                            Key.DirectionRight -> when {
                                row.isLoading -> true
                                currentIndex < row.items.list.lastIndex -> runCatching {
                                    itemFocusRequesters[currentIndex + 1]?.requestFocus()
                                }.getOrDefault(false) ?: false
                                row.items.list.getOrNull(currentIndex)?.payload is ModernPayload.CollectionFolder ->
                                    runCatching {
                                        itemFocusRequesters[0]?.requestFocus()
                                    }.getOrDefault(false)
                                else -> false
                            }
                            else -> false
                        }
                    },
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = row.items.list,
                    key = { _, item -> item.key },
                    contentType = { _, item ->
                        when (val payload = item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> if (payload.itemId.startsWith("__placeholder_")) "placeholder" else payload.itemType
                            is ModernPayload.CollectionFolder -> "modern_collection_folder_card"
                        }
                    }
                ) { index, item ->
                    val requester = itemFocusRequesters.getOrPut(index) { FocusRequester() }
                    val isContinueWatchingRow = row.key == MODERN_CONTINUE_WATCHING_ROW_KEY
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        {
                            onRowItemFocused(row.key, index, isContinueWatchingRow)
                            if (pendingRowFocusKey.value == row.key && (pendingRowFocusIndex.value ?: 0) == index) {
                                onPendingRowFocusCleared()
                            }
                        }
                    }

                    // Use derivedStateOf so only the ONE item that becomes/loses
                    // target status recomposes — not all items in all visible rows.
                    val isTargetItem by remember(row.key, index) {
                        derivedStateOf {
                            val isPending = pendingRowFocusKey.value == row.key &&
                                (pendingRowFocusIndex.value ?: 0) == index
                            val isCurrent = isActiveRow() &&
                                rowFocusedIndex.value == index
                            isPending || isCurrent
                        }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                isTargetItem = isTargetItem,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                                useEpisodeThumbnails = useEpisodeThumbnails,
                                onFocused = onFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                            )
                        }

                        is ModernPayload.Catalog,
                        is ModernPayload.CollectionFolder -> {
                            val nextCatalogItem = row.items.list.getOrNull(index + 1)?.metaPreview
                            val prevCatalogItem = row.items.list.getOrNull(index - 1)?.metaPreview
                            val metaPreview = item.metaPreview
                            val onLongPress: () -> Unit = {
                                if (payload is ModernPayload.Catalog) {
                                    metaPreview?.let { preview ->
                                        onCatalogItemLongPress(preview, payload.addonBaseUrl)
                                    }
                                }
                            }
                            val expandedFocusKey = when (payload) {
                                is ModernPayload.Catalog -> payload.focusKey
                                is ModernPayload.CollectionFolder -> payload.focusKey
                            }
                            val isBackdropExpandedLambda = remember(
                                effectiveExpandEnabled,
                                isRowScrollingState,
                                expandedCatalogFocusKey,
                                expandedFocusKey
                            ) {
                                {
                                    effectiveExpandEnabled &&
                                        (!isRowScrollingState.value || isExpansionScrollActive) &&
                                        expandedCatalogFocusKey.value == expandedFocusKey
                                }
                            }
                            ModernCatalogRowItem(
                                item = item,
                                payload = payload,
                                requester = requester,
                                isTargetItem = isTargetItem,
                                useLandscapePosters = useLandscapePosters,
                                showLabels = showLabels,
                                placeholderShimmerOffsetState = placeholderShimmerOffsetState,
                                posterCardCornerRadius = posterCardCornerRadius,
                                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                effectiveExpandEnabled = effectiveExpandEnabled,
                                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                                trailerPlaybackTarget = trailerPlaybackTarget,
                                isBackdropExpanded = isBackdropExpandedLambda,
                                useVideoCardTreatment = useVideoCardTreatment,
                                isIptvFavoriteRow = isIptvFavoriteRow,
                                expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                                portraitCatalogCardWidth = portraitCatalogCardWidth,
                                portraitCatalogCardHeight = portraitCatalogCardHeight,
                                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onFocused = onFocused,
                                onItemFocus = onItemFocus,
                                onPreloadAdjacentItem = remember(nextCatalogItem, prevCatalogItem, onPreloadAdjacentItem) {
                                     {
                                         nextCatalogItem?.let(onPreloadAdjacentItem)
                                         prevCatalogItem?.let(onPreloadAdjacentItem)
                                     }
                                },
                                onCatalogSelectionFocused = if (showFocusedDetails) {
                                    onCatalogSelectionFocused
                                } else {
                                    { }
                                },
                                onNavigateToDetail = onNavigateToDetail,
                                onNavigateToFolderDetail = onNavigateToFolderDetail,
                                onLongPress = onLongPress,
                                onBackdropInteraction = onBackdropInteraction,
                                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                                enrichedPreviews = enrichedPreviews
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapeOverlayTreatment: Boolean,
    useVideoCardTreatment: Boolean = false,
    showLabels: Boolean,
    placeholderShimmerOffsetState: State<Float>? = null,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    isIptvFavoriteRow: Boolean = false,
    enrichedMeta: MetaPreview? = null,
    enrichedLogoUrl: String? = null,
    enrichedPosterUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val favoriteInProgressContentIds = LocalFavoriteInProgressContentIds.current
    val favoriteMeta = enrichedMeta?.takeIf { it.isFavorite } ?: item.metaPreview
    val favoriteIdentityCandidates = setOfNotNull(
        item.key.takeIf { it.isNotBlank() },
        item.metaPreview?.id?.takeIf { it.isNotBlank() },
        item.metaPreview?.playbackProgressId?.takeIf { it.isNotBlank() },
        favoriteMeta?.id?.takeIf { it.isNotBlank() },
        favoriteMeta?.playbackProgressId?.takeIf { it.isNotBlank() }
    )
    val isFavoriteInProgress =
        (item.metaPreview?.isFavorite == true || favoriteMeta?.isFavorite == true) &&
            favoriteIdentityCandidates.any(favoriteInProgressContentIds::contains)
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val context = LocalContext.current
    val density = LocalDensity.current
    var isFocused by remember { mutableStateOf(false) }
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val payload = item.payload as? ModernPayload.CollectionFolder
    val isCollectionFolder = item.payload is ModernPayload.CollectionFolder
    val expandedCardWidth = if (useLandscapeOverlayTreatment) {
        cardWidth
    } else {
        cardHeight * (16f / 9f)
    }
    val shouldUseFocusedVideoWidth = useVideoCardTreatment &&
        !isIptvFavoriteRow &&
        isFocused &&
        !useLandscapeOverlayTreatment &&
        !isCollectionFolder
    val targetCardWidth = when {
        shouldUseFocusedVideoWidth -> expandedCardWidth
        focusedPosterBackdropExpandEnabled && isBackdropExpanded -> expandedCardWidth
        else -> cardWidth
    }
    val animatedCardWidthState = if (focusedPosterBackdropExpandEnabled || useVideoCardTreatment) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    val animatedCardWidth by animatedCardWidthState

    if (useVideoCardTreatment && !isCollectionFolder) {
        val enrichedRuntime = enrichedMeta?.runtime
        val enrichedTitle = enrichedMeta?.name?.takeIf { it.isNotBlank() }
        // The catalog preview is the authoritative artwork for this card.
        // Focus enrichment is asynchronous and may belong to a fuzzy match;
        // letting it win here replaces a correct poster as soon as focus lands.
        val explicitPosterUrl = item.metaPreview?.poster?.takeIf { it.isNotBlank() }
            ?: enrichedPosterUrl
            ?: item.heroPreview.poster?.takeIf { it.isNotBlank() }
            ?: item.imageUrl?.takeIf { it.isNotBlank() }
        val preferOfficialIptvBackdrop = item.metaPreview?.rawType == "iptv_movie" ||
            item.metaPreview?.rawType == "iptv_series"
        val explicitBackdropUrl = item.metaPreview?.homeArtworkOverride ?: if (preferOfficialIptvBackdrop) {
            enrichedBackdropUrl
                ?: item.metaPreview?.background?.takeIf { it.isNotBlank() }
                ?: item.metaPreview?.landscapePoster?.takeIf { it.isNotBlank() }
        } else {
            item.metaPreview?.background?.takeIf { it.isNotBlank() }
                ?: item.metaPreview?.landscapePoster?.takeIf { it.isNotBlank() }
                ?: enrichedBackdropUrl
        }
            ?: item.heroPreview.backdrop?.takeIf { backdrop ->
                backdrop.isNotBlank() && backdrop != explicitPosterUrl && backdrop != item.imageUrl
            }
        val sourceMovieDurationSeconds = item.metaPreview
            ?.takeIf { it.rawType == "iptv_movie" }
            ?.homeIptvDurationSortKey()
        // Most IPTV providers omit a duration. Once exact metadata arrives,
        // use its runtime for the card as well; otherwise ContinueWatchingCard
        // receives zero and removes the already formatted duration prefix.
        val resolvedMovieRuntime = item.metaPreview?.runtime
            ?: enrichedRuntime
            ?: item.heroPreview.runtimeText
        val displayedMovieDurationSeconds = sourceMovieDurationSeconds
            ?: resolvedMovieRuntime
                ?.let(::parseIptvDurationSeconds)
                ?.takeIf { it > 0L }
        val previewProgress = remember(
            item,
            enrichedTitle,
            enrichedRuntime,
            sourceMovieDurationSeconds,
            displayedMovieDurationSeconds,
            explicitPosterUrl,
            explicitBackdropUrl
        ) {
            val catalogPayload = item.payload as? ModernPayload.Catalog
            val iptvContentType = when {
                catalogPayload?.trailerApiType.equals("series", ignoreCase = true) -> "iptv_series"
                catalogPayload?.trailerApiType.equals("iptv_series", ignoreCase = true) -> "iptv_series"
                catalogPayload?.itemType.equals("series", ignoreCase = true) -> "iptv_series"
                catalogPayload?.itemType.equals("iptv_series", ignoreCase = true) -> "iptv_series"
                else -> "iptv_movie"
            }
            val baseTitle = enrichedTitle
                ?: item.metaPreview?.name?.takeIf { it.isNotBlank() }
                ?: item.title
            val displayTitle = if (iptvContentType == "iptv_movie") {
                // The Films row is ordered with the IPTV duration carried by
                // its source preview. Do not let a later TMDB enrichment show
                // a different runtime, otherwise the visible labels no longer
                // match the row order.
                prefixHomeIptvMovieDuration(
                    baseTitle,
                    resolvedMovieRuntime
                )
            } else {
                cleanHomeIptvDisplayTitle(baseTitle)
            }
            WatchProgress(
                contentId = item.key,
                contentType = iptvContentType,
                name = displayTitle,
                poster = explicitPosterUrl,
                backdrop = explicitBackdropUrl,
                logo = null,
                videoId = item.key,
                season = null,
                episode = null,
                episodeTitle = null,
                position = 0L,
                // ContinueWatchingCard normalizes IPTV movie titles from this
                // numeric duration. It must carry either the provider duration
                // or the exact enriched runtime shown in the title.
                duration = displayedMovieDurationSeconds?.times(1_000L) ?: 0L,
                lastWatched = 0L
            )
        }
        ContinueWatchingCard(
            item = ContinueWatchingItem.InProgress(previewProgress),
            onClick = onClick,
            onLongPress = onLongPress,
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusStateChanged(it.isFocused)
                    if (it.isFocused) {
                        onFocused()
                    }
                },
            cardWidth = animatedCardWidth,
            imageHeight = cardHeight,
            showBadge = isFavoriteInProgress,
            badgeTextOverride = if (isFavoriteInProgress) FAVORITE_IN_PROGRESS_BADGE_TEXT else null,
            showFavoriteMarker = (enrichedMeta ?: item.metaPreview)?.isFavorite == true && !isFavoriteInProgress,
            useEpisodeThumbnails = false
        )
        return
    }

    // Freeze the logo URL for row cards - enrichment updates must not cause flickering.
    // The first non-blank value wins and is never replaced.
    // Primary source of truth is the data-layer frozen value (survives navigation);
    // the remember-state acts as a secondary guard within the same composition.
    val dataFrozenLogo = item.heroPreview.frozenLogoUrl
    val frozenLogoUrl = remember(item.key) { mutableStateOf(dataFrozenLogo ?: item.heroPreview.logo) }
    if (frozenLogoUrl.value.isNullOrBlank() && !item.heroPreview.logo.isNullOrBlank()) {
        frozenLogoUrl.value = item.heroPreview.logo
    }
    if (!enrichedLogoUrl.isNullOrBlank() && frozenLogoUrl.value != enrichedLogoUrl) {
        // Outside landscape we always pick up the enriched URL so manual artwork
        // updates land instantly. Inside landscape we still adopt the enriched
        // URL when there was no logo to begin with — otherwise the card would
        // permanently fall back to the title text whenever the addon manifest
        // ships items without a logo even
        // though TMDB has one. Once we have any non-blank value we keep it
        // frozen to avoid mid-scroll flicker on enrichment refresh.
        if (!useLandscapeOverlayTreatment || frozenLogoUrl.value.isNullOrBlank()) {
            frozenLogoUrl.value = enrichedLogoUrl
        }
    }
    val effectiveLogoUrl = frozenLogoUrl.value
    val explicitItemBackdrop = item.metaPreview?.background?.takeIf { it.isNotBlank() }
        ?: item.metaPreview?.landscapePoster?.takeIf { it.isNotBlank() }
        ?: item.heroPreview.backdrop?.takeIf { backdrop ->
            backdrop != item.heroPreview.poster && backdrop != item.imageUrl
        }

    // Freeze the backdrop URL for landscape cards - prevents image reload when enrichment updates backdrop.
    val dataFrozenBackdrop = item.heroPreview.frozenBackdropUrl?.takeIf { frozen ->
        frozen != item.heroPreview.poster && frozen != item.imageUrl
    }
    val manualBackdropOverride = item.metaPreview?.homeArtworkOverride
        ?.takeIf { it.isNotBlank() }
    val frozenBackdropUrl = remember(item.key, dataFrozenBackdrop, explicitItemBackdrop) {
        mutableStateOf(dataFrozenBackdrop ?: explicitItemBackdrop)
    }
    // A manual poster/backdrop selection is authoritative. The frozen value
    // only protects against asynchronous enrichment; it must never hide a
    // deliberate user choice when the card is recreated after player exit.
    if (manualBackdropOverride != null && frozenBackdropUrl.value != manualBackdropOverride) {
        frozenBackdropUrl.value = manualBackdropOverride
    }
    if (frozenBackdropUrl.value.isNullOrBlank() && !explicitItemBackdrop.isNullOrBlank()) {
        frozenBackdropUrl.value = explicitItemBackdrop
    }
    if (!useLandscapeOverlayTreatment && !enrichedBackdropUrl.isNullOrBlank() && frozenBackdropUrl.value != enrichedBackdropUrl) {
        frozenBackdropUrl.value = enrichedBackdropUrl
    }
    // Resolve the manual choice directly for this composition as well, so the
    // first frame after returning from the player cannot paint the old image.
    val effectiveBackdropUrl = manualBackdropOverride ?: frozenBackdropUrl.value
    val effectivePosterUrl = item.metaPreview?.poster?.takeIf { it.isNotBlank() }
        ?: enrichedMeta?.poster
        ?: item.heroPreview.poster
        ?: item.imageUrl
    val baseImageUrl = if (useVideoCardTreatment && isFocused && !isCollectionFolder) {
        effectiveBackdropUrl ?: enrichedBackdropUrl ?: item.heroPreview.backdrop ?: effectivePosterUrl
    } else if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        if (useLandscapeOverlayTreatment) {
            effectiveBackdropUrl ?: item.heroPreview.backdrop ?: effectivePosterUrl
        } else {
            effectiveBackdropUrl ?: enrichedBackdropUrl ?: item.heroPreview.backdrop ?: effectivePosterUrl
        }
    } else if (useLandscapeOverlayTreatment && !isCollectionFolder) {
        effectiveBackdropUrl ?: effectivePosterUrl
    } else if (isCollectionFolder && !payload?.coverEmoji.isNullOrBlank()) {
        // Emoji cover folders: never fall back to backdrop for the card poster
        item.imageUrl
    } else {
        effectivePosterUrl ?: item.heroPreview.backdrop
    }
    val imageUrl = when {
        payload == null -> baseImageUrl
        !payload.focusGifEnabled -> baseImageUrl
        else -> baseImageUrl
    }
    // GIF overlay: shown on top of the base image only when focused and loaded
    val focusGifUrl = when {
        payload == null -> null
        !payload.focusGifEnabled -> null
        isFocused -> payload.focusGifUrl
        else -> null
    }
    val imageContentScale = when {
        item.payload is ModernPayload.Catalog && item.metaPreview?.posterShape == PosterShape.POSTER ->
            ContentScale.Fit
        is ModernPayload.CollectionFolder -> ContentScale.FillBounds
        is ModernPayload.ContinueWatching -> ContentScale.Fit
        else -> ContentScale.Crop
    }
    // Keep decode target stable across expand/collapse to avoid recreating image requests/painters
    // purely due to animated width changes.
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled || useVideoCardTreatment) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { maxRequestCardWidth.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }.coerceAtLeast(1)
    }

    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        imageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .memoryCacheKey("${it}_${requestWidthPx}x${requestHeightPx}")
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }.coerceAtLeast(1)
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }.coerceAtLeast(1)
    }

    val logoModel = remember(context, effectiveLogoUrl, maxLogoWidthPx, logoHeightPx) {
        effectiveLogoUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .memoryCacheKey("${it}_${maxLogoWidthPx}x${logoHeightPx}")
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()

    // Use the image model directly — Coil's memory cache handles repeated
    // requests efficiently without needing scroll-aware request swapping.
    val hasImage = !imageUrl.isNullOrBlank()
    val hasLandscapeLogo =
        !useVideoCardTreatment &&
        (useLandscapeOverlayTreatment || isBackdropExpanded) &&
            !isCollectionFolder &&
            !effectiveLogoUrl.isNullOrBlank() &&
            !landscapeLogoLoadFailed
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val backgroundCardColor = if (useVideoCardTreatment) Color.Transparent else NuvioColors.BackgroundCard
    val focusRingColor = NuvioColors.FocusRing
    val titleMedium = MaterialTheme.typography.titleMedium
    val backgroundPainter = remember(backgroundCardColor) { ColorPainter(backgroundCardColor) }
    val focusedBorder = remember(cardShape, focusRingColor) {
        Border(
            border = BorderStroke(2.dp, focusRingColor),
            shape = cardShape
        )
    }
    // While the user is dragging the list via held DPAD (see LazyColumn-level fast
    // scroll takeover in ModernHomeContent), hide focus chrome entirely — the list is
    // sliding like a touch swipe and showing a border / glow jittering across every
    // card the drag passes over would break that illusion. The chrome reappears the
    // moment the user releases the key, when requestFocus lands focus on whichever
    // card is visible at the leading edge.
    val isFastScrollingState = LocalFastScrollActive.current
    val isFastScrolling = isFastScrollingState.value
    val transparentFocusBorder = remember(cardShape) {
        Border(
            border = BorderStroke(0.dp, Color.Transparent),
            shape = cardShape
        )
    }
    val effectiveFocusedBorder = if (isFastScrolling) transparentFocusBorder else focusedBorder
    val noFocusGlow = remember { CardDefaults.glow(focusedGlow = Glow.None) }
    val cardGlow = when (payload) {
        is ModernPayload.CollectionFolder -> rememberArtworkBackedCardGlow(
            imageUrl = imageUrl,
            fallbackSeed = "${item.title}:${payload.collectionTitle}",
            enabled = payload.focusGlowEnabled
        )
        else -> noFocusGlow
    }
    val effectiveCardGlow = if (isFastScrolling) noFocusGlow else cardGlow
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }
    val videoTitleStripHeight = 38.dp
    val effectiveCardHeight = if (useVideoCardTreatment) {
        cardHeight + videoTitleStripHeight
    } else {
        cardHeight
    }

    Column(
        modifier = modifier
            .width(animatedCardWidth)
            .recompositionHighlighter(),
        verticalArrangement = if (useVideoCardTreatment) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(8.dp)
    ) {
        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(effectiveCardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusStateChanged(it.isFocused)
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            latestOnLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (longPressKeyTracker.handle(native, ::isSelectKey) {
                            longPressTriggered = true
                            latestOnLongPress()
                        }
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) {
                            longPressTriggered = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = CardDefaults.shape(cardShape),
            colors = CardDefaults.colors(
                containerColor = backgroundCardColor,
                focusedContainerColor = backgroundCardColor
            ),
            border = CardDefaults.border(focusedBorder = effectiveFocusedBorder),
            scale = CardDefaults.scale(focusedScale = 1f),
            glow = effectiveCardGlow
        ) {
            if (useVideoCardTreatment) {
                val imageClipShape = remember(cardCornerRadius) {
                    RoundedCornerShape(topStart = cardCornerRadius, topEnd = cardCornerRadius)
                }
                val titleClipShape = remember(cardCornerRadius) {
                    RoundedCornerShape(bottomStart = cardCornerRadius, bottomEnd = cardCornerRadius)
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cardHeight)
                            .clip(imageClipShape)
                    ) {
                        val isPlaceholderItem = item.imageUrl?.startsWith("placeholder://") == true
                        if (isPlaceholderItem) {
                            val effectivePlaceholderShimmerOffsetState =
                                placeholderShimmerOffsetState ?: rememberPlaceholderShimmerOffsetState(
                                    label = "placeholderShimmer"
                                )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .placeholderCardShimmer(effectivePlaceholderShimmerOffsetState)
                            )
                        } else if (hasImage) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                placeholder = backgroundPainter,
                                error = backgroundPainter,
                                fallback = backgroundPainter,
                                contentScale = imageContentScale
                            )
                        } else {
                            MonochromePosterPlaceholder()
                        }

                        if (isWatched && !isFavoriteInProgress) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(
                                        end = 8.dp,
                                        top = if (isIptvFavoriteRow) 32.dp else 8.dp
                                    )
                                    .zIndex(2f)
                                    .size(21.dp)
                                    .shadow(10.dp, shape = CircleShape, spotColor = Color.Transparent)
                                    .background(NuvioColors.Secondary, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    tint = if (NuvioColors.Secondary == ThemeColors.White.secondary) Color.Black else Color.White,
                                    contentDescription = stringResource(R.string.episodes_cd_watched),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (isFavoriteInProgress) {
                            FavoriteInProgressBadge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                .zIndex(3f)
                            )
                        }
                        if (isIptvFavoriteRow) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                tint = Color(0xFFFFD700),
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 8.dp, top = 8.dp)
                                    .zIndex(3f)
                                    .size(21.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(videoTitleStripHeight)
                            .clip(titleClipShape)
                            .background(NuvioColors.Secondary.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.then(
                                if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                            )
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                val mediaLayerModifier = remember(hasLandscapeLogo) {
                    if (hasLandscapeLogo) {
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = MODERN_LANDSCAPE_LOGO_GRADIENT, size = size)
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }
                }

                Box(modifier = mediaLayerModifier) {
                    val isPlaceholderItem = item.imageUrl?.startsWith("placeholder://") == true
                    if (isPlaceholderItem) {
                        // Horizontal sweeping shimmer for placeholder cards
                        val effectivePlaceholderShimmerOffsetState =
                            placeholderShimmerOffsetState ?: rememberPlaceholderShimmerOffsetState(
                                label = "placeholderShimmer"
                            )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .placeholderCardShimmer(effectivePlaceholderShimmerOffsetState)
                        )
                    } else if (hasImage) {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = backgroundPainter,
                            error = backgroundPainter,
                            fallback = backgroundPainter,
                            contentScale = imageContentScale
                        )
                    } else if (isCollectionFolder && !payload?.coverEmoji.isNullOrBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = payload!!.coverEmoji!!,
                                fontSize = 48.sp
                            )
                        }
                    } else {
                        MonochromePosterPlaceholder()
                    }

                    // GIF overlay: renders on top of image or emoji, visible only once loaded
                    if (!focusGifUrl.isNullOrBlank()) {
                        val gifModel = remember(context, focusGifUrl, requestWidthPx, requestHeightPx) {
                            ImageRequest.Builder(context)
                                .data(focusGifUrl)
                                .memoryCacheKey("${focusGifUrl}_${requestWidthPx}x${requestHeightPx}")
                                .size(width = requestWidthPx, height = requestHeightPx)
                                .build()
                        }
                        var gifLoaded by remember(focusGifUrl) { mutableStateOf(false) }
                        val gifAlpha by animateFloatAsState(
                            targetValue = if (gifLoaded) 1f else 0f,
                            animationSpec = tween(durationMillis = 200),
                            label = "gifFadeIn"
                        )
                        AsyncImage(
                            model = gifModel,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = gifAlpha },
                            contentScale = imageContentScale,
                            onSuccess = { gifLoaded = true }
                        )
                    }

                    if (shouldPlayTrailerInCard) {
                        key(trailerPreviewUrl) {
                            TrailerPlayer(
                                trailerUrl = trailerPreviewUrl,
                                trailerAudioUrl = trailerPreviewAudioUrl,
                                isPlaying = true,
                                onEnded = onTrailerEnded,
                                muted = focusedPosterBackdropTrailerMuted,
                                cropToFill = true,
                                overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (hasLandscapeLogo) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        onError = { landscapeLogoLoadFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (!useVideoCardTreatment && (useLandscapeOverlayTreatment || isBackdropExpanded)) {
                    Text(
                        text = item.title,
                        style = titleStyle,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                    )
                }

                if (isWatched && !isFavoriteInProgress) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 8.dp, top = 8.dp)
                            .zIndex(2f)
                            .size(21.dp)
                            .shadow(10.dp, shape = CircleShape, spotColor = Color.Transparent)
                            .background(NuvioColors.Secondary, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            tint = if (NuvioColors.Secondary == ThemeColors.White.secondary) Color.Black else Color.White,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (isFavoriteInProgress) {
                    FavoriteInProgressBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .zIndex(3f)
                    )
                }
                if (isIptvFavoriteRow) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        tint = Color(0xFFFFD700),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                end = 8.dp,
                                top = if (isIptvFavoriteRow) 32.dp else 8.dp
                            )
                            .zIndex(3f)
                            .size(21.dp)
                    )
                }
            }
            }
        }

        if (!useVideoCardTreatment && showLabels && !isBackdropExpanded && item.title.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (isFocused || isIptvFavoriteRow) {
                        Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    } else Modifier
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Back -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
