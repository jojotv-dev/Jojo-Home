package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleStyleSettings

/**
 * Temporary compatibility view for old player-controller references.
 * libmpv has been removed; every method is deliberately inert.
 */
class NuvioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    fun ensureInitialized() = Unit
    fun setMedia(url: String, headers: Map<String, String>) = Unit
    fun setMediaUsingLoadfile(url: String, headers: Map<String, String>) = Unit
    fun setPaused(paused: Boolean) = Unit
    fun isPlayingNow(): Boolean = false
    fun isPausedForCacheNow(): Boolean = false
    fun isCoreIdleNow(): Boolean = true
    fun seekToMs(positionMs: Long) = Unit
    fun currentPositionMs(): Long = 0L
    fun durationMs(): Long = 0L
    fun hasVideoTrackSelectedNow(): Boolean = false
    fun setPlaybackSpeed(speed: Float) = Unit
    fun applyAudioAmplificationDb(db: Int) = Unit
    fun applyAudioLanguagePreferences(languages: List<String>) = Unit
    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) = Unit
    fun setSubtitleDelayMs(delayMs: Int) = Unit
    fun applyAspectMode(mode: AspectMode) = Unit
    fun applySubtitleStyle(style: SubtitleStyleSettings) = Unit
    fun selectAudioTrackById(trackId: Int): Boolean = false
    fun selectSubtitleTrackById(trackId: Int): Boolean = false
    fun disableSubtitles(): Boolean = false
    fun addAndSelectExternalSubtitle(url: String, title: String? = null, language: String? = null): Boolean = false
    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) = Unit
    fun readTrackSnapshot() = MpvTrackSnapshot(emptyList(), emptyList(), null)
    fun releasePlayer() = Unit
}

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>,
    val videoFormat: MpvVideoFormat?
)

data class MpvVideoFormat(
    val codec: String?,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)

