package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import org.json.JSONArray
import org.json.JSONObject
import com.jojotv.home.HomeMediaRules
import com.nuvio.tv.domain.model.MetaCastMember
import java.net.URLDecoder

internal data class PlayerNavigationArgs(
    val streamUrl: String,
    val title: String,
    val streamName: String?,
    val year: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val country: String?,
    val ageRating: String?,
    val status: String?,
    val headersJson: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val tmdbId: Int?,
    val tmdbMediaType: String?,
    val videoId: String?,
    val initialSeason: Int?,
    val initialEpisode: Int?,
    val initialEpisodeTitle: String?,
    val bingeGroup: String?,
    val filename: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val startFromBeginning: Boolean,
    val addonName: String?,
    val addonLogo: String?,
    val streamDescription: String?,
    val initialCast: List<String>,
    val initialCastDetails: List<MetaCastMember>,
    val initialDirector: List<String>,
    val infoHash: String?,
    val fileIdx: Int?,
    val sourcesJson: String?,
    val contentLanguage: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?,
    val liveHandoffToken: String?,
    val dnsJson: String? = null,
    val initialDescription: String? = null,
    val rating: Float? = null,
    val releaseDate: String? = null
) {
    val torrentTrackers: List<String>
        get() {
            val json = sourcesJson ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length())
                    .mapNotNull { arr.optString(it) }
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
            } catch (_: Exception) {
                emptyList()
            }
        }

    companion object {
        fun from(savedStateHandle: SavedStateHandle): PlayerNavigationArgs {
            fun decodedOrNull(key: String): String? {
                val value = savedStateHandle.get<String>(key) ?: return null
                if (value.isEmpty()) return null
                // Stream metadata occasionally contains stray `%` or malformed escapes
                // Fall back to the raw value rather
                // than crashing the player on launch.
                return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
            fun numericValue(key: String): String? = when (val value = savedStateHandle.get<Any>(key)) {
                is Number -> value.toString()
                is String -> value
                else -> null
            }
            // Navigation already decoded this JSON. A second URL decode corrupts '+' and '%'.
            val presentation = runCatching { JSONObject(savedStateHandle.get<String>("presentation").orEmpty()) }
                .getOrDefault(JSONObject())
            fun presentationText(vararg keys: String): String? = keys.asSequence()
                .filter { presentation.opt(it) !is JSONArray && presentation.opt(it) !is JSONObject }
                .map { HomeMediaRules.mediaMetadataText(presentation.optString(it, "")) }
                .firstOrNull(String::isNotBlank)
            fun decodedList(key: String): List<String> = (presentation.optJSONArray(key)?.toString()
                ?: presentationText(key)?.takeIf { it.startsWith("[") } ?: decodedOrNull(key))
                ?.let { raw ->
                    runCatching {
                        val values = JSONArray(raw)
                        (0 until values.length())
                            .mapNotNull { HomeMediaRules.mediaMetadataText(values.optString(it)).takeIf(String::isNotBlank) }
                    }.getOrDefault(emptyList())
                }
                .orEmpty()
            fun decodedCastDetails(): List<MetaCastMember> = (presentation.optJSONArray("castDetails")?.toString() ?: decodedOrNull("castDetails"))
                ?.let { raw ->
                    runCatching {
                        val values = JSONArray(raw)
                        (0 until values.length()).mapNotNull { index ->
                            val value = values.optJSONObject(index) ?: return@mapNotNull null
                            val name = HomeMediaRules.mediaMetadataText(value.optString("name"))
                            if (name.isBlank()) return@mapNotNull null
                            MetaCastMember(
                                name = name,
                                character = value.optString("character").takeIf { it.isNotBlank() && it != "null" },
                                photo = value.optString("photo").takeIf { it.isNotBlank() && it != "null" },
                                tmdbId = value.optInt("tmdbId").takeIf { it > 0 }
                            )
                        }
                    }.getOrDefault(emptyList())
                }
                .orEmpty()

            return PlayerNavigationArgs(
                streamUrl = savedStateHandle.get<String>("streamUrl") ?: "",
                title = decodedOrNull("title") ?: "",
                streamName = decodedOrNull("streamName"),
                year = presentationText("year", "releaseYear") ?: decodedOrNull("year"),
                genres = decodedList("genres").ifEmpty { (presentationText("genres", "genre") ?: decodedOrNull("genres"))
                    ?.split(Regex("""\s*(?:•|,)\s*"""))
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    .orEmpty() },
                runtimeMinutes = presentation.optInt("runtimeMinutes").takeIf { it > 0 }
                    ?: numericValue("runtimeMinutes")?.toIntOrNull()?.takeIf { it > 0 },
                country = decodedOrNull("country"),
                ageRating = decodedOrNull("ageRating"),
                status = presentationText("status") ?: decodedOrNull("status"),
                headersJson = decodedOrNull("headers"),
                dnsJson = decodedOrNull("iptvDns"),
                // NavController already decodes these IDs.
                contentId = savedStateHandle.get<String>("contentId")?.takeIf { it.isNotEmpty() },
                contentType = savedStateHandle.get<String>("contentType")?.takeIf { it.isNotEmpty() },
                contentName = decodedOrNull("contentName"),
                poster = presentationText("poster", "artwork") ?: decodedOrNull("poster"),
                backdrop = presentationText("backdrop") ?: decodedOrNull("backdrop"),
                logo = presentationText("logo", "titleLogo") ?: decodedOrNull("logo"),
                tmdbId = numericValue("tmdbId")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: presentation.optInt("tmdbId").takeIf { it > 0 },
                tmdbMediaType = (decodedOrNull("tmdbMediaType") ?: presentationText("mediaType"))
                    ?.lowercase()
                    ?.takeIf { it == "movie" || it == "tv" },
                videoId = savedStateHandle.get<String>("videoId")?.takeIf { it.isNotEmpty() },
                initialSeason = numericValue("season")?.toIntOrNull()?.takeIf { it >= 0 },
                initialEpisode = numericValue("episode")?.toIntOrNull()?.takeIf { it >= 0 },
                initialEpisodeTitle = decodedOrNull("episodeTitle"),
                bingeGroup = decodedOrNull("bingeGroup"),
                filename = decodedOrNull("filename"),
                videoHash = savedStateHandle.get<String>("videoHash")?.takeIf { it.isNotEmpty() },
                videoSize = numericValue("videoSize")?.toLongOrNull()?.takeIf { it >= 0L },
                startFromBeginning = savedStateHandle.get<String>("startFromBeginning")?.toBooleanStrictOrNull() == true,
                addonName = decodedOrNull("addonName"),
                addonLogo = decodedOrNull("addonLogo"),
                streamDescription = decodedOrNull("streamDescription"),
                initialCast = decodedList("cast").ifEmpty { decodedList("actors") }
                    .ifEmpty { HomeMediaRules.mediaMetadataNames(presentationText("cast", "actors")) },
                initialCastDetails = decodedCastDetails(),
                initialDirector = decodedList("director").ifEmpty { HomeMediaRules.mediaMetadataNames(presentationText("director")) },
                infoHash = savedStateHandle.get<String>("infoHash")?.takeIf { it.isNotEmpty() },
                fileIdx = numericValue("fileIdx")?.toIntOrNull()?.takeIf { it >= 0 },
                sourcesJson = decodedOrNull("sources"),
                contentLanguage = decodedOrNull("contentLanguage"),
                rememberedAudioLanguage = decodedOrNull("rememberedAudioLanguage"),
                rememberedAudioName = decodedOrNull("rememberedAudioName"),
                liveHandoffToken = decodedOrNull("liveHandoffToken"),
                initialDescription = presentationText("description", "overview", "plot", "synopsis"),
                rating = presentation.optString("rating", "").replace(',', '.').toFloatOrNull()?.takeIf { it > 0f },
                releaseDate = presentationText("releaseDate")
            )
        }
    }
}
