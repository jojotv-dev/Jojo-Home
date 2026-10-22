package com.nuvio.tv.domain.model

enum class HomeCardSize(val scale: Float) {
    SMALL(0.75f),
    MEDIUM(0.875f),
    LARGE(1f);

    /** Scale used by the shared Videos/Films/Series media rows. */
    val mediaRowScale: Float
        get() = when (this) {
            SMALL -> 0.625f
            MEDIUM -> 0.75f
            LARGE -> 1f
        }

    companion object {
        val DEFAULT = LARGE

        fun fromName(name: String?): HomeCardSize =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
