package com.jojotv.home.progresspilot

import com.google.gson.Gson
import com.nuvio.tv.domain.model.WatchProgress
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only the player creates this marker. Imports, NAS pulls and artwork updates never do. */
class PilotPlaybackCheckpoint(val createdAtMs: Long, val createdAtElapsedMs: Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PilotPlaybackCheckpoint>
}

enum class PilotMode { NAS_ONLY, NAS_WITH_SUPABASE_MIRROR, SUPABASE_PROGRESS }

data class PilotScope(
    val ownerId: String,
    val profileBindingId: String,
    val localProfileId: Int,
    val localProfileInstanceId: String,
    val deviceId: String,
    val deviceA: String,
    val deviceB: String,
    val epoch: String,
    val deviceC: String? = null,
    val accountEnrolled: Boolean = false
) {
    fun validate() {
        listOf(ownerId, profileBindingId, localProfileInstanceId, deviceId, deviceA, deviceB, epoch)
            .forEach { require(UUID.fromString(it).toString() == it) { "Association UUID invalide." } }
        deviceC?.let { require(UUID.fromString(it).toString() == it) { "Association UUID invalide." } }
        require(localProfileId in 1..5) { "Profil invalide." }
        val devices = listOf(deviceA, deviceB) + listOfNotNull(deviceC)
        require(devices.distinct().size == devices.size && (deviceId in devices || accountEnrolled)) {
            "Associe deux ou trois appareils distincts, dont celui-ci."
        }
        require(deviceC == null || deviceId == deviceC || accountEnrolled) {
            "Renseigne C uniquement sur le troisième appareil ; conserve les associations A/B."
        }
    }
}

/** Never serialize this object: its key belongs only to the encrypted local configuration. */
class PilotCaptureContext(
    val scope: PilotScope,
    val activationBarrierElapsedMs: Long,
    secret: ByteArray,
    val activationId: String = scope.epoch
) {
    private val key = SecretKeySpec(secret.copyOf(), "HmacSHA256")

    init {
        scope.validate()
        require(secret.size == 32) { "La clé HMAC doit contenir 256 bits." }
    }

    fun contentKey(storageKey: String): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            listOf("jojo-progress-pilot:v1", scope.profileBindingId, storageKey).forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(bytes.toByteArray()).hex()
        }
    }

    internal fun resumeMetadataKey(): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(("jojo-progress-resume:aes:v1\u0000" + scope.ownerId + "\u0000" + scope.profileBindingId).toByteArray(Charsets.UTF_8))
    }

    /** Explicit offline comparison of saved technical IDs; never reads playback progress. */
    internal fun localDiagnostic(challenge: String, journal: PilotJournal): PilotLocalDiagnosticReport {
        require(UUID.fromString(challenge).toString() == challenge) { "Défi UUID invalide." }
        require(journal.issue == null && (journal.epoch == scope.epoch || journal == PilotJournal())) {
            "Le journal ne correspond pas à cette association."
        }
        require(journal.fingerprints.size <= PilotJournal.MAX_KEYS)
        val contentKeys = journal.fingerprints.keys
        require(contentKeys.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Identifiant technique invalide." }
        val contentCodes = contentKeys.map {
            diagnosticHmac("jojo-progress-pilot:diagnostic-content:v1", challenge, scope.profileBindingId, it)
        }.sorted()
        return PilotLocalDiagnosticReport(challenge, scope.profileBindingId,
            diagnosticHmac("jojo-progress-pilot:diagnostic-key:v1", challenge),
            contentCodes.take(PilotLocalDiagnosticReport.MAX_DISPLAYED_CODES), contentKeys.size)
    }

    // Separate domains and framing prevent a diagnostic code from becoming a production content_key.
    internal fun pairingKeyCode(challenge: String): String {
        require(UUID.fromString(challenge).toString() == challenge) { "Défi UUID invalide." }
        return diagnosticHmac("jojo-progress-pilot:key-pair:v1", challenge, scope.ownerId,
            scope.profileBindingId, scope.deviceA, scope.deviceB)
    }

    private fun diagnosticHmac(vararg values: String): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            values.forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(bytes.toByteArray()).hex() }
    }

    override fun toString() = "PilotCaptureContext(key=redacted)"
}

