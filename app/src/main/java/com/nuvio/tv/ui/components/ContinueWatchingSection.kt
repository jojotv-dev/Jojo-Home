package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.jojotv.home.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.displayTitleForContinueWatching
import com.nuvio.tv.ui.screens.home.freeboxIdOrNull
import com.nuvio.tv.ui.screens.home.isIptvItem
import com.nuvio.tv.data.freebox.formatFreeboxDurationCompact
import com.nuvio.tv.data.freebox.freeboxFileNameOnly
import com.nuvio.tv.data.freebox.freeboxDisplayName
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.freebox.freeboxVideoDisplayTitle
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.screens.player.PlayerLaunchArtworkState
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.request.crossfade
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.util.computeAirDateBadgeText
import androidx.compose.foundation.basicMarquee

private val CwCardShape = RoundedCornerShape(12.dp)
private val CwClipShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
private val CwNewEpisodeBadgeColor = Color(0xFF1D4ED8)
private val CwNewSeasonBadgeColor = Color(0xFFB45309)

/** URLs that failed to load  â€” â€™ â€” â€š â€” Â¢ â€” Â¢ â€” Â¢ â€” â€šÂ¬ â€” Â¡ â€” â€š â€” Â¬ â€” Â¢ â€” Â¢ â€” â‚¬ â€” Â¬ â€” â€š â€” Â skip them immediately on next recomposition. */
internal val brokenImageUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onRemoveIptvFavorite: ((ContinueWatchingItem) -> Unit)? = null,
    onDeleteFromFreebox: ((ContinueWatchingItem) -> Unit)? = null,
    onRenameFreebox: ((ContinueWatchingItem, String) -> Unit)? = null,
    onMarkAsUnwatched: ((ContinueWatchingItem) -> Unit)? = null,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    showManualPlayOption: Boolean = false,
    onPlayManually: (ContinueWatchingItem) -> Unit = {},
    onSearchPoster: ((ContinueWatchingItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    focusedItemIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = true,
    downFocusRequester: FocusRequester? = null,
    entryFocusRequester: FocusRequester? = null,
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    // The resume list remains its own conventional home row.  Coverflow is
    // reserved for explicit callers (for example a dedicated Favorites view).
    useCoverflow: Boolean = false
) {
    if (items.isEmpty()) return

    val focusKeys = remember(items) {
        items.map { item ->
            when (item) {
                is ContinueWatchingItem.InProgress ->
                    "cw_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
                is ContinueWatchingItem.NextUp ->
                    "nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
            }
        }
    }
    val focusRequesters = remember(focusKeys) { List(items.size) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var renameItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }

    val listState = rememberLazyListState()

    // Restore focus to specific item if requested
    LaunchedEffect(focusedItemIndex) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            if (lastRequestedFocusIndex == focusedItemIndex) return@LaunchedEffect
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[focusedItemIndex].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = focusedItemIndex
            }
        } else {
            lastRequestedFocusIndex = -1
        }
    }

    if (useCoverflow) {
        HomeCoverflowSection(
            items = items.map { item -> item.toHomeCoverflowItem(
                onClick = { onItemClick(item) },
                onLongPress = { optionsItem = item }
            ) },
            modifier = modifier,
            entryFocusRequester = entryFocusRequester,
            downFocusRequester = downFocusRequester,
            initialFocusedIndex = focusedItemIndex,
            focusRequesters = focusRequesters,
            onItemFocused = { index ->
                if (lastFocusedIndex != index) {
                    lastFocusedIndex = index
                    onItemFocused(index)
                }
            }
        )
    } else Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.continue_watching),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
        }

        val restoreFocusRequester = remember(lastFocusedIndex, focusRequesters.size) {
            val idx = if (lastFocusedIndex >= 0 && lastFocusedIndex < focusRequesters.size)
                lastFocusedIndex else 0
            focusRequesters.getOrNull(idx) ?: FocusRequester.Default
        }

        val density = LocalDensity.current
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        val layoutDirection = LocalLayoutDirection.current
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, isRtl) {
            val startPx = with(density) { 48.dp.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    val childSize = kotlin.math.abs(size)
                    if (isRtl) {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = containerSize - startPx.toFloat()
                        val targetForTrailingEdge =
                            if (childSmallerThanParent && initialTarget < childSize) {
                                childSize
                            } else {
                                initialTarget
                            }
                        return (offset + size) - targetForTrailingEdge
                    } else {
                        val target = startPx.toFloat()
                        val space = containerSize - target
                        val leading = if (childSize <= containerSize && space < childSize) containerSize - childSize else target
                        return offset - leading
                    }
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(restoreFocusRequester)
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            state = listState
        ) {
            itemsIndexed(
                items = items,
                key = { _, progress ->
                    when (progress) {
                        is ContinueWatchingItem.InProgress ->
                            "cw_${progress.progress.contentId}_${progress.progress.videoId}_${progress.progress.season ?: -1}_${progress.progress.episode ?: -1}"
                        is ContinueWatchingItem.NextUp ->
                            "nextup_${progress.info.contentId}_${progress.info.videoId}_${progress.info.season}_${progress.info.episode}"
                    }
                }
            ) { index, progress ->
                val focusModifier = when {
                    index < focusRequesters.size -> Modifier.focusRequester(focusRequesters[index])
                    else -> Modifier
                }

                    ContinueWatchingCard(
                    item = progress,
                    onClick = { onItemClick(progress) },
                    onLongPress = { optionsItem = progress },
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    cardWidth = cardWidth,
                    imageHeight = imageHeight,
                    modifier = Modifier
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && lastFocusedIndex != index) {
                                lastFocusedIndex = index
                                onItemFocused(index)
                            }
                        }
                        .then(
                            if (downFocusRequester != null || entryFocusRequester != null) {
                                Modifier.focusProperties {
                                    if (downFocusRequester != null) down = downFocusRequester
                                }
                            } else Modifier
                        )
                        .then(focusModifier)
                        .then(
                            if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester)
                            else Modifier
                        )
                )
            }
        }
        } // CompositionLocalProvider
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        val isFreeboxItem = menuItem.freeboxIdOrNull() != null
        val isIptvItem = menuItem.isIptvItem()
        // Some addons expose movies as "other". The action must therefore be
        // driven by the card's capabilities, not by an unreliable media type.
        val removesFavorite = isIptvItem && onRemoveIptvFavorite != null

        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemoveFromList = if (removesFavorite) {
                {
                    val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                    pendingFocusIndex = targetIndex
                    onRemoveItem(menuItem)
                    optionsItem = null
                }
            } else null,
            onDeleteFromFreebox = if (isFreeboxItem) onDeleteFromFreebox?.let { cb ->
                {
                    val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                    pendingFocusIndex = targetIndex
                    cb(menuItem)
                    optionsItem = null
                }
            } else null,
            onRenameFreebox = if (onRenameFreebox != null && menuItem is ContinueWatchingItem.InProgress && menuItem.progress.contentId.startsWith("freebox:")) {
                {
                    renameItem = menuItem
                    optionsItem = null
                }
            } else null,
            onMarkAsUnwatched = onMarkAsUnwatched?.let { cb ->
                if (menuItem is ContinueWatchingItem.InProgress) {
                    {
                        val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                        pendingFocusIndex = targetIndex
                        cb(menuItem)
                        optionsItem = null
                    }
                } else null
            },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                pendingFocusIndex = targetIndex
                if (removesFavorite) {
                    // The ViewModel removes the progress only after the favorite
                    // repository confirms success. This avoids hiding a failed
                    // deletion which would reappear on the next NAS refresh.
                    onRemoveIptvFavorite.invoke(menuItem)
                } else {
                    onRemoveItem(menuItem)
                }
                optionsItem = null
            },
            removeIptvFavorite = removesFavorite,
            onDetails = {
                onDetailsClick(menuItem)
                optionsItem = null
            },
            showDetails = true,
            onStartFromBeginning = {
                onStartFromBeginning(menuItem)
                optionsItem = null
            },
            showPlayManually = showManualPlayOption && !isIptvItem && !isFreeboxItem,
            onPlayManually = {
                onPlayManually(menuItem)
                optionsItem = null
            },
            onSearchPoster = if (isFreeboxItem || isIptvItem) {
                onSearchPoster?.let { cb -> { cb(menuItem); optionsItem = null } }
            } else {
                null
            }
        )
    }

    val selectedRenameItem = renameItem
    if (selectedRenameItem is ContinueWatchingItem.InProgress && onRenameFreebox != null) {
        FreeboxRenameDialog(
            initialName = freeboxFileNameOnly(selectedRenameItem.progress.name.ifBlank { selectedRenameItem.progress.videoId }),
            onDismiss = { renameItem = null },
            onConfirm = { newName ->
                onRenameFreebox(selectedRenameItem, newName)
                renameItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0 && target < focusRequesters.size) {
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[target].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = target
            }
            pendingFocusIndex = null
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = true,
    showBadge: Boolean = true,
    badgeTextOverride: String? = null,
    showFavoriteMarker: Boolean = false,
    showFolderMarker: Boolean = false,
    cardSizeMultiplier: Float = 1.5f,
    onDirectionLeft: (() -> Boolean)? = null,
    containArtworkOnLandscape: Boolean = false,
    titleBackgroundColor: Color? = null
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongPress by rememberUpdatedState(onLongPress)

    val progress = remember(item) { (item as? ContinueWatchingItem.InProgress)?.progress }
    val nextUp = remember(item) { (item as? ContinueWatchingItem.NextUp)?.info }
    val cardContext = LocalContext.current
    val episodeStr = remember(progress, nextUp, cardContext) {
        val season = progress?.season ?: nextUp?.season
        val episode = progress?.episode ?: nextUp?.episode
        if (season != null && episode != null) {
            cardContext.getString(R.string.season_episode_format, season, episode)
        } else {
            null
        }
    }
    val strUpcoming = stringResource(R.string.cw_upcoming)
    val strNextUp = stringResource(R.string.cw_next_up)
    val strNewEpisode = stringResource(R.string.cw_new_episode)
    val strNewSeason = stringResource(R.string.cw_new_season)
    val nextUpBadgeText = nextUp?.let { info ->
        if (info.isReleaseAlert) {
            if (info.isNewSeasonRelease) strNewSeason else strNewEpisode
        } else if (!info.hasAired) {
            computeAirDateBadgeText(cardContext, info.released, info.airDateLabel) ?: strUpcoming
        } else {
            strNextUp
        }
    }
    val isFreeboxProgress = progress?.isFreeboxProgressForDisplay() == true
    val isIptvProgress = progress?.isIptvProgressForDisplay() == true
    val badgeText = remember(progress != null, nextUpBadgeText, strNextUp) {
        if (progress != null) FAVORITE_IN_PROGRESS_BADGE_TEXT
        else nextUpBadgeText ?: strNextUp
    }
    val preferLandscapeArtwork = cardWidth > imageHeight
    // Live IPTV entries represent channels, not programmes. Their identity
    // artwork is the official channel logo supplied by the provider.
    val isIptvChannelProgress = isIptvProgress &&
        (progress?.contentType?.equals("iptv", ignoreCase = true) == true ||
            progress?.contentType?.contains("live", ignoreCase = true) == true ||
            progress?.contentId?.startsWith("iptv_live", ignoreCase = true) == true)
    val isIptvVodProgress = isIptvProgress && !isIptvChannelProgress
    val preferPosterArtwork = isIptvChannelProgress ||
        ((isFreeboxProgress) && !preferLandscapeArtwork)
    val imageModel = remember(nextUp, progress, item, useEpisodeThumbnails, preferLandscapeArtwork) {
        fun firstNonBroken(vararg candidates: String?): String? {
            return candidates.firstOrNull { !it.isNullOrBlank() && it !in brokenImageUrls }?.trim()
        }
        when {
            nextUp != null && !nextUp.hasAired -> firstNonBroken(
                nextUp.backdrop,
                nextUp.poster,
                nextUp.thumbnail
            )
            nextUp != null && useEpisodeThumbnails -> firstNonBroken(
                nextUp.thumbnail,
                nextUp.backdrop,
                nextUp.poster
            )
            nextUp != null -> firstNonBroken(
                nextUp.backdrop,
                nextUp.poster,
                nextUp.thumbnail
            )
            isIptvChannelProgress -> firstNonBroken(
                // Never show the current programme frame for a live channel.
                progress?.logo,
                progress?.poster,
                progress?.backdrop
            )
            useEpisodeThumbnails && isIptvVodProgress -> firstNonBroken(
                // IPTV films and series must use the provider/TMDB artwork.
                // An episode thumbnail is a video frame and is never used for
                // these VOD cards, even as a fallback.
                progress?.backdrop,
                progress?.poster,
                progress?.logo
            )
            useEpisodeThumbnails -> firstNonBroken(
                (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail,
                if (preferPosterArtwork) progress?.poster else progress?.backdrop,
                if (preferPosterArtwork) progress?.backdrop else progress?.poster,
                progress?.logo
            )
            else -> firstNonBroken(
                if (preferPosterArtwork) progress?.poster else progress?.backdrop,
                if (preferPosterArtwork) progress?.backdrop else progress?.poster,
                (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail,
                progress?.logo
            )
        }
    }
    val fallbackImageModel = remember(nextUp, progress, item, preferLandscapeArtwork) {
        when {
            nextUp != null -> firstNonBlank(
                nextUp.backdrop,
                nextUp.poster
            )
            else -> firstNonBlank(
                if (preferPosterArtwork) progress?.poster else progress?.backdrop,
                if (preferPosterArtwork) progress?.backdrop else progress?.poster,
                progress?.logo
            )
        }
    }
    var usesFallbackImage by remember { mutableStateOf(false) }
    // Reset fallback state when the item changes
    LaunchedEffect(imageModel) { usesFallbackImage = false }

    val effectiveImageModel = if (usesFallbackImage) fallbackImageModel else imageModel
    val containArtwork = containArtworkOnLandscape &&
        preferLandscapeArtwork &&
        !effectiveImageModel.isNullOrBlank()
    val titleText = remember(progress, nextUp) {
        when {
            item is ContinueWatchingItem.InProgress && progress?.isIptvProgressForDisplay() == true ->
                item.displayTitleForContinueWatching()
            progress != null && progress.isFreeboxProgressForDisplay() ->
                progress.freeboxContinueWatchingDisplayTitle()
            else ->
                progress?.name ?: nextUp?.name.orEmpty()
        }
    }
    val context = LocalContext.current
    val strAirsDateForEpisode = computeAirDateBadgeText(context, nextUp?.released, nextUp?.airDateLabel)
    val episodeTitle = remember(progress, nextUp, context, strAirsDateForEpisode) {
        when {
            progress != null -> progress.episodeTitle?.localizeEpisodeTitle(context)
            nextUp != null && !nextUp.hasAired -> nextUp.episodeTitle?.localizeEpisodeTitle(context) ?: strAirsDateForEpisode
            else -> nextUp?.episodeTitle?.localizeEpisodeTitle(context)
        }
    }
    val effectiveCardWidth = remember(cardWidth, cardSizeMultiplier) {
        (cardWidth.value * cardSizeMultiplier).roundToInt().dp
    }
    val effectiveImageHeight = remember(imageHeight, cardSizeMultiplier) {
        (imageHeight.value * cardSizeMultiplier).roundToInt().dp
    }
    val density = LocalDensity.current
    val requestWidthPx = remember(effectiveCardWidth, density) {
        with(density) { effectiveCardWidth.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(effectiveImageHeight, density) {
        with(density) { effectiveImageHeight.roundToPx() }.coerceAtLeast(1)
    }
    val shouldBlur = blurUnwatchedEpisodes && useEpisodeThumbnails && nextUp != null
    val imageRequest = remember(effectiveImageModel, requestWidthPx, requestHeightPx, shouldBlur) {
        ImageRequest.Builder(context)
            .data(effectiveImageModel)
            // The CW snapshot is restored before remote metadata.  Keep its
            // bitmap address stable across process launches so Coil can serve
            // the persisted disk entry immediately instead of refetching it.
            .crossfade(false)
            .memoryCacheKey("${effectiveImageModel}_${requestWidthPx}x${requestHeightPx}_blur${shouldBlur}")
            .diskCacheKey(effectiveImageModel)
            .size(width = requestWidthPx, height = requestHeightPx)
            .apply {
                if (shouldBlur) transformations(BlurTransformation())
            }
            .build()
    }

    // NuvioColors reads the active Compose theme, so resolve its values in the
    // composable scope before entering remember's non-composable calculation.
    val themeSecondary = NuvioColors.Secondary
    val themeBackground = NuvioColors.Background
    val badgeBackground = remember(nextUp, badgeTextOverride, themeSecondary, themeBackground) {
        when {
            nextUp?.isNewSeasonRelease == true -> CwNewSeasonBadgeColor
            nextUp?.isReleaseAlert == true -> CwNewEpisodeBadgeColor
            badgeTextOverride == FAVORITE_IN_PROGRESS_BADGE_TEXT -> themeSecondary.copy(alpha = 0.92f)
            else -> themeBackground.copy(alpha = 0.8f)
        }
    }

    val bgCardColor = NuvioColors.BackgroundCard
    val backgroundPainter = remember(bgCardColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgCardColor) }

    Card(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                PlayerLaunchArtworkState.rememberDisplayedArtwork(
                    contentId = progress?.contentId ?: nextUp?.contentId,
                    title = titleText,
                    artworkUrl = effectiveImageModel
                )
                // Keep the click synchronous.  Deferring it through a
                // coroutine allowed the TV Card to race the long-press
                // key-up event and swallow the options action.
                latestOnClick()
            }
        },
        modifier = modifier
            .width(effectiveCardWidth)
            .recompositionHighlighter()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT && onDirectionLeft?.invoke() == true) {
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        latestOnLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                // Use the exact same event lifecycle as the working
                // Films/Séries cards: the TV Card owns the short click, while
                // the tracker only consumes an actual long press.
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
        shape = CardDefaults.shape(shape = CwCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CwCardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Column {
            // Thumbnail with progress overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(effectiveImageHeight)
                    .clip(CwClipShape)
            ) {
                // Background image with size hints for efficient decoding
                if (effectiveImageModel.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else if (containArtwork) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                alpha = 0.38f
                            }
                            .clip(CwClipShape),
                        placeholder = backgroundPainter,
                        error = backgroundPainter,
                        fallback = backgroundPainter,
                        // Continue Watching thumbnails must cover the complete frame.
                        // Fit leaves empty side/top bands when the provider image ratio differs.
                        contentScale = ContentScale.Crop,
                        onError = {
                            if (!usesFallbackImage && effectiveImageModel != null) {
                                brokenImageUrls.add(effectiveImageModel)
                                if (fallbackImageModel != null && fallbackImageModel != effectiveImageModel) {
                                    usesFallbackImage = true
                                }
                            }
                        }
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.36f))
                    )
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = titleText,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .clip(CwClipShape),
                        placeholder = backgroundPainter,
                        error = backgroundPainter,
                        fallback = backgroundPainter,
                        contentScale = ContentScale.Crop,
                        onError = {
                            if (!usesFallbackImage && effectiveImageModel != null) {
                                brokenImageUrls.add(effectiveImageModel)
                                if (fallbackImageModel != null && fallbackImageModel != effectiveImageModel) {
                                    usesFallbackImage = true
                                }
                            }
                        }
                    )
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = titleText,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy =
                                    CompositingStrategy.Offscreen
                            }
                            .clip(CwClipShape),
                        placeholder = backgroundPainter,
                        error = backgroundPainter,
                        fallback = backgroundPainter,
                        contentScale = ContentScale.Crop,
                        onError = {
                            // Primary image failed (e.g. broken thumbnail URL)  â€” â€™ â€” â€š â€” Â¢ â€” Â¢ â€” Â¢ â€” â€šÂ¬ â€” Â¡ â€” â€š â€” Â¬ â€” Â¢ â€” Â¢ â€” â‚¬ â€” Â¬ â€” â€š â€” Â remember and try fallback.
                            if (!usesFallbackImage && effectiveImageModel != null) {
                                brokenImageUrls.add(effectiveImageModel)
                                if (fallbackImageModel != null && fallbackImageModel != effectiveImageModel) {
                                    usesFallbackImage = true
                                }
                            }
                        }
                    )
                }


                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(64.dp)
                        .drawWithCache {
                            val gradient = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f)
                                ),
                                startY = 0f,
                                endY = size.height
                            )
                            onDrawBehind { drawRect(gradient) }
                        }
                )

                if (showBadge) (badgeTextOverride ?: badgeText)?.let { text ->
                    if (text == FAVORITE_IN_PROGRESS_BADGE_TEXT) {
                        FavoriteInProgressBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .clip(BadgeShape)
                                .background(badgeBackground)
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioColors.TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.width(112.dp)
                            )
                        }
                    }
                }

                if (showFavoriteMarker) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(BadgeShape)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier
                                .width(16.dp)
                                .height(16.dp)
                        )
                    }
                }

                if (showFolderMarker) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(BadgeShape)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Subscriptions,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier
                                .width(16.dp)
                                .height(16.dp)
                        )
                    }
                }

            }

            // Title below thumbnail
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = titleBackgroundColor
                            ?: NuvioColors.Secondary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Episode info + title on a single line (for series)
                Row(
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (episodeStr != null) {
                        Text(
                            text = "$episodeStr  •  ",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Episode title if available (not for Freebox/IPTV continue watching items)
                val isSpecialProgress = isFreeboxProgress || isIptvProgress
                if (!isSpecialProgress) {
                    episodeTitle?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onRemoveFromList: (() -> Unit)? = null,
    removeIptvFavorite: Boolean = false,
    onDetails: () -> Unit,
    showDetails: Boolean = true,
    onStartFromBeginning: () -> Unit = {},
    showPlayManually: Boolean = false,
    onPlayManually: () -> Unit = {},
    onRenameFreebox: (() -> Unit)? = null,
    onMarkAsUnwatched: (() -> Unit)? = null,
    onDeleteFromFreebox: (() -> Unit)? = null,
    onSearchPoster: (() -> Unit)? = null
) {
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> if (item.progress.isFreeboxProgressForDisplay()) {
            item.progress.freeboxContinueWatchingDisplayTitle()
        } else {
            item.progress.name
        }
        is ContinueWatchingItem.NextUp -> item.info.name
    }

    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle)
    ) {
        if (showDetails) {
            Button(
                onClick = onDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.cw_action_go_to_details))
            }
        }

        if (item is ContinueWatchingItem.InProgress) {
            Button(
                onClick = onStartFromBeginning,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!showDetails) Modifier.focusRequester(primaryFocusRequester) else Modifier)
            ) {
                Text(stringResource(R.string.cw_action_start_from_beginning))
            }
        }

        if (showPlayManually) {
            Button(
                onClick = onPlayManually,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.play_manually))
            }
        }

        onMarkAsUnwatched?.let { markAction ->
            Button(
                onClick = markAction,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cw_action_mark_unwatched))
            }
        }

        onRenameFreebox?.let { renameAction ->
            Button(
                onClick = renameAction,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cw_action_rename))
            }
        }

        onSearchPoster?.let { searchAction ->
            Button(
                onClick = searchAction,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.freebox_choose_poster))
            }
        }

        // IPTV entries can be kept in Favorites after their resume point is
        // cleared.  Keep this explicit action immediately above the combined
        // deletion action so the two outcomes cannot be confused.
        onRemoveFromList?.let { removeFromListAction ->
            Button(
                onClick = removeFromListAction,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cw_action_remove))
            }
        }

        Button(
            onClick = onRemove,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (removeIptvFavorite) {
                    stringResource(R.string.cw_action_remove_with_favorites)
                } else {
                    stringResource(R.string.cw_action_remove)
                }
            )
        }

        onDeleteFromFreebox?.let { deleteAction ->
            Button(
                onClick = deleteAction,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = Color(0xFFFF6E6E)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cw_action_delete_freebox))
            }
        }
    }
}

