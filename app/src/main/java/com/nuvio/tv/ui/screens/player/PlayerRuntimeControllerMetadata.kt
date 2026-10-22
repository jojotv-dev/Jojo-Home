package com.nuvio.tv.ui.screens.player

import com.jojotv.home.R
import com.jojotv.home.HomeMediaRules
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.FreeboxVideoMeta
import com.nuvio.tv.core.tmdb.cleanTmdbTitleQuery
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.domain.model.resolveOriginalAudioLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (contentId?.startsWith("iptv:") == true) {
        val lookup = beginPlayerTitleLogoLookup()
        scope.launch {
            try {
                fetchOriginalAudioLanguage(tmdbId, navigationArgs.tmdbMediaType ?: type)
                externalProviderItem()?.let(::applyExternalProviderMetadata)
                applyStoredPlayerMetadataOverrides()
                resolveOriginalTitleLogo(tmdbId, navigationArgs.tmdbMediaType ?: type)
                if (!enrichPlayerMetadataFromKnownIdentity()) enrichPlayerMetadataFromTmdbTitle(type)
            } finally { finishPlayerTitleLogoLookup(lookup) }
        }
        return
    }
    val externalId = contentId
        ?.takeIf { it.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX) }
        ?.removePrefix(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX)
    android.util.Log.i(
        "CastPhotoTrace",
        "FETCH_META source=${if (externalId != null) "external" else if (isFreeboxPlayback) "freebox" else "catalog"} " +
            "type=${type.orEmpty()} idPresent=${!id.isNullOrBlank()} tmdbId=${navigationArgs.tmdbId != null} " +
            "navigationCast=${initialNavigationCast.size} " +
            "navigationPhotos=${initialNavigationCast.count { !it.photo.isNullOrBlank() }}"
    )
    if (externalId != null) {
        com.jojotv.home.ExternalPlaybackStore.enrichProviderMetadata(context, externalId) {
            scope.launch {
                fetchOriginalAudioLanguage(tmdbId, navigationArgs.tmdbMediaType ?: type)
                externalProviderItem()?.let(::applyExternalProviderMetadata)
                applyStoredPlayerMetadataOverrides()
                resolveOriginalTitleLogo(tmdbId, navigationArgs.tmdbMediaType ?: type)
                if (!enrichPlayerMetadataFromKnownIdentity()) enrichPlayerMetadataFromTmdbTitle(type)
            }
        }
        return
    }

    // Freebox recording IDs cannot be resolved by addon/TMDB APIs.  Falling back
    // to a title search makes an unrelated programme's artwork and overview look
    // valid, then exports that bad result to the shared Continue Watching entry.
    // Only exact, file-keyed user overrides are safe for a Freebox recording.
    if (isFreeboxPlayback) {
        android.util.Log.d(
            "FreeboxPlayerTrace",
            "PLAYER_METADATA skipped-unverified-freebox id=${(contentId ?: currentVideoId).orEmpty().hashCode()}"
        )
        scope.launch {
            applyStoredPlayerMetadataOverrides()
            val tmdbId = navigationArgs.tmdbId
            val tmdbMediaType = navigationArgs.tmdbMediaType
            if (tmdbId != null && tmdbMediaType != null) {
                val selectedMetadata = tmdbMetadataService.fetchMetadataForSelectedIdentity(
                    FreeboxVideoMeta(
                        tmdbId = tmdbId,
                        mediaType = tmdbMediaType,
                        posterUrl = null,
                        backdropUrl = null,
                        overview = null,
                        voteAverage = null,
                        year = null,
                        genres = emptyList()
                    )
                )
                android.util.Log.i(
                    "CastPhotoTrace",
                    "FREEBOX_TMDB_ID result=${selectedMetadata != null} " +
                        "cast=${selectedMetadata?.castMembers?.size ?: 0} " +
                        "photos=${selectedMetadata?.castMembers?.count { !it.photo.isNullOrBlank() } ?: 0}"
                )
                selectedMetadata?.let(::applyTmdbTitleMetadata)
            } else {
                android.util.Log.i(
                    "CastPhotoTrace",
                    "FREEBOX_TMDB_ID skipped=missing-identity id=${tmdbId != null} type=${tmdbMediaType != null}"
                )
                enrichPlayerMetadataFromTmdbTitle(type)
            }
        }
        return
    }
    scope.launch {
        applyStoredPlayerMetadataOverrides()
    }

    if (!id.isNullOrBlank() && !type.isNullOrBlank()) {
        scope.launch {
            when (
                val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                    .first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> {
                    applyMetaDetails(result.data)
                }
                is NetworkResult.Error -> {
                }
                NetworkResult.Loading -> {
                }
            }
        }

        scope.launch {
            enrichDescriptionFromTmdb(id, type)
        }
    }

    scope.launch {
        applyStoredPlayerMetadataOverrides()
        enrichPlayerMetadataFromTmdbTitle(type)
    }
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    metaGenres = meta.genres
    metaCountry = meta.country
    // Fill in content language from meta if not provided via navigation args.
    if (contentLanguage == null) {
        contentLanguage = meta.resolveContentLanguage()
    }
    val description = resolveDescription(meta)

    _uiState.update { state ->
        val castMembers = if (state.castMembers.any { HomeMediaRules.isTmdbPresenter(it.character) }) {
            state.castMembers
        } else if (initialNavigationCast.isNotEmpty()) {
            initialNavigationCast
        } else if (meta.castMembers.isNotEmpty()) {
            meta.castMembers
        } else {
            state.castMembers
        }
        android.util.Log.i(
            "CastPhotoTrace",
            "ADDON_APPLY current=${state.castMembers.size} " +
                "currentPhotos=${state.castMembers.count { !it.photo.isNullOrBlank() }} " +
                "navigation=${initialNavigationCast.size} " +
                "navigationPhotos=${initialNavigationCast.count { !it.photo.isNullOrBlank() }} " +
                "addon=${meta.castMembers.size} " +
                "addonPhotos=${meta.castMembers.count { !it.photo.isNullOrBlank() }} " +
                "result=${castMembers.size} photos=${castMembers.count { !it.photo.isNullOrBlank() }}"
        )
        state.copy(
            description = if (hasPinnedPlayerMetadataOverride) {
                state.description
            } else {
                description ?: state.description
            },
            title = if (state.title.isBlank()) meta.name else state.title,
            releaseYear = state.releaseYear ?: meta.releaseInfo?.extractPlayerYear(),
            runtimeMinutes = parsePlayerRuntimeMinutes(meta.runtime) ?: state.runtimeMinutes,
            rating = state.rating ?: meta.imdbRating,
            poster = state.poster?.takeIf { it.isNotBlank() }
                ?: meta.poster?.takeIf { it.isNotBlank() },
            backdrop = state.backdrop?.takeIf { it.isNotBlank() }
                ?: meta.backdropUrl?.takeIf { it.isNotBlank() },
            logo = state.logo?.takeIf { it.isNotBlank() }
                ?: meta.logo?.takeIf { it.isNotBlank() },
            genres = meta.genres.filter { it.isNotBlank() }.distinct(),
            director = if (initialNavigationDirector.isNotEmpty()) initialNavigationDirector else if (meta.director.isNotEmpty()) meta.director else state.director,
            castMembers = castMembers
        )
    }
    recomputeNextEpisode(resetVisibility = false)
}

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    if (type in listOf("series", "tv", "iptv_series") && currentSeason != null && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.season == currentSeason && video.episode == currentEpisode
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }

    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = metaVideos.firstOrNull { video ->
        video.season == currentSeason && video.episode == currentEpisode
    }?.overview

    if (!overview.isNullOrBlank() && !hasAuthoritativeNavigationDescription) {
        _uiState.update { it.copy(description = overview) }
    }

    // Push episode metadata to the MediaSession so Google Home shows the new episode.
    updateMediaSessionMetadata()

    // Re-enrich from TMDB for the new episode.
    scope.launch {
        enrichDescriptionFromTmdb(contentId, contentType)
    }
}

