package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nuvio.tv.ui.screens.settings.FreeboxBrowserViewModel
import com.nuvio.tv.ui.screens.settings.LayoutSettingsViewModel
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.freebox.freeboxProgrammeOverrideKey
import com.nuvio.tv.ui.components.freeboxposter.FreeboxPosterPickerViewModel
import com.nuvio.tv.ui.components.freeboxposter.FreeboxPosterPickerDialog
import com.nuvio.tv.ui.components.freeboxposter.FreeboxPosterPickerState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.tmdb.TmdbPosterCandidate
import com.nuvio.tv.core.tmdb.TmdbArtworkType
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ThumbnailOrientation
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LocalFavoriteInProgressContentIds
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.TrailerPlayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import com.jojotv.home.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.core.recommendations.AndroidTvLaunchRequest
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class HomePosterOptionsTarget(
    val item: MetaPreview,
    val addonBaseUrl: String
)

private const val HOME_LAYOUT_GATE_TIMEOUT_MS = 1_500L
// The profile switch must never leave the TV on a full-screen spinner while
// remote data is being reconciled. Home can render its shell and fill rows
// progressively as the observers finish.
private const val HOME_STARTUP_HARD_TIMEOUT_MS = 2_500L

@Composable
private fun HomeBlankState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    )
}

@Composable
private fun HomeStartupLoader() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator()
    }
}

private val IPTV_FAVORITES_CATALOG_IDS = setOf(
    HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID,
    HomeViewModel.IPTV_SERIES_FAVORITES_CATALOG_ID
)

private fun HomeUiState.withIptvFavoriteRowsOnly(
    showOnlyFavorites: Boolean,
    favoriteCatalogId: String? = null
): HomeUiState {
    fun isFavoriteCatalog(catalogId: String) = catalogId in IPTV_FAVORITES_CATALOG_IDS
    fun keepCatalog(catalogId: String) = when {
        !showOnlyFavorites -> !isFavoriteCatalog(catalogId)
        favoriteCatalogId != null -> catalogId == favoriteCatalogId
        else -> isFavoriteCatalog(catalogId)
    }
    // The dedicated Favorites screen contains only IPTV favorite catalogs.  On the
    // normal Home screen we merely hide those catalogs: Continue watching and the
    // Freebox Videos row have no catalogId and must remain visible.
    fun keepNonCatalogHomeContent() = !showOnlyFavorites

    val catalogRows = catalogRows.filter { keepCatalog(it.catalogId) }
    val homeRows = homeRows.filter { homeRow ->
        when (homeRow) {
            is HomeRow.Catalog -> keepCatalog(homeRow.row.catalogId)
            else -> keepNonCatalogHomeContent()
        }
    }
    val gridItems = gridItems.filter { item ->
        when (item) {
            is GridItem.SectionDivider -> keepCatalog(item.catalogId)
            is GridItem.Content -> keepCatalog(item.catalogId)
            is GridItem.SeeAll -> keepCatalog(item.catalogId)
            else -> keepNonCatalogHomeContent()
        }
    }
    val modernRows = modernHomePresentation.rows.list
        .filter { row -> row.catalogId?.let(::keepCatalog) ?: keepNonCatalogHomeContent() }
        .mapIndexed { index, row -> row.copy(globalRowIndex = index) }
    val modernRowKeys = modernRows.map { it.key }.toSet()
    val modernLookups = modernHomePresentation.lookups.copy(
        rowIndexByKey = modernRows.mapIndexed { index, row -> row.key to index }.toMap().asStable(),
        rowByKey = modernRows.associateBy { it.key }.asStable(),
        rowKeyByGlobalRowIndex = modernRows.associate { it.globalRowIndex to it.key }.asStable(),
        firstHeroPreviewByRow = modernRows.mapNotNull { row ->
            row.items.firstOrNull()?.heroPreview?.let { row.key to it }
        }.toMap().asStable(),
        fallbackBackdropByRow = modernHomePresentation.lookups.fallbackBackdropByRow
            .filterKeys { it in modernRowKeys }
            .asStable(),
        activeRowKeys = modernRowKeys.asStable(),
        activeItemKeysByRow = modernHomePresentation.lookups.activeItemKeysByRow
            .filterKeys { it in modernRowKeys }
            .asStable(),
        activeCatalogItemIds = modernRows.flatMap { row ->
            row.items.mapNotNull { item -> (item.payload as? ModernPayload.Catalog)?.itemId }
        }.toSet().asStable()
    )
    val activeFreeboxProgress = continueWatchingItems
        .asSequence()
        .filterIsInstance<ContinueWatchingItem.InProgress>()
        .map { it.progress }
        .filter { progress ->
            progress.contentId.startsWith("freebox:", ignoreCase = true)
        }
        .toList()
    val activeFreeboxIds = activeFreeboxProgress
        .flatMap { progress -> listOf(progress.contentId, progress.videoId) }
        .toSet()
    val activeFreeboxPaths = activeFreeboxProgress
        .flatMap { progress ->
            listOf(progress.contentId, progress.videoId).map(::freeboxPathFromContentId)
        }
        .toSet()
    val visibleFreeboxEntries = freeboxVideoEntries.filter { entry ->
        entry.isDirectory || (
            freeboxContentIdForEntry(entry) !in activeFreeboxIds &&
                entry.path !in activeFreeboxPaths
            )
    }

    return copy(
        catalogRows = catalogRows,
        homeRows = homeRows,
        gridItems = gridItems,
        continueWatchingItems = if (showOnlyFavorites) emptyList() else continueWatchingItems,
        freeboxVideoEntries = if (showOnlyFavorites) emptyList() else visibleFreeboxEntries,
        freeboxVideoArtwork = if (showOnlyFavorites) emptyMap() else freeboxVideoArtwork,
        freeboxVideoBackdrops = if (showOnlyFavorites) emptyMap() else freeboxVideoBackdrops,
        freeboxVideoMetadata = if (showOnlyFavorites) emptyMap() else freeboxVideoMetadata,
        freeboxVideoProbedDurations = if (showOnlyFavorites) emptyMap() else freeboxVideoProbedDurations,
        initialFreeboxVideosResolved = showOnlyFavorites || initialFreeboxVideosResolved,
        heroItems = if (showOnlyFavorites) emptyList() else heroItems,
        modernHomePresentation = modernHomePresentation.copy(
            rows = StableList(modernRows),
            lookups = modernLookups
        )
    )
}

/**
 * Apply the current Freebox artwork at the final rendering boundary.
 *
 * A freshly stopped player can publish its progress before the home artwork
 * pipeline has completed. Rendering both provider artwork and manual overrides
 * here keeps the Continue Watching card and its Home background on the same
 * image from the first displayable snapshot.
 */
