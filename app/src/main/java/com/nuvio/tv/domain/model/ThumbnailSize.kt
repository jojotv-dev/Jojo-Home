package com.nuvio.tv.domain.model

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tailles de vignettes pour la section "Continuer à regarder".
 * MEDIUM conserve les dimensions de l'ancien niveau SMALL.
 */
enum class ThumbnailSize(
    val cardWidth: Dp,
    val imageHeight: Dp
) {
    VERY_SMALL(cardWidth = 84.dp, imageHeight = 47.dp),
    SMALL(cardWidth = 116.dp, imageHeight = 65.dp),
    MEDIUM(cardWidth = 160.dp, imageHeight = 90.dp),
    LARGE(cardWidth = 220.dp, imageHeight = 124.dp),
    VERY_LARGE(cardWidth = 288.dp, imageHeight = 162.dp);

    companion object {
        val DEFAULT = MEDIUM
        fun fromName(name: String) = entries.firstOrNull { it.name == name } ?: DEFAULT

        fun fromLegacyName(name: String) = when (name) {
            "SMALL" -> MEDIUM
            "MEDIUM" -> LARGE
            "LARGE" -> VERY_LARGE
            else -> DEFAULT
        }
    }
}
