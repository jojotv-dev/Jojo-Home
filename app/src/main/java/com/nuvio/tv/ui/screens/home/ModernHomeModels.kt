package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.computeAirDateBadgeText
import com.nuvio.tv.domain.model.MetaPreview
import com.jojotv.home.R
import com.nuvio.tv.data.freebox.formatFreeboxDurationCompact
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.freebox.freeboxTmdbSearchQuery
import com.nuvio.tv.ui.components.formatContinueWatchingProgressLabel
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableSet
import com.nuvio.tv.ui.util.asStable

internal val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
// Keep the hero text clear of the sidebar, but use all the remaining width for
// the synopsis.  Long descriptions need the extra room more than the backdrop.
internal const val MODERN_HERO_TEXT_WIDTH_FRACTION = 1f
internal const val MODERN_HERO_SYNOPSIS_MAX_LINES = 5
internal val MODERN_HERO_SYNOPSIS_HEIGHT = 112.dp
internal val MODERN_HERO_SYNOPSIS_FONT_REDUCTION = 2.sp
// The title is anchored from the top; rows use the measured content height below.
internal val MODERN_HERO_CONTENT_TOP_PADDING = 18.dp
internal val MODERN_HERO_CONTENT_TO_ROWS_GAP = 20.dp
internal const val MODERN_HERO_MEDIA_WIDTH_FRACTION = 0.72f
internal const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 450L
internal val MODERN_ROW_HEADER_FOCUS_INSET = 10.dp
internal const val MODERN_CONTINUE_WATCHING_ROW_KEY = "continue_watching"

internal fun modernHeroSynopsisMaxLines(): Int = MODERN_HERO_SYNOPSIS_MAX_LINES

internal fun modernHeroTextWidthFraction(): Float = MODERN_HERO_TEXT_WIDTH_FRACTION

internal fun modernHeroRowsViewportFraction(useLandscapePosters: Boolean): Float {
    val defaultFraction = if (useLandscapePosters) 0.49f else 0.52f
    return defaultFraction
}

internal fun shouldHideBaseHeroForEnrichment(
    isIptvItem: Boolean,
    enrichmentActive: Boolean,
    enrichedHeroAvailable: Boolean
): Boolean = !isIptvItem && enrichmentActive && !enrichedHeroAvailable

internal fun selectMostCompleteHeroDescription(
    baseDescription: String?,
    enrichedDescription: String?
): String? {
    val base = baseDescription?.trim()?.takeIf { it.isNotBlank() }
    val enriched = enrichedDescription?.trim()?.takeIf { it.isNotBlank() }
    if (base == null) return enriched
    if (enriched == null) return base

    val enrichedWithoutEllipsis = enriched
        .trimEnd('.', '…')
        .trimEnd()
    return if (enrichedWithoutEllipsis.isNotBlank() &&
        base.startsWith(enrichedWithoutEllipsis, ignoreCase = true) &&
        base.length > enriched.length
    ) {
        base
    } else {
        enriched
    }
}

/**
 * Returns only artwork explicitly stored as a landscape image for an enriched IPTV movie.
 *
 * [MetaPreview.backdropUrl] cannot be used here because it falls back to [MetaPreview.poster].
 * IPTV provider posters frequently contain quality/language banners (4K, VFQ, VOSTFR) that
 * are acceptable on a card but must never be enlarged as the full-screen Home background.
 */
internal fun resolveIptvMovieHeroBackdrop(item: MetaPreview?): String? =
    firstNonBlank(
        item?.background.takeIfTrustedIptvHeroBackdrop(),
        item?.landscapePoster.takeIfTrustedIptvHeroBackdrop()
    )

internal val MODERN_LANDSCAPE_LOGO_GRADIENT = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.58f to Color.Transparent,
        1.0f to Color.Black.copy(alpha = 0.75f)
    )
)

