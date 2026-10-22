@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.media.FaceDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.jojotv.home.HomeMediaRules
import com.jojotv.home.OnlineAppIconLoader
import com.jojotv.home.iptv.IptvRemote
import com.jojotv.home.iptv.IptvRemoteAction
import com.jojotv.home.iptv.IptvRemotePress
import androidx.annotation.RawRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import coil3.transform.Transformation
import androidx.compose.ui.res.stringResource
import com.jojotv.home.R
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    errorAccentColor: Color,
    onBeforeExit: () -> Unit = {},
    onBackPress: (currentVideoId: String?, currentSeason: Int?, currentEpisode: Int?, autoPlayEnabled: Boolean, playbackCompleted: Boolean) -> Unit,
    onPlaybackErrorBack: () -> Unit = { onBackPress(null, null, null, false, false) },
    onPlaybackEnded: ((nextVideoId: String?, nextSeason: Int?, nextEpisode: Int?, exitReason: PlayerExitReason?) -> Unit)? = null,
    onIptvNavigation: ((String) -> Unit)? = null,
    attachedPlayerView: PlayerView? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val isFreeboxDeletionInProgress by viewModel.isFreeboxDeletionInProgress.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val iptvAppearance = remember(uiState.contentType) {
        if (uiState.contentType.equals("iptv", true)) com.jojotv.home.iptv.IptvStore.playerAppearance(context, "iptv:") else emptyMap()
    }
    val isIptvAppearance = uiState.contentType.equals("iptv", true)
    val iptvPanelOpacity = 1f - (iptvAppearance["interfaceTransparency"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50) / 100f
    val iptvRemotePress = remember(uiState.currentVideoId) { IptvRemotePress(android.view.ViewConfiguration.getLongPressTimeout().toLong()) }
    val iptvRemoteScope = rememberCoroutineScope()
    var iptvRemotePanel by remember { mutableStateOf(IptvRemoteAction.INFO_CONTROLS) }
    var initialChannelInfo by remember(uiState.title) { mutableStateOf(true) }
    LaunchedEffect(uiState.showControls, uiState.title) {
        if (!uiState.showControls) { initialChannelInfo = false; iptvRemotePanel = IptvRemoteAction.INFO_CONTROLS }
    }
    val freeboxDeleteSuccessMessage = "« %1\$s » a bien été supprimée de la Freebox."
    val containerFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val progressBarFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val streamsFocusRequester = remember { FocusRequester() }
    val sourceStreamsFocusRequester = remember { FocusRequester() }
    val skipIntroFocusRequester = remember { FocusRequester() }
    val streamInfoFocusRequester = remember { FocusRequester() }
    var skipButtonActuallyVisible by remember { mutableStateOf(false) }
    var skipIntroConfirmKeyCode by remember { mutableStateOf<Int?>(null) }
    var restoreStreamInfoFocus by remember { mutableStateOf(false) }
    var showControlsDuringLoading by remember { mutableStateOf(false) }
    var loadingControlsFocusRequestId by remember { mutableStateOf(0) }
    var loadingControlsFocusProgressBar by remember { mutableStateOf(false) }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    var subtitleDelayAutoSyncFocused by remember { mutableStateOf(false) }
    var subtitleTimingConsumeNextConfirmKeyUp by remember { mutableStateOf(false) }
    var isExitingPlayer by remember { mutableStateOf(false) }
    var pendingIptvNavigation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.freeboxDeletionErrors) {
        viewModel.freeboxDeletionErrors.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnBackPress by rememberUpdatedState(onBackPress)
    val currentOnIptvNavigation by rememberUpdatedState(onIptvNavigation)

    val exitPlayer: () -> Unit = exitPlayer@{
        if (isExitingPlayer) return@exitPlayer
        val exitRequestedAt = android.os.SystemClock.elapsedRealtime()
        Log.i("PlayerScreen", "EXIT_REQUEST at=$exitRequestedAt")
        isExitingPlayer = true
        val timeline = viewModel.playbackTimeline.value
        val progressSaveStartedAt = android.os.SystemClock.elapsedRealtime()
        viewModel.saveWatchProgress()
        Log.i(
            "PlayerScreen",
            "EXIT_PROGRESS_SAVE_RETURN at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - progressSaveStartedAt}ms"
        )
        val homeSaveStartedAt = android.os.SystemClock.elapsedRealtime()
        onBeforeExit()
        Log.i(
            "PlayerScreen",
            "EXIT_HOME_SAVE_RETURN at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - homeSaveStartedAt}ms"
        )
        val completed = timeline.duration > 0L &&
            (timeline.currentPosition.toFloat() / timeline.duration.toFloat()) >=
                WatchProgress.completionThreshold(uiState.contentType, uiState.currentEpisode)
        runCatching {
            val navigation = pendingIptvNavigation
            if (navigation != null && currentOnIptvNavigation != null) currentOnIptvNavigation?.invoke(navigation)
            else {
                onBackPress(
                    uiState.currentVideoId,
                    uiState.currentSeason,
                    uiState.currentEpisode,
                    uiState.streamAutoPlayMode != StreamAutoPlayMode.MANUAL,
                    completed
                )
            }
            viewModel.stopAndRelease()
            Log.i("PlayerScreen", "EXIT_CALLBACK_RETURN at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - exitRequestedAt}ms")
        }.onFailure {
            Log.e("PlayerScreen", "Failed to exit player", it)
            pendingIptvNavigation = null
            isExitingPlayer = false
        }
    }
    val navigateIptv: (String) -> Unit = { action ->
        if (currentOnIptvNavigation != null && !isExitingPlayer) {
            if (attachedPlayerView != null && action in listOf("next", "previous", "remote:RECENT", "remote:FAVORITE", "remote:RECORD",
                    "remote:INFO_NEXT", "remote:INFO_PREVIOUS")) {
                currentOnIptvNavigation?.invoke(action)
            } else {
                pendingIptvNavigation = action; exitPlayer()
            }
        }
    }
    val exitPlayerFromError: () -> Unit = exitPlayerFromError@{
        if (isExitingPlayer) return@exitPlayerFromError
        isExitingPlayer = true
        viewModel.saveWatchProgress()
        onBeforeExit()
        runCatching {
            onPlaybackErrorBack()
            viewModel.stopAndRelease()
        }.onFailure {
            Log.e("PlayerScreen", "Failed to exit player after playback error", it)
            isExitingPlayer = false
        }
    }
    val dispatchPlaybackEnd: (String?, Int?, Int?, PlayerExitReason?) -> Unit = dispatchPlaybackEnd@{ nextVideoId, nextSeason, nextEpisode, reason ->
        if (isExitingPlayer) return@dispatchPlaybackEnd
        isExitingPlayer = true
        val finishDispatch: () -> Unit = {
            viewModel.saveWatchProgress()
            onBeforeExit()
            runCatching {
                val cb = currentOnPlaybackEnded
                if (cb != null) {
                    cb(nextVideoId, nextSeason, nextEpisode, reason)
                } else {
                    currentOnBackPress(
                        uiState.currentVideoId,
                        uiState.currentSeason,
                        uiState.currentEpisode,
                        uiState.streamAutoPlayMode != StreamAutoPlayMode.MANUAL,
                        true
                    )
                }
            }.onFailure {
                Log.e("PlayerScreen", "Failed to dispatch playback end", it)
                isExitingPlayer = false
            }
            Unit
        }
        finishDispatch()
    }
    val dismissStreamInfoOverlay = {
        viewModel.onEvent(PlayerEvent.OnDismissStreamInfo)
    }

    val nextEpisodeForEndPrompt = uiState.nextEpisode?.takeIf { it.hasAired }
    // Some Freebox files report this specific container/parser error on the
    // final packet after virtually all content has already played. Keep the
    // exception extremely narrow: another playback error, or a position below
    // the final 1% of the file, must never look like normal completion.
    val freeboxPlaybackReachedEndAfterError = uiState.isFreeboxPlayback &&
        uiState.error?.contains("No valid varint length mask found", ignoreCase = true) == true &&
        viewModel.playbackTimeline.value.duration > 0L &&
        viewModel.playbackTimeline.value.currentPosition >=
            (viewModel.playbackTimeline.value.duration * 0.99f).toLong()
    val completionActionPrompt = when {
        uiState.isFreeboxPlayback && (uiState.playbackEnded || freeboxPlaybackReachedEndAfterError) ->
            CompletionActionPrompt.FREEBOX_DELETE
        !uiState.playbackEnded || uiState.error != null -> null
        else -> null
    }
    val shouldShowPlaybackEndOverlay =
        uiState.playbackEnded &&
            completionActionPrompt == null &&
            uiState.error == null &&
            !uiState.streamAutoPlayNextEpisodeEnabled &&
            uiState.postPlayMode == null &&
            !uiState.isFreeboxPlayback &&
            !uiState.contentType.equals("live", ignoreCase = true)
    val returnToDetailsFromEndPrompt = {
        dispatchPlaybackEnd(null, null, null, null)
    }
    val playSmbEpisode: (com.nuvio.tv.domain.model.Video) -> Unit = { video ->
        if (!isExitingPlayer && video.id != uiState.currentVideoId) {
            isExitingPlayer = true
            viewModel.saveWatchProgress()
            onBeforeExit()
            currentOnBackPress(video.id, video.season, video.episode, false, false)
            viewModel.stopAndRelease()
        }
    }
    val playNextEpisode = {
        val next = uiState.nextEpisode?.takeIf { it.hasAired }
        val smbVideo = uiState.episodesAll.firstOrNull { it.id == next?.videoId }
            ?.takeIf { uiState.isFreeboxPlayback && it.id.startsWith("freebox:smb:") }
        if (smbVideo != null) {
            // Home opens a fresh SMB proxy and preserves each file's resume identity.
            playSmbEpisode(smbVideo)
        } else {
            viewModel.onEvent(PlayerEvent.OnPlayNextEpisode)
        }
    }
    val continueToNextEpisodeFromEndPrompt = {
        if (nextEpisodeForEndPrompt != null) {
            playNextEpisode()
        }
    }

    val handleBackPress = {
        if (completionActionPrompt != null) {
            dispatchPlaybackEnd(null, null, null, null)
        } else if (shouldShowPlaybackEndOverlay) {
            returnToDetailsFromEndPrompt()
        } else if (uiState.error != null) {
            exitPlayerFromError()
        } else if (uiState.showAudioOverlay || uiState.showSubtitleOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay)
        } else if (uiState.showStreamInfoOverlay) {
            dismissStreamInfoOverlay()
        } else if (uiState.showMoreDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
        } else if (uiState.showSubtitleTimingDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog)
        } else if (uiState.showSubtitleDelayOverlay) {
            viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
        } else if (uiState.showSubtitleStylePanel) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleStylePanel)
        } else if (uiState.showSourcesPanel) {
            if (uiState.currentStreamUrl.isNullOrBlank()) {
                exitPlayer()
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
            }
        } else if (uiState.showEpisodesPanel) {
            if (uiState.showEpisodeStreams) {
                viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams)
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel)
            }
        } else if (uiState.postPlayMode is PostPlayMode.AutoPlay) {
            viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard)
            // Transfer focus to skip button if it's still visible
            if (skipButtonActuallyVisible) {
                runCatching { skipIntroFocusRequester.requestFocus() }
            }
        } else if (uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed && !uiState.showControls) {
            viewModel.onEvent(PlayerEvent.OnDismissSkipIntro)
        } else if (uiState.postPlayMode is PostPlayMode.StillWatching) {
            viewModel.onEvent(PlayerEvent.OnDismissStillWatchingPrompt)
        } else if (!uiState.isPlaying && !uiState.isBuffering &&
            !uiState.showLoadingOverlay && uiState.showControls) {
            viewModel.hideControls()
        } else {
            // During playback, Back exits immediately even when controls are visible.
            exitPlayer()
        }
    }

    BackHandler {
        Log.i(
            "PlayerScreen",
            "BACK_HANDLER at=${android.os.SystemClock.elapsedRealtime()} " +
                "playing=${uiState.isPlaying} ended=${uiState.playbackEnded} " +
                "hasError=${uiState.error != null} sources=${uiState.showSourcesPanel} " +
                "streamBlank=${uiState.currentStreamUrl.isNullOrBlank()} " +
                "postPlay=${uiState.postPlayMode?.javaClass?.simpleName}"
        )
        handleBackPress()
    }

    val performIptvRemote: (IptvRemoteAction, Int) -> Unit = { action, keyCode ->
        when (action) {
            IptvRemoteAction.IGNORE -> Unit
            IptvRemoteAction.INFO, IptvRemoteAction.CONTROLS, IptvRemoteAction.INFO_CONTROLS, IptvRemoteAction.DESCRIPTION -> {
                iptvRemotePanel = if (action == IptvRemoteAction.DESCRIPTION) IptvRemoteAction.INFO else action
                initialChannelInfo = action in listOf(IptvRemoteAction.INFO, IptvRemoteAction.INFO_CONTROLS, IptvRemoteAction.DESCRIPTION)
                if (!uiState.showControls) viewModel.onEvent(PlayerEvent.OnToggleControls)
                else { viewModel.scheduleHideControls(); viewModel.onUserInteraction() }
                if (iptvRemotePanel == IptvRemoteAction.INFO) runCatching { containerFocusRequester.requestFocus() }
            }
            IptvRemoteAction.MENU -> viewModel.onEvent(PlayerEvent.OnShowMoreDialog)
            IptvRemoteAction.LIVE -> viewModel.onEvent(PlayerEvent.OnReturnToLive)
            IptvRemoteAction.PLAY_PAUSE -> {
                viewModel.onEvent(playerMediaKeyEvent(keyCode) ?: PlayerEvent.OnPlayPause)
            }
            IptvRemoteAction.REWIND -> { viewModel.onEvent(PlayerEvent.OnSeekBackward); viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek) }
            IptvRemoteAction.FORWARD -> { viewModel.onEvent(PlayerEvent.OnSeekForward); viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek) }
            IptvRemoteAction.AUDIO -> viewModel.onEvent(PlayerEvent.OnShowAudioOverlay)
            IptvRemoteAction.SUBTITLES -> viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay)
            IptvRemoteAction.ASPECT -> viewModel.onEvent(PlayerEvent.OnToggleAspectRatio)
            IptvRemoteAction.VOLUME_UP, IptvRemoteAction.VOLUME_DOWN, IptvRemoteAction.MUTE -> {
                val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, when (action) {
                    IptvRemoteAction.VOLUME_UP -> android.media.AudioManager.ADJUST_RAISE
                    IptvRemoteAction.VOLUME_DOWN -> android.media.AudioManager.ADJUST_LOWER
                    else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                }, android.media.AudioManager.FLAG_SHOW_UI)
            }
            IptvRemoteAction.BACK -> handleBackPress()
            IptvRemoteAction.EXIT, IptvRemoteAction.STOP -> if (currentOnIptvNavigation != null) navigateIptv("remote:${action.name}") else exitPlayer()
            IptvRemoteAction.NEXT -> navigateIptv("next")
            IptvRemoteAction.PREVIOUS -> navigateIptv("previous")
            else -> if (currentOnIptvNavigation != null) navigateIptv("remote:${action.name}")
                else Toast.makeText(context, "Ouvre le module IPTV pour cette action.", Toast.LENGTH_SHORT).show()
        }
    }
    val currentPerformIptvRemote by rememberUpdatedState(performIptvRemote)

    DisposableEffect(Unit) {
        onDispose {
            Log.i("PlayerScreen", "DISPOSE_RELEASE at=${android.os.SystemClock.elapsedRealtime()}")
            viewModel.stopAndRelease()
        }
    }

    LaunchedEffect(uiState.playbackEnded, uiState.error, uiState.pendingExitReason, shouldShowPlaybackEndOverlay, completionActionPrompt) {
        val explicitReason = uiState.pendingExitReason
        val shouldDispatchNatural = uiState.playbackEnded &&
            uiState.error == null &&
            uiState.postPlayMode?.blocksNaturalCompletion() != true &&
            !shouldShowPlaybackEndOverlay &&
            completionActionPrompt == null &&
            explicitReason == null
        when {
            explicitReason == PlayerExitReason.StillWatchingPrompt -> {
                dispatchPlaybackEnd(null, null, null, PlayerExitReason.StillWatchingPrompt)
                viewModel.consumePendingExitReason()
            }
            shouldDispatchNatural -> {
                val next = uiState.nextEpisode?.takeIf { it.hasAired }
                dispatchPlaybackEnd(next?.videoId, next?.season, next?.episode, null)
            }
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!isExitingPlayer) viewModel.pauseForLifecycle()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Re-create the MediaSession so media controls work in foreground.
                    // Don't auto-resume playback — let the user press play.
                    viewModel.resumeForLifecycle()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Bump UI thread priority to THREAD_PRIORITY_DISPLAY (-4) while the player is active.
    // The Linux scheduler favors the thread under CPU pressure (background addon prefetch,
    // Trakt sync, image decode), reducing dropped frames at scene cuts and during decoder
    // spin-up. Restored on dispose so non-player screens stay at default priority.
    DisposableEffect(Unit) {
        val tid = android.os.Process.myTid()
        val previousPriority = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching {
            android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DISPLAY)
        }
        onDispose {
            runCatching {
                android.os.Process.setThreadPriority(tid, previousPriority)
            }
        }
    }

    // Frame rate matching lifecycle.
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(Unit) {
        viewModel.attachHostActivity(activity)
        viewModel.startInitialPlaybackIfNeeded()
        if (attachedPlayerView != null) viewModel.hideControls()
    }
    DisposableEffect(activity) {
        onDispose {
            viewModel.attachHostActivity(null)
        }
    }
    LaunchedEffect(uiState.frameRateMatchingMode) {
        if (activity != null &&
            uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.OFF
        ) {
            com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
        }
    }
    // Restore original display mode when leaving the player
    DisposableEffect(activity, uiState.frameRateMatchingMode) {
        onDispose {
            if (activity != null) {
                if (uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.START_STOP) {
                    com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
                } else {
                    com.nuvio.tv.core.player.FrameRateUtils.cleanupDisplayListener()
                    com.nuvio.tv.core.player.FrameRateUtils.clearOriginalDisplayMode()
                }
            }
        }
    }

    val showPlayerControls = !isExitingPlayer &&
        uiState.showControls &&
        uiState.error == null &&
        (!uiState.showLoadingOverlay || showControlsDuringLoading) &&
        !uiState.showStreamInfoOverlay &&
        !uiState.showSubtitleStylePanel &&
        !uiState.showSubtitleDelayOverlay &&
        !uiState.showEpisodesPanel &&
        !uiState.showSourcesPanel &&
        !uiState.showAudioOverlay &&
        !uiState.showSubtitleOverlay &&
        !uiState.showSpeedDialog &&
        uiState.postPlayMode !is PostPlayMode.StillWatching

    val canShowPlaybackPrompts = !isExitingPlayer && uiState.error == null &&
        !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
        !uiState.showStreamInfoOverlay && !uiState.showEpisodesPanel && !uiState.showSourcesPanel &&
        !uiState.showAudioOverlay && !uiState.showSubtitleOverlay && !uiState.showSubtitleStylePanel &&
        !uiState.showSubtitleDelayOverlay && !uiState.showSubtitleTimingDialog &&
        !uiState.showSpeedDialog && !uiState.showMoreDialog &&
        !shouldShowPlaybackEndOverlay && completionActionPrompt == null
    val creditsLeadToNextEpisode = uiState.nextEpisode?.hasAired == true &&
        uiState.activeSkipInterval?.let { AutoSkipSegmentType.fromSkipIntervalType(it.type) } == AutoSkipSegmentType.OUTRO
    val canShowSkipButton = canShowPlaybackPrompts && uiState.postPlayMode == null && !creditsLeadToNextEpisode
    val nextEpisodeCardVisible = canShowPlaybackPrompts && uiState.postPlayMode is PostPlayMode.AutoPlay

    LaunchedEffect(uiState.showLoadingOverlay) {
        if (!uiState.showLoadingOverlay) showControlsDuringLoading = false
    }

    LaunchedEffect(showPlayerControls, uiState.showControls, uiState.showLoadingOverlay) {
        if (uiState.showControls) {
            val controlsStateAt = android.os.SystemClock.elapsedRealtime()
            Log.i(
                "PlayerScreen",
                "CONTROLS_COMPOSE at=$controlsStateAt visible=$showPlayerControls loading=${uiState.showLoadingOverlay} paused=${!uiState.isPlaying}"
            )
            if (showPlayerControls) {
                androidx.compose.runtime.withFrameNanos { }
                Log.i(
                    "PlayerScreen",
                    "CONTROLS_FRAME at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - controlsStateAt}ms"
                )
            }
        }
    }

    // Request focus only after the controls bar is actually present.
    LaunchedEffect(
        isExitingPlayer,
        showPlayerControls,
        uiState.showControls,
        uiState.showLoadingOverlay,
        loadingControlsFocusRequestId,
        uiState.showEpisodesPanel,
        uiState.showSourcesPanel,
        uiState.showSubtitleStylePanel,
        uiState.showSubtitleDelayOverlay,
        uiState.showSubtitleTimingDialog,
        uiState.showAudioOverlay,
        uiState.showSubtitleOverlay,
        uiState.showSpeedDialog,
        uiState.showMoreDialog,
        shouldShowPlaybackEndOverlay,
        completionActionPrompt,
        canShowSkipButton,
        skipButtonActuallyVisible && !uiState.showControls,
        nextEpisodeCardVisible && !uiState.showControls,
    ) {
        if (isExitingPlayer) return@LaunchedEffect
        if (uiState.showMoreDialog) return@LaunchedEffect
        if (shouldShowPlaybackEndOverlay || completionActionPrompt != null) return@LaunchedEffect
        if (showPlayerControls && !uiState.showEpisodesPanel && !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay && !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleStylePanel && !uiState.showSubtitleDelayOverlay &&
            !uiState.showSubtitleTimingDialog && !uiState.showSpeedDialog
        ) {
            if (loadingControlsFocusRequestId > 0 && showControlsDuringLoading && uiState.showLoadingOverlay) {
                androidx.compose.runtime.withFrameNanos { }
                runCatching {
                    if (loadingControlsFocusProgressBar) {
                        progressBarFocusRequester.requestFocus()
                    } else {
                        playPauseFocusRequester.requestFocus()
                    }
                }
            } else {
                runCatching { playPauseFocusRequester.requestFocus() }
            }
        } else if (!uiState.showControls && canShowPlaybackPrompts) {
            // When controls are hidden, let skip intro button take focus if visible
            val skipVisible = skipButtonActuallyVisible && canShowSkipButton
            val nextEpisodeVisible = nextEpisodeCardVisible
            if (!skipVisible && !nextEpisodeVisible) {
                try {
                    containerFocusRequester.requestFocus()
                } catch (e: Exception) {
                    // Focus requester may not be ready yet
                }
            }
            // If skip or next episode card is visible, their own LaunchedEffect will request focus
        }
    }

    // Initial focus on container - the LaunchedEffect above will handle focusing controls
    LaunchedEffect(Unit) {
        containerFocusRequester.requestFocus()
    }
    LaunchedEffect(uiState.showSubtitleDelayOverlay) {
        subtitleDelayAutoSyncFocused = false
    }
    LaunchedEffect(uiState.showSubtitleTimingDialog) {
        if (!uiState.showSubtitleTimingDialog) {
            subtitleTimingConsumeNextConfirmKeyUp = false
        }
    }
    LaunchedEffect(uiState.showStreamInfoOverlay, uiState.showControls, uiState.showMoreDialog) {
        if (!uiState.showStreamInfoOverlay && uiState.showControls && uiState.showMoreDialog && restoreStreamInfoFocus) {
            delay(250)
            runCatching { streamInfoFocusRequester.requestFocus() }
            restoreStreamInfoFocus = false
        }
    }

    val remoteKeyActive: (Int) -> Boolean = { code ->
        val panelOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel || uiState.showAudioOverlay ||
            uiState.showSubtitleOverlay || uiState.showSubtitleStylePanel || uiState.showSpeedDialog ||
            uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog || uiState.showMoreDialog ||
            uiState.showStreamInfoOverlay || completionActionPrompt != null || shouldShowPlaybackEndOverlay || uiState.postPlayMode != null
        isIptvAppearance && !isExitingPlayer && uiState.error == null && !panelOpen &&
            (!uiState.showControls || iptvRemotePanel == IptvRemoteAction.INFO || IptvRemote.key(code) !in listOf(19, 20, 21, 22, 23))
    }
    val currentRemoteKeyActive by rememberUpdatedState(remoteKeyActive)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (attachedPlayerView == null) Color.Black else Color.Transparent)
            .focusRequester(containerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (isExitingPlayer) return@onPreviewKeyEvent true
                val event = keyEvent.nativeKeyEvent
                val confirmSkip = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                    !event.isCanceled && skipButtonActuallyVisible && canShowSkipButton && !uiState.showControls &&
                    event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
                // The standalone prompt owns the complete OK press before IPTV remote shortcuts.
                // Keep consuming repeats and UP after the seek removes the active interval.
                if (event.keyCode == skipIntroConfirmKeyCode || confirmSkip) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled &&
                        skipIntroConfirmKeyCode == null) {
                        skipIntroConfirmKeyCode = event.keyCode
                        viewModel.onEvent(PlayerEvent.OnSkipIntro)
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        skipIntroConfirmKeyCode = null
                    }
                    return@onPreviewKeyEvent true
                }
                val remoteActive = currentRemoteKeyActive(event.keyCode)
                if (isIptvAppearance && (remoteActive || iptvRemotePress.owns(event.keyCode)) &&
                    event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val code = IptvRemote.key(event.keyCode)
                    val button = IptvRemote.player.firstOrNull { it.keyCode == code && !it.long }
                    if (button != null) {
                        val action = IptvRemote.action(button, true, iptvAppearance[button.setting(true)])
                        val longButton = IptvRemote.player.firstOrNull { it.keyCode == code && it.long }
                        val longAction = longButton?.let { IptvRemote.action(it, true, iptvAppearance[it.setting(true)]) }
                        val result = iptvRemotePress.handle(code, event.action == KeyEvent.ACTION_DOWN, event.eventTime,
                            action, longAction, event.repeatCount, event.isCanceled)
                        if (remoteActive && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && longAction != null) {
                            val startedAt = event.eventTime
                            val originalCode = event.keyCode
                            iptvRemoteScope.launch {
                                delay(iptvRemotePress.longPressMs)
                                if (!currentRemoteKeyActive(originalCode)) iptvRemotePress.cancel(code, startedAt)
                                else iptvRemotePress.hold(code, startedAt, android.os.SystemClock.uptimeMillis()).action?.let { currentPerformIptvRemote(it, originalCode) }
                            }
                        }
                        if (remoteActive) result.action?.let { performIptvRemote(it, event.keyCode) }
                        return@onPreviewKeyEvent true
                    }
                }
                // Handle media keys before focused controls and side panels can consume them.
                // Own DOWN, repeats and UP so one press cannot also reach the MediaSession.
                val mediaEvent = playerMediaKeyEvent(event.keyCode)
                if (mediaEvent != null) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
                        viewModel.onEvent(mediaEvent)
                    }
                    return@onPreviewKeyEvent true
                }
                if (currentOnIptvNavigation != null && keyEvent.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.nativeKeyEvent.repeatCount == 0)
                        navigateIptv(if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) "next" else "previous")
                    return@onPreviewKeyEvent true
                }
                if (
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                ) {
                    return@onPreviewKeyEvent when (keyEvent.nativeKeyEvent.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) handleBackPress()
                            true
                        }
                        KeyEvent.ACTION_UP -> true
                        else -> true
                    }
                }

                if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_CAPTIONS) {
                    return@onPreviewKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent true
                }

                if (uiState.showSubtitleDelayOverlay) {
                    viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
                } else if (
                    !uiState.showEpisodesPanel &&
                    !uiState.showSourcesPanel &&
                    !uiState.showAudioOverlay &&
                    !uiState.showSubtitleOverlay &&
                    !uiState.showSubtitleStylePanel &&
                    !uiState.showSubtitleTimingDialog &&
                    !uiState.showSpeedDialog
                ) {
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay)
                }
                true
            }
            .onKeyEvent { keyEvent ->
                if (subtitleTimingConsumeNextConfirmKeyUp &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    subtitleTimingConsumeNextConfirmKeyUp = false
                    return@onKeyEvent true
                }
                if (uiState.showSubtitleDelayOverlay) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (subtitleDelayAutoSyncFocused) {
                            when (keyEvent.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER -> {
                                    subtitleDelayAutoSyncFocused = false
                                    subtitleTimingConsumeNextConfirmKeyUp = true
                                    viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    subtitleDelayAutoSyncFocused = false
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    return@onKeyEvent true
                                }
                            }
                        } else {
                            when (keyEvent.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS))
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS))
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    subtitleDelayAutoSyncFocused = true
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    return@onKeyEvent true
                                }
                            }
                        }
                    }
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                        (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ) {
                        return@onKeyEvent true
                    }
                    if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) {
                        // While open, consume all non-back keys to avoid accidental dismissal.
                        return@onKeyEvent true
                    }
                }

                // When a side panel or dialog is open, let it handle all keys
                val panelOrDialogOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel ||
                        uiState.showAudioOverlay || uiState.showSubtitleOverlay ||
                        uiState.showSubtitleStylePanel || uiState.showSpeedDialog ||
                        uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
                        uiState.showMoreDialog ||
                        shouldShowPlaybackEndOverlay ||
                        completionActionPrompt != null ||
                        uiState.postPlayMode is PostPlayMode.StillWatching
                if (panelOrDialogOpen) return@onKeyEvent false

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                                return@onKeyEvent true
                            }
                        }
                    }
                    return@onKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (isPlayerControlsMenuKey(keyEvent.nativeKeyEvent.keyCode)) {
                        if (!uiState.showControls) {
                            viewModel.onEvent(PlayerEvent.OnToggleControls)
                        } else {
                            viewModel.scheduleHideControls()
                            viewModel.onUserInteraction()
                        }
                        return@onKeyEvent true
                    }
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                                true
                            } else {
                                // Let the focused command handle OK.
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                // Android TV sends repeated DOWN events while
                                // the D-pad is held.  Consume every one here so
                                // Left/Right behaves as rewind/fast-forward,
                                // rather than only seeking once on the initial
                                // press.
                                val isLeft = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                viewModel.onEvent(if (isLeft) PlayerEvent.OnSeekBackward else PlayerEvent.OnSeekForward)
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                                if (uiState.showLoadingOverlay) {
                                    showControlsDuringLoading = true
                                    loadingControlsFocusProgressBar = true
                                    loadingControlsFocusRequestId++
                                    if (!uiState.showControls) {
                                        viewModel.onEvent(PlayerEvent.OnToggleControls)
                                    }
                                } else if (!uiState.showControls) {
                                    viewModel.onEvent(PlayerEvent.OnToggleControls)
                                } else {
                                    try {
                                        progressBarFocusRequester.requestFocus()
                                    } catch (_: Exception) {
                                        val skipVisible = skipButtonActuallyVisible
                                        if (skipVisible) {
                                            try {
                                                skipIntroFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else if (uiState.postPlayMode is PostPlayMode.AutoPlay) {
                                            try {
                                                nextEpisodeFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else {
                                            viewModel.hideControls()
                                        }
                                    }
                                }
                                true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (uiState.showLoadingOverlay) {
                                showControlsDuringLoading = true
                                loadingControlsFocusProgressBar = false
                                loadingControlsFocusRequestId++
                                if (!uiState.showControls) {
                                    viewModel.onEvent(PlayerEvent.OnToggleControls)
                                }
                                true
                            } else if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnToggleControls)
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                viewModel.onEvent(PlayerEvent.OnSeekForward)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                viewModel.onEvent(PlayerEvent.OnSeekBackward)
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Video Player
        if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
            MpvPlayerSurface(
                viewModel = viewModel,
                isPlaying = uiState.isPlaying,
                isBuffering = uiState.isBuffering,
                aspectMode = uiState.aspectMode,
                subtitleStyle = uiState.subtitleStyle,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            viewModel.exoPlayer?.let { player ->
                ExoPlayerSurface(
                    player = player,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    isLiveChannel = uiState.contentType.equals("iptv", ignoreCase = true),
                    aspectMode = uiState.aspectMode,
                    useLibass = uiState.useLibass,
                    libassRenderType = uiState.libassRenderType,
                    subtitleStyle = uiState.subtitleStyle,
                    attachedPlayerView = attachedPlayerView,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // For regular video playback the player surface is already mounted while
        // ExoPlayer prepares the first frame. Do not replace it with the full-screen
        // title/build/start sequence; errors remain handled by the error overlay.
        // Torrent streams keep their progress overlay because their preload state
        // conveys useful information before playback can begin.
        LoadingOverlay(
            visible = uiState.error == null &&
                (uiState.showResumeSeekOverlay ||
                    (uiState.showLoadingOverlay && uiState.isTorrentStream)),
            backdropUrl = uiState.backdrop,
            logoUrl = HomeMediaRules.canonicalTitleLogoUrl(uiState.logo),
            title = uiState.title.takeUnless { uiState.isTitleLogoLoading }.orEmpty(),
            message = uiState.loadingMessage.takeIf { uiState.showPlayerLoadingStatus || uiState.isTorrentStream },
            progress = uiState.loadingProgress,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
        )

        StreamInfoOverlay(
            visible = uiState.showStreamInfoOverlay && uiState.error == null && !uiState.showLoadingOverlay,
            onClose = dismissStreamInfoOverlay,
            data = uiState.streamInfoData,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
        )

        // Torrent stats overlay (top-right corner)
        TorrentOverlay(
            visible = uiState.isTorrentStream && uiState.showTorrentStats && !uiState.hideTorrentStats && uiState.error == null,
            downloadSpeed = uiState.torrentDownloadSpeed,
            uploadSpeed = uiState.torrentUploadSpeed,
            peers = uiState.torrentPeers,
            seeds = uiState.torrentSeeds,
            totalProgress = uiState.torrentTotalProgress,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .zIndex(2.7f)
        )

        // Buffering indicator — isolated in its own composable scope so that
        // isBuffering state changes only recompose this small subtree instead
        // of the entire PlayerScreen.
        PlayerBufferingIndicator(
            isBuffering = uiState.isBuffering,
            showLoadingOverlay = uiState.showLoadingOverlay,
            isTorrentStream = uiState.isTorrentStream,
            torrentBufferingMessage = uiState.torrentBufferingMessage,
            torrentBufferingProgress = uiState.torrentBufferingProgress
        )

        // This specific parser error can be emitted on the final packet of a
        // Freebox file. At that point the completion prompt is the meaningful
        // UI; do not let the generic error overlay cover it. Keep all other
        // playback errors visible.
        if (uiState.error != null && !freeboxPlaybackReachedEndAfterError) {
            ErrorOverlay(
                message = uiState.error!!,
                accentColor = errorAccentColor,
                onRetry = { viewModel.onEvent(PlayerEvent.OnRetry) },
                onBack = exitPlayerFromError
            )
        }

        if (shouldShowPlaybackEndOverlay) {
            NextEpisodeEndPromptOverlay(
                nextEpisode = nextEpisodeForEndPrompt,
                onReplay = { viewModel.onEvent(PlayerEvent.OnReplayFromBeginning) },
                onContinue = nextEpisodeForEndPrompt?.let { { continueToNextEpisodeFromEndPrompt() } },
                onReturnToDetails = returnToDetailsFromEndPrompt
            )
        }

        val playbackPromptBottomPadding by animateDpAsState(
            targetValue = if (uiState.showControls) 122.dp else 30.dp,
            animationSpec = tween(durationMillis = 180),
            label = "playbackPromptBottomPadding"
        )

        // Recap and opening skip within this video; closing credits offer the next episode.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 26.dp, bottom = playbackPromptBottomPadding)
                .zIndex(2.1f),
            horizontalAlignment = Alignment.End
        ) {
            SkipIntroButton(
                interval = uiState.activeSkipInterval.takeIf { canShowSkipButton },
                dismissed = uiState.skipIntervalDismissed,
                controlsVisible = uiState.showControls,
                suppressFocus = nextEpisodeCardVisible,
                onSkip = { viewModel.onEvent(PlayerEvent.OnSkipIntro) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSkipIntro) },
                onVisibilityChanged = { skipButtonActuallyVisible = it },
                onFocused = { viewModel.scheduleHideControls() },
                focusRequester = skipIntroFocusRequester,
                downFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
                upFocusRequester = null,
                onHideControls = {
                    if (uiState.showControls) viewModel.hideControls()
                    else viewModel.onEvent(PlayerEvent.OnToggleControls)
                },
            )
            PostPlayOverlay(
                mode = uiState.postPlayMode.takeIf { canShowPlaybackPrompts },
                nextEpisodeFocusRequester = nextEpisodeFocusRequester,
                progressBarFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
                onPlayNext = playNextEpisode,
                onCancelAutoPlay = { viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard) },
                onContinueStillWatching = { viewModel.onEvent(PlayerEvent.OnStillWatchingContinue) },
                onDismissStillWatching = { viewModel.onEvent(PlayerEvent.OnDismissStillWatchingPrompt) },
                seriesTitle = uiState.contentName?.takeIf { it.isNotBlank() } ?: uiState.title,
                releaseYear = uiState.releaseYear,
                controlsVisible = uiState.showControls,
            )
        }

        // Parental guide overlay (shows when video first starts playing)
        ParentalGuideOverlay(
            warnings = uiState.parentalWarnings,
            isVisible = uiState.showParentalGuide,
            onAnimationComplete = {
                viewModel.onEvent(PlayerEvent.OnParentalGuideHide)
            },
            modifier = Modifier.align(Alignment.TopStart)
        )

        DisplayModeOverlay(
            info = uiState.displayModeInfo,
            isVisible = uiState.showDisplayModeInfo,
            onAnimationComplete = {
                viewModel.onEvent(PlayerEvent.OnHideDisplayModeInfo)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(2.2f)
        )

        val showClockOverlay = !isExitingPlayer &&
            (if (isIptvAppearance) iptvAppearance["playerClock"] == "true" ||
                (uiState.showControls && iptvAppearance["playerInfoTime"] != "false")
            else uiState.showControls && uiState.osdClockEnabled) &&
            uiState.error == null &&
            !uiState.showLoadingOverlay &&
            !uiState.showPauseOverlay &&
            !uiState.showEpisodesPanel &&
            !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay &&
            !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleStylePanel &&
            !uiState.showSpeedDialog &&
            !uiState.showMoreDialog &&
            !uiState.showDisplayModeInfo

        AnimatedVisibility(
            visible = showClockOverlay,
            enter = fadeIn(animationSpec = tween(150)),
            exit = if (isExitingPlayer) ExitTransition.None else fadeOut(animationSpec = tween(150)),
            modifier = Modifier
                .align(if (isIptvAppearance && iptvAppearance["playerClock"] == "true") when (iptvAppearance["playerClockPosition"]) {
                    "topLeft" -> Alignment.TopStart; "bottomRight" -> Alignment.BottomEnd
                    "bottomLeft" -> Alignment.BottomStart; else -> Alignment.TopEnd
                } else Alignment.TopEnd)
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .zIndex(2.15f)
        ) {
            PlayerClockOverlayHost(
                viewModel = viewModel,
                playbackSpeed = uiState.playbackSpeed,
                iptvAppearance = if (isIptvAppearance) iptvAppearance else null,
                showInfoDate = uiState.showControls && iptvAppearance["playerInfoDate"] != "false"
            )
        }

        // One player overlay only: metadata and native commands are drawn over
        // the live video surface. No poster/backdrop replaces the current frame.
        AnimatedVisibility(
            visible = !isExitingPlayer && uiState.showControls && (!isIptvAppearance || iptvRemotePanel != IptvRemoteAction.CONTROLS) &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showStreamInfoOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showSubtitleTimingDialog &&
                !uiState.showSpeedDialog &&
                !uiState.showMoreDialog,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier
                .align(if (isIptvAppearance && iptvAppearance["playerInfoCard"] == "true" &&
                    iptvAppearance["playerInfoBottom"] == "true") Alignment.BottomStart else Alignment.TopStart)
                .padding(start = 48.dp, top = 28.dp, end = 48.dp,
                    bottom = if (isIptvAppearance && iptvAppearance["playerInfoCard"] == "true" && iptvAppearance["playerInfoBottom"] == "true") 140.dp else 0.dp)
                .background(if (isIptvAppearance && iptvAppearance["playerInfoCard"] == "true")
                    Color.Black.copy(alpha = iptvPanelOpacity) else Color.Transparent, RoundedCornerShape(8.dp))
                .zIndex(3f)
        ) {
            PlayerControlsMetadataSummary(uiState = uiState, viewModel = viewModel,
                iptvAppearance = if (isIptvAppearance) iptvAppearance else null,
                showDescription = !isIptvAppearance || initialChannelInfo || iptvAppearance["playerInfoDescriptionOnChange"] == "false")
        }

        AnimatedVisibility(
            visible = showPlayerControls && (!isIptvAppearance || iptvRemotePanel != IptvRemoteAction.INFO),
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.zIndex(if (showPlayerControls && uiState.showLoadingOverlay) 2.1f else 0f)
        ) {
            PlayerControlsBarOverlay(
                uiState = uiState,
                viewModel = viewModel,
                interfaceOpacity = if (isIptvAppearance) iptvPanelOpacity else null,
                playPauseFocusRequester = playPauseFocusRequester,
                progressBarFocusRequester = progressBarFocusRequester,
                progressBarUpFocusRequester = when {
                    uiState.postPlayMode is PostPlayMode.AutoPlay -> nextEpisodeFocusRequester
                    skipButtonActuallyVisible -> skipIntroFocusRequester
                    else -> null
                },
                onPlayPause = { viewModel.onEvent(PlayerEvent.OnPlayPause) },
                onReturnToLive = { viewModel.onEvent(PlayerEvent.OnReturnToLive) },
                onPreviousIptvChannel = { navigateIptv("previous") },
                onNextIptvChannel = { navigateIptv("next") },
                onShowIptvNavigation = { navigateIptv("guide") },
                onPlayPreviousEpisode = {
                    val index = uiState.episodesAll.indexOfFirst { it.id == uiState.currentVideoId }
                    val previous = uiState.episodesAll.getOrNull(index - 1)
                        ?.takeIf { uiState.isFreeboxPlayback && it.id.startsWith("freebox:smb:") }
                    if (previous != null) playSmbEpisode(previous)
                    else viewModel.onEvent(PlayerEvent.OnPlayPreviousEpisode)
                },
                onPlayNextEpisode = playNextEpisode,
                onShowEpisodesPanel = { viewModel.onEvent(PlayerEvent.OnShowEpisodesPanel) },
                onRecord = if (isIptvAppearance && currentOnIptvNavigation != null) {
                    { performIptvRemote(IptvRemoteAction.RECORD, KeyEvent.KEYCODE_MEDIA_RECORD) }
                } else null,
                onOpenInExternalPlayer = {
                    viewModel.launchInExternalPlayer(context, viewModel.playbackTimeline.value.currentPosition)
                },
                onShowAudioDialog = { viewModel.onEvent(PlayerEvent.OnShowAudioOverlay) },
                onShowSubtitleDialog = { viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay) },
                onToggleAspectRatio = { viewModel.onEvent(PlayerEvent.OnToggleAspectRatio) },
                onResetHideTimer = {
                    viewModel.scheduleHideControls()
                    viewModel.onUserInteraction()
                },
                onHideControls = { viewModel.hideControls() }
            )
        }

        // Aspect ratio indicator (floating pill)
        AnimatedVisibility(
            visible = uiState.showAspectRatioIndicator,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            AspectRatioIndicator(text = uiState.aspectRatioIndicatorText)
        }

        AnimatedVisibility(
            visible = uiState.showStreamSourceIndicator,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 128.dp)
        ) {
            StreamSourceIndicator(text = uiState.streamSourceIndicatorText)
        }

        AnimatedVisibility(
            visible = uiState.showPlayerEngineSwitchInfo && uiState.error == null,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.Center)
                .zIndex(2.35f)
        ) {
            PlayerEngineSwitchIndicator(
                title = stringResource(R.string.player_engine_switching_title),
                message = uiState.playerEngineSwitchInfoText
            )
        }

        // Seek-only overlay (progress bar + time) when controls are hidden
        AnimatedVisibility(
            visible = uiState.showSubtitleDelayOverlay &&
                !uiState.showControls &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleTimingDialog &&
                !uiState.showSpeedDialog,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .zIndex(2.3f)
        ) {
            SubtitleDelayOverlay(
                subtitleDelayMs = uiState.subtitleDelayMs,
                isAutoSyncButtonFocused = subtitleDelayAutoSyncFocused,
                isSliderFocused = !subtitleDelayAutoSyncFocused,
                onOpenSyncByLine = {
                    subtitleDelayAutoSyncFocused = false
                    subtitleTimingConsumeNextConfirmKeyUp = true
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                }
            )
        }

        AnimatedVisibility(
            visible = uiState.showSeekOverlay && !uiState.showControls && uiState.error == null &&
                !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
                !uiState.showSubtitleDelayOverlay && !uiState.showSubtitleTimingDialog &&
                !uiState.showMoreDialog,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SeekOverlayHost(viewModel = viewModel)
        }

        // Episodes/streams side panel (slides in from right)
        AnimatedVisibility(
            visible = uiState.showEpisodesPanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            // Scrim (fades in/out, no slide)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // Panel itself (slides in from right)
        AnimatedVisibility(
            visible = uiState.showEpisodesPanel && uiState.error == null,
            enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220),
                targetOffsetX = { it }
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                EpisodesSidePanel(
                    uiState = uiState,
                    episodesFocusRequester = episodesFocusRequester,
                    streamsFocusRequester = streamsFocusRequester,
                    onClose = { viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel) },
                    onBackToEpisodes = { viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams) },
                    onReloadEpisodeStreams = { viewModel.onEvent(PlayerEvent.OnReloadEpisodeStreams) },
                    onSeasonSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSeasonSelected(it)) },
                    onAddonFilterSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeAddonFilterSelected(it)) },
                    onEpisodeSelected = {
                        if (uiState.isFreeboxPlayback && it.id.startsWith("freebox:smb:")) playSmbEpisode(it)
                        else viewModel.onEvent(PlayerEvent.OnEpisodeSelected(it))
                    },
                    onStreamSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeStreamSelected(it)) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Sources panel scrim
        AnimatedVisibility(
            visible = uiState.showSourcesPanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // Sources panel (slides in from right)
        AnimatedVisibility(
            visible = uiState.showSourcesPanel && uiState.error == null,
            enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220),
                targetOffsetX = { it }
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                StreamSourcesSidePanel(
                    uiState = uiState,
                    streamsFocusRequester = sourceStreamsFocusRequester,
                    onClose = {
                        if (uiState.currentStreamUrl.isNullOrBlank()) {
                            exitPlayer()
                        } else {
                            viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
                        }
                    },
                    onReload = { viewModel.onEvent(PlayerEvent.OnReloadSourceStreams) },
                    onAddonFilterSelected = { viewModel.onEvent(PlayerEvent.OnSourceAddonFilterSelected(it)) },
                    onStreamSelected = { viewModel.onEvent(PlayerEvent.OnSourceStreamSelected(it)) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Subtitle style panel scrim
        AnimatedVisibility(
            visible = uiState.showSubtitleStylePanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
        }

        // Subtitle style panel
        AnimatedVisibility(
            visible = uiState.showSubtitleStylePanel && uiState.error == null,
            enter = slideInVertically(
                animationSpec = tween(220),
                initialOffsetY = { -it }
            ),
            exit = slideOutVertically(
                animationSpec = tween(220),
                targetOffsetY = { -it }
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                SubtitleStyleSidePanel(
                    subtitleStyle = uiState.subtitleStyle,
                    onEvent = { viewModel.onEvent(it) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                )
            }
        }

        // Audio track dialog
        AudioSelectionOverlay(
            visible = uiState.showAudioOverlay,
            tracks = uiState.audioTracks,
            selectedIndex = uiState.selectedAudioTrackIndex,
            audioDelayMs = uiState.audioDelayMs,
            audioAmplificationDb = uiState.audioAmplificationDb,
            isAmplificationAvailable = uiState.isAudioAmplificationAvailable,
            persistAmplification = uiState.persistAudioAmplification,
            centerMixLevelDb = uiState.centerMixLevelDb,
            isCenterMixAvailable = uiState.isCenterMixAvailable,
            onTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(it)) },
            onAudioDelayChange = { viewModel.onEvent(PlayerEvent.OnSetAudioDelayMs(it)) },
            onAmplificationChange = { viewModel.onEvent(PlayerEvent.OnSetAudioAmplificationDb(it)) },
            onPersistAmplificationChange = {
                viewModel.onEvent(PlayerEvent.OnSetPersistAudioAmplification(it))
            },
            onCenterMixLevelChange = {
                viewModel.onEvent(PlayerEvent.OnSetCenterMixLevelDb(it))
            },
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
        )

        SubtitleSelectionOverlay(
            visible = uiState.showSubtitleOverlay,
            internalTracks = uiState.subtitleTracks,
            selectedInternalIndex = uiState.selectedSubtitleTrackIndex,
            addonSubtitles = uiState.addonSubtitles,
            selectedAddonSubtitle = uiState.selectedAddonSubtitle,
            subtitleStyle = uiState.subtitleStyle,
            subtitleDelayMs = uiState.subtitleDelayMs,
            installedSubtitleAddonOrder = uiState.installedSubtitleAddonOrder,
            isLoadingAddons = uiState.isLoadingAddonSubtitles,
            onInternalTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(it)) },
            onAddonSubtitleSelected = { viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) },
            onDisableSubtitles = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },
            onEvent = { viewModel.onEvent(it) },
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
        )

        PlayerOverlayScaffold(
            visible = uiState.showSubtitleTimingDialog &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showSpeedDialog &&
                !uiState.showMoreDialog,
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.35f),
            captureKeys = false,
            contentPadding = PaddingValues(top = 44.dp)
        ) {
            SubtitleTimingDialogHost(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.TopCenter),
                selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                cues = uiState.subtitleAutoSyncCues,
                capturedVideoMs = uiState.subtitleAutoSyncCapturedVideoMs,
                statusMessage = uiState.subtitleAutoSyncStatus,
                errorMessage = uiState.subtitleAutoSyncError,
                isLoadingCues = uiState.subtitleAutoSyncLoading,
                onCaptureNow = { viewModel.onEvent(PlayerEvent.OnCaptureSubtitleAutoSyncTime) },
                onCueSelected = { cue ->
                    viewModel.onEvent(PlayerEvent.OnApplySubtitleAutoSyncCue(cue.startTimeMs))
                }
            )
        }

        if (isIptvAppearance && uiState.showMoreDialog) {
            val commands = linkedMapOf<String, Pair<String, () -> Unit>>()
            fun command(id: String, label: String, action: IptvRemoteAction) {
                commands[id] = label to {
                    viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                    performIptvRemote(action, KeyEvent.KEYCODE_MENU)
                }
            }
            if (currentOnIptvNavigation != null) {
                command("search", "Rechercher", IptvRemoteAction.SEARCH)
                command("channels", "Liste des Chaînes", IptvRemoteAction.CHANNELS_OVERLAY)
                command("record", "Enregistrements", IptvRemoteAction.RECORDINGS)
                command("favorite", "Changer le statut des Favoris", IptvRemoteAction.FAVORITE)
                command("settings", "Paramètres", IptvRemoteAction.SETTINGS)
                command("history", "Historique", IptvRemoteAction.HISTORY)
                command("movies", "Films", IptvRemoteAction.MOVIES)
                command("series", "Séries", IptvRemoteAction.SERIES)
                command("myList", "Ma liste", IptvRemoteAction.MY_LIST)
                command("exit", "Sortir", IptvRemoteAction.EXIT)
            }
            commands["video"] = "Informations vidéo" to {
                viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                viewModel.onEvent(PlayerEvent.OnShowStreamInfo)
            }
            command("audio", "Pistes audio", IptvRemoteAction.AUDIO)
            command("audioDelay", "Décalage Audio", IptvRemoteAction.AUDIO)
            command("subtitles", "Sous-titres", IptvRemoteAction.SUBTITLES)
            command("display", "Mode d'Affichage", IptvRemoteAction.ASPECT)
            val order = IptvRemote.visiblePlayerMenu(iptvAppearance)
            MoreActionsDialog(
                onPlaybackSpeed = { viewModel.onEvent(PlayerEvent.OnDismissMoreDialog); viewModel.onEvent(PlayerEvent.OnShowSpeedDialog) },
                onToggleAspectRatio = { viewModel.onEvent(PlayerEvent.OnDismissMoreDialog); viewModel.onEvent(PlayerEvent.OnToggleAspectRatio) },
                onOpenInExternalPlayer = {
                    viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                    viewModel.launchInExternalPlayer(context, viewModel.playbackTimeline.value.currentPosition)
                },
                onDismiss = {
                    viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                },
                includeAspectRatio = false,
                additionalActions = order.mapNotNull(commands::get)
            )
        }

        if (uiState.showSpeedDialog) {
            SpeedSelectionDialog(
                currentSpeed = uiState.playbackSpeed,
                onSpeedSelected = { viewModel.onEvent(PlayerEvent.OnSetPlaybackSpeed(it)) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) }
            )
        }

        completionActionPrompt?.let { prompt ->
            CompletionActionDialog(
                prompt = prompt,
                isWorking = prompt == CompletionActionPrompt.FREEBOX_DELETE && isFreeboxDeletionInProgress,
                onDecline = { dispatchPlaybackEnd(null, null, null, null) },
                onConfirm = {
                    when (prompt) {
                        CompletionActionPrompt.FREEBOX_DELETE -> {
                            viewModel.deleteCompletedFreeboxVideo { deletedFileName ->
                                Toast.makeText(
                                    context,
                                    freeboxDeleteSuccessMessage.format(deletedFileName),
                                    Toast.LENGTH_LONG
                                ).show()
                                dispatchPlaybackEnd(null, null, null, null)
                            }
                        }
                    }
                }
            )
        }
    }
}

