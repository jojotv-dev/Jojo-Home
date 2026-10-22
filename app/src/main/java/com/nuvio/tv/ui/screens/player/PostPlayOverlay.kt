@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.jojotv.home.R
import com.nuvio.tv.ui.theme.NuvioColors
import java.util.Locale

@Composable
fun PostPlayOverlay(
    mode: PostPlayMode?,
    nextEpisodeFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester?,
    onPlayNext: () -> Unit,
    onCancelAutoPlay: () -> Unit,
    onContinueStillWatching: () -> Unit,
    onDismissStillWatching: () -> Unit,
    modifier: Modifier = Modifier,
    seriesTitle: String? = null,
    releaseYear: String? = null,
    controlsVisible: Boolean = false,
) {
    val isAutoPlay = mode is PostPlayMode.AutoPlay
    val isAutoPlayPlayable = (mode as? PostPlayMode.AutoPlay)?.nextEpisode?.hasAired == true
    val cardShape = RoundedCornerShape(if (isAutoPlay) 20.dp else 14.dp)

    val transition = updateTransition(targetState = mode, label = "PostPlayMode")

    transition.AnimatedVisibility(
        visible = { it != null },
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
            fadeOut(animationSpec = tween(160)),
        modifier = modifier,
    ) {
        val onCardClick: () -> Unit = when {
            isAutoPlay && isAutoPlayPlayable -> onPlayNext
            mode is PostPlayMode.StillWatching -> onContinueStillWatching
            else -> ({})
        }
        Card(
            onClick = onCardClick,
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = Color(0xE3191919),
                focusedContainerColor = Color(0xE3191919),
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    shape = cardShape,
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = cardShape,
                ),
            ),
            scale = CardDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .width(if (isAutoPlay) 480.dp else 420.dp)
                .then(
                    if (progressBarFocusRequester != null) {
                        Modifier.focusProperties { down = progressBarFocusRequester }
                    } else {
                        Modifier
                    }
                ),
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(120))
                },
                contentKey = { it?.let { current -> current::class } },
            ) { current ->
                when (current) {
                    is PostPlayMode.AutoPlay -> AutoPlayBody(
                        mode = current,
                        onPlay = onPlayNext,
                        onCancel = onCancelAutoPlay,
                        playFocusRequester = nextEpisodeFocusRequester,
                        seriesTitle = seriesTitle,
                        releaseYear = releaseYear,
                        controlsVisible = controlsVisible,
                    )
                    is PostPlayMode.StillWatching -> StillWatchingBody(
                        mode = current,
                        onContinue = onContinueStillWatching,
                        onDismiss = onDismissStillWatching,
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun AutoPlayBody(
    mode: PostPlayMode.AutoPlay,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    playFocusRequester: FocusRequester,
    seriesTitle: String?,
    releaseYear: String?,
    controlsVisible: Boolean,
) {
    val nextEpisode = mode.nextEpisode
    val isPlayable = nextEpisode.hasAired
    val (title, metadata) = nextEpisodeCardText(nextEpisode, seriesTitle, releaseYear)
    // Take focus once when the card appears or controls close. Countdown updates
    // must not pull focus away from Cancel.
    LaunchedEffect(nextEpisode.videoId, controlsVisible) {
        if (!controlsVisible) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { playFocusRequester.requestFocus() }
        }
    }
    Row(
        modifier = Modifier
            .heightIn(min = 104.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NextEpisodeThumbnail(
            thumbnail = nextEpisode.thumbnail,
            contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                softWrap = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            if (metadata != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = metadata,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            val statusText = when {
                !isPlayable && !nextEpisode.unairedMessage.isNullOrBlank() -> nextEpisode.unairedMessage
                mode.searching -> stringResource(R.string.next_episode_finding_source)
                mode.countdownSec != null -> stringResource(R.string.next_episode_countdown, mode.countdownSec)
                else -> null
            }
            if (statusText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                NextEpisodeStatusLine(text = statusText)
            }
        }

        val cancelFocusRequester = remember { FocusRequester() }
        Column(
            modifier = Modifier.padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.next_episode_label),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PostPlayPillButton(
                    icon = Icons.Default.PlayArrow,
                    iconTint = if (isPlayable) Color.White else Color.White.copy(alpha = 0.65f),
                    label = if (isPlayable) stringResource(R.string.next_episode_play) else stringResource(R.string.next_episode_unaired),
                    textColor = if (isPlayable) Color.White else Color.White.copy(alpha = 0.72f),
                    onClick = onPlay,
                    focusRequester = playFocusRequester,
                    modifier = Modifier.focusProperties { right = cancelFocusRequester },
                )
                PostPlayPillButton(
                    icon = Icons.Default.Close,
                    iconTint = Color.White.copy(alpha = 0.7f),
                    label = stringResource(R.string.next_episode_cancel),
                    textColor = Color.White.copy(alpha = 0.8f),
                    onClick = onCancel,
                    focusRequester = cancelFocusRequester,
                    modifier = Modifier.focusProperties { left = playFocusRequester },
                )
            }
        }
    }
}

@Composable
private fun StillWatchingBody(
    mode: PostPlayMode.StillWatching,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val continueFocusRequester = remember { FocusRequester() }
    val exitFocusRequester = remember { FocusRequester() }
    var continueFocused by remember { mutableStateOf(false) }

    val nextEpisode = mode.nextEpisode
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nextEpisode.thumbnail != null) {
            NextEpisodeThumbnail(
                thumbnail = nextEpisode.thumbnail,
                contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.still_watching_title),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = nextEpisodeDisplayLabel(nextEpisode),
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            if (mode.countdownSec != null) {
                Spacer(modifier = Modifier.height(2.dp))
                NextEpisodeStatusLine(
                    text = stringResource(R.string.still_watching_countdown, mode.countdownSec),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PostPlayPillButton(
                icon = Icons.Default.PlayArrow,
                iconTint = Color.White,
                label = stringResource(R.string.still_watching_continue),
                textColor = Color.White,
                onClick = onContinue,
                focusRequester = continueFocusRequester,
                modifier = Modifier
                    .onPlaced {
                        if (!continueFocused) {
                            continueFocused = true
                            runCatching { continueFocusRequester.requestFocus() }
                        }
                    }
                    .focusProperties { right = exitFocusRequester },
            )
            PostPlayPillButton(
                icon = Icons.Default.Close,
                iconTint = Color.White.copy(alpha = 0.65f),
                label = stringResource(R.string.still_watching_exit),
                textColor = Color.White.copy(alpha = 0.72f),
                onClick = onDismiss,
                focusRequester = exitFocusRequester,
                modifier = Modifier.focusProperties { left = continueFocusRequester },
            )
        }
    }
}

@Composable
private fun PostPlayPillButton(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = CircleShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.15f),
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                shape = CircleShape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = modifier.focusRequester(focusRequester),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

@Composable
private fun NextEpisodeThumbnail(
    thumbnail: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 112.dp, height = 64.dp)
            .clip(RoundedCornerShape(9.dp)),
    ) {
        AsyncImage(
            model = thumbnail,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)),
                    ),
                ),
        )
    }
}

