package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import com.jojotv.home.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val AUDIO_AMPLIFICATION_MIN_DB = 0
internal const val AUDIO_AMPLIFICATION_MAX_DB = 10
internal const val CENTER_MIX_LEVEL_MIN_DB = -10
internal const val CENTER_MIX_LEVEL_MAX_DB = 30
internal const val AUDIO_DELAY_MIN_MS = -3000
internal const val AUDIO_DELAY_MAX_MS = 3000
private const val PLAYER_CONTROLS_AUTO_HIDE_MS = 5000L
internal const val AUDIO_DELAY_STEP_MS = 25

/** Restart the current item from zero without loading the completed resume. */
internal fun PlayerRuntimeController.replayFromBeginning() {
    if (isExitRequested || currentStreamUrl.isBlank()) return
    resetErrorRetryState()
    resetPostPlayOverlayState(clearEpisode = false)
    pendingResumeProgress = null
    resumeSeekOverlayTargetMs = null
    _uiState.update {
        it.copy(
            playbackEnded = false,
            error = null,
            isBuffering = true,
            showLoadingOverlay = it.loadingOverlayEnabled,
            showPauseOverlay = false,
            showControls = false,
            pendingSeekPosition = null
        )
    }
    releasePlayer()
    preparePlaybackBeforeStart(currentStreamUrl, currentHeaders, loadSavedProgress = false)
}

internal fun PlayerRuntimeController.applyAudioDelay(
    delayMs: Int,
    persistForCurrentRoute: Boolean = true
) {
    val clampedDelayMs = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    audioDelayUs.set(clampedDelayMs.toLong() * 1000L)
    _uiState.update { it.copy(audioDelayMs = clampedDelayMs) }
    if (persistForCurrentRoute) {
        persistAudioDelayForCurrentRoute(clampedDelayMs)
    }
}

internal fun PlayerRuntimeController.skipActiveInterval(): Boolean {
    return skipInterval(_uiState.value.activeSkipInterval ?: return false)
}

internal fun PlayerRuntimeController.skipInterval(interval: SkipInterval): Boolean {
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val seekMs = if (interval.endTime == Double.MAX_VALUE) {
        duration
    } else {
        (interval.endTime * 1000).toLong()
    }
    seekPlaybackTo(seekMs.coerceAtMost(duration), SeekParameters.NEXT_SYNC)
    scheduleProgressSyncAfterSeek()
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    return true
}

internal fun PlayerRuntimeController.applyAudioAmplification(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val isAudioAmplificationAvailable = isUsingMpvEngine() || _exoPlayer != null
    val wasActive = gainAudioProcessor.isGainEnabled()
    gainAudioProcessor.setGainDb(if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    val isActiveNow = gainAudioProcessor.isGainEnabled()

    if (wasActive != isActiveNow && !isUsingMpvEngine()) {
        playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
        _exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        }
    }

    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(clampedDb)
    }
    _uiState.update {
        it.copy(
            audioAmplificationDb = clampedDb,
            isAudioAmplificationAvailable = isAudioAmplificationAvailable
        )
    }
}

internal fun PlayerRuntimeController.applyCenterMixLevel(db: Int) {
    val clampedDb = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    ffmpegAudioRenderer?.setCenterMixLevelDb(clampedDb)
    _uiState.update { state ->
        state.copy(centerMixLevelDb = clampedDb)
    }
}

