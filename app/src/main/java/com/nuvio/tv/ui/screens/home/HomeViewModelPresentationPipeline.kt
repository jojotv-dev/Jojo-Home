package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.FreeboxVideoMeta
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CoreLayoutPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val classicFocusGradientEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean,
    val detailedHomeMediaRowsEnabled: Boolean,
    val homeRowCarouselKeys: Set<String>,
    val homeRowDetailedKeys: Set<String>
)

private data class FocusedBackdropPrefs(
    val expandEnabled: Boolean,
    val expandDelaySeconds: Int,
    val trailerEnabled: Boolean,
    val trailerMuted: Boolean,
    val trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget
)

private data class LayoutUiPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val classicFocusGradientEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean,
    val detailedHomeMediaRowsEnabled: Boolean,
    val homeRowCarouselKeys: Set<String>,
    val homeRowDetailedKeys: Set<String>,
    val modernLandscapePostersEnabled: Boolean,
    val modernHeroFullScreenBackdropEnabled: Boolean,
    val focusedBackdropExpandEnabled: Boolean,
    val focusedBackdropExpandDelaySeconds: Int,
    val focusedBackdropTrailerEnabled: Boolean,
    val focusedBackdropTrailerMuted: Boolean,
    val focusedBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    val posterCardWidthDp: Int,
    val posterCardHeightDp: Int,
    val posterCardCornerRadiusDp: Int
)

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLayoutPreferencesPipeline() {
    val coreLayoutPrefsFlow = combine(
        combine(
            layoutPreferenceDataStore.selectedLayout,
            layoutPreferenceDataStore.heroCatalogSelections,
            layoutPreferenceDataStore.heroSectionEnabled,
            layoutPreferenceDataStore.posterLabelsEnabled,
            layoutPreferenceDataStore.catalogAddonNameEnabled
        ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled, catalogAddonNameEnabled ->
            CoreLayoutPrefs(
                layout = layout,
                heroCatalogKeys = heroCatalogKeys,
                heroSectionEnabled = heroSectionEnabled,
                posterLabelsEnabled = posterLabelsEnabled,
                catalogAddonNameEnabled = catalogAddonNameEnabled,
                catalogTypeSuffixEnabled = true,
                classicFocusGradientEnabled = false,
                hideUnreleasedContent = false,
                showFullReleaseDate = true,
                detailedHomeMediaRowsEnabled = false,
                homeRowCarouselKeys = emptySet(),
                homeRowDetailedKeys = emptySet()
            )
        },
        layoutPreferenceDataStore.catalogTypeSuffixEnabled,
        layoutPreferenceDataStore.hideUnreleasedContent,
        layoutPreferenceDataStore.showFullReleaseDate,
        layoutPreferenceDataStore.classicFocusGradientEnabled
    ) { corePrefs, catalogTypeSuffixEnabled, hideUnreleasedContent, showFullReleaseDate, _ ->
        corePrefs.copy(
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
            classicFocusGradientEnabled = false,
            hideUnreleasedContent = hideUnreleasedContent,
            showFullReleaseDate = showFullReleaseDate
        )
    }

    val coreLayoutPrefsWithMediaRowsFlow = combine(
        coreLayoutPrefsFlow,
        layoutPreferenceDataStore.detailedHomeMediaRowsEnabled,
        layoutPreferenceDataStore.homeRowCarouselKeys,
        layoutPreferenceDataStore.homeRowDetailedKeys
    ) { corePrefs, detailedHomeMediaRowsEnabled, homeRowCarouselKeys, homeRowDetailedKeys ->
        corePrefs.copy(
            detailedHomeMediaRowsEnabled = detailedHomeMediaRowsEnabled,
            homeRowCarouselKeys = homeRowCarouselKeys,
            homeRowDetailedKeys = homeRowDetailedKeys
        )
    }

    val focusedBackdropPrefsFlow = combine(
        layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget
    ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted, trailerPlaybackTarget ->
        FocusedBackdropPrefs(
            expandEnabled = expandEnabled,
            expandDelaySeconds = expandDelaySeconds,
            trailerEnabled = trailerEnabled,
            trailerMuted = trailerMuted,
            trailerPlaybackTarget = trailerPlaybackTarget
        )
    }

    val modernLayoutPrefsFlow = combine(
        layoutPreferenceDataStore.modernLandscapePostersEnabled,
        layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled
    ) { landscapePosters, fullScreenBackdrop ->
        landscapePosters to fullScreenBackdrop
    }

    val baseLayoutUiPrefsFlow = combine(
        coreLayoutPrefsWithMediaRowsFlow,
        focusedBackdropPrefsFlow,
        layoutPreferenceDataStore.posterCardWidthDp,
        layoutPreferenceDataStore.posterCardHeightDp,
        layoutPreferenceDataStore.posterCardCornerRadiusDp
    ) { corePrefs, focusedBackdropPrefs, posterCardWidthDp, posterCardHeightDp, posterCardCornerRadiusDp ->
        LayoutUiPrefs(
            layout = corePrefs.layout,
            heroCatalogKeys = corePrefs.heroCatalogKeys,
            heroSectionEnabled = corePrefs.heroSectionEnabled,
            posterLabelsEnabled = corePrefs.posterLabelsEnabled,
            catalogAddonNameEnabled = corePrefs.catalogAddonNameEnabled,
            catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
            classicFocusGradientEnabled = false,
            hideUnreleasedContent = corePrefs.hideUnreleasedContent,
            showFullReleaseDate = corePrefs.showFullReleaseDate,
            detailedHomeMediaRowsEnabled = corePrefs.detailedHomeMediaRowsEnabled,
            homeRowCarouselKeys = corePrefs.homeRowCarouselKeys,
            homeRowDetailedKeys = corePrefs.homeRowDetailedKeys,
            modernLandscapePostersEnabled = false,
            modernHeroFullScreenBackdropEnabled = false,
            focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
            focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
            focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled &&
                AppFeaturePolicy.inAppTrailerPlaybackEnabled,
            focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
            focusedBackdropTrailerPlaybackTarget = focusedBackdropPrefs.trailerPlaybackTarget,
            posterCardWidthDp = posterCardWidthDp,
            posterCardHeightDp = posterCardHeightDp,
            posterCardCornerRadiusDp = posterCardCornerRadiusDp
        )
    }

    viewModelScope.launch {
        combine(
            baseLayoutUiPrefsFlow,
            modernLayoutPrefsFlow
        ) { basePrefs, modernPrefs ->
            basePrefs.copy(
                modernLandscapePostersEnabled = modernPrefs.first,
                modernHeroFullScreenBackdropEnabled = modernPrefs.second
            )
        }
            .distinctUntilChanged()
            // The first complete preference snapshot defines the actual Home
            // structure and must be published before the startup gate opens.
            // Debounce only later setting changes.
            .debounce { if (_uiState.value.layoutPreferencesReady) 300L else 0L }
            .collectLatest { prefs ->
                val effectivePosterLabelsEnabled = if (prefs.layout == HomeLayout.MODERN) {
                    false
                } else {
                    prefs.posterLabelsEnabled
                }
                val previousState = _uiState.value
                val heroKeysChanged = currentHeroCatalogKeys != prefs.heroCatalogKeys
                val shouldRefreshCatalogPresentation =
                    heroKeysChanged ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout ||
                        previousState.hideUnreleasedContent != prefs.hideUnreleasedContent ||
                        previousState.posterCardWidthDp != prefs.posterCardWidthDp
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                // Reset focus state when layout changes so the outgoing
                // layout's onDispose doesn't poison the incoming layout
                // (e.g., Modern dispose saves hasSavedFocus=true right
                // before Classic composes, preventing hero initial focus).
                if (previousState.layoutPreferencesReady && previousState.homeLayout != prefs.layout) {
                    // Suppress the outgoing layout's onDispose from saving
                    // stale focus state before the incoming layout composes.
                    suppressFocusSave = true
                    clearFocusState()
                }
                _uiState.update {
                    it.copy(
                        layoutPreferencesReady = true,
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        classicFocusGradientEnabled = false,
                        hideUnreleasedContent = prefs.hideUnreleasedContent,
                        showFullReleaseDate = prefs.showFullReleaseDate,
                        detailedHomeMediaRowsEnabled = prefs.detailedHomeMediaRowsEnabled,
                        homeRowCarouselKeys = prefs.homeRowCarouselKeys,
                        homeRowDetailedKeys = prefs.homeRowDetailedKeys,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        modernHeroFullScreenBackdropEnabled = prefs.modernHeroFullScreenBackdropEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        focusedPosterBackdropTrailerPlaybackTarget = prefs.focusedBackdropTrailerPlaybackTarget,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    // When switching to GRID layout, load all pending lazy catalogs
                    // since grid doesn't support placeholder shimmer rows.
                    if (prefs.layout == HomeLayout.GRID) {
                        loadAllPendingLazyCatalogs()
                    }
                    // When hero catalog keys change, load any hero catalogs
                    // not yet in catalogsMap (e.g., after startup race or
                    // when user changes hero selection in settings).
                    if (heroKeysChanged && prefs.heroCatalogKeys.isNotEmpty()) {
                        loadHeroCatalogsPipeline()
                    } else {
                        scheduleUpdateCatalogRows()
                    }
                }
            }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeModernHomePresentationPipeline() {
    viewModelScope.launch {
        combine(uiState, _currentLocaleTag) { state, localeTag ->
                ModernHomePresentationInput(
                    homeRows = state.homeRows,
                    catalogRows = state.catalogRows,
                    continueWatchingItems = state.continueWatchingItems,
                    useLandscapePosters = state.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = state.catalogTypeSuffixEnabled,
                    showFullReleaseDate = state.showFullReleaseDate,
                    localeTag = localeTag
                )
            }
            // Compare by row structure only (keys + item counts), not by
            // item content.  TMDB/meta enrichment changes item fields but
            // not the row structure — the hero section reads enriched data
            // via lastEnrichedPreview instead.
            .distinctUntilChanged { old, new ->
                old.homeRows === new.homeRows
                    && old.continueWatchingItems === new.continueWatchingItems
                    && old.useLandscapePosters == new.useLandscapePosters
                    && old.showCatalogTypeSuffix == new.showCatalogTypeSuffix
                    && old.showFullReleaseDate == new.showFullReleaseDate
                    && old.localeTag == new.localeTag
                    && old.catalogRows.size == new.catalogRows.size
                    && old.catalogRows.homeCatalogOrderSignature() == new.catalogRows.homeCatalogOrderSignature()
                    // IPTV metadata can replace portrait and landscape artwork without changing
                    // row structure. Include just those URLs so cards repaint automatically.

            }
            .debounce {
                // Use a longer debounce while catalogs are still loading to
                // avoid repeated expensive presentation builds during the
                // initial burst of catalog arrivals.
                if (catalogsLoadInProgress) 300L else 80L
            }
            .collectLatest { input ->
                val shouldWarmStart = uiState.value.modernHomePresentation.rows.list.isEmpty()
                val visibleCatalogRowCount = input.catalogRows.count { it.items.isNotEmpty() }
                val warmStartCatalogRowCount = if (input.continueWatchingItems.isNotEmpty()) 2 else 3

                if (shouldWarmStart && visibleCatalogRowCount > warmStartCatalogRowCount) {
                    val warmStartPresentation = withContext(Dispatchers.Default) {
                        buildModernHomePresentation(
                            input = input,
                            cache = modernCarouselRowBuildCache,
                            context = appContext,
                            maxCatalogRows = warmStartCatalogRowCount
                        )
                    }
                    _uiState.update { state ->
                        if (state.modernHomePresentation == warmStartPresentation) {
                            state
                        } else {
                            state.copy(modernHomePresentation = warmStartPresentation)
                        }
                    }
                }

                val presentation = withContext(Dispatchers.Default) {
                    buildModernHomePresentation(
                        input = input,
                        cache = modernCarouselRowBuildCache,
                        context = appContext
                    )
                }
                _uiState.update { state ->
                    if (state.modernHomePresentation == presentation) {
                        state
                    } else {
                        state.copy(modernHomePresentation = presentation)
                    }
                }
            }
    }
}

internal fun HomeViewModel.observeExternalMetaPrefetchPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.preferExternalMetaAddonDetail
            .distinctUntilChanged()
            .collectLatest { enabled ->
                externalMetaPrefetchEnabled = enabled
                if (!enabled) {
                    externalMetaPrefetchJob?.cancel()
                    pendingExternalMetaPrefetchItemId = null
                    externalMetaPrefetchInFlightIds.clear()
                }
            }
    }
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(item: MetaPreview) {
    requestTrailerPreviewPipeline(
        itemId = item.id,
        title = item.name,
        releaseInfo = item.releaseInfo,
        apiType = item.apiType,
        fallbackYtId = item.trailerYtIds.firstOrNull()
    )
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    fallbackYtId: String? = null
) {
    if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
    if (startupGracePeriodActive) return

    // Resolve fallbackYtId from catalog item if not provided
    val resolvedFallbackYtId = fallbackYtId ?: findCatalogItemById(itemId)?.trailerYtIds?.firstOrNull()

    // Always bump version — only the latest request (highest version) will proceed after debounce
    activeTrailerPreviewItemId = itemId
    trailerPreviewRequestVersion++
    val requestVersion = trailerPreviewRequestVersion

    if (trailerPreviewNegativeCache.contains(itemId)) return
    if (trailerPreviewUrlsState.containsKey(itemId)) return
    if (!trailerPreviewLoadingIds.add(itemId)) return

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Debounce: wait for focus to settle before hitting network
            delay(180)

            // Only the LATEST request proceeds — all earlier ones are stale
            if (trailerPreviewRequestVersion != requestVersion) {
                return@launch
            }

            val trailerType = apiType
            val trailerTitle = title
            val tmdbId = runCatching {
                tmdbService.ensureTmdbId(itemId, trailerType)
                    ?: tmdbService.fetchMetadataForTitleQuery(trailerTitle, trailerType)?.tmdbId?.toString()
            }.getOrNull()

            val trailerSource = trailerService.getTrailerPlaybackSource(
                title = trailerTitle,
                year = extractYear(releaseInfo),
                tmdbId = tmdbId,
                type = trailerType
            )

            withContext(Dispatchers.Main) {
                if (trailerSource?.videoUrl.isNullOrBlank()) {
                    val fallbackSource = resolvedFallbackYtId?.let { ytId ->
                        trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                            youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                            title = title,
                            year = extractYear(releaseInfo)
                        )
                    }
                    if (fallbackSource?.videoUrl != null) {
                        if (trailerPreviewUrlsState[itemId] != fallbackSource.videoUrl) {
                            trailerPreviewUrlsState[itemId] = fallbackSource.videoUrl
                        }
                        val fallbackAudio = fallbackSource.audioUrl
                        if (fallbackAudio.isNullOrBlank()) {
                            trailerPreviewAudioUrlsState.remove(itemId)
                        } else if (trailerPreviewAudioUrlsState[itemId] != fallbackAudio) {
                            trailerPreviewAudioUrlsState[itemId] = fallbackAudio
                        }
                    } else {
                        trailerPreviewNegativeCache.add(itemId)
                        trailerPreviewUrlsState.remove(itemId)
                        trailerPreviewAudioUrlsState.remove(itemId)
                    }
                } else {
                    val videoUrl = trailerSource.videoUrl
                    if (trailerPreviewUrlsState[itemId] != videoUrl) {
                        trailerPreviewUrlsState[itemId] = videoUrl
                    }
                    val audioUrl = trailerSource.audioUrl
                    if (audioUrl.isNullOrBlank()) {
                        trailerPreviewAudioUrlsState.remove(itemId)
                    } else if (trailerPreviewAudioUrlsState[itemId] != audioUrl) {
                        trailerPreviewAudioUrlsState[itemId] = audioUrl
                    }
                }
            }
        } finally {
            trailerPreviewLoadingIds.remove(itemId)
        }
    }
}