@Composable
private fun NextEpisodeStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.78f),
        fontSize = 11.sp,
        softWrap = true,
        modifier = modifier,
    )
}

private fun nextEpisodeCardText(
    nextEpisode: NextEpisodeInfo,
    seriesTitle: String?,
    releaseYear: String?,
): Pair<String, String?> {
    if (nextEpisode.isOtherType) return nextEpisode.title to null
    val rawTitle = seriesTitle?.takeIf { it.isNotBlank() } ?: nextEpisode.title
    val titleYearPattern = Regex("""\((?:19|20)\d{2}\)""")
    val year = releaseYear?.takeIf { it.isNotBlank() }
        ?: titleYearPattern.find(rawTitle)?.value?.removeSurrounding("(", ")")
        ?: titleYearPattern.find(nextEpisode.title)?.value?.removeSurrounding("(", ")")
    val title = rawTitle
        .replace(Regex("""(?i)(?<![\p{L}\p{N}])S\s*\d{1,2}[ ._-]*E\s*\d{1,3}(?![\p{L}\p{N}])"""), "")
        .replace(titleYearPattern, "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '•', '-', '–', '—', '|', ':')
    val code = String.format(Locale.ROOT, "S%02dE%02d", nextEpisode.season, nextEpisode.episode)
    return title.ifBlank { nextEpisode.title } to listOfNotNull(year, code).joinToString(" • ")
}

@Composable
private fun nextEpisodeDisplayLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val code = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode,
    )
    return "$code • ${nextEpisode.title}"
}
