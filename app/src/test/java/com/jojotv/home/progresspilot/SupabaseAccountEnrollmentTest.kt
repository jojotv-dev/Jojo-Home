package com.jojotv.home.progresspilot

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SupabaseAccountEnrollmentTest {
    private fun uuid() = UUID.randomUUID().toString()
    private val owner = uuid(); private val profile = uuid(); private val instance = uuid()
    private val a = uuid(); private val b = uuid(); private val d = uuid(); private val epoch = uuid()
    private val enrolled = PilotScope(owner, profile, 1, instance, d, a, b, epoch, accountEnrolled = true)
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun automaticAssociationDoesNotSelectAProgressSource() {
        assertEquals(PilotMode.NAS_ONLY, PilotLocalSettings(scope = enrolled).mode)
        assertFalse(AccountContentState().selected)
    }
    @Test fun anOrdinaryScopeCannotPretendToBeAFourthInstallation() {
        reject { enrolled.copy(accountEnrolled = false).validate() }; enrolled.validate()
    }
    @Test fun newInstallationUsesExactlyTheExistingContentHmacNamespace() {
        val key = ByteArray(32) { it.toByte() }
        val legacy = enrolled.copy(deviceId = a, accountEnrolled = false)
        assertEquals(PilotCaptureContext(legacy, 0, key).contentKey("synthetic-file"),
            PilotCaptureContext(enrolled, 0, key).contentKey("synthetic-file"))
    }
    @Test fun legacySerializedScopeStillHasAutomaticEnrollmentDisabled() {
        val json = com.google.gson.Gson().toJsonTree(enrolled).asJsonObject
        json.remove("accountEnrolled"); json.addProperty("deviceId", a)
        val legacy = com.google.gson.Gson().fromJson(json, PilotScope::class.java)
        assertFalse(legacy.accountEnrolled); legacy.validate()
    }
    @Test fun newInstallationNeverFallsBackToHistoricalRegistrationRpc() {
        var calls = 0
        val executor = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest): PilotHttpCall { calls++; error("Unexpected transport") }
        }
        val gate = PilotRequestGate(); val endpoint = PilotEndpoint("https://test.supabase.co", "sb_publishable_test")
        val session = PilotAuthSession(owner, uuid(), "synthetic-token", "synthetic-refresh", 9999)
        val permit = gate.allow(endpoint, owner, setOf("rest/v1/rpc/pilot_register_device"), enrolled)
        reject { SupabaseProgressApi(executor, gate).registerDevice(endpoint, session, enrolled, permit) }
        assertEquals(0, calls)
    }

    private val installation = uuid(); private val home = uuid(); private val sessionId = uuid(); private val nonce = uuid()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val endpoint = PilotEndpoint("https://test.supabase.co", "sb_publishable_test")
    private fun session(id: String = sessionId, user: String = owner) = PilotAuthSession(user, id, "synthetic-token", "synthetic-refresh", 9999)
    private fun recovery() = AccountRecoveryGrant(1, owner, installation, d, sessionId, home, nonce,
        profile, epoch, a, b, null, 4, java.util.Base64.getEncoder().encodeToString(key))
    private fun validate(grant: AccountRecoveryGrant) = grant.validate(owner, installation, d, sessionId, home, nonce)
    private fun configured(mode: PilotMode = PilotMode.NAS_ONLY): PilotLocalSettings {
        val config = PilotLocalSettings(deviceId = d, scope = enrolled, session = session(), endpoint = endpoint,
            hmacKeyBase64 = recovery().secret, cloudKeyEpoch = epoch, cloudKeyBindingEpoch = enrolled.epoch,
            mode = mode, cloudActivationId = uuid(), everActivated = true)
        val capture = PilotCaptureContext(enrolled, 0, key)
        return config.checkedKey(capture, nonce, capture.pairingKeyCode(nonce))
    }
    @Test fun freshLoginRestoresTheExistingKeyWithoutSelectingTheSourceInTheStore() {
        val previous = PilotLocalSettings(deviceId = d, endpoint = endpoint, session = session())
        val grant = recovery(); validate(grant)
        val recovered = previous.recoveredAccount(grant, grant.secretBytes(), instance)
        assertEquals(PilotMode.NAS_ONLY, recovered.mode)
        assertTrue(recovered.scope!!.accountEnrolled)
        assertArrayEquals(key, java.util.Base64.getDecoder().decode(recovered.hmacKeyBase64))
        assertTrue(recovered.keyCheckMatches(PilotCaptureContext(recovered.scope, 0, key)))
        assertEquals("", recovered.nasAuthorityDigest)
    }
    @Test fun reconnectPreservesTheSourceAndVerifiedKeyWhileTheAccountIsRestored() {
        val before = configured(PilotMode.SUPABASE_PROGRESS)
        val after = before.withAccountSession(endpoint, session(uuid()))
        assertEquals(before.scope, after.scope); assertEquals(before.mode, after.mode)
        assertEquals(before.cloudActivationId, after.cloudActivationId)
        assertTrue(after.keyCheckMatches(PilotCaptureContext(after.scope!!, 0, key)))
    }
    @Test fun reconnectCannotSwitchTheConfiguredAccount() {
        reject { configured().withAccountSession(endpoint, session(user = uuid())) }
    }
    @Test fun reconnectCannotSwitchTheConfiguredProject() {
        reject { configured().withAccountSession(PilotEndpoint("https://other.supabase.co", "sb_publishable_test"), session()) }
    }
    @Test fun recoveredSessionRetainsDeliveryEpochAndCloudActivation() {
        val before = configured(PilotMode.SUPABASE_PROGRESS)
        val after = before.recoveredAccount(recovery(), key, instance)
        assertEquals(before.scope, after.scope); assertEquals(before.cloudActivationId, after.cloudActivationId)
        assertEquals(PilotMode.SUPABASE_PROGRESS, after.mode)
    }
    @Test fun oldKeyIsNeverReplacedByARecoveryResponse() {
        reject { configured().recoveredAccount(recovery(), ByteArray(32) { 9 }, instance) }
    }
    @Test fun restoredProfileInstanceCannotReplaceAnOldDeliveryIdentity() {
        reject { configured().recoveredAccount(recovery(), key, uuid()) }
    }
    @Test fun anotherCloudEpochRequiresReviewRatherThanSilentRotation() {
        reject { configured().recoveredAccount(recovery().copy(key_epoch = uuid()), key, instance) }
    }
    @Test fun anotherProgressProfileCannotReplaceTheAssociation() {
        reject { configured().recoveredAccount(recovery().copy(profile = uuid()), key, instance) }
    }
    @Test fun restorationResponseMustMatchEveryIdentityAndTheFreshRequest() {
        val grant = recovery()
        listOf(grant.copy(owner = uuid()), grant.copy(installation = uuid()), grant.copy(device = uuid()),
            grant.copy(session = uuid()), grant.copy(home_profile = uuid()), grant.copy(nonce = uuid()),
            grant.copy(schema = 2), grant.copy(slot = 1), grant.copy(slot = 33), grant.copy(device_b = a)).forEach { reject { validate(it) } }
    }
    @Test fun recoveryCannotBeReplayedIntoANewAuthSession() {
        reject { configured().copy(session = session(uuid())).recoveredAccount(recovery(), key, instance) }
    }
    @Test fun retiredAssociationCannotBeReplacedWithAFreshAutomaticEnrollment() {
        reject { PilotLocalSettings(deviceId = d, session = session(), everActivated = true).recoveredAccount(recovery(), key, instance) }
    }
    @Test fun thirdLegacyDeviceRetainsItsOwnScopeFormat() {
        val legacy = recovery().copy(device_c = d, slot = 3)
        validate(legacy)
        val result = PilotLocalSettings(deviceId = d, session = session()).recoveredAccount(legacy, key, instance)
        assertEquals(d, result.scope!!.deviceC); assertFalse(result.scope.accountEnrolled)
    }
    @Test fun firstLegacyDeviceDoesNotReceiveTheThirdDeviceField() {
        val legacy = recovery().copy(device = a, slot = 1)
        legacy.validate(owner, installation, a, sessionId, home, nonce)
        val result = PilotLocalSettings(deviceId = a, session = session()).recoveredAccount(legacy, key, instance)
        assertNull(result.scope!!.deviceC); assertFalse(result.scope.accountEnrolled)
    }
    @Test fun recoveryRejectsMalformedAndNonCanonicalKeys() {
        listOf("", "private-key", java.util.Base64.getEncoder().encodeToString(ByteArray(31)),
            java.util.Base64.getEncoder().encodeToString(ByteArray(33))).forEach { bad -> reject { recovery().copy(secret = bad).secretBytes() } }
    }
    @Test fun responseParserRejectsExtraFieldsAndStringNumbers() {
        val json = com.google.gson.GsonBuilder().serializeNulls().create().toJsonTree(recovery()).asJsonObject
        validate(accountRecoveryGrant(json.toString()))
        json.addProperty("password", "synthetic-private-value"); reject { accountRecoveryGrant(json.toString()) }
        json.remove("password"); json.addProperty("slot", "4"); reject { accountRecoveryGrant(json.toString()) }
    }
    @Test fun anOlderAccountFileDoesNotInventAutoselectionPreferences() {
        val restored = com.google.gson.Gson().fromJson("{}", AccountLocalState::class.java)
        assertNull(restored.login); assertNull(restored.recoveryIdentity)
    }
    @Test fun disabledSourcesSurviveAReconnectRequest() {
        val chosen = AccountLoginChoices(progress = false, iptv = true, pending = false)
        val resumed = chosen.copy(pending = true)
        assertFalse(resumed.progress); assertTrue(resumed.iptv)
        assertFalse(com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(chosen), AccountLoginChoices::class.java).progress)
    }
    @Test fun credentialsAndRecoveredKeyDoNotAppearInDiagnosticStrings() {
        val grant = recovery()
        assertFalse(grant.toString().contains(grant.secret)); assertFalse(configured().toString().contains(grant.secret))
        assertFalse(PilotHttpRequest("https://test.supabase.co", emptyMap(), grant.secret).toString().contains(grant.secret))
        assertFalse(PilotHttpResponse(200, grant.secret).toString().contains(grant.secret))
    }
    @Test fun recoveryRpcStillRequiresAnExplicitPathPermit() {
        var calls = 0
        val executor = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest): PilotHttpCall { calls++; error("Unexpected transport") }
        }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, owner, setOf("rest/v1/rpc/account_settings_read"))
        reject { SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(), "account_sync_recover", emptyMap<String, String>(), permit) }
        assertEquals(0, calls)
    }
    @Test fun versionTwoSettingsReaderPassesDispatchAndAccountPermitTogether() {
        var calls = 0
        val reply = """{"schema":1,"initialized":true,"head_revision":0,"rows":[]}"""
        val executor = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest): PilotHttpCall {
                calls++
                assertEquals(endpoint.url + "/rest/v1/rpc/account_settings_read_v2", request.url)
                assertEquals("Bearer synthetic-token", request.headers["Authorization"])
                val body = com.google.gson.JsonParser.parseString(request.body).asJsonObject
                assertEquals(setOf("p_device", "p_proof", "p_after"), body.keySet())
                assertEquals(0L, body["p_after"].asLong)
                return object : PilotHttpCall {
                    override fun execute() = PilotHttpResponse(200, reply)
                    override fun cancel() {}
                }
            }
        }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, owner, accountRpcNames.map { "rest/v1/rpc/$it" }.toSet())
        val body = mapOf("p_device" to installation, "p_proof" to "a".repeat(64), "p_after" to 0L)
        val result = SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(), "account_settings_read_v2", body, permit)
        assertTrue(AccountProtocol.page(result, installation).initialized)
        assertEquals(1, calls)
        val legacyPermit = gate.allow(endpoint, owner, setOf("rest/v1/rpc/account_settings_read"))
        reject { SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(), "account_settings_read_v2", body, legacyPermit) }
        assertEquals(1, calls)
    }
    @Test fun netflixRpcPassesDispatchAndAccountPermitTogether() {
        var calls = 0
        val reply = """{"schema":1,"next_cursor":0,"head_revision":0,"rows":[]}"""
        val executor = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest): PilotHttpCall {
                calls++
                assertEquals(endpoint.url + "/rest/v1/rpc/account_netflix_resumes_sync", request.url)
                assertEquals("Bearer synthetic-token", request.headers["Authorization"])
                val body = com.google.gson.JsonParser.parseString(request.body).asJsonObject
                assertEquals(setOf("p_device", "p_proof", "p_after", "p_ops"), body.keySet())
                assertEquals(0, body["p_ops"].asJsonArray.size())
                return object : PilotHttpCall {
                    override fun execute() = PilotHttpResponse(200, reply)
                    override fun cancel() {}
                }
            }
        }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, owner, accountRpcNames.map { "rest/v1/rpc/$it" }.toSet())
        val body = mapOf("p_device" to installation, "p_proof" to "a".repeat(64), "p_after" to 0L, "p_ops" to emptyList<Any>())
        assertTrue(accountNetflixPage(SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(),
            "account_netflix_resumes_sync", body, permit), 0).rows.isEmpty())
        assertEquals(1, calls)
        val otherPermit = gate.allow(endpoint, owner, setOf("rest/v1/rpc/account_settings_read_v2"))
        reject { SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(), "account_netflix_resumes_sync", body, otherPermit) }
        assertEquals(1, calls)
    }
    @Test fun recoveryRpcUsesTheAuthenticatedAccountEndpointWithoutAnyNasCredential() {
        val reply = com.google.gson.GsonBuilder().serializeNulls().create().toJson(recovery())
        var calls = 0
        val executor = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest): PilotHttpCall {
                calls++
                assertEquals(endpoint.url + "/rest/v1/rpc/account_sync_recover", request.url)
                assertEquals("Bearer synthetic-token", request.headers["Authorization"])
                val body = com.google.gson.JsonParser.parseString(request.body).asJsonObject
                assertEquals(setOf("p_device", "p_proof", "p_target", "p_home_profile", "p_nonce"), body.keySet())
                assertFalse(request.toString().contains("synthetic-token"))
                return object : PilotHttpCall {
                    override fun execute() = PilotHttpResponse(200, reply)
                    override fun cancel() {}
                }
            }
        }
        val gate = PilotRequestGate(); val permit = gate.allow(endpoint, owner, setOf("rest/v1/rpc/account_sync_recover"))
        val body = mapOf("p_device" to installation, "p_proof" to "a".repeat(64), "p_target" to d,
            "p_home_profile" to home, "p_nonce" to nonce)
        val grant = accountRecoveryGrant(SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(), "account_sync_recover", body, permit))
        validate(grant); assertArrayEquals(key, grant.secretBytes()); assertEquals(1, calls)
    }
    @Test fun signingInAgainAfterLocalLogoutKeepsTheVerifiedSameAccountKey() {
        val before = configured().copy(session = null)
        val after = before.withAccountSession(endpoint, session(uuid()))
        assertEquals(before.keyCheckCode, after.keyCheckCode)
        assertTrue(after.keyCheckMatches(PilotCaptureContext(after.scope!!, 0, key)))
    }

    private fun failedRecovery(reply: String, status: Int = 500, rpc: String = "account_sync_recover"): java.io.IOException {
        val executor = object : PilotHttpExecutor {
            override fun prepare(request: PilotHttpRequest) = object : PilotHttpCall {
                override fun execute() = PilotHttpResponse(status, reply)
                override fun cancel() {}
            }
        }
        val gate = PilotRequestGate()
        val permit = gate.allow(endpoint, owner, setOf("rest/v1/rpc/$rpc"))
        try {
            SupabaseProgressApi(executor, gate).accountRpc(endpoint, session(), rpc, emptyMap<String, String>(), permit)
        } catch (failure: java.io.IOException) { return failure }
        throw AssertionError("Expected an HTTP failure")
    }

    @Test fun emptyAccountCofferIsAnExplicitPrerequisiteRatherThanATransientServerFailure() {
        val failure = failedRecovery("""{"code":"55000","message":"ACCOUNT_RECOVERY_NOT_READY","details":null,"hint":null}""")
        assertTrue(failure is AccountRecoveryNotReady)
        assertEquals("ACCOUNT_RECOVERY_NOT_READY", failure.message)
    }

    @Test fun homeMasksAndArtworkUseTheSameApprovedAccountRpcDispatchAsSettings() {
        for (rpc in listOf("account_resume_masks_sync", "account_home_artwork_sync")) {
            assertTrue(rpc in accountRpcNames)
            assertEquals(500, (failedRecovery("{}", rpc = rpc) as PilotHttpFailure).status)
        }
        assertFalse("arbitrary_sql" in accountRpcNames)
    }

    @Test fun readinessDetectionDoesNotChangeOtherRpcFailures() {
        val reply = """{"code":"55000","message":"ACCOUNT_RECOVERY_NOT_READY"}"""
        assertEquals(500, (failedRecovery(reply, rpc = "account_settings_read") as PilotHttpFailure).status)
        assertEquals(401, (failedRecovery(reply, status = 401) as PilotHttpFailure).status)
    }

    @Test fun differentSqlFailuresRemainServerFailures() {
        listOf("""{"code":"55000","message":"ACCOUNT_RECOVERY_VAULT_INVALID"}""",
            """{"code":"42501","message":"ACCOUNT_RECOVERY_NOT_READY"}""").forEach {
            assertEquals(500, (failedRecovery(it) as PilotHttpFailure).status)
        }
    }

    @Test fun malformedOrUnexpectedReadinessResponsesDoNotEscapeTheHttpErrorBoundary() {
        listOf("not-json", "[]", "null", "{}", """{"code":55000,"message":"ACCOUNT_RECOVERY_NOT_READY"}""",
            """{"code":"55000","message":["ACCOUNT_RECOVERY_NOT_READY"]}""").forEach {
            assertEquals(500, (failedRecovery(it) as PilotHttpFailure).status)
        }
    }

    @Test fun oversizedErrorsAreNeverParsedAsAccountReadiness() {
        val reply = """{"code":"55000","message":"ACCOUNT_RECOVERY_NOT_READY","details":"${"x".repeat(4096)}"}"""
        assertTrue(failedRecovery(reply) is PilotHttpFailure)
    }

    @Test fun serverErrorDetailsNeverEnterTheDiagnosticException() {
        val privateDetail = "synthetic-private-value"
        val failure = failedRecovery("""{"code":"55000","message":"ACCOUNT_RECOVERY_NOT_READY","details":"$privateDetail"}""")
        assertTrue(failure is AccountRecoveryNotReady)
        assertFalse(failure.toString().contains(privateDetail))
        assertFalse(failure.stackTraceToString().contains(privateDetail))
    }
}