@Immutable
data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val isSeries: Boolean = false,
    val yearText: String?,
    val runtimeText: String? = null,
    val secondaryHighlightText: String? = null,
    val imdbText: String?,
    val ageRatingText: String? = null,
    val statusText: String? = null,
    val countryText: String? = null,
    val languageText: String? = null,
    val seasonCount: Int? = null,
    val cast: StableList<String> = StableList(),
    val director: StableList<String> = StableList(),
    val genres: StableList<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?,
    /** Snapshot of the backdrop URL captured before TMDB enrichment.
     *  Survives cache rebuilds so landscape cards keep their original art
     *  even after navigation away and back. */
    val frozenBackdropUrl: String? = null,
    /** Same idea for the logo URL. */
    val frozenLogoUrl: String? = null
)

@Immutable
sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
    data class CollectionFolder(
        val focusKey: String,
        val collectionId: String,
        val collectionTitle: String,
        val folderId: String,
        val posterShape: PosterShape,
        val focusGlowEnabled: Boolean,
        val focusGifEnabled: Boolean,
        val focusGifUrl: String?,
        val heroBackdropUrl: String?,
        val heroVideoUrl: String?,
        val titleLogoUrl: String?,
        val coverEmoji: String? = null
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val focusKey: String,
    val payload: ModernPayload
)

@Immutable
data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null
)

