package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot ownership transfer for the live IPTV player.
 *
 * The token is passed through Navigation, while the player itself stays in
 * memory. This keeps the decoder and the already-buffered live stream alive.
 */
internal object IptvLivePlayerHandoff {
    internal class Session(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector,
        val streamUrl: String,
        val streamHeaders: Map<String, String>,
        private val returnToOwner: (ExoPlayer, DefaultTrackSelector) -> Boolean,
    ) {
        private val completed = AtomicBoolean(false)

        fun returnOrRelease() {
            if (!completed.compareAndSet(false, true)) return
            val accepted = runCatching { returnToOwner(player, trackSelector) }.getOrDefault(false)
            if (!accepted) runCatching { player.release() }
        }

        fun release() {
            if (!completed.compareAndSet(false, true)) return
            runCatching { player.release() }
        }
    }

    private val pending = ConcurrentHashMap<String, Session>()

    fun register(session: Session): String {
        val token = UUID.randomUUID().toString()
        pending[token] = session
        return token
    }

    fun consume(token: String?): Session? = token?.let(pending::remove)

    fun reclaim(token: String) {
        pending.remove(token)?.returnOrRelease()
    }

    fun abandon(token: String) {
        pending.remove(token)?.release()
    }
}