private suspend fun PlayerRuntimeController.enrichDescriptionFromTmdb(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) {
        android.util.Log.i("CastPhotoTrace", "TMDB_ID_LOOKUP skipped=missing-id-or-type")
        return
    }
    // A home-external ID is a local IPTV record key, never a TMDB identity.
    if (contentId?.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX) == true) {
        android.util.Log.i("CastPhotoTrace", "TMDB_ID_LOOKUP skipped=external-id")
        return
    }
    val settings = tmdbSettingsDataStore.settings.first()
    if (!settings.enabled || !settings.useBasicInfo) {
        android.util.Log.i(
            "CastPhotoTrace",
            "TMDB_ID_LOOKUP skipped=settings enabled=${settings.enabled} basicInfo=${settings.useBasicInfo}"
        )
        return
    }

    val tmdbId = runCatching { tmdbService.ensureTmdbId(id, type) }.getOrNull()
    if (tmdbId == null) {
        android.util.Log.i("CastPhotoTrace", "TMDB_ID_LOOKUP result=unresolved type=$type")
        return
    }
    val contentType = when (type.lowercase()) {
        "series", "tv", "iptv_series" -> ContentType.SERIES
        else -> ContentType.MOVIE
    }
    val enrichment = runCatching {
        tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = contentType,
            language = settings.language
        )
    }.getOrNull()
    if (enrichment == null) {
        android.util.Log.i("CastPhotoTrace", "TMDB_ID_LOOKUP result=no-enrichment type=$type")
        return
    }
    android.util.Log.i(
        "CastPhotoTrace",
        "TMDB_ID_RESULT type=$type cast=${enrichment.castMembers.size} " +
            "photos=${enrichment.castMembers.count { !it.photo.isNullOrBlank() }}"
    )

    val isSeries = type.lowercase() in listOf("series", "tv", "iptv_series")
    val season = currentSeason
    val episode = currentEpisode

    // For series, try to get episode-level overview and title from TMDB.
    val episodeEnrichment = if (isSeries && season != null && episode != null) {
        runCatching {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season),
                language = settings.language
            )[season to episode]
        }.getOrNull()
    } else null

    val tmdbDescription = episodeEnrichment?.overview ?: enrichment.description
    if (settings.useBasicInfo) {
        val tmdbGenres = enrichment.genres
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        _uiState.update { state ->
            state.copy(
                description = if (!hasPinnedPlayerMetadataOverride && !tmdbDescription.isNullOrBlank()) {
                    tmdbDescription
                } else {
                    state.description
                },
                runtimeMinutes = state.runtimeMinutes ?: enrichment.runtimeMinutes,
                rating = state.rating ?: enrichment.rating?.toFloat(),
                // The addon metadata is preferred when it already supplies
                // genres. Otherwise retain the official TMDB genres resolved
                // for this player session.
                genres = if (state.genres.isEmpty() && tmdbGenres.isNotEmpty()) {
                    tmdbGenres
                } else {
                    state.genres
                }
            )
        }
    }

    // Enrich title from TMDB (localized).
    if (settings.useBasicInfo) {
        val tmdbTitle = enrichment.localizedTitle
        if (!tmdbTitle.isNullOrBlank()) {
            _uiState.update { it.copy(title = tmdbTitle) }
        }
    }

    // Enrich logo from TMDB if artwork is enabled.
    if (settings.useArtwork) {
        com.jojotv.home.OnlineAppIconLoader.rememberTitleLogo(context,
            if (contentType == ContentType.SERIES) "tv" else "movie", tmdbId.toIntOrNull() ?: 0, enrichment.logo)
        _uiState.update { state ->
            state.copy(
                logo = state.logo?.takeIf { it.isNotBlank() } ?: enrichment.logo?.takeIf { it.isNotBlank() },
                poster = state.poster ?: enrichment.poster?.takeIf { it.isNotBlank() },
                backdrop = state.backdrop ?: enrichment.backdrop?.takeIf { it.isNotBlank() }
            )
        }
        if (enrichment.logo.isNullOrBlank()) requestOfficialPlayerTitleLogo(tmdbId.toIntOrNull(),
            if (contentType == ContentType.SERIES) "tv" else "movie")
    }

    // Also enrich episode title from TMDB if available.
    if (settings.useBasicInfo) {
        val tmdbEpisodeTitle = episodeEnrichment?.title
        if (!tmdbEpisodeTitle.isNullOrBlank()) {
            _uiState.update { it.copy(currentEpisodeTitle = tmdbEpisodeTitle) }
        }
    }

    // Enrich cast from TMDB if addon didn't provide any.
    if (settings.useBasicInfo && enrichment.castMembers.isNotEmpty()) {
        _uiState.update { state ->
            val tmdbCastByIdentity = enrichment.castMembers.associateBy { member ->
                member.tmdbId?.toString()
                    ?: member.name.trim().lowercase(Locale.ROOT)
            }
            val mergedCast = if ((isSeries && enrichment.castMembers.any { HomeMediaRules.isTmdbPresenter(it.character) }) || state.castMembers.isEmpty()) {
                enrichment.castMembers
            } else {
                state.castMembers.map { member ->
                    val key = member.tmdbId?.toString()
                        ?: member.name.trim().lowercase(Locale.ROOT)
                    val tmdbMember = tmdbCastByIdentity[key]
                    if (member.photo.isNullOrBlank() && !tmdbMember?.photo.isNullOrBlank()) {
                        member.copy(
                            photo = tmdbMember.photo,
                            tmdbId = member.tmdbId ?: tmdbMember.tmdbId,
                            character = member.character ?: tmdbMember.character
                        )
                    } else {
                        member
                    }
                }
            }
            android.util.Log.i(
                "CastPhotoTrace",
                "TMDB_ID_MERGE current=${state.castMembers.size} " +
                    "currentPhotos=${state.castMembers.count { !it.photo.isNullOrBlank() }} " +
                    "result=${mergedCast.size} photos=${mergedCast.count { !it.photo.isNullOrBlank() }}"
            )
            state.copy(
                castMembers = mergedCast,
                director = if (state.director.isEmpty()) enrichment.director else state.director
            )
        }
    } else if (settings.useBasicInfo && enrichment.director.isNotEmpty()) {
        _uiState.update { state ->
            if (state.director.isEmpty()) state.copy(director = enrichment.director) else state
        }
    }

    // Refresh MediaSession metadata with TMDB-enriched title / artwork.
    updateMediaSessionMetadata()
}