/** Display only. Never add this report to settings, the delivery journal or an API payload. */
internal data class PilotLocalDiagnosticReport(
    val challenge: String,
    val profileBindingId: String,
    val keyCode: String,
    val contentCodes: List<String>,
    val contentCount: Int
) {
    override fun toString() = "PilotLocalDiagnosticReport(redacted)"

    companion object { const val MAX_DISPLAYED_CODES = 16 }
}

internal class PilotKeyMismatch : IllegalArgumentException("La clé ne correspond pas au code de référence de l’autre appareil.")
internal class PilotKeyCheckRequired : IllegalStateException("Vérifie la correspondance de la clé avant d’activer le miroir.")

/** Ephemeral receiver. Only a public wrapping key crosses ADB; the HMAC is never a command argument. */
internal class PilotKeyImportSession(private val bound: PilotScope,
                                     private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val created = clock()
    private val nonce = UUID.randomUUID()
    private val binding = MessageDigest.getInstance("SHA-256").digest(
        listOf("jojo-progress-pilot:key-import:v1", bound.ownerId, bound.profileBindingId,
            bound.deviceId, bound.epoch).joinToString("\u0000").toByteArray(Charsets.UTF_8))
    private val wrapping = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var consumed = false

    init { bound.validate() }

    fun publicToken(encode: (ByteArray) -> String): String =
        "JPK1|$nonce|${binding.hex()}|${encode(wrapping.public.encoded)}"

    @Synchronized fun unwrap(ciphertext: ByteArray, current: PilotScope): ByteArray {
        require(!consumed)
        consumed = true
        require(current == bound && clock() - created in 0..300_000 && ciphertext.size == 256)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, wrapping.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val packet = cipher.doFinal(ciphertext)
        try {
            val expectedNonce = ByteBuffer.allocate(16).putLong(nonce.mostSignificantBits).putLong(nonce.leastSignificantBits).array()
            require(packet.size == 80 && MessageDigest.isEqual(packet.copyOfRange(0, 16), expectedNonce) &&
                MessageDigest.isEqual(packet.copyOfRange(16, 48), binding))
            return packet.copyOfRange(48, 80)
        } finally { packet.fill(0) }
    }

    override fun toString() = "PilotKeyImportSession(privateKey=redacted)"
}

/** Explicit payload allowlist. Never send or serialize WatchProgress itself. */
data class PilotProgressPayload(
    val owner_id: String,
    val profile_binding_id: String,
    val device_id: String,
    val content_key: String,
    val media_kind: String,
    val position_ms: Long,
    val duration_ms: Long,
    val progress_percent: Float?,
    val source_time_ms: Long
)

data class PilotOperation(
    val operation_id: String,
    val revision: Long,
    val payload: PilotProgressPayload
)

data class PilotAcknowledgement(val operation_id: String, val revision: Long, val status: String)

