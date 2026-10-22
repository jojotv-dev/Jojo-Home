package com.jojotv.home.progresspilot

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.jojotv.home.BuildConfig
import com.jojotv.home.iptv.iptvDecryptStorage
import com.jojotv.home.iptv.iptvEncryptStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded local diagnostics: messages never contain identity values, hashes or keys. */
internal enum class PilotKeyCheckIssue(val feedback: String) {
    UNAVAILABLE("Le stockage local du pilote est indisponible."),
    STATE_CHANGED("La configuration a changé pendant le contrôle. Relance le contrôle."),
    MODE_ACTIVE("Désactive le miroir avant de contrôler la clé."),
    SCOPE_MISSING("L'association locale du pilote est absente."),
    SCOPE_INVALID("L'association locale du pilote est invalide."),
    DEVICE_CHANGED("L'installation ne correspond plus à l'appareil associé."),
    ACCOUNT_CHANGED("La session Supabase ne correspond pas au compte associé."),
    NAS_UNAVAILABLE("L'identité du compte NAS n'est pas disponible localement."),
    NAS_CHANGED("Le compte ou l'adresse NAS diffère de l'association du pilote."),
    PROFILE_CHANGED("Le profil actif ne correspond pas au profil associé."),
    PROFILE_INSTANCE_MISSING("L'identifiant technique du profil associé est absent. Une réassociation explicite est nécessaire."),
    PROFILE_RECREATED("L'identifiant technique du profil a changé. Une réassociation explicite est nécessaire."),
    KEY_INVALID("La clé HMAC locale est absente ou invalide.")
}

internal fun PilotScope.checkKeyProfileInstance(current: String?) {
    if (current == null) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.PROFILE_INSTANCE_MISSING)
    if (current != localProfileInstanceId) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.PROFILE_RECREATED)
}

internal class PilotKeyCheckBlocked(val issue: PilotKeyCheckIssue) :
    IllegalStateException("Contrôle HMAC bloqué [${issue.name}] : ${issue.feedback}")

