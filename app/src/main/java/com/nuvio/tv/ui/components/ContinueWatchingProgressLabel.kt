package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.WatchProgress
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal fun formatContinueWatchingProgressLabel(
    progress: WatchProgress,
    resumeLabel: String,
    percentWatchedLabel: String,
    hoursMinLeftLabel: String,
    minLeftLabel: String
): String {
    val effectiveDuration = progress.duration
    val effectivePosition = if (progress.position > 0L) {
        progress.position
    } else if (effectiveDuration > 0L && progress.progressPercent != null) {
        (effectiveDuration * (progress.progressPercent / 100f)).toLong()
    } else {
        0L
    }

    if (effectiveDuration <= 0L) {
        val percentWatched = (progress.progressPercentage * 100f)
            .roundToInt()
            .coerceIn(0, 100)
        return if (percentWatched > 0) {
            percentWatchedLabel.format(percentWatched)
        } else {
            resumeLabel
        }
    }

    val remainingMs = (effectiveDuration - effectivePosition).coerceAtLeast(0)
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    if (totalMinutes <= 0L) return resumeLabel

    // Si rien n'a encore ete visionne (position = 0), afficher la duree totale
    // sans le prefixe "reste" (ex: "1h39" au lieu de "reste 1h39m").
    if (progress.position <= 0L && progress.progressPercent == null) {
        return if (hours > 0L) {
            "${hours}h${minutes.toString().padStart(2, '0')}"
        } else {
            "${minutes}m"
        }
    }

    return "reste ${hours}h${minutes.toString().padStart(2, '0')}m"
}