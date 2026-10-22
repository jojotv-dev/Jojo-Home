package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    private val allowPassthrough: Boolean = true
) : ForwardingAudioSink(sink) {

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = false

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(object : AudioSink.Listener by listener {
            // Kotlin delegation does not forward Java interface default callbacks.
            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                listener.onPositionAdvancing(playoutStartSystemTimeMs)
            }

            override fun onOffloadBufferEmptying() {
                listener.onOffloadBufferEmptying()
            }

            override fun onOffloadBufferFull() {
                listener.onOffloadBufferFull()
            }

            override fun onAudioSinkError(audioSinkError: Exception) {
                listener.onAudioSinkError(audioSinkError)
            }

            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                listener.onAudioTrackInitialized(audioTrackConfig)
            }

            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                listener.onAudioTrackReleased(audioTrackConfig)
            }

            override fun onSilenceSkipped() {
                listener.onSilenceSkipped()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                listener.onAudioSessionIdChanged(audioSessionId)
            }

            override fun onAudioCapabilitiesChanged() {
                val inputFormat = currentInputFormat
                // Media3 seeks to the current position on capability changes, even
                // when the selected track is unchanged. Keep decoded PCM and
                // compatible passthrough playing while Android changes the output.
                if (inputFormat != null &&
                    getFormatSupport(inputFormat) != AudioSink.SINK_FORMAT_UNSUPPORTED
                ) {
                    return
                }
                // Passthrough that the new output cannot play still needs to
                // switch to decoding. Keep the normal recovery path for it.
                listener.onAudioCapabilitiesChanged()
            }
        })
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        val shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) {
            listener?.onAudioCapabilitiesChanged()
        }
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldRejectDirectPlayback(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (shouldRejectDirectPlayback(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    fun shouldForcePcmForFormat(format: Format): Boolean {
        return shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        if (!allowPassthrough && format.sampleMimeType != MimeTypes.AUDIO_RAW) return true
        return requiresPcmForSpeed(format) && (forcePcmForCurrentSession || playbackSpeed != 1f)
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || speed == 1f || !requiresPcmForSpeed(format)) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    private fun requiresPcmForSpeed(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        if (mimeType != null && (
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                    mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                    mimeType == MimeTypes.AUDIO_AC3 ||
                    mimeType == MimeTypes.AUDIO_AC4 ||
                    mimeType == MimeTypes.AUDIO_TRUEHD ||
                    mimeType == MimeTypes.AUDIO_DTS ||
                    mimeType == MimeTypes.AUDIO_DTS_HD ||
                    mimeType == MimeTypes.AUDIO_DTS_EXPRESS ||
                    mimeType.startsWith("audio/vnd.dts")
                )
        ) {
            return true
        }
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ac-3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                codecs.contains("ec-3", ignoreCase = true) ||
                codecs.contains("dts", ignoreCase = true) ||
                codecs.contains("truehd", ignoreCase = true) ||
                codecs.contains("dtshd", ignoreCase = true)
        }
        return false
    }
}
