package com.nuvio.tv.ui.screens.player

/**
 * Carries the exact bitmap URL rendered by the card which launched playback.
 * Metadata lookups may discover other valid images later, but Continue Watching
 * must keep the image the user actually selected on Home.
 */
internal object PlayerLaunchArtworkState {
    private const val MAX_AGE_MS = 30 * 60_000L

    internal data class Selection(
        val contentId: String?,
        val normalizedTitle: String,
        val artworkUrl: String,
        val selectedAt: Long
    )

    @Volatile
    private var latest: Selection? = null

    fun rememberDisplayedArtwork(contentId: String?, title: String?, artworkUrl: String?) {
        val url = artworkUrl?.trim()?.takeIf(String::isNotBlank) ?: return
        val requestedContentId = contentId?.trim()?.takeIf(String::isNotBlank)
        val requestedTitle = normalizeTitle(title)
        val previous = latest
        // A legacy Continue Watching entry may still contain a TMDB id saved by
        // the broken player route. Its card click must not overwrite the stable
        // provider-qualified identity captured from the original Home card.
        val stableContentId = previous
            ?.takeIf {
                requestedTitle.isNotBlank() &&
                    requestedTitle == it.normalizedTitle &&
                    it.contentId.isProviderQualifiedPlaybackId() &&
                    !requestedContentId.isProviderQualifiedPlaybackId()
            }
            ?.contentId
        latest = Selection(
            contentId = stableContentId ?: requestedContentId,
            normalizedTitle = requestedTitle,
            artworkUrl = url,
            selectedAt = System.currentTimeMillis()
        )
    }

    fun artworkForPlayback(contentId: String?, vararg titles: String?): String? {
        return selectionForPlayback(contentId, *titles)?.artworkUrl
    }

    fun selectionForPlayback(contentId: String?, vararg titles: String?): Selection? {
        val selection = latest ?: return null
        if (System.currentTimeMillis() - selection.selectedAt > MAX_AGE_MS) {
            latest = null
            return null
        }
        val normalizedContentId = contentId?.trim()?.takeIf(String::isNotBlank)
        if (normalizedContentId != null && normalizedContentId == selection.contentId) {
            return selection
        }
        if (selection.normalizedTitle.isBlank()) return null
        return selection.takeIf {
            titles.asSequence()
                .map(::normalizeTitle)
                .any { title -> title.isNotBlank() && title == selection.normalizedTitle }
        }
    }

    private fun normalizeTitle(value: String?): String = value
        .orEmpty()
        .trim()
        .replace(Regex("""^\s*\d{1,2}h\d{2}\s+"""), "")
        .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun String?.isProviderQualifiedPlaybackId(): Boolean {
        val value = this ?: return false
        return value.startsWith("iptv_movie:", ignoreCase = true) ||
            value.startsWith("iptv_movie_stream:", ignoreCase = true) ||
            value.startsWith("iptv_series:", ignoreCase = true) ||
            value.startsWith("iptv_series_remote:", ignoreCase = true) ||
            value.startsWith("freebox:", ignoreCase = true)
    }
}

