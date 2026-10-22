package com.jojotv.home.progresspilot

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal data class AccountPairRequest(val id: String, val owner: String, val installation: String,
    val device: String, val session: String, val home_profile: String, val nonce: String,
    val public_key: String, val expires_ms: Long) {
    fun validate(now: Long) {
        listOf(id, owner, installation, device, session, home_profile, nonce).forEach { require(accountUuid(it)) }
        require(expires_ms > now && expires_ms - now <= 660_000 && public_key.length in 350..500)
        require(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(public_key))).let {
            (it as java.security.interfaces.RSAPublicKey).modulus.bitLength() == 2048 && it.publicExponent == java.math.BigInteger.valueOf(65537)
        })
    }
    fun transcript() = listOf("jojo-account-pair:v1", id, owner, installation, device, session, home_profile, nonce, public_key)
        .joinToString("\u0000")
    fun code(): String = (ByteBuffer.wrap(pairDigest(transcript())).long.and(Long.MAX_VALUE) % 100_000_000L).toString().padStart(8, '0')
    override fun toString() = "AccountPairRequest(publicKey=redacted)"
}
internal data class AccountPairGrant(val request: AccountPairRequest, val profile: String, val key_epoch: String,
    val device_a: String, val device_b: String, val packet: String) {
    fun binding() = request.transcript() + listOf(profile, key_epoch, device_a, device_b).joinToString("\u0000", prefix = "\u0000")
    fun validate(now: Long) {
        request.validate(now); listOf(profile, key_epoch, device_a, device_b).forEach { require(accountUuid(it)) }
        require(device_a != device_b && request.device !in setOf(device_a, device_b))
    }
    override fun toString() = "AccountPairGrant(packet=redacted)"
}
private fun pairDigest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
private fun pairCipher() = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
private val pairOaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

/** The receiver private key exists only in RAM, for ten minutes and a single matching request. */
internal class AccountPairReceiver(private val clock: () -> Long = System::currentTimeMillis) {
    private val born = clock()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var consumed = false
    val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
    val nonce: String = UUID.randomUUID().toString()
    fun unwrap(grant: AccountPairGrant, expected: AccountPairRequest): ByteArray {
        require(!consumed && clock() - born in 0..600_000 && grant.request == expected &&
            expected.public_key == publicKey && expected.nonce == nonce)
        grant.validate(clock())
        consumed = true
        val encrypted = Base64.getDecoder().decode(grant.packet); require(encrypted.size == 256)
        val cipher = pairCipher(); cipher.init(Cipher.DECRYPT_MODE, keys.private, pairOaep)
        val bytes = cipher.doFinal(encrypted)
        return try {
            require(bytes.size == 64 && MessageDigest.isEqual(bytes.copyOfRange(0, 32), pairDigest(grant.binding())))
            bytes.copyOfRange(32, 64)
        } finally { bytes.fill(0) }
    }
    override fun toString() = "AccountPairReceiver(privateKey=redacted)"
}

internal fun sealAccountPair(request: AccountPairRequest, code: String, profile: String, epoch: String,
    deviceA: String, deviceB: String, key: ByteArray, now: Long): AccountPairGrant {
    request.validate(now)
    require(code.matches(Regex("[0-9]{8}")) && MessageDigest.isEqual(code.toByteArray(), request.code().toByteArray())) {
        "ACCOUNT_PAIR_CODE_MISMATCH"
    }
    require(key.size == 32)
    val grant = AccountPairGrant(request, profile, epoch, deviceA, deviceB, "").also { it.validate(now) }
    val clear = pairDigest(grant.binding()) + key
    return try {
        val recipient = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(request.public_key)))
        val cipher = pairCipher(); cipher.init(Cipher.ENCRYPT_MODE, recipient, pairOaep)
        grant.copy(packet = Base64.getEncoder().encodeToString(cipher.doFinal(clear)))
    } finally { clear.fill(0) }
}