@Composable
fun FreeboxRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    NuvioInputDialog(
        title = stringResource(R.string.cw_action_rename),
        subtitle = stringResource(R.string.freebox_rename_dialog_subtitle),
        initialValue = initialName,
        placeholder = initialName,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun formatRemainingTime(
    remainingMs: Long,
    strHoursMinLeft: String,
    strMinLeft: String,
    strAlmostDone: String
): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (totalMinutes <= 0L) return strAlmostDone
    return "reste ${hours}h${minutes.toString().padStart(2, '0')}m"
}

private fun String.compactHoursMinutes(): String =
    replace(Regex("(\\d+)h\\s+(\\d+)m"), "$1h$2m")

private fun com.nuvio.tv.domain.model.WatchProgress.freeboxContinueWatchingDisplayTitle(): String {
    val bestName = name
        .takeUnless { it.isLikelyGeneratedFreeboxLabel() }
        ?: freeboxPathFromContentId(contentId)
            .takeUnless { it.isLikelyGeneratedFreeboxLabel() }
        ?: freeboxPathFromContentId(videoId)
            .takeUnless { it.isLikelyGeneratedFreeboxLabel() }
        ?: videoId
    return freeboxVideoDisplayTitle(bestName, duration)
}

internal fun ContinueWatchingItem.toHomeCoverflowItem(
    onClick: () -> Unit,
    onLongPress: () -> Unit
): HomeCoverflowItem = when (this) {
    is ContinueWatchingItem.InProgress -> HomeCoverflowItem(
        // Keep one visual identity for the programme while progress, episode
        // metadata and artwork are enriched in the background.
        key = "cw_${progress.contentId}",
        title = displayTitleForContinueWatching().compactHomeCoverflowTitle(),
        meta = listOfNotNull(
            genres.firstOrNull()?.takeIf { it.isNotBlank() },
            releaseInfo?.takeIf { it.isNotBlank() },
            progress.duration.takeIf { it > 0L }?.let(::formatFreeboxDurationCompact)
        ).joinToString(" • "),
        cast = cast,
        director = director,
        synopsis = episodeDescription,
        artworkUrl = homeCoverflowArtworkUrl(),
        portraitArtworkUrl = homeCoverflowPortraitArtworkUrl(),
        inProgress = true,
        episodeCode = progress.seasonEpisodeCode(),
        onClick = onClick,
        onLongPress = onLongPress
    )
    is ContinueWatchingItem.NextUp -> HomeCoverflowItem(
        key = "cw_${info.contentId}",
        title = info.name.compactHomeCoverflowTitle(),
        meta = listOfNotNull(
            info.genres.firstOrNull()?.takeIf { it.isNotBlank() },
            info.releaseInfo?.takeIf { it.isNotBlank() }
        ).joinToString(" • "),
        synopsis = info.episodeDescription,
        artworkUrl = homeCoverflowArtworkUrl(),
        portraitArtworkUrl = homeCoverflowPortraitArtworkUrl(),
        episodeCode = info.seasonEpisodeCode(),
        onClick = onClick,
        onLongPress = onLongPress
    )
}