data class PilotLocalSettings(
    val deviceId: String = UUID.randomUUID().toString(),
    val mode: PilotMode = PilotMode.NAS_ONLY,
    val endpoint: PilotEndpoint? = null,
    val session: PilotAuthSession? = null,
    val scope: PilotScope? = null,
    val hmacKeyBase64: String = "",
    val nasAuthorityDigest: String = "",
    val backendApproved: Boolean = false,
    val everActivated: Boolean = false,
    val registeredSessionId: String = "",
    val nextAttemptMs: Long = 0,
    val lastAckMs: Long = 0,
    val lastIssue: String? = null,
    val keyCheckEpoch: String = "",
    val keyCheckChallenge: String = "",
    val keyCheckCode: String = "",
    val keyRepairFromEpoch: String = "",
    val keyRepairSequence: Long = 0,
    val cloudKeyEpoch: String = "",
    val cloudActivationId: String = "",
    val cloudKeyBindingEpoch: String = ""
) {
    // Mirror activation is process-local. The explicitly selected cloud source survives a restart.
    internal fun stoppedForPersistence() = if (mode == PilotMode.SUPABASE_PROGRESS) this else copy(mode = PilotMode.NAS_ONLY)

    internal fun validateCloudGeneration(candidate: String) {
        require(UUID.fromString(candidate).toString() == candidate)
        require(cloudKeyEpoch.orEmpty().isEmpty() || cloudKeyEpoch != candidate || cloudKeyBindingEpoch.orEmpty() == scope?.epoch) {
            "Une nouvelle génération cloud doit être approuvée après la réassociation de la clé."
        }
    }

    internal fun validateSignInTarget(candidate: PilotEndpoint) {
        candidate.validate()
        require(mode == PilotMode.NAS_ONLY) { "Désactive le pilote avant de te connecter." }
        if (everActivated) require(endpoint == candidate) { "Le projet de ce pilote est figé." }
    }

    internal fun validateKeyScope(currentNasAuthority: String): PilotScope {
        if (mode != PilotMode.NAS_ONLY) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.MODE_ACTIVE)
        val bound = scope ?: throw PilotKeyCheckBlocked(PilotKeyCheckIssue.SCOPE_MISSING)
        try { bound.validate() }
        catch (_: IllegalArgumentException) { throw PilotKeyCheckBlocked(PilotKeyCheckIssue.SCOPE_INVALID) }
        if (deviceId != bound.deviceId) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.DEVICE_CHANGED)
        if (session?.ownerId != bound.ownerId) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.ACCOUNT_CHANGED)
        if (currentNasAuthority.isEmpty()) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.NAS_UNAVAILABLE)
        if (nasAuthorityDigest != currentNasAuthority) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.NAS_CHANGED)
        return bound
    }

    internal fun validateLocalDiagnostic(journal: PilotJournal): PilotScope {
        require(mode == PilotMode.NAS_ONLY) { "Le contrôle local exige NAS_ONLY." }
        val bound = requireNotNull(scope) { "Association locale absente." }
        bound.validate()
        require(session?.ownerId == bound.ownerId && deviceId == bound.deviceId) { "Compte ou appareil différent." }
        require(keyRepairFromEpoch.isEmpty()) { "Termine la réassociation de la clé." }
        require(journal.issue == null && (journal.epoch == bound.epoch || journal == PilotJournal())) {
            "Le journal ne correspond pas à cette association."
        }
        return bound
    }

    internal fun validateKeyRepairJournal(journal: PilotJournal) {
        require(mode == PilotMode.NAS_ONLY)
        require(journal.issue == null && journal.pending.isEmpty()) { "La file doit être saine et vide." }
        require(journal.epoch.isNotEmpty() || !everActivated) { "Compteur technique manquant." }
        if (keyRepairFromEpoch.isEmpty()) validateLocalDiagnostic(journal)
    }

    /** Explicit repair of key material and, optionally, the same primary profile's local marker. */
    internal fun plannedKeyRepair(journal: PilotJournal, normalizedKey: String, context: PilotCaptureContext,
                                  challenge: String, reference: String, instance: String): PilotLocalSettings {
        validateKeyRepairJournal(journal)
        val bound = requireNotNull(scope)
        require(UUID.fromString(instance).toString() == instance)
        // Primary profile cannot be deleted through ProfileManager. Secondary reassignment needs review.
        require(instance == bound.localProfileInstanceId || bound.localProfileId == 1)
        val checked = checkedKey(context, challenge, reference)
        if (keyRepairFromEpoch.isNotEmpty()) {
            require(hmacKeyBase64 == normalizedKey && instance == bound.localProfileInstanceId) {
                "Termine la réparation en attente avec la même clé et la même instance."
            }
            return this
        }
        if (hmacKeyBase64 == normalizedKey && instance == bound.localProfileInstanceId) return checked
        return copy(scope = bound.copy(localProfileInstanceId = instance, epoch = UUID.randomUUID().toString()),
            hmacKeyBase64 = normalizedKey, keyCheckEpoch = "", keyCheckChallenge = checked.keyCheckChallenge,
            keyCheckCode = checked.keyCheckCode, keyRepairFromEpoch = bound.epoch, keyRepairSequence = journal.sequence,
            registeredSessionId = "", nextAttemptMs = 0, lastIssue = "KEY_REPAIR_PENDING")
    }

    internal fun withoutKeyCheck() = copy(keyCheckEpoch = "", keyCheckChallenge = "", keyCheckCode = "")

    internal fun checkedKey(context: PilotCaptureContext, challenge: String, reference: String): PilotLocalSettings {
        require(context.scope == scope)
        val code = reference.trim().lowercase(java.util.Locale.ROOT)
        require(code.matches(Regex("[0-9a-f]{64}"))) { "Code de référence invalide." }
        if (!MessageDigest.isEqual(context.pairingKeyCode(challenge).toByteArray(Charsets.US_ASCII),
                code.toByteArray(Charsets.US_ASCII))) throw PilotKeyMismatch()
        return copy(keyCheckEpoch = context.scope.epoch, keyCheckChallenge = challenge, keyCheckCode = code)
    }

    internal fun keyCheckMatches(context: PilotCaptureContext): Boolean = try {
        keyRepairFromEpoch.isEmpty() && keyCheckEpoch.isNotEmpty() && keyCheckEpoch == scope?.epoch &&
            context.scope == scope && checkedKey(context, keyCheckChallenge, keyCheckCode) == this
    } catch (_: Exception) { false }

    override fun toString() = "PilotLocalSettings(mode=$mode, secrets=redacted)"
}

