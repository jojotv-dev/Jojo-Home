package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.net.Uri
import android.util.Log
import com.jojotv.home.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.security.MessageDigest

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true

    navigationArgs.liveHandoffToken?.let { token ->
        val session = IptvLivePlayerHandoff.consume(token)
        if (session != null) adoptLivePlayerHandoff(session)
        else _uiState.update { it.copy(error = "Le mini lecteur n’est plus disponible.", showLoadingOverlay = false) }
        return
    }

    // Persist binge group from navigation args so that subsequent plays
    // (from CW, Details, or next-episode) can reuse the same source group.
    val bg = navigationArgs.bingeGroup
    val cid = contentId
    if (bg != null && cid != null) {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            bingeGroupCacheDataStore.save(cid, bg)
        }
    }

    val infoHash = navigationArgs.infoHash
    val streamScheme = Uri.parse(initialStreamUrl).scheme ?: "unknown"
    Log.d("PlayerStartup", "startInitialPlayback: infoHash=$infoHash, streamScheme=$streamScheme, streamSha256=${initialStreamUrl.sha256ForLog()}")
    if (infoHash != null) {
        _uiState.update {
            it.copy(
                error = context.getString(R.string.player_error_failed_start_torrent, "TorrServer a été retiré."),
                showLoadingOverlay = false
            )
        }
        return
    }

    scope.launch {
        currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
            url = currentStreamUrl,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders
        )
        _uiState.update { it.copy(currentStreamUrl = currentStreamUrl) }
        preparePlaybackBeforeStart(
            url = currentStreamUrl,
            headers = currentHeaders,
            loadSavedProgress = !navigationArgs.startFromBeginning
        )
    }
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}

private fun String.sha256ForLog(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