@Immutable
data class HeroCarouselRow(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: StableList<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

@Immutable
data class CarouselRowLookups(
    val rowIndexByKey: StableMap<String, Int>,
    val rowByKey: StableMap<String, HeroCarouselRow>,
    val rowKeyByGlobalRowIndex: StableMap<Int, String>,
    val firstHeroPreviewByRow: StableMap<String, HeroPreview>,
    val fallbackBackdropByRow: StableMap<String, String>,
    val activeRowKeys: StableSet<String>,
    val activeItemKeysByRow: StableMap<String, Set<String>>,
    val activeCatalogItemIds: StableSet<String>
)

@Immutable
data class ModernHomePresentationState(
    val rows: StableList<HeroCarouselRow> = StableList(),
    val lookups: CarouselRowLookups = CarouselRowLookups(
        rowIndexByKey = StableMap(),
        rowByKey = StableMap(),
        rowKeyByGlobalRowIndex = StableMap(),
        firstHeroPreviewByRow = StableMap(),
        fallbackBackdropByRow = StableMap(),
        activeRowKeys = StableSet(),
        activeItemKeysByRow = StableMap(),
        activeCatalogItemIds = StableSet()
    )
)

@Immutable
internal data class ModernHeroSceneState(
    val heroBackdrop: String?,
    val preview: HeroPreview?,
    val enrichmentActive: Boolean,
    val shouldPlayTrailer: Boolean,
    val trailerFirstFrameRendered: Boolean,
    val trailerUrl: String?,
    val trailerAudioUrl: String?,
    val trailerPlaybackKey: String?,
    val trailerMuted: Boolean,
    val fullScreenBackdrop: Boolean
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val localeTag: String,
    val mappedRow: HeroCarouselRow
)

internal data class ModernCollectionRowBuildCacheEntry(
    val source: Collection,
    val mappedRow: HeroCarouselRow
)
@Stable
class ModernCarouselRowBuildCache {
    var continueWatchingItems: List<ContinueWatchingItem> = emptyList()
    var continueWatchingTitle: String = ""
    var continueWatchingAirsDateTemplate: String = ""
    var continueWatchingUpcomingLabel: String = ""
    var continueWatchingUseLandscapePosters: Boolean = false
    var continueWatchingRow: HeroCarouselRow? = null
    internal val catalogRows = java.util.concurrent.ConcurrentHashMap<String, ModernCatalogRowBuildCacheEntry>()
    internal val collectionRows = java.util.concurrent.ConcurrentHashMap<String, ModernCollectionRowBuildCacheEntry>()
    // per-item cache: rowKey -> (itemId -> cached carousel item + source MetaPreview)
    internal val catalogItemCache = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, CachedCarouselItem>>()
}

internal data class CachedCarouselItem(
    val source: MetaPreview,
    val useLandscapePosters: Boolean,
    val showFullReleaseDate: Boolean,
    val carouselItem: ModernCarouselItem
)

@Immutable
internal data class ModernCatalogCardMetrics(
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp
)

internal fun ModernCarouselItem.catalogCardMetrics(
    useLandscapePosters: Boolean,
    portraitCardWidth: androidx.compose.ui.unit.Dp,
    portraitCardHeight: androidx.compose.ui.unit.Dp,
    landscapeCardWidth: androidx.compose.ui.unit.Dp,
    landscapeCardHeight: androidx.compose.ui.unit.Dp
): ModernCatalogCardMetrics {
    // Collection folders define their own tile shape — never override with
    // the global landscape-posters toggle.
    if (useLandscapePosters && payload !is ModernPayload.CollectionFolder) {
        return ModernCatalogCardMetrics(
            width = landscapeCardWidth,
            height = landscapeCardHeight
        )
    }

    val posterShape = when (payload) {
        is ModernPayload.Catalog -> metaPreview?.posterShape ?: PosterShape.POSTER
        is ModernPayload.CollectionFolder -> payload.posterShape
        is ModernPayload.ContinueWatching -> metaPreview?.posterShape
            ?: payload.item.preferredContinueWatchingPosterShape()
    }

    return when (posterShape) {
        PosterShape.LANDSCAPE -> ModernCatalogCardMetrics(
            width = landscapeCardWidth,
            height = landscapeCardHeight
        )

        PosterShape.SQUARE -> ModernCatalogCardMetrics(
            width = portraitCardHeight,
            height = portraitCardHeight
        )

        PosterShape.POSTER -> ModernCatalogCardMetrics(
            width = portraitCardWidth,
            height = portraitCardHeight
        )
    }
}

internal fun ModernCarouselItem.catalogCardRequestMetrics(
    useLandscapePosters: Boolean,
    portraitCardWidth: androidx.compose.ui.unit.Dp,
    portraitCardHeight: androidx.compose.ui.unit.Dp,
    landscapeCardWidth: androidx.compose.ui.unit.Dp,
    landscapeCardHeight: androidx.compose.ui.unit.Dp,
    expandEnabled: Boolean = false
): ModernCatalogCardMetrics {
    val base = catalogCardMetrics(
        useLandscapePosters = useLandscapePosters,
        portraitCardWidth = portraitCardWidth,
        portraitCardHeight = portraitCardHeight,
        landscapeCardWidth = landscapeCardWidth,
        landscapeCardHeight = landscapeCardHeight
    )
    if (!expandEnabled) return base

    val expandedWidth = if (useLandscapePosters && payload !is ModernPayload.CollectionFolder) {
        base.width
    } else {
        base.height * (16f / 9f)
    }
    return base.copy(width = maxOf(base.width, expandedWidth))
}


internal fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean,
    airsDateTemplate: String,
    upcomingLabel: String,
    context: android.content.Context
): ModernCarouselItem {
    val secondaryHighlightText = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val progress = item.progress
            if (progress.isFreeboxProgressForHome() || progress.isIptvProgressForHome()) {
                ""
            } else {
                formatContinueWatchingProgressLabel(
                    progress = progress,
                    resumeLabel = context.getString(R.string.cw_resume),
                    percentWatchedLabel = context.getString(R.string.cw_percent_watched),
                    hoursMinLeftLabel = context.getString(R.string.cw_hours_min_left),
                    minLeftLabel = context.getString(R.string.cw_min_left)
                )
            }
        }
        is ContinueWatchingItem.NextUp -> {
            if (item.info.isReleaseAlert) {
                if (item.info.isNewSeasonRelease) {
                    context.getString(R.string.cw_new_season)
                } else {
                    context.getString(R.string.cw_new_episode)
                }
            } else if (!item.info.hasAired) {
                computeAirDateBadgeText(context, item.info.released, item.info.airDateLabel)
                    ?: context.getString(R.string.cw_upcoming)
            } else {
                context.getString(R.string.cw_next_up)
            }
        }
    }.uppercase()

    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val isSeries = isSeriesType(item.progress.contentType)
            val s = item.progress.season
            val e = item.progress.episode
            val episodeCode = if (s != null && e != null) {
                context.getString(R.string.season_episode_format, s, e)
            } else {
                null
            }
            val episodeTitle = item.progress.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
            val episodeLabel = when {
                isSeries && episodeCode != null && episodeTitle != null -> "$episodeCode · $episodeTitle"
                isSeries && episodeCode != null -> episodeCode
                isSeries && episodeTitle != null -> episodeTitle
                item.progress.isFreeboxProgressForHome() || item.progress.isIptvProgressForHome() -> ""
                else -> item.progress.contentType.replaceFirstChar { ch -> ch.uppercase() }
            }
            HeroPreview(
                title = item.progress.name,
                logo = item.progress.logo,
                description = item.episodeDescription
                    ?: item.progress.episodeTitle?.localizeEpisodeTitle(context),
                contentTypeText = episodeLabel,
                isSeries = isSeries,
                yearText = extractYearOrRange(item.releaseInfo),
                runtimeText = if (item.progress.isFreeboxProgressForHome()) {
                    formatFreeboxDurationCompact(item.progress.duration)
                } else {
                    null
                },
                secondaryHighlightText = secondaryHighlightText.takeIf { it.isNotBlank() },
                imdbText = if (item.progress.isFreeboxProgressForHome()) {
                    null
                } else {
                    item.episodeImdbRating?.let { String.format("%.1f", it) }
                },
                cast = item.cast.asStable(),
                director = item.director.asStable(),
                genres = item.genres.asStable(),
                poster = item.progress.poster,
                backdrop = item.progress.backdrop,
                imageUrl = if (useLandscapePosters || item.progress.isSpecialProgressForHome()) {
                    firstNonBlank(item.progress.backdrop, item.progress.poster, item.progress.logo)
                } else {
                    firstNonBlank(item.progress.poster, item.progress.backdrop, item.progress.logo)
                }
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = context.getString(
                R.string.season_episode_format,
                item.info.season,
                item.info.episode
            )
            val episodeTitle = item.info.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            HeroPreview(
                title = item.info.name,
                logo = item.info.logo,
                description = item.info.episodeDescription
                    ?: item.info.episodeTitle?.localizeEpisodeTitle(context)
                    ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
                contentTypeText = episodeLabel,
                isSeries = true,
                yearText = extractYearOrRange(item.info.releaseInfo),
                secondaryHighlightText = secondaryHighlightText,
                imdbText = item.info.imdbRating?.let { String.format("%.1f", it) },
                genres = item.info.genres.asStable(),
                poster = item.info.poster,
                backdrop = item.info.backdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
                } else {
                    firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
                }
            )
        }
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(item.progress.backdrop, item.episodeThumbnail, item.progress.poster, item.progress.logo)
            } else {
                firstNonBlank(item.progress.backdrop, item.progress.poster, item.progress.logo)
            }
        } else {
            val preferSpecialBackdrop = item.progress.isSpecialProgressForHome()
            if (isSeriesType(item.progress.contentType)) {
                if (preferSpecialBackdrop) {
                    firstNonBlank(item.progress.backdrop, item.progress.poster, heroPreview.poster, item.progress.logo)
                } else {
                    firstNonBlank(heroPreview.poster, item.progress.poster, item.progress.backdrop, item.progress.logo)
                }
            } else {
                if (preferSpecialBackdrop) {
                    firstNonBlank(item.progress.backdrop, item.progress.poster, item.progress.logo)
                } else {
                    firstNonBlank(item.progress.poster, item.progress.backdrop, item.progress.logo)
                }
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(item.info.backdrop, item.info.thumbnail, item.info.poster)
            } else {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            }
        } else {
            firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.displayTitleForContinueWatching()
            is ContinueWatchingItem.NextUp -> item.info.name
        },
          subtitle = when (item) {
              is ContinueWatchingItem.InProgress -> {
                  if (item.progress.isFreeboxProgressForHome() || item.progress.isIptvProgressForHome()) null
                  else {
                      val ps = item.progress.season
                      val pe = item.progress.episode
                      if (ps != null && pe != null) context.getString(R.string.season_episode_format, ps, pe)
                      else item.progress.episodeTitle
                  }
              }
              is ContinueWatchingItem.NextUp -> {
                  val code = context.getString(R.string.season_episode_format, item.info.season, item.info.episode)
                  if (item.info.hasAired) "$code ${item.info.episodeTitle?.localizeEpisodeTitle(context).orEmpty()}".trim()
                  else code
              }
          },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item),
        metaPreview = item.toEnrichmentSeed()
    )
}