/**
 * Enriches only the first visible cards from each IPTV row. Remaining cards retain the
 * existing focus/adjacent prefetch behavior, so opening Home stays responsive on low-RAM TVs.
 */
}

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) {
        deferredEnrichItem = item
        return
    }
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
    if (pendingTmdbEnrichItemId == item.id) return
    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) setEnrichingItemId(null)
    val tmdbEnabled = currentTmdbSettings.enabled &&
        (_uiState.value.homeLayout != HomeLayout.MODERN || currentTmdbSettings.modernHomeEnabled)
    if (!tmdbEnabled && !externalMetaPrefetchEnabled) return
    if (tmdbEnabled) setEnrichingItemId(item.id)
    pendingTmdbEnrichItemId = item.id
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (pendingTmdbEnrichItemId != item.id) return@launch
        try {
            var enriched = false
            if (tmdbEnabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                val result = tmdbId?.let { runCatching {
                    tmdbMetadataService.fetchEnrichment(it, item.type, currentTmdbSettings.language)
                }.getOrNull() }
                if (result != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, result, item)
                    enriched = true
                }
            }
            if (!enriched && externalMetaPrefetchEnabled && externalMetaPrefetchInFlightIds.add(item.id)) {
                try {
                    val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                        .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    if (result is NetworkResult.Success) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data, item)
                    }
                } finally { externalMetaPrefetchInFlightIds.remove(item.id) }
            }
        } finally {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            if (pendingTmdbEnrichItemId == item.id) {
                pendingTmdbEnrichItemId = null
            }
        }
    }
}

internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (startupGracePeriodActive || item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
    if (pendingTmdbEnrichItemId == item.id || pendingAdjacentPrefetchItemId == item.id) return
    pendingAdjacentPrefetchItemId = item.id
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (pendingAdjacentPrefetchItemId != item.id) return@launch
        try {
            val tmdbEnabled = currentTmdbSettings.enabled &&
                (_uiState.value.homeLayout != HomeLayout.MODERN || currentTmdbSettings.modernHomeEnabled)
            if (tmdbEnabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                val result = tmdbId?.let { runCatching {
                    tmdbMetadataService.fetchEnrichment(it, item.type, currentTmdbSettings.language)
                }.getOrNull() }
                if (result != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, result, item)
                }
            }
        } finally {
            if (pendingAdjacentPrefetchItemId == item.id) {
                pendingAdjacentPrefetchItemId = null
            }
        }
    }
}

private fun formatTmdbRuntime(minutes: Int): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (hours > 0) {
        hours.toString() + "h" + remainingMinutes.toString().padStart(2, '0')
    } else {
        "$minutes min"
    }
}

internal fun HomeViewModel.updateCatalogItemImdbRating(itemId: String, rating: Float) {
    updateIndexedCatalogItem(itemId) { currentItem ->
        currentItem.copy(imdbRating = rating)
    }
    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val idx = row.items.indexOfFirst { it.id == itemId }
            if (idx < 0) row
            else {
                val updated = row.items[idx].copy(imdbRating = rating)
                if (updated == row.items[idx]) row
                else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[idx] = updated
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
}

private fun HomeViewModel.updateCatalogItemWithMeta(
    itemId: String,
    meta: Meta,
    fallbackItem: MetaPreview? = null
) {
    val incomingTrailerYtIds = meta.trailerYtIds
    val seasonCount = meta.videos
        .asSequence()
        .mapNotNull { it.season }
        .filter { it > 0 }
        .distinct()
        .count()
        .takeIf { it > 0 }

    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = currentItem.background ?: meta.backdropUrl ?: currentItem.landscapePoster,
        logo = meta.logo ?: currentItem.logo,
        description = meta.description ?: currentItem.description,
        imdbRating = meta.imdbRating ?: currentItem.imdbRating,
        genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres,
        runtime = meta.runtime ?: currentItem.runtime,
        status = meta.status ?: currentItem.status,
        ageRating = meta.ageRating ?: currentItem.ageRating,
        language = meta.language ?: currentItem.language,
        country = meta.country ?: currentItem.country,
        director = meta.director.ifEmpty { currentItem.director },
        writer = meta.writer.ifEmpty { currentItem.writer },
        cast = meta.cast.ifEmpty { currentItem.cast },
        seasonCount = seasonCount ?: currentItem.seasonCount,
        trailerYtIds = if (incomingTrailerYtIds.isNotEmpty()) incomingTrailerYtIds else currentItem.trailerYtIds
    )

    updateIndexedCatalogItem(itemId, ::mergeItem)

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) {
                row
            } else {
                val mergedItem = mergeItem(row.items[itemIndex])
                if (mergedItem == row.items[itemIndex]) {
                    row
                } else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[itemIndex] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
    (_enrichedPreviews.value[itemId] ?: findCatalogItemById(itemId) ?: fallbackItem)
        ?.let(::mergeItem)
        ?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        _enrichedPreviews.update { it + (itemId to enriched) }
    }

    // If external meta brought new trailerYtIds and the item has no trailer resolved yet, retry.
    // Only retry if this item is currently focused — avoid prefetching trailers for adjacent items.
    if (incomingTrailerYtIds.isNotEmpty() && !trailerPreviewUrlsState.containsKey(itemId) && activeTrailerPreviewItemId == itemId) {
        trailerPreviewNegativeCache.remove(itemId)
        trailerPreviewLoadingIds.remove(itemId)
        // Bump version so any in-flight pipeline for this item treats itself as stale
        // and won't overwrite the retry result with a negative cache entry.
        trailerPreviewRequestVersion++
        val currentItem = findCatalogItemById(itemId) ?: return
        requestTrailerPreviewPipeline(currentItem)
    }
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): List<MetaPreview> {
    if (items.isEmpty()) return items
    val mdbSettings = currentMdbListSettings
    val mdbEnabled = mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()

    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    val tmdbDeferred = async {
                        val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async null
                        tmdbId.toIntOrNull()?.let { numericId ->
                            runCatching { tmdbService.tmdbToImdb(numericId, item.apiType) }
                        }
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = settings.language
                        )
                    }
                    val mdbDeferred = if (mdbEnabled) async {
                        runCatching { mdbListRepository.getImdbRatingForItem(item.id, item.apiType) }.getOrNull()
                    } else null

                    val enrichment = tmdbDeferred.await() ?: return@async if (item.rawType == "iptv_movie") {
                        // A provider banner is valid for the small card, but never for
                        // the full-screen Home background. Keep the background empty
                        // until an exact TMDB result supplies official artwork.
                        item.copy(background = null, landscapePoster = null)
                    } else {
                        item
                    }
                    val mdbImdbRating = mdbDeferred?.await()

                    var enriched = item

                    if (settings.useArtwork) {
                        enriched = enriched.copy(
                            background = if (enriched.rawType == "iptv_movie") {
                                enrichment.backdrop ?: enrichment.poster ?: enriched.background
                            } else {
                                enriched.background ?: enrichment.backdrop
                            },
                            logo = enrichment.logo ?: enriched.logo,
                            poster = enrichment.poster ?: enriched.poster
                        )
                    }

                    if (settings.useBasicInfo) {
                        val localizedName = enrichment.localizedTitle ?: enriched.name
                        enriched = enriched.copy(
                            name = if (enriched.rawType == "iptv_movie") {
                                prefixHomeIptvMovieDuration(
                                    localizedName,
                                    enrichment.runtimeMinutes?.let(::formatTmdbRuntime) ?: enriched.runtime
                                )
                            } else {
                                localizedName
                            },
                            description = enrichment.description ?: enriched.description,
                            genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                            imdbRating = mdbImdbRating?.toFloat() ?: enriched.imdbRating,
                            cast = if (enriched.cast.isEmpty()) enrichment.castMembers.map { it.name } else enriched.cast
                        )
                    }

                    if (settings.useDetails) {
                        enriched = enriched.copy(
                            runtime = enrichment.runtimeMinutes?.let(::formatTmdbRuntime) ?: enriched.runtime,
                            status = enrichment.status ?: enriched.status,
                            ageRating = enrichment.ageRating ?: enriched.ageRating,
                            country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                            language = enrichment.language ?: enriched.language,
                            director = if (enriched.director.isEmpty()) enrichment.director else enriched.director,
                            writer = if (enriched.writer.isEmpty()) enrichment.writer else enriched.writer
                        )
                        if (enriched.rawType == "iptv_movie") {
                            enriched = enriched.copy(
                                name = prefixHomeIptvMovieDuration(enriched.name, enriched.runtime)
                            )
                        }
                    }

                    if (settings.useReleaseDates) {
                        enriched = enriched.copy(
                            releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
                        )
                    }

                    enriched
                } catch (e: Exception) {
                    Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                    item
                }
            }
        }.awaitAll()
    }
}

