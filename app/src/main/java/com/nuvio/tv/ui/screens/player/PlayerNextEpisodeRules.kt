package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

object PlayerNextEpisodeRules {
    private val seasonEpisodePattern = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:S(\d{1,2})[ ._-]*E(\d{1,3})|(\d{1,2})x(\d{1,3}))(?![\p{L}\p{N}])"""
    )

    fun usesSequentialPlaylistNextEpisode(contentType: String?): Boolean =
        contentType.equals("other", ignoreCase = true) ||
            contentType.equals("freebox", ignoreCase = true)

    fun parseSeasonEpisode(value: String?): Pair<Int, Int>? {
        val match = value?.let(seasonEpisodePattern::find) ?: return null
        val season = (match.groups[1]?.value ?: match.groups[3]?.value)?.toIntOrNull()
        val episode = (match.groups[2]?.value ?: match.groups[4]?.value)?.toIntOrNull()
        if (season == null || episode == null || season <= 0 || episode <= 0) return null
        return season to episode
    }

    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int,
        currentEpisode: Int
    ): Video? {
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        // Do not use the next list index: provider metadata can temporarily
        // contain a duplicate episode while details are refreshed. In that
        // situation index + 1 can jump over the real next episode.
        sortedEpisodes.firstOrNull {
            it.season == currentSeason && it.episode == currentEpisode + 1
        }?.let { return it }

        // At the end of a season, advance to the first numbered episode of the
        // following season. This also keeps non-contiguous provider numbering
        // deterministic instead of selecting an arbitrary duplicate.
        return sortedEpisodes
            .asSequence()
            .filter { (it.season ?: Int.MAX_VALUE) > currentSeason }
            .minWithOrNull(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        val outroInterval = skipIntervals
            .filter { AutoSkipSegmentType.fromSkipIntervalType(it.type) == AutoSkipSegmentType.OUTRO }
            .minByOrNull { it.startTime }
        return if (outroInterval != null) {
            positionMs / 1000.0 >= outroInterval.startTime
        } else {
            if (durationMs <= 0L) return false
            when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(95f, 100f)
                    (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    val remainingMs = durationMs - positionMs
                    remainingMs <= (clampedMinutes * 60_000f).toLong()
                }
            }
        }
    }

    fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return runCatching { LocalDate.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
    }

    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        val releasedDate = parseEpisodeReleaseDate(raw) ?: return true
        return !releasedDate.isAfter(LocalDate.now(clock))
    }
}