internal fun ContinueWatchingItem.toEnrichmentSeed(): MetaPreview = when (this) {
    is ContinueWatchingItem.InProgress -> {
        val progress = progress
        MetaPreview(
            id = progress.contentId,
            type = ContentType.fromString(progress.contentType),
            rawType = progress.contentType,
            name = displayTitleForContinueWatching(),
            poster = progress.poster ?: progress.logo,
            posterShape = preferredContinueWatchingPosterShape(),
            background = progress.backdrop ?: progress.logo,
            logo = progress.logo,
            description = episodeDescription,
            releaseInfo = releaseInfo,
            imdbRating = episodeImdbRating,
            genres = genres,
            runtime = progress.duration.takeIf { it > 0L }?.let(::formatFreeboxDurationCompact)
        )
    }
    is ContinueWatchingItem.NextUp -> {
        val info = info
        MetaPreview(
            id = info.contentId,
            type = ContentType.fromString(info.contentType),
            rawType = info.contentType,
            name = info.name,
            poster = info.poster,
            posterShape = preferredContinueWatchingPosterShape(),
            background = info.backdrop,
            logo = info.logo,
            description = info.episodeDescription,
            releaseInfo = info.releaseInfo,
            imdbRating = info.imdbRating,
            genres = info.genres
        )
    }
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int,
    strTypeMovie: String = "",
    strTypeSeries: String = "",
    showFullReleaseDate: Boolean = true,
    previousCachedItem: ModernCarouselItem? = null
): ModernCarouselItem {
    // Nuvio's catalogue identity is the current MetaPreview. Do not carry an
    // old artwork URL from a previous row snapshot: that made stale posters
    // reappear after enrichment or a refresh.
    val currentBackdrop = item.backdropUrl
    val currentPoster = item.poster?.takeIf { it.isNotBlank() }
    val currentLogo = item.logo
    val cleanTitle = item.name
    val cardImageUrl = if (useLandscapePosters) {
        currentBackdrop ?: currentPoster
    } else {
        currentPoster ?: currentBackdrop
    }

    val heroPreview = HeroPreview(
        title = cleanTitle,
        logo = item.logo,
        description = item.description,
        contentTypeText = when (item.apiType.lowercase()) {
            "movie" -> strTypeMovie.ifBlank { item.apiType.replaceFirstChar { ch -> ch.uppercase() } }
            "series" -> strTypeSeries.ifBlank { item.apiType.replaceFirstChar { ch -> ch.uppercase() } }
            "iptv_movie", "iptv_series", "freebox" -> null
            else -> item.apiType.replaceFirstChar { ch -> ch.uppercase() }
        },
        isSeries = isSeriesType(item.apiType),
        yearText = extractYearText(item.type, item.releaseInfo, item.released, showFullReleaseDate),
        runtimeText = formatHeroRuntime(item.runtime),
        imdbText = item.imdbRating?.let { String.format("%.1f", it) },
        ageRatingText = item.ageRating,
        statusText = item.status,
        countryText = item.country,
        languageText = item.language?.uppercase(),
        seasonCount = item.seasonCount,
        cast = item.cast.asStable(),
        director = item.director.asStable(),
        genres = item.genres.take(3).asStable(),
        poster = currentPoster,
        backdrop = currentBackdrop,
        imageUrl = cardImageUrl,
        frozenBackdropUrl = null,
        frozenLogoUrl = null
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = cleanTitle,
        subtitle = item.releaseInfo,
        imageUrl = cardImageUrl,
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}

internal fun buildCollectionFolderItem(
    collection: Collection,
    folder: CollectionFolder,
    occurrence: Int = 0
): ModernCarouselItem {
    val title = if (!folder.coverEmoji.isNullOrBlank()) {
        "${folder.coverEmoji}  ${folder.title}"
    } else {
        folder.title
    }
    // Cover image takes priority over emoji. Emoji is only used as fallback
    // when no cover image is available.
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // Don't use it as a static poster — it would still animate via Coil's GIF decoder.
    val imageUrl = firstNonBlank(folder.coverImageUrl, collection.backdropImageUrl)
    val heroBackdrop = firstNonBlank(folder.heroBackdropUrl, folder.coverImageUrl, collection.backdropImageUrl)

    return ModernCarouselItem(
        key = "collection_${collection.id}_${folder.id}_$occurrence",
        title = if (folder.hideTitle) "" else folder.title,
        subtitle = if (folder.hideTitle) null else collection.title,
        imageUrl = imageUrl,
        heroPreview = HeroPreview(
            title = if (folder.hideTitle) "" else title,
            logo = folder.titleLogoUrl,
            description = null,
            contentTypeText = null,
            yearText = null,
            imdbText = null,
            genres = emptyList<String>().asStable(),
            poster = imageUrl,
            backdrop = heroBackdrop,
            imageUrl = imageUrl
        ),
        payload = ModernPayload.CollectionFolder(
            focusKey = "collection_${collection.id}::${folder.id}",
            collectionId = collection.id,
            collectionTitle = collection.title,
            folderId = folder.id,
            posterShape = folder.tileShape,
            focusGlowEnabled = collection.focusGlowEnabled,
            focusGifEnabled = folder.focusGifEnabled,
            focusGifUrl = folder.focusGifUrl,
            heroBackdropUrl = folder.heroBackdropUrl,
            heroVideoUrl = folder.heroVideoUrl,
            titleLogoUrl = folder.titleLogoUrl,
            coverEmoji = folder.coverEmoji
        )
    )
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
    }
}