private suspend fun PlayerRuntimeController.enrichPlayerMetadataFromKnownIdentity(): Boolean {
    if (contentType?.lowercase(Locale.ROOT) in setOf("iptv", "live", "channel")) return true
    val id = navigationArgs.tmdbId ?: return false
    val type = navigationArgs.tmdbMediaType ?: return false
    if (!tmdbSettingsDataStore.settings.first().enabled) return true
    val metadata = tmdbMetadataService.fetchMetadataForSelectedIdentity(FreeboxVideoMeta(
        tmdbId = id, mediaType = type, posterUrl = null, backdropUrl = null,
        overview = null, voteAverage = null, year = null, genres = emptyList()
    ))
    metadata?.let(::applyTmdbTitleMetadata)
    // A known identity must never fall back to a different same-title search result.
    return true
}

private suspend fun PlayerRuntimeController.enrichPlayerMetadataFromTmdbTitle(type: String?) {
    // Live channel titles must not be resolved as films or programmes.
    if (type.equals("iptv", ignoreCase = true)) {
        android.util.Log.i("CastPhotoTrace", "TMDB_TITLE_LOOKUP skipped=live-channel")
        return
    }
    val settings = tmdbSettingsDataStore.settings.first()
    if (!settings.enabled) {
        android.util.Log.i("CastPhotoTrace", "TMDB_TITLE_LOOKUP skipped=disabled")
        return
    }

    val state = _uiState.value
    val missingMetadata = state.description.isNullOrBlank() ||
        state.castMembers.isEmpty() ||
        state.castMembers.none { !it.photo.isNullOrBlank() } ||
        state.director.isEmpty() ||
        state.releaseYear.isNullOrBlank() ||
        state.genres.isEmpty() ||
        state.logo.isNullOrBlank()
    android.util.Log.i(
        "CastPhotoTrace",
        "TMDB_TITLE_LOOKUP_START type=${type.orEmpty()} external=${isIptvPlayback()} " +
            "cast=${state.castMembers.size} photos=${state.castMembers.count { !it.photo.isNullOrBlank() }}"
    )
    if (!isIptvPlayback() && !missingMetadata) {
        android.util.Log.i("CastPhotoTrace", "TMDB_TITLE_LOOKUP skipped=metadata-complete")
        return
    }

    val providerItem = externalProviderItem()
    // For external videos, only the provider record is authoritative. A
    // generic title search cannot establish a certain correspondence.
    if (isIptvPlayback() && providerItem == null) {
        android.util.Log.i("CastPhotoTrace", "TMDB_TITLE_LOOKUP skipped=missing-provider-item")
        return
    }
    val isFreebox = type.equals("freebox", ignoreCase = true) || isFreeboxPlayback
    val isIptv = isIptvPlayback()
    val freeboxFolderTitle = if (isFreebox) freeboxMetadataFolderTitle() else null
    val filenameQuery = currentFilename?.takeIf {
        !isIptv && cleanPlayerTmdbQuery(it).any(Char::isLetter)
    }
    val queryCandidates = listOfNotNull(
        filenameQuery,
        providerItem?.providerText("providerTitle", "title"),
        providerItem?.providerText("providerOriginalTitle", "originalTitle"),
        state.contentName,
        state.title,
        contentName,
        title,
        currentFilename,
        providerItem?.providerText("filename", "streamName"),
        freeboxFolderTitle
    )
        .map(::cleanPlayerTmdbQuery)
        .filter { it.length >= 2 }
        .distinctBy(::iptvMetadataTitleKey)
    if (queryCandidates.isEmpty()) {
        android.util.Log.i("CastPhotoTrace", "TMDB_TITLE_LOOKUP skipped=no-query")
        return
    }

    val externalCategory = providerItem?.optString("category")
    val freeboxEpisodeName = currentFilename.orEmpty().contains(
        Regex("\\b(?:S\\d{1,2}[ ._-]*E\\d{1,3}|\\d{1,2}x\\d{1,3}|EP(?:ISODE)?[ ._-]*0*\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    )
    val mediaTypeHint = when {
        externalCategory in listOf("series", "tv", "show", "tvshow", "iptv_series") -> "tv"
        isFreebox && freeboxEpisodeName -> "tv"
        isFreebox -> "unknown"
        (externalCategory ?: type?.lowercase()) in listOf("series", "tv", "show", "tvshow", "iptv_series") -> "tv"
        else -> "movie"
    }
    val lookupYear = providerItem?.providerText("year", "releaseYear", "release_date")
        ?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }
        ?: state.releaseYear?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }
    val validationTitle = listOfNotNull(
        providerItem?.providerText("providerTitle", "title"),
        state.contentName,
        state.title,
        contentName,
        title,
        currentFilename
    ).firstOrNull { it.isNotBlank() } ?: return
    val lookupCandidates = when {
        isIptv -> queryCandidates.take(5)
        isFreebox -> queryCandidates.take(2)
        else -> queryCandidates.take(2)
    }
    for (query in lookupCandidates) {
        val lookupQuery = listOfNotNull(query, lookupYear).joinToString(" ")
        val metadata = runCatching {
            tmdbService.fetchMetadataForTitleQuery(lookupQuery, mediaTypeHint)
        }.getOrNull()
        if (metadata == null) {
            android.util.Log.i(
                "CastPhotoTrace",
                "TMDB_TITLE_RESULT queryHash=${query.hashCode()} result=none type=$mediaTypeHint"
            )
            if (isIptv) {
                android.util.Log.i(
                    "IptvMetadataMatch",
                    "query=${query.take(120)} type=$mediaTypeHint year=${lookupYear.orEmpty()} " +
                        "result=none confidence=0 accepted=false reason=no-result"
                )
            }
            continue
        }
        android.util.Log.i(
            "CastPhotoTrace",
            "TMDB_TITLE_RESULT queryHash=${query.hashCode()} result=found tmdbId=${metadata.tmdbId != null} " +
                "cast=${metadata.castMembers.size} photos=${metadata.castMembers.count { !it.photo.isNullOrBlank() }}"
        )

        if (!isIptv) {
            applyTmdbTitleMetadata(metadata)
            return
        }

        val decision = validateIptvTmdbMatch(
            metadata = metadata,
            providerItem = providerItem,
            fallbackTitle = validationTitle,
            expectedType = mediaTypeHint,
            fallbackYear = state.releaseYear
        )
        android.util.Log.i(
            "IptvMetadataMatch",
            "query=${query.take(120)} type=$mediaTypeHint year=${decision.expectedYear.orEmpty()} " +
                "result=${decision.resultTitle.take(120)} resultType=${metadata.mediaType.orEmpty()} " +
                "resultYear=${metadata.year.orEmpty()} confidence=${decision.confidence} " +
                "accepted=${decision.accepted} reason=${decision.reason}"
        )
        android.util.Log.i(
            "CastPhotoTrace",
            "TMDB_TITLE_MATCH accepted=${decision.accepted} reason=${decision.reason}"
        )
        if (decision.accepted) {
            applyTmdbTitleMetadata(metadata)
            // The title search establishes identity; the exact TMDB record also
            // resolves missing person portraits and the localized title logo.
            if (metadata.tmdbId != null) {
                val exactMetadata = tmdbMetadataService.fetchMetadataForSelectedIdentity(metadata)
                android.util.Log.i(
                    "CastPhotoTrace",
                    "TMDB_TITLE_EXACT result=${exactMetadata != null} " +
                        "photos=${exactMetadata?.castMembers?.count { !it.photo.isNullOrBlank() } ?: 0}"
                )
                exactMetadata?.let(::applyTmdbTitleMetadata)
            }
            return
        }
    }
    if (_uiState.value.logo.isNullOrBlank()) requestOfficialPlayerTitleLogo(null, mediaTypeHint)
}

