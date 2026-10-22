package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import com.nuvio.tv.LocaleCache
import com.jojotv.home.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.ui.util.asStable
import java.util.Locale
import kotlinx.coroutines.withContext

@Immutable
internal data class ModernHomePresentationInput(
    val homeRows: List<HomeRow>,
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showFullReleaseDate: Boolean,
    val localeTag: String
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
    val visibleHomeRows = resolveVisibleHomeRows(input)
    val localizedContext = getLocalizedContext(context)
    val strContinueWatching = localizedContext.getString(R.string.continue_watching)
    val strAirsDate = localizedContext.getString(R.string.cw_airs_date)
    val strUpcoming = localizedContext.getString(R.string.cw_upcoming)
    val strTypeMovie = localizedContext.getString(R.string.type_movie)
    val strTypeSeries = localizedContext.getString(R.string.type_series)

    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>()
        val activeCollectionKeys = LinkedHashSet<String>()
        val catalogRowLimit = maxCatalogRows?.coerceAtLeast(0)
        var renderedCatalogRows = 0

        if (input.continueWatchingItems.isNotEmpty()) {
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == strContinueWatching &&
                    cache.continueWatchingAirsDateTemplate == strAirsDate &&
                    cache.continueWatchingUpcomingLabel == strUpcoming &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters
            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HeroCarouselRow(
                    key = MODERN_CONTINUE_WATCHING_ROW_KEY,
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = localizedContext
                        )
                    }.asStable()
                )
            }
            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = strContinueWatching
            cache.continueWatchingAirsDateTemplate = strAirsDate
            cache.continueWatchingUpcomingLabel = strUpcoming
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingRow = null
        }

        visibleHomeRows.forEachIndexed { index, homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val row = homeRow.row.withSortedIptvMovieFavorites()
                    val rowUsesLandscapeArtwork = row.usesLandscapeArtwork(input.useLandscapePosters)
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val rowKey = catalogRowKey(row)
                    activeCatalogKeys += rowKey
                    val cached = cache.catalogRows[rowKey]
                    val currentLocaleTag = LocaleCache.localeTag
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == row &&
                            cached.useLandscapePosters == rowUsesLandscapeArtwork &&
                            cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                            cached.localeTag == currentLocaleTag

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                        val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                        HeroCarouselRow(
                            key = rowKey,
                            title = catalogRowTitle(
                                row = row,
                                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                                strTypeMovie = strTypeMovie,
                                strTypeSeries = strTypeSeries
                            ),
                            globalRowIndex = index,
                            catalogId = row.catalogId,
                            addonId = row.addonId,
                            apiType = row.apiType,
                            supportsSkip = row.supportsSkip,
                            hasMore = row.hasMore,
                            isLoading = row.isLoading,
                            items = row.items.map { item ->
                                val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                                rowItemOccurrenceCounts[item.id] = occurrence + 1
                                val cacheKey = "${item.id}_$occurrence"
                                val cachedItem = rowItemCache[cacheKey]
                                if (cachedItem != null &&
                                    cachedItem.source == item &&
                                    cachedItem.useLandscapePosters == rowUsesLandscapeArtwork &&
                                    cachedItem.showFullReleaseDate == input.showFullReleaseDate
                                ) {
                                    cachedItem.carouselItem
                                } else {
                                    val built = buildCatalogItem(
                                        item = item,
                                        row = row,
                                        useLandscapePosters = rowUsesLandscapeArtwork,
                                        occurrence = occurrence,
                                        strTypeMovie = strTypeMovie,
                                        strTypeSeries = strTypeSeries,
                                        showFullReleaseDate = input.showFullReleaseDate,
                                        previousCachedItem = cachedItem?.carouselItem
                                    )
                                    rowItemCache[cacheKey] = CachedCarouselItem(
                                        source = item,
                                        useLandscapePosters = rowUsesLandscapeArtwork,
                                        showFullReleaseDate = input.showFullReleaseDate,
                                        carouselItem = built
                                    )
                                    built
                                }
                            }.asStable()
                        )
                    }

                    cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                        source = row,
                        useLandscapePosters = rowUsesLandscapeArtwork,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                        localeTag = currentLocaleTag,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.CollectionRow -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    val collection = homeRow.collection
                    val rowKey = collectionRowKey(collection)
                    activeCollectionKeys += rowKey
                    val cached = cache.collectionRows[rowKey]
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == collection

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val occurrenceCounts = mutableMapOf<String, Int>()
                        HeroCarouselRow(
                            key = rowKey,
                            title = collection.title,
                            globalRowIndex = index,
                            items = collection.folders.map { folder ->
                                val occurrence = occurrenceCounts.getOrDefault(folder.id, 0)
                                occurrenceCounts[folder.id] = occurrence + 1
                                buildCollectionFolderItem(
                                    collection = collection,
                                    folder = folder,
                                    occurrence = occurrence
                                )
                            }.asStable()
                        )
                    }

                    cache.collectionRows[rowKey] = ModernCollectionRowBuildCacheEntry(
                        source = collection,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.PlaceholderCatalog -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val fakeItemCount = 8
                    val fakeItems = (0 until fakeItemCount).map { i ->
                        val fakeId = "__placeholder_${homeRow.catalogKey}_$i"
                        ModernCarouselItem(
                            key = "${homeRow.catalogKey}_$i",
                            title = "",
                            subtitle = null,
                            // Dummy URL triggers shimmer instead of MonochromePosterPlaceholder
                            imageUrl = "placeholder://empty",
                            heroPreview = HeroPreview(
                                title = "", logo = null, description = null,
                                contentTypeText = null, yearText = null, imdbText = null,
                                genres = com.nuvio.tv.ui.util.StableList(emptyList()), poster = null, backdrop = null,
                                imageUrl = "placeholder://empty"
                            ),
                            payload = ModernPayload.Catalog(
                                focusKey = "placeholder_${homeRow.catalogKey}_$i",
                                itemId = fakeId,
                                itemType = homeRow.apiType,
                                addonBaseUrl = homeRow.addonBaseUrl,
                                trailerTitle = "",
                                trailerReleaseInfo = null,
                                trailerApiType = homeRow.apiType
                            )
                        )
                    }.asStable()
                    val placeholderTitle = if (input.showCatalogTypeSuffix) {
                        homeRow.displayTitle
                    } else {
                        homeRow.catalogName.replaceFirstChar { it.uppercase() }
                    }
                    val placeholderRow = HeroCarouselRow(
                        key = homeRow.catalogKey,
                        title = placeholderTitle,
                        globalRowIndex = index,
                        catalogId = homeRow.catalogId,
                        addonId = homeRow.addonId,
                        apiType = homeRow.apiType,
                        items = fakeItems,
                        isLoading = true
                    )
                    add(placeholderRow)
                }
            }
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        cache.collectionRows.keys.retainAll(activeCollectionKeys)
    }

    // Continue Watching is built from the playback snapshot, which is a
    // deliberately small record.  Reuse the already-resolved catalogue card
    // for its descriptive fields before exposing the row to the Hero.  This
    // keeps playback state (progress, episode and artwork) local to CW while
    // making the fiche identical to the Films/Séries row.
    val resolvedRows = rows.withResolvedContinueWatchingMetadata()
    val lookups = buildCarouselRowLookups(resolvedRows)
    return ModernHomePresentationState(
        rows = resolvedRows.asStable(),
        lookups = lookups
    )
}