internal fun catalogRowKey(row: CatalogRow): String {
    return "${row.addonId}_${row.apiType}_${row.catalogId}"
}

internal fun catalogRowTitle(
    row: CatalogRow,
    showCatalogTypeSuffix: Boolean,
    strTypeMovie: String = "",
    strTypeSeries: String = ""
): String {
    val catalogName = row.catalogName.replaceFirstChar { it.uppercase() }
    if (!showCatalogTypeSuffix || row.addonId == "iptv_favorites") return catalogName
    val typeLabel = when (row.apiType.lowercase()) {
        "movie" -> strTypeMovie.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        "series" -> strTypeSeries.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        else -> row.apiType.replaceFirstChar { it.uppercase() }
    }
    return "$catalogName - $typeLabel"
}

internal fun CatalogRow.key(): String {
    return "${addonId}_${apiType}_${catalogId}"
}

internal fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        type.equals("iptv_series", ignoreCase = true)
}

internal fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun CatalogRow.usesLandscapeArtwork(globalLandscapePosters: Boolean): Boolean =
    globalLandscapePosters ||
        (addonId == HomeViewModel.IPTV_FAVORITES_ADDON_ID &&
            (catalogId == HomeViewModel.IPTV_MOVIE_FAVORITES_CATALOG_ID ||
                catalogId == HomeViewModel.IPTV_SERIES_FAVORITES_CATALOG_ID))

