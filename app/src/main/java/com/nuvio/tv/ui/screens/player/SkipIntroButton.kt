@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.jojotv.home.R
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * Skip Intro/Outro/Recap button for the player.
 * Remains visible at bottom-right while playback is within a skip interval.
 * Independent of the player controls and focusable for D-pad navigation.
 */
@Composable
fun SkipIntroButton(
    interval: SkipInterval?,
    dismissed: Boolean,
    controlsVisible: Boolean,
    suppressFocus: Boolean = false,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onHideControls: (() -> Unit)? = null,
    onVisibilityChanged: (Boolean) -> Unit = {},
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {

    var lastType by remember { mutableStateOf(interval?.type) }
    if (interval != null) lastType = interval.type
    val shouldShow = interval != null && (!dismissed || controlsVisible)

    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: internalFocusRequester
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(shouldShow) { onVisibilityChanged(shouldShow) }

    // Request focus when becoming visible or when controls hide
    // but not when the next episode card has priority
    LaunchedEffect(shouldShow, controlsVisible, suppressFocus) {
        if (shouldShow && !controlsVisible && !suppressFocus) {
            androidx.compose.runtime.withFrameNanos { }
            try { activeFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
        modifier = modifier
    ) {
        Card(
            onClick = onSkip,
            modifier = Modifier
                .focusRequester(activeFocusRequester)
                .then(
                    if (downFocusRequester != null || upFocusRequester != null) {
                        Modifier.focusProperties {
                            downFocusRequester?.let { down = it }
                            upFocusRequester?.let { up = it }
                        }
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (downFocusRequester != null) {
                                    try { downFocusRequester.requestFocus() } catch (_: Exception) {}
                                    true
                                } else {
                                    false
                                }
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                // Pressing UP from Skip Intro button navigates to upFocusRequester or hides controls
                                if (upFocusRequester != null) {
                                    try { upFocusRequester.requestFocus() } catch (_: Exception) {}
                                    true
                                } else if (onHideControls != null) {
                                    onHideControls()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                },
            colors = CardDefaults.colors(
                containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f),
                focusedContainerColor = NuvioColors.Secondary
            ),
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = null,
                    tint = if (isFocused) NuvioColors.OnSecondary else Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = getSkipLabel(lastType),
                    color = if (isFocused) NuvioColors.OnSecondary else Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun getSkipLabel(type: String?): String = when (type?.trim()?.lowercase()) {
    "op", "opening", "mixed-op", "intro" -> stringResource(R.string.skip_intro)
    "ed", "ending", "mixed-ed", "outro", "credits" -> stringResource(R.string.skip_ending)
    "recap" -> stringResource(R.string.skip_recap)
    else -> stringResource(R.string.skip_generic)
}