/** One artwork identity shared by the Home coverflow card and its background. */
internal fun ContinueWatchingItem.homeCoverflowArtworkUrl(): String? = when (this) {
    is ContinueWatchingItem.InProgress -> if (progress.isIptvChannelProgressForHome()) {
        firstNonBlank(progress.logo, progress.poster, progress.backdrop, episodeThumbnail)
    } else {
        firstNonBlank(progress.backdrop, episodeThumbnail, progress.poster, progress.logo)
    }
    is ContinueWatchingItem.NextUp -> if (info.isIptvChannelForHome()) {
        firstNonBlank(info.logo, info.poster, info.backdrop, info.thumbnail)
    } else {
        firstNonBlank(info.backdrop, info.thumbnail, info.poster)
    }
}

/** Official portrait poster used only by the lateral coverflow cards. */
internal fun ContinueWatchingItem.homeCoverflowPortraitArtworkUrl(): String? = when (this) {
    is ContinueWatchingItem.InProgress -> if (progress.isIptvChannelProgressForHome()) {
        firstNonBlank(progress.logo, progress.poster)
    } else {
        firstNonBlank(progress.poster, progress.logo)
    }
    is ContinueWatchingItem.NextUp -> if (info.isIptvChannelForHome()) {
        firstNonBlank(info.logo, info.poster)
    } else {
        firstNonBlank(info.poster, info.logo)
    }
}