/** Dedicated encrypted configuration; nothing is stored in home_settings or NAS backups. */
@Singleton
class SupabaseProgressPilotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val requests: PilotRequestGate
) {
    private val lock = Any()
    private val file = AtomicFile(File(context.noBackupFilesDir, "progress-pilot/config.bin"))
    private val gson = Gson()
    private val deliveryStores = mutableMapOf<Int, DataStore<Preferences>>()
    private val deliveryJobs by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private var storageHealthy = true
    private var currentNasAuthority = ""
    private var activationBarrier = SystemClock.elapsedRealtime()
    private var activationId = ""
    private var observedCloudProfile: Int? = null
    private var deliveryPermit: PilotRequestPermit? = null
    private val key: SecretKey by lazy {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            check(!file.baseFile.exists()) { "Clé locale du pilote indisponible." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }

    private val state = MutableStateFlow(load())
    val settings: StateFlow<PilotLocalSettings> = state.asStateFlow()

    /** Separate file and coroutine scope: delivery failures cannot fail a NAS/local save. */
    fun deliveryStore(profileId: Int): DataStore<Preferences> = synchronized(lock) {
        checkAvailable()
        require(profileId in 1..5)
        deliveryStores.getOrPut(profileId) {
            PreferenceDataStoreFactory.create(scope = deliveryJobs) {
                File(context.noBackupFilesDir, "progress-pilot/delivery-$profileId.preferences_pb")
            }
        }
    }

    fun ensureDeviceId(): String = synchronized(lock) {
        checkAvailable()
        if (!file.baseFile.exists()) save(state.value)
        state.value.deviceId
    }

    fun generateLinkKey(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)

    fun authorizeSignIn(endpoint: PilotEndpoint): PilotRequestPermit = synchronized(lock) {
        checkAvailable()
        state.value.validateSignInTarget(endpoint) // Before credentials reach any HTTP call.
        requests.allow(endpoint, state.value.scope?.ownerId.takeIf { state.value.everActivated },
            setOf("auth/v1/token?grant_type=password"))
    }

    internal fun authorizeAccountSignIn(endpoint: PilotEndpoint): PilotRequestPermit = synchronized(lock) {
        checkAvailable(); endpoint.validate()
        require(state.value.scope == null || state.value.endpoint == endpoint) { "ACCOUNT_RECOVERY_IDENTITY_CHANGED" }
        requests.allow(endpoint, state.value.scope?.ownerId, setOf("auth/v1/token?grant_type=password"))
    }

    internal fun saveAccountSession(endpoint: PilotEndpoint, session: PilotAuthSession) = synchronized(lock) {
        checkAvailable()
        val next = state.value.withAccountSession(endpoint, session)
        requests.stop(); deliveryPermit = null
        save(next)
    }

    internal fun installAccountRecovery(grant: AccountRecoveryGrant, secret: ByteArray, instance: String) = synchronized(lock) {
        checkAvailable()
        save(state.value.recoveredAccount(grant, secret, instance))
    }

    fun authorizeSignOut(endpoint: PilotEndpoint, session: PilotAuthSession): PilotRequestPermit = synchronized(lock) {
        checkAvailable()
        require(state.value.mode == PilotMode.NAS_ONLY && state.value.endpoint == endpoint && state.value.session === session)
        requests.allow(endpoint, session.ownerId, setOf("auth/v1/logout?scope=local"))
    }

    fun finishRequest(permit: PilotRequestPermit) = requests.finish(permit)

    internal fun accountRequest(endpoint: PilotEndpoint, session: PilotAuthSession): PilotRequestPermit = synchronized(lock) {
        checkAvailable()
        require(state.value.endpoint == endpoint && state.value.session === session) { "ACCOUNT_SESSION_CHANGED" }
        requests.allow(endpoint, session.ownerId, accountRpcNames.map { "rest/v1/rpc/$it" }.toSet() +
            "auth/v1/token?grant_type=refresh_token")
    }

    /** A copy for scoped encryption only. Callers must clear it; no NAS credential is involved. */
    internal fun accountContentKey(owner: String): ByteArray = synchronized(lock) {
        checkAvailable()
        val current = state.value
        require(current.session?.ownerId == owner && current.scope?.ownerId == owner &&
            current.keyCheckMatches(keyContext(current))) { "ACCOUNT_CONTENT_KEY_REQUIRED" }
        Base64.decode(current.hmacKeyBase64, Base64.DEFAULT).also { require(it.size == 32) }
    }

    internal fun installAccountEnrollment(grant: AccountPairGrant, secret: ByteArray, profile: Int, instance: String) = synchronized(lock) {
        checkAvailable(); val previous = state.value
        require(previous.mode == PilotMode.NAS_ONLY && !previous.everActivated && previous.scope == null &&
            previous.session?.ownerId == grant.request.owner && previous.session.sessionId == grant.request.session &&
            previous.deviceId == grant.request.device && secret.size == 32)
        val bound = PilotScope(grant.request.owner, grant.profile, profile, instance, previous.deviceId,
            grant.device_a, grant.device_b, UUID.randomUUID().toString(), accountEnrolled = true).also { it.validate() }
        val candidate = previous.copy(scope = bound, hmacKeyBase64 = Base64.encodeToString(secret, Base64.NO_WRAP),
            nasAuthorityDigest = currentNasAuthority, backendApproved = true, cloudKeyEpoch = grant.key_epoch,
            cloudKeyBindingEpoch = bound.epoch, registeredSessionId = "", lastIssue = null)
        val capture = keyContext(candidate); val challenge = grant.request.nonce
        save(candidate.checkedKey(capture, challenge, capture.pairingKeyCode(challenge)))
    }

    fun requestPermit(capture: PilotCaptureContext): PilotRequestPermit = synchronized(lock) {
        if (captureContext()?.activationId != capture.activationId || state.value.scope != capture.scope)
            throw PilotRequestsStopped()
        deliveryPermit ?: throw PilotRequestsStopped()
    }

    /** Reads only the NAS authority identity. Its bearer token is never accepted by this store. */
    fun updateNasAuthority(baseUrl: String, email: String?, authenticated: Boolean) = synchronized(lock) {
        currentNasAuthority = if (!authenticated || email.isNullOrBlank()) "" else
            MessageDigest.getInstance("SHA-256").digest(
                (baseUrl.trim().trimEnd('/') + "\u0000" + email.trim().lowercase(java.util.Locale.ROOT))
                    .toByteArray(Charsets.UTF_8)).hex()
        if (state.value.mode == PilotMode.NAS_WITH_SUPABASE_MIRROR && currentNasAuthority != state.value.nasAuthorityDigest) {
            disable("NAS_ASSOCIATION_CHANGED")
        }
    }

    fun saveSession(endpoint: PilotEndpoint, session: PilotAuthSession) = synchronized(lock) {
        checkAvailable()
        endpoint.validate()
        val previous = state.value
        require(previous.mode == PilotMode.NAS_ONLY) { "Désactive le pilote avant de changer de session." }
        if (previous.everActivated) require(previous.endpoint == endpoint && previous.scope?.ownerId == session.ownerId) {
            "Le projet et le propriétaire de ce pilote sont figés."
        }
        save(previous.copy(endpoint = endpoint, session = session, registeredSessionId = "", lastIssue = null).withoutKeyCheck())
    }

    fun configure(profileId: Int, profileInstance: String, profileBinding: String, deviceA: String,
                  deviceB: String, linkKey: String, backendApproved: Boolean,
                  challenge: String = "", reference: String = "", deviceC: String? = null) = synchronized(lock) {
        checkAvailable()
        val previous = state.value
        require(previous.mode == PilotMode.NAS_ONLY) { "Désactive le pilote avant de modifier l'association." }
        require(previous.keyRepairFromEpoch.isEmpty()) { "Termine la réassociation de la clé." }
        val session = previous.session ?: error("Connecte le compte Supabase de test.")
        require(currentNasAuthority.isNotEmpty()) { "Une session NAS de référence est requise." }
        val secret = try { Base64.decode(linkKey.trim(), Base64.DEFAULT) } catch (_: Exception) { error("Clé HMAC invalide.") }
        require(secret.size == 32) { "La clé HMAC doit contenir 256 bits." }
        val scope = PilotScope(session.ownerId, profileBinding.trim(), profileId, profileInstance,
            previous.deviceId, deviceA.trim(), deviceB.trim(), previous.scope?.epoch ?: UUID.randomUUID().toString(),
            deviceC?.trim()?.takeIf { it.isNotEmpty() })
        scope.validate()
        val normalizedKey = Base64.encodeToString(secret, Base64.NO_WRAP)
        secret.fill(0)
        if (scope.deviceC != null && reference.isBlank()) throw PilotKeyCheckRequired()
        val next = previous.copy(scope = scope, hmacKeyBase64 = normalizedKey, nasAuthorityDigest = currentNasAuthority,
            backendApproved = backendApproved, registeredSessionId = "", nextAttemptMs = 0, lastIssue = null).withoutKeyCheck()
        val checked = if (reference.isBlank()) next else next.checkedKey(keyContext(next), challenge, reference)
        if (previous.everActivated) require(previous.scope == scope && previous.hmacKeyBase64 == normalizedKey &&
            previous.nasAuthorityDigest == currentNasAuthority) { "L'association de ce pilote est figée après activation." }
        save(checked)
    }

    fun activate() = synchronized(lock) {
        checkAvailable()
        val configured = state.value
        require(configured.scope?.accountEnrolled != true) { "Cet appareil associé utilise uniquement la progression cloud, après sélection explicite." }
        if (configured.keyCheckEpoch.isEmpty() || configured.keyRepairFromEpoch.isNotEmpty()) throw PilotKeyCheckRequired()
        val verified = keyContext(configured)
        if (!configured.keyCheckMatches(verified)) throw PilotKeyCheckRequired()
        require(configured.backendApproved) { "La migration Supabase doit d'abord être validée et installée." }
        require(configured.scope != null && configured.session?.ownerId == configured.scope.ownerId &&
            configured.nasAuthorityDigest == currentNasAuthority && currentNasAuthority.isNotEmpty()) {
            "Association du profil, des appareils ou du compte invalide."
        }
        activationBarrier = SystemClock.elapsedRealtime()
        requests.stop()
        deliveryPermit = null
        save(configured.copy(mode = PilotMode.NAS_WITH_SUPABASE_MIRROR, everActivated = true,
            nextAttemptMs = 0, lastIssue = null))
        activationId = UUID.randomUUID().toString()
        deliveryPermit = requests.allow(configured.endpoint ?: error("Configuration absente."), configured.scope.ownerId,
            setOf("auth/v1/token?grant_type=refresh_token", configured.scope.registrationPath, "rest/v1/rpc/pilot_write_progress"),
            configured.scope, activationId)
    }

    fun disable(issue: String? = null) = synchronized(lock) {
        requests.stop() // Close the gate and cancel prepared calls before releasing the lock.
        deliveryPermit = null
        activationId = ""
        val stopped = state.value.copy(mode = PilotMode.NAS_ONLY, lastIssue = issue)
        state.value = stopped // Fail closed in memory even if writing the configuration fails.
        save(stopped)
    }

    /** Explicitly selected source; unrelated to the availability of the NAS session. */
    internal fun authorizeCloudProbe(expected: PilotLocalSettings): PilotRequestPermit = synchronized(lock) {
        checkAvailable()
        require(state.value === expected && expected.mode == PilotMode.NAS_ONLY)
        val bound = requireNotNull(expected.scope)
        require(expected.keyCheckMatches(keyContext(expected)) && expected.session?.ownerId == bound.ownerId)
        requests.allow(requireNotNull(expected.endpoint), bound.ownerId,
            setOf("auth/v1/token?grant_type=refresh_token", "rest/v1/rpc/progress_sync_read_v2"), bound)
    }

    internal fun activateCloud(keyEpoch: String, expected: PilotLocalSettings) = synchronized(lock) {
        checkAvailable()
        val configured = state.value
        require(configured.scope == expected.scope && configured.session?.sessionId == expected.session?.sessionId &&
            configured.endpoint == expected.endpoint && configured.hmacKeyBase64 == expected.hmacKeyBase64)
        require(configured.mode == PilotMode.NAS_ONLY)
        configured.validateCloudGeneration(keyEpoch)
        val bound = requireNotNull(configured.scope)
        require(configured.session?.ownerId == bound.ownerId && configured.deviceId == bound.deviceId)
        require(configured.keyCheckMatches(keyContext(configured))) { "Vérifie la clé HMAC avant activation." }
        requests.stop()
        activationId = UUID.randomUUID().toString()
        deliveryPermit = null
        save(configured.copy(mode = PilotMode.SUPABASE_PROGRESS, cloudKeyEpoch = keyEpoch,
            cloudActivationId = activationId, cloudKeyBindingEpoch = bound.epoch,
            everActivated = true, lastIssue = null, nextAttemptMs = 0))
    }

    internal fun cloudContext(): PilotCaptureContext? = synchronized(lock) {
        val current = state.value
        if (!BuildConfig.JOJO_PROGRESS_PILOT_COMPILED || !storageHealthy || current.mode != PilotMode.SUPABASE_PROGRESS ||
            current.cloudKeyEpoch.isBlank() || current.cloudKeyBindingEpoch != current.scope?.epoch ||
            current.session?.ownerId != current.scope?.ownerId) return null
        val bound = current.scope ?: return null
        if (current.deviceId != bound.deviceId) return null
        val checked = keyContext(current)
        if (!current.keyCheckMatches(checked)) return null
        if (activationId.isEmpty()) activationId = UUID.randomUUID().toString()
        PilotCaptureContext(bound, Long.MAX_VALUE, Base64.decode(current.hmacKeyBase64, Base64.DEFAULT), activationId)
    }

    internal fun cloudRequestPermit(capture: PilotCaptureContext): PilotRequestPermit = synchronized(lock) {
        if (cloudContext()?.activationId != capture.activationId || state.value.scope != capture.scope) throw PilotRequestsStopped()
        deliveryPermit ?: requests.allow(requireNotNull(state.value.endpoint), capture.scope.ownerId,
            setOf("auth/v1/token?grant_type=refresh_token", "rest/v1/rpc/progress_sync_read_v2", "rest/v1/rpc/progress_sync_write_v2"),
            capture.scope, activationId).also { deliveryPermit = it }
    }

    internal fun cloudProfileChanged(profileId: Int) = synchronized(lock) {
        val previous = observedCloudProfile
        observedCloudProfile = profileId
        if (previous != null && previous != profileId && state.value.mode == PilotMode.SUPABASE_PROGRESS) {
            requests.stop()
            deliveryPermit = null
            activationId = UUID.randomUUID().toString()
            save(state.value.copy(cloudActivationId = activationId))
        }
    }

    fun captureContext(): PilotCaptureContext? = synchronized(lock) {
        val current = state.value
        if (!BuildConfig.JOJO_PROGRESS_PILOT_COMPILED || !storageHealthy ||
            current.mode != PilotMode.NAS_WITH_SUPABASE_MIRROR || !current.backendApproved ||
            currentNasAuthority.isEmpty() || currentNasAuthority != current.nasAuthorityDigest ||
            current.session?.ownerId != current.scope?.ownerId) return null
        val scope = current.scope ?: return null
        try {
            val capture = PilotCaptureContext(scope, activationBarrier, Base64.decode(current.hmacKeyBase64, Base64.DEFAULT), activationId)
            capture.takeIf { current.keyCheckMatches(it) }
        }
        catch (_: Exception) { null }
    }

    private fun keyContext(configured: PilotLocalSettings, candidate: String = configured.hmacKeyBase64): PilotCaptureContext {
        val secret = try { Base64.decode(candidate.trim(), Base64.DEFAULT) }
            catch (_: IllegalArgumentException) { throw PilotKeyCheckBlocked(PilotKeyCheckIssue.KEY_INVALID) }
        try {
            if (secret.size != 32) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.KEY_INVALID)
            return PilotCaptureContext(requireNotNull(configured.scope), Long.MAX_VALUE, secret)
        } finally { secret.fill(0) }
    }

    internal fun keyScope(expected: PilotLocalSettings): PilotScope = synchronized(lock) {
        try { checkAvailable() }
        catch (_: IllegalStateException) { throw PilotKeyCheckBlocked(PilotKeyCheckIssue.UNAVAILABLE) }
        if (state.value !== expected) throw PilotKeyCheckBlocked(PilotKeyCheckIssue.STATE_CHANGED)
        expected.validateKeyScope(currentNasAuthority)
    }

    internal fun confirmKey(expected: PilotLocalSettings, journal: PilotJournal, challenge: String, reference: String) = synchronized(lock) {
        keyScope(expected)
        save(expected.withoutKeyCheck()) // A failed attempt must not leave a previous confirmation usable.
        val current = state.value
        current.validateLocalDiagnostic(journal)
        save(current.checkedKey(keyContext(current), challenge, reference))
    }

    internal fun localPairingCode(expected: PilotLocalSettings, challenge: String): String = synchronized(lock) {
        keyScope(expected)
        keyContext(expected).pairingKeyCode(challenge)
    }

    internal fun candidateKeyMatches(expected: PilotLocalSettings, candidate: String,
                                     challenge: String, reference: String) = synchronized(lock) {
        keyScope(expected)
        expected.checkedKey(keyContext(expected, candidate), challenge, reference)
        Unit
    }

    internal fun beginKeyRepair(expected: PilotLocalSettings, journal: PilotJournal, candidate: String,
                                challenge: String, reference: String,
                                instance: String = requireNotNull(expected.scope).localProfileInstanceId): PilotLocalSettings = synchronized(lock) {
        keyScope(expected)
        requests.stop()
        deliveryPermit = null
        activationId = ""
        save(expected.withoutKeyCheck())
        val current = state.value
        current.validateKeyRepairJournal(journal)
        val secret = try { Base64.decode(candidate.trim(), Base64.DEFAULT) } catch (_: Exception) { error("Clé HMAC invalide.") }
        val normalized = try {
            require(secret.size == 32) { "Clé HMAC invalide." }
            Base64.encodeToString(secret, Base64.NO_WRAP)
        } finally { secret.fill(0) }
        val next = current.plannedKeyRepair(journal, normalized, keyContext(current, normalized), challenge, reference, instance)
        if (next !== current) save(next)
        state.value
    }

    internal fun finishKeyRepair(expected: PilotLocalSettings, journal: PilotJournal, challenge: String, reference: String) = synchronized(lock) {
        val bound = keyScope(expected)
        require(expected.keyRepairFromEpoch.isNotEmpty() && journal.epoch == bound.epoch &&
            journal.sequence == expected.keyRepairSequence && journal.pending.isEmpty() && journal.issue == null &&
            journal.fingerprints.isEmpty() && journal.capturedTimes.isEmpty())
        val completed = expected.copy(keyRepairFromEpoch = "", keyRepairSequence = 0, lastIssue = null)
        save(completed.checkedKey(keyContext(completed), challenge, reference))
    }

    /** Uses the saved key, not edited form values. No save(), request permit or mode change. */
    internal fun localDiagnostic(expected: PilotLocalSettings, challenge: String,
                                 journal: PilotJournal): PilotLocalDiagnosticReport = synchronized(lock) {
        checkAvailable()
        require(state.value === expected) { "La configuration a changé pendant le contrôle." }
        val bound = expected.validateLocalDiagnostic(journal)
        val secret = Base64.decode(expected.hmacKeyBase64, Base64.DEFAULT)
        try { PilotCaptureContext(bound, Long.MAX_VALUE, secret).localDiagnostic(challenge, journal) }
        finally { secret.fill(0) }
    }

    fun replaceSession(expected: PilotAuthSession, next: PilotAuthSession) = synchronized(lock) {
        require(state.value.session === expected && expected.ownerId == next.ownerId && expected.sessionId == next.sessionId) {
            "La session du pilote a changé."
        }
        save(state.value.copy(session = next))
    }

    fun clearSession() = synchronized(lock) {
        requests.stop()
        deliveryPermit = null
        activationId = ""
        save(state.value.copy(mode = PilotMode.NAS_ONLY, session = null, registeredSessionId = "", lastIssue = null).withoutKeyCheck())
    }

    fun markRegistered(expected: PilotScope, sessionId: String) = synchronized(lock) {
        if (state.value.scope == expected && state.value.session?.sessionId == sessionId)
            save(state.value.copy(registeredSessionId = sessionId))
    }

    fun recordDelivery(issue: String?, retryAtMs: Long = 0, acknowledged: Boolean = false) = synchronized(lock) {
        save(state.value.copy(lastIssue = issue, nextAttemptMs = retryAtMs,
            lastAckMs = if (acknowledged) System.currentTimeMillis() else state.value.lastAckMs))
    }

    private fun checkAvailable() {
        check(BuildConfig.JOJO_PROGRESS_PILOT_COMPILED && storageHealthy) { "Pilote indisponible dans cette configuration." }
    }

    private fun load(): PilotLocalSettings {
        if (!file.baseFile.exists()) return PilotLocalSettings()
        return try {
            gson.fromJson(iptvDecryptStorage(file.readFully(), ::unwrap).toString(Charsets.UTF_8), PilotLocalSettings::class.java)
                .also { settings ->
                    require(UUID.fromString(settings.deviceId).toString() == settings.deviceId)
                    settings.scope?.validate()
                    settings.endpoint?.validate()
                }.let { loaded ->
                    // Missing fields in an older Gson configuration are null, despite Kotlin defaults.
                    loaded.copy(cloudKeyEpoch = loaded.cloudKeyEpoch.orEmpty(), cloudKeyBindingEpoch = loaded.cloudKeyBindingEpoch.orEmpty(),
                        cloudActivationId = if (loaded.mode == PilotMode.SUPABASE_PROGRESS) UUID.randomUUID().toString() else "")
                }.stoppedForPersistence()
        } catch (_: Exception) {
            storageHealthy = false
            PilotLocalSettings(lastIssue = "LOCAL_STORE_UNREADABLE")
        }
    }

    private fun save(next: PilotLocalSettings) {
        check(storageHealthy) { "Stockage du pilote indisponible." }
        check(file.baseFile.parentFile?.mkdirs() == true || file.baseFile.parentFile?.isDirectory == true)
        val bytes = iptvEncryptStorage(gson.toJson(next.stoppedForPersistence()).toByteArray(Charsets.UTF_8), ::wrap)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
            state.value = next
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun wrap(input: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        byteArrayOf(iv.size.toByte()) + iv + doFinal(input)
    }

    private fun unwrap(input: ByteArray): ByteArray {
        require(input.size > 13 && input[0].toInt() == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, input.copyOfRange(1, 13)))
            doFinal(input, 13, input.size - 13)
        }
    }

    companion object { private const val KEY_ALIAS = "jojo_progress_pilot_v1" }
}
