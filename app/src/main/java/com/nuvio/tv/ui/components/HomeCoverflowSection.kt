package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.screens.player.PlayerLaunchArtworkState
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker

/**
 * The home equivalent of the IPTV Favorites coverflow.  It deliberately owns
 * its metadata and synopsis instead of feeding the global Home hero: each row
 * therefore keeps the same focused-item contract in Modern, Grid and Classic.
 */
internal data class HomeCoverflowItem(
    val key: String,
    val title: String,
    val meta: String,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val synopsis: String? = null,
    val artworkUrl: String? = null,
    val portraitArtworkUrl: String? = null,
    val inProgress: Boolean = false,
    val episodeCode: String? = null,
    val onClick: () -> Unit,
    val onLongPress: () -> Unit = {},
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeCoverflowSection(
    items: List<HomeCoverflowItem>,
    sectionTitle: String? = null,
    cardScale: Float = 1f,
    modifier: Modifier = Modifier,
    entryFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    initialFocusedIndex: Int = -1,
    onItemFocused: (Int) -> Unit = {},
    focusRequesters: List<FocusRequester>? = null,
    canRequestInitialFocus: Boolean = true,
) {
    if (items.isEmpty()) return

    val ownedFocusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val itemFocusRequesters = focusRequesters?.takeIf { it.size == items.size } ?: ownedFocusRequesters
    val itemKeys = items.map(HomeCoverflowItem::key)
    var selectedKey by remember {
        mutableStateOf(itemKeys.getOrNull(initialFocusedIndex) ?: itemKeys.first())
    }
    // Restoring Home after leaving the player may deliver the saved index one
    // frame after the coverflow was composed. Do not animate that correction:
    // the card must appear directly at its restored center position.
    var positionAnimationEnabled by remember { mutableStateOf(false) }
    val selectedIndex = itemKeys.indexOf(selectedKey).takeIf { it >= 0 } ?: 0
    var pendingHorizontalFocusKey by remember { mutableStateOf<String?>(null) }
    val selected = items.getOrNull(selectedIndex.coerceIn(0, items.lastIndex)) ?: return
    // Geometry-based focus search sees the sidebar and the following Home row
    // as valid neighbours of the narrow cards. Resolve the target only after
    // the carousel has recomposed around it; requesting it immediately made
    // Compose restore a stale row focus while the card was still moving.
    val moveHorizontally: (Int) -> Boolean = { direction ->
        val targetIndex = Math.floorMod(selectedIndex + direction, items.size)
        selectedKey = itemKeys[targetIndex]
        pendingHorizontalFocusKey = itemKeys[targetIndex]
        true
    }

    androidx.compose.runtime.LaunchedEffect(pendingHorizontalFocusKey, itemKeys, itemFocusRequesters) {
        val targetKey = pendingHorizontalFocusKey ?: return@LaunchedEffect
        val targetIndex = itemKeys.indexOf(targetKey).takeIf { it >= 0 }
            ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { itemFocusRequesters[targetIndex].requestFocus() }
        if (pendingHorizontalFocusKey == targetKey) {
            pendingHorizontalFocusKey = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        initialFocusedIndex,
        itemKeys,
        itemFocusRequesters,
        canRequestInitialFocus
    ) {
        // Apply any restored selection without an entrance translation. This
        // also covers the case where the saved index arrives after the first
        // Home composition.
        positionAnimationEnabled = false
        if (canRequestInitialFocus) {
            val target = initialFocusedIndex.takeIf { it in items.indices }
            if (target != null) {
                selectedKey = itemKeys[target]
                runCatching { itemFocusRequesters[target].requestFocus() }
            }
        }
        androidx.compose.runtime.withFrameNanos { }
        positionAnimationEnabled = true
    }

    androidx.compose.runtime.LaunchedEffect(itemKeys) {
        if (selectedKey !in itemKeys) selectedKey = itemKeys.first()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sectionTitle?.takeIf { it.isNotBlank() }?.let { title ->
            HomeSectionTitleChip(
                title = title,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(start = 52.dp, bottom = 16.dp)
            )
        }
        Box(
            // Keep the side previews visible; clipping the whole viewport
            // cuts the left/right cards while they are intentionally offset.
            modifier = Modifier
                .fillMaxWidth()
                // Keep the caption tied to the actual focused card size. The
                // former fixed 332 dp viewport left a large empty gap when
                // compact/standard scaling was selected.
                .height(316.dp * cardScale + 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            items.forEachIndexed { index, item ->
                HomeCoverflowCard(
                    item = item,
                    cardScale = cardScale,
                    relativeIndex = wrappedCoverflowRelativeIndex(index, selectedIndex, items.size),
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onMoveHorizontally = moveHorizontally,
                    positionAnimationEnabled = positionAnimationEnabled,
                    modifier = Modifier
                        .focusRequester(itemFocusRequesters[index])
                        .then(if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            if (upFocusRequester != null) up = upFocusRequester
                            if (downFocusRequester != null) down = downFocusRequester
                        }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                if (selectedKey != item.key) selectedKey = item.key
                                onItemFocused(index)
                            }
                        },
                )
            }
        }
        Text(
            text = selected.title,
            modifier = Modifier.padding(top = 2.dp, start = 52.dp, end = 52.dp),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected.meta.isNotBlank()) {
            Text(
                text = selected.meta,
                modifier = Modifier.padding(top = 2.dp, start = 52.dp, end = 52.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shared Home row title treatment; every row uses the Videos reference color. */
@Composable
internal fun HomeSectionTitleChip(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(NuvioColors.Secondary)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.Background,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeCoverflowCard(
    item: HomeCoverflowItem,
    cardScale: Float,
    relativeIndex: Int,
    onMoveUp: (() -> Boolean)?,
    onMoveDown: (() -> Boolean)?,
    onMoveHorizontally: (Int) -> Boolean,
    positionAnimationEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val distance = kotlin.math.abs(relativeIndex)
    if (distance > 4) return
    val active = distance == 0
    // The focused card keeps the 16:9 resume artwork. Lateral cards use the
    // official portrait poster and morph back to landscape when selected.
    val scale = cardScale.coerceIn(0.75f, 1.5f)
    val sideCardWidth = 144.dp * scale
    val targetWidth = if (active) 560.dp * scale else sideCardWidth
    val targetHeight = if (active) 316.dp * scale else 256.dp * scale
    val sideCardGap = 24.dp * scale
    val firstOffset = (560.dp * scale) / 2 + sideCardGap + sideCardWidth / 2
    val additionalSideOffset = (sideCardWidth + sideCardGap) * (distance - 1).toFloat()
    val targetX = when {
        active -> 0.dp
        // Side cards are 4x wider than before.  Their old fixed 60 dp step
        // left them stacked on top of one another; advance by the actual
        // thumbnail width so each lateral preview remains readable.
        relativeIndex < 0 -> -(firstOffset + additionalSideOffset)
        else -> firstOffset + additionalSideOffset
    }
    val positionAnimationSpec: AnimationSpec<Dp> =
        if (positionAnimationEnabled) tween(300) else snap()
    val x by animateDpAsState(targetX, positionAnimationSpec, label = "homeCoverflowX")
    val width by animateDpAsState(targetWidth, positionAnimationSpec, label = "homeCoverflowWidth")
    val height by animateDpAsState(targetHeight, positionAnimationSpec, label = "homeCoverflowHeight")
    val displayedArtworkUrl = if (active) {
        item.artworkUrl
    } else {
        item.portraitArtworkUrl ?: item.artworkUrl
    }
    val longPressTracker = rememberLongPressKeyTracker()
    var longPressTriggered by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(18.dp)
    val launchItem = {
        PlayerLaunchArtworkState.rememberDisplayedArtwork(
            contentId = item.key,
            title = item.title,
            artworkUrl = item.artworkUrl
        )
        item.onClick()
    }

    Card(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                launchItem()
            }
        },
        modifier = modifier
            .offset(x = x)
            .width(width)
            .height(height)
            .clip(cardShape)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.repeatCount == 0 &&
                    event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_MENU
                ) {
                    longPressTriggered = false
                }
                // Some Android TV remotes expose the contextual long-press
                // action as MENU instead of a repeated CENTER/ENTER event.
                // Handle it here as well as in the regular Continue Watching
                // card so the coverflow variant cannot swallow the action.
                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU &&
                    event.nativeKeyEvent.repeatCount == 0
                ) {
                    longPressTriggered = true
                    item.onLongPress()
                    return@onPreviewKeyEvent true
                }
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft ->
                        onMoveHorizontally(-1)
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight ->
                        onMoveHorizontally(1)
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp && onMoveUp?.invoke() == true -> true
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown && onMoveDown?.invoke() == true -> true
                    else -> longPressTracker.handle(
                        event.nativeKeyEvent,
                        isLongPressKey = { keyCode ->
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A
                        },
                        onLongPress = {
                            longPressTriggered = true
                            item.onLongPress()
                        },
                        onClick = { if (!longPressTriggered) launchItem() },
                        consumeUntilUp = true,
                    )
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            contentColor = NuvioColors.TextPrimary,
            focusedContainerColor = NuvioColors.BackgroundCard,
            focusedContentColor = NuvioColors.TextPrimary,
        ),
        shape = CardDefaults.shape(cardShape),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, NuvioColors.FocusRing),
                shape = cardShape,
            )
        ),
        // The parent panel is deliberately compact. Do not scale a focused
        // card into its clipped bounds, otherwise the rounded ring is flattened.
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = displayedArtworkUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                // Logos and portrait channel identities must remain contained
                // in both the focused and lateral states. Landscape artwork
                // keeps the normal crop treatment.
                contentScale = if (item.artworkUrl == item.portraitArtworkUrl) {
                    ContentScale.Fit
                } else {
                    ContentScale.Crop
                },
            )
            item.episodeCode?.let { code ->
                CoverflowCardBadge(
                    text = code,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                )
            }
            if (item.inProgress) {
                FavoriteInProgressBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun CoverflowCardBadge(
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 10.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = horizontalPadding, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
        )
    }
}

private fun wrappedCoverflowRelativeIndex(index: Int, selected: Int, count: Int): Int {
    if (count <= 1) return 0
    var relative = ((index - selected) % count + count) % count
    if (relative > count / 2) relative -= count
    return relative
}
