package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jojotv.home.R
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxDisplayName
import com.nuvio.tv.data.freebox.freeboxFileNameOnly
import com.nuvio.tv.data.freebox.freeboxFolderDisplayName
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.freebox.formatFreeboxDurationCompact
import com.nuvio.tv.data.freebox.freeboxVideoDisplayTitle
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.core.tmdb.FreeboxVideoMeta
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.components.freeboxposter.FreeboxPosterPickerDialog
import com.nuvio.tv.ui.components.freeboxposter.FreeboxPosterPickerViewModel

internal fun orderedFreeboxVideoEntriesForHome(
    entries: List<FreeboxFileEntry>,
    continueWatchingIds: Set<String>,
    probedDurations: Map<String, Long>
): List<FreeboxFileEntry> {
    // A resume can come from an older snapshot using the legacy content ID,
    // while the current Freebox listing uses the fingerprinted ID.  Compare
    // both IDs and their canonical paths so a file promoted to Continue
    // Watching cannot remain duplicated in the Videos row.
    val continueWatchingPaths = continueWatchingIds
        .map(::freeboxPathFromContentId)
        .filter(String::isNotBlank)
        .toSet()
    val sortedEntries = entries.sortedWith(
        compareByDescending<FreeboxFileEntry> { entry ->
            entry.isDirectory
        }.thenBy { entry ->
            entry.resolvedHomeDuration(probedDurations) ?: Long.MAX_VALUE
        }.thenBy { entry -> entry.name.lowercase() }
    )
    val filteredEntries = sortedEntries.filter { entry ->
        entry.isDirectory || (
            freeboxContentIdForEntry(entry) !in continueWatchingIds &&
                entry.path !in continueWatchingPaths
            )
    }
    // A video promoted to Continue Watching must remain absent from Videos
    // until the user explicitly removes it from that list.
    return filteredEntries
}

/** A landscape Home card must never prefer a portrait poster over an available backdrop. */
internal fun freeboxVideoRowArtwork(
    posterUrl: String?,
    backdropUrl: String?,
    metadataPosterUrl: String?,
    metadataBackdropUrl: String?,
    preferPortrait: Boolean = false
): String? = (if (preferPortrait) {
    listOf(posterUrl, metadataPosterUrl, backdropUrl, metadataBackdropUrl)
} else {
    listOf(backdropUrl, metadataBackdropUrl, posterUrl, metadataPosterUrl)
}).firstOrNull { !it.isNullOrBlank() }?.trim()