private enum class CompletionActionPrompt {
    FREEBOX_DELETE
}

@Composable
private fun CompletionActionDialog(
    prompt: CompletionActionPrompt,
    isWorking: Boolean,
    onDecline: () -> Unit,
    onConfirm: () -> Unit,
) {
    val noFocusRequester = remember { FocusRequester() }
    val yesFocusRequester = remember { FocusRequester() }
    var noFocused by remember { mutableStateOf(false) }
    var yesFocused by remember { mutableStateOf(false) }
    val actionButtonShape = RoundedCornerShape(12.dp)
    val noBackground by animateColorAsState(
        targetValue = if (noFocused) NuvioColors.Secondary else Color.Transparent,
        label = "completionNoButtonBackground"
    )
    val yesBackground by animateColorAsState(
        targetValue = if (yesFocused) NuvioColors.Secondary else Color.Transparent,
        label = "completionYesButtonBackground"
    )
    LaunchedEffect(Unit) { noFocusRequester.requestFocus() }
    val message = when (prompt) {
        CompletionActionPrompt.FREEBOX_DELETE -> "Supprimer de la Freebox ?"
    }

    Dialog(
        onDismissRequest = { if (!isWorking) onDecline() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(NuvioColors.BackgroundCard)
                .border(1.dp, NuvioColors.FocusRing.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier.width(340.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Lecture terminée",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        onClick = { if (!isWorking) onDecline() },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(noFocusRequester)
                            .onFocusChanged { noFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = noBackground,
                            focusedContainerColor = noBackground
                        ),
                        border = CardDefaults.border(
                            border = Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(1.5.dp, Color.Transparent),
                                shape = actionButtonShape
                            )
                        ),
                        shape = CardDefaults.shape(shape = actionButtonShape),
                        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                    ) {
                        Text(
                            text = "NON",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (noFocused) NuvioColors.OnSecondary else NuvioColors.TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Card(
                        onClick = { if (!isWorking) onConfirm() },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(yesFocusRequester)
                            .onFocusChanged { yesFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = yesBackground,
                            focusedContainerColor = yesBackground
                        ),
                        border = CardDefaults.border(
                            border = Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(1.5.dp, Color.Transparent),
                                shape = actionButtonShape
                            )
                        ),
                        shape = CardDefaults.shape(shape = actionButtonShape),
                        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                    ) {
                        Text(
                            text = if (isWorking) "SUPPRESSION…" else "OUI",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (yesFocused) NuvioColors.OnSecondary else NuvioColors.TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MpvPlayerSurface(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    subtitleStyle: SubtitleStyleSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val mpvView = remember(context) {
        NuvioMpvSurfaceView(context)
    }

    AndroidView(
        factory = { mpvView },
        modifier = modifier
    )

    DisposableEffect(viewModel, mpvView) {
        viewModel.attachMpvView(mpvView)
        onDispose {
            viewModel.attachMpvView(null)
        }
    }

    DisposableEffect(mpvView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            mpvView.applyAspectMode(latestAspectMode)
        }
        mpvView.addOnLayoutChangeListener(listener)
        onDispose {
            mpvView.removeOnLayoutChangeListener(listener)
        }
    }

    LaunchedEffect(mpvView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (mpvView.keepScreenOn != shouldKeepScreenOn) {
            mpvView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(mpvView, aspectMode) {
        mpvView.applyAspectMode(aspectMode)
    }

    LaunchedEffect(mpvView, subtitleStyle) {
        mpvView.applySubtitleStyle(subtitleStyle)
    }
}

@Composable
private fun ExoPlayerSurface(
    player: ExoPlayer,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isLiveChannel: Boolean,
    aspectMode: AspectMode,
    useLibass: Boolean,
    libassRenderType: LibassRenderType,
    subtitleStyle: SubtitleStyleSettings,
    attachedPlayerView: PlayerView? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val playerView = attachedPlayerView ?: remember(context, player, isLiveChannel) {
        val view = if (isLiveChannel) {
            LayoutInflater.from(context)
                .inflate(R.layout.player_view_live, null, false) as PlayerView
        } else {
            PlayerView(context)
        }
        view.apply {
            useController = false
            keepScreenOn = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            enableComposeSurfaceSyncWorkaroundIfAvailable()
            this.player = player
        }
    }

    if (attachedPlayerView == null) AndroidView(
        factory = { playerView },
        modifier = modifier,
        update = {
            it.syncLibassOverlay(
                player = player,
                enabled = useLibass,
                renderType = libassRenderType
            )
        }
    )

    DisposableEffect(playerView, player) {
        if (playerView.player !== player) {
            playerView.player = player
        }
        onDispose {
            if (attachedPlayerView == null && playerView.player === player) {
                val detachStartedAt = android.os.SystemClock.elapsedRealtime()
                Log.i("PlayerScreen", "SURFACE_DETACH_BEGIN at=$detachStartedAt")
                playerView.player = null
                Log.i("PlayerScreen", "SURFACE_DETACH_END at=${android.os.SystemClock.elapsedRealtime()} elapsedMs=${android.os.SystemClock.elapsedRealtime() - detachStartedAt}")
            }
        }
    }

    DisposableEffect(player, playerView) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                playerView.post {
                    playerView.applyExoAspectMode(latestAspectMode)
                }
            }

            override fun onRenderedFirstFrame() {
                playerView.post {
                    playerView.applyExoAspectMode(latestAspectMode)
                }
            }
        }
        player.addListener(listener)
        playerView.post {
            playerView.applyExoAspectMode(latestAspectMode)
        }
        onDispose {
            player.removeListener(listener)
        }
    }

    DisposableEffect(playerView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            playerView.post {
                playerView.applyExoAspectMode(latestAspectMode)
            }
        }
        val removeListener = addExoAspectLayoutChangeListener(playerView, listener)
        onDispose {
            removeListener()
        }
    }

    LaunchedEffect(playerView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (playerView.keepScreenOn != shouldKeepScreenOn) {
            playerView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(playerView, aspectMode) {
        playerView.applyExoAspectMode(aspectMode)
    }

    LaunchedEffect(playerView, player, useLibass, libassRenderType) {
        playerView.syncLibassOverlay(
            player = player,
            enabled = useLibass,
            renderType = libassRenderType
        )
    }

    LaunchedEffect(playerView, subtitleStyle) {
        playerView.applySubtitleStyleIfNeeded(subtitleStyle)
    }
}

private fun PlayerView.enableComposeSurfaceSyncWorkaroundIfAvailable() {
    runCatching {
        javaClass
            .getMethod("setEnableComposeSurfaceSyncWorkaround", java.lang.Boolean.TYPE)
            .invoke(this, true)
    }
}

private fun PlayerView.applyExoAspectMode(mode: AspectMode) {
    setTag(R.id.player_view_aspect_mode_tag, mode)
    applyExoAspectMode(this, mode)
}

private fun PlayerView.applySubtitleStyleIfNeeded(subtitleStyle: SubtitleStyleSettings) {
    if (getTag(R.id.player_view_subtitle_style_tag) == subtitleStyle) {
        return
    }
    setTag(R.id.player_view_subtitle_style_tag, subtitleStyle)
    subtitleView?.apply {
        val baseFontSize = 24f
        val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
        setApplyEmbeddedFontSizes(false)

        val typeface = if (subtitleStyle.bold) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }

        val edgeType = if (subtitleStyle.outlineEnabled) {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }

        setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                subtitleStyle.textColor,
                subtitleStyle.backgroundColor,
                android.graphics.Color.TRANSPARENT,
                edgeType,
                subtitleStyle.outlineColor,
                typeface
            )
        )

        setApplyEmbeddedStyles(false)

        val bottomPaddingFraction =
            (0.06f + (subtitleStyle.verticalOffset / 250f)).coerceIn(0f, 0.4f)
        setBottomPaddingFraction(bottomPaddingFraction)

        post {
            val extraPadding = (height * (subtitleStyle.verticalOffset / 400f)).toInt().coerceAtLeast(0)
            setPadding(paddingLeft, paddingTop, paddingRight, extraPadding)
        }
    }
}

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderType: LibassRenderType
) {
    val containerId = if (renderType == LibassRenderType.OVERLAY_OPEN_GL) {
        R.id.libass_overlay_container_gl
    } else {
        R.id.libass_overlay_container
    }
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    val needsOverlay = enabled && renderType.usesOverlaySubtitleView()
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    val assSubtitleView = AssSubtitleView(overlayContainer.context, assHandler)
    overlayContainer.addView(
        assSubtitleView,
        android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    setTag(R.id.libass_overlay_bound_player, player)
}

private fun LibassRenderType.usesOverlaySubtitleView(): Boolean {
    return this == LibassRenderType.OVERLAY_CANVAS || this == LibassRenderType.OVERLAY_OPEN_GL
}

private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        if (getChildAt(index) is AssSubtitleView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        if (getChildAt(index) is AssSubtitleView) {
            removeViewAt(index)
        }
    }
}

@Composable
private fun RecordingChoiceDialog(
    onDismiss: () -> Unit,
    onRecordNow: () -> Unit,
    onSchedule: () -> Unit,
) {
    val firstActionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstActionFocusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NuvioColors.BackgroundElevated)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Enregistrer cette chaîne",
                color = NuvioColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Choisissez quand démarrer l'enregistrement.",
                color = NuvioColors.TextSecondary,
                fontSize = 14.sp,
            )
            Button(
                onClick = onRecordNow,
                modifier = Modifier.fillMaxWidth().focusRequester(firstActionFocusRequester),
            ) {
                Text("Enregistrer maintenant")
            }
            Button(
                onClick = onSchedule,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(containerColor = NuvioColors.BackgroundCard),
            ) {
                Text("Programmer une heure")
            }
        }
    }
}