private fun PlayerRuntimeController.freeboxMetadataFolderTitle(): String? {
    val rawPath = contentId
        ?.takeIf { it.startsWith("freebox:", ignoreCase = true) }
        ?.let(::freeboxPathFromContentId)
        ?: return null
    val folders = android.net.Uri.decode(rawPath)
        .replace('\\', '/')
        .trimEnd('/')
        .substringBeforeLast('/', "")
        .split('/')
        .filter(String::isNotBlank)
    val videoRootIndex = folders.indexOfLast {
        it.equals("videos", ignoreCase = true) || it.equals("video", ignoreCase = true) ||
            it.equals("vidéos", ignoreCase = true)
    }
    val candidates = if (videoRootIndex >= 0) folders.drop(videoRootIndex + 1) else folders
    val genericFolders = setOf("freebox", "disque dur", "films", "film", "séries", "série", "series", "video", "videos", "vidéos")
    return candidates.asReversed().firstOrNull { folder ->
        val normalized = folder.trim()
        normalized.isNotEmpty() &&
            normalized.lowercase(Locale.ROOT) !in genericFolders &&
            !Regex("(?i)^(?:saison|season)\\s*\\d+$|s\\d{1,2}$").matches(normalized)
    }
}

private fun PlayerRuntimeController.externalProviderItem(): JSONObject? {
    val externalId = contentId
        ?.takeIf { it.startsWith("iptv:") || it.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX) }
        ?.removePrefix(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX)
        ?: return null
    val item = com.jojotv.home.ExternalPlaybackStore.get(context, externalId)
    return item.takeIf {
        !it.optBoolean("deleted") && it.optString("category") in setOf("movies", "series", "live")
    }
}

private fun PlayerRuntimeController.applyExternalProviderMetadata(item: JSONObject) {
    val providerTitle = item.providerText("providerTitle", "title")
    val providerYear = item.providerText("year", "releaseYear", "release_date")
        ?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }
    val providerGenres = item.providerList("genres", "genre")
    val providerCast = item.providerList("cast", "actors")
        .map { MetaCastMember(name = it) }
    val providerDirectors = item.providerList("director", "directors")
    val providerDescription = item.providerText("description", "overview", "plot")
    val providerPoster = item.providerText("artwork", "poster", "posterUrl", "screenshot_uri", "pic")
    val providerLogo = item.providerText("titleLogo", "title_logo", "logoUrl", "logo_url", "logo")
    val originalLogo = com.jojotv.home.OnlineAppIconLoader.cachedTitleLogo(context,
        navigationArgs.tmdbMediaType ?: item.optString("category"), tmdbId ?: item.optInt("tmdbId", 0))
        .takeIf(String::isNotBlank)
    val providerBackdrop = item.providerText(
        "backdrop", "backdrop_url", "backdropUrl", "background", "background_url"
    )
    val providerRating = item.providerNumber("rating", "voteAverage", "rating_imdb")?.toFloat()
    val providerRuntime = item.providerRuntimeMinutes()
    val providerSeason = item.providerNumber("season")?.toInt()?.takeIf { it > 0 }
    val providerEpisode = item.providerNumber("episode")?.toInt()?.takeIf { it > 0 }
    val providerEpisodeTitle = item.providerText("episodeTitle", "episode_name")
    providerSeason?.let { currentSeason = it }
    providerEpisode?.let { currentEpisode = it }
    if (item.optString("category") == "series") {
        item.providerNumber("tmdbId", "tmdb_id")?.toInt()?.takeIf { it > 0 }?.let {
            skipIntroTvTmdbId = it
        }
    }
    fetchOriginalAudioLanguage(item.providerNumber("tmdbId", "tmdb_id")?.toInt(),
        when (item.optString("category")) { "series" -> "tv"; "movies" -> "movie"; else -> null })

    _uiState.update { state ->
        val castMembers = state.castMembers.ifEmpty { providerCast }
        android.util.Log.i(
            "CastPhotoTrace",
            "PROVIDER_APPLY current=${state.castMembers.size} " +
                "currentPhotos=${state.castMembers.count { !it.photo.isNullOrBlank() }} " +
                "provider=${providerCast.size} providerPhotos=${providerCast.count { !it.photo.isNullOrBlank() }} " +
                "result=${castMembers.size} photos=${castMembers.count { !it.photo.isNullOrBlank() }}"
        )
        val displayTitle = providerTitle?.let {
            com.jojotv.home.ExternalPlaybackStore.playerDisplayTitle(
                it,
                providerSeason ?: state.currentSeason ?: 0,
                providerEpisode ?: state.currentEpisode ?: 0
            )
        }
        state.copy(
            title = displayTitle ?: state.title,
            contentName = providerTitle ?: state.contentName,
            description = initialNavigationDescription ?: providerDescription ?: state.description,
            releaseYear = state.releaseYear?.takeIf(String::isNotBlank) ?: providerYear,
            genres = state.genres.ifEmpty { providerGenres },
            runtimeMinutes = state.runtimeMinutes ?: providerRuntime,
            rating = state.rating ?: providerRating,
            poster = state.poster?.takeIf(String::isNotBlank) ?: providerPoster,
            backdrop = state.backdrop?.takeIf(String::isNotBlank) ?: providerBackdrop,
            logo = state.logo?.takeIf(String::isNotBlank) ?: providerLogo ?: originalLogo,
            castMembers = castMembers,
            director = state.director.ifEmpty { providerDirectors },
            currentSeason = providerSeason ?: state.currentSeason,
            currentEpisode = providerEpisode ?: state.currentEpisode,
            currentEpisodeTitle = providerEpisodeTitle ?: state.currentEpisodeTitle
        )
    }
    updateMediaSessionMetadata()
    // The provider can identify the series after the initial episode load ran.
    loadEpisodesIfNeeded()
    recomputeNextEpisode(resetVisibility = false)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
}

