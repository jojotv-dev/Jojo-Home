@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jojotv.home.R

@Composable
fun NextEpisodeEndPromptOverlay(
    nextEpisode: NextEpisodeInfo?,
    onReplay: () -> Unit,
    onContinue: (() -> Unit)?,
    onReturnToDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val replayFocusRequester = remember { FocusRequester() }
    val continueFocusRequester = remember { FocusRequester() }
    val returnFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val nextEpisodeText = nextEpisode?.let { nextEpisodeEndPromptLabel(it) }

    LaunchedEffect(nextEpisode?.videoId ?: "replay") {
        focusManager.clearFocus(force = true)
        repeat(3) { withFrameNanos { } }
        runCatching { replayFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .focusGroup()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.player_end_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(
                    if (nextEpisode != null) R.string.player_end_next_message
                    else R.string.player_end_replay_message
                ),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (nextEpisodeText != null) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.next_episode_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.46f),
                    maxLines = 1
            )
                Text(
                    text = "  $nextEpisodeText",
                    modifier = Modifier.weight(1f).basicMarquee(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogButton(
                    text = stringResource(R.string.player_end_replay),
                    onClick = onReplay,
                    isPrimary = true,
                    modifier = Modifier
                        .focusRequester(replayFocusRequester)
                        .focusProperties {
                            right = if (onContinue != null) continueFocusRequester else returnFocusRequester
                        }
                )

                if (onContinue != null) {
                    DialogButton(
                        text = stringResource(R.string.player_end_next_episode),
                        onClick = onContinue,
                        isPrimary = false,
                        modifier = Modifier
                            .focusRequester(continueFocusRequester)
                            .focusProperties {
                                left = replayFocusRequester
                                right = returnFocusRequester
                            }
                    )
                }

                DialogButton(
                    text = stringResource(R.string.player_end_back),
                    onClick = onReturnToDetails,
                    isPrimary = false,
                    modifier = Modifier
                        .focusRequester(returnFocusRequester)
                        .focusProperties {
                            left = if (onContinue != null) continueFocusRequester else replayFocusRequester
                        }
                )
            }
        }
    }
}

@Composable
private fun nextEpisodeEndPromptLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val episodeCode = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode
    )
    return "$episodeCode - ${nextEpisode.title}"
}