/** IPTV provider artwork is never a full-screen Home hero for movies or series. */
internal fun resolveIptvFavoriteHeroBackdrop(
    baseItem: MetaPreview?,
    cardImageUrl: String?,
    enrichedItem: MetaPreview?,
    manualBackdropUrl: String? = null
): String? {
    if (baseItem?.rawType != "iptv_movie" && baseItem?.rawType != "iptv_series") return null

    // Old versions could write provider banners into the override store. Only
    // an official TMDB URL or a locally generated/selected image is accepted.
    val acceptedManualBackdrop = manualBackdropUrl
        .takeIfTrustedIptvHeroBackdrop()
        ?.takeIf { candidate ->
            candidate == baseItem.background || candidate == baseItem.landscapePoster
        }
    return firstNonBlank(
        acceptedManualBackdrop,
        enrichedItem?.background.takeIfTrustedIptvHeroBackdrop(),
        enrichedItem?.landscapePoster.takeIfTrustedIptvHeroBackdrop(),
        baseItem.background.takeIfTrustedIptvHeroBackdrop(),
        baseItem.landscapePoster.takeIfTrustedIptvHeroBackdrop(),
        cardImageUrl.takeIfTrustedIptvHeroBackdrop()
    )
}

private fun String?.takeIfTrustedIptvHeroBackdrop(): String? = this?.takeIf { url ->
    url.isTrustedIptvHeroBackdrop()
}

