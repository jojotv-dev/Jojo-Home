package com.nuvio.tv.data.freebox

data class FreeboxEpgChannel(
    val epgSourceId: Long,
    val xmltvChannelId: String,
    val displayName: String,
    val iconUrl: String? = null
)

data class FreeboxEpgProgram(
    val externalId: String? = null,
    val channelId: String,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val lang: String = "",
    val rating: String? = null,
    val imageUrl: String? = null,
    val genre: String? = null,
    val category: String? = null
)
