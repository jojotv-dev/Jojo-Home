package com.jojotv.home.progresspilot

import com.nuvio.tv.domain.model.WatchProgress
import java.util.UUID

/** Cloud revisions are server revisions, never device clocks or the mirror's sequence. */
internal data class CloudProgressRow(
    val content_key: String,
    val media_kind: String,
    val position_ms: Long,
    val duration_ms: Long,
    val progress_percent: Float?,
    val watch_state: String,
    val resume_hidden: Boolean,
    val source_time_ms: Long,
    val server_revision: Long = 0,
    val received_time_ms: Long = 0,
    val sealed_metadata: String? = null
) {
    fun validate() {
        require(content_key.matches(Regex("[0-9a-f]{64}")))
        require(media_kind in setOf("file", "film", "episode"))
        require(position_ms >= 0 && duration_ms >= 0 && source_time_ms > 0 && server_revision >= 0 && received_time_ms >= 0)
        require(progress_percent == null || progress_percent.isFinite() && progress_percent in 0f..100f)
        require(watch_state in setOf("unseen", "in_progress", "completed"))
        require(sealed_metadata == null || sealed_metadata.length in 40..24000 &&
            sealed_metadata.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
    }

    fun sameValue(other: CloudProgressRow) = copy(server_revision = 0, received_time_ms = 0, sealed_metadata = null) ==
        other.copy(server_revision = 0, received_time_ms = 0, sealed_metadata = null)

    companion object {
        fun from(context: PilotCaptureContext, progress: WatchProgress, hidden: Boolean = false,
                 includeMetadata: Boolean = true): CloudProgressRow? {
            if (progress.isLiveChannel) return null
            val kind = when {
                progress.contentType.equals("series", true) && progress.season != null && progress.episode != null -> "episode"
                progress.contentType.equals("movie", true) -> "film"
                progress.contentType.equals("freebox", true) || progress.contentType.equals("video", true) -> "file"
                else -> return null
            }
            return CloudProgressRow(context.contentKey(progress.storageKey), kind, progress.position,
                progress.duration, progress.progressPercent, when {
                    progress.isCompleted() -> "completed"
                    progress.position > 0 || (progress.progressPercent ?: 0f) > 0f -> "in_progress"
                    else -> "unseen"
                }, hidden, progress.lastWatched,
                sealed_metadata = if (includeMetadata) CloudResumeMetadata.seal(context, progress) else null).also { it.validate() }
        }
    }
}

/** A portable presentation template, encrypted separately from the numeric checkpoint.
 * No stream URL, headers, credentials, intent or historical position is serialized here. */
internal object CloudResumeMetadata {
    private val fields = setOf("contentId", "contentType", "name", "videoId", "season", "episode",
        "episodeTitle", "poster", "backdrop", "logo", "description", "addonBaseUrl")

    private fun text(value: String?, maximum: Int): Boolean = value != null && value.length <= maximum &&
        value.none { it.code < 32 && it !in "\r\n\t" } && !value.contains("://")
    private fun path(value: String): Boolean = value.length in 1..4000 && value.none { it.code < 32 } &&
        (value.startsWith("/") || runCatching {
            val uri = java.net.URI(value.replace(" ", "%20"))
            uri.scheme in setOf("smb", "file") && uri.userInfo == null && uri.query == null && uri.fragment == null
        }.getOrDefault(false))
    private fun supported(value: WatchProgress): Boolean {
        if (!text(value.name, 500) || value.name.isBlank() ||
            !value.addonBaseUrl.orEmpty().matches(Regex("[A-Za-z0-9._-]{0,200}"))) return false
        return when {
            value.contentId.startsWith("tizentube:") -> value.contentType == "video" &&
                value.contentId.removePrefix("tizentube:").matches(Regex("[A-Za-z0-9_-]{11}"))
            value.contentId.startsWith("iptv:") -> value.contentType in setOf("movie", "series") &&
                value.contentId.matches(Regex("[A-Za-z0-9._:-]{1,800}"))
            value.contentId.startsWith("freebox:") -> value.contentType == "freebox" &&
                path(com.nuvio.tv.data.freebox.freeboxPathFromContentId(value.contentId))
            else -> value.contentType == "video" && path(value.contentId)
        }
    }
    private fun image(value: String?): String = value?.takeIf {
        com.jojotv.home.iptv.cloudImageReference(it).isNotEmpty()
    }.orEmpty()
    private fun encode(value: WatchProgress): String = com.google.gson.JsonObject().apply {
        addProperty("contentId", value.contentId); addProperty("contentType", value.contentType)
        addProperty("name", value.name)
        addProperty("videoId", value.videoId.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,800}")) } ?: value.contentId)
        addProperty("season", value.season); addProperty("episode", value.episode)
        addProperty("episodeTitle", value.episodeTitle?.takeIf { text(it, 500) }.orEmpty())
        addProperty("poster", image(value.poster)); addProperty("backdrop", image(value.backdrop)); addProperty("logo", image(value.logo))
        addProperty("description", value.description?.takeIf { text(it, 5000) }.orEmpty())
        addProperty("addonBaseUrl", value.addonBaseUrl)
    }.toString()
    private fun aad(context: PilotCaptureContext, key: String) =
        ("jojo-progress-resume:v1\u0000" + context.scope.ownerId + "\u0000" + context.scope.profileBindingId + "\u0000" + key).toByteArray(Charsets.UTF_8)

    fun seal(context: PilotCaptureContext, value: WatchProgress): String? {
        if (!supported(value)) return null
        val clear = encode(value).toByteArray(Charsets.UTF_8)
        if (clear.size > 16000) { clear.fill(0); return null }
        val key = context.resumeMetadataKey()
        try {
            val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(context, context.contentKey(value.storageKey)))
            return java.util.Base64.getEncoder().encodeToString(iv + cipher.doFinal(clear))
        } finally { key.fill(0); clear.fill(0) }
    }
    fun open(context: PilotCaptureContext, row: CloudProgressRow): WatchProgress? {
        val sealed = row.sealed_metadata ?: return null
        row.validate()
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        require(bytes.size in 29..16028)
        val key = context.resumeMetadataKey()
        try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad(context, row.content_key))
            val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
            return try {
                val obj = com.google.gson.JsonParser.parseString(String(clear, Charsets.UTF_8)).asJsonObject
                require(obj.keySet() == fields)
                fun string(name: String): String = obj[name].let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
                }
                fun number(name: String): Int? = obj[name].let {
                    if (it.isJsonNull) null else { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber &&
                        it.toString().matches(Regex("[0-9]{1,6}"))); it.asInt.also { n -> require(n in 0..100000) } }
                }
                val addon = obj["addonBaseUrl"].let { if (it.isJsonNull) null else string("addonBaseUrl") }
                val result = WatchProgress(string("contentId"), string("contentType"), string("name"),
                    string("poster").ifEmpty { null }, string("backdrop").ifEmpty { null }, string("logo").ifEmpty { null },
                    string("videoId"), number("season"), number("episode"), string("episodeTitle").ifEmpty { null },
                    0, 0, 1, addonBaseUrl = addon, description = string("description").ifEmpty { null })
                require(supported(result) && context.contentKey(result.storageKey) == row.content_key)
                require(result.videoId == result.contentId || result.videoId.matches(Regex("[A-Za-z0-9._:-]{1,800}")))
                require(text(result.episodeTitle.orEmpty(), 500) && text(result.description.orEmpty(), 5000))
                require(listOf(result.poster, result.backdrop, result.logo).all { it == null || image(it) == it })
                require(when (result.contentType) {
                    "freebox", "video" -> "file"
                    "series" -> if (result.season != null && result.episode != null) "episode" else ""
                    else -> "film"
                } == row.media_kind)
                result
            } finally { clear.fill(0) }
        } finally { key.fill(0); bytes.fill(0) }
    }
}