private data class IptvTmdbMatchDecision(
    val accepted: Boolean,
    val confidence: Int,
    val reason: String,
    val expectedYear: String?,
    val resultTitle: String
)

private fun validateIptvTmdbMatch(
    metadata: FreeboxVideoMeta,
    providerItem: JSONObject?,
    fallbackTitle: String,
    expectedType: String,
    fallbackYear: String?
): IptvTmdbMatchDecision {
    val providerTitles = listOfNotNull(
        providerItem?.providerText("providerTitle", "title"),
        providerItem?.providerText("providerOriginalTitle", "originalTitle")
    ).map(::iptvMetadataTitleKey).filter(String::isNotBlank).distinct()
    val expectedTitles = providerTitles.ifEmpty { listOf(iptvMetadataTitleKey(fallbackTitle)) }
    val candidateTitles = listOfNotNull(metadata.matchedTitle, metadata.originalTitle)
        .map(::iptvMetadataTitleKey).filter(String::isNotBlank).distinct()
    val titleMatches = expectedTitles.any(candidateTitles::contains)
    val normalizedResultType = when (metadata.mediaType?.lowercase()) {
        "series", "tv" -> "tv"
        "movie", "movies" -> "movie"
        else -> metadata.mediaType?.lowercase().orEmpty()
    }
    val typeMatches = normalizedResultType == expectedType
    val expectedYear = providerItem?.providerText("year", "releaseYear", "release_date")
        ?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }
        ?: fallbackYear?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }
    val yearMatches = expectedYear == null || expectedYear == metadata.year
    val confidence = (if (titleMatches) 65 else 0) +
        (if (typeMatches) 20 else 0) +
        (if (expectedYear != null && metadata.year == expectedYear) 15 else 0)
    val reason = when {
        !titleMatches -> "title-mismatch"
        !typeMatches -> "type-mismatch"
        !yearMatches -> if (metadata.year == null) "year-missing" else "year-mismatch"
        else -> "verified"
    }
    return IptvTmdbMatchDecision(
        accepted = titleMatches && typeMatches && yearMatches,
        confidence = confidence,
        reason = reason,
        expectedYear = expectedYear,
        resultTitle = metadata.matchedTitle ?: metadata.originalTitle.orEmpty()
    )
}

private fun JSONObject.providerText(vararg keys: String): String? = keys
    .asSequence()
    .mapNotNull { key -> HomeMediaRules.mediaMetadataText(optString(key, "")).takeIf(String::isNotBlank) }
    .firstOrNull()

private fun JSONObject.providerNumber(vararg keys: String): Double? = keys
    .asSequence()
    .mapNotNull { key -> opt(key)?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() }
    .firstOrNull()

private fun JSONObject.providerList(vararg keys: String): List<String> {
    val value = keys.asSequence().mapNotNull { key -> opt(key) }
        .firstOrNull { HomeMediaRules.mediaMetadataText(it.toString()).isNotBlank() } ?: return emptyList()
    val values = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { index -> value.optString(index).trim().takeIf(String::isNotBlank) }
        else -> HomeMediaRules.mediaMetadataNames(value.toString())
    }
    return values.map(HomeMediaRules::mediaMetadataText).filter(String::isNotBlank).distinct()
}

internal fun JSONObject.providerRuntimeMinutes(): Int? {
    // ExternalPlaybackStore.duration is a playback duration in milliseconds,
    // including when the first playback attempt did not reach the started state.
    return HomeMediaRules.externalRuntimeMinutes(optLong("duration", 0L),
        providerNumber("runtimeMinutes")?.toInt() ?: 0,
        providerText("providerRuntime", "runtime", "time")).takeIf { it > 0 }
}

private fun iptvMetadataTitleKey(value: String): String = java.text.Normalizer
    .normalize(
        cleanPlayerTmdbQuery(value),
        java.text.Normalizer.Form.NFD
    )
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private suspend fun PlayerRuntimeController.applyStoredPlayerMetadataOverrides() {
    val keys = playerMetadataOverrideKeys()
    if (keys.isEmpty()) return

    val posterOverrides = freeboxPosterOverrideDataStore.overrides.first()
    val backdropOverrides = freeboxPosterOverrideDataStore.backdropOverrides.first()
    val overviewOverrides = freeboxPosterOverrideDataStore.overviewOverrides.first()
    val metadataOverrides = freeboxPosterOverrideDataStore.metadataOverrides.first()
    val selectedMetadata = keys.firstNotNullOfOrNull(metadataOverrides::get)

    val poster = keys.firstNotNullOfOrNull { key ->
        posterOverrides[key]?.takeIf { it.isNotBlank() }
    }
    val backdrop = keys.firstNotNullOfOrNull { key ->
        backdropOverrides[key]?.takeIf { it.isNotBlank() }
    }
    val overview = keys.firstNotNullOfOrNull { key ->
        overviewOverrides[key]?.takeIf { it.isNotBlank() }
    }

    if (poster == null && backdrop == null && overview == null && selectedMetadata == null) return
    if (selectedMetadata != null) {
        hasPinnedPlayerMetadataOverride = true
        fetchOriginalAudioLanguage(selectedMetadata.tmdbId, selectedMetadata.mediaType)
        _uiState.update { state ->
            val storedCastMembers = selectedMetadata.castMembers.ifEmpty {
                selectedMetadata.cast.map { MetaCastMember(name = it) }
            }.ifEmpty { state.castMembers }
            android.util.Log.i(
                "CastPhotoTrace",
                "STORED_OVERRIDE current=${state.castMembers.size} " +
                    "currentPhotos=${state.castMembers.count { !it.photo.isNullOrBlank() }} " +
                    "stored=${selectedMetadata.castMembers.size} " +
                    "storedPhotos=${selectedMetadata.castMembers.count { !it.photo.isNullOrBlank() }} " +
                    "result=${storedCastMembers.size} " +
                    "photos=${storedCastMembers.count { !it.photo.isNullOrBlank() }}"
            )
            state.copy(
                // The selected image and its TMDB identity are one unit. An
                // older EPG/title match must not keep a different synopsis.
                description = selectedMetadata.overview ?: overview,
                director = selectedMetadata.director.ifEmpty { state.director },
                castMembers = storedCastMembers,
                releaseYear = selectedMetadata.year ?: state.releaseYear,
                releaseDate = selectedMetadata.releaseDate ?: state.releaseDate,
                genres = selectedMetadata.genres.ifEmpty { state.genres },
                poster = poster ?: selectedMetadata.posterUrl ?: state.poster,
                backdrop = backdrop ?: selectedMetadata.backdropUrl ?: state.backdrop ?: poster,
                logo = com.jojotv.home.OnlineAppIconLoader.cachedTitleLogo(context,
                    selectedMetadata.mediaType, selectedMetadata.tmdbId ?: 0).takeIf(String::isNotBlank)
                    ?: selectedMetadata.logoUrl ?: state.logo
            )
        }
        if (selectedMetadata.mediaType.equals("tv", ignoreCase = true)) {
            selectedMetadata.tmdbId?.takeIf { it > 0 }?.let {
                skipIntroTvTmdbId = it
                fetchSkipIntervals(contentId, currentSeason, currentEpisode)
            }
        }
        updateMediaSessionMetadata()
        return
    }
    if (!overview.isNullOrBlank() && !hasAuthoritativeNavigationDescription) {
        hasPinnedPlayerMetadataOverride = true
    }
    _uiState.update { state ->
        state.copy(
            // A saved override can belong to an old/fuzzy match.  For a
            // Freebox launch from Home, retain the EPG synopsis carried by
            // navigation instead of replacing it with that stale entry.
            description = if (hasAuthoritativeNavigationDescription) {
                state.description
            } else {
                overview ?: state.description
            },
            poster = poster ?: state.poster,
            backdrop = backdrop ?: state.backdrop ?: poster
        )
    }
    updateMediaSessionMetadata()
}

