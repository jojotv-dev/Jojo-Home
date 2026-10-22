package com.jojotv.home.progresspilot

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SupabaseAccountPairingTest {
    private var now = 2_000_000L
    private fun uuid() = UUID.randomUUID().toString()
    private fun request(receiver: AccountPairReceiver) = AccountPairRequest(uuid(), uuid(), uuid(), uuid(), uuid(), uuid(),
        receiver.nonce, receiver.publicKey, now + 600_000)
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} catch (_: javax.crypto.BadPaddingException) {} }
    private fun grant(request: AccountPairRequest, key: ByteArray = ByteArray(32) { 7 }) =
        sealAccountPair(request, request.code(), uuid(), uuid(), uuid(), uuid(), key, now)

    @Test fun approvedRecipientReceivesSameKey() {
        val receiver = AccountPairReceiver { now }; val request = request(receiver); val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(key, receiver.unwrap(grant(request, key), request))
    }
    @Test fun codeIsFixedLengthAndBoundToRecipient() {
        val a = AccountPairReceiver { now }; val b = AccountPairReceiver { now }; val request = request(a)
        assertTrue(request.code().matches(Regex("[0-9]{8}"))); assertNotEquals(request.code(), request.copy(public_key = b.publicKey).code())
    }
    @Test fun wrongCodeDoesNotSealKey() {
        val r = request(AccountPairReceiver { now }); reject { sealAccountPair(r, "wrong", uuid(), uuid(), uuid(), uuid(), ByteArray(32), now) }
    }
    @Test fun receiverIsSingleUse() { val receiver = AccountPairReceiver { now }; val request = request(receiver); val grant = grant(request)
        receiver.unwrap(grant, request); reject { receiver.unwrap(grant, request) } }
    @Test fun expiredGrantIsRejected() { val receiver = AccountPairReceiver { now }; val request = request(receiver); val grant = grant(request)
        now += 600_001; reject { receiver.unwrap(grant, request) } }
    @Test fun anotherInstallationCannotCollectKey() { val receiver = AccountPairReceiver { now }; val request = request(receiver)
        reject { receiver.unwrap(grant(request), request.copy(installation = uuid())) } }
    @Test fun substitutedEpochCannotUnlockPacket() { val receiver = AccountPairReceiver { now }; val request = request(receiver)
        reject { receiver.unwrap(grant(request).copy(key_epoch = uuid()), request) } }
    @Test fun substitutedProfileCannotUnlockPacket() { val receiver = AccountPairReceiver { now }; val request = request(receiver)
        reject { receiver.unwrap(grant(request).copy(profile = uuid()), request) } }
    @Test fun anotherPrivateKeyCannotDecrypt() { val receiver = AccountPairReceiver { now }; val other = AccountPairReceiver { now }
        val r = request(receiver); reject { other.unwrap(grant(r), r) } }
    @Test fun nonceReplacementIsRejected() { val receiver = AccountPairReceiver { now }; val request = request(receiver)
        reject { receiver.unwrap(grant(request.copy(nonce = uuid())), request) } }
    @Test fun lostReceiverRequiresFreshRequest() { val receiver = AccountPairReceiver { now }; val request = request(receiver)
        reject { AccountPairReceiver { now }.unwrap(grant(request), request) } }
    @Test fun packetNeverSerializesTheRawKey() { val request = request(AccountPairReceiver { now }); val grant = grant(request)
        val body = com.google.gson.Gson().toJson(grant)
        assertEquals(344, grant.packet.length); assertFalse(body.contains("BwcHBwcHBwcH"))
    }
}
