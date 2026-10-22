package com.nuvio.tv

object LocaleCache {
    const val UNSET = "__UNSET__"

    @Volatile
    var localeTag: String = UNSET
}