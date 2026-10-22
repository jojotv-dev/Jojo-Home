package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.releasePlayer() {
    releasePlayer(flushPlaybackState = true)
}

internal fun PlayerRuntimeController.releasePlayer(flushPlaybackState: Boolean) {
    if (isReleasingPlayer) return
    isReleasingPlayer = true
    val startedAt = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i(PlayerRuntimeController.TAG, "RELEASE_BEGIN at=$startedAt flush=$flushPlaybackState")
    try {
        if (flushPlaybackState) {
            runCatching { stopTorrentStream() }
            runCatching { flushPlaybackSnapshotForSwitchOrExit() }
        }

        notifyAudioSessionUpdate(false)
        unregisterAudioDelayRouteCallback()

        try {
            currentMediaSession?.release()
            currentMediaSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        progressJob?.cancel()
        progressJob = null
        vodTelemetryJob?.cancel()
        vodTelemetryJob = null
        firstFrameWatchdogJob?.cancel()
        firstFrameWatchdogJob = null
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        hideControlsJob?.cancel()
        hideControlsJob = null
        hideSeekOverlayJob?.cancel()
        hideSeekOverlayJob = null
        watchProgressSaveJob?.cancel()
        watchProgressSaveJob = null
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        frameRateProbeJob?.cancel()
        frameRateProbeJob = null
        hideAspectRatioIndicatorJob?.cancel()
        hideAspectRatioIndicatorJob = null
        hideStreamSourceIndicatorJob?.cancel()
        hideStreamSourceIndicatorJob = null
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
        hidePlayerEngineSwitchInfoJob?.cancel()
        hidePlayerEngineSwitchInfoJob = null
        hideSubtitleDelayOverlayJob?.cancel()
        hideSubtitleDelayOverlayJob = null
        subtitleAutoSyncLoadJob?.cancel()
        subtitleAutoSyncLoadJob = null
        playbackPreparationJob?.cancel()
        playbackPreparationJob = null
        traktMappingJob?.cancel()
        traktMappingJob = null
        delayMpvResumeSeekUntilVideoTrack = false
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlayJob = null
        debridResolveJob?.cancel()
        debridResolveJob = null
        stillWatchingPromptJob?.cancel()
        stillWatchingPromptJob = null
        errorRetryJob?.cancel()
        errorRetryJob = null
        stableProgressResetJob?.cancel()
        stableProgressResetJob = null
        pauseOverlayJob?.cancel()
        pauseOverlayJob = null
        bufferLogJob?.cancel()
        bufferLogJob = null
        sourceStreamsJob?.cancel()
        sourceStreamsJob = null
        sourceStreamsScope?.cancel()
        sourceStreamsScope = null
        episodeStreamsJob?.cancel()
        episodeStreamsJob = null
        episodeStreamsScope?.cancel()
        episodeStreamsScope = null
        sourceChipErrorDismissJob?.cancel()
        sourceChipErrorDismissJob = null
        torrentStreamJob?.cancel()
        torrentStreamJob = null
        torrentStateObserverJob?.cancel()
        torrentStateObserverJob = null
        releaseMpvPlayer()
        val handedOffSession = liveHandoffSession
        val handedOffPlayer = handedOffSession?.player
        if (handedOffSession != null && handedOffPlayer != null) {
            liveHandoffListener?.let { listener ->
                runCatching { handedOffPlayer.removeListener(listener) }
            }
            liveHandoffListener = null
            liveHandoffSession = null
            _exoPlayer = null
            trackSelector = null
            handedOffSession.returnOrRelease()
        } else {
            _exoPlayer?.let { player ->
                // release() already stops playback and removes surface callbacks.
                // clearVideoSurface() here added a separate blocking renderer
                // acknowledgement before the release acknowledgement.
                val exoStartedAt = android.os.SystemClock.elapsedRealtime()
                android.util.Log.i(PlayerRuntimeController.TAG, "EXO_RELEASE_BEGIN at=$exoStartedAt")
                runCatching { player.release() }.onFailure {
                    android.util.Log.e(PlayerRuntimeController.TAG, "EXO_RELEASE_FAILED", it)
                }
                android.util.Log.i(PlayerRuntimeController.TAG,
                    "EXO_RELEASE_END at=${android.os.SystemClock.elapsedRealtime()} elapsedMs=${android.os.SystemClock.elapsedRealtime() - exoStartedAt}")
            }
        }
        _exoPlayer = null
        ffmpegAudioRenderer = null
        updateAudioControlAvailability()
        playbackSpeedAwareAudioSink = null
        resetPlaybackTimeline()
    } finally {
        isReleasingPlayer = isExitRequested
        val finishedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i(PlayerRuntimeController.TAG, "RELEASE_END at=$finishedAt duration=${finishedAt - startedAt}ms")
    }
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    _exoPlayer?.let { player ->
        try {
            val intent = Intent(
                if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            )
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
