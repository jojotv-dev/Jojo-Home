package com.jojotv.home.progresspilot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class SupabaseProgressPilotTest {
    @Test fun lateRuntimesReceiveTheCurrentForegroundWithoutAnotherActivityStart() = runBlocking {
        val foreground = SupabaseForegroundState()
        val home = Any()
        foreground.started(home)
        val deliveries = List(3) { mutableListOf<Boolean>() }
        val observers = deliveries.map { received ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                foreground.state.take(3).collect { received.add(it) }
            }
        }
        foreground.stopped(home)
        yield()
        foreground.started(home)
        withTimeout(2_000) { observers.forEach { it.join() } }
        deliveries.forEach { assertEquals(listOf(true, false, true), it) }
    }

    @Test fun activityHandoverKeepsForegroundUntilTheLastActivityStops() {
        val foreground = SupabaseForegroundState()
        val home = Any(); val player = Any()
        assertFalse(foreground.state.value)
        foreground.started(home); foreground.started(player); foreground.stopped(home)
        assertTrue(foreground.state.value)
        foreground.stopped(player)
        assertFalse(foreground.state.value)
    }

    @Test fun duplicateStartsAndUnknownStopsCannotStrandForegroundState() {
        val foreground = SupabaseForegroundState()
        val home = Any()
        foreground.stopped(Any())
        assertFalse(foreground.state.value)
        foreground.started(home); foreground.started(home); foreground.stopped(Any())
        assertTrue(foreground.state.value)
        foreground.stopped(home)
        assertFalse(foreground.state.value)
        foreground.started(Any())
        assertTrue(foreground.state.value)
    }

    private val binding = PilotScope(OWNER, PROFILE, 1, INSTANCE, DEVICE_A, DEVICE_A, DEVICE_B, EPOCH)
    private val context = PilotCaptureContext(binding, 100, ByteArray(32) { it.toByte() })
    private val endpoint = PilotEndpoint("https://pilot-test.supabase.co", "sb_publishable_test")
    private val session = PilotAuthSession(OWNER, SESSION, "supabase-test-token", "supabase-refresh", 10_000)
    private val thirdSession = PilotAuthSession(OWNER, SESSION_C, "supabase-C-test-token", "supabase-C-refresh", 10_000)

    @Test fun legacyEnrolledSettingsKeepTheirScopeProofAndPendingJournal() {
        val challenge = "00000000-0000-4000-8000-000000000099"
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val saved = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session,
            endpoint = endpoint, hmacKeyBase64 = key, nasAuthorityDigest = "synthetic-nas",
            backendApproved = true, everActivated = true, registeredSessionId = SESSION, lastAckMs = 123)
            .checkedKey(context, challenge, context.pairingKeyCode(challenge))
        // Fixed old format, independent of the current Scope serializer.
        val legacy = """{
            "deviceId":"$DEVICE_A","mode":"NAS_WITH_SUPABASE_MIRROR",
            "endpoint":{"url":"https://pilot-test.supabase.co","publishableKey":"sb_publishable_test"},
            "session":{"ownerId":"$OWNER","sessionId":"$SESSION","accessToken":"supabase-test-token",
                "refreshToken":"supabase-refresh","expiresAtSeconds":10000},
            "scope":{"ownerId":"$OWNER","profileBindingId":"$PROFILE","localProfileId":1,
                "localProfileInstanceId":"$INSTANCE","deviceId":"$DEVICE_A",
                "deviceA":"$DEVICE_A","deviceB":"$DEVICE_B","epoch":"$EPOCH"},
            "hmacKeyBase64":"$key","nasAuthorityDigest":"synthetic-nas","backendApproved":true,
            "everActivated":true,"registeredSessionId":"$SESSION","lastAckMs":123,
            "keyCheckEpoch":"$EPOCH","keyCheckChallenge":"$challenge",
            "keyCheckCode":"66e1b527d91dd40ee11e436042998d98dfb16f4caef686a92c0eb8029002ce00"
        }""".trimIndent()
        val journal = PilotJournal().enqueue(context, payload())
        val journalJson = PilotJson.encode(journal)
        val restored = PilotJson.gson.fromJson(legacy, PilotLocalSettings::class.java).stoppedForPersistence()
        val restoredSession = requireNotNull(restored.session)
        assertEquals(saved.copy(session = null), restored.copy(session = null))
        assertEquals(session.ownerId, restoredSession.ownerId)
        assertEquals(session.sessionId, restoredSession.sessionId)
        assertEquals(session.accessToken, restoredSession.accessToken)
        assertEquals(session.refreshToken, restoredSession.refreshToken)
        assertEquals(session.expiresAtSeconds, restoredSession.expiresAtSeconds)
        assertFalse(PilotJson.gson.toJson(restored).contains("\"deviceC\""))
        assertNull(restored.scope!!.deviceC)
        assertEquals(PilotMode.NAS_ONLY, restored.mode)
        assertTrue(restored.keyCheckMatches(context))
        assertEquals(journal, PilotJson.decode(journalJson))
    }

    @Test fun thirdScopeRequiresItsOwnDistinctCanonicalInstallation() {
        val third = binding.copy(deviceId = DEVICE_C, deviceC = DEVICE_C)
        third.validate()
        val encoded = PilotJson.gson.toJson(third)
        assertEquals(third, PilotJson.gson.fromJson(encoded, PilotScope::class.java))
        for (invalid in listOf(third.copy(deviceC = DEVICE_A), third.copy(deviceC = DEVICE_B),
                third.copy(deviceC = ""), third.copy(deviceC = "invalid"), third.copy(deviceC = null),
                third.copy(deviceId = OTHER_OWNER), binding.copy(deviceC = DEVICE_C),
                third.copy(deviceId = DEVICE_B)))
            assertTrue(runCatching { invalid.validate() }.isFailure)
        val missingOwner = encoded.replace("\"ownerId\":\"$OWNER\"", "\"ownerId\":null")
        assertTrue(runCatching { PilotJson.gson.fromJson(missingOwner, PilotScope::class.java).validate() }.isFailure)
    }

    @Test fun thirdDeviceUsesExistingContentHmacAndPairingReferenceWithoutRekeyingAB() {
        val third = PilotCaptureContext(binding.copy(deviceId = DEVICE_C, deviceC = DEVICE_C), 100,
            ByteArray(32) { it.toByte() })
        val challenge = "00000000-0000-4000-8000-000000000099"
        assertEquals(context.contentKey(progress().storageKey), third.contentKey(progress().storageKey))
        assertEquals(context.pairingKeyCode(challenge), third.pairingKeyCode(challenge))
        val settings = PilotLocalSettings(deviceId = DEVICE_C, scope = third.scope,
            session = thirdSession)
        assertTrue(settings.checkedKey(third, challenge, context.pairingKeyCode(challenge)).keyCheckMatches(third))
        val wrongKey = PilotCaptureContext(third.scope, 100, ByteArray(32) { 9 })
        assertTrue(runCatching { settings.checkedKey(wrongKey, challenge, context.pairingKeyCode(challenge)) }
            .exceptionOrNull() is PilotKeyMismatch)
        assertEquals(PilotMode.NAS_ONLY, settings.mode)
        assertFalse(settings.everActivated)
        assertTrue(settings.keyCheckEpoch.isEmpty())
    }

    @Test fun oldRegistrationRequestsRetainExactlyFourParametersForBothDevices() {
        for (device in listOf(DEVICE_A, DEVICE_B)) {
            val scope = binding.copy(deviceId = device)
            val gate = PilotRequestGate()
            val transport = FakeTransport { PilotHttpResponse(204, "") }
            val permit = gate.allow(endpoint, OWNER, setOf("rest/v1/rpc/pilot_register_device"), scope)
            SupabaseProgressApi(transport, gate).registerDevice(endpoint, session, scope, permit)
            val request = transport.requests.single()
            assertEquals(endpoint.url + "/rest/v1/rpc/pilot_register_device", request.url)
            val body = JsonParser.parseString(request.body).asJsonObject
            assertEquals(setOf("p_profile_binding_id", "p_device_a", "p_device_b", "p_device_id"), body.keySet())
            assertEquals(device, body["p_device_id"].asString)
        }
    }

    @Test fun thirdRegistrationSendsOnlyFiveTechnicalParametersToItsDedicatedRpc() {
        val scope = binding.copy(deviceId = DEVICE_C, deviceC = DEVICE_C)
        val gate = PilotRequestGate()
        val transport = FakeTransport { PilotHttpResponse(204, "") }
        val permit = gate.allow(endpoint, OWNER, setOf("rest/v1/rpc/pilot_register_third_device"), scope)
        val auth = thirdSession
        SupabaseProgressApi(transport, gate).registerDevice(endpoint, auth, scope, permit)
        val request = transport.requests.single()
        assertEquals(endpoint.url + "/rest/v1/rpc/pilot_register_third_device", request.url)
        val body = JsonParser.parseString(request.body).asJsonObject
        assertEquals(setOf("p_profile_binding_id", "p_device_a", "p_device_b", "p_device_c", "p_device_id"), body.keySet())
        assertEquals(PROFILE, body["p_profile_binding_id"].asString)
        assertEquals(DEVICE_A, body["p_device_a"].asString)
        assertEquals(DEVICE_B, body["p_device_b"].asString)
        assertEquals(DEVICE_C, body["p_device_c"].asString)
        assertEquals(DEVICE_C, body["p_device_id"].asString)
        assertFalse(request.body.contains(auth.accessToken))
        assertFalse(request.body.contains(auth.refreshToken))
    }

    @Test fun registrationCannotUseTheOtherEndpointEvenWithTheSameOwnerAndScope() {
        for ((scope, wrongPath) in listOf(
                binding.copy(deviceId = DEVICE_C, deviceC = DEVICE_C) to "rest/v1/rpc/pilot_register_device",
                binding to "rest/v1/rpc/pilot_register_third_device")) {
            val gate = PilotRequestGate()
            val transport = FakeTransport { error("Unexpected transport") }
            val permit = gate.allow(endpoint, OWNER, setOf(wrongPath), scope)
            assertTrue(runCatching { SupabaseProgressApi(transport, gate).registerDevice(endpoint, session, scope, permit) }.isFailure)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun thirdDeviceRegistrationAndAcknowledgementKeepItsOwnObservation() = runBlocking {
        val third = PilotCaptureContext(binding.copy(deviceId = DEVICE_C, deviceC = DEVICE_C), 100,
            ByteArray(32) { it.toByte() })
        val gate = PilotRequestGate()
        val access = Access(gate, third).apply { registered = ""; auth = thirdSession }
        val payload = SupabaseProgressProjector.checkpoint(third, event(progress()), progress())!!
        val queue = Queue(PilotJournal().enqueue(third, payload))
        val transport = FakeTransport { request ->
            if (request.url.endsWith("pilot_register_third_device")) PilotHttpResponse(204, "") else acknowledgements(request)
        }
        val engine = PilotDeliveryEngine(access, queue, SupabaseProgressApi(transport, gate)) { 1000 }
        assertEquals(PilotDeliveryResult.DONE, engine.flush())
        assertEquals(2, transport.requests.size)
        assertEquals(SESSION_C, access.registered)
        assertTrue(queue.state.value.pending.isEmpty())
        assertEquals(DEVICE_C, queue.acknowledged.single().payload.device_id)
        assertEquals(context.contentKey(progress().storageKey), payload.content_key)
    }

    @Test fun missingThirdRpcStopsWithoutFallbackAndRetainsThePendingOperation() = runBlocking {
        val third = PilotCaptureContext(binding.copy(deviceId = DEVICE_C, deviceC = DEVICE_C), 100,
            ByteArray(32) { it.toByte() })
        val gate = PilotRequestGate()
        val access = Access(gate, third).apply { registered = ""; auth = thirdSession }
        val payload = SupabaseProgressProjector.checkpoint(third, event(progress()), progress())!!
        val queue = Queue(PilotJournal().enqueue(third, payload))
        val before = queue.state.value
        val transport = FakeTransport { PilotHttpResponse(404, "") }
        assertEquals(PilotDeliveryResult.STOPPED,
            PilotDeliveryEngine(access, queue, SupabaseProgressApi(transport, gate)) { 1000 }.flush())
        assertTrue(transport.requests.single().url.endsWith("pilot_register_third_device"))
        assertEquals(before, queue.state.value)
        assertEquals("HTTP_404", access.issue)
        assertNull(access.active)
    }

    @Test fun profileInstanceDiagnosticSeparatesMissingAndDifferentMarkers() {
        binding.checkKeyProfileInstance(INSTANCE)
        val absent = runCatching { binding.checkKeyProfileInstance(null) }.exceptionOrNull() as PilotKeyCheckBlocked
        val changed = runCatching { binding.checkKeyProfileInstance(OTHER_OWNER) }.exceptionOrNull() as PilotKeyCheckBlocked
        assertEquals(PilotKeyCheckIssue.PROFILE_INSTANCE_MISSING, absent.issue)
        assertEquals(PilotKeyCheckIssue.PROFILE_RECREATED, changed.issue)
    }

    @Test fun explicitPrimaryInstanceRepairChangesOnlyTechnicalIdentityAndKeepsRevisionFloor() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val saved = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session, endpoint = endpoint,
            hmacKeyBase64 = key, nasAuthorityDigest = "synthetic-authority", everActivated = true,
            backendApproved = true, lastAckMs = 1234)
        val journal = PilotJournal(epoch = EPOCH, sequence = 42, fingerprints = mapOf("a".repeat(64) to "b".repeat(64)))
        val prepared = saved.plannedKeyRepair(journal, key, context, nonce, context.pairingKeyCode(nonce), OTHER_OWNER)
        val next = requireNotNull(prepared.scope)
        assertEquals(binding.copy(localProfileInstanceId = OTHER_OWNER, epoch = next.epoch), next)
        assertNotEquals(EPOCH, next.epoch)
        assertEquals(42L, prepared.keyRepairSequence)
        assertEquals(EPOCH, prepared.keyRepairFromEpoch)
        assertEquals(saved.session, prepared.session)
        assertEquals(saved.endpoint, prepared.endpoint)
        assertEquals(saved.deviceId, prepared.deviceId)
        assertEquals(saved.nasAuthorityDigest, prepared.nasAuthorityDigest)
        assertEquals(saved.hmacKeyBase64, prepared.hmacKeyBase64)
        assertEquals(saved.lastAckMs, prepared.lastAckMs)
        assertEquals(PilotMode.NAS_ONLY, prepared.mode)
        assertFalse(prepared.keyCheckMatches(PilotCaptureContext(next,100,ByteArray(32) { it.toByte() })))
        val repaired = journal.forKeyRepair(EPOCH, next.epoch, 42)
        assertTrue(repaired.fingerprints.isEmpty() && repaired.pending.isEmpty())
        assertEquals(43L, repaired.enqueue(PilotCaptureContext(next,100,ByteArray(32) { it.toByte() }),payload()).sequence)
        val resumed = PilotCaptureContext(next,100,ByteArray(32) { it.toByte() })
        assertEquals(prepared, prepared.plannedKeyRepair(repaired,key,resumed,nonce,resumed.pairingKeyCode(nonce),OTHER_OWNER))
    }

    @Test fun instanceRepairRefusesPendingForeignEpochWrongReferenceAndSecondaryReassignment() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val saved = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session,
            hmacKeyBase64 = key, everActivated = true)
        for (journal in listOf(PilotJournal(),PilotJournal(epoch=OTHER_OWNER),
                PilotJournal(epoch=EPOCH,issue="OUTBOX_UNREADABLE"),PilotJournal().enqueue(context,payload())))
            assertTrue(runCatching { saved.plannedKeyRepair(journal,key,context,nonce,context.pairingKeyCode(nonce),OTHER_OWNER) }.isFailure)
        val journal = PilotJournal(epoch=EPOCH)
        assertTrue(runCatching { saved.plannedKeyRepair(journal,key,context,nonce,"a".repeat(64),OTHER_OWNER) }.exceptionOrNull() is PilotKeyMismatch)
        val secondary = binding.copy(localProfileId=2)
        assertTrue(runCatching { saved.copy(scope=secondary).plannedKeyRepair(journal,key,
            PilotCaptureContext(secondary,100,ByteArray(32) { it.toByte() }),nonce,context.pairingKeyCode(nonce),OTHER_OWNER) }.isFailure)
        assertTrue(runCatching { saved.copy(mode=PilotMode.NAS_WITH_SUPABASE_MIRROR)
            .plannedKeyRepair(journal,key,context,nonce,context.pairingKeyCode(nonce),OTHER_OWNER) }.isFailure)
    }

    @Test fun keyScopeAcceptsOnlyTheEnrolledDeviceAccountAndNasAuthority() {
        val settings = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session,
            nasAuthorityDigest = "synthetic-nas-authority")
        assertEquals(PilotMode.NAS_ONLY, settings.mode)
        assertEquals(binding, settings.validateKeyScope("synthetic-nas-authority"))
        val cases = listOf(
            settings.copy(mode = PilotMode.NAS_WITH_SUPABASE_MIRROR) to PilotKeyCheckIssue.MODE_ACTIVE,
            settings.copy(scope = null) to PilotKeyCheckIssue.SCOPE_MISSING,
            settings.copy(scope = binding.copy(deviceA = DEVICE_B)) to PilotKeyCheckIssue.SCOPE_INVALID,
            settings.copy(deviceId = DEVICE_B) to PilotKeyCheckIssue.DEVICE_CHANGED,
            settings.copy(session = null) to PilotKeyCheckIssue.ACCOUNT_CHANGED,
            settings.copy(session = PilotAuthSession(OTHER_OWNER, SESSION, "synthetic-token", "synthetic-refresh", 10_000)) to PilotKeyCheckIssue.ACCOUNT_CHANGED
        )
        for ((candidate, expected) in cases) {
            val failure = runCatching { candidate.validateKeyScope("synthetic-nas-authority") }.exceptionOrNull()
            assertTrue(failure is PilotKeyCheckBlocked)
            assertEquals(expected, (failure as PilotKeyCheckBlocked).issue)
        }
    }

    @Test fun missingNasIdentityAndChangedNasIdentityRemainSeparateBlockingConditions() {
        val settings = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session,
            nasAuthorityDigest = "synthetic-nas-authority")
        val missing = runCatching { settings.validateKeyScope("") }.exceptionOrNull() as PilotKeyCheckBlocked
        val changed = runCatching { settings.validateKeyScope("another-authority") }.exceptionOrNull() as PilotKeyCheckBlocked
        assertEquals(PilotKeyCheckIssue.NAS_UNAVAILABLE, missing.issue)
        assertEquals(PilotKeyCheckIssue.NAS_CHANGED, changed.issue)
        assertEquals("synthetic-nas-authority", settings.nasAuthorityDigest)
        assertEquals(PilotMode.NAS_ONLY, settings.mode)
    }

    @Test fun keyCheckDiagnosticsExposeOnlyBoundedReasons() {
        for (issue in PilotKeyCheckIssue.entries) {
            val message = PilotKeyCheckBlocked(issue).message.orEmpty()
            assertTrue(message.contains("[${issue.name}]"))
            for (sensitive in listOf(OWNER, DEVICE_A, PROFILE, "synthetic-nas-authority", session.accessToken))
                assertFalse(message.contains(sensitive))
        }
    }

    @Test fun pairingProofMatchesAcrossDevicesAndChangesWithKeyOwnerProfileOrPair() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val code = context.pairingKeyCode(nonce)
        // Independent Python hmac + uint32 big-endian UTF-8 framing, synthetic key only.
        assertEquals("66e1b527d91dd40ee11e436042998d98dfb16f4caef686a92c0eb8029002ce00", code)
        assertEquals(code, PilotCaptureContext(binding.copy(deviceId = DEVICE_B, epoch = OTHER_OWNER), 900,
            ByteArray(32) { it.toByte() }).pairingKeyCode(nonce))
        for (scope in listOf(binding.copy(ownerId = OTHER_OWNER), binding.copy(profileBindingId = OTHER_OWNER),
                binding.copy(deviceB = OTHER_OWNER)))
            assertNotEquals(code, PilotCaptureContext(scope, 100, ByteArray(32) { it.toByte() }).pairingKeyCode(nonce))
        assertNotEquals(code, PilotCaptureContext(binding, 100, ByteArray(32) { 9 }).pairingKeyCode(nonce))
        assertNotEquals(code, context.pairingKeyCode("00000000-0000-4000-8000-000000000098"))
        assertNotEquals(code, context.localDiagnostic(nonce, PilotJournal()).keyCode)
    }

    @Test fun validBase64KeyWithoutReferenceCannotBecomeVerified() {
        val saved = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session,
            hmacKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        assertFalse(saved.keyCheckMatches(context))
        assertTrue(runCatching { saved.checkedKey(context, "00000000-0000-4000-8000-000000000099", "") }.isFailure)
        val failure = runCatching { saved.checkedKey(context, "00000000-0000-4000-8000-000000000099", "a".repeat(64)) }.exceptionOrNull()
        assertTrue(failure is PilotKeyMismatch)
        assertFalse(failure.toString().contains(saved.hmacKeyBase64))
    }

    @Test fun keyConfirmationIsBoundToStoredKeyEpochAndPublicContext() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val saved = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session)
        val checked = saved.checkedKey(context, nonce, context.pairingKeyCode(nonce).uppercase())
        assertTrue(checked.keyCheckMatches(context))
        assertFalse(checked.withoutKeyCheck().keyCheckMatches(context))
        assertFalse(checked.copy(keyCheckEpoch = OTHER_OWNER).keyCheckMatches(context))
        assertFalse(checked.copy(keyRepairFromEpoch = EPOCH).keyCheckMatches(context))
        assertFalse(checked.keyCheckMatches(PilotCaptureContext(binding, 100, ByteArray(32) { 9 })))
        assertFalse(checked.toString().contains(checked.keyCheckCode))
    }

    @Test fun legacyConfigurationReopensStoppedAndUnverified() {
        val legacy = "{\"deviceId\":\"$DEVICE_A\",\"mode\":\"NAS_WITH_SUPABASE_MIRROR\"}"
        val restored = PilotJson.gson.fromJson(legacy, PilotLocalSettings::class.java).stoppedForPersistence()
        assertEquals(PilotMode.NAS_ONLY, restored.mode)
        assertEquals("", restored.keyCheckEpoch)
        assertEquals("", restored.keyRepairFromEpoch)
        assertEquals(0L, restored.keyRepairSequence)
        assertFalse(restored.keyCheckMatches(context))
    }

    @Test fun keyRepairPreservesRevisionFloorRetiresOnlyTechnicalHashesAndIsIdempotent() {
        val original = PilotJournal(epoch = EPOCH, sequence = 42,
            fingerprints = mapOf("a".repeat(64) to "b".repeat(64)), capturedTimes = mapOf("a".repeat(64) to 1000))
        val repaired = original.forKeyRepair(EPOCH, OTHER_OWNER, 42)
        assertEquals(42L, repaired.sequence)
        assertEquals(OTHER_OWNER, repaired.epoch)
        assertTrue(repaired.fingerprints.isEmpty() && repaired.capturedTimes.isEmpty() && repaired.pending.isEmpty())
        assertEquals(repaired, repaired.forKeyRepair(EPOCH, OTHER_OWNER, 42))
        assertEquals(1, original.fingerprints.size)
        val next = repaired.enqueue(PilotCaptureContext(binding.copy(epoch = OTHER_OWNER), 100, ByteArray(32)), payload())
        assertEquals(43L, next.sequence)
    }

    @Test fun keyRepairRefusesPendingCorruptionLostCounterAndForeignEpoch() {
        val pending = PilotJournal().enqueue(context, payload())
        assertTrue(runCatching { pending.forKeyRepair(EPOCH, OTHER_OWNER, pending.sequence) }.isFailure)
        assertTrue(runCatching { PilotJournal(epoch = EPOCH, issue = "OUTBOX_UNREADABLE").forKeyRepair(EPOCH, OTHER_OWNER, 0) }.isFailure)
        assertTrue(runCatching { PilotJournal().forKeyRepair(EPOCH, OTHER_OWNER, 42) }.isFailure)
        assertTrue(runCatching { PilotJournal(epoch = DEVICE_A).forKeyRepair(EPOCH, OTHER_OWNER, 0) }.isFailure)
        assertTrue(runCatching { PilotJournal(epoch = EPOCH, sequence = 2).forKeyRepair(EPOCH, OTHER_OWNER, 1) }.isFailure)
        assertTrue(runCatching { PilotJournal(epoch = OTHER_OWNER, fingerprints = mapOf("a" to "b"))
            .forKeyRepair(EPOCH, OTHER_OWNER, 0) }.isFailure)
    }

    @Test fun oldAcknowledgementCannotAlterRepairedJournal() {
        val old = PilotJournal().enqueue(context, payload())
        val sent = old.pending.values.single()
        val empty = old.acknowledge(EPOCH, listOf(sent), listOf(PilotAcknowledgement(sent.operation_id, sent.revision, "applied")))
        val repaired = empty.forKeyRepair(EPOCH, OTHER_OWNER, 1)
            .enqueue(PilotCaptureContext(binding.copy(epoch = OTHER_OWNER), 100, ByteArray(32)), payload())
        assertEquals(repaired, repaired.acknowledge(EPOCH, listOf(sent),
            listOf(PilotAcknowledgement(sent.operation_id, sent.revision, "applied"))))
        assertEquals(2L, repaired.sequence)
    }

    @Test fun interruptedRepairPersistsClosedAndRetainsSequenceFloor() {
        val pending = PilotLocalSettings(deviceId = DEVICE_A, scope = binding.copy(epoch = OTHER_OWNER), session = session,
            keyRepairFromEpoch = EPOCH, keyRepairSequence = 42, lastIssue = "KEY_REPAIR_PENDING")
        val restored = PilotJson.gson.fromJson(PilotJson.gson.toJson(pending), PilotLocalSettings::class.java).stoppedForPersistence()
        assertEquals(PilotMode.NAS_ONLY, restored.mode)
        assertEquals(42L, restored.keyRepairSequence)
        assertFalse(restored.keyCheckMatches(PilotCaptureContext(requireNotNull(restored.scope), 100, ByteArray(32))))
        assertTrue(runCatching { restored.validateLocalDiagnostic(PilotJournal(epoch = OTHER_OWNER)) }.isFailure)
    }

    @Test fun sealedImportReturnsOnlySyntheticKeyAndRejectsAnotherReceiver() {
        val receiver = PilotKeyImportSession(binding)
        val secret = ByteArray(32) { it.toByte() }
        val sealed = sealSyntheticKey(receiver, secret)
        assertArrayEquals(secret, receiver.unwrap(sealed, binding))
        assertTrue(runCatching { receiver.unwrap(sealed, binding) }.isFailure)
        assertTrue(runCatching { PilotKeyImportSession(binding).unwrap(sealed, binding) }.isFailure)
        assertTrue(runCatching { receiver.unwrap(sealed, binding.copy(deviceId = DEVICE_B)) }.isFailure)
        assertFalse(receiver.toString().contains(Base64.getEncoder().encodeToString(secret)))
    }

    @Test fun sealedImportRefusesExpiryCorruptionAndInvalidSize() {
        var now = 100L
        val receiver = PilotKeyImportSession(binding) { now }
        val sealed = sealSyntheticKey(receiver, ByteArray(32))
        assertTrue(runCatching { receiver.unwrap(sealed.copyOf(255), binding) }.isFailure)
        val corruptReceiver = PilotKeyImportSession(binding)
        val corruptPacket = sealSyntheticKey(corruptReceiver, ByteArray(32)).apply { this[40] = (this[40].toInt() xor 1).toByte() }
        assertTrue(runCatching { corruptReceiver.unwrap(corruptPacket, binding) }.isFailure)
        val expiring = PilotKeyImportSession(binding) { now }
        val expiringPacket = sealSyntheticKey(expiring, ByteArray(32))
        now += 300_001
        assertTrue(runCatching { expiring.unwrap(expiringPacket, binding) }.isFailure)
    }

    private fun sealSyntheticKey(receiver: PilotKeyImportSession, key: ByteArray): ByteArray {
        val parts = receiver.publicToken { Base64.getEncoder().encodeToString(it) }.split('|')
        val nonce = java.util.UUID.fromString(parts[1])
        val nonceBytes = java.nio.ByteBuffer.allocate(16).putLong(nonce.mostSignificantBits).putLong(nonce.leastSignificantBits).array()
        val bindingBytes = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(parts[3])))
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey,
            javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource.PSpecified.DEFAULT))
        return cipher.doFinal(nonceBytes + bindingBytes + key)
    }

    @Test fun localDiagnosticMatchesIndependentReferenceAndDoesNotExposeRawIdentity() {
        val challenge = "00000000-0000-4000-8000-000000000099"
        val journal = PilotJournal(epoch = EPOCH, fingerprints = mapOf("a".repeat(64) to "technical-fingerprint"))
        val report = context.localDiagnostic(challenge, journal)
        assertEquals("3bea42b4f6510c3a5ad457eed3f75071d206052f3d8919cc83556962e4f562eb", report.keyCode)
        assertEquals(listOf("804cd69b05bc9dc112ef04ba294dee6a043234ed34c9a3437ca481876bb41a90"), report.contentCodes)
        assertEquals(1, report.contentCount)
        assertFalse(report.toString().contains(report.keyCode))
        assertFalse(report.contentCodes.contains("a".repeat(64)))
        assertEquals(mapOf("a".repeat(64) to "technical-fingerprint"), journal.fingerprints)
        assertTrue(journal.pending.isEmpty())
    }

    @Test fun localDiagnosticSeparatesKeyMismatchFromContentOrProfileMismatch() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val journal = PilotJournal(epoch = EPOCH, fingerprints = mapOf("a".repeat(64) to "one"))
        val original = context.localDiagnostic(nonce, journal)
        val otherDevice = PilotCaptureContext(binding.copy(deviceId = DEVICE_B), 900, ByteArray(32) { it.toByte() })
        assertEquals(original, otherDevice.localDiagnostic(nonce, journal))
        val changedKey = PilotCaptureContext(binding, 100, ByteArray(32) { 9 }).localDiagnostic(nonce, journal)
        assertNotEquals(original.keyCode, changedKey.keyCode)
        val changedContent = context.localDiagnostic(nonce, journal.copy(fingerprints = mapOf("b".repeat(64) to "two")))
        assertEquals(original.keyCode, changedContent.keyCode)
        assertNotEquals(original.contentCodes, changedContent.contentCodes)
        val changedProfile = PilotCaptureContext(binding.copy(profileBindingId = OTHER_OWNER), 100,
            ByteArray(32) { it.toByte() }).localDiagnostic(nonce, journal)
        assertEquals(original.keyCode, changedProfile.keyCode)
        assertNotEquals(original.contentCodes, changedProfile.contentCodes)
    }

    @Test fun localDiagnosticRequiresSameChallengeAndIgnoresDeliveryUpdates() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val first = PilotJournal(epoch = EPOCH, fingerprints = linkedMapOf("a".repeat(64) to "one", "b".repeat(64) to "two"))
        val second = first.copy(sequence = 99, fingerprints = linkedMapOf("b".repeat(64) to "changed", "a".repeat(64) to "changed"),
            capturedTimes = mapOf("a".repeat(64) to 9999))
        assertEquals(context.localDiagnostic(nonce, first), context.localDiagnostic(nonce, second))
        val other = context.localDiagnostic("00000000-0000-4000-8000-000000000098", first)
        assertNotEquals(context.localDiagnostic(nonce, first).keyCode, other.keyCode)
        assertNotEquals(context.localDiagnostic(nonce, first).contentCodes, other.contentCodes)
    }

    @Test fun localDiagnosticRejectsInvalidChallengeForeignEpochAndCorruptedKeys() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        assertTrue(runCatching { context.localDiagnostic("not-a-challenge", PilotJournal()) }.isFailure)
        assertTrue(runCatching { context.localDiagnostic("AAAAAAAA-aaaa-4aaa-8aaa-aaaaaaaaaaaa", PilotJournal()) }.isFailure)
        assertTrue(runCatching { context.localDiagnostic(nonce, PilotJournal(epoch = OTHER_OWNER)) }.isFailure)
        assertTrue(runCatching { context.localDiagnostic(nonce, PilotJournal(issue = "OUTBOX_UNREADABLE")) }.isFailure)
        assertTrue(runCatching { context.localDiagnostic(nonce,
            PilotJournal(epoch = EPOCH, fingerprints = mapOf("private-path" to "never-display"))) }.isFailure)
        assertTrue(runCatching { context.localDiagnostic(nonce, PilotJournal(sequence = 1)) }.isFailure)
    }

    @Test fun localDiagnosticAllowsEmptyJournalAndReportsTruncation() {
        val nonce = "00000000-0000-4000-8000-000000000099"
        val empty = context.localDiagnostic(nonce, PilotJournal())
        assertEquals(0, empty.contentCount)
        assertTrue(empty.contentCodes.isEmpty())
        val keys = (1..20).associate { it.toString(16).padStart(64, '0') to "technical-fingerprint" }
        val report = context.localDiagnostic(nonce, PilotJournal(epoch = EPOCH, fingerprints = keys))
        assertEquals(20, report.contentCount)
        assertEquals(PilotLocalDiagnosticReport.MAX_DISPLAYED_CODES, report.contentCodes.size)
        assertEquals(report.contentCodes.sorted(), report.contentCodes)
    }

    @Test fun localDiagnosticRequiresNasOnlyAndCurrentLocalAccountAndDevice() {
        val settings = PilotLocalSettings(deviceId = DEVICE_A, scope = binding, session = session)
        val journal = PilotJournal(epoch = EPOCH)
        assertEquals(PilotMode.NAS_ONLY, settings.mode)
        assertEquals(binding, settings.validateLocalDiagnostic(journal))
        assertTrue(runCatching { settings.copy(mode = PilotMode.NAS_WITH_SUPABASE_MIRROR).validateLocalDiagnostic(journal) }.isFailure)
        assertTrue(runCatching { settings.copy(scope = null).validateLocalDiagnostic(journal) }.isFailure)
        assertTrue(runCatching { settings.copy(session = null).validateLocalDiagnostic(journal) }.isFailure)
        assertTrue(runCatching { settings.copy(deviceId = DEVICE_B).validateLocalDiagnostic(journal) }.isFailure)
        assertTrue(runCatching { settings.copy(session = PilotAuthSession(OTHER_OWNER, SESSION, "test", "test", 10000))
            .validateLocalDiagnostic(journal) }.isFailure)
        assertTrue(runCatching { settings.validateLocalDiagnostic(PilotJournal(epoch = OTHER_OWNER)) }.isFailure)
    }

    @Test fun hmacMatchesIndependentLengthPrefixedReference() {
        // Reference computed offline with Python hmac + struct.pack('>I', UTF-8 byte length).
        assertEquals("8f795b82c910c22b5453211549a6af6189b2fb1798cb1f824b0ec9024e1f0694",
            context.contentKey("wp2:" + "a".repeat(64)))
        assertFalse(context.toString().contains("000102"))
    }

    @Test fun sameContentMatchesAcrossDevicesButKeysAndProfilesSeparateNamespaces() {
        val progress = progress()
        val otherDevice = PilotCaptureContext(binding.copy(deviceId = DEVICE_B), 100, ByteArray(32) { it.toByte() })
        assertEquals(context.contentKey(progress.storageKey), otherDevice.contentKey(progress.storageKey))
        assertNotEquals(context.contentKey(progress.storageKey),
            PilotCaptureContext(binding, 100, ByteArray(32) { 9 }).contentKey(progress.storageKey))
        assertNotEquals(context.contentKey(progress.storageKey),
            PilotCaptureContext(binding.copy(profileBindingId = OTHER_OWNER), 100, ByteArray(32) { it.toByte() })
                .contentKey(progress.storageKey))
    }

    @Test fun providersAndEpisodesRemainDistinctAndTitlesDoNotChangeIdentity() {
        val progress = progress()
        assertEquals(context.contentKey(progress.storageKey), context.contentKey(progress.copy(name = "Changed title").storageKey))
        assertNotEquals(context.contentKey(progress.storageKey), context.contentKey(progress.copy(addonBaseUrl = "https://other.invalid").storageKey))
        val first = progress.copy(contentType = "series", season = 1, episode = 1)
        assertNotEquals(context.contentKey(first.storageKey), context.contentKey(first.copy(episode = 2).storageKey))
    }

    @Test fun projectorRejectsUnmarkedPreActivationAndStaleSnapshots() {
        val progress = progress()
        assertNull(SupabaseProgressProjector.checkpoint(context, null, progress))
        assertNull(SupabaseProgressProjector.checkpoint(context, PilotPlaybackCheckpoint(progress.lastWatched, 99), progress))
        assertNull(SupabaseProgressProjector.checkpoint(context, PilotPlaybackCheckpoint(progress.lastWatched, 100), progress))
        assertNull(SupabaseProgressProjector.checkpoint(context, PilotPlaybackCheckpoint(progress.lastWatched - 1, 101), progress))
        assertNotNull(SupabaseProgressProjector.checkpoint(context, event(progress), progress))
    }

    @Test fun projectorExcludesLiveDummyMarksAndInvalidNumbers() {
        listOf(progress().copy(contentType = "iptv"), progress().copy(contentType = "unknown"),
            progress().copy(position = 1, duration = 1), progress().copy(position = -1),
            progress().copy(duration = -1), progress().copy(progressPercent = Float.NaN),
            progress().copy(progressPercent = 101f)).forEach {
            assertNull(SupabaseProgressProjector.checkpoint(context, event(it), it))
        }
    }

    @Test fun payloadHasOnlyApprovedFieldsAndNoPrivateMediaMetadata() {
        val payload = payload()
        val body = PilotJson.gson.toJson(payload)
        val keys = JsonParser.parseString(body).asJsonObject.keySet()
        assertEquals(setOf("owner_id", "profile_binding_id", "device_id", "content_key", "media_kind",
            "position_ms", "duration_ms", "source_time_ms"), keys)
        listOf("Private title", "nas.invalid", "provider.invalid", "smb://", "private-video", "wp2:",
            "description", "season", "episode", "addon", "name", "token").forEach { assertFalse(body.contains(it)) }
    }

    @Test fun duplicateCheckpointRemainsDeduplicatedAfterAcknowledgement() {
        val first = PilotJournal().enqueue(context, payload())
        val operation = first.pending.values.single()
        val acknowledged = first.acknowledge(EPOCH, listOf(operation), listOf(ack(operation)))
        assertTrue(acknowledged.pending.isEmpty())
        assertEquals(acknowledged, acknowledged.enqueue(context, payload()))
    }

    @Test fun pendingWritesCoalesceAndOldAcknowledgementCannotEraseNewerRewind() {
        val first = PilotJournal().enqueue(context, payload())
        val operation = first.pending.values.single()
        val rewind = payload().copy(position_ms = 3_000, source_time_ms = 2000)
        val latest = first.enqueue(context, rewind)
        assertEquals(1, latest.pending.size)
        assertEquals(2L, latest.sequence)
        assertEquals(3_000L, latest.pending.values.single().payload.position_ms)
        assertEquals(latest, latest.acknowledge(EPOCH, listOf(operation), listOf(ack(operation))))
        assertEquals(latest, latest.enqueue(context, payload())) // Delayed older capture is ignored.
    }

    @Test fun journalQuarantinesAssociationChangesAndLimitsWithoutDroppingAcceptedOperations() {
        val first = PilotJournal().enqueue(context, payload())
        val changed = PilotCaptureContext(binding.copy(epoch = OTHER_OWNER), 100, ByteArray(32))
        assertEquals("ASSOCIATION_CHANGED", first.enqueue(changed, payload()).issue)
        val full = first.copy(sequence = Long.MAX_VALUE).enqueue(context, payload().copy(source_time_ms = 2000))
        assertEquals("OUTBOX_LIMIT", full.issue)
        assertEquals(first.pending, full.pending)
        assertEquals(first, PilotJson.decode(PilotJson.encode(first)))
    }

    @Test fun defaultConfigurationIsNasOnlyAndUnapproved() {
        val settings = PilotLocalSettings()
        assertEquals(PilotMode.NAS_ONLY, settings.mode)
        assertFalse(settings.backendApproved)
        assertFalse(settings.everActivated)
        assertNull(settings.scope)
        assertNull(settings.session)
    }

    @Test fun persistedAndLegacyActiveConfigurationsAlwaysReopenNasOnly() {
        val active = PilotLocalSettings(mode = PilotMode.NAS_WITH_SUPABASE_MIRROR, everActivated = true)
        val persisted = PilotJson.gson.toJson(active.stoppedForPersistence())
        assertEquals(PilotMode.NAS_ONLY, PilotJson.gson.fromJson(persisted, PilotLocalSettings::class.java).mode)
        val legacy = PilotJson.gson.fromJson(PilotJson.gson.toJson(active), PilotLocalSettings::class.java)
        // If stopping cannot replace the file, reopening even the previous active file fails closed.
        assertEquals(PilotMode.NAS_ONLY, legacy.stoppedForPersistence().mode)
        assertTrue(legacy.stoppedForPersistence().everActivated)
    }

    @Test fun frozenProjectIsRejectedBeforeAnyAuthenticationCallCanBeAuthorized() {
        val configured = PilotLocalSettings(endpoint = endpoint, scope = binding, everActivated = true)
        configured.validateSignInTarget(endpoint)
        assertThrows(IllegalArgumentException::class.java) {
            configured.validateSignInTarget(PilotEndpoint("https://other-test.supabase.co", "sb_publishable_other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            configured.copy(mode = PilotMode.NAS_WITH_SUPABASE_MIRROR).validateSignInTarget(endpoint)
        }
    }

    @Test fun liveOrUnknownTypeWithEpisodeMetadataCannotEnterTheMirror() {
        listOf("iptv", "unknown").forEach { type ->
            val progress = progress().copy(contentType = type, season = 1, episode = 1)
            assertNull(SupabaseProgressProjector.checkpoint(context, event(progress), progress))
        }
        val episode = progress().copy(contentType = "series", season = 1, episode = 1)
        assertEquals("episode", SupabaseProgressProjector.checkpoint(context, event(episode), episode)?.media_kind)
    }

    @Test fun revokedPermitCannotPrepareAnyCallEvenAfterExplicitReactivation() {
        val gate = PilotRequestGate()
        val old = mirrorPermit(gate)
        val transport = FakeTransport(::acknowledgements)
        val api = SupabaseProgressApi(transport, gate)
        val operation = PilotJournal().enqueue(context, payload()).pending.values.single()
        gate.stop()
        val next = mirrorPermit(gate)
        assertThrows(PilotRequestsStopped::class.java) { api.writeBatch(endpoint, session, binding, listOf(operation), old) }
        assertTrue(transport.requests.isEmpty())
        assertEquals(listOf(ack(operation)), api.writeBatch(endpoint, session, binding, listOf(operation), next))
    }

    @Test fun stopCancelsAPreparedCallBeforeItsTransportStarts() {
        val gate = PilotRequestGate()
        val permit = mirrorPermit(gate)
        val prepared = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var started = false
        val transport = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest) = object : PilotHttpCall {
                @Volatile var cancelled = false
                override fun execute(): PilotHttpResponse {
                    prepared.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    if (cancelled) throw IOException("cancelled before transport")
                    started = true
                    return acknowledgements(request)
                }
                override fun cancel() { cancelled = true }
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = PilotJournal().enqueue(context, payload()).pending.values.single()
            val result = worker.submit<List<PilotAcknowledgement>> {
                SupabaseProgressApi(transport, gate).writeBatch(endpoint, session, binding, listOf(operation), permit)
            }
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            gate.stop()
            resume.countDown()
            assertTrue(assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }.cause is PilotRequestsStopped)
            assertFalse(started)
        } finally { resume.countDown(); worker.shutdownNow() }
    }

    @Test fun stopAfterRemoteAcceptanceRetainsOperationForIdempotentRetry() = runBlocking {
        val harness = harness(::acknowledgements)
        val before = harness.queue.state.value
        harness.transport.respond = { request ->
            val response = acknowledgements(request)
            harness.access.gate.stop()
            response
        }
        assertEquals(PilotDeliveryResult.STOPPED, harness.engine.flush())
        assertEquals(before, harness.queue.state.value)
        assertTrue(harness.queue.acknowledged.isEmpty())
    }

    @Test fun permitsCannotBeUsedForAnotherProjectOrToReadTables() {
        val gate = PilotRequestGate()
        val permit = mirrorPermit(gate)
        val transport = FakeTransport(::acknowledgements)
        assertThrows(IllegalArgumentException::class.java) {
            gate.execute(transport, permit, PilotHttpRequest(endpoint.url + "/rest/v1/pilot_watch_progress", emptyMap(), "{}"))
        }
        val operation = PilotJournal().enqueue(context, payload()).pending.values.single()
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseProgressApi(transport, gate).writeBatch(
                PilotEndpoint("https://other-test.supabase.co", "sb_publishable_other"), session, binding, listOf(operation), permit)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun endpointRejectsServerKeysRedirectTargetsAndPlainHttp() {
        listOf(PilotEndpoint("http://pilot.supabase.co", "sb_publishable_test"),
            PilotEndpoint("https://pilot.supabase.co.attacker.invalid", "sb_publishable_test"),
            PilotEndpoint("https://user:password@pilot.supabase.co", "sb_publishable_test"),
            PilotEndpoint("https://pilot.supabase.co/path", "sb_publishable_test"),
            PilotEndpoint("https://pilot.supabase.co", "sb_secret_test"),
            PilotEndpoint("https://pilot.supabase.co", "service_role_test")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }

    @Test fun explicitAuthenticationUsesOnlyOwnSupabaseCredentialsAndRefreshKeepsIdentity() {
        val transport = FakeTransport { request ->
            assertEquals("POST", request.method)
            assertFalse(request.headers.containsKey("Authorization"))
            PilotHttpResponse(200, sessionBody(OWNER, SESSION))
        }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, OWNER, setOf("auth/v1/token?grant_type=password", "auth/v1/token?grant_type=refresh_token"))
        val api = SupabaseProgressApi(transport, gate) { 10 }
        val signedIn = api.signIn(endpoint, "pilot@example.invalid", "supabase-only-password", permit)
        assertEquals(OWNER, signedIn.ownerId)
        val body = JsonParser.parseString(transport.requests.first().body).asJsonObject
        assertEquals(setOf("email", "password"), body.keySet())
        assertEquals("supabase-only-password", body["password"].asString)
        assertEquals(SESSION, api.refreshSession(endpoint, signedIn, permit).sessionId)
        transport.respond = { PilotHttpResponse(200, sessionBody(OTHER_OWNER, SESSION)) }
        assertThrows(IllegalArgumentException::class.java) { api.refreshSession(endpoint, signedIn, permit) }
    }

    @Test fun missingCredentialsAreReportedBeforeAnyTransportCanBePrepared() {
        val transport = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest): PilotHttpCall =
                throw AssertionError("Missing credentials must never reach the transport")
        }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, null, setOf("auth/v1/token?grant_type=password"))
        val api = SupabaseProgressApi(transport, gate)
        listOf("" to "supabase-only-password", "pilot@example.invalid" to "", " " to " ").forEach { (email, password) ->
            assertNotNull(pilotSignInInputMessage(email, password))
            assertThrows(IllegalArgumentException::class.java) { api.signIn(endpoint, email, password, permit) }
        }
    }

    @Test fun significantPasswordWhitespaceIsPreservedThroughAuthentication() {
        val password = "  supabase-only-password  "
        assertNull(pilotSignInInputMessage("pilot@example.invalid", password))
        val transport = FakeTransport { PilotHttpResponse(200, sessionBody(OWNER, SESSION)) }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, null, setOf("auth/v1/token?grant_type=password"))
        SupabaseProgressApi(transport, gate).signIn(endpoint, "pilot@example.invalid", password, permit)
        val body = JsonParser.parseString(transport.requests.single().body).asJsonObject
        assertEquals(password, body["password"].asString)
    }

    @Test fun authenticationFailuresHaveDistinctDiagnosticsWithoutExposingExceptionPayloads() {
        val sensitive = "private-password Bearer private-token private-response-body"
        val failures = listOf(PilotHttpFailure(400), UnknownHostException(sensitive),
            SocketTimeoutException(sensitive), SSLHandshakeException(sensitive), ConnectException(sensitive),
            GeneralSecurityException(sensitive), IOException(sensitive), IllegalArgumentException(sensitive),
            IllegalStateException(sensitive), Exception(sensitive).also { it.initCause(IOException(sensitive)) })
        val messages = failures.map(::pilotSignInFailureMessage)
        assertEquals(failures.size, messages.distinct().size)
        assertTrue(messages.first().contains("400"))
        assertTrue(messages[1].contains("DNS"))
        assertTrue(messages[3].contains("TLS"))
        messages.forEach { message ->
            assertFalse(message.contains("private-password"))
            assertFalse(message.contains("private-token"))
            assertFalse(message.contains("private-response-body"))
            assertFalse(message.contains("Bearer"))
        }
    }

    @Test fun invalidAuthenticationResponseIsReportedWithoutDisplayingItsPayload() {
        val sensitive = "private-response-payload"
        val transport = FakeTransport { PilotHttpResponse(200, "{\"unexpected\":\"$sensitive\"}") }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, null, setOf("auth/v1/token?grant_type=password"))
        val failure = assertThrows(IOException::class.java) {
            SupabaseProgressApi(transport, gate).signIn(endpoint, "pilot@example.invalid", "supabase-only-password", permit)
        }
        val message = pilotSignInFailureMessage(failure)
        assertTrue(message.contains("session Supabase valide"))
        assertFalse(message.contains(sensitive))
    }

    @Test fun transportOnlyPostsRegistrationAndTypedOperationsWithTechnicalAcknowledgements() {
        val transport = FakeTransport { request ->
            if (request.url.endsWith("pilot_register_device")) PilotHttpResponse(204, "") else acknowledgements(request)
        }
        val gate = PilotRequestGate()
        val permit = mirrorPermit(gate)
        val api = SupabaseProgressApi(transport, gate)
        api.registerDevice(endpoint, session, binding, permit)
        val operation = PilotJournal().enqueue(context, payload()).pending.values.single()
        assertEquals(listOf(ack(operation)), api.writeBatch(endpoint, session, binding, listOf(operation), permit))
        assertTrue(transport.requests.all { it.method == "POST" })
        assertEquals(setOf("p_profile_binding_id", "p_device_a", "p_device_b", "p_device_id"),
            JsonParser.parseString(transport.requests[0].body).asJsonObject.keySet())
        assertEquals("Bearer supabase-test-token", transport.requests[1].headers["Authorization"])
        assertTrue(transport.requests.none { it.url.contains("select=") || it.body.contains("supabase-refresh") })
        assertThrows(IllegalArgumentException::class.java) {
            api.writeBatch(endpoint, session, binding, listOf(operation.copy(payload = operation.payload.copy(device_id = DEVICE_B))), permit)
        }
        assertEquals(2, transport.requests.size)
        transport.respond = { request ->
            val response = acknowledgements(request)
            val ack = JsonParser.parseString(response.body).asJsonArray
            ack[0].asJsonObject.addProperty("position_ms", 1234)
            PilotHttpResponse(200, ack.toString())
        }
        assertThrows(IOException::class.java) { api.writeBatch(endpoint, session, binding, listOf(operation), permit) }
    }

    @Test fun malformedAcknowledgementDoesNotClearTheQueue() = runBlocking {
        val harness = harness { PilotHttpResponse(200, "[]") }
        val before = harness.queue.state.value
        assertEquals(PilotDeliveryResult.RETRY, harness.engine.flush())
        assertEquals(before, harness.queue.state.value)
        assertEquals("DELIVERY_FAILED", harness.access.issue)
    }

    @Test fun offlineFailureKeepsOperationIdAndRetryAcknowledgesOnlyDelivery() = runBlocking {
        val harness = harness { throw IOException("private detail must not reach diagnostics") }
        val operation = harness.queue.state.value.pending.values.single()
        assertEquals(PilotDeliveryResult.RETRY, harness.engine.flush())
        assertEquals(operation, harness.queue.state.value.pending.values.single())
        assertEquals("DELIVERY_FAILED", harness.access.issue)
        harness.transport.respond = ::acknowledgements
        harness.access.retryAt = 0
        assertEquals(PilotDeliveryResult.DONE, harness.engine.flush())
        assertTrue(harness.queue.state.value.pending.isEmpty())
        assertEquals(operation, harness.queue.acknowledged.single())
    }

    @Test fun conflictsPauseWithoutAcknowledgingOrMutatingProgress() = runBlocking {
        val harness = harness { acknowledgements(it, "conflict") }
        val before = harness.queue.state.value
        assertEquals(PilotDeliveryResult.STOPPED, harness.engine.flush())
        assertEquals(before, harness.queue.state.value)
        assertEquals("REMOTE_REVISION_CONFLICT", harness.access.issue)
        assertNull(harness.access.active)
    }

    @Test fun changedProfileInstanceStopsBeforeAnyHttpRequest() = runBlocking {
        val harness = harness(::acknowledgements)
        harness.queue.instance = OTHER_OWNER
        assertEquals(PilotDeliveryResult.STOPPED, harness.engine.flush())
        assertEquals("PROFILE_ASSOCIATION_CHANGED", harness.access.issue)
        assertTrue(harness.transport.requests.isEmpty())
    }

    @Test fun inactiveAndEmptyQueueNeverCreateRemoteRows() = runBlocking {
        val harness = harness(::acknowledgements)
        harness.access.active = null
        assertEquals(PilotDeliveryResult.STOPPED, harness.engine.flush())
        harness.access.active = context
        harness.queue.state.value = PilotJournal()
        harness.access.registered = ""
        assertEquals(PilotDeliveryResult.DONE, harness.engine.flush())
        assertTrue(harness.transport.requests.isEmpty())
    }

    @Test fun disablingDuringRegistrationPreventsProgressPost() = runBlocking {
        val harness = harness(::acknowledgements)
        harness.access.registered = ""
        harness.transport.respond = { harness.access.active = null; PilotHttpResponse(204, "") }
        assertEquals(PilotDeliveryResult.STOPPED, harness.engine.flush())
        assertEquals(1, harness.transport.requests.size)
        assertTrue(harness.transport.requests.single().url.endsWith("pilot_register_device"))
        assertEquals(1, harness.queue.state.value.pending.size)
    }

    @Test fun rateLimitRetainsQueueAndHonoursTechnicalRetryDeadline() = runBlocking {
        val harness = harness { PilotHttpResponse(429, "secret response", 120) }
        assertEquals(PilotDeliveryResult.RETRY, harness.engine.flush())
        assertEquals(121_000L, harness.access.retryAt)
        assertEquals("HTTP_429", harness.access.issue)
        assertEquals(PilotDeliveryResult.RETRY, harness.engine.flush())
        assertEquals(1, harness.transport.requests.size)
    }

    @Test fun expiredSessionRefreshesWithoutChangingOwnerOrDeviceAssociation() = runBlocking {
        val harness = harness { request ->
            if (request.url.contains("refresh_token")) PilotHttpResponse(200, sessionBody(OWNER, SESSION))
            else acknowledgements(request)
        }
        harness.access.auth = PilotAuthSession(OWNER, SESSION, "old-supabase", "own-refresh", 0)
        assertEquals(PilotDeliveryResult.DONE, harness.engine.flush())
        assertEquals(2, harness.transport.requests.size)
        assertTrue(harness.transport.requests[0].url.contains("refresh_token"))
        assertEquals(context.scope, harness.access.active?.scope)
        assertFalse(harness.transport.requests[1].body.contains("own-refresh"))
    }

    private fun payload() = SupabaseProgressProjector.checkpoint(context, event(progress()), progress())!!
    private fun event(progress: WatchProgress) = PilotPlaybackCheckpoint(progress.lastWatched, 101)
    private fun ack(operation: PilotOperation) = PilotAcknowledgement(operation.operation_id, operation.revision, "applied")

    private fun harness(respond: (PilotHttpRequest) -> PilotHttpResponse): Harness {
        val gate = PilotRequestGate()
        val access = Access(gate)
        val queue = Queue(PilotJournal().enqueue(context, payload()))
        val transport = FakeTransport(respond)
        return Harness(access, queue, transport, PilotDeliveryEngine(access, queue, SupabaseProgressApi(transport, gate)) { 1000 })
    }

    private fun mirrorPermit(gate: PilotRequestGate, capture: PilotCaptureContext = context) = gate.allow(endpoint, OWNER,
        setOf("auth/v1/token?grant_type=refresh_token", capture.scope.registrationPath, "rest/v1/rpc/pilot_write_progress"),
        capture.scope, capture.activationId)

    private inner class Access(val gate: PilotRequestGate, capture: PilotCaptureContext = context) : PilotDeliveryAccess {
        private val permit = mirrorPermit(gate, capture)
        var active: PilotCaptureContext? = capture
        var auth = session
        var registered = SESSION
        var retryAt = 0L
        var issue: String? = null
        override fun current() = active
        override fun endpoint() = this@SupabaseProgressPilotTest.endpoint
        override fun session() = auth
        override fun requestPermit(context: PilotCaptureContext): PilotRequestPermit {
            if (active?.activationId != context.activationId) throw PilotRequestsStopped()
            return permit
        }
        override fun registeredSessionId() = registered
        override fun nextAttemptMs() = retryAt
        override fun replaceSession(previous: PilotAuthSession, next: PilotAuthSession) { auth = next }
        override fun registered(scope: PilotScope, sessionId: String) { registered = sessionId }
        override fun delivered() { issue = null; retryAt = 0 }
        override fun retry(code: String, retryAtMs: Long) { issue = code; retryAt = retryAtMs }
        override fun pause(code: String) { issue = code; active = null; gate.stop() }
    }

    private class Queue(journal: PilotJournal) : PilotDeliveryQueue {
        val state = MutableStateFlow(journal)
        var instance = INSTANCE
        val acknowledged = mutableListOf<PilotOperation>()
        override fun observe(profileId: Int) = state
        override suspend fun profileInstanceId(profileId: Int) = instance
        override suspend fun acknowledge(scope: PilotScope, sent: Collection<PilotOperation>, acks: Collection<PilotAcknowledgement>) {
            acknowledged += sent.filter { op -> acks.any { it.operation_id == op.operation_id && it.status in setOf("applied", "duplicate") } }
            state.value = state.value.acknowledge(scope.epoch, sent, acks)
        }
    }

    private data class Harness(val access: Access, val queue: Queue, val transport: FakeTransport, val engine: PilotDeliveryEngine)

    private class FakeTransport(var respond: (PilotHttpRequest) -> PilotHttpResponse) : PilotHttpExecutor {
        val requests = mutableListOf<PilotHttpRequest>()
        override fun prepare(request: PilotHttpRequest) = object : PilotHttpCall {
            @Volatile private var cancelled = false
            override fun execute(): PilotHttpResponse {
                if (cancelled) throw IOException("cancelled")
                requests += request
                return respond(request)
            }
            override fun cancel() { cancelled = true }
        }
    }

    companion object {
        const val OWNER = "11111111-1111-4111-8111-111111111111"
        const val PROFILE = "22222222-2222-4222-8222-222222222222"
        const val DEVICE_A = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_B = "44444444-4444-4444-8444-444444444444"
        const val DEVICE_C = "99999999-9999-4999-8999-999999999999"
        const val SESSION_C = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val INSTANCE = "55555555-5555-4555-8555-555555555555"
        const val EPOCH = "66666666-6666-4666-8666-666666666666"
        const val SESSION = "77777777-7777-4777-8777-777777777777"
        const val OTHER_OWNER = "88888888-8888-4888-8888-888888888888"

        fun progress(time: Long = 1000) = WatchProgress("private-content", "movie", "Private title",
            "https://nas.invalid/poster", "https://nas.invalid/backdrop", "https://nas.invalid/logo",
            "smb://private-video", null, null, null, 10_000, 100_000, time,
            addonBaseUrl = "https://provider.invalid", description = "Private description")

        private fun sessionBody(owner: String, sessionId: String): String {
            val claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"$owner\",\"session_id\":\"$sessionId\"}".toByteArray())
            return "{\"access_token\":\"test.$claims.signature\",\"refresh_token\":\"supabase-refresh\",\"expires_at\":10000,\"user\":{\"id\":\"$owner\"}}"
        }

        private fun acknowledgements(request: PilotHttpRequest, status: String = "applied"): PilotHttpResponse {
            val operations = JsonParser.parseString(request.body).asJsonObject.getAsJsonArray("p_ops")
            val acks = operations.map { entry -> entry.asJsonObject.let {
                JsonObject().apply {
                    add("operation_id", it["operation_id"]); add("revision", it["revision"]); addProperty("status", status)
                }
            } }
            return PilotHttpResponse(200, PilotJson.gson.toJson(acks))
        }
    }
}