private fun String.isTrustedIptvHeroBackdrop(): Boolean =
    contains("image.tmdb.org/", ignoreCase = true) ||
        startsWith("content://", ignoreCase = true) ||
        startsWith("file:", ignoreCase = true)

/** Upstream already freezes the scene during scrolling; a settled null must clear stale artwork. */
internal fun resolveDisplayedHeroBackdrop(
    requestedBackdrop: String?,
    displayedBackdrop: String?,
    enrichmentActive: Boolean
): String? = if (!enrichmentActive && requestedBackdrop != displayedBackdrop) {
    requestedBackdrop
} else {
    displayedBackdrop
}

internal fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

internal fun extractYearOrRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

@Volatile
private var cachedDateFormatLocale: java.util.Locale? = null
@Volatile
private var cachedDateFormatPattern: String? = null

internal fun extractYearText(type: ContentType, releaseInfo: String?, released: String?, showFullDate: Boolean = true): String? {
    if (showFullDate && type == ContentType.MOVIE) {
        val full = released
            ?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
            ?.let {
                val locale = java.util.Locale.getDefault()
                val pattern = if (locale == cachedDateFormatLocale && cachedDateFormatPattern != null) {
                    cachedDateFormatPattern!!
                } else {
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy").also { p ->
                        cachedDateFormatPattern = p
                        cachedDateFormatLocale = locale
                    }
                }
                java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(it)
            }
        if (full != null) return full
    }
    return extractYearOrRange(releaseInfo)
}

private val HOURS_REGEX = "(\\d+)\\s*h".toRegex()
private val MINUTES_REGEX = "(\\d+)\\s*m(?:in)?".toRegex()

private fun formatHeroRuntime(runtime: String?): String? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hours = HOURS_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = MINUTES_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val totalMinutes = when {
        hours != null || minutes != null -> (hours ?: 0) * 60 + (minutes ?: 0)
        else -> normalized.filter(Char::isDigit).toIntOrNull()
    } ?: return runtime

    val wholeHours = totalMinutes / 60
    val remainingMinutes = totalMinutes % 60
    return when {
        wholeHours > 0 && remainingMinutes > 0 -> "${wholeHours}h${remainingMinutes.toString().padStart(2, '0')}m"
        wholeHours > 0 -> "${wholeHours}h00m"
        else -> "0h${remainingMinutes.toString().padStart(2, '0')}m"
    }
}

internal fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

internal fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

internal fun ContinueWatchingItem.videoId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.videoId
        is ContinueWatchingItem.NextUp -> info.videoId
    }
}

internal fun ContinueWatchingItem.freeboxIdOrNull(): String? =
    sequenceOf(videoId(), contentId())
        .firstOrNull { it.startsWith("freebox:", ignoreCase = true) }

internal fun ContinueWatchingItem.isIptvItem(): Boolean =
    contentType().equals("iptv", ignoreCase = true) ||
        contentType().startsWith("iptv_", ignoreCase = true) ||
        contentId().startsWith("iptv_", ignoreCase = true) ||
        videoId().startsWith("iptv_", ignoreCase = true)

internal fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.seedSeason
    }
}

internal fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.seedEpisode
    }
}

internal fun ContinueWatchingItem.withHomeDisplayTitle(): ContinueWatchingItem = when (this) {
    is ContinueWatchingItem.InProgress -> copy(progress = progress.copy(name = displayTitleForContinueWatching()))
    is ContinueWatchingItem.NextUp -> this
}