internal fun HomeViewModel.replaceGridHeroItemsPipeline(
    gridItems: List<GridItem>,
    heroItems: List<MetaPreview>
): List<GridItem> {
    if (gridItems.isEmpty()) return gridItems
    return gridItems.map { item ->
        if (item is GridItem.Hero) {
            item.copy(items = heroItems)
        } else {
            item
        }
    }
}

internal fun HomeViewModel.heroEnrichmentSignaturePipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): String {
    val itemSignature = items.joinToString(separator = "|") { item ->
        "${item.id}:${item.apiType}:${item.name}:${item.backdropUrl}:${item.logo}:${item.poster}"
    }
    return buildString {
        append(settings.enabled)
        append(':')
        append(settings.language)
        append(':')
        append(settings.useArtwork)
        append(':')
        append(settings.useBasicInfo)
        append(':')
        append(settings.useDetails)
        append("::")
        append(itemSignature)
    }
}

private fun List<CatalogRow>.homeCatalogOrderSignature(): String =
    joinToString(separator = "|") { row ->
        row.key() + ":" + row.items.joinToString(separator = ",") { it.id }
    }

private fun List<CatalogRow>.homeCatalogIptvArtworkSignature(): String =
    asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.rawType == "iptv_movie" || it.rawType == "iptv_series" }
        .joinToString(separator = "|") { item ->
            "${item.id}:${item.poster.orEmpty()}:${item.background.orEmpty()}:${item.landscapePoster.orEmpty()}"
        }