private fun HomeUiState.withFreeboxArtworkOverrides(
    posterOverrides: Map<String, String>,
    backdropOverrides: Map<String, String>
): HomeUiState {
    if (freeboxVideoArtwork.isEmpty() &&
        freeboxVideoBackdrops.isEmpty() &&
        posterOverrides.isEmpty() &&
        backdropOverrides.isEmpty()
    ) return this

    val artwork = freeboxVideoArtwork + posterOverrides
    val backdrops = freeboxVideoBackdrops + backdropOverrides
    fun resolve(values: Map<String, String>, contentId: String, name: String): String? =
        values[contentId]
            ?: values[freeboxProgrammeOverrideKey(name, false)]
            ?: values.entries.firstOrNull {
                freeboxPathFromContentId(it.key) == freeboxPathFromContentId(contentId)
            }?.value

    fun MetaPreview.withManualIptvArtwork(): MetaPreview {
        if (!rawType.startsWith("iptv_", ignoreCase = true) &&
            !id.startsWith("iptv_", ignoreCase = true)
        ) return this
        val manualPoster = (posterOverrides[id]
            ?: playbackProgressId?.let(posterOverrides::get))?.takeIf { it.isNotBlank() }
        val manualBackdrop = (backdropOverrides[id]
            ?: playbackProgressId?.let(backdropOverrides::get))?.takeIf { it.isNotBlank() }
        if (manualPoster == null && manualBackdrop == null) return this
        val selectedHomeThumbnail = manualBackdrop ?: manualPoster
        return copy(
            poster = manualPoster ?: poster?.takeIf { it.isNotBlank() },
            rawPosterUrl = manualPoster ?: rawPosterUrl,
            // The picker represents one Home thumbnail choice. If the user
            // deliberately chooses a portrait, it must remain the selected
            // image even inside a landscape card after a reload.
            background = selectedHomeThumbnail ?: background?.takeIf { it.isNotBlank() },
            landscapePoster = selectedHomeThumbnail ?: landscapePoster?.takeIf { it.isNotBlank() },
            homeArtworkOverride = selectedHomeThumbnail
        )
    }

    fun ModernCarouselItem.withManualIptvArtwork(): ModernCarouselItem {
        val original = metaPreview ?: return this
        val updated = original.withManualIptvArtwork()
        if (updated == original) return this
        val manualPoster = posterOverrides[original.id]
            ?: original.playbackProgressId?.let(posterOverrides::get)
        val manualBackdrop = backdropOverrides[original.id]
            ?: original.playbackProgressId?.let(backdropOverrides::get)
        val oldBackdrop = original.background ?: original.landscapePoster
        val updatedImage = when {
            manualBackdrop != null -> updated.background ?: updated.landscapePoster ?: updated.poster
            manualPoster != null -> updated.poster ?: updated.background ?: updated.landscapePoster
            imageUrl == oldBackdrop -> updated.background ?: updated.landscapePoster ?: updated.poster
            else -> updated.poster ?: updated.background ?: updated.landscapePoster
        }
        return copy(
            imageUrl = updatedImage,
            metaPreview = updated,
            heroPreview = heroPreview.copy(
                poster = updated.poster,
                backdrop = updated.background ?: updated.landscapePoster ?: updated.poster,
                imageUrl = updatedImage
            )
        )
    }
    val continueWatching = continueWatchingItems.map { item ->
        if (item is ContinueWatchingItem.InProgress &&
            (item.progress.contentType.equals("freebox", ignoreCase = true) ||
                item.progress.contentId.startsWith("freebox:", ignoreCase = true))
        ) {
            val contentId = item.progress.contentId
            item.copy(
                progress = item.progress.copy(
                    poster = resolve(artwork, contentId, item.progress.name) ?: item.progress.poster,
                    backdrop = resolve(backdrops, contentId, item.progress.name)
                        ?: resolve(artwork, contentId, item.progress.name)
                        ?: item.progress.backdrop
                )
            )
        } else {
            if (item is ContinueWatchingItem.InProgress && item.progress.isIptvPosterEditable()) {
                val contentId = item.progress.contentId
                item.copy(
                    progress = item.progress.copy(
                        poster = posterOverrides[contentId] ?: item.progress.poster,
                        backdrop = backdropOverrides[contentId] ?: item.progress.backdrop
                    )
                )
            } else item
        }
    }

    val updatedCatalogRows = catalogRows.map { row ->
        row.copy(items = row.items.map { it.withManualIptvArtwork() })
    }
    val updatedHomeRows = homeRows.map { row ->
        if (row is HomeRow.Catalog) {
            row.copy(row = row.row.copy(items = row.row.items.map { it.withManualIptvArtwork() }))
        } else row
    }
    val updatedGridItems = gridItems.map { gridItem ->
        when (gridItem) {
            is GridItem.Content -> gridItem.copy(item = gridItem.item.withManualIptvArtwork())
            is GridItem.Hero -> gridItem.copy(items = gridItem.items.map { it.withManualIptvArtwork() })
            else -> gridItem
        }
    }
    val updatedModernRows = modernHomePresentation.rows.list.map { row ->
        row.copy(items = row.items.list.map { it.withManualIptvArtwork() }.asStable())
    }
    val updatedModernLookups = modernHomePresentation.lookups.copy(
        rowByKey = updatedModernRows.associateBy { it.key }.asStable(),
        firstHeroPreviewByRow = updatedModernRows.mapNotNull { row ->
            row.items.firstOrNull()?.heroPreview?.let { row.key to it }
        }.toMap().asStable()
    )

    return copy(
        catalogRows = updatedCatalogRows,
        homeRows = updatedHomeRows,
        gridItems = updatedGridItems,
        freeboxVideoArtwork = artwork,
        freeboxVideoBackdrops = backdrops,
        continueWatchingItems = continueWatching,
        modernHomePresentation = modernHomePresentation.copy(
            rows = updatedModernRows.asStable(),
            lookups = updatedModernLookups
        )
    )
}