private fun PlayerRuntimeController.playerMetadataOverrideKeys(): List<String> =
    listOfNotNull(
        contentId,
        currentVideoId,
        videoId
    )
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private suspend fun PlayerRuntimeController.resolveOriginalTitleLogo(id: Int?, type: String?) {
    if (!_uiState.value.logo.isNullOrBlank()) return
    if (id == null || id <= 0) return
    val mediaType = when (type?.lowercase()) {
        "tv", "series", "iptv_series" -> "tv"
        "movie" -> "movie"
        else -> return
    }
    val requestedContent = contentId
    val requestedVideo = currentVideoId
    val cached = com.jojotv.home.OnlineAppIconLoader.cachedTitleLogo(context, mediaType, id)
    val logo = cached.ifBlank {
        runCatching {
            tmdbMetadataService.fetchEnrichment(id.toString(),
                if (mediaType == "tv") ContentType.SERIES else ContentType.MOVIE)?.logo
        }.getOrNull().orEmpty()
    }
    if (contentId != requestedContent || currentVideoId != requestedVideo) return
    if (logo.isBlank()) { requestOfficialPlayerTitleLogo(id, mediaType); return }
    com.jojotv.home.OnlineAppIconLoader.rememberTitleLogo(context, mediaType, id, logo)
    _uiState.update { it.copy(logo = it.logo?.takeIf(String::isNotBlank) ?: logo) }
}

private fun PlayerRuntimeController.beginPlayerTitleLogoLookup(): Any {
    val lookup = Any()
    if (contentType?.lowercase() in setOf("iptv", "live", "channel")) return lookup
    titleLogoLookups.add(lookup)
    _uiState.update { it.copy(isTitleLogoLoading = true) }
    return lookup
}

private fun PlayerRuntimeController.finishPlayerTitleLogoLookup(lookup: Any) {
    if (titleLogoLookups.remove(lookup) && titleLogoLookups.isEmpty())
        _uiState.update { it.copy(isTitleLogoLoading = false) }
}

private fun PlayerRuntimeController.requestOfficialPlayerTitleLogo(id: Int?, mediaType: String?) {
    val requestedContent = contentId
    val requestedVideo = currentVideoId
    val query = currentFilename?.takeIf { it.isNotBlank() } ?: _uiState.value.title
    val lookup = beginPlayerTitleLogoLookup()
    com.jojotv.home.OfficialProgrammeLogoResolver.resolve(context, query, id ?: 0, mediaType) { logo ->
        if (logo.isNotBlank() && contentId == requestedContent && currentVideoId == requestedVideo &&
            _uiState.value.logo.isNullOrBlank()) {
            _uiState.update { it.copy(logo = logo) }
            updateMediaSessionMetadata()
        }
        finishPlayerTitleLogoLookup(lookup)
    }
}

private fun PlayerRuntimeController.applyTmdbTitleMetadata(metadata: FreeboxVideoMeta) {
    fetchOriginalAudioLanguage(metadata.tmdbId, metadata.mediaType)
    com.jojotv.home.OnlineAppIconLoader.rememberTitleLogo(context, metadata.mediaType,
        metadata.tmdbId ?: 0, metadata.logoUrl)
    if (metadata.logoUrl.isNullOrBlank()) requestOfficialPlayerTitleLogo(metadata.tmdbId, metadata.mediaType)
    val castMembers = metadata.castMembers.ifEmpty {
        metadata.cast
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
            .map { MetaCastMember(name = it) }
    }

    _uiState.update { state ->
        val mergedCastMembers = when {
            metadata.mediaType in setOf("tv", "series") && castMembers.any { HomeMediaRules.isTmdbPresenter(it.character) } -> castMembers
            state.castMembers.isEmpty() -> castMembers
            state.castMembers.none { !it.photo.isNullOrBlank() } &&
                castMembers.any { !it.photo.isNullOrBlank() } -> castMembers
            else -> state.castMembers.map { currentMember ->
                val tmdbMember = castMembers.firstOrNull { candidate ->
                    candidate.tmdbId != null && candidate.tmdbId == currentMember.tmdbId ||
                        candidate.name.equals(currentMember.name, ignoreCase = true)
                }
                if (currentMember.photo.isNullOrBlank() && !tmdbMember?.photo.isNullOrBlank()) {
                    currentMember.copy(
                        photo = tmdbMember?.photo,
                        tmdbId = currentMember.tmdbId ?: tmdbMember?.tmdbId,
                        character = currentMember.character ?: tmdbMember?.character
                    )
                } else {
                    currentMember
                }
            }
        }
        android.util.Log.i(
            "CastPhotoTrace",
            "TMDB_TITLE_MERGE current=${state.castMembers.size} " +
                "currentPhotos=${state.castMembers.count { !it.photo.isNullOrBlank() }} " +
                "incoming=${castMembers.size} incomingPhotos=${castMembers.count { !it.photo.isNullOrBlank() }} " +
                "result=${mergedCastMembers.size} photos=${mergedCastMembers.count { !it.photo.isNullOrBlank() }}"
        )
        state.copy(
            description = state.description?.takeIf { it.isNotBlank() }
                ?: metadata.overview?.takeIf { it.isNotBlank() },
            director = if (state.director.isEmpty()) metadata.director else state.director,
            castMembers = mergedCastMembers,
            releaseYear = state.releaseYear?.takeIf { it.isNotBlank() }
                ?: metadata.year?.takeIf { it.isNotBlank() },
            releaseDate = state.releaseDate?.takeIf { it.isNotBlank() }
                ?: metadata.releaseDate?.takeIf { it.isNotBlank() },
            genres = if (state.genres.isEmpty()) {
                metadata.genres
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            } else {
                state.genres
            },
            runtimeMinutes = state.runtimeMinutes ?: metadata.runtimeMinutes,
            rating = state.rating ?: metadata.voteAverage?.toFloat(),
            poster = state.poster?.takeIf { it.isNotBlank() }
                ?: metadata.posterUrl?.takeIf { it.isNotBlank() },
            backdrop = state.backdrop?.takeIf { it.isNotBlank() }
                ?: metadata.backdropUrl?.takeIf { it.isNotBlank() },
            logo = state.logo?.takeIf { it.isNotBlank() }
                ?: metadata.logoUrl?.takeIf { it.isNotBlank() }
        )
    }

    updateMediaSessionMetadata()
    if (metadata.mediaType.equals("tv", ignoreCase = true)) {
        metadata.tmdbId?.takeIf { it > 0 }?.let {
            skipIntroTvTmdbId = it
            fetchSkipIntervals(contentId, currentSeason, currentEpisode)
        }
    }
}