@Composable
private fun PlayerControlsBarOverlay(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    playPauseFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester,
    progressBarUpFocusRequester: FocusRequester? = null,
    interfaceOpacity: Float? = null,
    onPlayPause: () -> Unit,
    onReturnToLive: () -> Unit,
    onPreviousIptvChannel: () -> Unit,
    onNextIptvChannel: () -> Unit,
    onShowIptvNavigation: () -> Unit,
    onPlayPreviousEpisode: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onShowEpisodesPanel: () -> Unit,
    onRecord: (() -> Unit)?,
    onOpenInExternalPlayer: () -> Unit,
    onShowAudioDialog: () -> Unit,
    onShowSubtitleDialog: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val customPlayPainter = rememberRawSvgPainter(R.raw.ic_player_play)
    val customPausePainter = rememberRawSvgPainter(R.raw.ic_player_pause)
    val customSubtitlePainter = rememberRawSvgPainter(R.raw.ic_player_subtitles)
    val customAudioPainter = rememberRawSvgPainter(R.raw.ic_player_audio_filled)
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)
    val customEpisodesPainter = rememberRawSvgPainter(R.raw.ic_player_episodes)

    Box(modifier = Modifier.fillMaxSize()) {
        if (!uiState.showLoadingOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.76f)
                    .align(Alignment.CenterStart)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = interfaceOpacity ?: 0.82f),
                                Color.Black.copy(alpha = interfaceOpacity?.times(.7f) ?: 0.56f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = interfaceOpacity ?: 0.8f))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                PlayerControlsProgressBarHost(
                    viewModel = viewModel,
                    focusRequester = progressBarFocusRequester,
                    upFocusRequester = progressBarUpFocusRequester,
                    downFocusRequester = playPauseFocusRequester,
                    onUpKey = onHideControls,
                    onFocused = onResetHideTimer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasEpisodeContext = uiState.currentSeason != null && uiState.currentEpisode != null
                    val isLiveChannel = uiState.contentType.equals("iptv", ignoreCase = true)
                    val canPlayPreviousEpisode = uiState.hasPreviousEpisode()
                    val canPlayNextEpisode = uiState.nextEpisode?.hasAired == true &&
                        (uiState.postPlayMode as? PostPlayMode.AutoPlay)?.searching != true
                    val showPreviousEpisodeButton = !isLiveChannel && canPlayPreviousEpisode
                    val showNextEpisodeButton = !isLiveChannel && canPlayNextEpisode
                    val useCompactControlButtons = !isLiveChannel &&
                        (hasEpisodeContext || (canPlayPreviousEpisode && uiState.nextEpisode != null))
                    val hasSubtitleControl = uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()
                    val hasAudioControl = uiState.audioTracks.isNotEmpty()
                    val selectedAudioTrack = uiState.audioTracks.firstOrNull { it.index == uiState.selectedAudioTrackIndex }
                        ?: uiState.audioTracks.firstOrNull { it.isSelected }
                    val selectedSubtitleTrack = uiState.subtitleTracks.firstOrNull { it.index == uiState.selectedSubtitleTrackIndex }
                        ?: uiState.subtitleTracks.firstOrNull { it.isSelected }
                    val audioStatus = formatAudioControlStatus(selectedAudioTrack, uiState.streamInfoData)
                    val subtitleStatus = formatSubtitleControlStatus(
                        selectedTrack = selectedSubtitleTrack,
                        selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                        streamInfoData = uiState.streamInfoData
                    )
                    val resolutionStatus = formatResolutionControlStatus(uiState.streamInfoData)
                    val resolutionBadge = formatResolutionControlBadge(uiState.streamInfoData)
                    val aspectStatus = aspectModeLabel(uiState.aspectMode, ::playerControlAspectLabel)

                    Row(
                        modifier = Modifier.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showPreviousEpisodeButton) {
                            ControlButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Episode précédent",
                                onClick = onPlayPreviousEpisode,
                                enabled = canPlayPreviousEpisode,
                                label = "Précédent",
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer,
                                compact = useCompactControlButtons
                            )
                        }

                        ControlButton(
                            icon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            iconPainter = if (uiState.isPlaying) customPausePainter else customPlayPainter,
                            contentDescription = if (uiState.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                            onClick = onPlayPause,
                            label = if (uiState.isPlaying) "Pause" else "Lecture",
                            focusRequester = playPauseFocusRequester,
                            upFocusRequester = progressBarFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer,
                            compact = useCompactControlButtons
                        )

                        if (isLiveChannel) {
                            ControlButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Chaîne précédente",
                                onClick = onPreviousIptvChannel,
                                label = "Chaîne -",
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer,
                                compact = useCompactControlButtons
                            )
                            ControlButton(
                                icon = Icons.Default.LiveTv,
                                contentDescription = "Revenir au direct",
                                onClick = onReturnToLive,
                                label = "Direct",
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer,
                                compact = useCompactControlButtons
                            )
                            ControlButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = "Chaîne suivante",
                                onClick = onNextIptvChannel,
                                label = "Chaîne +",
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer,
                                compact = useCompactControlButtons
                            )
                            ControlButton(
                                icon = Icons.AutoMirrored.Filled.List,
                                contentDescription = "Navigation des favoris",
                                onClick = onShowIptvNavigation,
                                label = "Navigation",
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer,
                                compact = useCompactControlButtons
                            )
                        }

                        if (showNextEpisodeButton) {
                            ControlButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = stringResource(R.string.next_episode_label),
                                onClick = onPlayNextEpisode,
                                enabled = canPlayNextEpisode,
                                label = "Suivant",
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer,
                                compact = useCompactControlButtons
                            )
                        }

                        val appearanceContext = LocalContext.current
                        val appearance = remember(isLiveChannel) {
                            if (isLiveChannel) com.jojotv.home.iptv.IptvStore.playerAppearance(appearanceContext, "iptv:") else emptyMap()
                        }
                        val defaultOrder = listOf("record", "resolution", "audio", "subtitles", "episodes", "external", "display")
                        val commandOrder = if (isLiveChannel) com.jojotv.home.iptv.IptvStore.playerMenuOrder(
                            appearance["playerMenuOrder"] ?: "[]", defaultOrder) else defaultOrder
                        commandOrder.forEach { command ->
                            val enabled = !isLiveChannel || when (command) {
                                "resolution" -> appearance["playerInfoMedia"] != "false"
                                else -> appearance["playerMenu:$command"] != "false"
                            }
                            if (enabled) when (command) {
                                "record" -> {
                                    onRecord?.let { record ->
                                        ControlButton(
                                            icon = Icons.Default.FiberManualRecord,
                                            contentDescription = "Enregistrer",
                                            onClick = record,
                                            label = "Enregistrer",
                                            upFocusRequester = progressBarFocusRequester,
                                            onDownKey = onHideControls,
                                            onFocused = onResetHideTimer,
                                            compact = useCompactControlButtons
                                        )
                                    }
                                }
                                "resolution" -> {
                                    ControlButton(
                                        icon = Icons.Default.AspectRatio,
                                        contentDescription = "Résolution",
                                        onClick = onResetHideTimer,
                                        label = "Résolution",
                                        value = resolutionStatus,
                                        textIcon = if (isLiveChannel && appearance["playerInfoResolution"] == "true") resolutionStatus else resolutionBadge,
                                        upFocusRequester = progressBarFocusRequester,
                                        onDownKey = onHideControls,
                                        onFocused = onResetHideTimer,
                                        compact = useCompactControlButtons
                                    )
                                }
                                "audio" -> {
                                    if (hasAudioControl) {
                                        ControlButton(
                                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                                            iconPainter = customAudioPainter,
                                            contentDescription = stringResource(R.string.cd_audio_tracks),
                                            onClick = onShowAudioDialog,
                                            label = "Audio",
                                            value = audioStatus,
                                            upFocusRequester = progressBarFocusRequester,
                                            onDownKey = onHideControls,
                                            onFocused = onResetHideTimer,
                                            compact = useCompactControlButtons
                                        )
                                    }
                                }
                                "subtitles" -> {
                                    if (hasSubtitleControl) {
                                        ControlButton(
                                            icon = Icons.Default.ClosedCaption,
                                            iconPainter = customSubtitlePainter,
                                            contentDescription = stringResource(R.string.cd_subtitles),
                                            onClick = onShowSubtitleDialog,
                                            label = "Sous-titres",
                                            value = subtitleStatus,
                                            upFocusRequester = progressBarFocusRequester,
                                            onDownKey = onHideControls,
                                            onFocused = onResetHideTimer,
                                            compact = useCompactControlButtons
                                        )
                                    }
                                }
                                "episodes" -> {
                                    if (hasEpisodeContext) {
                                        ControlButton(
                                            icon = Icons.AutoMirrored.Filled.List,
                                            iconPainter = customEpisodesPainter,
                                            contentDescription = stringResource(R.string.cd_episodes),
                                            onClick = onShowEpisodesPanel,
                                            label = "Episodes",
                                            upFocusRequester = progressBarFocusRequester,
                                            onDownKey = onHideControls,
                                            onFocused = onResetHideTimer,
                                            compact = useCompactControlButtons
                                        )
                                    }
                                }
                                "external" -> {
                                    ControlButton(
                                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.cd_open_external_player),
                                        onClick = onOpenInExternalPlayer,
                                        label = stringResource(R.string.player_open_external_label),
                                        labelMaxLines = 2,
                                        upFocusRequester = progressBarFocusRequester,
                                        onDownKey = onHideControls,
                                        onFocused = onResetHideTimer,
                                        compact = useCompactControlButtons
                                    )
                                }
                                "display" -> {
                                    ControlButton(
                                        icon = Icons.Default.AspectRatio,
                                        iconPainter = customAspectPainter,
                                        contentDescription = stringResource(R.string.cd_aspect_ratio),
                                        onClick = onToggleAspectRatio,
                                        label = "Ajustement",
                                        value = aspectStatus,
                                        upFocusRequester = progressBarFocusRequester,
                                        onDownKey = onHideControls,
                                        onFocused = onResetHideTimer,
                                        compact = useCompactControlButtons
                                    )
                                }
                            }
                        }
                    }

                    PlayerControlsTimeTextHost(viewModel = viewModel)
                }
            }
        }
    }
}