private fun List<HeroCarouselRow>.withResolvedContinueWatchingMetadata(): List<HeroCarouselRow> {
    val catalogItems = flatMap { row ->
        row.items.list.filter { it.payload is ModernPayload.Catalog }
    }
    if (catalogItems.isEmpty()) return this

    val catalogById = catalogItems
        .mapNotNull { item ->
            item.metaPreview?.let { preview -> preview.id.lowercase() to preview }
        }
        .toMap()
    val catalogByPlaybackId = catalogItems
        .mapNotNull { item ->
            val preview = item.metaPreview ?: return@mapNotNull null
            preview.playbackProgressId?.takeIf { it.isNotBlank() }
                ?.let { it.lowercase() to preview }
        }
        .toMap(String.CASE_INSENSITIVE_ORDER)
    val catalogByTitle = catalogItems
        .mapNotNull { item ->
            val preview = item.metaPreview ?: return@mapNotNull null
            normalizedHeroTitle(preview.name).takeIf { it.isNotBlank() }?.let { it to preview }
        }
        .toMap()

    fun findCatalogPreview(item: ModernCarouselItem): com.nuvio.tv.domain.model.MetaPreview? {
        val payload = item.payload as? ModernPayload.ContinueWatching ?: return null
        val progress = when (val watched = payload.item) {
            is ContinueWatchingItem.InProgress -> watched.progress
            is ContinueWatchingItem.NextUp -> null
        }
        val ids = listOfNotNull(
            progress?.contentId,
            progress?.videoId,
            item.metaPreview?.id
        ).filter { it.isNotBlank() }
        return ids.firstNotNullOfOrNull { id ->
            catalogById[id.lowercase()] ?: catalogByPlaybackId[id.lowercase()]
        } ?: catalogByTitle[normalizedHeroTitle(item.title)]
    }

    return map { row ->
        if (row.key != MODERN_CONTINUE_WATCHING_ROW_KEY) return@map row
        row.copy(items = row.items.map { item ->
            val catalog = findCatalogPreview(item) ?: return@map item
            item.copy(
                heroPreview = item.heroPreview.copy(
                    logo = catalog.logo ?: item.heroPreview.logo,
                    description = catalog.description ?: item.heroPreview.description,
                    yearText = extractYearOrRange(catalog.releaseInfo)
                        ?: item.heroPreview.yearText,
                    runtimeText = formatHeroRuntime(catalog.runtime)
                        ?: item.heroPreview.runtimeText,
                    isSeries = item.heroPreview.isSeries || isSeriesType(catalog.apiType),
                    genres = catalog.genres.take(3).takeIf { it.isNotEmpty() }
                        ?.asStable() ?: item.heroPreview.genres,
                    cast = catalog.cast.takeIf { it.isNotEmpty() }?.asStable()
                        ?: item.heroPreview.cast,
                    director = catalog.director.takeIf { it.isNotEmpty() }?.asStable()
                        ?: item.heroPreview.director,
                    seasonCount = catalog.seasonCount ?: item.heroPreview.seasonCount
                ),
                metaPreview = item.metaPreview?.copy(
                    logo = catalog.logo ?: item.metaPreview.logo,
                    description = catalog.description ?: item.metaPreview.description,
                    releaseInfo = catalog.releaseInfo ?: item.metaPreview.releaseInfo,
                    genres = catalog.genres.ifEmpty { item.metaPreview.genres },
                    runtime = catalog.runtime ?: item.metaPreview.runtime,
                    seasonCount = catalog.seasonCount ?: item.metaPreview.seasonCount
                )
            )
        }.asStable())
    }
}