/** Only delivery bookkeeping. No consumer of this type can provide playback resume data. */
data class PilotJournal(
    val epoch: String = "",
    val sequence: Long = 0,
    val fingerprints: Map<String, String> = emptyMap(),
    val capturedTimes: Map<String, Long> = emptyMap(),
    val pending: Map<String, PilotOperation> = emptyMap(),
    val issue: String? = null
) {
    fun enqueue(context: PilotCaptureContext, payload: PilotProgressPayload): PilotJournal {
        if (issue != null) return this
        if (epoch.isNotEmpty() && epoch != context.scope.epoch) return copy(issue = "ASSOCIATION_CHANGED")
        if (payload.source_time_ms < (capturedTimes[payload.content_key] ?: 0L)) return this
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(PilotJson.gson.toJson(payload).toByteArray(Charsets.UTF_8)).hex()
        if (fingerprints[payload.content_key] == fingerprint) return this
        if (sequence == Long.MAX_VALUE ||
            (payload.content_key !in fingerprints && fingerprints.size >= MAX_KEYS)) {
            return copy(issue = "OUTBOX_LIMIT")
        }
        val nextSequence = sequence + 1
        val next = copy(
            epoch = context.scope.epoch,
            sequence = nextSequence,
            fingerprints = fingerprints + (payload.content_key to fingerprint),
            capturedTimes = capturedTimes + (payload.content_key to payload.source_time_ms),
            pending = pending + (payload.content_key to PilotOperation(UUID.randomUUID().toString(), nextSequence, payload))
        )
        return if (PilotJson.encode(next).length <= MAX_JSON_CHARS) next else copy(issue = "OUTBOX_LIMIT")
    }

    fun acknowledge(epoch: String, sent: Collection<PilotOperation>, acks: Collection<PilotAcknowledgement>): PilotJournal {
        if (this.epoch != epoch) return this
        val confirmed = acks.filter { it.status in setOf("applied", "duplicate") }
            .associateBy { it.operation_id }
        val keys = sent.filter { operation ->
            val ack = confirmed[operation.operation_id]
            ack?.revision == operation.revision && pending[operation.payload.content_key] == operation
        }.map { it.payload.content_key }.toSet()
        return copy(pending = pending - keys)
    }

    /** Explicit key repair only: retire old hashes, retain the revision floor, never touch playback progress. */
    internal fun forKeyRepair(previousEpoch: String, newEpoch: String, sequenceFloor: Long): PilotJournal {
        require(UUID.fromString(previousEpoch).toString() == previousEpoch &&
            UUID.fromString(newEpoch).toString() == newEpoch && previousEpoch != newEpoch)
        require(issue == null && pending.isEmpty() && sequenceFloor >= 0 && sequence == sequenceFloor)
        require(epoch == previousEpoch || (this == PilotJournal() && sequenceFloor == 0L) ||
            (epoch == newEpoch && fingerprints.isEmpty() && capturedTimes.isEmpty()))
        return PilotJournal(epoch = newEpoch, sequence = sequence)
    }

    companion object {
        const val MAX_KEYS = 4096
        const val MAX_JSON_CHARS = 4 * 1024 * 1024
    }
}

object PilotJson {
    internal val gson = Gson()
    fun encode(journal: PilotJournal): String = gson.toJson(journal)
    fun decode(json: String?): PilotJournal = if (json == null) PilotJournal() else
        gson.fromJson(json, PilotJournal::class.java).also { journal ->
            require(journal.sequence >= 0 && journal.pending.size <= PilotJournal.MAX_KEYS)
            require(journal.fingerprints.size <= PilotJournal.MAX_KEYS)
            require(journal.capturedTimes.size <= PilotJournal.MAX_KEYS)
        }
}

object SupabaseProgressProjector {
    fun checkpoint(context: PilotCaptureContext, event: PilotPlaybackCheckpoint?, progress: WatchProgress): PilotProgressPayload? {
        if (event == null || event.createdAtElapsedMs <= context.activationBarrierElapsedMs ||
            event.createdAtMs != progress.lastWatched || progress.isLiveChannel ||
            progress.position < 0 || progress.duration < 0 || progress.lastWatched <= 0 ||
            (progress.position <= 1 && progress.duration == 1L)) return null
        if (progress.progressPercent?.let { !it.isFinite() || it !in 0f..100f } == true) return null
        val kind = mediaKind(progress) ?: return null
        return PilotProgressPayload(context.scope.ownerId, context.scope.profileBindingId, context.scope.deviceId,
            context.contentKey(progress.storageKey), kind, progress.position, progress.duration,
            progress.progressPercent, progress.lastWatched)
    }

    private fun mediaKind(progress: WatchProgress): String? = when {
        progress.contentType.equals("series", true) && progress.season != null && progress.episode != null -> "episode"
        progress.contentType.equals("movie", true) -> "film"
        progress.contentType.equals("freebox", true) || progress.contentType.equals("video", true) -> "file"
        else -> null
    }

}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** This interface intentionally cannot read or mutate playback progress. */
interface PilotDeliveryQueue {
    fun observe(profileId: Int): Flow<PilotJournal>
    suspend fun profileInstanceId(profileId: Int): String?
    suspend fun acknowledge(scope: PilotScope, sent: Collection<PilotOperation>, acks: Collection<PilotAcknowledgement>)
    suspend fun repairKeyEpoch(scope: PilotScope, previousEpoch: String, sequenceFloor: Long): PilotJournal =
        error("Réassociation indisponible dans cette file.")
}