private fun PlayerUiState.hasPreviousEpisode(): Boolean {
    val season = currentSeason ?: return false
    val episode = currentEpisode ?: return false
    val orderedEpisodes = episodesAll
        .filter { it.season != null && it.episode != null }
        .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
    return orderedEpisodes.indexOfFirst { it.season == season && it.episode == episode } > 0
}

@Composable
private fun PlayerControlsProgressBarHost(
    viewModel: PlayerViewModel,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    var previewPosition by remember { mutableStateOf<Long?>(null) }
    ProgressBar(
            currentPosition = playbackTimeline.currentPosition,
            duration = playbackTimeline.duration,
            onSeekPreview = { delta ->
                viewModel.onEvent(if (delta < 0L) PlayerEvent.OnSeekBackward else PlayerEvent.OnSeekForward)
            },
            onSeekCommit = {},
            onSeekToPosition = { position -> viewModel.onEvent(PlayerEvent.OnSeekTo(position)) },
            onPreviewPositionChanged = { position -> previewPosition = position },
            focusRequester = focusRequester,
            upFocusRequester = upFocusRequester,
            downFocusRequester = downFocusRequester,
            onUpKey = onUpKey,
            onFocused = onFocused,
            bufferedPosition = playbackTimeline.bufferedPosition
        )
}

