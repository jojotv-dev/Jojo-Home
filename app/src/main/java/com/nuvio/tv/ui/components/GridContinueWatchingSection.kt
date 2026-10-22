package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.jojotv.home.R
import com.nuvio.tv.data.freebox.freeboxFileNameOnly
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.freeboxIdOrNull
import com.nuvio.tv.ui.screens.home.isIptvItem
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GridContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onRemoveIptvFavorite: ((ContinueWatchingItem) -> Unit)? = null,
    onDeleteFromFreebox: ((ContinueWatchingItem) -> Unit)? = null,
    onRenameFreebox: ((ContinueWatchingItem, String) -> Unit)? = null,
    onMarkAsUnwatched: ((ContinueWatchingItem) -> Unit)? = null,
    thumbnailSize: com.nuvio.tv.domain.model.ThumbnailSize = com.nuvio.tv.domain.model.ThumbnailSize.DEFAULT,
    continueWatchingPortraitMode: Boolean = false,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    showManualPlayOption: Boolean = false,
    onPlayManually: (ContinueWatchingItem) -> Unit = {},
    onSearchPoster: ((ContinueWatchingItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    fullWidth: Dp = Dp.Unspecified,
    focusedItemIndex: Int = -1,
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = true,
    downFocusRequester: FocusRequester? = null,
    entryFocusRequester: FocusRequester? = null,
    // Keep this row distinct from Freebox Videos in every Home layout.
    useCoverflow: Boolean = false
) {
    if (items.isEmpty()) return
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var renameItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val lastFocusedIndex = remember { mutableIntStateOf(-1) }
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }

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
            onItemFocused = { index -> lastFocusedIndex.intValue = index }
        )
    } else Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 12.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.continue_watching),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary
                )
            }
        }

        LazyRow(
            modifier = Modifier
                .then(
                    if (fullWidth != Dp.Unspecified)
                        Modifier.requiredWidth(fullWidth)
                    else
                        Modifier.fillMaxWidth()
                )
                .focusRestorer {
                    val idx = if (lastFocusedIndex.intValue >= 0 && lastFocusedIndex.intValue < focusRequesters.size)
                        lastFocusedIndex.intValue else 0
                    focusRequesters.getOrNull(idx) ?: FocusRequester.Default
                },
            contentPadding = PaddingValues(horizontal = GridLayoutConstants.RowHorizontalPadding, vertical = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, item ->
                    when (item) {
                        is ContinueWatchingItem.InProgress ->
                            "cw_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
                        is ContinueWatchingItem.NextUp ->
                            "nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
                    }
                }
            ) { index, progress ->
                val focusModifier = if (index < focusRequesters.size) {
                    Modifier.focusRequester(focusRequesters[index])
                } else {
                    Modifier
                }

                ContinueWatchingCard(
                    item = progress,
                    onClick = { onItemClick(progress) },
                    onLongPress = { optionsItem = progress },
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    modifier = focusModifier
                        .then(
                            if (downFocusRequester != null || entryFocusRequester != null) {
                                Modifier.focusProperties {
                                    if (downFocusRequester != null) down = downFocusRequester
                                }
                            } else Modifier
                        )
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && lastFocusedIndex.intValue != index) {
                                lastFocusedIndex.intValue = index
                            }
                        }
                        .then(
                            if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester)
                            else Modifier
                        ),
                    cardWidth = if (continueWatchingPortraitMode) (126f * (thumbnailSize.cardWidth.value / 220f)).dp else thumbnailSize.cardWidth,
                    imageHeight = if (continueWatchingPortraitMode) (189f * (thumbnailSize.cardWidth.value / 220f)).dp else thumbnailSize.imageHeight
                )
            }
        }
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        val isFreeboxItem = menuItem.freeboxIdOrNull() != null
        val isIptvItem = menuItem.isIptvItem()
        val removesFavorite = isIptvItem && onRemoveIptvFavorite != null

        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemoveFromList = if (removesFavorite) {
                {
                    val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex.intValue, items.size - 2)
                    pendingFocusIndex = targetIndex
                    onRemoveItem(menuItem)
                    optionsItem = null
                }
            } else null,
            onDeleteFromFreebox = if (isFreeboxItem) onDeleteFromFreebox?.let { cb ->
                {
                    val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex.intValue, items.size - 2)
                    pendingFocusIndex = targetIndex
                    cb(menuItem)
                    optionsItem = null
                }
            } else null,
            onRenameFreebox = if (onRenameFreebox != null && menuItem is ContinueWatchingItem.InProgress && isFreeboxItem) {
                {
                    renameItem = menuItem
                    optionsItem = null
                }
            } else null,
            onMarkAsUnwatched = onMarkAsUnwatched?.let { cb ->
                if (menuItem is ContinueWatchingItem.InProgress) {
                    {
                        val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex.intValue, items.size - 2)
                        pendingFocusIndex = targetIndex
                        cb(menuItem)
                        optionsItem = null
                    }
                } else null
            },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex.intValue, items.size - 2)
                pendingFocusIndex = targetIndex
                if (removesFavorite) {
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
