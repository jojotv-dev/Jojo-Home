package com.nuvio.tv.ui.screens.home

fun cleanHomeIptvDisplayTitle(title: String): String = title
    .replace(Regex("^\\s*\\d+\\s*h\\s*\\d{1,2}\\s*", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s+"), " ")
    .trim()
