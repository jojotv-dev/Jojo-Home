package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.WatchedItem

/**
 * Makes historic local watch entries acceptable to the NAS snapshot endpoint.
 * Older playback records can legitimately have no display title; their content
 * identifier remains stable and is used as a non-empty fallback. Entries that
 * have no usable identity cannot be restored on another device, so do not send
 * them as part of the otherwise valid snapshot.
 */
internal fun watchedItemForNasSync(item: WatchedItem): NasWatchedItem? {
    val contentId = item.contentId.trim()
    val contentType = item.contentType.trim()
    val watchedAt = item.watchedAt
    if (contentId.isEmpty() || contentType.isEmpty() || watchedAt <= 0L) return null

    return NasWatchedItem(
        contentId = contentId,
        contentType = contentType,
        title = item.title.trim().ifEmpty { contentId },
        season = item.season,
        episode = item.episode,
        watchedAt = watchedAt
    )
}
