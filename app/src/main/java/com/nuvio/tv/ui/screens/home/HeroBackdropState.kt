package com.nuvio.tv.ui.screens.home

object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    fun update(url: String?) {
        currentHeroBackdropUrl = url
        // A settled item with no safe backdrop must clear the previous one.
        // Keeping the last non-null URL resurrected rejected IPTV banners.
        lastDisplayedUrl = url
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        return url
    }
}