internal fun ContinueWatchingItem.preferredContinueWatchingPosterShape(): PosterShape {
    val landscapeArtwork = when (this) {
        // Channel cards use the provider's official logo as their identity
        // artwork; do not infer their shape from a programme frame.
        is ContinueWatchingItem.InProgress -> if (
            progress.contentType.equals("iptv", ignoreCase = true) ||
            progress.contentType.equals("iptv_live", ignoreCase = true) ||
            progress.contentId.startsWith("iptv_live", ignoreCase = true)
        ) firstNonBlank(progress.logo, progress.poster, progress.backdrop)
        else firstNonBlank(progress.backdrop, episodeThumbnail)
        is ContinueWatchingItem.NextUp -> firstNonBlank(info.backdrop, info.thumbnail)
    }
    return if (!landscapeArtwork.isNullOrBlank()) PosterShape.LANDSCAPE else PosterShape.POSTER
}

internal fun ContinueWatchingItem.InProgress.displayTitleForContinueWatching(): String =
    progress.displayNameForContinueWatching(
        fallbackTitle = releaseInfo
            ?.takeIf { it.isNotBlank() && !it.isYearOnlyLabel() }
            ?: progress.episodeTitle?.takeIf { it.isNotBlank() }
    )

private fun com.nuvio.tv.domain.model.WatchProgress.displayNameForContinueWatching(
    fallbackTitle: String? = null
): String =
    if (isFreeboxProgressForHome()) {
        val bestName = name
            .takeUnless { it.isLikelyGeneratedFreeboxLabel() }
            ?: freeboxPathFromContentId(contentId)
                .takeUnless { it.isLikelyGeneratedFreeboxLabel() }
            ?: freeboxPathFromContentId(videoId)
                .takeUnless { it.isLikelyGeneratedFreeboxLabel() }
            ?: videoId
        freeboxTmdbSearchQuery(bestName)
    } else if (contentType.equals("iptv_movie", ignoreCase = true)) {
        val bestName = name
            .takeUnless { it.isBlank() || it.isDurationOnlyLabel() }
            ?: fallbackTitle
                ?.takeUnless { it.isBlank() || it.isDurationOnlyLabel() }
            ?: name
        prefixHomeIptvMovieDuration(
            title = bestName,
            formattedDuration = duration.takeIf { it > 0L }?.let(::formatFreeboxDurationCompact)
        )
    } else if (contentType.equals("iptv_series", ignoreCase = true)) {
        cleanHomeIptvDisplayTitle(name)
    } else {
        name
    }

private fun String.isDurationOnlyLabel(): Boolean =
    trim().lowercase().matches(Regex("""\d+h\d{2}|\d+\s*min"""))

private fun String.isYearOnlyLabel(): Boolean =
    trim().matches(Regex("""(?:19|20)\d{2}"""))

private fun String.isLikelyGeneratedFreeboxLabel(): Boolean {
    val value = trim()
    if (value.isBlank()) return true
    if (value.equals("videos", ignoreCase = true) || value.equals("vidéos", ignoreCase = true)) return true
    if (value.isDurationOnlyLabel()) return true
    if (value.matches(Regex("""(?i)s\d{1,2}e\d{1,3}"""))) return true
    return value.matches(Regex("\\d{10,}"))
}

private fun com.nuvio.tv.domain.model.WatchProgress.isFreeboxProgressForHome(): Boolean =
    contentId.startsWith("freebox:") || videoId.startsWith("freebox:") || contentType.equals("freebox", ignoreCase = true)
private fun com.nuvio.tv.domain.model.WatchProgress.isIptvProgressForHome(): Boolean =
    contentId.startsWith("iptv_") || videoId.startsWith("iptv_") || contentType.startsWith("iptv", ignoreCase = true)
private fun com.nuvio.tv.domain.model.WatchProgress.isSpecialProgressForHome(): Boolean =
    isFreeboxProgressForHome() || isIptvProgressForHome()