private fun List<CatalogRow>.sortedHomeIptvMovieFavoriteRows(): List<CatalogRow> =
    map { row ->
        if (row.addonId == HomeViewModel.IPTV_FAVORITES_ADDON_ID &&
            row.catalogId == HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID
        ) {
            row.copy(items = row.items.sortedByHomeIptvMovieDuration())
        } else {
            row
        }
    }

private fun HomeViewModel.sortHomeIptvMovieFavoriteRows() {
    iptvFavoriteRows = iptvFavoriteRows.map { homeRow ->
        if (homeRow.row.catalogId == HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID) {
            homeRow.copy(row = homeRow.row.copy(items = homeRow.row.items.sortedByHomeIptvMovieDuration()))
        } else {
            homeRow
        }
    }
    _uiState.update { state ->
        state.copy(catalogRows = state.catalogRows.sortedHomeIptvMovieFavoriteRows())
    }
    sortIndexedHomeIptvMovieFavoriteRows()
}

private fun HomeViewModel.sortIndexedHomeIptvMovieFavoriteRows() {
    synchronized(catalogStateLock) {
        catalogsMap.keys.toList().forEach { key ->
            val row = catalogsMap[key] ?: return@forEach
            if (row.addonId == HomeViewModel.IPTV_FAVORITES_ADDON_ID &&
                row.catalogId == HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID
            ) {
                val sortedItems = row.items.sortedByHomeIptvMovieDuration()
                if (sortedItems != row.items) {
                    catalogsMap[key] = row.copy(items = sortedItems)
                    truncatedRowCache.remove(key)
                }
            }
        }
    }
}
