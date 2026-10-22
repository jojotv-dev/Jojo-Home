package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Video
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

object FreeboxLocalEpisodeCache {
    private val episodesByContentId = ConcurrentHashMap<String, List<Video>>()

    fun put(contentId: String, episodes: List<Video>) {
        if (contentId.isBlank() || episodes.isEmpty()) return
        episodesByContentId[contentId] = episodes
    }

    fun get(contentId: String?): List<Video> {
        if (contentId.isNullOrBlank()) return emptyList()
        return episodesByContentId[contentId].orEmpty()
    }

    @JvmStatic
    fun putSmbEpisodes(records: JSONArray) {
        val episodePattern = Regex("""(?i)(?<![\p{L}\p{N}])(?:S\d{1,2}[ ._-]*E\d{1,3}|\d{1,2}x\d{1,3})(?![\p{L}\p{N}])""")
        val episodes = (0 until records.length()).mapNotNull { index ->
            val record = records.optJSONObject(index) ?: return@mapNotNull null
            val title = record.optString("title")
            val coordinates = PlayerNextEpisodeRules.parseSeasonEpisode(title) ?: return@mapNotNull null
            val id = record.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val match = episodePattern.find(title) ?: return@mapNotNull null
            val seriesKey = title.substring(0, match.range.first)
                .lowercase(java.util.Locale.ROOT).replace(Regex("[ ._-]+"), " ").trim()
            seriesKey to Video(
                id = id, title = title, released = null,
                thumbnail = record.optString("artwork").takeIf { it.isNotBlank() },
                season = coordinates.first, episode = coordinates.second,
                overview = record.optString("description").takeIf { it.isNotBlank() }
            )
        }
        episodes.groupBy({ it.first }, { it.second }).values.forEach { group ->
            val ordered = group.sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })
            ordered.forEach { put(it.id, ordered) }
        }
    }
}