internal fun PlayerRuntimeController.updateAudioControlAvailability(
    audioTracks: List<TrackInfo> = _uiState.value.audioTracks,
    selectedAudioIndex: Int = _uiState.value.selectedAudioTrackIndex
) {
    val selectedTrack = audioTracks.getOrNull(selectedAudioIndex)
    val isAudioAmplificationAvailable = isUsingMpvEngine() || _exoPlayer != null
    val isCenterMixAvailable =
        ffmpegAudioRenderer?.isCenterMixActive() == true && (selectedTrack?.channelCount ?: 0) > 2
    val clampedDb = _uiState.value.audioAmplificationDb
        .coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    gainAudioProcessor.setGainDb(
        if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB
    )
    _uiState.update { state ->
        state.copy(
            isAudioAmplificationAvailable = isAudioAmplificationAvailable,
            isCenterMixAvailable = isCenterMixAvailable
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayStateAfterPlaybackEnded() {
    if (!shouldResetPostPlayStateAfterPlaybackEnded(
            state = _uiState.value,
            hasInFlightNextEpisodeAutoPlay = nextEpisodeAutoPlayJob?.isActive == true
        )
    ) {
        return
    }
    resetPostPlayOverlayState(clearEpisode = false)
}

internal fun shouldResetPostPlayStateAfterPlaybackEnded(
    state: PlayerUiState,
    hasInFlightNextEpisodeAutoPlay: Boolean
): Boolean {
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return false
    if (hasInFlightNextEpisodeAutoPlay) return false
    return true
}

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            if (isUsingMpvEngine()) {
                val view = mpvView
                if (view != null) {
                    val pos = view.currentPositionMs().coerceAtLeast(0L)
                    val playerDuration = view.durationMs().coerceAtLeast(0L)
                    applyPendingMpvSeekIfNeeded(
                        view = view,
                        currentPositionMs = pos,
                        durationMs = playerDuration
                    )
                    val playingNow = view.isPlayingNow()
                    val cacheBuffering = view.isPausedForCacheNow() || view.isCoreIdleNow()
                    var firstFrameReady = hasRenderedFirstFrame
                    if (!firstFrameReady) {
                        firstFrameReady = pos > 0L || (playingNow && !cacheBuffering && playerDuration > 0L)
                        if (firstFrameReady) {
                            hasRenderedFirstFrame = true
                        }
                    }
                    if (playerDuration > lastKnownDuration) {
                        lastKnownDuration = playerDuration
                    }
                    val displayPosition = pendingPreviewSeekPosition ?: pos
                    updatePlaybackTimeline(
                        currentPosition = displayPosition,
                        duration = playerDuration
                    )
                    val ended = playerDuration > 0L && pos >= (playerDuration - 500L)
                    val wasEnded = _uiState.value.playbackEnded
                    _uiState.update { state ->
                        state.copy(
                            isPlaying = playingNow,
                            isBuffering = !firstFrameReady || cacheBuffering,
                            showLoadingOverlay = if (state.loadingOverlayEnabled) !firstFrameReady else false,
                            // Snap the loading-logo fill to 100% once playback is
                            // ready so the logo finishes filling on dismissal.
                            loadingProgress = if (firstFrameReady && state.loadingProgress != null) 1f else state.loadingProgress,
                            playbackEnded = ended
                        )
                    }
                    updateMpvAvailableTracks()
                    tryAutoSelectPreferredSubtitleFromAvailableTracks()
                    updateActiveSkipInterval(pos)
                    evaluatePostPlayOverlayVisibility(
                        positionMs = pos,
                        durationMs = playerDuration
                    )
                    if (ended && !wasEnded) {
                        emitCompletionScrobbleStop(progressPercent = 99.5f)
                        saveWatchProgress()
                        resetPostPlayStateAfterPlaybackEnded()
                    }
                }
                delay(500)
                continue
            }

            _exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playerDuration = player.duration
                if (playerDuration > lastKnownDuration) {
                    lastKnownDuration = playerDuration
                }
                val displayPosition = pendingPreviewSeekPosition ?: pos
                maybeFinishResumeSeekOverlay(pos)
                updatePlaybackTimeline(
                    currentPosition = displayPosition,
                    duration = playerDuration.coerceAtLeast(0L),
                    bufferedPosition = player.bufferedPosition.coerceAtLeast(displayPosition)
                )
                // Update torrent rebuffer progress from ExoPlayer's buffer state
                if (isTorrentStream && _uiState.value.isBuffering && hasRenderedFirstFrame) {
                    val bufferedAheadMs = (player.bufferedPosition - pos).coerceAtLeast(0)
                    val bufferedSec = bufferedAheadMs / 1000f
                    val statsHidden = _uiState.value.hideTorrentStats
                    val message = if (statsHidden) {
                        null
                    } else {
                        val speed = formatTorrentSpeed(_uiState.value.torrentDownloadSpeed)
                        val peerInfo = "${_uiState.value.torrentSeeds} seeds \u00B7 ${_uiState.value.torrentPeers} peers"
                        val bufLabel = String.format("%.0fs", bufferedSec)
                        "$bufLabel buffered \u00B7 $peerInfo \u00B7 $speed"
                    }
                    val progress = (bufferedSec / 10f).coerceIn(0f, 1f)
                    val torrentState = _uiState.value
                    if (torrentState.torrentBufferingMessage != message ||
                        kotlin.math.abs(torrentState.torrentBufferingProgress - progress) >= 0.01f
                    ) {
                        _uiState.update {
                            it.copy(
                                torrentBufferingMessage = message,
                                torrentBufferingProgress = progress
                            )
                        }
                    }
                }
                updateActiveSkipInterval(pos)
                evaluatePostPlayOverlayVisibility(
                    positionMs = pos,
                    durationMs = playerDuration.coerceAtLeast(0L)
                )

                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastBufferLogTimeMs >= 10_000) {
                        lastBufferLogTimeMs = now
                        val bufAhead = (player.bufferedPosition - player.currentPosition) / 1000
                        val loading = player.isLoading
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        val maxMb = runtime.maxMemory() / (1024 * 1024)
                        Log.d(PlayerRuntimeController.TAG, "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=$usedMb/${maxMb}MB, pos=${pos / 1000}s")
                    }
                }
            }
            delay(500)
        }
    }
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = scope.launch {
        // Publish the first real playback checkpoint promptly so Home can add
        // the item immediately when the player closes. Later writes retain the
        // normal ten-second cadence.
        while (isActive && lastSavedPosition <= 0L) {
            delay(1000)
            saveWatchProgressIfNeeded()
        }
        while (isActive) {
            delay(10000)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    // Don't save progress for very short streams (< 2:01) — these are
    // typically error/warning messages or "stream not ready" placeholders that
    // would incorrectly mark content as watched when the user exits.
    if (isShortPlaceholderDuration(duration) && !isHomeRecordedMedia()) return

    val firstCheckpoint = lastSavedPosition <= 0L && (currentPosition >= 1000L || contentType.equals("iptv", true))
    if (firstCheckpoint || kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition.coerceAtLeast(1L)
        // Each checkpoint is also queued for the NAS. The repository collapses
        // repeated writes for the same progress key and keeps the pending key on
        // disk until the server acknowledges it.
        saveWatchProgressInternal(currentPosition, duration, syncRemote = true)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (isShortPlaceholderDuration(duration) && !isHomeRecordedMedia()) return
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = currentPlaybackDurationMs()
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = if (isUsingMpvEngine()) {
        position >= (effectiveDuration - 500L)
    } else {
        _exoPlayer?.playbackState == Player.STATE_ENDED
    }
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

private fun isShortPlaceholderDuration(duration: Long) = duration in 1..120999

private fun PlayerRuntimeController.isHomeRecordedMedia(): Boolean =
    contentType.equals("video", true) || contentType.equals("freebox", true) ||
        contentId?.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX) == true

private fun PlayerRuntimeController.isShortPlaceholderStream(): Boolean {
    if (isHomeRecordedMedia()) return false
    val position = currentPlaybackPositionMs() ?: return false
    return isShortPlaceholderDuration(getEffectiveDuration(position))
}

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    val pilotCreatedElapsedMs = if (com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED)
        android.os.SystemClock.elapsedRealtime() else 0L
    val contentId = contentId
    if (contentId.isNullOrEmpty() || contentType.isNullOrEmpty()) return

    val isLiveIptvChannel = contentType.equals("iptv", ignoreCase = true)
    if (position < 1000 && !isLiveIptvChannel) return

    val fallbackPercent = if (duration <= 0L && !isLiveIptvChannel) 5f else null
    val playbackLogo = if (isLiveIptvChannel) _uiState.value.logo else logo
    val channelArtwork = playbackLogo?.takeIf { it.isNotBlank() }
    val isIptvVod = contentType.startsWith("iptv_", ignoreCase = true) && !isLiveIptvChannel
    val isFreeboxContent = _uiState.value.isFreeboxPlayback ||
        contentType.equals("freebox", ignoreCase = true) ||
        contentId.startsWith("freebox:", ignoreCase = true)
    val routePresentationIsShareable = !isFreeboxContent || hasVerifiedFreeboxPresentation
    val launchSelection = PlayerLaunchArtworkState.selectionForPlayback(
        contentId,
        displayContentNameForPlayback(),
        displayTitleForPlayback(),
        _uiState.value.title
    )
    // A Continue Watching card for IPTV VOD must not turn its episode frame
    // into the persistent programme artwork. The provider/TMDB poster and
    // backdrop below remain authoritative and are enriched on Home refresh.
    val displayedCardArtwork = launchSelection?.artworkUrl
        ?.takeIf { !isIptvVod }
    val progressContentId = launchSelection?.contentId
        ?.takeIf {
            contentType.startsWith("iptv_", ignoreCase = true) ||
                contentType.equals("freebox", ignoreCase = true)
        }
        ?: contentId

    val progress = WatchProgress(
        contentId = progressContentId,
        contentType = contentType,
        // Une carte « Continuer à regarder » d'épisode doit toujours conserver le
        // titre de la série. Le titre du flux peut n'être que « S01E02 » avant
        // que le lecteur ait récupéré ses métadonnées.
        name = displayContentNameForPlayback() ?: displayTitleForPlayback(),
        // Live IPTV routes carry the channel artwork in `logo`. Continue
        // Watching cards consume poster/backdrop, so persist the same stable
        // channel image in those slots when no programme artwork is available.
        poster = displayedCardArtwork
            ?: (poster ?: channelArtwork.takeIf { isLiveIptvChannel })
                ?.takeIf { routePresentationIsShareable },
        backdrop = displayedCardArtwork
            ?: (backdrop ?: channelArtwork.takeIf { isLiveIptvChannel })
                ?.takeIf { routePresentationIsShareable },
        logo = playbackLogo,
        // Keep the synopsis with the progress entry so Jojo Home and another
        // JojoTV device can render the same resume card without refetching it.
        description = _uiState.value.description
            ?.takeIf { it.isNotBlank() && routePresentationIsShareable },
        // contentId identifies the film/series card; videoId identifies the
        // concrete playable stream (especially an episode). Never replace the
        // latter when artwork canonicalization changes only the card identity.
        videoId = if (contentId.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX))
            currentStreamUrl else currentVideoId ?: contentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = if (isLiveIptvChannel) 0L else position,
        duration = if (isLiveIptvChannel) 0L else duration,
        lastWatched = System.currentTimeMillis(),
        progressPercent = fallbackPercent
    )

    Log.d(
        PlayerRuntimeController.TAG,
        "Saving watch progress: contentId=$contentId position=${progress.position} " +
            "duration=${progress.duration} percent=${progress.progressPercentage}"
    )
    val pilotCheckpoint = if (com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED)
        com.jojotv.home.progresspilot.PilotPlaybackCheckpoint(progress.lastWatched, pilotCreatedElapsedMs)
    else kotlin.coroutines.EmptyCoroutineContext
    scope.launch(kotlinx.coroutines.NonCancellable + pilotCheckpoint) {
        watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)
    }
}