private fun PlayerRuntimeController.fetchOriginalAudioLanguage(id: Int?, mediaType: String?) {
    if (!isIptvPlayback() || id == null || id <= 0) return
    val type = when (mediaType?.lowercase(Locale.ROOT)) {
        "tv", "series", "iptv_series" -> ContentType.SERIES
        "movie", "movies", "iptv_movie" -> ContentType.MOVIE
        else -> return
    }
    val key = "$type:$id"
    if (originalAudioLanguageLookupKey == key) return
    originalAudioLanguageLookupKey = key
    scope.launch {
        val settings = com.jojotv.home.iptv.IptvStore.applyPlayerSettings(
            context, contentId, playerSettingsDataStore.playerSettings.first())
        val needsOriginal = listOf(settings.preferredAudioLanguage, settings.secondaryPreferredAudioLanguage)
            .any { it.equals(com.nuvio.tv.data.local.AudioLanguageOption.ORIGINAL, true) }
        if (!needsOriginal) {
            if (originalAudioLanguageLookupKey == key) originalAudioLanguageLookupKey = null
            return@launch
        }
        if (originalAudioLanguageLookupKey != key) return@launch
        val metadata = withTimeoutOrNull(15_000L) {
            tmdbMetadataService.fetchEnrichment(id.toString(), type, Locale.getDefault().language)
        }
        if (originalAudioLanguageLookupKey != key) return@launch
        val language = resolveOriginalAudioLanguage(metadata?.language, metadata?.countries.orEmpty())
        if (language == null) {
            originalAudioLanguageLookupKey = null
            return@launch
        }
        contentLanguage = language
        _uiState.update { it.copy(contentLanguage = language) }
        applyPreferredAudioLanguageSettings(settings)
    }
}

private fun PlayerRuntimeController.isIptvPlayback(): Boolean =
    contentId?.startsWith("iptv:") == true ||
    contentType?.startsWith("iptv_", ignoreCase = true) == true ||
        contentId?.startsWith("iptv_", ignoreCase = true) == true ||
        contentType.equals("home-external", ignoreCase = true) ||
        contentId?.startsWith(com.jojotv.home.ExternalPlaybackStore.CONTENT_PREFIX) == true

private fun String?.extractPlayerYear(): String? = this
    ?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value }

private fun parsePlayerRuntimeMinutes(raw: String?): Int? =
    HomeMediaRules.providerRuntimeMinutes(raw).takeIf { it > 0 }

