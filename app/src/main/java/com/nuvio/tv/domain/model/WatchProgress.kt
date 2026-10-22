package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.util.Locale
import java.security.MessageDigest

/**
 * Represents the watch progress for a content item (movie or episode).
 */
@Immutable
data class WatchProgress(
    val contentId: String,           // IMDB ID of the movie/series
    val contentType: String,         // "movie" or "series"
    val name: String,                // Movie or series name
    val poster: String?,             // Poster URL
    val backdrop: String?,           // Backdrop URL
    val logo: String?,               // Logo URL
    val videoId: String,             // Specific video/episode ID being watched
    val season: Int?,                // Season number (null for movies)
    val episode: Int?,               // Episode number (null for movies)
    val episodeTitle: String?,       // Episode title (null for movies)
    val position: Long,              // Current playback position in ms
    val duration: Long,              // Total duration in ms
    val lastWatched: Long,           // Timestamp when last watched
    val addonBaseUrl: String? = null, // Addon that was used to play
    val progressPercent: Float? = null, // 0..100 from remote sources like Trakt playback
    val source: String = SOURCE_LOCAL,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val description: String? = null   // Synopsis shown by launchers that support it
) {
    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_TRAKT_PLAYBACK = "trakt_playback"
        const val SOURCE_TRAKT_HISTORY = "trakt_history"
        const val SOURCE_TRAKT_SHOW_PROGRESS = "trakt_show_progress"
        const val STARTED_THRESHOLD = 0.02f
        const val COMPLETED_THRESHOLD = 0.90f
        const val EPISODE_COMPLETED_THRESHOLD = 0.93f

        @JvmStatic
        fun completionThreshold(contentType: String?, episode: Int?): Float =
            if (contentType.equals("series", true) || episode != null) EPISODE_COMPLETED_THRESHOLD
            else COMPLETED_THRESHOLD

        /** Stable deduplication key. Titles are deliberately excluded because homonyms are common. */
        @JvmStatic
        fun stableIdentity(
            contentType: String,
            contentId: String,
            addonBaseUrl: String?,
            season: Int?,
            episode: Int?
        ): String = buildString {
            append(contentType.trim().lowercase(Locale.ROOT))
            append('|').append(addonBaseUrl?.trim().orEmpty())
            append('|').append(contentId.trim())
            if (season != null || episode != null) {
                append('|').append(season ?: 0).append(':').append(episode ?: 0)
            }
        }

        @JvmStatic
        fun shouldApplyRemoteProgress(existingLastWatched: Long?, remoteLastWatched: Long): Boolean =
            existingLastWatched == null || remoteLastWatched > existingLastWatched

        @JvmStatic
        fun hasNewerUnpushedLocalProgress(
            existingLastWatched: Long?,
            remoteLastWatched: Long,
            lastSuccessfulPushMs: Long
        ): Boolean = existingLastWatched != null &&
            existingLastWatched > remoteLastWatched && existingLastWatched > lastSuccessfulPushMs
    }

    /**
     * Progress percentage (0.0 to 1.0)
     */
    val isLiveChannel: Boolean
        get() = contentType.equals("iptv", true) && season == null && episode == null

    /** Profile is the storage namespace; the key also separates providers, types and episodes. */
    @Transient @Volatile private var cachedStorageKey: String? = null
    val storageKey: String
        get() {
            cachedStorageKey?.let { return it }
            val parts = listOf(contentType.trim().lowercase(Locale.ROOT), addonBaseUrl?.trim().orEmpty(),
                contentId.trim(), season?.toString().orEmpty(), episode?.toString().orEmpty())
            val identity = parts.joinToString("") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(68) {
                append("wp2:")
                digest.forEach { append(hex[(it.toInt() and 0xff) ushr 4]); append(hex[it.toInt() and 15]) }
            }.also { cachedStorageKey = it }
        }

    val legacyStorageKey: String
        get() = if (season != null && episode != null) "${contentId}_s${season}e${episode}" else contentId

    val progressPercentage: Float
        get() {
            if (isLiveChannel) return 0f
            progressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        }

    /**
     * Returns true at 93% for episodes, or 90% for other content by default.
     */
    fun isCompleted(threshold: Float = completionThreshold(contentType, episode)): Boolean = progressPercentage >= threshold

    /**
     * Returns true if the content has been started but not completed
     */
    fun isInProgress(startThreshold: Float = STARTED_THRESHOLD,
                     endThreshold: Float = completionThreshold(contentType, episode)): Boolean =
        progressPercentage >= startThreshold && progressPercentage < endThreshold

    /**
     * Returns the remaining time in milliseconds
     */
    val remainingTime: Long
        get() = if (isLiveChannel || duration <= 0) 0L else duration - position.coerceIn(0L, duration)

    fun resolveResumePosition(actualDuration: Long): Long {
        if (isLiveChannel) return 0L
        if (actualDuration <= 0) return position.coerceAtLeast(0L)
        if (duration > 0 && position > 0) {
            return position.coerceIn(0L, actualDuration)
        }
        progressPercent?.let { explicitPercent ->
            val fraction = (explicitPercent / 100f).coerceIn(0f, 1f)
            return (actualDuration * fraction).toLong()
        }
        return position.coerceAtLeast(0L)
    }
}

/**
 * Represents the next item to watch for a series or a movie to resume.
 */
@Immutable
data class NextToWatch(
    val watchProgress: WatchProgress?,  // Null if nothing has been watched yet
    val isResume: Boolean,              // True if resuming current item, false if next episode
    val nextVideoId: String?,           // Video ID to play next
    val nextSeason: Int?,               // Next season number
    val nextEpisode: Int?,              // Next episode number
    val displayText: String             // Text to show on button (e.g., "Resume S1E2", "Play S1E3")
)