internal fun PlayerRuntimeController.currentPlaybackProgressPercent(): Float {
    if (!hasRenderedFirstFrame) return 0f
    val position = currentPlaybackPositionMs() ?: return 0f
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: lastKnownDuration
    if (duration <= 0L) return 0f
    return ((position.toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
}

internal fun PlayerRuntimeController.refreshScrobbleItem() {
    currentScrobbleItem = null
    hasSentScrobbleStartForCurrentItem = false
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration++
    hasSentCompletionScrobbleForCurrentItem = false
}

internal fun PlayerRuntimeController.buildScrobbleItem(): Any? {
    return null
}

internal fun PlayerRuntimeController.emitScrobbleStart() {
    // Trakt has been retired; local/NAS progress is saved by the playback flow.
}

internal fun PlayerRuntimeController.emitScrobbleStop(progressPercent: Float? = null) {
    Unit
}

internal fun PlayerRuntimeController.emitPauseScrobbleStop(progressPercent: Float) {
    Unit
}

internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    Unit
}

internal fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress() {
    Unit
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    if (_uiState.value.playbackEnded) {
        emitCompletionScrobbleStop(progressPercent = 99.5f)
        return
    }
    emitStopScrobbleForCurrentProgress()
    saveWatchProgress()
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()

        val progressPercent = currentPlaybackProgressPercent()
        emitPauseScrobbleStop(progressPercent = progressPercent)

        if (isPlaybackCurrentlyPlaying() && progressPercent >= 1f && progressPercent < 80f) {
            emitScrobbleStart()
        }
    }
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        val timeout = if (_uiState.value.contentType.equals("iptv", ignoreCase = true)) {
            com.jojotv.home.iptv.IptvStore.playerPanelTimeout(
                com.jojotv.home.iptv.IptvStore.playerAppearance(context, contentId))
        } else PLAYER_CONTROLS_AUTO_HIDE_MS
        if (timeout == 0L) return@launch
        delay(timeout)
        if (_uiState.value.isPlaying && !_uiState.value.showAudioOverlay &&
            !_uiState.value.showSubtitleOverlay && !_uiState.value.showSubtitleStylePanel &&
            !_uiState.value.showSpeedDialog && !_uiState.value.showMoreDialog &&
            !_uiState.value.showSubtitleDelayOverlay &&
            !_uiState.value.showSubtitleTimingDialog &&
            !_uiState.value.showEpisodesPanel && !_uiState.value.showSourcesPanel &&
            !_uiState.value.showStreamInfoOverlay) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    adjustSubtitleDelay(deltaMs = deltaMs, showOverlay = true)
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int, showOverlay: Boolean) {
    val currentState = _uiState.value
    val currentDelayMs = currentState.subtitleDelayMs
    val newDelayMs = (currentDelayMs + deltaMs).coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )
    val keepInlineInSubtitleOverlay = showOverlay && currentState.showSubtitleOverlay

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(newDelayMs)
    }
    if (showOverlay) {
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showControls = if (keepInlineInSubtitleOverlay) it.showControls else false,
                showSubtitleDelayOverlay = if (keepInlineInSubtitleOverlay) false else true
            )
        }
    } else {
        hideSubtitleDelayOverlayJob?.cancel()
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showSubtitleDelayOverlay = false,
                showControls = true
            )
        }
    }

    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .build()
    }
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()

    if (!showOverlay || keepInlineInSubtitleOverlay) {
        hideSubtitleDelayOverlayJob?.cancel()
        hideSubtitleDelayOverlayJob = null
    } else {
        scheduleHideSubtitleDelayOverlay()
    }
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    // The legacy pause sheet has been removed, but pausing must still expose
    // the native timeline and command bar immediately.
    _uiState.update { it.copy(showPauseOverlay = false, showControls = true) }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
}

