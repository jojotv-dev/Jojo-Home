package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.flow.update

/** Adopts the already-playing guide player without resolving or preparing again. */
internal fun PlayerRuntimeController.adoptLivePlayerHandoff(
    session: IptvLivePlayerHandoff.Session,
) {
    liveHandoffSession = session
    _exoPlayer = session.player
    trackSelector = session.trackSelector
    currentStreamUrl = session.streamUrl
    currentHeaders = session.streamHeaders
    currentInternalPlayerEngine = InternalPlayerEngine.EXOPLAYER
    hasRenderedFirstFrame = session.player.playbackState == Player.STATE_READY
    shouldEnforceAutoplayOnFirstReady = false

    val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isReleasingPlayer || isExitRequested) return
            val player = _exoPlayer ?: return
            val duration = player.duration.coerceAtLeast(0L)
            updatePlaybackTimeline(
                currentPosition = player.currentPosition,
                duration = duration,
                bufferedPosition = player.bufferedPosition,
            )
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    playbackEnded = false,
                    showLoadingOverlay = playbackState != Player.STATE_READY,
                    loadingMessage = null,
                )
            }
            updateAudioControlAvailability()
            if (playbackState == Player.STATE_ENDED) {
                val error = PlaybackException(
                    "Le flux direct a été interrompu", null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                )
                if (!attemptAutoRetry(error, "Fin inattendue du direct")) {
                    _uiState.update {
                        it.copy(error = "Direct indisponible. Réessayez.", showLoadingOverlay = false)
                    }
                }
            }
            refreshStableProgressResetGate()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isReleasingPlayer || isExitRequested) return
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressUpdates()
                scheduleHideControls()
            } else {
                stopProgressUpdates()
            }
            refreshStableProgressResetGate()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateAvailableTracks(tracks)
        }

        override fun onRenderedFirstFrame() {
            if (isReleasingPlayer || isExitRequested) return
            hasRenderedFirstFrame = true
            _uiState.update {
                it.copy(showLoadingOverlay = false, loadingMessage = null, isBuffering = false)
            }
            refreshStableProgressResetGate()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isReleasingPlayer || isExitRequested) return
            cancelStableProgressReset()
            if (attemptAutoRetry(error, error.localizedMessage ?: "Impossible de lire cette chaîne")) return
            _uiState.update {
                it.copy(
                    error = error.localizedMessage ?: "Impossible de lire cette chaîne",
                    showLoadingOverlay = false,
                    isBuffering = false,
                )
            }
        }
    }
    liveHandoffListener = listener
    session.player.addListener(listener)
    updateAvailableTracks(session.player.currentTracks)
    updatePlaybackTimeline(
        currentPosition = session.player.currentPosition,
        duration = session.player.duration.coerceAtLeast(0L),
        bufferedPosition = session.player.bufferedPosition,
    )
    _uiState.update {
        it.copy(
            currentStreamUrl = currentStreamUrl,
            internalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
            isPlaying = session.player.isPlaying,
            isBuffering = session.player.playbackState == Player.STATE_BUFFERING,
            showLoadingOverlay = false,
            loadingMessage = null,
            error = null,
        )
    }
    notifyAudioSessionUpdate(true)
    updateAudioControlAvailability()
    if (session.player.isPlaying) startProgressUpdates()
}

/** Updates the native controls after the guide replaces the channel on its player. */
internal fun PlayerRuntimeController.updateLiveHandoffChannel(
    id: String, channelTitle: String, description: String, artwork: String,
    url: String, headers: Map<String, String>,
) {
    val player = liveHandoffSession?.player ?: return
    if (_exoPlayer !== player || isExitRequested) return
    contentId = id
    currentVideoId = id
    title = channelTitle
    currentStreamUrl = url
    currentHeaders = headers
    _uiState.update {
        it.copy(currentVideoId = id, title = channelTitle, description = description,
            logo = artwork, poster = artwork, backdrop = null, currentStreamUrl = url,
            error = null, playbackEnded = false, showLoadingOverlay = false,
            isPlaying = player.isPlaying, isBuffering = player.playbackState == Player.STATE_BUFFERING)
    }
    updatePlaybackTimeline(currentPosition = player.currentPosition,
        duration = player.duration.coerceAtLeast(0L), bufferedPosition = player.bufferedPosition)
}