internal interface PilotDeliveryAccess {
    fun current(): PilotCaptureContext?
    fun endpoint(): PilotEndpoint
    fun session(): PilotAuthSession
    fun requestPermit(context: PilotCaptureContext): PilotRequestPermit
    fun registeredSessionId(): String
    fun nextAttemptMs(): Long
    fun replaceSession(previous: PilotAuthSession, next: PilotAuthSession)
    fun registered(scope: PilotScope, sessionId: String)
    fun delivered()
    fun retry(code: String, retryAtMs: Long)
    fun pause(code: String)
}

internal enum class PilotDeliveryResult { DONE, RETRY, STOPPED }

/** The only local writer available to this engine is the delivery acknowledgement. */
internal class PilotDeliveryEngine(
    private val access: PilotDeliveryAccess,
    private val queue: PilotDeliveryQueue,
    private val api: SupabaseProgressApi,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    suspend fun flush(): PilotDeliveryResult {
        val context = access.current() ?: return PilotDeliveryResult.STOPPED
        val scope = context.scope
        return try {
            if (queue.profileInstanceId(scope.localProfileId) != scope.localProfileInstanceId) {
                access.pause("PROFILE_ASSOCIATION_CHANGED")
                return PilotDeliveryResult.STOPPED
            }
            val journal = queue.observe(scope.localProfileId).first()
            if (journal.issue != null || (journal.epoch.isNotEmpty() && journal.epoch != scope.epoch)) {
                access.pause(journal.issue ?: "ASSOCIATION_CHANGED")
                return PilotDeliveryResult.STOPPED
            }
            val operations = journal.pending.values.sortedBy { it.revision }.take(50)
            if (operations.isEmpty()) return PilotDeliveryResult.DONE
            if (access.nextAttemptMs() > nowMs()) return PilotDeliveryResult.RETRY
            var session = access.session()
            val endpoint = access.endpoint()
            val permit = access.requestPermit(context)
            if (session.expiresAtSeconds <= nowMs() / 1000 + 30) {
                val refreshed = api.refreshSession(endpoint, session, permit)
                access.replaceSession(session, refreshed)
                session = refreshed
            }
            if (access.current()?.activationId != context.activationId) return PilotDeliveryResult.STOPPED
            if (access.registeredSessionId() != session.sessionId) {
                api.registerDevice(endpoint, session, scope, permit)
                access.registered(scope, session.sessionId)
            }
            if (access.current()?.activationId != context.activationId) return PilotDeliveryResult.STOPPED
            val acks = api.writeBatch(endpoint, session, scope, operations, permit)
            if (access.current()?.activationId != context.activationId) return PilotDeliveryResult.STOPPED
            queue.acknowledge(scope, operations, acks)
            if (acks.any { it.status == "stale" || it.status == "conflict" }) {
                access.pause("REMOTE_REVISION_CONFLICT")
                PilotDeliveryResult.STOPPED
            } else {
                access.delivered()
                PilotDeliveryResult.DONE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PilotRequestsStopped) {
            PilotDeliveryResult.STOPPED
        } catch (failure: PilotHttpFailure) {
            if (failure.status in setOf(401, 403) || failure.status in 400..499 && failure.status !in setOf(408, 429)) {
                access.pause("HTTP_${failure.status}")
                PilotDeliveryResult.STOPPED
            } else {
                access.retry("HTTP_${failure.status}", nowMs() + maxOf(30_000, failure.retryAfterMs ?: 0))
                PilotDeliveryResult.RETRY
            }
        } catch (_: IllegalArgumentException) {
            access.pause("PILOT_CONFIGURATION_INVALID")
            PilotDeliveryResult.STOPPED
        } catch (_: IllegalStateException) {
            access.pause("PILOT_STATE_INVALID")
            PilotDeliveryResult.STOPPED
        } catch (_: Exception) {
            // Do not put exception messages, request bodies or tokens in diagnostics.
            access.retry("DELIVERY_FAILED", nowMs() + 30_000)
            PilotDeliveryResult.RETRY
        }
    }
}
