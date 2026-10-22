package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nuvio.tv.data.local.toTrackPreference

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    val preparationStartedAt = android.os.SystemClock.elapsedRealtime()
    Log.i(
        PlayerRuntimeController.TAG,
        "FREEBOX_TRACE prepare begin at=$preparationStartedAt loadSavedProgress=$loadSavedProgress"
    )
    logSwitchTrace(
        stage = "prepare-playback-before-start",
        message = "urlHash=${url.hashCode().toUInt().toString(16)} loadSavedProgress=$loadSavedProgress " +
            "clearPendingSwitchPref=true"
    )
    clearPendingEngineSwitchTrackPreference()
    playbackPreparationJob?.cancel()

    playbackPreparationJob = scope.launch {
        refreshScrobbleItem()
        // Track preferences and resume progress are independent disk reads.
        // Start the preference read in parallel so it cannot add its full
        // latency in front of player initialization.
        val trackPreferenceJob = if (persistedTrackPreference == null) {
            async {
                contentId?.let { id ->
                    val loaded = trackPreferenceDataStore.load(id)?.toTrackPreference()
                    logSwitchTrace(
                        stage = "track-pref-load",
                        message = "contentId=$id loadedAudio=${loaded?.audio?.language}/${loaded?.audio?.name} " +
                            "loadedSubtitle=${loaded?.subtitle?.javaClass?.simpleName ?: "none"}"
                    )
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "TRACK_PREF load: contentId=$id S${currentSeason}E${currentEpisode} " +
                            "result=${if (loaded == null) "null (no saved preference)" else "audio=${loaded.audio?.language}/${loaded.audio?.name} subtitle=${loaded.subtitle?.javaClass?.simpleName}"}"
                    )
                    persistedTrackPreference = loaded
                } ?: Log.d(PlayerRuntimeController.TAG, "TRACK_PREF load: skipped (contentId is null)")
                // Subtitle delay is keyed per-videoId, so it is loaded separately
                // from the track selection above. This happens before
                // initializePlayer() because the renderers factory snapshots
                // subtitleDelayUs from _uiState.value.subtitleDelayMs on build.
                currentVideoId?.takeIf { it.isNotBlank() }?.let { vid ->
                    val savedDelayMs = trackPreferenceDataStore.loadSubtitleDelayMs(vid)
                    if (savedDelayMs != null && savedDelayMs != 0) {
                        subtitleDelayUs.set(savedDelayMs.toLong() * 1000L)
                        _uiState.update { it.copy(subtitleDelayMs = savedDelayMs) }
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "TRACK_PREF load: restored subtitleDelayMs=$savedDelayMs for videoId=$vid"
                        )
                    }
                }
            }
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF load: skipped (persistedTrackPreference already set: " +
                    "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                    "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName})"
            )
            logSwitchTrace(
                stage = "track-pref-load",
                message = "skipped=true reason=persisted-already-set " +
                    "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                    "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName ?: "none"}"
            )
            null
        }
        // Resume and track preferences are independent reads. Await both only
        // after starting them so a slow DataStore read cannot serialize startup.
        val savedProgressJob = if (loadSavedProgress) {
            async { loadSavedProgressSuspend(currentSeason, currentEpisode) }
        } else {
            null
        }
        // Load saved watch progress BEFORE player init.
        // This eliminates the race condition where ExoPlayer's STATE_READY
        // callback fired before the DB read completed, causing the resume
        // seek to be silently skipped — the player would start from 0:00
        // or hang in buffering after a late seek.
        savedProgressJob?.await()
        trackPreferenceJob?.await()
        Log.i(
            PlayerRuntimeController.TAG,
            "FREEBOX_TRACE prepare ready at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - preparationStartedAt}ms"
        )
        initializePlayer(url, headers)
    }
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
}

internal fun PlayerRuntimeController.currentEpisodeMappingCacheKey(): String? {
    val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    val videoId = currentVideoId?.trim().orEmpty()
    return "$resolvedType|$resolvedContentId|$videoId|$season|$episode"
}
