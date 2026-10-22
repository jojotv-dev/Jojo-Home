package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class MetaPreview(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val runtime: String? = null,
    val status: String? = null,
    val ageRating: String? = null,
    val language: String? = null,
    val released: String? = null,
    val country: String? = null,
    val imdbId: String? = null,
    val slug: String? = null,
    val landscapePoster: String? = null,
    val rawPosterUrl: String? = null,
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val links: List<MetaLink> = emptyList(),
    val behaviorHints: MetaBehaviorHints? = null,
    val trailers: List<MetaTrailer> = emptyList(),
    val trailerYtIds: List<String> = emptyList(),
    val seasonCount: Int? = null,
    /** Total number of regular episodes when the source exposes it. */
    val episodeCount: Int? = null,
    val isFavorite: Boolean = false,
    val homeSortDurationSeconds: Long? = null,
    /** Stable resume identity when the card's local database ID is different. */
    val playbackProgressId: String? = null,
    /** Explicit thumbnail selected by the user for the Home card, regardless of orientation. */
    val homeArtworkOverride: String? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster
}
