package com.jojotv.home.iptv

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** Deliberately smaller than IptvItem: even encrypted packets never contain stream URLs or headers. */
@Serializable internal data class IptvCloudItem(val id: String, val source: String, val provider: String,
    val type: ContentType, val title: String, val season: Int = 0, val episode: Int = 0, val series: String = "",
    val poster: String = "", val backdrop: String = "", val logo: String = "",
    val tmdbId: Int = 0, val year: String = "") {
    fun item(): IptvItem {
        val identifier = Regex("[A-Za-z0-9._:-]{1,400}")
        require(listOf(id, source, provider).all { it.matches(identifier) } && (series.isEmpty() || series.matches(identifier)))
        require(title.length in 1..500 && !title.contains("://") && title.none { it.code < 32 })
        require(season in 0..10000 && episode in 0..100000)
        require(listOf(poster, backdrop, logo).all { it.isEmpty() || cloudImageName(it) })
        require(tmdbId >= 0 && (year.isEmpty() || year.matches(Regex("(?:18|19|20|21)\\d{2}"))))
        return IptvItem(id, source, provider, type, title, season = season, episode = episode, seriesId = series,
            artwork = cloudImageUrl(poster), backdrop = cloudImageUrl(backdrop), titleLogo = cloudImageUrl(logo),
            tmdbId = tmdbId, year = year)
    }
    companion object {
        fun from(item: IptvItem) = IptvCloudItem(item.id, item.sourceId, item.providerId, item.type, item.title,
            item.season, item.episode, item.seriesId, cloudImageReference(item.artwork),
            cloudImageReference(item.backdrop), cloudImageReference(item.titleLogo), item.tmdbId.coerceAtLeast(0),
            item.year.take(4).takeIf { it.matches(Regex("(?:18|19|20|21)\\d{2}")) }.orEmpty())
            .also { it.item(); require(!item.scheduledEpisode) }
    }
}
// Only a public TMDB image identifier crosses the wire, never an arbitrary URL or a local file.
internal fun cloudImageName(value: String) = value.matches(Regex("(?:(?:original|w[0-9]{2,4}):)?[A-Za-z0-9]{1,128}\\.(jpg|png|webp)"))
internal fun cloudImageReference(value: String): String {
    val match = Regex("https://image\\.tmdb\\.org/t/p/(original|w[0-9]{2,4})/([A-Za-z0-9]{1,128}\\.(?:jpg|png|webp))")
        .matchEntire(value) ?: return ""
    return (if (match.groupValues[1] == "original") "" else match.groupValues[1] + ":") + match.groupValues[2]
}
internal fun cloudImageUrl(value: String): String = if (value.isEmpty()) "" else {
    require(cloudImageName(value))
    "https://image.tmdb.org/t/p/${value.substringBefore(':', "original")}/${value.substringAfter(':')}"
}

