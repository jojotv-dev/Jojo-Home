package com.nuvio.tv.domain.model

enum class ThumbnailOrientation {
    PORTRAIT,
    LANDSCAPE;

    companion object {
        val DEFAULT = LANDSCAPE

        fun fromName(name: String): ThumbnailOrientation =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