private fun ContinueWatchingItem.InProgress.toFreeboxEntry(uiState: HomeUiState): FreeboxFileEntry {
    val freeboxId = freeboxIdOrNull() ?: progress.contentId
    val path = freeboxPathFromContentId(freeboxId)
    val existing = uiState.freeboxVideoEntries.firstOrNull { entry ->
        entry.path == path || freeboxPathFromContentId(freeboxContentIdForEntry(entry)) == path
    }
    return existing ?: FreeboxFileEntry(
        name = progress.name.ifBlank { path.substringAfterLast('/') },
        path = path,
        encodedPath = null,
        isDirectory = false,
        size = null,
        durationMs = progress.duration
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    freeboxViewModel: FreeboxBrowserViewModel = hiltViewModel(),
    layoutSettingsViewModel: LayoutSettingsViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onSearchContinueWatchingPoster: ((ContinueWatchingItem) -> Unit)? = null,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateToFreebox: (String, String?, String?, com.nuvio.tv.core.tmdb.FreeboxVideoMeta?) -> Unit = { _, _, _, _ -> },
    androidTvLaunchRequest: AndroidTvLaunchRequest? = null,
    onAndroidTvLaunchConsumed: () -> Unit = {},
    favoritesOnly: Boolean = false,
    favoriteCatalogId: String? = null,
) {
    val sourceUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val iptvSidebarSettings by viewModel.iptvSettingsDataStore.settings.collectAsStateWithLifecycle(
        initialValue = com.nuvio.tv.data.local.IptvSidebarSettings()
    )
    val posterOverrides by viewModel.freeboxPosterOverrideDataStore.overrides.collectAsStateWithLifecycle(emptyMap())
    val backdropOverrides by viewModel.freeboxPosterOverrideDataStore.backdropOverrides.collectAsStateWithLifecycle(emptyMap())
    val uiState = remember(
        sourceUiState,
        favoritesOnly,
        favoriteCatalogId,
        iptvSidebarSettings.showFavoritesInSidebar,
        posterOverrides,
        backdropOverrides
    ) {
        val stateWithFreeboxArtwork = sourceUiState.withFreeboxArtworkOverrides(
            posterOverrides = posterOverrides,
            backdropOverrides = backdropOverrides
        )
        when {
            favoritesOnly -> stateWithFreeboxArtwork.withIptvFavoriteRowsOnly(
                showOnlyFavorites = true,
                favoriteCatalogId = favoriteCatalogId
            )
            iptvSidebarSettings.showFavoritesInSidebar -> stateWithFreeboxArtwork.withIptvFavoriteRowsOnly(showOnlyFavorites = false)
            else -> stateWithFreeboxArtwork
        }
    }

    val layoutUiState by layoutSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val initialCwResolved by viewModel.initialCwResolved.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val freeboxPosterPickerViewModel: FreeboxPosterPickerViewModel = hiltViewModel()
    val freeboxPosterPickerState by freeboxPosterPickerViewModel.state.collectAsStateWithLifecycle()
    var showContinueWatchingFreeboxPosterPicker by remember { mutableStateOf(false) }
    var iptvPosterPickerItem by remember { mutableStateOf<ContinueWatchingItem.InProgress?>(null) }
    var iptvCatalogPosterPickerTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }
    var iptvPosterCandidates by remember { mutableStateOf<List<TmdbPosterCandidate>>(emptyList()) }
    var iptvPosterPickerLoading by remember { mutableStateOf(false) }
    var iptvPosterPickerSaveFailed by remember { mutableStateOf(false) }
    var pendingIptvContinueWatchingArtwork by remember {
        mutableStateOf<Pair<ContinueWatchingItem.InProgress, TmdbPosterCandidate>?>(null)
    }
    var pendingIptvCatalogArtwork by remember {
        mutableStateOf<Pair<HomePosterOptionsTarget, TmdbPosterCandidate>?>(null)
    }
    // All Home artwork pickers close first. Applying the image on the next
    // composed frame lets Android TV restore the originating card's focus
    // before that card is recomposed with its new image.
    LaunchedEffect(pendingIptvContinueWatchingArtwork) {
        pendingIptvContinueWatchingArtwork?.let { (item, poster) ->
            delay(50L)
            viewModel.updateIptvContinueWatchingPoster(item, poster)
            pendingIptvContinueWatchingArtwork = null
        }
    }
    LaunchedEffect(pendingIptvCatalogArtwork) {
        pendingIptvCatalogArtwork?.let { (target, poster) ->
            delay(50L)
            viewModel.updateIptvFavoriteArtwork(target.item, poster)
            pendingIptvCatalogArtwork = null
        }
    }
    val defaultSearchContinueWatchingPoster = remember(
        freeboxPosterPickerViewModel,
        uiState.freeboxVideoEntries,
        uiState.freeboxVideoArtwork,
        uiState.freeboxVideoBackdrops,
        posterOverrides,
        backdropOverrides
    ) {
        { item: ContinueWatchingItem ->
            if (item is ContinueWatchingItem.InProgress && item.progress.contentId.startsWith("freebox:")) {
                val entry = item.toFreeboxEntry(uiState)
                // In Modern coverflow the Videos row is outside the composed
                // viewport. Its historical dialog host therefore appears only
                // after pressing Down. Host this CW request at Home level so
                // the picker opens immediately from the options action.
                showContinueWatchingFreeboxPosterPicker = true
                freeboxPosterPickerViewModel.open(
                    entry = entry,
                    currentPosterUrl = posterOverrides[item.progress.contentId]
                        ?: uiState.freeboxVideoArtwork[item.progress.contentId]
                        ?: item.progress.poster,
                    currentBackdropUrl = backdropOverrides[item.progress.contentId]
                        ?: uiState.freeboxVideoBackdrops[item.progress.contentId]
                        ?: item.progress.backdrop,
                    artworkType = if (
                        layoutUiState.continueWatchingThumbnailOrientation == ThumbnailOrientation.PORTRAIT
                    ) TmdbArtworkType.PORTRAIT else TmdbArtworkType.LANDSCAPE
                )
            } else if (item is ContinueWatchingItem.InProgress && item.progress.isIptvPosterEditable()) {
                iptvPosterPickerItem = item
            }
        }
    }
    val onSearchContinueWatchingPosterEffective = onSearchContinueWatchingPoster ?: defaultSearchContinueWatchingPoster
    val deleteFreeboxProgressFromHome = remember(viewModel, uiState.freeboxVideoEntries) {
        { item: ContinueWatchingItem ->
            if (item is ContinueWatchingItem.InProgress && item.freeboxIdOrNull() != null) {
                // This callback is used by the explicit "Supprimer de la Freebox"
                // action. The ViewModel keeps every row intact until the remote
                // deletion has been confirmed by the Freebox.
                viewModel.deleteFreeboxVideoFromHome(item.toFreeboxEntry(uiState))
            }
        }
    }
    val deleteFreeboxVideoFromHome = remember(viewModel) {
        { entry: FreeboxFileEntry -> viewModel.deleteFreeboxVideoFromHome(entry) }
    }
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    val hasCollectionContent = uiState.homeRows.any { it is HomeRow.CollectionRow }
    val hasHeroContent = uiState.heroItems.isNotEmpty()
    val hasFreeboxContent = uiState.freeboxVideoEntries.isNotEmpty()
    val hasContinueWatchingContent = uiState.continueWatchingItems.isNotEmpty()
    val hasModernRowsContent = uiState.modernHomePresentation.rows.list.isNotEmpty()
    val presentedContinueWatchingKeys = uiState.modernHomePresentation.rows.list
        .firstOrNull { it.key == MODERN_CONTINUE_WATCHING_ROW_KEY }
        ?.items
        ?.list
        ?.map { it.key }
        .orEmpty()
    val expectedContinueWatchingKeys = uiState.continueWatchingItems.map(::continueWatchingItemKey)
    val continueWatchingPresentationReady =
        presentedContinueWatchingKeys == expectedContinueWatchingKeys
    val canRenderHomeWithoutCatalogAddons =
        uiState.homeLayout == HomeLayout.MODERN ||
            uiState.installedAddonsCount == 0 ||
            hasFreeboxContent ||
            hasModernRowsContent
    val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
        uiState.continueWatchingItems.isNotEmpty() ||
        uiState.heroItems.isNotEmpty() ||
        hasCollectionContent ||
        hasFreeboxContent ||
        hasModernRowsContent
    val modernPresentationReady =
        uiState.homeLayout != HomeLayout.MODERN ||
            (continueWatchingPresentationReady && (
                hasModernRowsContent ||
                    hasFreeboxContent ||
                    !uiState.isLoading ||
                    (uiState.heroSectionEnabled && hasHeroContent && !hasCatalogContent && !hasCollectionContent)
                ))
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    // Once we've shown stable home content, never go back to loading gate.
    var homeStableGateReleased by rememberSaveable { mutableStateOf(false) }
    var homeStartupTimedOut by rememberSaveable { mutableStateOf(false) }
    // Une préférence de mise en page de profil absente ou corrompue ne doit jamais
    // laisser l'accueil sur un indicateur permanent.
    var layoutGateTimedOut by rememberSaveable { mutableStateOf(false) }
    // Track that catalog loading has started at least once (isLoading went true â€” false)
    var catalogLoadingStarted by rememberSaveable { mutableStateOf(false) }
    var posterOptionsTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }
    var manualTrailerTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }

    LaunchedEffect(uiState.homeLayout) {
        if (uiState.homeLayout != HomeLayout.MODERN) {
            HeroBackdropState.update(null)
        }
    }

    LaunchedEffect(iptvPosterPickerItem, iptvCatalogPosterPickerTarget) {
        val item = iptvPosterPickerItem
        val catalogTarget = iptvCatalogPosterPickerTarget
        if (item == null && catalogTarget == null) {
            iptvPosterCandidates = emptyList()
            iptvPosterPickerLoading = false
            iptvPosterPickerSaveFailed = false
            return@LaunchedEffect
        }
        iptvPosterPickerLoading = true
        iptvPosterPickerSaveFailed = false
        val pickerContentId = item?.progress?.contentId ?: catalogTarget?.item?.id.orEmpty()
        val pickerProgressId = catalogTarget?.item?.playbackProgressId
        val pickerArtworkType = when {
            item != null -> if (
                layoutUiState.continueWatchingThumbnailOrientation == ThumbnailOrientation.PORTRAIT
            ) TmdbArtworkType.PORTRAIT else TmdbArtworkType.LANDSCAPE
            catalogTarget?.item?.rawType.equals("iptv_series", ignoreCase = true) -> if (
                layoutUiState.seriesFavoritesThumbnailOrientation == ThumbnailOrientation.PORTRAIT
            ) TmdbArtworkType.PORTRAIT else TmdbArtworkType.LANDSCAPE
            catalogTarget?.item?.rawType.equals("iptv_movie", ignoreCase = true) -> if (
                layoutUiState.movieFavoritesThumbnailOrientation == ThumbnailOrientation.PORTRAIT
            ) TmdbArtworkType.PORTRAIT else TmdbArtworkType.LANDSCAPE
            else -> if (
                layoutUiState.videoThumbnailOrientation == ThumbnailOrientation.PORTRAIT
            ) TmdbArtworkType.PORTRAIT else TmdbArtworkType.LANDSCAPE
        }
        val currentPoster = posterOverrides[pickerContentId]
            ?: pickerProgressId?.let(posterOverrides::get)
            ?: item?.progress?.poster
            ?: catalogTarget?.item?.poster
        val currentBackdrop = backdropOverrides[pickerContentId]
            ?: pickerProgressId?.let(backdropOverrides::get)
            ?: item?.progress?.backdrop
            ?: catalogTarget?.item?.background
            ?: catalogTarget?.item?.landscapePoster
        iptvPosterCandidates = buildList {
            currentPoster?.takeIf { it.isNotBlank() && pickerArtworkType == TmdbArtworkType.PORTRAIT }?.let { url ->
                add(
                    TmdbPosterCandidate(
                        url = url,
                        language = null,
                        title = "Image actuelle",
                        artworkType = TmdbArtworkType.PORTRAIT
                    )
                )
            }
            currentBackdrop?.takeIf { it.isNotBlank() && it != currentPoster && pickerArtworkType == TmdbArtworkType.LANDSCAPE }?.let { url ->
                add(
                    TmdbPosterCandidate(
                        url = url,
                        language = null,
                        title = "Fond actuel",
                        artworkType = TmdbArtworkType.LANDSCAPE
                    )
                )
            }
        }
        iptvPosterPickerLoading = iptvPosterCandidates.isEmpty()
        coroutineScope {
            val frameDeferred = async(Dispatchers.IO) {
                runCatching {
                    when {
                        item != null -> viewModel.iptvFramePosterCandidates(item.progress)
                        catalogTarget != null -> viewModel.iptvFramePosterCandidates(catalogTarget.item)
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
            }
            val tmdbDeferred = async(Dispatchers.IO) {
                runCatching {
                    viewModel.tmdbService.fetchPosterCandidatesForTitleQuery(
                        query = cleanHomePosterSearchQuery(item?.progress?.name ?: catalogTarget?.item?.name.orEmpty()),
                        mediaTypeHint = when {
                            item?.progress?.contentType.equals("iptv_series", ignoreCase = true) -> "tv"
                            catalogTarget?.item?.rawType == "iptv_series" -> "tv"
                            else -> "movie"
                        },
                        artworkType = pickerArtworkType
                        )
                }.getOrDefault(emptyList())
            }
            val tmdbPosters = tmdbDeferred.await()
            if (item == iptvPosterPickerItem && catalogTarget == iptvCatalogPosterPickerTarget) {
                val tmdbByArtwork = tmdbPosters.associateBy { it.artworkType to it.url }
                iptvPosterCandidates = (iptvPosterCandidates + tmdbPosters)
                    .distinctBy { it.artworkType to it.url }
                    .map { candidate ->
                        tmdbByArtwork[candidate.artworkType to candidate.url] ?: candidate
                    }
                iptvPosterPickerLoading = false
            }

            val framePosters = frameDeferred.await()
            if (item == iptvPosterPickerItem && catalogTarget == iptvCatalogPosterPickerTarget) {
                iptvPosterCandidates = (iptvPosterCandidates + framePosters.filter {
                    it.artworkType == pickerArtworkType
                })
                    .distinctBy { it.artworkType to it.url }
                iptvPosterPickerLoading = false
            }
        }
    }

    LaunchedEffect(uiState.layoutPreferencesReady) {
        if (uiState.layoutPreferencesReady) {
            layoutGateTimedOut = false
        } else {
            delay(HOME_LAYOUT_GATE_TIMEOUT_MS)
            if (!uiState.layoutPreferencesReady) layoutGateTimedOut = true
        }
    }

    // This is an upper bound for the visible transition after profile
    // selection. It is intentionally independent from any network/cache
    // source: a slow NAS, Trakt response or catalog must not block Home.
    LaunchedEffect(Unit) {
        delay(HOME_STARTUP_HARD_TIMEOUT_MS)
        homeStartupTimedOut = true
    }

    // Notify ViewModel of locale changes after activity recreation
    LaunchedEffect(Unit) {
        viewModel.notifyLocaleChanged()
    }

    val movieWatchedStatus = uiState.movieWatchedStatus
    // Favorites intentionally hides Continue Watching, but its cards still
    // need the unfiltered resume state to display their playback badge.
    val favoriteInProgressContentIds = remember(sourceUiState.continueWatchingItems) {
        sourceUiState.continueWatchingItems
            .filterIsInstance<ContinueWatchingItem.InProgress>()
            .mapTo(mutableSetOf()) { it.progress.contentId }
    }
    // Use a stable lambda whose identity NEVER changes. The lambda captures
    // movieWatchedStatus via rememberUpdatedState so it always reads the latest
    // value without forcing downstream recomposition from lambda identity change.
    val latestMovieWatchedStatus = androidx.compose.runtime.rememberUpdatedState(movieWatchedStatus)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember {
        { item: MetaPreview ->
            latestMovieWatchedStatus.value[homeItemStatusKey(item.id, item.apiType)] == true
        }
    }
    val onCatalogItemLongPress: (MetaPreview, String) -> Unit = remember {
        { item, addonBaseUrl -> posterOptionsTarget = HomePosterOptionsTarget(item, addonBaseUrl) }
    }

    val onNavigateToDetailStable = remember(onNavigateToDetail) { onNavigateToDetail }
    val onContinueWatchingClickStable = remember(onContinueWatchingClick) { onContinueWatchingClick }
    val onContinueWatchingDetailsStable = remember(viewModel, onNavigateToDetailStable) {
        { item: ContinueWatchingItem ->
            viewModel.openContinueWatchingDetails(item, onNavigateToDetailStable)
        }
    }
    val onContinueWatchingStartFromBeginningStable = remember(onContinueWatchingStartFromBeginning) { onContinueWatchingStartFromBeginning }
    val onContinueWatchingPlayManuallyStable = remember(onContinueWatchingPlayManually) { onContinueWatchingPlayManually }
    val onNavigateToCatalogSeeAllStable = remember(onNavigateToCatalogSeeAll) { onNavigateToCatalogSeeAll }
    val onNavigateToFolderDetailStable = remember(onNavigateToFolderDetail) { onNavigateToFolderDetail }
    val onRemoveContinueWatchingStable = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }

    LaunchedEffect(androidTvLaunchRequest, uiState.continueWatchingItems, initialCwResolved) {
        val request = androidTvLaunchRequest ?: return@LaunchedEffect
        request.progressSnapshot?.let { progress ->
            onAndroidTvLaunchConsumed()
            onContinueWatchingClickStable(ContinueWatchingItem.InProgress(progress))
            return@LaunchedEffect
        }
        val item = uiState.continueWatchingItems.firstOrNull {
            request.matches(it.contentId(), it.contentType())
        }
        if (item != null) {
            onAndroidTvLaunchConsumed()
            onContinueWatchingClickStable(item)
        } else if (initialCwResolved) {
            onAndroidTvLaunchConsumed()
        }
    }

    LaunchedEffect(
        uiState.isLoading,
        uiState.layoutPreferencesReady,
        uiState.installedAddonsCount,
        hasAnyContent,
        hasCatalogContent,
        hasCollectionContent,
        hasHeroContent,
        hasFreeboxContent,
        hasModernRowsContent,
        initialCwResolved,
        modernPresentationReady,
        layoutGateTimedOut,
        homeStartupTimedOut
    ) {
        // Track that addons are known (even if isLoading flipped too fast to catch).
        if (uiState.installedAddonsCount > 0) {
            catalogLoadingStarted = true
        }
        // Do not expose an intermediate Home where Videos is the only visible
        // row.  Continue Watching and Videos originate from independent data
        // sources; releasing before the first CW snapshot made the UI jump
        // Videos -> Continue Watching and reorder its cards in front of the user.
        if (!homeStableGateReleased &&
            (uiState.layoutPreferencesReady || layoutGateTimedOut || homeStartupTimedOut) &&
            (modernPresentationReady || homeStartupTimedOut) &&
            (initialCwResolved || homeStartupTimedOut) &&
            (hasAnyContent || !uiState.isLoading || homeStartupTimedOut)
        ) {
            Log.d("HomeGate", "RELEASE: timeout=$homeStartupTimedOut anyContent=$hasAnyContent catalogs=$hasCatalogContent cwResolved=$initialCwResolved cwItems=${uiState.continueWatchingItems.size} addons=${uiState.installedAddonsCount}")
            homeStableGateReleased = true
            // The first frame is the useful one: do not keep a loader for an
            // extra frame merely to animate Home in.
            showHomeContentWithAnimation = true
        }
    }

    // Do not compete with first-frame rendering or the initial focus hand-off.
    // Once Home is visible, the ViewModel schedules a delayed stale-only IPTV
    // check. The worker itself processes providers one after the other.
    LaunchedEffect(homeStableGateReleased) {
        if (homeStableGateReleased) {
            viewModel.notifyHomeRendered(loadFreeboxVideos = !favoritesOnly)
            viewModel.scheduleDeferredIptvStaleCheck()
        }
    }

    // A Freebox refresh is a post-render operation.  Starting it while the
    // profile transition is still resolving competes with Continue Watching
    // and creates the visible Videos -> Continue Watching sequence.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, homeStableGateReleased, favoritesOnly) {
        val observer = LifecycleEventObserver { _, event ->
            if (homeStableGateReleased && !favoritesOnly && event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshFreeboxVideosAfterHomeRendered()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Freebox does not push filesystem changes to the app. Poll the small
    // Videos directory while Home is actually resumed so a new recording is
    // visible without forcing the user to leave and re-enter the app.
    LaunchedEffect(lifecycleOwner, homeStableGateReleased, favoritesOnly) {
        if (homeStableGateReleased && !favoritesOnly) {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.refreshFreeboxVideosAfterHomeRendered()
                }
            }
        }
    }

    val posterCardStyle = remember(
        uiState.posterCardWidthDp,
        uiState.posterCardCornerRadiusDp
    ) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val noAddonsError = stringResource(R.string.home_error_no_addons)
    val noCatalogAddonsError = stringResource(R.string.home_error_no_catalog_addons)
    val blockingHomeError = uiState.error?.takeUnless { error ->
        error == noAddonsError || error == noCatalogAddonsError
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            !uiState.layoutPreferencesReady && !layoutGateTimedOut && !homeStartupTimedOut -> {
                HomeStartupLoader()
            }

            blockingHomeError != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = blockingHomeError,
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            homeStableGateReleased && !hasAnyContent -> {
                HomeBlankState()
            }

            uiState.isLoading && !hasAnyContent && !canRenderHomeWithoutCatalogAddons -> {
                if (!homeStableGateReleased) {
                    HomeStartupLoader()
                } else {
                    HomeBlankState()
                }
            }

            !uiState.isLoading && !hasAnyContent && !canRenderHomeWithoutCatalogAddons -> {
                // Don't show "no catalogs" until the stable gate has released â€”
                // addons may still be loading from remote after a cache clear.
                if (!homeStableGateReleased) {
                    HomeStartupLoader()
                } else {
                    HomeBlankState()
                }
            }

            else -> {
                // On first launch, wait for stable content before revealing home.
                // Once released, never go back to loading (homeStableGateReleased is rememberSaveable).
                if (!homeStableGateReleased) {
                    HomeStartupLoader()
                } else if (!modernPresentationReady && !homeStartupTimedOut) {
                    HomeBlankState()
                } else {
                    // The startup gate already guarantees a stable first Home
                    // frame.  A second frame and a 320 ms enter animation only
                    // make profile selection feel slower on Android TV.
                    if (!showHomeContentWithAnimation) {
                        HomeStartupLoader()
                    }
                    CompositionLocalProvider(
                        LocalFavoriteInProgressContentIds provides favoriteInProgressContentIds
                    ) {
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = EnterTransition.None
                    ) {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeRoute(
                                viewModel = viewModel,
                                freeboxViewModel = freeboxViewModel,
                                uiState = uiState,
                                continueWatchingPortraitMode =
                                    layoutUiState.continueWatchingThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                videoPortraitMode =
                                    layoutUiState.videoThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                movieFavoritesPortraitMode =
                                    layoutUiState.movieFavoritesThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                seriesFavoritesPortraitMode =
                                    layoutUiState.seriesFavoritesThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                // Classic was the only home layout not to
                                // receive the saved thumbnail-size setting.
                                // It therefore used its default while Videos
                                // and Favorites were rendered from other
                                // dimensions.
                                continueWatchingThumbnailSize = uiState.continueWatchingThumbnailSize,
                                mediaRowCardSize = layoutUiState.homeMediaRowCardSize,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingDetails = onContinueWatchingDetailsStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                onSearchContinueWatchingPoster = onSearchContinueWatchingPosterEffective,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress,
                                onDeleteFreeboxProgress = deleteFreeboxProgressFromHome,
                                onDeleteFreeboxVideo = deleteFreeboxVideoFromHome,
                                onNavigateToFreebox = onNavigateToFreebox,
                            )

                            HomeLayout.GRID -> GridHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingDetails = onContinueWatchingDetailsStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                onSearchContinueWatchingPoster = onSearchContinueWatchingPosterEffective,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress,
                                onDeleteFreeboxProgress = deleteFreeboxProgressFromHome,
                                onDeleteFreeboxVideo = deleteFreeboxVideoFromHome,
                                continueWatchingPortraitMode =
                                    layoutUiState.continueWatchingThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                videoPortraitMode =
                                    layoutUiState.videoThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                movieFavoritesPortraitMode =
                                    layoutUiState.movieFavoritesThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                seriesFavoritesPortraitMode =
                                    layoutUiState.seriesFavoritesThumbnailOrientation ==
                                        ThumbnailOrientation.PORTRAIT,
                                continueWatchingThumbnailSize = com.nuvio.tv.domain.model.ThumbnailSize.fromName(uiState.continueWatchingThumbnailSize.name),
                                mediaRowCardSize = layoutUiState.homeMediaRowCardSize,
                                onNavigateToFreebox = onNavigateToFreebox,
                            )
                              HomeLayout.MODERN -> ModernHomeRoute(
                                  viewModel = viewModel,
                                  uiState = uiState,
                                  backdropOverrides = backdropOverrides,
                                  onNavigateToDetail = onNavigateToDetailStable,
                                  onContinueWatchingClick = onContinueWatchingClickStable,
                                  onContinueWatchingDetails = onContinueWatchingDetailsStable,
                                  onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                  onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                  showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                  onSearchContinueWatchingPoster = onSearchContinueWatchingPosterEffective,
                                  onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                  isCatalogItemWatched = isCatalogItemWatched,
                                  onCatalogItemLongPress = onCatalogItemLongPress,
                                  onDeleteFreeboxProgress = deleteFreeboxProgressFromHome,
                                  onDeleteFreeboxVideo = deleteFreeboxVideoFromHome,
                                  continueWatchingPortraitMode =
                                      layoutUiState.continueWatchingThumbnailOrientation ==
                                          ThumbnailOrientation.PORTRAIT,
                                  // Modern media rows have one shared landscape
                                  // geometry. The legacy per-row portrait
                                  // toggles must not alter Videos, Films or
                                  // Séries in this layout.
                                  videoPortraitMode = false,
                                  movieFavoritesPortraitMode = false,
                                  seriesFavoritesPortraitMode = false,
                                  continueWatchingThumbnailSize = uiState.continueWatchingThumbnailSize,
                                  carouselCardSize = layoutUiState.homeCarouselCardSize,
                                  mediaRowCardSize = layoutUiState.homeMediaRowCardSize,
                                  onNavigateToFreebox = onNavigateToFreebox,
                              )
                        }
                    }
                    }
                }
            }
        }

        val startupAuthNotice = uiState.startupAuthNotice
        if (startupAuthNotice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(
                        color = Color(0xFF5A1C1C),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when (startupAuthNotice) {
                        StartupAuthNotice.NUVIO -> stringResource(R.string.auth_notice_nuvio_logged_out)
                        StartupAuthNotice.TRAKT -> stringResource(R.string.auth_notice_trakt_logged_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary
                )
            }
        }
    }

    val selectedPoster = posterOptionsTarget
    if (selectedPoster != null) {
        val item = selectedPoster.item
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        // Catalog rows can arrive with a provider suffix or an upper-case
        // discriminator after a refresh. Keep poster actions available for
        // every IPTV movie/series preview, not only the exact rawType string.
        val normalizedRawType = item.rawType.trim().lowercase()
        val isIptvContent = normalizedRawType.startsWith("iptv_movie") ||
            normalizedRawType.startsWith("iptv_series")
        val isIptvFavorite = isIptvContent && item.isFavorite
        val isMovie = item.apiType.equals("movie", ignoreCase = true) ||
            normalizedRawType.startsWith("iptv_movie")
        val isSeries = item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true) ||
            normalizedRawType.startsWith("iptv_series")
        HomePosterOptionsDialog(
            title = item.name,
            isInLibrary = uiState.posterLibraryMembership[statusKey] == true,
            isLibraryPending = statusKey in uiState.posterLibraryPending,
            showManageLists = uiState.librarySourceMode == LibrarySourceMode.TRAKT,
            isMovie = isMovie,
            isSeries = isSeries,
            isWatched = movieWatchedStatus[statusKey] == true,
            isWatchedPending = statusKey in uiState.movieWatchedPending,
            isIptvContent = isIptvContent,
            isIptvFavorite = isIptvFavorite,
            onDismiss = { posterOptionsTarget = null },
            onDetails = {
                onNavigateToDetail(item.id, item.apiType, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onWatchTrailer = if (isIptvContent && (isMovie || isSeries)) {
                {
                    manualTrailerTarget = selectedPoster
                    posterOptionsTarget = null
                }
            } else null,
            onRemoveIptvFavorite = {
                viewModel.removeIptvFavorite(item)
                posterOptionsTarget = null
            },
            onAddIptvFavorite = {
                viewModel.addIptvFavorite(item)
                posterOptionsTarget = null
            },
            onToggleLibrary = {
                if (uiState.librarySourceMode == LibrarySourceMode.TRAKT) {
                    viewModel.openPosterListPicker(item, selectedPoster.addonBaseUrl)
                } else {
                    viewModel.togglePosterLibrary(item, selectedPoster.addonBaseUrl)
                }
                posterOptionsTarget = null
            },
            onToggleWatched = {
                if (isMovie) {
                    viewModel.togglePosterMovieWatched(item)
                } else {
                    viewModel.togglePosterSeriesWatched(item)
                }
                posterOptionsTarget = null
            },
            onSearchPoster = if (isIptvContent) {
                {
                    iptvCatalogPosterPickerTarget = selectedPoster
                    posterOptionsTarget = null
                }
            } else {
                null
            }
        )
    }

    if (uiState.homeLayout == HomeLayout.MODERN && showContinueWatchingFreeboxPosterPicker) {
        FreeboxPosterPickerDialog(
            state = freeboxPosterPickerState,
            onSelect = freeboxPosterPickerViewModel::select,
            onDismiss = {
                showContinueWatchingFreeboxPosterPicker = false
                freeboxPosterPickerViewModel.dismiss()
            }
        )
    }

    val trailerTarget = manualTrailerTarget
    if (trailerTarget != null) {
        val trailerItem = trailerTarget.item
        LaunchedEffect(trailerItem.id) {
            viewModel.requestTrailerPreview(trailerItem)
        }
        NuvioDialog(
            onDismiss = { manualTrailerTarget = null },
            title = trailerItem.name,
            subtitle = stringResource(R.string.hero_play_trailer),
            width = 900.dp
        ) {
            val trailerUrl = viewModel.trailerPreviewUrls[trailerItem.id]
            if (trailerUrl.isNullOrBlank()) {
                Text(stringResource(R.string.detail_trailer_loading), color = NuvioColors.TextSecondary)
            } else {
                TrailerPlayer(
                    trailerUrl = trailerUrl,
                    trailerAudioUrl = viewModel.trailerPreviewAudioUrls[trailerItem.id],
                    isPlaying = true,
                    onEnded = { manualTrailerTarget = null },
                    modifier = Modifier.fillMaxWidth().height(420.dp)
                )
            }
        }
    }

    if (uiState.showPosterListPicker) {
        HomeLibraryListPickerDialog(
            title = uiState.posterListPickerTitle ?: stringResource(R.string.detail_lists_fallback),
            tabs = uiState.libraryListTabs,
            membership = uiState.posterListPickerMembership,
            isPending = uiState.posterListPickerPending,
            error = uiState.posterListPickerError,
            onToggle = { key -> viewModel.togglePosterListPickerMembership(key) },
            onSave = { viewModel.savePosterListPickerMembership() },
            onDismiss = { viewModel.dismissPosterListPicker() }
        )
    }

    val selectedIptvPosterItem = iptvPosterPickerItem
    val selectedIptvCatalogPosterTarget = iptvCatalogPosterPickerTarget
    if (selectedIptvPosterItem != null || selectedIptvCatalogPosterTarget != null) {
        val dialogTitle = selectedIptvPosterItem?.progress?.name
            ?: selectedIptvCatalogPosterTarget?.item?.name
            ?: ""
        val dialogContentId = selectedIptvPosterItem?.progress?.contentId
            ?: selectedIptvCatalogPosterTarget?.item?.id
            ?: ""
        val dialogPoster = posterOverrides[dialogContentId]
            ?: selectedIptvPosterItem?.progress?.poster
            ?: selectedIptvCatalogPosterTarget?.item?.poster
        val dialogBackdrop = backdropOverrides[dialogContentId]
            ?: selectedIptvPosterItem?.progress?.backdrop
            ?: selectedIptvCatalogPosterTarget?.item?.background
            ?: selectedIptvCatalogPosterTarget?.item?.landscapePoster
        FreeboxPosterPickerDialog(
            state = FreeboxPosterPickerState(
                entry = FreeboxFileEntry(
                    name = dialogTitle,
                    path = dialogContentId,
                    encodedPath = null,
                    isDirectory = false,
                    size = null,
                    durationMs = selectedIptvPosterItem?.progress?.duration
                ),
                posters = iptvPosterCandidates,
                currentPosterUrl = dialogPoster,
                currentBackdropUrl = dialogBackdrop,
                isLoading = iptvPosterPickerLoading,
                isSaving = false,
                saveFailed = iptvPosterPickerSaveFailed
            ),
            onSelect = { poster ->
                iptvPosterPickerSaveFailed = false
                if (selectedIptvPosterItem != null) {
                    pendingIptvContinueWatchingArtwork = selectedIptvPosterItem to poster
                    iptvPosterPickerItem = null
                } else if (selectedIptvCatalogPosterTarget != null) {
                    pendingIptvCatalogArtwork = selectedIptvCatalogPosterTarget to poster
                    iptvCatalogPosterPickerTarget = null
                }
            },
            onDismiss = {
                iptvPosterPickerItem = null
                iptvCatalogPosterPickerTarget = null
            }
        )
    }
}