@Composable
private fun PlayerControlsTimeTextHost(viewModel: PlayerViewModel) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    Text(
        text = "${formatTime(playbackTimeline.currentPosition)} / ${formatTime(playbackTimeline.duration)}",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.9f)
    )
}

@Composable
private fun PlayerControlsMetadataSummary(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    iptvAppearance: Map<String, String>? = null,
    showDescription: Boolean = true,
    modifier: Modifier = Modifier
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    val playbackRuntimeMinutes = if (!uiState.contentType.equals("iptv", ignoreCase = true)) {
        val durationMs = playbackTimeline.duration
        (durationMs / 60_000L).takeIf {
            durationMs > 0L && it in 1L..Int.MAX_VALUE.toLong()
        }?.toInt()
    } else {
        null
    }
    val displayedRuntimeMinutes = playbackRuntimeMinutes ?: uiState.runtimeMinutes
    val visibleCredits = uiState.castMembers
        .filter { it.name.isNotBlank() }
        .distinctBy { it.tmdbId?.toString() ?: it.name.trim().lowercase(Locale.ROOT) }
        .take(6)
    LaunchedEffect(visibleCredits) {
        android.util.Log.i(
            "CastPhotoTrace",
            "PLAYER_UI_CAST count=${visibleCredits.size} " +
                "photos=${visibleCredits.count { !it.photo.isNullOrBlank() }}"
        )
    }
    val logo = HomeMediaRules.canonicalTitleLogoUrl(uiState.logo).takeIf { it.isNotBlank() }
    val context = LocalContext.current
    var titleLogoBitmap by remember(logo) { mutableStateOf(OnlineAppIconLoader.cachedTitleLogoBitmap(logo)) }
    var titleLogoFailed by remember(logo) { mutableStateOf(false) }
    DisposableEffect(logo) {
        var active = true
        if (logo != null && titleLogoBitmap == null) OnlineAppIconLoader.loadTitleLogoBitmap(context, logo) { bitmap ->
            if (active) { titleLogoBitmap = bitmap; titleLogoFailed = bitmap == null }
        }
        onDispose { active = false }
    }
    val statusLabel = playerMetadataStatusLabel(uiState.status, uiState.contentType, uiState.releaseDate)

    Column(
        modifier = modifier.fillMaxWidth(0.74f),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (logo != null && !titleLogoFailed) {
            val logoModifier = Modifier.width(260.dp).height(62.dp)
            val bitmap = titleLogoBitmap
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = logoModifier)
            } else {
                Spacer(modifier = logoModifier)
            }
        } else if (uiState.isTitleLogoLoading) {
            Spacer(modifier = Modifier.width(260.dp).height(62.dp))
        } else {
            Text(
                text = playerControlsDisplayTitle(uiState.title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (uiState.director.isNotEmpty()) {
            Text(
                text = "Réalisateur : ${uiState.director.take(3).joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        uiState.description?.takeIf { it.isNotBlank() && showDescription && iptvAppearance?.get("playerInfoDescription") != "false" }?.let { description ->
            Text(
                text = shortPlayerSynopsis(description),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        val metaLine = buildList {
            if (!uiState.isFreeboxVideosFolderContent) {
                uiState.rating?.takeIf { it > 0f }
                    ?.let { "%.1f".format(Locale.getDefault(), it) }
                    ?.let(::add)
            }
            uiState.genres.filter { it.isNotBlank() }.distinct().take(3)
                .joinToString(" · ").takeIf { it.isNotBlank() }?.let(::add)
            uiState.releaseYear?.takeIf { it.isNotBlank() }?.let(::add)
            displayedRuntimeMinutes?.takeIf { it > 0 }
                ?.let(::formatPlayerRuntime)
                ?.let(::add)
            if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                compactSeasonEpisodeLabel(uiState.currentSeason, uiState.currentEpisode)?.let(::add)
            }
        }.joinToString("  •  ")
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val secondaryItems = buildList {
            uiState.country?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            uiState.contentLanguage?.trim()?.takeIf { it.isNotBlank() }
                ?.uppercase(Locale.ROOT)
                ?.let(::add)
        }.distinctBy { it.trim().uppercase(Locale.ROOT) }
        if (!uiState.ageRating.isNullOrBlank() || statusLabel != null || secondaryItems.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ageAndStatus = listOfNotNull(
                    uiState.ageRating?.trim()?.takeIf { it.isNotBlank() },
                    statusLabel
                ).joinToString("  |  ")
                if (ageAndStatus.isNotBlank()) {
                    Text(
                        text = ageAndStatus,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .border(1.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
                secondaryItems.forEach { value ->
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }

        if (visibleCredits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (visibleCredits.any { HomeMediaRules.isTmdbPresenter(it.character) }) "Pr\u00e9sentation" else "Créateur et distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val castSpacing = 10.dp
                val availableCardWidth = (
                    (maxWidth - castSpacing * (visibleCredits.size - 1).toFloat()) /
                        visibleCredits.size.toFloat()
                    ).coerceAtMost(112.dp)
                val portraitSize = 72.dp.coerceAtMost(availableCardWidth)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(castSpacing),
                    verticalAlignment = Alignment.Top
                ) {
                    visibleCredits.forEach { member ->
                        PlayerInfoCastMember(
                            member = member,
                            cardWidth = availableCardWidth,
                            portraitSize = portraitSize
                        )
                    }
                }
            }
        }
    }
}

/** Keep the player synopsis at the same first-sentence length as the Home hero. */
private fun shortPlayerSynopsis(value: String): String {
    val synopsis = value.trim().replace(Regex("\\s+"), " ")
    if (synopsis.length <= 480) return synopsis
    val sentenceEnd = (80 until minOf(480, synopsis.length))
        .firstOrNull { synopsis[it] == '.' || synopsis[it] == '!' || synopsis[it] == '?' }
    if (sentenceEnd != null) return synopsis.substring(0, sentenceEnd + 1).trim()
    val cut = synopsis.substring(0, 480).lastIndexOf(' ')
    return synopsis.substring(0, if (cut > 0) cut else 480).trim() + "…"
}

@Composable
private fun playerMetadataStatusLabel(
    status: String?,
    contentType: String?,
    releaseDate: String?
): String? {
    val normalized = status?.trim()?.lowercase(Locale.ROOT) ?: return null
    val isSeries = contentType.equals("series", ignoreCase = true) ||
        contentType.equals("tv", ignoreCase = true) ||
        contentType.equals("iptv_series", ignoreCase = true)
    val labelRes = when (normalized) {
        "ended" -> if (isSeries) R.string.series_status_ended else R.string.movie_status_ended
        "continuing", "returning series" -> if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing
        "current" -> if (isSeries) R.string.series_status_current else R.string.movie_status_current
        "cancelled", "canceled" -> if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled
        "released" -> if (isSeries) R.string.series_status_released else R.string.movie_status_released
        "planned" -> if (isSeries) R.string.series_status_planned else R.string.movie_status_planned
        "rumored" -> if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored
        "in production" -> if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production
        "post production" -> if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production
        else -> return status.trim().takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    }
    val label = stringResource(labelRes).uppercase(Locale.getDefault())
    return if (normalized == "released") {
        formatPlayerReleaseDate(releaseDate)?.let { date -> "$label LE $date" } ?: label
    } else {
        label
    }
}

private fun formatPlayerReleaseDate(rawDate: String?): String? {
    val parts = rawDate?.trim()?.take(10)?.split('-') ?: return null
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val months = arrayOf(
        "JANVIER", "FÉVRIER", "MARS", "AVRIL", "MAI", "JUIN",
        "JUILLET", "AOÛT", "SEPTEMBRE", "OCTOBRE", "NOVEMBRE", "DÉCEMBRE"
    )
    return "$day ${months[month - 1]} $year"
}

@Composable
private fun PlayerInfoCastMember(
    member: MetaCastMember,
    cardWidth: Dp,
    portraitSize: Dp
) {
    val context = LocalContext.current
    val photoModel = remember(context, member.photo) {
        member.photo?.takeIf { it.isNotBlank() }?.let { photo ->
            ImageRequest.Builder(context)
                .data(photo.replace("https://image.tmdb.org/t/p/w185/", "https://image.tmdb.org/t/p/w500/"))
                .size(512, 768)
                .scale(Scale.FIT)
                .allowHardware(false)
                .transformations(PlayerCastHeadTransformation())
                .build()
        }
    }
    Column(
        modifier = Modifier.width(cardWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(portraitSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            if (photoModel != null) {
                AsyncImage(
                    model = photoModel,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = member.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 32.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        member.character?.takeIf { it.isNotBlank() }?.let { role ->
            Text(
                text = if (HomeMediaRules.isTmdbPresenter(role)) "Pr\u00e9sentation" else role,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class PlayerCastHeadFrame(val left: Float, val top: Float, val side: Float)

private fun playerCastHeadFrame(eyeX: Float, eyeY: Float, eyeDistance: Float,
                                imageWidth: Int, imageHeight: Int): PlayerCastHeadFrame? {
    if (!eyeX.isFinite() || !eyeY.isFinite() || !eyeDistance.isFinite() || eyeDistance <= 0f
        || imageWidth <= 0 || imageHeight <= 0) return null
    // Eye spacing gives the same head size for a close portrait and a wider shot.
    // Leave room above the eyes for hair and below them for the chin inside the circle.
    // Keep the entire crop inside the photo so the circular portrait has no empty bands.
    val side = minOf(eyeDistance * 4.8f, imageWidth.toFloat(), imageHeight.toFloat())
    val left = (eyeX - side / 2f).coerceIn(0f, imageWidth - side)
    val top = (eyeY + eyeDistance * 0.45f - side / 2f).coerceIn(0f, imageHeight - side)
    return PlayerCastHeadFrame(left, top, side)
}

private class PlayerCastHeadTransformation : Transformation() {
    override val cacheKey: String = "player_cast_centered_head_v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (input.width < 2 || input.height < 2) return input
        // Android's detector needs a software RGB_565 bitmap with an even width.
        val detection = Bitmap.createBitmap(input.width + input.width % 2, input.height, Bitmap.Config.RGB_565)
        try {
            Canvas(detection).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(input, 0f, 0f, null)
            }
            val faces = arrayOfNulls<FaceDetector.Face>(4)
            FaceDetector(detection.width, detection.height, faces.size).findFaces(detection, faces)
            val face = faces.filterNotNull().filter { it.confidence() >= 0.4f }
                .maxByOrNull { it.eyesDistance() } ?: return input
            val eyes = PointF()
            face.getMidPoint(eyes)
            val frame = playerCastHeadFrame(eyes.x, eyes.y, face.eyesDistance(), input.width, input.height) ?: return input
            // Draw a full square from the photo, adjusting the framing at its edges.
            val output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val scale = output.width / frame.side
            Canvas(output).drawBitmap(input, null, RectF(
                -frame.left * scale, -frame.top * scale,
                (input.width - frame.left) * scale, (input.height - frame.top) * scale
            ), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } catch (error: RuntimeException) {
            Log.d("PlayerCastPortrait", "Keeping original portrait after face detection failure", error)
            return input
        } finally {
            detection.recycle()
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    label: String? = null,
    labelMaxLines: Int = 1,
    value: String? = null,
    textIcon: String? = null,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onDownKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    compact: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    // Reduce only the yellow focus tile: labels, values and icons retain their
    // established sizes for TV readability.
    val buttonWidth = if (compact) 74.dp else 86.dp
    val buttonHeight = if (compact) 68.dp else 74.dp
    val painterIconSize = if (compact) 22.dp else 24.dp
    val vectorIconSize = if (compact) 25.dp else 28.dp
    val textIconFontSize = if (compact) 16.sp else 18.sp
    val textIconLineHeight = if (compact) 16.sp else 18.sp
    val labelFontSize = if (compact) 9.sp else 10.sp
    val valueFontSize = if (compact) 9.sp else 10.sp
    val valueLineHeight = if (compact) 10.sp else 11.sp
    val horizontalTextPadding = if (compact) 2.dp else 4.dp

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(buttonWidth)
            .height(buttonHeight)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester }
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent { keyEvent ->
                if (
                    upFocusRequester != null &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                ) {
                    try {
                        upFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                    true
                } else if (
                    onDownKey != null &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onDownKey.invoke()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = NuvioColors.Secondary,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.38f),
            focusedContentColor = NuvioColors.OnSecondary
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (iconPainter != null) {
                Icon(
                    painter = iconPainter,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(painterIconSize)
                )
            } else if (!textIcon.isNullOrBlank()) {
                Text(
                    text = textIcon,
                    color = if (!enabled) Color.White.copy(alpha = 0.38f) else if (isFocused) NuvioColors.OnSecondary else Color.White,
                    fontSize = textIconFontSize,
                    lineHeight = textIconLineHeight,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(vectorIconSize)
                )
            }
            if (!label.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = label,
                    color = if (!enabled) Color.White.copy(alpha = 0.38f) else if (isFocused) NuvioColors.OnSecondary else Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = labelMaxLines,
                    lineHeight = if (labelMaxLines > 1) valueLineHeight else androidx.compose.ui.unit.TextUnit.Unspecified,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontSize = labelFontSize,
                    modifier = Modifier.padding(horizontal = horizontalTextPadding),
                )
            }
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    color = if (!enabled) Color.White.copy(alpha = 0.38f) else if (isFocused) NuvioColors.OnSecondary.copy(alpha = 0.82f) else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = valueFontSize,
                    lineHeight = valueLineHeight,
                    modifier = Modifier.padding(horizontal = horizontalTextPadding),
                )
            }
        }
    }
}

private fun formatResolutionControlStatus(streamInfoData: StreamInfoData?): String? {
    val width = streamInfoData?.videoWidth
    val height = streamInfoData?.videoHeight
    return if (width != null && height != null && width > 0 && height > 0) {
        "${width}x${height}"
    } else {
        null
    }
}

private fun formatResolutionControlBadge(streamInfoData: StreamInfoData?): String {
    val width = streamInfoData?.videoWidth ?: return "--"
    val height = streamInfoData.videoHeight ?: return "--"
    val maxEdge = maxOf(width, height)
    return when {
        // Les masters 4K au format cinéma peuvent perdre quelques pixels sur la largeur
        // (par exemple 3836×1596) sans devenir une source 2K.
        maxEdge >= 3800 -> "4K"
        maxEdge >= 2560 -> "2K"
        // Les masters cinéma FHD retirent souvent les bandes noires. Dès que la
        // largeur active atteint environ 1900 px, ils conservent la définition
        // horizontale d'un 1920×1080, quelle que soit leur hauteur de cadrage.
        maxEdge >= 1900 -> "FHD"
        maxEdge >= 1280 -> "HD"
        maxEdge > 0 -> "SD"
        else -> "--"
    }
}

private fun compactSeasonEpisodeLabel(season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    return "S%02dE%02d".format(Locale.ROOT, season, episode)
}

/** The year is already displayed on the metadata line below the player title. */
private fun playerControlsDisplayTitle(title: String): String =
    title.replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim()

internal fun playerPrimaryDisplayName(
    contentName: String?,
    title: String,
    season: Int?,
    episode: Int?
): String {
    val raw = if (season != null && episode != null) {
        contentName?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }
    if (season == null || episode == null) return raw
    val codePattern = Regex("""(?i)(?:^|[\s._-]+)s0*${season}e0*${episode}(?:$|[\s._-]+)""")
    val withoutCode = raw
        .replace(codePattern, " ")
        .replace(Regex("""[._]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '.', '_')
    return withoutCode.takeIf { it.isNotBlank() } ?: raw
}

private fun String.isGenericEpisodeTitle(season: Int?, episode: Int?): Boolean {
    val s = season ?: return false
    val e = episode ?: return false
    val normalized = trim().lowercase(Locale.ROOT)
    return normalized == "season $s episode $e" ||
        normalized == "saison $s episode $e" ||
        normalized == "saison $s épisode $e" ||
        normalized == "episode $e" ||
        normalized == "épisode $e" ||
        normalized == "ep $e"
}

private fun formatAudioControlStatus(track: TrackInfo?, streamInfoData: StreamInfoData?): String? {
    val language = readableLanguage(track?.language ?: streamInfoData?.audioLanguage)
        ?: track?.name?.let(::conciseAudioTrackName)
    val codec = readableAudioCodec(track?.codec ?: streamInfoData?.audioCodec)
    val channels = readableAudioChannels(track?.channelCount, streamInfoData?.audioChannels)
    return listOfNotNull(language, codec, channels)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(3)
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}

/**
 * The fallback label produced by a number of IPTV streams is e.g.
 * "Audio 1 (AAC Stereo)".  Codec and channels are already rendered below, so
 * keeping that label produces the duplicate, truncated summary seen in the
 * controls.  Preserve a real human-readable label, but discard generic slots.
 */
private fun conciseAudioTrackName(raw: String): String? {
    val label = raw.substringBefore('(').trim()
    if (label.isBlank()) return null
    return label.takeUnless {
        it.matches(Regex("""(?i)^(audio|piste\s+audio|track)\s*\d*$"""))
    }
}

private fun formatSubtitleControlStatus(
    selectedTrack: TrackInfo?,
    selectedAddonSubtitle: com.nuvio.tv.domain.model.Subtitle?,
    streamInfoData: StreamInfoData?,
): String? {
    val language = readableLanguage(
        selectedAddonSubtitle?.lang
            ?: selectedTrack?.language
            ?: streamInfoData?.subtitleLanguage
    )
    val name = selectedAddonSubtitle?.lang?.let { null }
        ?: selectedTrack?.name?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        ?: streamInfoData?.subtitleName?.takeIf { it.isNotBlank() }
    val status = (language ?: name)?.takeIf { it.isNotBlank() } ?: return null
    return if (selectedTrack?.isForced == true) "$status (Forcés)" else status
}

private fun formatPlayerInfoControlStatus(streamInfoData: StreamInfoData?): String? {
    val resolution = streamInfoData?.let { data ->
        val width = data.videoWidth
        val height = data.videoHeight
        if (width != null && height != null && width > 0 && height > 0) "${width}x${height}" else null
    }
    val audio = readableAudioCodec(streamInfoData?.audioCodec)
    return listOfNotNull(resolution, audio).joinToString(" ").takeIf { it.isNotBlank() }
}

private fun readableAudioChannels(trackChannels: Int?, streamChannels: String?): String? {
    trackChannels?.let { count ->
        return when (count) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${count}ch"
        }
    }
    return streamChannels
        ?.replace("channels", "ch", ignoreCase = true)
        ?.replace("channel", "ch", ignoreCase = true)
        ?.takeIf { it.isNotBlank() }
}

private fun readableAudioCodec(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val lower = value.lowercase(Locale.ROOT)
    return when {
        "truehd" in lower -> "TrueHD"
        "atmos" in lower -> "Atmos"
        "eac3" in lower || "e-ac-3" in lower || "ec-3" in lower -> "E-AC3"
        "ac3" in lower || "ac-3" in lower -> "AC3"
        "aac" in lower -> "AAC"
        "dts-hd" in lower -> "DTS-HD"
        "dts" in lower -> "DTS"
        "mp3" in lower || "mpeg" in lower -> "MP3"
        "opus" in lower -> "Opus"
        "vorbis" in lower -> "Vorbis"
        "flac" in lower -> "FLAC"
        "pcm" in lower -> "PCM"
        else -> value.removePrefix("audio/").uppercase(Locale.ROOT)
    }
}

private fun readableLanguage(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: return null
    return when (value.lowercase(Locale.ROOT)) {
        "fr", "fra", "fre", "french", "francais", "français" -> "Français"
        "en", "eng", "english", "anglais" -> "Anglais"
        "es", "spa", "spanish", "espagnol" -> "Espagnol"
        "de", "ger", "deu", "german", "allemand" -> "Allemand"
        "it", "ita", "italian", "italien" -> "Italien"
        "pt", "por", "portuguese", "portugais" -> "Portugais"
        "ja", "jpn", "japanese", "japonais" -> "Japonais"
        "ko", "kor", "korean", "coreen", "coréen" -> "Coréen"
        "zh", "chi", "zho", "chinese", "chinois" -> "Chinois"
        else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
}

private fun playerControlAspectLabel(resId: Int): String {
    return when (resId) {
        R.string.player_aspect_fit -> "Normal"
        R.string.player_aspect_crop -> "Remplir"
        R.string.player_aspect_stretch -> "Etirer"
        R.string.player_aspect_fit_width -> "Largeur"
        R.string.player_aspect_fit_height -> "Hauteur"
        R.string.player_aspect_mode_slight_zoom -> "Zoom"
        R.string.player_aspect_mode_cinema_zoom -> "Cinema"
        else -> ""
    }
}

private fun formatPlayerRuntime(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h${remainder.toString().padStart(2, '0')}"
        hours > 0 -> "${hours}h"
        else -> "${minutes} min"
    }
}

private fun playerMediaKeyEvent(keyCode: Int): PlayerEvent? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerEvent.OnPlayPause
    KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerEvent.OnSetPaused(false)
    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerEvent.OnSetPaused(true)
    else -> null
}

private fun isPlayerControlsMenuKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_MENU ||
        keyCode == KeyEvent.KEYCODE_PLUS ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ADD

@Composable
private fun ProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onSeekToPosition: ((Long) -> Unit)? = null,
    onPreviewPositionChanged: ((Long) -> Unit)? = null,
    /** Position (ms) up to which content is buffered. Pass 0 to skip the overlay. */
    bufferedPosition: Long = 0L
) {
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val bufferedProgress = if (duration > 0 && bufferedPosition > currentPosition) {
        (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "progress"
    )
    val animatedBufferedProgress by animateFloatAsState(
        targetValue = bufferedProgress,
        animationSpec = tween(200),
        label = "bufferedProgress"
    )
    var isFocused by remember { mutableStateOf(false) }
    fun positionAt(x: Float, width: Int): Long {
        if (duration <= 0L || width <= 0) return 0L
        return (duration * (x / width.toFloat()).coerceIn(0f, 1f)).toLong()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isFocused) 12.dp else 8.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null || downFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable()
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    onPreviewPositionChanged?.invoke(positionAt(offset.x, size.width))
                    onSeekToPosition?.invoke(positionAt(offset.x, size.width))
                }
            }
            .pointerInput(duration) {
                var lastPosition = 0L
                detectDragGestures(
                    onDragStart = { offset ->
                        lastPosition = positionAt(offset.x, size.width)
                        onPreviewPositionChanged?.invoke(lastPosition)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        lastPosition = positionAt(change.position.x, size.width)
                        onPreviewPositionChanged?.invoke(lastPosition)
                    },
                    onDragEnd = { onSeekToPosition?.invoke(lastPosition) }
                )
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekCommit()
                            return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }

                // testing additional key handling for DPAD_LEFT and DPAD_RIGHT to allow seek in focus (check)
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (downFocusRequester != null) {
                                try {
                                    downFocusRequester.requestFocus()
                                } catch (_: Exception) {
                                }
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (upFocusRequester != null) {
                                try {
                                    upFocusRequester.requestFocus()
                                } catch (_: Exception) {
                                }
                                true
                            } else if (onUpKey != null) {
                                onUpKey.invoke()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSeekPreview(-10_000L)
                            onPreviewPositionChanged?.invoke((currentPosition - 10_000L).coerceAtLeast(0L))
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekPreview(10_000L)
                            onPreviewPositionChanged?.invoke((currentPosition + 10_000L).coerceAtMost(duration))
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        val trackWidth = maxWidth

        // Keep the existing track exactly as it was, but isolate its clipping
        // so the larger playback thumb can extend above and below it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (isFocused) Color.White.copy(alpha = 0.45f)
                    else Color.White.copy(alpha = 0.3f)
                )
        )

        // Buffered-ahead overlay: the theme accent, faded so it reads under the played
        // fill and on light themes.
        if (animatedBufferedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(trackWidth * animatedBufferedProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NuvioColors.Secondary.copy(alpha = 0.35f))
            )
        }
        // Played fill.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(trackWidth * animatedProgress)
                .clip(RoundedCornerShape(3.dp))
                .background(NuvioColors.Secondary)
        )
        val thumbDiameter = 18.dp
        Box(
            modifier = Modifier
                .offset(x = trackWidth * animatedProgress - thumbDiameter / 2)
                .requiredSize(thumbDiameter)
                .align(Alignment.CenterStart)
                .clip(CircleShape)
                .background(NuvioColors.Secondary)
        )
    }
}

@Composable
private fun SeekOverlay(
    currentPosition: Long,
    duration: Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            ProgressBar(
                currentPosition = currentPosition,
                duration = duration,
                onSeekPreview = {},
                onSeekCommit = {}
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun SeekOverlayHost(viewModel: PlayerViewModel) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        SeekOverlay(
            currentPosition = playbackTimeline.currentPosition,
            duration = playbackTimeline.duration,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PlayerClockOverlay(
    currentPosition: Long,
    duration: Long,
    playbackSpeed: Float,
    iptvAppearance: Map<String, String>? = null,
    showInfoDate: Boolean = false
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val timeFormatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            val delayMs = (1_000L - (current % 1_000L)).coerceAtLeast(250L)
            delay(delayMs)
        }
    }

    val effectiveSpeed = playbackSpeed.takeIf { it > 0f } ?: 1f
    val remainingMediaMs = (duration - currentPosition).coerceAtLeast(0L)
    val remainingMs = kotlin.math.ceil(remainingMediaMs.toDouble() / effectiveSpeed.toDouble()).toLong()
    val endTimeText = if (duration > 0L) {
        timeFormatter.format(Date(nowMs + remainingMs))
    } else {
        "--:--"
    }

    val clockSize = if (iptvAppearance == null) 13 else when (iptvAppearance["playerClockSize"]) {
        "small" -> 13; "large" -> 22; else -> 17
    }
    val clockAlpha = if (iptvAppearance == null) .96f else when (iptvAppearance["playerClockTransparency"]) {
        "none" -> 1f; "low" -> .75f; "high" -> .25f; else -> .5f
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = timeFormatter.format(Date(nowMs)),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = clockSize.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White.copy(alpha = clockAlpha)
        )
        if (iptvAppearance != null && showInfoDate) {
            Text(text = DateFormat.getDateFormat(context).format(Date(nowMs)), fontSize = clockSize.sp,
                color = Color.White.copy(alpha = clockAlpha))
        }
        if (iptvAppearance == null) Text(
            text = stringResource(R.string.player_ends_at, endTimeText),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White.copy(alpha = 0.96f)
        )
    }
}

@Composable
private fun PlayerClockOverlayHost(viewModel: PlayerViewModel, playbackSpeed: Float,
    iptvAppearance: Map<String, String>? = null, showInfoDate: Boolean = false) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    PlayerClockOverlay(
        currentPosition = playbackTimeline.currentPosition,
        duration = playbackTimeline.duration,
        playbackSpeed = playbackSpeed,
        iptvAppearance = iptvAppearance,
        showInfoDate = showInfoDate
    )
}

@Composable
private fun SubtitleTimingDialogHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    SubtitleTimingDialog(
        modifier = modifier,
        currentPositionMs = playbackTimeline.currentPosition,
        selectedAddonSubtitle = selectedAddonSubtitle,
        cues = cues,
        capturedVideoMs = capturedVideoMs,
        statusMessage = statusMessage,
        errorMessage = errorMessage,
        isLoadingCues = isLoadingCues,
        onCaptureNow = onCaptureNow,
        onCueSelected = onCueSelected
    )
}

@Composable
private fun AspectRatioIndicator(text: String) {
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)

    // Floating pill indicator for aspect ratio changes
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon background circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = Color(0xFF3B3B3B),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = customAspectPainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White
        )
    }
}

@Composable
private fun StreamSourceIndicator(text: String) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerEngineSwitchIndicator(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SubtitleDelayOverlay(
    subtitleDelayMs: Int,
    isAutoSyncButtonFocused: Boolean,
    isSliderFocused: Boolean,
    onOpenSyncByLine: () -> Unit
) {
    val fraction = ((subtitleDelayMs - SUBTITLE_DELAY_MIN_MS).toFloat() /
        (SUBTITLE_DELAY_MAX_MS - SUBTITLE_DELAY_MIN_MS).toFloat()).coerceIn(0f, 1f)
    val sliderAccent = if (isSliderFocused) Color(0xFF4AA3FF) else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xCC0F0F0F))
            .padding(horizontal = 26.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_subtitle_delay),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
            Text(
                text = formatSubtitleDelay(subtitleDelayMs),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.95f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        ) {
            val thumbWidth = 22.dp
            val thumbOffset = (maxWidth - thumbWidth) * fraction

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .align(Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.15f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    val tickHeight = if (index == 2) 13.dp else 9.dp
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(tickHeight)
                            .background(sliderAccent.copy(alpha = if (isSliderFocused) 0.52f else 0.22f))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .align(Alignment.CenterStart)
                    .width(thumbWidth)
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(sliderAccent.copy(alpha = 0.95f))
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            onClick = onOpenSyncByLine,
            colors = CardDefaults.colors(
                containerColor = if (isAutoSyncButtonFocused) {
                    Color.White.copy(alpha = 0.22f)
                } else {
                    Color.White.copy(alpha = 0.11f)
                },
                focusedContainerColor = Color.White.copy(alpha = 0.22f)
            ),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(
                text = stringResource(R.string.player_sync_line),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun rememberRawSvgPainter(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { 24.dp.roundToPx() }
    val request = remember(iconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .size(sizePx)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
private fun ErrorOverlay(
    message: String,
    accentColor: Color,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val retryFocusRequester = remember { FocusRequester() }
    val exitFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        retryFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DialogButton(
                    text = stringResource(R.string.player_retry),
                    onClick = onRetry,
                    isPrimary = true,
                    focusedAccentColor = accentColor,
                    modifier = Modifier.focusRequester(retryFocusRequester)
                )
                DialogButton(
                    text = stringResource(R.string.player_go_back),
                    onClick = onBack,
                    isPrimary = false,
                    focusedAccentColor = accentColor,
                    modifier = Modifier.focusRequester(exitFocusRequester)
                )
            }
        }
    }
}

@Composable
private fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIndex = remember(currentSpeed) {
        PLAYBACK_SPEEDS.indices.minByOrNull { index ->
            abs(PLAYBACK_SPEEDS[index] - currentSpeed)
        } ?: 0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val speedFocusRequesters = remember {
        PLAYBACK_SPEEDS.map { FocusRequester() }
    }

    LaunchedEffect(selectedIndex) {
        runCatching { speedFocusRequesters[selectedIndex].requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_speed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp)
                ) {
                    itemsIndexed(PLAYBACK_SPEEDS) { index, speed ->
                        SpeedItem(
                            modifier = Modifier.focusRequester(speedFocusRequesters[index]),
                            speed = speed,
                            isSelected = speed == currentSpeed,
                            onClick = { onSpeedSelected(speed) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionsDialog(
    onPlaybackSpeed: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onDismiss: () -> Unit,
    includeAspectRatio: Boolean = true,
    additionalActions: List<Pair<String, () -> Unit>> = emptyList()
) {
    val firstActionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstActionFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NuvioColors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_more_actions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val actions = additionalActions + listOf(
                    stringResource(R.string.player_more_speed) to onPlaybackSpeed,
                    stringResource(R.string.player_more_open_external) to onOpenInExternalPlayer
                ) + if (includeAspectRatio) listOf(stringResource(R.string.player_more_aspect_ratio) to onToggleAspectRatio) else emptyList()
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(actions) { index, action ->
                        MoreActionItem(text = action.first, onClick = action.second,
                            modifier = if (index == 0) Modifier.focusRequester(firstActionFocus) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isFocused) {
                if (NuvioColors.FocusBackground.luminance() > .5f) Color.Black else Color.White
            } else NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun SpeedItem(
    modifier: Modifier = Modifier,
    speed: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.2f) else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (speed == 1f) stringResource(R.string.player_speed_normal) else "${speed}x",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun DialogButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    focusedAccentColor: Color? = null
) {
    val focusedAccentContentColor = focusedAccentColor?.let {
        if (it.luminance() > 0.58f) Color(0xFF111111) else Color.White
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (isPrimary) NuvioColors.Secondary else NuvioColors.BackgroundCard,
            contentColor = if (isPrimary) NuvioColors.OnSecondary else NuvioColors.TextSecondary,
            focusedContainerColor = focusedAccentColor ?: if (isPrimary) NuvioColors.SecondaryVariant else NuvioColors.FocusBackground,
            focusedContentColor = focusedAccentContentColor ?: if (isPrimary) NuvioColors.OnSecondaryVariant else NuvioColors.Primary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, focusedAccentColor ?: if (isPrimary) NuvioColors.SecondaryVariant else NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"

    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return String.format(Locale.US, "%+.3fs", delayMs / 1000f)
}

/**
 * Buffering indicator extracted into its own composable to isolate
 * recomposition scope. When [isBuffering] toggles, only this subtree
 * is recomposed — the rest of [PlayerScreen] is skipped by Compose.
 */
@Composable
private fun PlayerBufferingIndicator(
    isBuffering: Boolean,
    showLoadingOverlay: Boolean,
    isTorrentStream: Boolean,
    torrentBufferingMessage: String?,
    torrentBufferingProgress: Float
) {
    if (!isBuffering || showLoadingOverlay) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isTorrentStream && torrentBufferingMessage != null) {
            // Torrent rebuffer: spinner + download stats + progress bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = torrentBufferingMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                if (torrentBufferingProgress > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .width(200.dp)
                            .height(3.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(torrentBufferingProgress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(
                                    color = Color.White.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
        } else {
            LoadingIndicator()
        }
    }
}
