package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.OpenSubtitlesHasher
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.jojotv.home.R
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield

private fun String.startupTimeoutHost(): String = runCatching {
    android.net.Uri.parse(this).host?.takeIf { it.isNotBlank() } ?: "unknown"
}.getOrDefault("unknown")

internal data class SubtitleFetchRequest(
    val type: String,
    val id: String,
    val videoId: String?
)

internal fun PlayerRuntimeController.buildSubtitleFetchRequest(): SubtitleFetchRequest? {
    if (isFreeboxPlayback) return null
    val id = contentId ?: return null
    val type = contentType ?: return null
    return SubtitleFetchRequest(
        type = type.lowercase(),
        id = id,
        videoId = currentVideoId
    )
}

internal suspend fun PlayerRuntimeController.fetchAddonSubtitlesNow(
    onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null
): List<Subtitle> {
    val request = buildSubtitleFetchRequest() ?: return emptyList()
    val installedAddonOrder = addonRepository.getInstalledAddons().firstOrNull()
        ?.enabledAddons()
        ?.map { it.displayName }
        .orEmpty()
    _uiState.update { it.copy(installedSubtitleAddonOrder = installedAddonOrder) }

    // Compute hash lazily for providers that support OpenSubtitles-style matching.
    if (!isFreeboxPlayback && currentVideoHash == null && currentStreamUrl.isNotBlank()) {
        val result = OpenSubtitlesHasher.compute(currentStreamUrl, currentHeaders)
        if (result != null) {
            currentVideoHash = result.hash
            if (currentVideoSize == null) currentVideoSize = result.fileSize
            // Update cache now that we have the computed hash.
            // For torrent streams we cache the torrent identity (infoHash + fileIdx
            // + sources) instead of the localhost URL — the URL is ephemeral and
            // won't survive an app restart, but the identity is enough to
            // re-establish the stream from scratch on next launch.
            val key = streamCacheKey
            if (key != null) {
                val state = _uiState.value
                val torrentInfoHash = currentInfoHash
                if (isTorrentStream && torrentInfoHash != null) {
                    streamLinkCacheDataStore.save(
                        contentKey = key,
                        url = "",
                        streamName = state.currentStreamName ?: title,
                        headers = emptyMap(),
                        filename = currentFilename,
                        videoHash = currentVideoHash,
                        videoSize = currentVideoSize,
                        infoHash = torrentInfoHash,
                        fileIdx = currentFileIdx,
                        sources = currentTorrentSources,
                        bingeGroup = currentStreamBingeGroup
                    )
                } else if (currentStreamUrl.isNotBlank()) {
                    streamLinkCacheDataStore.save(
                        contentKey = key,
                        url = currentStreamUrl,
                        streamName = state.currentStreamName ?: title,
                        headers = currentHeaders,
                        filename = currentFilename,
                        videoHash = currentVideoHash,
                        videoSize = currentVideoSize,
                        bingeGroup = currentStreamBingeGroup
                    )
                }
            }
        }
    }

    return subtitleRepository.getSubtitles(
        type = request.type,
        id = request.id,
        videoId = request.videoId,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        filename = currentFilename,
        onProgress = onProgress
    )
}

internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    if (buildSubtitleFetchRequest() == null) return

    scope.launch {
        _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }

        try {
            val subtitles = fetchAddonSubtitlesNow()
            val visibleSubtitles = filterToVisibleAddonSubtitles(subtitles)
            Log.d(PlayerRuntimeController.TAG, "fetchAddonSubtitles done: ${subtitles.size} subs, visible=${visibleSubtitles.size}, persistedPref=${persistedTrackPreference?.subtitle?.javaClass?.simpleName}")
            _uiState.update {
                it.copy(
                    addonSubtitles = visibleSubtitles,
                    isLoadingAddonSubtitles = false
                )
            }
            val pendingAddon = pendingRestoredAddonSubtitle
            if (pendingAddon != null) {
                val match = visibleSubtitles.firstOrNull { it.id == pendingAddon.id }
                    ?: visibleSubtitles.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.lang, pendingAddon.lang) }
                if (match != null) {
                    autoSubtitleSelected = true
                    selectAddonSubtitle(match)
                    _uiState.update { it.copy(selectedAddonSubtitle = match, selectedSubtitleTrackIndex = -1) }
                    return@launch
                }
            }
            applyPersistedTrackPreference(
                audioTracks = _uiState.value.audioTracks,
                subtitleTracks = _uiState.value.subtitleTracks
            )
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingAddonSubtitles = false,
                    addonSubtitlesError = e.message
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.refreshSubtitlesForCurrentEpisode() {
    autoSubtitleSelected = false
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    resetSubtitleAutoSyncState()
    attachedAddonSubtitleKeys = emptySet()
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = true,
            addonSubtitlesError = null
        )
    }
    fetchAddonSubtitles()
}

