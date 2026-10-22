package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jojotv.home.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.theme.ThemeColors

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GridContentCard(
    item: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    focusedPosterCardStyle: PosterCardStyle? = null,
    showLabel: Boolean = true,
    showLogo: Boolean = false,
    imageCrossfade: Boolean = true,
    isWatched: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onLongPress: (() -> Unit)? = null,
    onFocused: () -> Unit = {}
) {
    val isFavoriteInProgress = item.isFavorite &&
        (item.id in LocalFavoriteInProgressContentIds.current ||
            item.playbackProgressId?.let { it in LocalFavoriteInProgressContentIds.current } == true)
    var isFocused by remember { mutableStateOf(false) }
    val currentPosterCardStyle = if (isFocused && focusedPosterCardStyle != null) {
        focusedPosterCardStyle
    } else {
        posterCardStyle
    }
    val cardShape = remember(currentPosterCardStyle.cornerRadius) { RoundedCornerShape(currentPosterCardStyle.cornerRadius) }
    val density = LocalDensity.current
    val requestWidthPx = remember(density, currentPosterCardStyle.width) { with(density) { currentPosterCardStyle.width.roundToPx() }.coerceAtLeast(1) }
    val requestHeightPx = remember(density, currentPosterCardStyle.height) { with(density) { currentPosterCardStyle.height.roundToPx() }.coerceAtLeast(1) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()


    Column(
        modifier = modifier
            .width(currentPosterCardStyle.width)
            .recompositionHighlighter()
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
                .width(currentPosterCardStyle.width)
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .then(
                    if (upFocusRequester != null || downFocusRequester != null) {
                        Modifier.focusProperties {
                            if (upFocusRequester != null) {
                                up = upFocusRequester
                            }
                            if (downFocusRequester != null) {
                                down = downFocusRequester
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && onLongPress != null) {
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (onLongPress != null &&
                        longPressKeyTracker.handle(native, ::isSelectKey) {
                            longPressTriggered = true
                            onLongPress()
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
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(currentPosterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = currentPosterCardStyle.focusedScale)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentPosterCardStyle.height)
                        .clip(cardShape)
                ) {
                    val context = LocalContext.current
                    val bgCardColor = NuvioColors.BackgroundCard
                    val bgPainter = remember(bgCardColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgCardColor) }
                    val imageModel = remember(item.poster, requestWidthPx, requestHeightPx) {
                        ImageRequest.Builder(context)
                            .data(item.poster)
                            .crossfade(imageCrossfade)
                            .size(width = requestWidthPx, height = requestHeightPx)
                            .memoryCacheKey("${item.poster}_${requestWidthPx}x${requestHeightPx}")
                            .build()
                    }
                    val imageContentScale = if (
                        item.rawType.equals("iptv_live", ignoreCase = true) ||
                            item.posterShape == PosterShape.POSTER ||
                            posterCardStyle.height > posterCardStyle.width
                    ) {
                        // Preserve the full source image in live-TV and portrait cards.
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    }
                    if (item.poster.isNullOrBlank()) {
                        MonochromePosterPlaceholder()
                    } else {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = item.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .clip(cardShape),
                            contentScale = imageContentScale,
                            placeholder = bgPainter,
                            error = bgPainter,
                            fallback = bgPainter
                        )
                    }

                    if (showLogo && !item.logo.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(currentPosterCardStyle.height * 0.45f)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                                    )
                                )
                        )
                        val logoRequest = remember(item.logo) {
                            ImageRequest.Builder(context)
                                .data(item.logo)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = logoRequest,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .heightIn(max = currentPosterCardStyle.height * 0.35f)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }

                    if (isFavoriteInProgress) {
                        FavoriteInProgressBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .zIndex(3f)
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
                }

                if (showLabel && (!showLogo || item.logo.isNullOrBlank())) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = NuvioColors.Secondary.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(
                                    bottomStart = currentPosterCardStyle.cornerRadius,
                                    bottomEnd = currentPosterCardStyle.cornerRadius
                                )
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                        )
                    }
                }
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