internal data class CloudProgressOperation(
    val operation_id: String = UUID.randomUUID().toString(),
    val sequence: Long,
    val expected_revision: Long,
    val payload: CloudProgressRow
)

internal data class CloudProgressAck(val operation_id: String, val status: String, val server_revision: Long)
internal data class CloudProgressPage(val rows: List<CloudProgressRow>, val next_cursor: Long, val head_revision: Long,
                                      val point: CloudProgressRow? = null)

/** Only technical state. The visible cache is separate and is never rebuilt from pending operations. */
internal data class CloudProgressJournal(
    val incarnation: String = UUID.randomUUID().toString(),
    val sequence: Long = 0,
    val cursor: Long = 0,
    val rows: Map<String, CloudProgressRow> = emptyMap(),
    val writeBases: Map<String, Long> = emptyMap(),
    val pending: List<CloudProgressOperation> = emptyList(),
    val transmitted: Set<String> = emptySet(),
    val conflicts: Map<String, CloudProgressOperation> = emptyMap(),
    val issue: String? = null
) {
    fun enqueue(row: CloudProgressRow): CloudProgressJournal {
        row.validate()
        val key = row.content_key
        val previous = pending.lastOrNull { it.payload.content_key == key }
        if (previous?.payload?.sameValue(row) == true || previous == null && rows[key]?.sameValue(row) == true) return this
        require(sequence < Long.MAX_VALUE) { "CLOUD_SEQUENCE_EXHAUSTED" }
        // Only checkpoints never transmitted may be coalesced. Persist transmission before HTTP.
        val retained = pending.filterNot { it.payload.content_key == key && it.operation_id !in transmitted }
        val operation = CloudProgressOperation(sequence = sequence + 1,
            expected_revision = previous?.expected_revision ?: writeBases[key] ?: rows[key]?.server_revision ?: 0,
            payload = row.copy(server_revision = 0, received_time_ms = 0))
        if (key in conflicts) return copy(sequence = operation.sequence, conflicts = conflicts + (key to operation))
        require(retained.size < 1000) { "CLOUD_QUEUE_FULL" }
        return copy(sequence = operation.sequence, pending = retained + operation)
    }

    fun batch(): List<CloudProgressOperation> = pending.distinctBy { it.payload.content_key }.take(50)

    fun acknowledge(sent: List<CloudProgressOperation>, acks: List<CloudProgressAck>): CloudProgressJournal {
        require(acks.size == sent.size && acks.map { it.operation_id }.toSet().size == acks.size)
        require(acks.all { ack -> sent.any { it.operation_id == ack.operation_id } &&
            ack.status in setOf("applied", "duplicate", "conflict") && ack.server_revision >= 0 })
        var remaining = pending
        val nextConflicts = conflicts.toMutableMap()
        val nextRows = rows.toMutableMap()
        val nextBases = writeBases.toMutableMap()
        for (ack in acks) {
            val op = sent.single { it.operation_id == ack.operation_id }
            if (remaining.none { it == op }) continue // A stale acknowledgement owns no other operation.
            val key = op.payload.content_key
            remaining = remaining.filterNot { it == op }
            if (ack.status == "conflict") {
                val latest = remaining.lastOrNull { it.payload.content_key == key } ?: op
                nextConflicts[key] = latest
                remaining = remaining.filterNot { it.payload.content_key == key }
            } else {
                require(ack.server_revision > op.expected_revision)
                val existing = nextRows[key]
                if (existing == null || ack.server_revision > existing.server_revision)
                    nextRows[key] = op.payload.copy(server_revision = ack.server_revision)
                nextBases[key] = maxOf(nextBases[key] ?: 0, ack.server_revision)
                // Successor has never been transmitted: preserve its id/body, update only its CAS base.
                var updated = false
                remaining = remaining.map {
                    if (!updated && it.payload.content_key == key && it.operation_id !in transmitted) {
                        updated = true
                        it.copy(expected_revision = ack.server_revision)
                    } else it
                }
            }
        }
        return copy(pending = remaining, rows = nextRows, writeBases = nextBases, conflicts = nextConflicts,
            transmitted = transmitted.intersect(remaining.map { it.operation_id }.toSet()))
    }

    fun receive(page: CloudProgressPage): CloudProgressJournal {
        require(page.next_cursor >= cursor && page.next_cursor <= page.head_revision)
        require(page.rows.size <= 200 && page.rows.all { it.server_revision in (cursor + 1)..page.next_cursor })
        require(page.rows.zipWithNext().all { (a,b) -> a.server_revision < b.server_revision })
        val merged = rows.toMutableMap()
        page.point?.let { it.validate(); require(it.server_revision in 1..page.head_revision) }
        (page.rows + listOfNotNull(page.point)).forEach { row ->
            row.validate()
            if (row.server_revision > (merged[row.content_key]?.server_revision ?: 0)) merged[row.content_key] = row
        }
        return copy(rows = merged, cursor = page.next_cursor, issue = null)
    }
}

internal data class CloudCheckpointOrder(val activation: String, val elapsed: Long)
internal data class CloudConflictChoice(val operationId: String, val remoteRevision: Long, val description: String)
internal class CloudConflictChanged : IllegalStateException("CLOUD_CONFLICT_CHANGED")

/** Pins a legacy/import transaction to the NAS namespace across a source change. */
internal object NasProgressMutation : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    object Key : kotlin.coroutines.CoroutineContext.Key<NasProgressMutation>
}