internal fun PlayerRuntimeController.filterToVisibleAddonSubtitles(
    subtitles: List<Subtitle>
): List<Subtitle> {
    val style = _uiState.value.subtitleStyle
    if (!style.showOnlyPreferredLanguages) return subtitles

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(style.preferredLanguage)) {
        "none" -> listOfNotNull(
            style.secondaryPreferredLanguage?.takeIf { it.isNotBlank() },
            if (style.useForcedSubtitles) {
                selectedAudioTrackForSubtitleMatching(_uiState.value)
                    ?.takeIf { selectedAudioMatchesResolvedPreferredAudio(it) }
                    ?.let { selectedAudioLanguageTarget(it) }
            } else {
                null
            }
        )
        else -> listOfNotNull(
            style.preferredLanguage,
            style.secondaryPreferredLanguage?.takeIf { it.isNotBlank() }
        )
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        .distinct()

    if (preferredTargets.isEmpty()) {
        return if (
            style.useForcedSubtitles &&
            PlayerSubtitleUtils.normalizeLanguageCode(style.preferredLanguage) == "none" &&
            selectedAudioTrackForSubtitleMatching(_uiState.value) == null
        ) {
            subtitles
        } else {
            emptyList()
        }
    }

    return subtitles.filter { subtitle ->
        preferredTargets.any { target ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
        }
    }
}

internal fun PlayerRuntimeController.observeBlurUnwatchedEpisodes() {
    scope.launch {
        layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
            _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
        }
    }
}

internal fun PlayerRuntimeController.observeEpisodeWatchProgress() {
    val id = contentId ?: return
    val type = contentType ?: return
    if (type.lowercase() !in listOf("series", "tv", "iptv_series", "other", "freebox")) return
    // Provider prefixes are part of the stored series identity. Truncating at
    // ':' turns every IPTV series into the same key and hides its episode history.
    scope.launch {
        watchProgressRepository.getAllEpisodeProgress(id).collectLatest { progressMap ->
            _uiState.update { it.copy(episodeWatchProgressMap = progressMap) }
        }
    }
    scope.launch {
        watchedItemsPreferences.getWatchedEpisodesForContent(id).collectLatest { watchedSet ->
            _uiState.update { it.copy(watchedEpisodeKeys = watchedSet) }
        }
    }
}

internal fun PlayerRuntimeController.applyPreferredAudioLanguageSettings(settings: PlayerSettings) {
    val languages = resolvePreferredAudioLanguages(
        settings.preferredAudioLanguage, settings.secondaryPreferredAudioLanguage,
        resolveDeviceAudioLanguages(), contentLanguage)
    if (languages != mpvPreferredAudioLanguages) {
        mpvPreferredAudioLanguages = languages
        if (isUsingMpvEngine()) {
            mpvView?.applyAudioLanguagePreferences(languages)
            updateMpvAvailableTracks()
        }
    }
    _exoPlayer?.let { player ->
        if (player.trackSelectionParameters.preferredAudioLanguages != languages) {
            // Keep explicit track overrides made by the user during this playback.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages(*languages.toTypedArray()).build()
        }
    }
}