private fun WatchProgress.isIptvPosterEditable(): Boolean =
    contentId.startsWith("iptv_movie:", ignoreCase = true) ||
        contentId.startsWith("iptv_movie_stream:", ignoreCase = true) ||
        contentId.startsWith("iptv_series:", ignoreCase = true) ||
        contentId.startsWith("iptv_series_remote:", ignoreCase = true) ||
        contentType.equals("iptv_movie", ignoreCase = true) ||
        contentType.equals("iptv_series", ignoreCase = true)

private fun cleanHomePosterSearchQuery(rawTitle: String): String {
    return rawTitle
        .replace(Regex("""^\s*\d+h\s*\d{1,2}(?:m)?\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^\s*\d+\s*min\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^\]]*]"""), " ")
        .replace(
            Regex(
                """\b(?:4k|uhd|hdr10\+?|hdr|dv|dolby\s*vision|hevc|x265|x264|h\.?264|h\.?265|multi|vostfr|vf|truefrench|french|web-?dl|bluray|brrip|webrip|hdtv|aac|ac3|eac3|atmos|dts)\b""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { rawTitle.trim() }
}

@Composable
private fun ClassicHomeRoute(
    viewModel: HomeViewModel,
    freeboxViewModel: FreeboxBrowserViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    onSearchContinueWatchingPoster: ((ContinueWatchingItem) -> Unit)? = null,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onDeleteFreeboxProgress: ((ContinueWatchingItem) -> Unit)? = null,
    onDeleteFreeboxVideo: (FreeboxFileEntry) -> Unit = {},
    onNavigateToFreebox: (String, String?, String?, com.nuvio.tv.core.tmdb.FreeboxVideoMeta?) -> Unit = { _, _, _, _ -> },
    continueWatchingPortraitMode: Boolean = false,
    videoPortraitMode: Boolean = false,
    movieFavoritesPortraitMode: Boolean = true,
    seriesFavoritesPortraitMode: Boolean = true,
    continueWatchingThumbnailSize: com.nuvio.tv.domain.model.ThumbnailSize = com.nuvio.tv.domain.model.ThumbnailSize.DEFAULT,
    mediaRowCardSize: com.nuvio.tv.domain.model.HomeCardSize = com.nuvio.tv.domain.model.HomeCardSize.DEFAULT
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    ClassicHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState,
        scrollToTopTrigger = scrollToTopTrigger,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingDetails = onContinueWatchingDetails,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        onSearchContinueWatchingPoster = onSearchContinueWatchingPoster,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        onRemoveIptvFavorite = viewModel::removeFavoriteForContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = { item ->
            viewModel.requestTrailerPreview(item)
        },
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = { vi, vo, rk, ikm, m, ri, ii ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ri, ii)
        },
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        },
        continueWatchingPortraitMode = continueWatchingPortraitMode,
        videoPortraitMode = videoPortraitMode,
        movieFavoritesPortraitMode = movieFavoritesPortraitMode,
        seriesFavoritesPortraitMode = seriesFavoritesPortraitMode,
        continueWatchingThumbnailSize = continueWatchingThumbnailSize,
        mediaRowCardSize = mediaRowCardSize,
        onRenameFreeboxProgress = { item, newName -> viewModel.renameFreeboxContinueWatching(item, newName) },
        onMarkFreeboxUnwatched = { item -> viewModel.markFreeboxAsUnwatched(item) },
        onRenameFreeboxVideo = { entry, newName -> viewModel.renameFreeboxVideo(entry, newName) },
        onDeleteFreeboxVideo = onDeleteFreeboxVideo,
        onNavigateToFreebox = onNavigateToFreebox,
    )
}

@Composable
private fun GridHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onSearchContinueWatchingPoster: ((ContinueWatchingItem) -> Unit)? = null,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onDeleteFreeboxProgress: ((ContinueWatchingItem) -> Unit)? = null,
    onDeleteFreeboxVideo: (FreeboxFileEntry) -> Unit = {},
    onNavigateToFreebox: (String, String?, String?, com.nuvio.tv.core.tmdb.FreeboxVideoMeta?) -> Unit = { _, _, _, _ -> },
    continueWatchingThumbnailSize: com.nuvio.tv.domain.model.ThumbnailSize = com.nuvio.tv.domain.model.ThumbnailSize.DEFAULT,
    continueWatchingPortraitMode: Boolean = false,
    videoPortraitMode: Boolean = false,
    movieFavoritesPortraitMode: Boolean = true,
    seriesFavoritesPortraitMode: Boolean = true,
    mediaRowCardSize: com.nuvio.tv.domain.model.HomeCardSize = com.nuvio.tv.domain.model.HomeCardSize.DEFAULT
) {
    val gridFocusState by viewModel.gridFocusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState,
        scrollToTopTrigger = scrollToTopTrigger,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingDetails = onContinueWatchingDetails,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onSearchContinueWatchingPoster = onSearchContinueWatchingPoster,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = remember(viewModel) {
            { contentId, season, episode, isNextUp ->
                viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
            }
        },
        onRemoveIptvFavorite = viewModel::removeFavoriteForContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
            onDeleteFromFreebox = onDeleteFreeboxProgress,
            continueWatchingThumbnailSize = continueWatchingThumbnailSize,
            mediaRowCardSize = mediaRowCardSize,
            continueWatchingPortraitMode = continueWatchingPortraitMode,
            videoPortraitMode = videoPortraitMode,
        movieFavoritesPortraitMode = movieFavoritesPortraitMode,
        seriesFavoritesPortraitMode = seriesFavoritesPortraitMode,
            onItemFocus = remember(viewModel) {
            { item ->
                viewModel.onItemFocus(item)
            }
        },
        onSaveGridFocusState = remember(viewModel) {
            { vi, vo, key ->
                viewModel.saveGridFocusState(vi, vo, focusedItemKey = key)
            }
        },
        onRenameFreeboxProgress = { item, newName -> viewModel.renameFreeboxContinueWatching(item, newName) },
        onMarkFreeboxUnwatched = { item -> viewModel.markFreeboxAsUnwatched(item) },
        onRenameFreeboxVideo = { entry, newName -> viewModel.renameFreeboxVideo(entry, newName) },
        onDeleteFreeboxVideo = onDeleteFreeboxVideo,
        onNavigateToFreebox = onNavigateToFreebox,
    )
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    backdropOverrides: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onSearchContinueWatchingPoster: ((ContinueWatchingItem) -> Unit)? = null,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onDeleteFreeboxProgress: ((ContinueWatchingItem) -> Unit)? = null,
    onDeleteFreeboxVideo: (FreeboxFileEntry) -> Unit = {},
    onNavigateToFreebox: (String, String?, String?, com.nuvio.tv.core.tmdb.FreeboxVideoMeta?) -> Unit = { _, _, _, _ -> },
    continueWatchingPortraitMode: Boolean = false,
    videoPortraitMode: Boolean = false,
    movieFavoritesPortraitMode: Boolean = true,
    seriesFavoritesPortraitMode: Boolean = true,
    continueWatchingThumbnailSize: com.nuvio.tv.domain.model.ThumbnailSize = com.nuvio.tv.domain.model.ThumbnailSize.DEFAULT,
    carouselCardSize: com.nuvio.tv.domain.model.HomeCardSize = com.nuvio.tv.domain.model.HomeCardSize.DEFAULT,
    mediaRowCardSize: com.nuvio.tv.domain.model.HomeCardSize = com.nuvio.tv.domain.model.HomeCardSize.DEFAULT
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val initialCwResolved by viewModel.initialCwResolved.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val enrichingItemId by viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val lastEnrichedPreview by viewModel.lastEnrichedPreview.collectAsStateWithLifecycle()
    val enrichedPreviews by viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds by viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ri: Int, ii: Int ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ri, ii)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.preloadAdjacentItem(item)
        }
    }
    ModernHomeContent(
        uiState = uiState,
        focusState = focusState,
        iptvBackdropOverrides = backdropOverrides,
        continueWatchingReady = initialCwResolved,
        scrollToTopTrigger = scrollToTopTrigger,
        enrichingItemId = enrichingItemId,
        lastEnrichedPreview = lastEnrichedPreview,
        enrichedPreviews = enrichedPreviews,
        failedEnrichmentIds = failedEnrichmentIds,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingDetails = onContinueWatchingDetails,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onSearchContinueWatchingPoster = onSearchContinueWatchingPoster,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        onRemoveIptvFavorite = viewModel::removeFavoriteForContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onItemFocus = remember(viewModel) {
            { item -> viewModel.onItemFocus(item) }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        continueWatchingPortraitMode = continueWatchingPortraitMode,
        videoPortraitMode = videoPortraitMode,
        movieFavoritesPortraitMode = movieFavoritesPortraitMode,
        seriesFavoritesPortraitMode = seriesFavoritesPortraitMode,
        continueWatchingThumbnailSize = continueWatchingThumbnailSize,
        carouselCardSize = carouselCardSize,
        mediaRowCardSize = mediaRowCardSize,
        onDeleteFreeboxProgress = onDeleteFreeboxProgress,
        onRenameFreeboxProgress = { item, newName -> viewModel.renameFreeboxContinueWatching(item, newName) },
        onMarkFreeboxUnwatched = { item -> viewModel.markFreeboxAsUnwatched(item) },
        onRenameFreeboxVideo = { entry, newName -> viewModel.renameFreeboxVideo(entry, newName) },
        onDeleteFreeboxVideo = onDeleteFreeboxVideo,
        onNavigateToFreebox = onNavigateToFreebox,
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    showManageLists: Boolean,
    isMovie: Boolean,
    isSeries: Boolean = false,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    isIptvContent: Boolean,
    isIptvFavorite: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onWatchTrailer: (() -> Unit)? = null,
    onRemoveIptvFavorite: () -> Unit,
    onAddIptvFavorite: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    onSearchPoster: (() -> Unit)? = null
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        onWatchTrailer?.let { watchTrailer ->
            Button(
                onClick = watchTrailer,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.hero_play_trailer))
            }
        }

        if (isIptvContent) {
            Button(
                onClick = if (isIptvFavorite) onRemoveIptvFavorite else onAddIptvFavorite,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(
                    stringResource(
                        if (isIptvFavorite) {
                            R.string.home_remove_from_favorites
                        } else {
                            R.string.home_add_to_favorites
                        }
                    )
                )
            }
            onSearchPoster?.let { searchPoster ->
                Button(
                    onClick = searchPoster,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.freebox_choose_poster))
                }
            }
        } else {
            Button(
                onClick = onToggleLibrary,
                enabled = !isLibraryPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(
                    if (showManageLists) {
                        stringResource(R.string.library_manage_lists)
                    } else {
                        if (isInLibrary) {
                            stringResource(R.string.hero_remove_from_library)
                        } else {
                            stringResource(R.string.hero_add_to_library)
                        }
                    }
                )
            }

            }

        if (!isIptvContent && (isMovie || isSeries)) {
            Button(
                onClick = onToggleWatched,
                enabled = !isWatchedPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(
                    if (isWatched) {
                        stringResource(R.string.hero_mark_unwatched)
                    } else {
                        stringResource(R.string.hero_mark_watched)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeLibraryListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "\u2713 ${tab.title}" else tab.title
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = NuvioColors.Border, thickness = 1.dp)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}
