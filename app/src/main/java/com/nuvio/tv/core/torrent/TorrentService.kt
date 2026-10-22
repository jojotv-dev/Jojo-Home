package com.nuvio.tv.core.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility facade kept while old torrent links are still represented in
 * the navigation model. TorrServer is no longer bundled or started by JojoTV.
 */
@Singleton
class TorrentService @Inject constructor() {
    private val mutableState = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = mutableState.asStateFlow()

    suspend fun startStream(
        infoHash: String,
        fileIdx: Int?,
        filename: String? = null,
        trackers: List<String> = emptyList()
    ): String {
        val message = "TorrServer a été retiré de JojoTV."
        mutableState.value = TorrentState.Error(message)
        throw UnsupportedOperationException(message)
    }

    fun stopStream() {
        mutableState.value = TorrentState.Idle
    }

    fun shutdown() {
        stopStream()
    }
}