internal fun PlayerRuntimeController.observeSubtitleSettings() {
    scope.launch {
        // Diagnostics share this DataStore; they must not reapply unchanged track settings.
        playerSettingsDataStore.playerSettings.map { sharedSettings ->
            com.jojotv.home.iptv.IptvStore.applyPlayerSettings(context, contentId, sharedSettings)
        }.distinctUntilChanged().collect { settings ->
            val currentState = _uiState.value
            val showOnlyPreferredLanguagesChanged =
                currentState.subtitleStyle.showOnlyPreferredLanguages != settings.subtitleStyle.showOnlyPreferredLanguages
            val wasRememberingAudioDelayPerDevice = rememberAudioDelayPerDeviceEnabled
            rememberAudioDelayPerDeviceEnabled = settings.rememberAudioDelayPerDevice
            val resolvedInternalPlayerEngine = when {
                navigationArgs.liveHandoffToken != null || liveHandoffSession != null ->
                    com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER
                PlayerMediaSourceFactory.isFreeboxRtspUrl(currentStreamUrl) -> com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER
                else -> runtimeInternalPlayerEngineOverride ?: resolvedAutoPlayerEngine ?: settings.internalPlayerEngine
            }
            val resolvedAudioAmplificationDb = when {
                !hasInitializedAudioAmplificationForSession -> {
                    hasInitializedAudioAmplificationForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.audioAmplificationDb
                    } else {
                        AUDIO_AMPLIFICATION_MIN_DB
                    }
                }
                settings.persistAudioAmplification -> settings.audioAmplificationDb
                else -> currentState.audioAmplificationDb
            }
            val resolvedCenterMixLevelDb = when {
                !hasInitializedCenterMixForSession -> {
                    hasInitializedCenterMixForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.centerMixLevelDb
                    } else {
                        0
                    }
                }
                settings.persistAudioAmplification -> settings.centerMixLevelDb
                else -> currentState.centerMixLevelDb
            }

            _uiState.update { state ->
                val shouldShowOverlay = when {
                    !settings.loadingOverlayEnabled -> false
                    !hasRenderedFirstFrame && state.isBuffering -> true
                    else -> state.showLoadingOverlay
                }

                state.copy(
                    subtitleStyle = settings.subtitleStyle,
                    loadingOverlayEnabled = settings.loadingOverlayEnabled,
                    showPlayerLoadingStatus = settings.showPlayerLoadingStatus,
                    showLoadingOverlay = shouldShowOverlay,
                    loadingMessage = if (settings.showPlayerLoadingStatus || state.isTorrentStream) state.loadingMessage else null,
                    pauseOverlayEnabled = settings.pauseOverlayEnabled,
                    osdClockEnabled = settings.osdClockEnabled,
                    internalPlayerEngine = resolvedInternalPlayerEngine,
                    frameRateMatchingMode = settings.frameRateMatchingMode,
                    tunnelingEnabled = settings.tunnelingEnabled,
                    persistAudioAmplification = settings.persistAudioAmplification,
                    audioAmplificationDb = resolvedAudioAmplificationDb,
                    centerMixLevelDb = resolvedCenterMixLevelDb
                )
            }

            if (resolvedAudioAmplificationDb != currentState.audioAmplificationDb) {
                applyAudioAmplification(resolvedAudioAmplificationDb)
            }
            if (resolvedCenterMixLevelDb != currentState.centerMixLevelDb) {
                applyCenterMixLevel(resolvedCenterMixLevelDb)
            }

            if (settings.rememberAudioDelayPerDevice && !wasRememberingAudioDelayPerDevice) {
                registerAudioDelayRouteCallback()
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            } else if (!settings.rememberAudioDelayPerDevice && wasRememberingAudioDelayPerDevice) {
                unregisterAudioDelayRouteCallback()
            }

            bufferLogsEnabled = settings.enableBufferLogs
            if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
                frameRateProbeJob?.cancel()
                _uiState.update {
                    it.copy(
                        detectedFrameRateRaw = 0f,
                        detectedFrameRate = 0f,
                        detectedFrameRateSource = null,
                        afrProbeRunning = false
                    )
                }
            }

            if (!settings.pauseOverlayEnabled) {
                cancelPauseOverlay()
            } else if (!_uiState.value.isPlaying &&
                !_uiState.value.showPauseOverlay && pauseOverlayJob == null &&
                userPausedManually && hasRenderedFirstFrame
            ) {
                schedulePauseOverlay()
            }
            streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled
            autoSwitchInternalPlayerOnErrorEnabled = settings.autoSwitchInternalPlayerOnError
            currentInternalPlayerEngine = resolvedInternalPlayerEngine
            streamAutoPlayModeSetting = settings.streamAutoPlayMode
            streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
            _uiState.update {
                it.copy(
                    streamAutoPlayMode = settings.streamAutoPlayMode,
                    streamAutoPlayNextEpisodeEnabled = settings.streamAutoPlayNextEpisodeEnabled,
                    streamAutoPlayPreferBingeGroupForNextEpisode = settings.streamAutoPlayPreferBingeGroupForNextEpisode
                )
            }
            streamAutoPlayPreferBingeGroupForNextEpisodeSetting =
                settings.streamAutoPlayPreferBingeGroupForNextEpisode
            nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
            nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
            nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd
            stillWatchingEnabledSetting = settings.stillWatchingEnabled
            stillWatchingEpisodeThresholdSetting = settings.stillWatchingEpisodeThreshold

            // VOD cache config is gated by the "Custom Playback Buffers" master.
            // When the master is off the cache is disabled at player build time, so
            // don't push live size updates to it here either (keeps the factory from
            // carrying cache config the master has turned off).
            if (settings.bufferEngineEnabled) {
                mediaSourceFactory.vodCacheSizeMode = settings.vodCacheSizeMode
                mediaSourceFactory.vodCacheSizeMb = settings.vodCacheSizeMb
            }

            val previousMpvHardwareDecodeMode = mpvHardwareDecodeModeSetting
            mpvHardwareDecodeModeSetting = settings.mpvHardwareDecodeMode
            if (isUsingMpvEngine() && previousMpvHardwareDecodeMode != mpvHardwareDecodeModeSetting) {
                mpvView?.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
            }

            applyPreferredAudioLanguageSettings(settings)

            applySubtitlePreferences(
                settings.subtitleStyle.preferredLanguage,
                settings.subtitleStyle.secondaryPreferredLanguage
            )
            val subtitlePreferenceChanged =
                lastSubtitlePreferredLanguage != settings.subtitleStyle.preferredLanguage ||
                    lastSubtitleSecondaryLanguage != settings.subtitleStyle.secondaryPreferredLanguage ||
                    lastUseForcedSubtitles != settings.subtitleStyle.useForcedSubtitles
            if (subtitlePreferenceChanged) {
                if (!subtitleDisabledByPersistedPreference && !subtitleAddonRestoredByPersistedPreference) autoSubtitleSelected = false
                lastSubtitlePreferredLanguage = settings.subtitleStyle.preferredLanguage
                lastSubtitleSecondaryLanguage = settings.subtitleStyle.secondaryPreferredLanguage
                lastUseForcedSubtitles = settings.subtitleStyle.useForcedSubtitles
                tryAutoSelectPreferredSubtitleFromAvailableTracks()
            }

            if (showOnlyPreferredLanguagesChanged) {
                if (settings.subtitleStyle.showOnlyPreferredLanguages) {
                    _uiState.update { state ->
                        val visibleSubtitles = filterToVisibleAddonSubtitles(state.addonSubtitles)
                        state.copy(
                            addonSubtitles = visibleSubtitles,
                            selectedAddonSubtitle = state.selectedAddonSubtitle?.takeIf { selected ->
                                visibleSubtitles.any { it.id == selected.id }
                            }
                        )
                    }
                } else if (_uiState.value.addonSubtitles.isNotEmpty() || _uiState.value.selectedAddonSubtitle != null) {
                    fetchAddonSubtitles()
                }
            }

            val wasEnabled = skipIntroEnabled
            skipIntroEnabled = settings.skipIntroEnabled
            parentalGuideEnabled = settings.parentalGuideEnabled
            rewindStepMs = settings.rewindStepSeconds * 1_000L
            forwardStepMs = settings.forwardStepSeconds * 1_000L
            autoSkipSegmentTypes = settings.autoSkipSegmentTypes
            playerSettingsInitialized = true

            // Fetch parental guide on first settings emission (after we know
            // whether the feature is enabled). Subsequent emissions skip this.
            if (settings.parentalGuideEnabled && _uiState.value.parentalWarnings.isEmpty()) {
                fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
            }

            if (!skipIntroEnabled) {
                // Invalidate pending lookups too: a late reply must not re-enable the button.
                skipIntervals = emptyList()
                skipIntroFetchedKey = null
                lastAutoSkippedIntervalKey = null
                if (_uiState.value.activeSkipInterval != null || !_uiState.value.skipIntervalDismissed) {
                    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
                }
            } else {
                if (!wasEnabled || skipIntroFetchedKey == null) {
                    _uiState.update { it.copy(skipIntervalDismissed = false) }
                    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.loadSavedProgressFor(season: Int?, episode: Int?) {
    val contentId = contentId ?: return

    scope.launch {
        pendingResumeProgress = null
        val repositoryProgress = if (season != null && episode != null) {
            watchProgressRepository.getEpisodeProgress(contentId, season, episode).firstOrNull()
        } else {
            watchProgressRepository.getProgress(contentId).firstOrNull()
        }
        val progress = listOfNotNull(repositoryProgress, loadFreeboxHomeResume(contentId, season, episode))
            .maxByOrNull { it.lastWatched }

        progress?.let { saved ->

            if (saved.position > 0L || saved.progressPercent?.let { it > 0f } == true) {
                pendingResumeProgress = saved
                if (isUsingMpvEngine()) {
                    _uiState.update { it.copy(pendingSeekPosition = null) }
                    mpvView?.let { view ->
                        applyPendingMpvSeekIfNeeded(view)
                    }
                } else {
                    _exoPlayer?.let { player ->
                        // Désactivé pour éviter les blocages de flux réseau (IPTV) lors d'un seek précoce.
                        // Le seek se fera de manière sécurisée dans onRenderedFirstFrame().
                        // if (player.playbackState == Player.STATE_READY) {
                        //     tryApplyPendingResumeProgress(player)
                        // }
                    }
                }
            }
        }
    }
}

/**
 * Suspend variant of [loadSavedProgressFor] that completes the DB read inline
 * instead of launching a fire-and-forget coroutine.
 *
 * This MUST be called **before** [initializePlayer] inside [preparePlaybackBeforeStart]
 * so that [pendingResumeProgress] is guaranteed to be set by the time ExoPlayer's
 * `STATE_READY` callback fires.  The fire-and-forget version races against the
 * player lifecycle and can lose the resume position entirely.
 */
internal suspend fun PlayerRuntimeController.loadSavedProgressSuspend(season: Int?, episode: Int?) {
    val contentId = contentId ?: return

    pendingResumeProgress = null
    val repositoryProgress = if (season != null && episode != null) {
        watchProgressRepository.getEpisodeProgress(contentId, season, episode).firstOrNull()
    } else {
        watchProgressRepository.getProgress(contentId).firstOrNull()
    }
    // A recent rewind must win over an older, larger position in either store.
    val progress = listOfNotNull(repositoryProgress, loadFreeboxHomeResume(contentId, season, episode))
        .maxByOrNull { it.lastWatched }

    progress?.let { saved ->
        // Continue Watching deliberately keeps a started item until the user
        // removes it.  Its playback path must use the same rule: a video stopped
        // after the old 90% completion threshold is still resumed locally/NAS,
        // rather than silently restarting at 0:00.
        if (saved.position > 0L || saved.progressPercent?.let { it > 0f } == true) {
            pendingResumeProgress = saved
            Log.d(
                PlayerRuntimeController.TAG,
                "loadSavedProgressSuspend: set pendingResumeProgress " +
                    "position=${saved.position} duration=${saved.duration} " +
                    "percent=${saved.progressPercent} S${season}E${episode}"
            )
        }
    }
}

private fun PlayerRuntimeController.loadFreeboxHomeResume(
    id: String,
    season: Int?,
    episode: Int?
): WatchProgress? {
    if (com.jojotv.home.HomeWatchProgressBridge.isCloud(context)) return null
    // The Home/NAS record is authoritative, including explicit zero/reset values.
    if (id.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX)) {
        val item = com.jojotv.home.ExternalPlaybackStore.get(context,
            id.removePrefix(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX))
        if (item.optInt("progressProfileId", 1) != com.jojotv.home.HomeWatchProgressBridge.currentProfileId(context)) return null
        // A series record tracks the last episode played; only reuse it for that exact episode.
        if ((season != null || episode != null) &&
            (season == null || episode == null ||
                item.optInt("season", 0) != season || item.optInt("episode", 0) != episode)
        ) {
            return null
        }
        return WatchProgress(
            contentId = id, contentType = if (item.optString("category") == "live") "iptv" else "home-external", name = item.optString("title", "Vidéo"),
            poster = null, backdrop = null, logo = null, videoId = id,
            season = null, episode = null, episodeTitle = null,
            position = if (item.optBoolean("deleted") || item.optString("category") == "live") 0L
                else item.optLong("position", 0L).coerceAtLeast(0L),
            duration = item.optLong("duration", 0L).coerceAtLeast(0L),
            lastWatched = item.optLong("lastPlayedAt", item.optLong("updatedAt", 0L)), source = WatchProgress.SOURCE_LOCAL
        )
    }
    if (!id.startsWith("freebox:", ignoreCase = true)) return null
    // Fingerprinted player IDs still read the legacy path-based Home record.
    // MainActivity clears that record before launch when size/date changed.
    val source = freeboxPathFromContentId(id)
    if (source.isBlank()) return null
    return runCatching {
        val prefs = context.getSharedPreferences("home_settings", android.content.Context.MODE_PRIVATE)
        val entries = org.json.JSONArray(prefs.getString("file_video_resumes", "[]"))
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            if (item.optInt("profileId", 1) != com.jojotv.home.HomeWatchProgressBridge.currentProfileId(context)) continue
            val key = item.optString("key", item.optString("source", ""))
            if (!source.equals(key, ignoreCase = false)) continue
            // Read the same per-file value as the Home card, including an
            // explicit zero after a reset instead of reviving old DB progress.
            val position = prefs.getLong("video_resume_$source", item.optLong("position", 0L))
                .coerceAtLeast(0L)
            return@runCatching WatchProgress(
                contentId = id,
                contentType = "freebox",
                name = item.optString("title", "Vidéo"),
                poster = null,
                backdrop = null,
                logo = null,
                videoId = id,
                season = null,
                episode = null,
                episodeTitle = null,
                position = position,
                duration = item.optLong("duration", 0L),
                lastWatched = item.optLong("updatedAt", 0L),
                source = WatchProgress.SOURCE_LOCAL
            )
        }
        null
    }.getOrNull()
}

internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    if (id.isNullOrBlank()) return

    // Prefer videoId over contentId — videoId carries the season/episode-specific ID
    val effectiveId = currentVideoId?.takeIf { it.isNotBlank() } ?: id

    // MAL ID format: "mal:57658:1" (malId:episode)
    if (effectiveId.startsWith("mal:")) {
        val parts = effectiveId.split(":")
        val malId = parts.getOrNull(1) ?: return
        val malEpisode = parts.getOrNull(2)?.toIntOrNull() ?: episode ?: return
        val key = "mal:$malId:$malEpisode"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        scope.launch {
            val intervals = withTimeoutOrNull(15_000L) {
                skipIntroRepository.getSkipIntervalsForMal(malId, malEpisode)
            } ?: emptyList()
            if (skipIntroEnabled && skipIntroFetchedKey == key) skipIntervals = intervals
        }
        return
    }

    // Kitsu ID format: "kitsu:12345:1" (kitsuId:episode)
    if (effectiveId.startsWith("kitsu:")) {
        val parts = effectiveId.split(":")
        val kitsuId = parts.getOrNull(1) ?: return
        val kitsuEpisode = parts.getOrNull(2)?.toIntOrNull() ?: episode ?: return
        val key = "kitsu:$kitsuId:$kitsuEpisode"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        scope.launch {
            val intervals = withTimeoutOrNull(15_000L) {
                skipIntroRepository.getSkipIntervalsForKitsu(kitsuId, kitsuEpisode)
            } ?: emptyList()
            if (skipIntroEnabled && skipIntroFetchedKey == key) skipIntervals = intervals
        }
        return
    }

    val fileEpisode = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?!\d)""")
        .find(currentFilename.orEmpty())
    val resolvedSeason = season ?: fileEpisode?.groupValues?.get(1)?.toIntOrNull()
    val resolvedEpisode = episode ?: fileEpisode?.groupValues?.get(2)?.toIntOrNull()
    if (resolvedSeason == null || resolvedEpisode == null) {
        Log.d(
            "SkipIntro",
            "skip lookup skipped: missing episode season=$resolvedSeason episode=$resolvedEpisode " +
                "videoId=${currentVideoId.orEmpty()} filename=${currentFilename.orEmpty()}"
        )
        return
    }

    val imdbId = sequenceOf(effectiveId, id)
        .mapNotNull { it.split(":").firstOrNull()?.takeIf { part -> part.matches(Regex("tt\\d+")) } }
        .firstOrNull()
    val parsedTmdbId = sequenceOf(effectiveId, id)
        .mapNotNull { value ->
            value.split(":")
                .takeIf { parts -> parts.firstOrNull().equals("tmdb", ignoreCase = true) }
                ?.getOrNull(1)?.toIntOrNull()
        }
        .firstOrNull()
    val resolvedTmdbId = skipIntroTvTmdbId ?: tmdbId ?: parsedTmdbId
    val isTvIdentity = navigationArgs.tmdbMediaType.equals("tv", ignoreCase = true) ||
        skipIntroTvTmdbId != null ||
        contentType.equals("series", ignoreCase = true) ||
        contentType.equals("tv", ignoreCase = true) ||
        contentType.equals("iptv_series", ignoreCase = true)
    if (imdbId == null && (resolvedTmdbId == null || !isTvIdentity)) {
        Log.d(
            "SkipIntro",
            "skip lookup skipped: no IMDb/TV TMDB identity id=${id.orEmpty()} " +
                "videoId=${currentVideoId.orEmpty()} type=${contentType.orEmpty()}"
        )
        return
    }

    val key = "${imdbId ?: "tmdb:$resolvedTmdbId"}:$resolvedSeason:$resolvedEpisode"
    if (skipIntroFetchedKey == key) return
    skipIntroFetchedKey = key

    scope.launch {
        val resolvedImdbId = imdbId ?: withTimeoutOrNull(15_000L) {
            tmdbService.tmdbToImdb(resolvedTmdbId!!, "tv")
        }
        if (!skipIntroEnabled || skipIntroFetchedKey != key) return@launch
        if (resolvedImdbId.isNullOrBlank()) {
            skipIntroFetchedKey = null
            return@launch
        }
        val intervals = withTimeoutOrNull(15_000L) {
            skipIntroRepository.getSkipIntervals(resolvedImdbId, resolvedSeason, resolvedEpisode)
        } ?: emptyList()
        Log.d(
            "SkipIntro",
            "lookup result id=$resolvedImdbId S$resolvedSeason E$resolvedEpisode intervals=${intervals.size}"
        )
        if (skipIntroEnabled && skipIntroFetchedKey == key) skipIntervals = intervals
    }
}

/** Absolute positions can be applied before prepare; percentages without a duration must wait for the timeline. */
internal fun initialResumeSeekPosition(saved: WatchProgress?, pendingSeekPosition: Long?, isLive: Boolean): Long? {
    if (isLive) return null
    val savedPosition = saved?.position?.takeIf { it > 0L }
        ?: saved?.takeIf { it.duration > 0L }?.let { it.resolveResumePosition(it.duration).takeIf { position -> position > 0L } }
    return savedPosition ?: pendingSeekPosition?.takeIf { it > 0L }
}

internal fun initialResumeAutoplayOnReady(contentId: String?, contentType: String?, position: Long?): Boolean =
    contentId?.startsWith("iptv:") == true &&
        (contentType.equals("movie", ignoreCase = true) || contentType.equals("series", ignoreCase = true)) &&
        position != null && position > 0L

internal fun initialResumeSeekReached(target: Long?, position: Long): Boolean =
    target != null && target > 0L && position >= target && position - target <= 1000L

internal fun PlayerRuntimeController.confirmPreparedResumeSeek(player: Player) {
    val target = preparedResumeSeekPositionMs ?: return
    preparedResumeSeekPositionMs = null
    // A portal can ignore an initial seek until its timeline becomes seekable.
    // Retain the saved progress in that case for the existing delayed resume path.
    if (!initialResumeSeekReached(target, player.currentPosition)) return
    pendingResumeProgress = null
    deferResumeSeekUntilPlaybackStarts = false
    _uiState.update { it.copy(pendingSeekPosition = null) }
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgress(player: Player) {
    val saved = pendingResumeProgress ?: return
    val duration = player.duration
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }

    if (target <= 0L) {
        Log.d(PlayerRuntimeController.TAG, "Resume ignored: no valid saved position")
        pendingResumeProgress = null
        resumeSeekOverlayTargetMs = null
        deferResumeSeekUntilPlaybackStarts = false
        _uiState.update { it.copy(pendingSeekPosition = null, showResumeSeekOverlay = false) }
        return
    }

    if (!player.isCurrentMediaItemSeekable) {
        // IPTV streams can report themselves non-seekable on their first rendered
        // frame even though the VOD becomes seekable immediately after playback
        // starts.  Keep the saved position and apply it from onIsPlayingChanged()
        // instead of losing the resume permanently.
        deferResumeSeekUntilPlaybackStarts = true
        _uiState.update { it.copy(pendingSeekPosition = target) }
        Log.d(
            PlayerRuntimeController.TAG,
            "Resume deferred until playback starts: target=${target}ms duration=${duration}ms"
        )
        return
    }

    Log.d(PlayerRuntimeController.TAG, "Resume applied: target=${target}ms duration=${duration}ms")
    deferResumeSeekUntilPlaybackStarts = false
    player.seekTo(target)
    resumeSeekOverlayTargetMs = target
    _uiState.update { it.copy(pendingSeekPosition = null) }
    pendingResumeProgress = null
}

internal fun PlayerRuntimeController.applyDeferredResumeSeekAfterPlaybackStart() {
    if (!deferResumeSeekUntilPlaybackStarts) return
    val player = _exoPlayer ?: return
    val target = _uiState.value.pendingSeekPosition ?: return
    deferResumeSeekUntilPlaybackStarts = false
    _uiState.update { it.copy(pendingSeekPosition = null) }
    scope.launch {
        delay(IPTV_DEFERRED_RESUME_SEEK_DELAY_MS)
        val livePlayer = _exoPlayer ?: return@launch
        if (isReleasingPlayer) return@launch
        if (livePlayer.playbackState == Player.STATE_IDLE || livePlayer.playbackState == Player.STATE_ENDED) return@launch
        Log.d(PlayerRuntimeController.TAG, "IPTV resume: applying delayed seek to ${target}ms")
        livePlayer.seekTo(target)
        resumeSeekOverlayTargetMs = target
    }
}

internal fun PlayerRuntimeController.maybeFinishResumeSeekOverlay(positionMs: Long) {
    val target = resumeSeekOverlayTargetMs ?: return
    val revealThreshold = if (target <= 1_500L) target else target - 1_000L
    if (positionMs < revealThreshold) return
    resumeSeekOverlayTargetMs = null
    _uiState.update { it.copy(showResumeSeekOverlay = false) }
    Log.d(PlayerRuntimeController.TAG, "IPTV resume artwork dismissed at position=${positionMs}ms target=${target}ms")
}

private const val IPTV_DEFERRED_RESUME_SEEK_DELAY_MS = 0L

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamFromStartAfter416() {
    if (hasRetriedCurrentStreamAfter416) return
    hasRetriedCurrentStreamAfter416 = true
    pendingResumeProgress = null
    deferResumeSeekUntilPlaybackStarts = false
    scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterTimeout(fromPositionMs: Long) {
    if (timeoutRecoveryAttempts >= PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) return
    timeoutRecoveryAttempts += 1
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterUnexpectedNpe(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamAfterMediaPeriodHolderCrash(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithSafeAudioFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithAudioDisabled(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDolbyVisionFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDv7Mode1Fallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1SoftwareFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1TrackSelectionBypass(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.cancelFirstFrameWatchdog() {
    firstFrameWatchdogJob?.cancel()
    firstFrameWatchdogJob = null
}

internal fun PlayerRuntimeController.cancelStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
}

/** Tiny skip past the buffered edge to force Media3 to cancel the in-flight Range request. */
private const val STALL_WATCHDOG_SKIP_PAST_BUFFERED_MS = 250L

internal fun stallWatchdogSeekTargetMs(
    bufferedNowMs: Long,
    playheadMs: Long,
    durationMs: Long
): Long? {
    if (durationMs == C.TIME_UNSET || durationMs <= 0L) return null
    if (bufferedNowMs <= playheadMs) return null
    val targetPastBufferedMs = if (bufferedNowMs > Long.MAX_VALUE - STALL_WATCHDOG_SKIP_PAST_BUFFERED_MS) {
        Long.MAX_VALUE
    } else {
        bufferedNowMs + STALL_WATCHDOG_SKIP_PAST_BUFFERED_MS
    }
    val seekTargetMs = targetPastBufferedMs.coerceAtMost((durationMs - 1L).coerceAtLeast(0L))
    if (seekTargetMs <= playheadMs || seekTargetMs <= 0L) return null
    return seekTargetMs
}

/** Re-seeks past the buffered edge when bufferedPosition stops advancing during buffering. */
internal fun PlayerRuntimeController.maybeScheduleStallWatchdog() {
    if (stallWatchdogJob?.isActive == true) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_BUFFERING) return

    stallWatchdogJob = scope.launch {
        var lastBufferedPosition = player.bufferedPosition
        var lastAdvanceAtMs = System.currentTimeMillis()

        while (isActive) {
            delay(PlayerRuntimeController.STALL_WATCHDOG_POLL_INTERVAL_MS)
            val livePlayer = _exoPlayer ?: return@launch
            if (livePlayer.playbackState != Player.STATE_BUFFERING) {
                // Buffering resolved on its own.
                return@launch
            }

            val nowMs = System.currentTimeMillis()
            val bufferedNow = livePlayer.bufferedPosition
            if (bufferedNow > lastBufferedPosition) {
                // Real progress — reset the stall timer.
                lastBufferedPosition = bufferedNow
                lastAdvanceAtMs = nowMs
                continue
            }

            val stalledForMs = nowMs - lastAdvanceAtMs
            if (stalledForMs >= PlayerRuntimeController.STALL_WATCHDOG_THRESHOLD_MS) {
                val playheadMs = livePlayer.currentPosition.coerceAtLeast(0L)
                val durationMs = livePlayer.duration
                val seekTargetMs = stallWatchdogSeekTargetMs(
                    bufferedNowMs = bufferedNow,
                    playheadMs = playheadMs,
                    durationMs = durationMs
                )
                if (seekTargetMs == null) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=$playheadMs); skipping self-seek " +
                            "because duration=$durationMs or target is not safely ahead"
                    )
                    return@launch
                }
                Log.w(
                    PlayerRuntimeController.TAG,
                    "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                        "during STATE_BUFFERING (playhead=$playheadMs); seeking past buffered " +
                        "edge to $seekTargetMs to break stuck request"
                )
                livePlayer.seekTo(seekTargetMs)
                return@launch
            }
        }
    }
}

internal fun PlayerRuntimeController.maybeScheduleFirstFrameWatchdog() {
    if (hasRenderedFirstFrame || !currentStreamHasVideoTrack) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_READY || !player.playWhenReady) return
    if (firstFrameWatchdogJob?.isActive == true) return

    firstFrameWatchdogJob = scope.launch {
        delay(PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS)

        val livePlayer = _exoPlayer ?: return@launch
        if (hasRenderedFirstFrame) return@launch
        if (livePlayer.playbackState != Player.STATE_READY || !livePlayer.playWhenReady) return@launch

        val currentPosition = livePlayer.currentPosition
        // Manual Convert-to-DV8.1 mode 2 produced no first frame (e.g. black
        // screen): retry the stream at libdovi mode 1 before other fallbacks.
        if (isManualDv81Mode2ActiveForCurrentPlayback &&
            !dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)
        ) {
            dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
            retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
            return@launch
        }
        if (currentVideoTrackIsLikelyVc1 && !isVc1SoftwareFallbackActiveForCurrentPlayback) {
            vc1SoftwarePreferredStreamUrls.add(currentStreamUrl)
            retryCurrentStreamWithVc1SoftwareFallback(currentPosition)
            return@launch
        }

        if (currentVideoTrackIsLikelyVc1 &&
            !currentVideoTrackSelected &&
            isVc1SoftwareFallbackActiveForCurrentPlayback &&
            !isVc1TrackSelectionBypassActiveForCurrentPlayback
        ) {
            vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
            retryCurrentStreamWithVc1TrackSelectionBypass(currentPosition)
            return@launch
        }

        val elapsedMs = (System.currentTimeMillis() - playerInitializationStartedAtMs)
            .coerceAtLeast(0L)
        val timeoutSeconds = (elapsedMs / 1_000L).coerceAtLeast(1L).toInt()
        val timeoutMessage = context.getString(R.string.player_error_start_timeout, timeoutSeconds)
        val attempts = errorRetryCount + startupRetryCount + timeoutRecoveryAttempts + 1
        val httpContentType = currentStreamResponseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        Log.e(
            PlayerRuntimeController.TAG,
            "STARTUP_TIMEOUT elapsedMs=$elapsedMs attempts=$attempts " +
                "provider=${currentAddonName ?: "unknown"} host=${currentStreamUrl.startupTimeoutHost()} " +
                "urlHash=${currentStreamUrl.hashCode().toUInt().toString(16)} " +
                "mime=${currentStreamMimeType ?: "unknown"} codec=${currentVideoTrackCodecs ?: currentVideoCodec ?: "unknown"} " +
                "httpContentType=$httpContentType drm=not-reported"
        )
        cancelStallWatchdog()
        firstFrameWatchdogJob = null
        scope.launch {
            runCatching {
                playerSettingsDataStore.setLastPlaybackDiagnostics(
                    LastPlaybackDiagnostics(
                        timestampMs = System.currentTimeMillis(),
                        host = currentStreamUrl.startupTimeoutHost(),
                        firstFrameMs = -1L,
                        videoCodec = currentVideoTrackCodecs ?: currentVideoCodec,
                        result = "Error: startup timeout after ${timeoutSeconds}s; attempts=$attempts"
                    )
                )
            }
        }
        _uiState.update {
            it.copy(
                error = timeoutMessage,
                showLoadingOverlay = false,
                showPauseOverlay = false,
                showControls = false
            )
        }
    }
}

private fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
    fromPositionMs: Long,
    clearResumeProgress: Boolean = false
) {
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    val recoveryPositionMs = fromPositionMs.takeIf { it > 1_000L } ?: recoveryResumePositionMs()
    if (clearResumeProgress) {
        pendingResumeProgress = null
    }
    _uiState.update {
        it.copy(
            pendingSeekPosition = if (recoveryPositionMs > 0L) recoveryPositionMs else null,
            error = null,
            showLoadingOverlay = it.loadingOverlayEnabled
        )
    }
    scope.launch {
        yield()
        runCatching {
            releasePlayer()
            initializePlayer(currentStreamUrl, currentHeaders)
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    error = e.toDisplayMessage(context),
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.observeDeviceLocalAspectMode() {
    scope.launch {
        deviceLocalPlayerPreferences.aspectMode
            .distinctUntilChanged()
            .collect { mode ->
                val currentState = _uiState.value
                if (currentState.aspectMode != mode) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "Aspect mode restored from device-local prefs: ${currentState.aspectMode} -> $mode"
                    )
                    _uiState.update { it.copy(aspectMode = mode) }
                }
            }
    }
}