@Serializable internal data class IptvCloudArtwork(val image: String, val changedAt: Long, val writer: String,
    val deleted: Boolean = false) {
    fun validate() {
        require(changedAt in 1..253402300799999L && writer.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(if (deleted) image.isEmpty() else cloudImageName(image))
    }
}

@Serializable private data class LocalArtworkChoice(val value: String = "", val changedAt: Long = 0,
    val writer: String = "", val manualArtwork: Boolean = false, val deleted: Boolean = false)

internal fun cloudArtworkChoice(raw: String?): IptvCloudArtwork? = runCatching {
    val local = iptvJson.decodeFromString<LocalArtworkChoice>(raw ?: return null)
    if (!local.manualArtwork) return null
    IptvCloudArtwork(if (local.deleted) "" else cloudImageReference(local.value), local.changedAt, local.writer, local.deleted)
        .also { it.validate() }
}.getOrNull()

internal fun receivedArtworkChoice(raw: String?, incoming: IptvCloudArtwork): String? {
    incoming.validate()
    val current = runCatching { iptvJson.decodeFromString<LocalArtworkChoice>(raw.orEmpty()) }.getOrNull()
    if (current?.manualArtwork == true && (current.changedAt > incoming.changedAt ||
            current.changedAt == incoming.changedAt && current.writer >= incoming.writer)) return null
    return iptvJson.encodeToString(LocalArtworkChoice(cloudImageUrl(incoming.image), incoming.changedAt,
        incoming.writer, manualArtwork = true, deleted = incoming.deleted))
}

internal fun cloudArtworkKeys(item: IptvCloudItem): Map<String, String> {
    fun key(identity: String, hero: Boolean) = (if (hero) "external_hero_artwork_" else "external_thumbnail_artwork_") +
        Integer.toUnsignedString(identity.hashCode(), 16)
    val identity = "external-playback|${item.id}"
    return linkedMapOf("thumbnail" to key(identity, false), "hero" to key(identity, true)).apply {
        if (item.type == ContentType.EPISODE && item.series.isNotEmpty()) {
            val series = "external-playback|${itemId(item.source, ContentType.SERIES, item.series)}"
            put("seriesThumbnail", key(series, false)); put("seriesHero", key(series, true))
            put("episodes", key("$series|episodes", false))
            if (item.season > 0 && item.episode > 0) put("episode", key("$series|season:${item.season}|episode:${item.episode}", false))
        }
    }
}

internal fun cloudArtworkChoices(context: android.content.Context, item: IptvCloudItem): Map<String, IptvCloudArtwork> {
    val prefs = context.getSharedPreferences("external_artwork_selections", android.content.Context.MODE_PRIVATE)
    return cloudArtworkKeys(item).mapNotNull { (role, key) -> cloudArtworkChoice(prefs.getString(key, null))?.let { role to it } }.toMap()
}

internal fun restoreCloudArtwork(context: android.content.Context, playback: IptvCloudPlayback): Boolean {
    val prefs = context.getSharedPreferences("external_artwork_selections", android.content.Context.MODE_PRIVATE)
    val keys = cloudArtworkKeys(playback.item)
    var changed = false
    synchronized(prefs) {
        val editor = prefs.edit()
        playback.artwork.forEach { (role, value) ->
            val key = keys.getValue(role)
            receivedArtworkChoice(prefs.getString(key, null), value)?.let { editor.putString(key, it); changed = true }
        }
        if (changed) editor.apply()
    }
    return changed
}

@Serializable internal data class IptvCloudPlayback(val item: IptvCloudItem, val playedAt: Long = 0,
    val artwork: Map<String, IptvCloudArtwork> = emptyMap()) {
    fun validate() {
        item.item(); require(playedAt in 0..253402300799999L)
        require(artwork.keys.all { it in cloudArtworkKeys(item) })
        artwork.values.forEach { it.validate() }
    }
}
@Serializable internal data class IptvCloudPlan(val id: String, val channel: IptvCloudItem, val title: String,
    val start: Long, val end: Long, val state: RecordingState, val before: Int, val after: Int,
    val days: List<Int>, val recurrence: String = "") {
    fun recording(account: String): IptvRecording {
        require(runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false))
        require(channel.type == ContentType.LIVE && title.length in 1..500 && !title.contains("://") && title.none { it.code < 32 })
        require(start > 0 && end > start && end - start <= 7 * 86_400_000L && end <= 253402300799999L)
        require(before in 0..180 && after in 0..180 && days.size <= 7 && days.distinct().size == days.size && days.all { it in 1..7 })
        require(recurrence.isEmpty() || runCatching { java.util.UUID.fromString(recurrence).toString() == recurrence }.getOrDefault(false))
        return IptvRecording(id, channel.item(), title, start - before * 60_000L, end + after * 60_000L,
            state = state, accountId = account, programStart = start, programEnd = end, beforeMinutes = before,
            afterMinutes = after, repeatDays = days, recurrenceId = recurrence)
    }
    companion object {
        fun from(plan: IptvRecording) = IptvCloudPlan(plan.id,
            IptvCloudItem.from(plan.channel).copy(poster = "", backdrop = "", logo = "", tmdbId = 0, year = ""), plan.title,
            plan.programStart, plan.programEnd, plan.state, plan.beforeMinutes, plan.afterMinutes, plan.repeatDays, plan.recurrenceId)
            .also { it.recording("") }
    }
}
@Serializable internal data class IptvCloudCommand(val action: String, val desired: IptvCloudPlan? = null) {
    fun validate(id: String) {
        require(action in setOf("EDIT", "CANCEL"))
        if (action == "EDIT") require(desired != null && desired.id == id && desired.state == RecordingState.PLANNED)
        else require(desired == null)
        desired?.recording("")
    }
}

internal fun cloudPlayback(value: IptvCloudPlayback) = iptvJson.encodeToString(value.also { it.validate() })
internal fun cloudPlayback(value: String) = iptvJson.decodeFromString<IptvCloudPlayback>(value).also { it.validate() }
/** Provider enrichment is incidental; a manual image choice remains a tracked user edit. */
internal fun cloudPlaybackTracked(value: String): String = cloudPlayback(value).let {
    cloudPlayback(it.copy(item = it.item.copy(poster = "", backdrop = "", logo = "", tmdbId = 0, year = "")))
}
internal fun cloudPlan(value: IptvCloudPlan) = iptvJson.encodeToString(value.also { it.recording("") })
internal fun cloudPlan(value: String) = iptvJson.decodeFromString<IptvCloudPlan>(value).also { it.recording("") }
internal fun cloudCommand(value: IptvCloudCommand, id: String) = iptvJson.encodeToString(value.also { it.validate(id) })
internal fun cloudCommand(value: String, id: String) = iptvJson.decodeFromString<IptvCloudCommand>(value).also { it.validate(id) }