private fun normalizedHeroTitle(value: String): String = value
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), "")

private fun resolveVisibleHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    if (input.homeRows.isNotEmpty()) {
        val latestCatalogByKey = input.catalogRows.associateBy(::catalogRowKey)
        return input.homeRows.mapNotNull { homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val latest = latestCatalogByKey[catalogRowKey(homeRow.row)] ?: homeRow.row
                    latest.takeIf { it.items.isNotEmpty() }
                        ?.withSortedIptvMovieFavorites()
                        ?.let(HomeRow::Catalog)
                }
                is HomeRow.CollectionRow -> {
                    homeRow.collection.takeIf(Collection::hasVisibleFolders)?.let(HomeRow::CollectionRow)
                }
                is HomeRow.PlaceholderCatalog -> {
                    // Keep placeholder rows as-is — they'll be rendered as shimmer skeletons
                    homeRow
                }
            }
        }
    }

    return input.catalogRows
        .filter { it.items.isNotEmpty() }
        .map { it.withSortedIptvMovieFavorites() }
        .map(HomeRow::Catalog)
}

private fun CatalogRow.withSortedIptvMovieFavorites(): CatalogRow {
    if (addonId != HomeViewModel.IPTV_FAVORITES_ADDON_ID ||
        catalogId != HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID
    ) {
        return this
    }
    val sortedItems = items.sortedByHomeIptvMovieDuration()
    return if (sortedItems == items) this else copy(items = sortedItems)
}

private fun collectionRowKey(collection: Collection): String {
    return "collection_${collection.id}"
}

private fun getLocalizedContext(context: Context): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return context
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

private fun Collection.hasVisibleFolders(): Boolean {
    return folders.isNotEmpty()
}

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val firstHeroPreviewByRow = LinkedHashMap<String, HeroPreview>(carouselRows.size)
    val fallbackBackdropByRow = LinkedHashMap<String, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        row.items.list.firstOrNull()?.heroPreview?.let { firstHeroPreviewByRow[row.key] = it }
        row.items.list.firstNotNullOfOrNull { item ->
            item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
        }?.let { fallbackBackdropByRow[row.key] = it }
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.list.size)
        row.items.list.forEach { item ->
            itemKeys.add(item.key)
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey.asStable(),
        rowByKey = rowByKey.asStable(),
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex.asStable(),
        firstHeroPreviewByRow = firstHeroPreviewByRow.asStable(),
        fallbackBackdropByRow = fallbackBackdropByRow.asStable(),
        activeRowKeys = activeRowKeys.asStable(),
        activeItemKeysByRow = activeItemKeysByRow.asStable(),
        activeCatalogItemIds = activeCatalogItemIds.asStable()
    )
}