/** The Freebox frequently supplies `0` before its metadata probe completes. */
private fun FreeboxFileEntry.resolvedHomeDuration(probedDurations: Map<String, Long>): Long? =
    // Freebox can reuse stale metadata when a recording is replaced at the
    // same path. A fresh local probe is therefore authoritative whenever it
    // exists; the API value is only the fallback while probing is in flight.
    probedDurations[path]?.takeIf { it > 0L } ?: durationMs?.takeIf { it > 0L }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FreeboxVideosSection(
    entries: List<FreeboxFileEntry>,
    onItemClick: (FreeboxFileEntry) -> Unit,
    artworkMap: Map<String, String> = emptyMap(),
    backdropMap: Map<String, String> = emptyMap(),
    metadataMap: Map<String, FreeboxVideoMeta> = emptyMap(),
    probedDurations: Map<String, Long> = emptyMap(),
    continueWatchingIds: Set<String> = emptySet(),
    cardWidth: Dp = 126.dp,
    imageHeight: Dp = 189.dp,
    horizontalPadding: Dp = 48.dp,
    itemSpacing: Dp = 16.dp,
    focusBorderPadding: Dp = 0.dp,
    onShowDetails: (FreeboxFileEntry) -> Unit = {},
    onRenameFreebox: (FreeboxFileEntry, String) -> Unit = { _, _ -> },
    onDeleteFromFreebox: (FreeboxFileEntry) -> Unit = {},
    onItemFocused: (index: Int, item: MetaPreview) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    entryFocusRequester: FocusRequester? = null,
    // Modern Home owns the cross-row focus graph. Accept its exact card
    // requesters so a move from Continue Watching lands on a Videos card,
    // rather than first focusing the LazyRow container and restoring an
    // unrelated previous child.
    externalFocusRequesters: List<FocusRequester> = emptyList(),
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    // Match the Home Films/Séries cards, which use ContinueWatchingCard's
    // shared 1.5x scale. Keeping a local 1x default made Videos visibly smaller.
    cardSizeMultiplier: Float = 1.5f,
    // Videos is a separate conventional row; it must never visually merge
    // with Continue Watching.
    useCoverflow: Boolean = false,
    fullScreenCoverflowPanelHeight: Dp? = null,
    posterPickerViewModel: FreeboxPosterPickerViewModel = hiltViewModel()
) {
    val posterPickerState by posterPickerViewModel.state.collectAsStateWithLifecycle()
    val filteredEntries = remember(entries, continueWatchingIds, probedDurations) {
        orderedFreeboxVideoEntriesForHome(
            entries = entries,
            continueWatchingIds = continueWatchingIds,
            probedDurations = probedDurations
        )
    }
    if (filteredEntries.isEmpty()) return

    val filteredEntryKeys = remember(filteredEntries) { filteredEntries.map { it.path } }
    // Duration and artwork probes can reorder this row a few seconds after it
    // appears. Keep one requester per video identity: recreating the whole list
    // by index leaves the visibly focused card attached to a retired focus node.
    val localFocusRequestersByKey = remember { mutableMapOf<String, FocusRequester>() }
    val localFocusRequesters = filteredEntryKeys.map { key ->
        localFocusRequestersByKey.getOrPut(key) { FocusRequester() }
    }
    LaunchedEffect(filteredEntryKeys) {
        localFocusRequestersByKey.keys.retainAll(filteredEntryKeys.toSet())
    }
    val focusRequesters = externalFocusRequesters
        .takeIf { it.size >= filteredEntries.size }
        ?: localFocusRequesters

    var optionsEntry by remember { mutableStateOf<FreeboxFileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FreeboxFileEntry?>(null) }

    if (useCoverflow) {
        HomeCoverflowSection(
            items = filteredEntries.map { entry ->
                val contentId = freeboxContentIdForEntry(entry)
                val metadata = metadataMap[contentId]
                val posterArtwork = artworkMap[contentId]?.takeIf(String::isNotBlank)
                    ?: metadata?.posterUrl?.takeIf(String::isNotBlank)
                val backdropArtwork = backdropMap[contentId]?.takeIf(String::isNotBlank)
                    ?: metadata?.backdropUrl?.takeIf(String::isNotBlank)
                val rowArtwork = freeboxVideoRowArtwork(
                    posterUrl = artworkMap[contentId],
                    backdropUrl = backdropMap[contentId],
                    metadataPosterUrl = metadata?.posterUrl,
                    metadataBackdropUrl = metadata?.backdropUrl,
                    preferPortrait = !useCoverflow && entry.isDirectory &&
                        imageHeight > cardWidth && metadata?.mediaType.equals("tv", ignoreCase = true)
                )
                val displayName = if (entry.isDirectory) {
                    freeboxFolderDisplayName(entry.name)
                } else {
                    freeboxVideoDisplayTitle(
                        entry.name,
                        entry.resolvedHomeDuration(probedDurations)
                    )
                }
                HomeCoverflowItem(
                    key = entry.path,
                    title = displayName,
                    meta = listOfNotNull(
                        metadata?.genres?.firstOrNull()?.takeIf { it.isNotBlank() },
                        metadata?.year?.takeIf { it.isNotBlank() },
                        formatFreeboxDurationCompact(entry.resolvedHomeDuration(probedDurations))
                    ).joinToString(" • "),
                    cast = metadata?.cast.orEmpty(),
                    director = metadata?.director.orEmpty(),
                    synopsis = metadata?.overview,
                    artworkUrl = rowArtwork,
                    onClick = { onItemClick(entry) },
                    onLongPress = { optionsEntry = entry }
                )
            },
            modifier = modifier,
            entryFocusRequester = entryFocusRequester,
            upFocusRequester = upFocusRequester,
            downFocusRequester = downFocusRequester,
            onMoveUp = onMoveUp,
            focusRequesters = focusRequesters,
            onItemFocused = { index ->
                val entry = filteredEntries[index]
                val contentId = freeboxContentIdForEntry(entry)
                val metadata = metadataMap[contentId]
                val posterArtwork = artworkMap[contentId]?.takeIf(String::isNotBlank)
                    ?: metadata?.posterUrl?.takeIf(String::isNotBlank)
                val backdropArtwork = backdropMap[contentId]?.takeIf(String::isNotBlank)
                    ?: metadata?.backdropUrl?.takeIf(String::isNotBlank)
                onItemFocused(
                    index,
                    MetaPreview(
                        id = contentId,
                        type = ContentType.UNKNOWN,
                        rawType = if (entry.isDirectory) "freebox_folder" else "freebox",
                        name = if (entry.isDirectory) freeboxFolderDisplayName(entry.name) else freeboxDisplayName(entry.name),
                        poster = posterArtwork,
                        posterShape = PosterShape.LANDSCAPE,
                        background = backdropArtwork,
                        logo = metadata?.logoUrl,
                        description = metadata?.overview,
                        releaseInfo = metadata?.year,
                        imdbRating = metadata?.voteAverage?.toFloat(),
                        genres = metadata?.genres.orEmpty(),
                        runtime = formatFreeboxDurationCompact(
                            entry.resolvedHomeDuration(probedDurations)
                                ?: metadata?.runtimeMinutes?.let { it * 60_000L }
                        ),
                        director = metadata?.director.orEmpty(),
                        cast = metadata?.cast.orEmpty(),
                        seasonCount = metadata?.seasonCount
                    )
                )
            }
        )
    } else Column(modifier = modifier.clipToBounds()) {
        HomeSectionTitleChip(
            title = "Vid\u00e9os",
            modifier = Modifier.padding(start = horizontalPadding, bottom = 16.dp)
        )
        val rowFocusModifier = if (entryFocusRequester != null) {
            Modifier
                .focusRequester(entryFocusRequester)
                .focusRestorer(focusRequesters.firstOrNull() ?: FocusRequester.Default)
                .focusGroup()
        } else {
            Modifier
        }

        val listState = rememberLazyListState()
        // Films and Series render this translucent semantic tint over the
        // dark Home surface. Videos sits higher over the bright hero image,
        // which made the exact same alpha look teal and much more transparent.
        // Pre-compose it over that same dark surface so the visible tint is
        // identical without changing the other rows.
        val videoTitleBackgroundColor = NuvioColors.Secondary
            .copy(alpha = 0.18f)
            .compositeOver(NuvioColors.Background)
        var focusedVideoIndex by remember { mutableStateOf(-1) }
        LaunchedEffect(focusedVideoIndex, filteredEntries.size) {
            if (focusedVideoIndex in filteredEntries.indices) {
                // Explicitly frame the focused card. On Android TV the
                // custom focus graph can move focus without LazyRow receiving
                // a regular bring-into-view request, leaving the right side
                // of this row visually locked at its first cards.
                listState.animateScrollToItem(focusedVideoIndex)
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .then(rowFocusModifier),
            contentPadding = PaddingValues(
                start = horizontalPadding + focusBorderPadding,
                end = horizontalPadding + focusBorderPadding,
                top = focusBorderPadding,
                bottom = focusBorderPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            itemsIndexed(filteredEntries, key = { _, e -> e.path }) { index, entry ->
                val contentId = freeboxContentIdForEntry(entry)
                val metadata = metadataMap[contentId]
                val posterArtwork = artworkMap[contentId]?.takeIf(String::isNotBlank)
                    ?: metadata?.posterUrl?.takeIf(String::isNotBlank)
                val backdropArtwork = backdropMap[contentId]?.takeIf(String::isNotBlank)
                    ?: metadata?.backdropUrl?.takeIf(String::isNotBlank)
                val rowArtwork = freeboxVideoRowArtwork(
                    posterUrl = artworkMap[contentId],
                    backdropUrl = backdropMap[contentId],
                    metadataPosterUrl = metadata?.posterUrl,
                    metadataBackdropUrl = metadata?.backdropUrl,
                    preferPortrait = entry.isDirectory && imageHeight > cardWidth &&
                        metadata?.mediaType.equals("tv", ignoreCase = true)
                )
                val displayName = if (entry.isDirectory) {
                    freeboxFolderDisplayName(entry.name)
                } else {
                    freeboxVideoDisplayTitle(
                        entry.name,
                        entry.resolvedHomeDuration(probedDurations)
                    )
                }
                val cwItem = remember(entry, posterArtwork, rowArtwork, metadata, probedDurations[entry.path]) {
                    ContinueWatchingItem.InProgress(
                        progress = WatchProgress(
                            contentId = contentId,
                            contentType = if (entry.isDirectory) "freebox_folder" else "freebox",
                            name = displayName,
                            poster = posterArtwork,
                            // Videos cards are landscape, so their thumbnail
                            // must use the selected/provider backdrop. The
                            // portrait poster remains available to details.
                            backdrop = rowArtwork,
                            logo = metadata?.logoUrl,
                            videoId = contentId,
                            season = null,
                            episode = null,
                            episodeTitle = null,
                            position = 0L,
                            duration = entry.resolvedHomeDuration(probedDurations) ?: 0L,
                            lastWatched = 0L
                        ),
                        episodeDescription = metadata?.overview,
                        episodeImdbRating = metadata?.voteAverage?.toFloat(),
                        genres = metadata?.genres.orEmpty(),
                        cast = metadata?.cast.orEmpty(),
                        director = metadata?.director.orEmpty(),
                        releaseInfo = metadata?.year,
                        logo = metadata?.logoUrl,
                        castMembers = metadata?.castMembers.orEmpty()
                    )
                }
                val freeboxMetaPreview = remember(entry, posterArtwork, backdropArtwork, metadata) {
                    MetaPreview(
                        id = contentId,
                        type = ContentType.UNKNOWN,
                        rawType = if (entry.isDirectory) "freebox_folder" else "freebox",
                        name = displayName,
                        poster = posterArtwork,
                        posterShape = PosterShape.POSTER,
                        background = backdropArtwork,
                        logo = metadata?.logoUrl,
                        description = metadata?.overview,
                        releaseInfo = metadata?.year,
                        imdbRating = metadata?.voteAverage?.toFloat(),
                        genres = metadata?.genres.orEmpty(),
                        runtime = formatFreeboxDurationCompact(
                            entry.resolvedHomeDuration(probedDurations)
                                ?: metadata?.runtimeMinutes?.let { it * 60_000L }
                        ),
                        director = metadata?.director.orEmpty(),
                        cast = metadata?.cast.orEmpty(),
                        seasonCount = metadata?.seasonCount
                    )
                }
                val focusModifier = if (index < focusRequesters.size) {
                    Modifier.focusRequester(focusRequesters[index])
                } else {
                    Modifier
                }
                val horizontalFocusModifier = if (focusRequesters.size > 1) {
                    Modifier.focusProperties {
                        // Freebox/Vidéos rows are carousels: reaching either
                        // edge must stay in the same row, including when the
                        // focused card contains nested Compose subviews.
                        left = focusRequesters[(index - 1 + focusRequesters.size) % focusRequesters.size]
                        right = focusRequesters[(index + 1) % focusRequesters.size]
                    }
                } else {
                    Modifier
                }
                // Let Compose own the explicit Classic/Grid graph.  Calling a
                // row requester from the key callback can return success while
                // its focusRestorer is being replaced by metadata updates; the
                // event is then swallowed although no card received focus.
                val verticalFocusModifier = if (onMoveUp == null && onMoveDown == null &&
                    (upFocusRequester != null || downFocusRequester != null)
                ) {
                    Modifier.focusProperties {
                        if (upFocusRequester != null) up = upFocusRequester
                        if (downFocusRequester != null) down = downFocusRequester
                    }
                } else {
                    Modifier
                }
                var isItemFocused by remember(entry.path) { mutableStateOf(false) }
                // Videos keeps its landscape focus expansion.  The configured
                // thumbnail size remains the resting size; only the selected
                // landscape card grows to make the active video unmistakable.
                val effectiveCardWidth = if (isItemFocused && cardWidth < imageHeight) {
                    imageHeight * (16f / 9f)
                } else {
                    cardWidth
                }
                ContinueWatchingCard(
                    item = cwItem,
                    onClick = { onItemClick(entry) },
                    onLongPress = { optionsEntry = entry },
                    modifier = focusModifier
                        .then(verticalFocusModifier)
                        // Modern Home owns an ordered, asynchronous row graph.
                        // Only intercept there; returning false deliberately
                        // gives Compose a spatial fallback during recomposition.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false
                            else when (event.key) {
                                Key.DirectionUp -> onMoveUp?.invoke() == true
                                Key.DirectionDown -> onMoveDown?.invoke() == true
                                else -> false
                            }
                        }
                        .onFocusChanged { focusState ->
                            isItemFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                focusedVideoIndex = index
                                onItemFocused(index, freeboxMetaPreview)
                            }
                        }
                        .then(horizontalFocusModifier),
                    cardWidth = effectiveCardWidth,
                    imageHeight = imageHeight,
                    showBadge = false,
                    showFolderMarker = entry.isDirectory,
                    cardSizeMultiplier = cardSizeMultiplier,
                    titleBackgroundColor = videoTitleBackgroundColor,
                        onDirectionLeft = {
                            if (index > 0) {
                                runCatching { focusRequesters[index - 1].requestFocus() }.getOrDefault(false)
                            } else {
                                runCatching { focusRequesters.last().requestFocus() }.getOrDefault(false)
                            }
                        }
                )
            }
        }
    }

    val menuEntry = optionsEntry
    if (menuEntry != null) {
        FreeboxVideoOptionsDialog(
            entry = menuEntry,
            onDismiss = { optionsEntry = null },
            onShowDetails = {
                onShowDetails(menuEntry)
                optionsEntry = null
            },
            onChoosePoster = {
                posterPickerViewModel.open(
                    entry = menuEntry,
                    currentPosterUrl = artworkMap[freeboxContentIdForEntry(menuEntry)],
                    currentBackdropUrl = backdropMap[freeboxContentIdForEntry(menuEntry)],
                    allowPortrait = menuEntry.isDirectory &&
                        metadataMap[freeboxContentIdForEntry(menuEntry)]?.mediaType
                            .equals("tv", ignoreCase = true)
                )
                optionsEntry = null
            },
            onRenameFreebox = {
                renameEntry = menuEntry
                optionsEntry = null
            },
            onDeleteFromFreebox = {
                onDeleteFromFreebox(menuEntry)
                optionsEntry = null
            }
        )
    }

    val selectedRenameEntry = renameEntry
    if (selectedRenameEntry != null) {
        FreeboxRenameDialog(
            initialName = freeboxFileNameOnly(selectedRenameEntry.name),
            onDismiss = { renameEntry = null },
            onConfirm = { newName ->
                onRenameFreebox(selectedRenameEntry, newName)
                renameEntry = null
            }
        )
    }
    FreeboxPosterPickerDialog(
        state = posterPickerState,
        onSelect = { poster ->
            // The ViewModel closes only after persistence succeeds. Keeping
            // the dialog alive lets a failed save remain visible and retryable.
            posterPickerViewModel.select(poster)
        },
        onArtworkTypeSelected = posterPickerViewModel::changeArtworkType,
        onDismiss = posterPickerViewModel::dismiss
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FreeboxVideoOptionsDialog(
    entry: FreeboxFileEntry,
    onDismiss: () -> Unit,
    onShowDetails: () -> Unit,
    onChoosePoster: () -> Unit,
    onRenameFreebox: () -> Unit,
    onDeleteFromFreebox: () -> Unit
) {
    val title = remember(entry) {
        if (entry.isDirectory) {
            freeboxFileNameOnly(entry.name)
        } else {
            freeboxVideoDisplayTitle(entry.name, entry.durationMs)
        }
    }

    val detailsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        detailsFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle)
    ) {
        Button(
            onClick = onShowDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(detailsFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        Button(
            onClick = onChoosePoster,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.freebox_choose_poster))
        }

        Button(
            onClick = onRenameFreebox,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.cw_action_rename))
        }

        Button(
            onClick = onDeleteFromFreebox,
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