private fun com.nuvio.tv.domain.model.WatchProgress.isIptvChannelProgressForHome(): Boolean =
    contentId.startsWith("iptv_live", ignoreCase = true) ||
        contentType.equals("iptv", ignoreCase = true) ||
        contentType.equals("iptv_live", ignoreCase = true)

private fun NextUpInfo.isIptvChannelForHome(): Boolean =
    contentId.startsWith("iptv_live", ignoreCase = true) ||
        contentType.equals("iptv", ignoreCase = true) ||
        contentType.equals("iptv_live", ignoreCase = true)

private fun com.nuvio.tv.domain.model.WatchProgress.seasonEpisodeCode(): String? =
    if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null

private fun NextUpInfo.seasonEpisodeCode(): String? =
    if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null

/** Removes duplicated duration and parenthesised year from the compact Home caption. */
private fun String.compactHomeCoverflowTitle(): String {
    val original = trim()
    val withoutLeadingDuration = original.replace(
        Regex("^\\d+\\s*h(?:\\s*\\d+\\s*m?)?\\s+", RegexOption.IGNORE_CASE),
        ""
    )
    val withoutTrailingYear = withoutLeadingDuration.replace(
        Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"),
        ""
    ).trim()
    return withoutTrailingYear.ifBlank { original }
}

private fun String.isLikelyGeneratedFreeboxLabel(): Boolean {
    val value = trim()
    if (value.isBlank()) return true
    return value.matches(Regex("\\d{10,}"))
}

private fun com.nuvio.tv.domain.model.WatchProgress.isFreeboxProgressForDisplay(): Boolean {
    return contentId.startsWith("freebox:") || videoId.startsWith("freebox:") || contentType.equals("freebox", ignoreCase = true)
}
private fun com.nuvio.tv.domain.model.WatchProgress.isIptvProgressForDisplay(): Boolean {
    return contentId.startsWith("iptv_") || videoId.startsWith("iptv_") || contentType.startsWith("iptv", ignoreCase = true)
}