/** TLS-only recovery response, bound to this account, installation, session and fresh request. */
internal data class AccountRecoveryGrant(val schema: Int, val owner: String, val installation: String,
    val device: String, val session: String, val home_profile: String, val nonce: String,
    val profile: String, val key_epoch: String, val device_a: String, val device_b: String,
    val device_c: String?, val slot: Int, val secret: String) {
    fun validate(expectedOwner: String, expectedInstallation: String, expectedDevice: String,
        expectedSession: String, expectedHome: String, expectedNonce: String) {
        require(schema == 1 && slot in 1..32 && owner == expectedOwner && installation == expectedInstallation &&
            device == expectedDevice && session == expectedSession && home_profile == expectedHome && nonce == expectedNonce) {
            "ACCOUNT_RECOVERY_RESPONSE_INVALID"
        }
        listOf(owner, installation, device, session, home_profile, nonce, profile, key_epoch, device_a, device_b)
            .forEach { require(accountUuid(it)) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" } }
        device_c?.let { require(accountUuid(it)) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" } }
        require(device_a != device_b && (device_c == null || device_c !in setOf(device_a, device_b)) &&
            when (slot) { 1 -> device == device_a; 2 -> device == device_b; 3 -> device == device_c
                else -> device !in setOfNotNull(device_a, device_b, device_c) }) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" }
    }
    fun secretBytes(): ByteArray {
        require(secret.matches(Regex("[A-Za-z0-9+/]{43}="))) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" }
        val bytes = Base64.getDecoder().decode(secret)
        if (bytes.size != 32 || Base64.getEncoder().encodeToString(bytes) != secret) {
            bytes.fill(0); throw IllegalArgumentException("ACCOUNT_RECOVERY_RESPONSE_INVALID")
        }
        return bytes
    }
    override fun toString() = "AccountRecoveryGrant(secret=redacted)"
}

internal fun accountRecoveryGrant(body: String): AccountRecoveryGrant {
    require(body.length <= 4096) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" }
    val value = com.google.gson.JsonParser.parseString(body)
    require(value.isJsonObject) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" }
    val obj = value.asJsonObject
    val strings = setOf("owner", "installation", "device", "session", "home_profile", "nonce", "profile",
        "key_epoch", "device_a", "device_b", "secret")
    require(obj.keySet() == strings + setOf("schema", "device_c", "slot") && strings.all {
        obj[it].isJsonPrimitive && obj[it].asJsonPrimitive.isString
    } && listOf("schema", "slot").all { obj[it].isJsonPrimitive && obj[it].asJsonPrimitive.isNumber &&
        obj[it].toString().matches(Regex("[0-9]{1,2}")) } && (obj["device_c"].isJsonNull ||
        obj["device_c"].isJsonPrimitive && obj["device_c"].asJsonPrimitive.isString)) { "ACCOUNT_RECOVERY_RESPONSE_INVALID" }
    return com.google.gson.Gson().fromJson(obj, AccountRecoveryGrant::class.java)
}

/** Local choices survive reconnection; only a new installation defaults to all cloud sources. */
internal data class AccountLoginChoices(val progress: Boolean, val iptv: Boolean, val pending: Boolean = true)

internal fun PilotLocalSettings.withAccountSession(target: PilotEndpoint, next: PilotAuthSession): PilotLocalSettings {
    target.validate()
    require(scope == null || endpoint == target && scope.ownerId == next.ownerId) { "ACCOUNT_RECOVERY_IDENTITY_CHANGED" }
    require(!everActivated || endpoint == target) { "ACCOUNT_RECOVERY_IDENTITY_CHANGED" }
    val sameAccount = endpoint == target && (scope?.ownerId == next.ownerId || session?.ownerId == next.ownerId)
    val updated = copy(endpoint = target, session = next, registeredSessionId = "", lastIssue = null)
    return if (sameAccount) updated else updated.withoutKeyCheck()
}

internal fun PilotLocalSettings.recoveredAccount(grant: AccountRecoveryGrant, secret: ByteArray,
    profileInstance: String): PilotLocalSettings {
    require(secret.size == 32 && Base64.getEncoder().encodeToString(secret) == grant.secret &&
        accountUuid(profileInstance) && session?.ownerId == grant.owner &&
        session.sessionId == grant.session && deviceId == grant.device) { "ACCOUNT_RECOVERY_IDENTITY_CHANGED" }
    val previous = scope
    if (previous != null) {
        require(previous.ownerId == grant.owner && previous.profileBindingId == grant.profile &&
            previous.deviceA == grant.device_a && previous.deviceB == grant.device_b && previous.deviceC == grant.device_c &&
            previous.localProfileId == 1 && previous.localProfileInstanceId == profileInstance &&
            previous.accountEnrolled == (grant.slot >= 4) &&
            (cloudKeyEpoch.orEmpty().isEmpty() || cloudKeyEpoch == grant.key_epoch)) { "ACCOUNT_RECOVERY_IDENTITY_CHANGED" }
        if (hmacKeyBase64.isNotBlank()) {
            val oldKey = Base64.getDecoder().decode(hmacKeyBase64)
            try { require(MessageDigest.isEqual(oldKey, secret)) { "ACCOUNT_CONTENT_KEY_CHANGED" } }
            finally { oldKey.fill(0) }
        }
    } else require(!everActivated && mode == PilotMode.NAS_ONLY) { "ACCOUNT_RECOVERY_IDENTITY_CHANGED" }
    val bound = previous ?: PilotScope(grant.owner, grant.profile, 1, profileInstance, grant.device,
        grant.device_a, grant.device_b, UUID.randomUUID().toString(), grant.device_c, accountEnrolled = grant.slot >= 4)
    bound.validate()
    val candidate = copy(scope = bound, hmacKeyBase64 = Base64.getEncoder().encodeToString(secret),
        backendApproved = true, cloudKeyEpoch = grant.key_epoch, cloudKeyBindingEpoch = bound.epoch,
        registeredSessionId = "", lastIssue = null)
    val capture = PilotCaptureContext(bound, Long.MAX_VALUE, secret)
    return candidate.checkedKey(capture, grant.nonce, capture.pairingKeyCode(grant.nonce))
}