private fun cleanPlayerTmdbQuery(raw: String): String =
    cleanTmdbTitleQuery(
        raw
            .replace(Regex("""^\s*(?:\d+h\d{2}|\d+\s*min)\s+""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bS\d+\s*E\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:saison|season)\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:episode|épisode|ep)\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bSDR\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    )

internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    // Metadata can resolve an initially unclassified external stream as a series.
    val normalizedType = externalProviderItem()?.optString("category")?.takeIf { it == "series" }
        ?: contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv", "iptv_series", "other", "freebox")) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    if (PlayerNextEpisodeRules.usesSequentialPlaylistNextEpisode(normalizedType)) {
        val currentId = currentVideoId
        val idx = if (currentId != null) metaVideos.indexOfFirst { it.id == currentId } else -1
        val resolvedNext = if (idx >= 0 && idx < metaVideos.size - 1) metaVideos[idx + 1] else null
        nextEpisodeVideo = resolvedNext
        if (resolvedNext == null) {
            clearNextEpisodeAndCancelPostPlay()
            return
        }
        val nextInfo = NextEpisodeInfo(
            videoId = resolvedNext.id,
            season = resolvedNext.season ?: 1,
            episode = resolvedNext.episode ?: (idx + 2),
            title = resolvedNext.title,
            thumbnail = resolvedNext.thumbnail,
            overview = resolvedNext.overview,
            released = resolvedNext.released,
            hasAired = true,
            unairedMessage = null,
            isOtherType = true
        )
        applyRecomputedNextEpisode(nextInfo, resetVisibility)
        return
    }

    val state = _uiState.value
    val currentVideo = currentVideoId?.let { id -> metaVideos.firstOrNull { it.id == id } }
    val parsedEpisode = sequenceOf(currentEpisodeTitle, currentFilename, state.title)
        .mapNotNull { PlayerNextEpisodeRules.parseSeasonEpisode(it) }
        .firstOrNull()
    val season = currentSeason ?: state.currentSeason ?: currentVideo?.season ?: parsedEpisode?.first
    val episode = currentEpisode ?: state.currentEpisode ?: currentVideo?.episode ?: parsedEpisode?.second
    if (season == null || episode == null) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val resolvedNext = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = metaVideos,
        currentSeason = season,
        currentEpisode = episode
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val hasAired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: return,
        episode = resolvedNext.episode ?: return,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        hasAired = hasAired,
        unairedMessage = if (hasAired) {
            null
        } else {
            context.getString(com.nuvio.tv.R.string.next_episode_not_aired_yet)
        }
    )
    applyRecomputedNextEpisode(nextInfo, resetVisibility)
}

private fun PlayerRuntimeController.clearNextEpisodeAndCancelPostPlay() {
    val mode = _uiState.value.postPlayMode
    if (mode != null) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update {
        it.copy(
            nextEpisode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
}

private fun PlayerRuntimeController.applyRecomputedNextEpisode(
    nextInfo: NextEpisodeInfo,
    resetVisibility: Boolean,
) {
    val previousState = _uiState.value
    val previousNextEpisode = previousState.nextEpisode
    val previousMode = previousState.postPlayMode
    if (previousMode is PostPlayMode.StillWatching &&
        previousNextEpisode != null &&
        previousNextEpisode.videoId != nextInfo.videoId
    ) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        val updatedMode = if (shouldResetVisibility) {
            null
        } else {
            state.postPlayMode?.copyWithNextEpisode(nextInfo)
        }
        state.copy(
            nextEpisode = nextInfo,
            postPlayMode = updatedMode,
            postPlayDismissedForCurrentEpisode =
                if (shouldResetVisibility) false else state.postPlayDismissedForCurrentEpisode,
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayOverlayState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    postPlayTransitionTargetKey = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

internal fun PlayerRuntimeController.evaluatePostPlayOverlayVisibility(positionMs: Long, durationMs: Long) {
    if (!hasRenderedFirstFrame) return
    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    // Keep the transition lock while the new stream is still at the end of the
    // preceding playback. It is released only after the target episode has
    // genuinely started, so one completion cannot queue episode N+2.
    if (postPlayTransitionTargetKey != null) {
        val targetEpisodeHasStarted = effectiveDuration > 0L && positionMs < (effectiveDuration * 0.90f).toLong()
        if (!targetEpisodeHasStarted) return
        postPlayTransitionTargetKey = null
    }

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.postPlayMode != null) {
            _uiState.update { it.copy(postPlayMode = null) }
        }
        return
    }
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return

    // All sources use detected credits; without markers, native IPTV follows its
    // profile's threshold and other libraries keep their manual choice at 95%.
    val nativeIptv = contentId?.startsWith("iptv:") == true
    if (nativeIptv && !playerSettingsInitialized) return
    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = if (nativeIptv) nextEpisodeThresholdModeSetting else NextEpisodeThresholdMode.PERCENTAGE,
        thresholdPercent = if (nativeIptv) nextEpisodeThresholdPercentSetting else 95f,
        thresholdMinutesBeforeEnd = if (nativeIptv) nextEpisodeThresholdMinutesBeforeEndSetting else 0f
    )

    if (!shouldShow) return

    val autoPlay = nativeIptv && streamAutoPlayNextEpisodeEnabledSetting && state.nextEpisode.hasAired
    if (autoPlay && !state.isPlaying && !state.playbackEnded) return
    val shouldEnterStillWatching = shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        autoPlayNextEpisodeEnabled = autoPlay,
        nextEpisodeHasAired = state.nextEpisode.hasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = stillWatchingEpisodeThresholdSetting,
    )

    if (shouldEnterStillWatching) {
        enterStillWatchingPromptMode()
    } else if (autoPlay) {
        // Reuse the existing cancellable countdown and single-transition lock.
        playNextEpisode()
    } else {
        _uiState.update {
            // The next-episode card is a choice, not a short-lived toast.  Keep it
            // visible through the end of the current episode until the viewer
            // explicitly plays or dismisses it.
            it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = state.nextEpisode))
        }
    }
}

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank() || chosenSource == "IPTV fournisseur") return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    if (skipIntervals.isEmpty()) {
        lastAutoSkippedIntervalKey = null
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null) }
        }
        return
    }

    // Don't evaluate skip intervals until player settings are loaded from DataStore.
    // Without this, autoSkipSegmentTypes is empty on first iterations, causing the
    // skip button to appear instead of auto-skipping.
    if (!playerSettingsInitialized) return

    val positionSec = positionMs / 1000.0
    val active = skipIntervals.find { interval ->
        positionSec >= interval.startTime && positionSec < (interval.endTime - 0.5)
    }

    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        if (currentActive == null || active.type != currentActive.type || active.startTime != currentActive.startTime) {
            lastActiveSkipType = active.type
            _uiState.update { it.copy(activeSkipInterval = active, skipIntervalDismissed = false) }
        }
        val segmentType = AutoSkipSegmentType.fromSkipIntervalType(active.type)
        val activeKey = active.autoSkipKey()
        if (
            segmentType != null &&
            segmentType in autoSkipSegmentTypes &&
            lastAutoSkippedIntervalKey != activeKey
        ) {
            lastAutoSkippedIntervalKey = activeKey
            skipInterval(active)
        }
    } else if (currentActive != null) {

        lastAutoSkippedIntervalKey = null
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}

private fun SkipInterval.autoSkipKey(): String =
    "$provider:$type:$startTime:$endTime"

internal fun PlayerRuntimeController.tryShowParentalGuide() {
    val state = _uiState.value
    if (!state.parentalGuideHasShown && state.parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        _uiState.update { it.copy(showParentalGuide = true, parentalGuideHasShown = true) }
    }
}

internal fun PlayerRuntimeController.fetchParentalGuide(id: String?, type: String?, season: Int?, episode: Int?) {
    if (!parentalGuideEnabled) return
    if (id.isNullOrBlank()) return

    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return

    scope.launch {
        val guide = parentalGuideRepository.getParentalGuide(imdbId) ?: return@launch

        val labels = mapOf(
            "nudity" to context.getString(R.string.parental_nudity),
            "violence" to context.getString(R.string.parental_violence),
            "profanity" to context.getString(R.string.parental_profanity),
            "alcohol" to context.getString(R.string.parental_alcohol),
            "frightening" to context.getString(R.string.parental_frightening)
        )
        val severityOrder = mapOf(
            "severe" to 0, "moderate" to 1, "mild" to 2
        )

        val entries = listOfNotNull(
            guide.nudity?.let { "nudity" to it },
            guide.violence?.let { "violence" to it },
            guide.profanity?.let { "profanity" to it },
            guide.alcohol?.let { "alcohol" to it },
            guide.frightening?.let { "frightening" to it }
        )

        val warnings = entries
            .sortedBy { severityOrder[it.second.lowercase()] ?: 3 }
            .map {
                val localizedSeverity = when (it.second.lowercase()) {
                    "severe" -> context.getString(R.string.parental_severity_severe)
                    "moderate" -> context.getString(R.string.parental_severity_moderate)
                    "mild" -> context.getString(R.string.parental_severity_mild)
                    else -> it.second
                }
                ParentalWarning(label = labels[it.first] ?: it.first, severity = localizedSeverity)
            }
            .take(5)

        _uiState.update {
            it.copy(
                parentalWarnings = warnings,
                showParentalGuide = false,
                parentalGuideHasShown = false
            )
        }

        if (_uiState.value.isPlaying) {
            tryShowParentalGuide()
        }
    }
}
