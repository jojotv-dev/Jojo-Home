package com.nuvio.tv.ui.util

/**
 * Compact synopsis for TV focus surfaces.
 *
 * Providers and TMDB expose one full overview rather than separate short and
 * long variants. Keep that original text in storage, but render a readable
 * preview: prefer a complete first sentence when it is concise, otherwise cut
 * on a word boundary. Detail pages can still use the complete overview.
 */
fun String.toShortSynopsis(maxCharacters: Int = 260): String {
    val text = replace(Regex("\\s+"), " ").trim()
    if (text.length <= maxCharacters) return text

    val firstSentenceEnd = text.indexOfFirst { it == '.' || it == '!' || it == '?' }
    if (firstSentenceEnd in 44..maxCharacters) {
        return text.substring(0, firstSentenceEnd + 1)
    }

    val cutoff = text.lastIndexOf(' ', startIndex = maxCharacters)
        .takeIf { it >= maxCharacters / 2 }
        ?: maxCharacters
    return text.substring(0, cutoff).trimEnd(' ', ',', ';', ':') + "…"
}