fun PlayerRuntimeController.onUserInteraction() {
    cancelPauseOverlay()
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false, showMoreDialog = false) }
}

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    onUserInteraction()
    when (event) {
        is PlayerEvent.OnSetPaused -> {
            val playbackRequested = if (isUsingMpvEngine()) !userPausedManually
                else _exoPlayer?.playWhenReady ?: return
            if (event.paused == playbackRequested) onEvent(PlayerEvent.OnPlayPause)
        }
        PlayerEvent.OnPlayPause -> {
            Log.i(
                PlayerRuntimeController.TAG,
                "FREEBOX_TRACE playPause before at=${android.os.SystemClock.elapsedRealtime()} freebox=$isFreeboxPlayback engine=${if (isUsingMpvEngine()) "mpv" else "exo"} paused=$userPausedManually playing=${isPlaybackCurrentlyPlaying()} position=${currentPlaybackPositionMs() ?: -1L}"
            )
            if (isUsingMpvEngine()) {
                val playbackRequested = !userPausedManually
                if (playbackRequested) {
                    userPausedManually = true
                    userPauseStartedAtMs = System.currentTimeMillis()
                    // Publish the command-bar state before talking to the
                    // playback engine so a slow pause acknowledgement cannot
                    // delay the visible controls.
                    showControlsTemporarily()
                    setPlaybackPaused(true)
                    stopProgressUpdates()
                    stopWatchProgressSaving()
                    emitStopScrobbleForCurrentProgress()
                } else {
                    userPausedManually = false
                    userPauseStartedAtMs = 0L
                    cancelPauseOverlay()
                    hideControls()
                    setPlaybackPaused(false)
                    startProgressUpdates()
                    startWatchProgressSaving()
                    emitScrobbleStart()
                }
            } else {
                _exoPlayer?.let { player ->
                    // Buffering and audio-focus suppression stop rendering, not the play request.
                    if (player.playWhenReady) {
                        userPausedManually = true
                        userPauseStartedAtMs = System.currentTimeMillis()
                        showControlsTemporarily()
                        player.pause()
                    } else {
                        userPausedManually = false
                        userPauseStartedAtMs = 0L
                        cancelPauseOverlay()
                        hideControls()
                        player.play()
                    }
                }
            }
            Log.i(
                PlayerRuntimeController.TAG,
                "FREEBOX_TRACE playPause after at=${android.os.SystemClock.elapsedRealtime()} freebox=$isFreeboxPlayback paused=$userPausedManually playing=${isPlaybackCurrentlyPlaying()} position=${currentPlaybackPositionMs() ?: -1L}"
            )
        }
        PlayerEvent.OnSeekForward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = forwardStepMs))
        }
        PlayerEvent.OnSeekBackward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = -rewindStepMs))
        }
        PlayerEvent.OnReturnToLive -> {
            if (contentType.equals("iptv", ignoreCase = true)) {
                pendingPreviewSeekPosition = null
                userPausedManually = false
                _exoPlayer?.let { player ->
                    player.seekToDefaultPosition()
                    player.play()
                }
                mpvView?.let { view ->
                    view.seekToMs(currentPlaybackDurationMs().coerceAtLeast(0L))
                    view.setPaused(false)
                }
                updatePlaybackTimeline(currentPosition = currentPlaybackPositionMs() ?: 0L)
                hideControls()
            }
        }
        is PlayerEvent.OnSeekBy -> {
            pendingPreviewSeekPosition = null
            val current = currentPlaybackPositionMs() ?: 0L
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val target = (current + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            seekPlaybackTo(target, seekParametersForDelta(event.deltaMs))
            updatePlaybackTimeline(currentPosition = target)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnPreviewSeekBy -> {
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val basePosition = pendingPreviewSeekPosition ?: currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
            val target = (basePosition + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            pendingPreviewSeekPosition = target
            updatePlaybackTimeline(currentPosition = target)
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            val target = pendingPreviewSeekPosition
            if (target != null) {
                seekPlaybackTo(target, SeekParameters.CLOSEST_SYNC)
                updatePlaybackTimeline(currentPosition = target)
                pendingPreviewSeekPosition = null
                scheduleProgressSyncAfterSeek()
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSeekTo -> {
            pendingPreviewSeekPosition = null
            seekPlaybackTo(event.position, SeekParameters.CLOSEST_SYNC)
            updatePlaybackTimeline(currentPosition = event.position)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            logSwitchTrace(
                stage = "event-select-audio",
                message = "index=${event.index}"
            )
            rememberAudioSelection(event.index)
            selectAudioTrack(event.index)
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleDelayOverlay = false,
                    showSubtitleTimingDialog = false
                )
            }
        }
        is PlayerEvent.OnSetAudioDelayMs -> {
            applyAudioDelay(event.delayMs)
        }
        is PlayerEvent.OnSetAudioAmplificationDb -> {
            val clampedDb = event.db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
            applyAudioAmplification(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setAudioAmplificationDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSetPersistAudioAmplification -> {
            val currentDb = _uiState.value.audioAmplificationDb
            val currentCenterMixDb = _uiState.value.centerMixLevelDb
            _uiState.update { it.copy(persistAudioAmplification = event.enabled) }
            scope.launch {
                playerSettingsDataStore.setPersistAudioAmplification(
                    enabled = event.enabled,
                    dbToPersist = if (event.enabled) currentDb else null,
                    centerMixDbToPersist = if (event.enabled) currentCenterMixDb else null
                )
            }
        }
        is PlayerEvent.OnSetCenterMixLevelDb -> {
            val clampedDb = event.db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
            applyCenterMixLevel(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setCenterMixLevelDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSelectSubtitleTrack -> {
            logSwitchTrace(
                stage = "event-select-subtitle-internal",
                message = "index=${event.index}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberInternalSubtitleSelection(event.index)
            selectSubtitleTrack(event.index)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null
                )
            }
        }
        PlayerEvent.OnDisableSubtitles -> {
            logSwitchTrace(
                stage = "event-disable-subtitles",
                message = "selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberSubtitleDisabled()
            disableSubtitles()
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                )
            }
        }
        is PlayerEvent.OnSelectAddonSubtitle -> {
            logSwitchTrace(
                stage = "event-select-subtitle-addon",
                message = "addonId=${event.subtitle.id} addonLang=${event.subtitle.lang} addonName=${event.subtitle.addonName}"
            )
            autoSubtitleSelected = true
            rememberAddonSubtitleSelection(event.subtitle)
            selectAddonSubtitle(event.subtitle)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
            if (isUsingMpvEngine()) {
                setPlaybackSpeedInternal(event.speed)
            } else {
                _exoPlayer?.let { player ->
                    player.setPlaybackSpeed(event.speed)
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .build()
                }
            }
            _uiState.update {
                it.copy(
                    playbackSpeed = event.speed,
                    showSpeedDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
        }
        PlayerEvent.OnToggleControls -> {
            if (_uiState.value.showSubtitleTimingDialog) {
                dismissSubtitleTimingDialog()
            }
            if (_uiState.value.showSubtitleDelayOverlay) {
                hideSubtitleDelayOverlay()
            }
            val shouldShowControls = !_uiState.value.showControls
            _uiState.update {
                it.copy(
                    showControls = shouldShowControls,
                    showSeekOverlay = false,
                    showMoreDialog = if (shouldShowControls) it.showMoreDialog else false
                )
            }
            if (shouldShowControls) {
                scheduleHideControls()
            }
        }
        PlayerEvent.OnShowAudioOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = true,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleOverlay -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showAudioOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnOpenSubtitleStylePanel -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = true,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissSubtitleStylePanel -> {
            _uiState.update { it.copy(showSubtitleStylePanel = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowSubtitleTimingDialog -> {
            showSubtitleTimingDialog()
        }
        PlayerEvent.OnDismissSubtitleTimingDialog -> {
            dismissSubtitleTimingDialog()
        }
        PlayerEvent.OnCaptureSubtitleAutoSyncTime -> {
            captureSubtitleAutoSyncTime()
        }
        is PlayerEvent.OnApplySubtitleAutoSyncCue -> {
            applySubtitleAutoSyncCue(event.cueStartTimeMs)
        }
        PlayerEvent.OnReloadSubtitleAutoSyncCues -> {
            reloadSubtitleAutoSyncCues()
        }
        PlayerEvent.OnShowSubtitleDelayOverlay -> {
            showSubtitleDelayOverlay()
        }
        PlayerEvent.OnHideSubtitleDelayOverlay -> {
            hideSubtitleDelayOverlay()
        }
        is PlayerEvent.OnAdjustSubtitleDelay -> {
            adjustSubtitleDelay(event.deltaMs, event.showOverlay)
        }
        PlayerEvent.OnShowSpeedDialog -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            _uiState.update {
                it.copy(
                    showSpeedDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowMoreDialog -> {
            _uiState.update {
                it.copy(
                    showMoreDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showSpeedDialog = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> {
            showEpisodesPanel()
        }
        PlayerEvent.OnDismissEpisodesPanel -> {
            dismissEpisodesPanel()
        }
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false
                )
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> {
            selectEpisodesSeason(event.season)
        }
        is PlayerEvent.OnEpisodeSelected -> {
            loadStreamsForEpisode(event.video)
        }
        PlayerEvent.OnReloadEpisodeStreams -> {
            reloadEpisodeStreams()
        }
        is PlayerEvent.OnEpisodeAddonFilterSelected -> {
            filterEpisodeStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnEpisodeStreamSelected -> {
            switchToEpisodeStream(event.stream)
        }
        PlayerEvent.OnShowSourcesPanel -> {
            showSourcesPanel()
        }
        PlayerEvent.OnDismissSourcesPanel -> {
            dismissSourcesPanel()
        }
        PlayerEvent.OnReloadSourceStreams -> {
            loadSourceStreams(forceRefresh = true)
        }
        is PlayerEvent.OnSourceAddonFilterSelected -> {
            filterSourceStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnSourceStreamSelected -> {
            switchToSourceStream(event.stream)
        }
        PlayerEvent.OnDismissTransientOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false,
                    showMoreDialog = false
                )
            }
            scheduleHideControls()
        }
        PlayerEvent.OnRetry -> {
            hasRenderedFirstFrame = false
            hasRetriedCurrentStreamAfter416 = false
            resetErrorRetryState()
            clearPendingEngineSwitchTrackPreference()
            resetPostPlayOverlayState(clearEpisode = false)
            _uiState.update { state ->
                state.copy(
                    error = null,
                    showLoadingOverlay = state.loadingOverlayEnabled,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
            if (isTorrentStream && currentInfoHash != null) {
                releasePlayer()
                stopTorrentStream()
                launchTorrentSourceStream(
                    stream = com.nuvio.tv.domain.model.Stream(
                        name = _uiState.value.currentStreamName,
                        title = null,
                        description = null,
                        url = null,
                        ytId = null,
                        infoHash = currentInfoHash,
                        fileIdx = currentFileIdx,
                        externalUrl = null,
                        behaviorHints = null,
                        addonName = currentAddonName ?: "",
                        addonLogo = currentAddonLogo
                    ),
                    infoHash = currentInfoHash!!,
                    loadSavedProgress = true
                )
            } else {
                releasePlayer()
                initializePlayer(currentStreamUrl, currentHeaders)
            }
        }
        PlayerEvent.OnReplayFromBeginning -> {
            replayFromBeginning()
        }
        PlayerEvent.OnParentalGuideHide -> {
            _uiState.update { it.copy(showParentalGuide = false) }
        }
        PlayerEvent.OnToggleTorrentStats -> {
            _uiState.update { it.copy(showTorrentStats = !it.showTorrentStats) }
        }
        is PlayerEvent.OnShowDisplayModeInfo -> {
            _uiState.update {
                it.copy(
                    displayModeInfo = event.info,
                    showDisplayModeInfo = true
                )
            }
        }
        PlayerEvent.OnHideDisplayModeInfo -> {
            _uiState.update { it.copy(showDisplayModeInfo = false) }
        }
        PlayerEvent.OnSkipIntro -> {
            skipActiveInterval()
        }
        PlayerEvent.OnDismissSkipIntro -> {
            _uiState.update { it.copy(skipIntervalDismissed = true) }
        }
        PlayerEvent.OnPlayPreviousEpisode -> {
            playPreviousEpisode()
        }
        PlayerEvent.OnPlayNextEpisode -> {
            playNextEpisode(userInitiated = true)
        }
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            postPlayTransitionTargetKey = null
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
        }
        PlayerEvent.OnStillWatchingContinue -> onStillWatchingContinue()
        PlayerEvent.OnDismissStillWatchingPrompt -> onDismissStillWatchingPrompt()
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleTextColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleTextColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        }
        is PlayerEvent.OnSetSubtitleOutlineEnabled -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineEnabled(event.enabled) }
        }
        is PlayerEvent.OnSetSubtitleOutlineColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> {
            scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        }
        PlayerEvent.OnResetSubtitleDefaults -> {
            scope.launch {
                val defaults = SubtitleStyleSettings()
                playerSettingsDataStore.setSubtitleSize(defaults.size)
                playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
                playerSettingsDataStore.setSubtitleBold(defaults.bold)
                playerSettingsDataStore.setSubtitleOutlineEnabled(defaults.outlineEnabled)
                playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
                playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
                playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
                playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            val newMode = nextAspectMode(state.aspectMode)
            val label = aspectModeLabel(newMode, context::getString)
            Log.d(PlayerRuntimeController.TAG, "Aspect mode toggled by user: ${state.aspectMode} -> $newMode ($label)")
            _uiState.update {
                it.copy(
                    aspectMode = newMode,
                    showAspectRatioIndicator = true,
                    aspectRatioIndicatorText = label
                )
            }
            scope.launch {
                Log.d(PlayerRuntimeController.TAG, "Persisting aspect mode: $newMode")
                deviceLocalPlayerPreferences.setAspectMode(newMode)
            }
            hideAspectRatioIndicatorJob?.cancel()
            hideAspectRatioIndicatorJob = scope.launch {
                delay(1500)
                _uiState.update { it.copy(showAspectRatioIndicator = false) }
            }
        }
        PlayerEvent.OnSwitchInternalPlayerEngine -> {
            logSwitchTrace(
                stage = "event-switch-engine",
                message = "requestedByUser=true"
            )
            switchInternalPlayerEngineManually()
        }
        PlayerEvent.OnShowStreamInfo -> {
            val info = buildStreamInfoData()
            _uiState.update {
                it.copy(
                    showStreamInfoOverlay = true,
                    streamInfoData = info,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissStreamInfo -> {
            _uiState.update { it.copy(showStreamInfoOverlay = false) }
        }
    }
}

internal fun PlayerRuntimeController.buildStreamInfoData(): StreamInfoData {
    val state = _uiState.value
    val selectedAudio = state.audioTracks.firstOrNull { it.isSelected }
    val selectedSubtitle = state.subtitleTracks.firstOrNull { it.isSelected }
    val addonSub = state.selectedAddonSubtitle

    return StreamInfoData(
        addonName = currentAddonName,
        addonLogo = currentAddonLogo,
        streamName = state.currentStreamName,
        streamDescription = currentStreamDescription,
        filename = currentFilename,
        fileSize = currentVideoSize,
        videoCodec = currentVideoCodec,
        videoWidth = currentVideoWidth,
        videoHeight = currentVideoHeight,
        videoFrameRate = state.detectedFrameRate.takeIf { it > 0f },
        videoBitrate = currentVideoBitrate,
        audioCodec = selectedAudio?.codec,
        audioChannels = selectedAudio?.channelCount?.let {
            CustomDefaultTrackNameProvider.getChannelLayoutName(it)
        },
        audioSampleRate = selectedAudio?.sampleRate,
        audioLanguage = selectedAudio?.language,
        subtitleName = selectedSubtitle?.name ?: addonSub?.lang,
        subtitleCodec = selectedSubtitle?.codec,
        subtitleLanguage = selectedSubtitle?.language ?: addonSub?.lang,
        subtitleSource = when {
            addonSub != null -> context.getString(R.string.stream_info_subtitle_source_addon)
            selectedSubtitle != null -> context.getString(R.string.stream_info_subtitle_source_embedded)
            else -> null
        },
        playerEngine = when (currentInternalPlayerEngine) {
            com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
            com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
            com.nuvio.tv.data.local.InternalPlayerEngine.AUTO -> null
        }
    )
}

internal fun seekParametersForDelta(deltaMs: Long): SeekParameters {
    // PREVIOUS_SYNC/NEXT_SYNC changes the requested duration into a jump to a
    // GOP boundary.  On long-GOP IPTV/VOD files, a -10 s command could then
    // land 60 s earlier and a +30 s command minutes later.  Keep the exact
    // target requested by the user; the decoder still chooses its own safe
    // decode point internally when necessary.
    return SeekParameters.EXACT
}

internal fun PlayerRuntimeController.refreshStreamInfoDataSnapshot() {
    if (
        currentVideoWidth == null &&
        currentVideoHeight == null &&
        currentVideoCodec == null &&
        currentVideoBitrate == null &&
        _uiState.value.audioTracks.isEmpty() &&
        _uiState.value.subtitleTracks.isEmpty() &&
        _uiState.value.selectedAddonSubtitle == null
    ) {
        return
    }
    val info = buildStreamInfoData()
    _uiState.update { it.copy(streamInfoData = info) }
}

private fun formatTorrentSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}
