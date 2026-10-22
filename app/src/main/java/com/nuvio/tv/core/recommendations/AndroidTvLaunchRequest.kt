package com.nuvio.tv.core.recommendations

import com.nuvio.tv.domain.model.WatchProgress

data class AndroidTvLaunchRequest(
    val contentId: String,
    val contentType: String,
    val profileId: Int? = null,
    val progressSnapshot: WatchProgress? = null
) {
    fun matches(contentId: String, contentType: String): Boolean =
        this.contentId == contentId.trim() &&
            this.contentType.equals(contentType.trim(), ignoreCase = true)

    fun launchesStreamImmediately(): Boolean = progressSnapshot != null

    companion object {
        const val EXTRA_CONTENT_ID = "contentId"
        const val EXTRA_CONTENT_TYPE = "contentType"
        const val EXTRA_PROFILE_ID = "androidTvProfileId"
        const val EXTRA_DIRECT_PLAY = "androidTvDirectPlay"
        const val EXTRA_NAME = "androidTvName"
        const val EXTRA_POSTER = "androidTvPoster"
        const val EXTRA_BACKDROP = "androidTvBackdrop"
        const val EXTRA_LOGO = "androidTvLogo"
        const val EXTRA_VIDEO_ID = "androidTvVideoId"
        const val EXTRA_SEASON = "androidTvSeason"
        const val EXTRA_EPISODE = "androidTvEpisode"
        const val EXTRA_EPISODE_TITLE = "androidTvEpisodeTitle"
        const val EXTRA_POSITION = "androidTvPosition"
        const val EXTRA_DURATION = "androidTvDuration"
        const val EXTRA_LAST_WATCHED = "androidTvLastWatched"
        const val EXTRA_DESCRIPTION = "androidTvDescription"
        fun fromExtras(
            contentId: String?,
            contentType: String?,
            profileId: Int? = null,
            directPlay: Boolean = false,
            name: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            videoId: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            position: Long? = null,
            duration: Long? = null,
            lastWatched: Long? = null,
            description: String? = null
        ): AndroidTvLaunchRequest? {
            val normalizedContentId = contentId?.trim().orEmpty()
            val normalizedContentType = contentType?.trim().orEmpty()
            if (normalizedContentId.isBlank() || normalizedContentType.isBlank()) return null
            val snapshot = if (directPlay) {
                WatchProgress(
                    contentId = normalizedContentId,
                    contentType = normalizedContentType,
                    name = name?.trim()?.takeIf { it.isNotBlank() } ?: normalizedContentId,
                    poster = poster?.trim()?.takeIf { it.isNotBlank() },
                    backdrop = backdrop?.trim()?.takeIf { it.isNotBlank() },
                    logo = logo?.trim()?.takeIf { it.isNotBlank() },
                    videoId = videoId?.trim()?.takeIf { it.isNotBlank() } ?: normalizedContentId,
                    season = season,
                    episode = episode,
                    episodeTitle = episodeTitle?.trim()?.takeIf { it.isNotBlank() },
                    position = position ?: 0L,
                    duration = duration ?: 0L,
                    lastWatched = lastWatched ?: 0L,
                    description = description?.trim()?.takeIf { it.isNotBlank() }
                )
            } else {
                null
            }
            return AndroidTvLaunchRequest(
                contentId = normalizedContentId,
                contentType = normalizedContentType,
                profileId = profileId?.takeIf { it > 0 },
                progressSnapshot = snapshot
            )
        }
    }
}
