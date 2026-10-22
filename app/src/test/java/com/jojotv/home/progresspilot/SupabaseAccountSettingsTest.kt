package com.jojotv.home.progresspilot

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SupabaseAccountSettingsTest {
    private val organizationPrefix = "organization:${"a".repeat(64)}:"

    @Test fun upgradedReaderRecoversSettingsOlderThanTheSavedLegacyCursor() {
        val cursor = AccountReadCursor()
        val section = AccountEntry("account", AccountSettingsCatalog.ACCOUNT_SUBJECT, "home.home_section_order",
            JsonParser.parseString("[\"resumes\",\"apps\",\"movies\"]"), 120)
        val savedLegacyHead = 200L
        val after = cursor.after("account/session/installation", true, savedLegacyHead)
        val recovered = AccountPage(1, true, savedLegacyHead, listOf(section).filter { it.revision > after })
        recovered.validate("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        assertEquals(0L, after)
        assertEquals(listOf(section), recovered.rows)
        cursor.applied("account/session/installation")
        val nextAfter = cursor.after("account/session/installation", true, recovered.head_revision)
        assertEquals(savedLegacyHead, nextAfter)
        assertTrue(listOf(section).filter { it.revision > nextAfter }.isEmpty())
    }

    @Test fun interruptedRestoreNewIdentityAndProcessRestartRequireAnotherFullRead() {
        val cursor = AccountReadCursor()
        val identity = "account/session/installation"
        assertEquals(0L, cursor.after(identity, true, 200))
        // A failed restore never calls applied, even if its page was already saved locally.
        assertEquals(0L, cursor.after(identity, true, 210))
        cursor.applied(identity)
        assertEquals(210L, cursor.after(identity, true, 210))
        assertEquals(0L, cursor.after(identity, false, 210))
        for (next in listOf("account/other-session/installation", "other-account/session/installation", "account/session/other-installation"))
            assertEquals(0L, cursor.after(next, true, 210))
        assertEquals(0L, AccountReadCursor().after(identity, true, 210))
    }

    @Test fun homeSectionOrderUsesTheSameWireListForSnapshotAndCompareBeforeRestore() {
        val value = accountHomeSettingValue("home.home_section_order", "resumes|apps|movies|series|live|shortcuts")
        val spec = AccountSettingsCatalog.spec("home.home_section_order")!!
        assertEquals("account", spec.scope); assertTrue(spec.accepts(value))
        assertEquals("resumes|apps|movies|series|live|shortcuts", value.asJsonArray.joinToString("|") { it.asString })
        assertTrue(spec.accepts(accountHomeSettingValue(spec.key, "")))
        for (bad in listOf("apps|apps", "apps|private", "https://private"))
            assertFalse(spec.accepts(accountHomeSettingValue(spec.key, bad)))
        assertNull(AccountSettingsCatalog.spec("home.continue_watching_module_style"))
        assertEquals("device", AccountSettingsCatalog.spec("home.apps_order")!!.scope)
    }

    @Test fun organizationSharesOnlyExplicitVisibilityNamesAndOrdering() {
        val settings = mapOf(organizationPrefix + "hidden" to "true", organizationPrefix + "groupName" to "Informations",
            organizationPrefix + "itemOrder" to "[\"iptv:provider:live:${"b".repeat(64)}\"]",
            organizationPrefix + "groupOrder:LIVE" to "[\"12\", \"34\"]", organizationPrefix + "knownGroups:LIVE" to "[\"12\"]",
            organizationPrefix + "newGroupsVisible:LIVE" to "false", organizationPrefix + "externalPlayerMode" to "all",
            organizationPrefix + "blocked" to "true", organizationPrefix + "category:LIVE" to "12", "pinHash" to "local",
            "sourceUrl" to "https://private.invalid", "bufferSize" to "large")
        val value = AccountIptvOrganization.capture(settings)
        assertTrue(AccountSettingsCatalog.spec("iptv.organization")!!.accepts(value))
        assertEquals(6, value.asJsonObject.size())
        for (text in listOf("pinHash", "https://", "sourceUrl", "externalPlayerMode", "blocked", "category:LIVE", "bufferSize"))
            assertFalse(text, value.toString().contains(text))
        assertEquals("[\"12\",\"34\"]", value.asJsonObject[organizationPrefix + "groupOrder:LIVE"].asString)
        assertTrue(AccountSettingsCatalog.iptvRecord("setting:${organizationPrefix}hidden"))
        assertFalse(AccountSettingsCatalog.iptvRecord("setting:${organizationPrefix}externalPlayerMode"))
    }

    @Test fun threeInstallationsRestoreOrganizationAndResetItWithoutChangingTheirPlayerSettings() {
        val shared = mapOf(organizationPrefix + "hidden" to "true", organizationPrefix + "groupName" to "Actualités")
        val wire = AccountIptvOrganization.capture(shared).toString()
        for (player in listOf("small", "medium", "large")) {
            val local = mapOf("bufferSize" to player, organizationPrefix + "externalPlayerMode" to "never")
            val restored = AccountIptvOrganization.patch(local, null, wire)!!
            assertEquals(player, restored["bufferSize"])
            assertEquals("never", restored[organizationPrefix + "externalPlayerMode"])
            assertEquals(shared, restored.filterKeys(AccountIptvOrganization::key))
            assertEquals(local, AccountIptvOrganization.patch(restored, wire, null))
        }
    }

    @Test fun aDelayedOrganizationRestorePreservesANewerLocalEdit() {
        val original = mapOf(organizationPrefix + "groupName" to "Ancien nom")
        val expected = AccountIptvOrganization.capture(original).toString()
        val edited = original + (organizationPrefix + "groupName" to "Nouveau nom")
        assertNull(AccountIptvOrganization.patch(edited, expected, null))
        assertEquals("Nouveau nom", edited[organizationPrefix + "groupName"])
    }

    @Test fun organizationRejectsUnknownFieldsPrivateAddressesMalformedListsAndOversizedPayloads() {
        val spec = AccountSettingsCatalog.spec("iptv.organization")!!
        for ((key, value) in listOf(organizationPrefix + "hidden" to "yes", organizationPrefix + "groupName" to "https://private.invalid",
            organizationPrefix + "groupName" to "control\nname", organizationPrefix + "blocked" to "true",
            organizationPrefix + "groupOrder:LIVE" to "[\"12\",\"12\"]", organizationPrefix + "itemOrder" to "[\"https://private\"]",
            "organization:private:groupName" to "name")) {
            val bad = com.google.gson.JsonObject().apply { addProperty(key, value) }
            assertFalse(key, spec.accepts(bad))
        }
        val huge = com.google.gson.JsonObject().apply { repeat(513) { addProperty("organization:${it.toString(16).padStart(64, '0')}:hidden", "true") } }
        assertFalse(spec.accepts(huge))
        val large = com.google.gson.JsonObject().apply { repeat(512) { addProperty("organization:${it.toString(16).padStart(64, '0')}:groupName", "é".repeat(160)) } }
        assertFalse(spec.accepts(large))
    }

    @Test fun providerOrderUsesOpaqueInstallationIndependentSourceIds() {
        val spec = AccountSettingsCatalog.spec("iptv.sourceOrder")!!
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertEquals("account", spec.scope)
        assertTrue(spec.accepts(JsonParser.parseString("[\"$id\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"$id\",\"$id\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"https://private.invalid\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"com.example.player\"]")))
    }

    private val artKey = "home_videos_programme_portrait_artwork_12ab"
    private val artUrl = "https://image.tmdb.org/t/p/w500/French.jpg"
    private fun artMetadata(url: String = artUrl, time: Long = 100, id: Int = 17) =
        """{"artworkUrl":"$url","selectedAt":$time,"tmdbId":$id,"tmdbMediaType":"tv","mediaPath":"smb://private/video.mkv","overview":"private title"}"""
    private fun artRow(choice: AccountHomeArtworkChoice, revision: Long = 1): String =
        com.google.gson.Gson().toJsonTree(choice).asJsonObject.apply { addProperty("revision", revision) }.toString()
    private fun artPage(rows: String, cursor: Long = 1, head: Long = cursor, after: Long = 0) =
        accountHomeArtworkPage("""{"schema":1,"next_cursor":$cursor,"head_revision":$head,"rows":$rows}""", after)

    @Test fun freeboxProgrammeChoiceKeepsTheExactPublicImageAndTmdbIdentityOnly() {
        val value = AccountHomeArtwork.capture(artKey, artUrl, artMetadata(), null, 200)!!
        assertEquals("w500:French.jpg", value.image); assertEquals(17, value.tmdb_id); assertEquals("tv", value.media_type)
        val wire = com.google.gson.Gson().toJson(value)
        listOf("smb://", "private", "https://", "overview", "mediaPath").forEach { assertFalse(wire.contains(it)) }
        assertEquals(value, artPage("[${artRow(value)}]").rows.single().value)
    }

    @Test fun privateAndAutomaticArtworkNeverBecomeASharedManualChoice() {
        for (url in listOf("file:///private.jpg", "content://photos/17", "https://provider/token/image", "http://image.tmdb.org/t/p/w500/A.jpg"))
            assertNull(AccountHomeArtwork.capture(artKey, url, artMetadata(url), null, 200))
        assertNull(AccountHomeArtwork.capture(artKey, artUrl, "{}", null, 200))
        assertNull(AccountHomeArtwork.capture(artKey, artUrl, artMetadata(time = 0), null, 200))
        reject { AccountHomeArtworkChoice("home_videos_file_artwork_smb://private", "A.jpg", 0, "", 1).validate() }
        reject { AccountHomeArtworkChoice(artKey, "https://private/A.jpg", 0, "", 1).validate() }
    }

    @Test fun delayedMetadataCannotPublishDefaultsOverAnExplicitProgrammeChoice() {
        val before = AccountHomeArtwork.capture(artKey, artUrl, artMetadata(), null, 200)!!
        assertEquals(before, AccountHomeArtwork.capture(artKey, artUrl, null, before, 300))
        assertEquals(before, AccountHomeArtwork.capture(artKey, "https://image.tmdb.org/t/p/w500/Other.jpg", artMetadata(), before, 300))
    }

    @Test fun newSelectionAndResetAdvanceTheClockOnceAndSurviveGsonRestart() {
        val first = AccountHomeArtwork.capture(artKey, artUrl, artMetadata(), null, 200)!!
        assertEquals(first, AccountHomeArtwork.capture(artKey, artUrl, artMetadata(), first, 300))
        val url = "https://image.tmdb.org/t/p/original/New.jpg"
        val second = AccountHomeArtwork.capture(artKey, url, artMetadata(url, 90, 19), first, 300)!!
        assertEquals(300L, second.stamp); assertEquals(19, second.tmdb_id)
        val reset = AccountHomeArtwork.capture(artKey, null, null, second, 299)!!
        assertEquals(301L, reset.stamp); assertEquals("", reset.image); assertEquals(0, reset.tmdb_id)
        assertEquals(reset, AccountHomeArtwork.capture(artKey, null, null, reset, 400))
        val state = AccountHomeArtworkSync(known = mapOf(artKey to reset), published = mapOf(artKey to second))
        val gson = com.google.gson.Gson()
        assertEquals(state, gson.fromJson(gson.toJson(state), AccountHomeArtworkSync::class.java))
        assertEquals(listOf(reset), state.pending(state.known))
    }

    @Test fun artworkMergeConvergesWithOlderDevicesAndEqualClockSelections() {
        val first = AccountHomeArtworkChoice(artKey, "A.jpg", 17, "tv", 100)
        val second = first.copy(image = "Z.jpg")
        assertEquals(second, AccountHomeArtwork.newest(first, second))
        assertEquals(second, AccountHomeArtwork.newest(second, first))
        assertEquals(second, AccountHomeArtwork.newest(second, first.copy(stamp = 99)))
        assertEquals(first.copy(stamp = 101), AccountHomeArtwork.newest(second, first.copy(stamp = 101)))
    }

    @Test fun artworkPagesRejectUnrelatedKeysPrivateUrlsUnknownFieldsAndInvalidCursors() {
        val choice = AccountHomeArtworkChoice(artKey, "A.jpg", 17, "tv", 100)
        reject { artPage("[${artRow(choice)}]", cursor = 0) }
        reject { artPage("[]", cursor = 0, after = 1) }
        reject { artPage("[${artRow(choice)},${artRow(choice, 2)}]", 2) }
        reject { artPage("[${artRow(choice).replace("\"revision\":1", "\"revision\":1,\"mediaPath\":\"smb://private\"")}]") }
        reject { artPage("[${artRow(choice.copy(image = "file:///private.jpg"))}]") }
        reject { artPage("[${artRow(choice).replace("\"tmdb_id\":17", "\"tmdb_id\":2147483648")}]") }
        reject { artPage("[${artRow(choice).replace("\"stamp\":100", "\"stamp\":\"100\"")}]") }
        reject { artPage("[${artRow(choice.copy(stamp = 0))}]") }
        reject { artPage("[${artRow(choice.copy(media_type = "person"))}]") }
        reject { accountHomeArtworkPage("""{"schema":4294967297,"next_cursor":0,"head_revision":0,"rows":[]}""", 0) }
        reject { artPage("[]", cursor = -1, head = -1, after = -1) }
        reject { artPage("[${artRow(choice, Long.MAX_VALUE)}]", cursor = Long.MAX_VALUE, head = Long.MAX_VALUE, after = Long.MAX_VALUE) }
    }

    @Test fun artworkAcknowledgementDoesNotLoseASelectionMadeDuringTheRequest() {
        val first = AccountHomeArtworkChoice(artKey, "A.jpg", 17, "tv", 100)
        val second = first.copy(image = "Z.jpg", stamp = 101)
        val state = AccountHomeArtworkSync(known = mapOf(artKey to first))
        val page = artPage("[${artRow(first)}]")
        val next = state.acknowledge(listOf(first), page, mapOf(artKey to second))
        assertEquals(1L, next.cursor); assertEquals(listOf(second), next.pending(next.known))
        assertTrue(next.pending(mapOf(artKey to first)).isEmpty())
    }

    @Test fun portraitAndHeroAreIndependentAndSyncBookkeepingNeverMasqueradesAsArtwork() {
        assertTrue(AccountHomeArtwork.key(artKey))
        assertTrue(AccountHomeArtwork.key(artKey.replace("portrait", "hero")))
        assertTrue(AccountHomeArtwork.key("home_videos_series_episode_artwork_ab12"))
        assertFalse(AccountHomeArtwork.key(artKey + "_metadata"))
        assertTrue(AccountHomeArtwork.preference(artKey + "_metadata"))
        assertFalse(AccountHomeArtwork.preference(AccountHomeArtwork.SYNC_PREFIX + "test"))
    }

    private val device = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val key = AccountRowId("account", AccountSettingsCatalog.ACCOUNT_SUBJECT, "home.icon_size")
    private val gson = GsonBuilder().serializeNulls().create()
    private fun reject(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    private fun change(value: String = "medium") = AccountJournal().change(key, JsonPrimitive(value)).prepare()
    private fun ack(j: AccountJournal, revision: Long = 1) = j.acknowledge(j.pending, j.pending.map { AccountAck(it.operation_id, "applied", revision) })

    @Test fun resumeRemovalUsesOnlyAnOpaqueStableIdentity() {
        val key = "external_app_resume_tizentube_" + "a".repeat(64)
        val identity = AccountResumeMasks.identity(key)
        assertTrue(AccountResumeMasks.external(key)); assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
        assertEquals(identity, AccountResumeMasks.identity(key))
        assertNotEquals(identity, AccountResumeMasks.identity(key.replace('a', 'b')))
        val wire = AccountResumeMasks.encode(mapOf(identity to 100L))
        assertFalse(wire.contains("tizentube")); assertFalse(wire.contains("external_app"))
        assertFalse(wire.contains("http")); assertFalse(wire.contains("title"))
    }
    @Test fun externalMasksDoNotExportFilePathsNativeIptvOrJojoProgress() {
        listOf("external-playback|iptv:source:movie:17", "freebox-video|smb://nas/private/file.mkv",
            "com.jojotv.home|film", "com.nuvio.tv|episode", "http://private-token", "external_app_resume_private").forEach {
            assertFalse(AccountResumeMasks.external(it))
        }
        assertTrue(AccountResumeMasks.external("com.example.player|film|1|2"))
    }
    @Test fun legacyExternalRemovalIsImportedWithoutExportingTheLegacyRecord() {
        val hidden = "external_app_resume_tizentube_" + "a".repeat(64)
        val values = AccountResumeMasks.legacy(setOf(hidden, "freebox-video|smb://nas/private"), 1,
            { 200L }, { 0L })
        assertEquals(mapOf(AccountResumeMasks.identity(hidden) to 200L), values)
        assertFalse(AccountResumeMasks.encode(values).contains("smb"))
    }
    @Test fun aLegacyRemovalWithoutProfileEvidenceDoesNotLeakToAnotherProfile() {
        val hidden = "external_app_resume_netflix_" + "a".repeat(64)
        assertEquals(1L, AccountResumeMasks.legacy(setOf(hidden), 1, { 0L }, { 0L }).values.single())
        assertTrue(AccountResumeMasks.legacy(setOf(hidden), 2, { 0L }, { 0L }).isEmpty())
        assertEquals(-300L, AccountResumeMasks.legacy(setOf(hidden), 1, { 200L }, { 300L }).values.single())
    }
    @Test fun deletionsOnDifferentTvsMergeWithoutLosingEitherVideo() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val merged = AccountResumeMasks.merge(mapOf(a to 100L), mapOf(b to 200L))
        assertEquals(mapOf(a to 100L, b to 200L), merged)
        assertEquals(merged, AccountResumeMasks.merge(merged, emptyMap()))
    }
    @Test fun aStaleObservationOrReplayCannotUndoANewerRemoval() {
        val a = "a".repeat(64)
        assertEquals(300L, AccountResumeMasks.merge(mapOf(a to 300L), mapOf(a to -200L))[a])
        assertEquals(300L, AccountResumeMasks.merge(mapOf(a to 300L), mapOf(a to -300L))[a])
        assertEquals(300L, AccountResumeMasks.merge(mapOf(a to -300L), mapOf(a to 300L))[a])
        assertEquals(-301L, AccountResumeMasks.merge(mapOf(a to 300L), mapOf(a to -301L))[a])
    }
    @Test fun aNewExplicitIntentAdvancesAnObservedClockEvenIfTheDeviceClockIsBehind() {
        val a = "a".repeat(64)
        assertEquals(-301L, AccountResumeMasks.change(mapOf(a to 300L), a, false, 100)[a])
        assertEquals(302L, AccountResumeMasks.change(mapOf(a to -301L), a, true, 100)[a])
    }
    @Test fun maskStorageRoundTripPreservesRestorationsAndRejectsUnsafeValues() {
        val values = mapOf("a".repeat(64) to 200L, "b".repeat(64) to -300L)
        assertEquals(values, AccountResumeMasks.decode(AccountResumeMasks.encode(values)))
        listOf("""{"url":200}""", """{"${"a".repeat(64)}":"200"}""", """{"${"a".repeat(64)}":0}""",
            """{"${"a".repeat(64)}":1.5}""", """{"${"a".repeat(64)}":9223372036854775807}""", "[]").forEach {
            reject { AccountResumeMasks.decode(it) }
        }
    }
    private fun maskPage(rows: String, cursor: Long = 1, head: Long = cursor, after: Long = 0) = accountResumeMaskPage(
        """{"schema":1,"next_cursor":$cursor,"head_revision":$head,"rows":$rows}""", after)
    private fun maskRow(key: String = "a".repeat(64), stamp: Long = 200, revision: Long = 1, profile: String = device) =
        """{"profile":"$profile","key":"$key","stamp":$stamp,"revision":$revision}"""
    @Test fun resumeMaskPagesContainNoRawAppRecordAndKeepProfileIdentity() {
        val row = maskPage("[${maskRow()}]").rows.single()
        assertEquals(device, row.profile); assertEquals("a".repeat(64), row.key); assertEquals(200L, row.stamp)
        assertNotEquals(row.token(), row.copy(profile = other).token())
        assertTrue(maskPage("[]", 0).rows.isEmpty())
        reject { maskPage("[${maskRow().replace("\"revision\":1", "\"revision\":1,\"intent\":\"https://private\"")}]") }
    }
    @Test fun maskPagesRejectCursorRegressionDuplicateIdentitiesAndInvalidClocks() {
        reject { maskPage("[]", cursor = 0, after = 1) }
        reject { maskPage("[${maskRow()},${maskRow(revision = 2)}]", 2) }
        reject { maskPage("[${maskRow(stamp = 0)}]") }
        reject { maskPage("[${maskRow(stamp = Long.MIN_VALUE)}]") }
        reject { maskPage("[${maskRow(profile = "not-a-profile")}]") }
        reject { maskPage("[${maskRow(revision = 2)}]", 1) }
    }
    @Test fun maskRequestsAreBatchedAndSuccessfulReplayProducesNoNewWrites() {
        val values = (1..60).map { AccountResumeMask(device, "%064x".format(it), 200) }
        val initial = AccountResumeMaskSync(listOf(device))
        val sent = initial.pending(values); assertEquals(50, sent.size)
        val next = initial.acknowledge(sent, AccountResumeMaskPage(0, 0, emptyList()))
        assertEquals(10, next.pending(values).size)
        assertTrue(next.acknowledge(next.pending(values), AccountResumeMaskPage(0, 0, emptyList())).pending(values).isEmpty())
    }
    @Test fun anAcknowledgementCannotConsumeARemovalMadeDuringTheRequest() {
        val old = AccountResumeMask(device, "a".repeat(64), 200)
        val current = old.copy(stamp = 300)
        val next = AccountResumeMaskSync(listOf(device)).acknowledge(listOf(old), AccountResumeMaskPage(1, 1, listOf(old.copy(revision = 1))))
        assertEquals(listOf(current), next.pending(listOf(current)))
        val restored = gson.fromJson(gson.toJson(next), AccountResumeMaskSync::class.java)
        assertEquals(listOf(current), restored.pending(listOf(current)))
    }
    @Test fun aReceivedRemovalIsNotEchoedAsANewLocalChange() {
        val row = AccountResumeMask(device, "a".repeat(64), 300, 1)
        val next = AccountResumeMaskSync(listOf(device)).acknowledge(emptyList(), AccountResumeMaskPage(1, 1, listOf(row)))
        assertTrue(next.pending(listOf(row.copy(revision = 0))).isEmpty())
    }

    @Test fun onlyDeclaredKeysAreExportable() {
        listOf("home.nas_token", "home.smb_password", "iptv.userAgent", "iptv.udpProxy", "iptv.pinHash", "iptv.epgSources", "home.wallpaper_uri",
            "iptv.sourceUrl", "iptv.savedPrograms", "home.home_file_shortcuts").forEach { assertNull(AccountSettingsCatalog.spec(it)) }
        assertEquals(AccountSettingsCatalog.specs.size, AccountSettingsCatalog.specs.map { it.key }.toSet().size)
    }
    @Test fun orderAndHardwareAreScopedPerInstallation() {
        listOf("home.apps_order", "home.fire_tv_home_redirect", "iptv.bufferSize", "iptv.afrLive", "iptv.remote.player.ok").forEach {
            assertEquals("device", AccountSettingsCatalog.spec(it)!!.scope)
        }
    }
    @Test fun languagesAndNextEpisodeAreProfileScoped() {
        listOf("iptv.audioLanguage", "iptv.subtitleLanguage", "iptv.autoNextEpisode", "profiles.home", "profiles.iptv").forEach {
            assertEquals("profile", AccountSettingsCatalog.spec(it)!!.scope)
        }
    }
    @Test fun callerCannotMakeDeviceSettingCommon() {
        reject { AccountSettingsCatalog.validate(AccountRowId("account", AccountSettingsCatalog.ACCOUNT_SUBJECT, "iptv.afrLive"), JsonPrimitive(true)) }
    }
    @Test fun commonScopeRequiresReservedSubject() { reject { AccountSettingsCatalog.validate(key.copy(subject = device), JsonPrimitive("small")) } }
    @Test fun deviceScopeCannotUseReservedSubject() { reject { AccountSettingsCatalog.validate(AccountRowId("device", AccountSettingsCatalog.ACCOUNT_SUBJECT, "home.apps_order"), JsonNull.INSTANCE) } }
    @Test fun fieldsCannotCarryUrlOrPath() {
        val spec = AccountSettingsCatalog.spec("home.icon_size")!!
        assertFalse(spec.accepts(JsonPrimitive("https://provider/private-token")))
        assertFalse(spec.accepts(JsonPrimitive("/storage/video.mkv")))
    }
    @Test fun booleanDoesNotAcceptStringCoercion() { assertFalse(AccountSettingsCatalog.spec("home.dynamic_wallpaper")!!.accepts(JsonPrimitive("true"))) }
    @Test fun numberDoesNotAcceptFractionOrString() {
        val spec = AccountSettingsCatalog.spec("home.home_rows")!!
        assertFalse(spec.accepts(JsonPrimitive("2"))); assertFalse(spec.accepts(JsonPrimitive(2.5))); assertFalse(spec.accepts(JsonPrimitive(20)))
        assertTrue(spec.accepts(JsonPrimitive(2)))
    }
    @Test fun percentagesPreserveHalfSteps() {
        val spec = AccountSettingsCatalog.spec("iptv.nextEpisodeThresholdPercent")!!
        assertTrue(spec.accepts(JsonPrimitive("98.5"))); assertFalse(spec.accepts(JsonPrimitive("NaN")))
        assertFalse(spec.accepts(JsonPrimitive("90"))); assertFalse(spec.accepts(JsonPrimitive("101")))
    }
    @Test fun orderRejectsDuplicatesAndNonPackages() {
        val spec = AccountSettingsCatalog.spec("home.apps_order")!!
        assertTrue(spec.accepts(JsonParser.parseString("[\"com.example.one\",\"com.example.two\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"com.example.one\",\"com.example.one\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"smb://nas/share\"]")))
    }
    @Test fun appsOrderUsesTheExistingLauncherSeparator() {
        val raw = "com.example.first|com.example.second"
        val value = accountAppsOrder(raw)
        assertTrue(AccountSettingsCatalog.spec("home.apps_order")!!.accepts(value))
        assertEquals(2, value.size()); assertEquals(raw, accountAppsOrder(value))
    }
    @Test fun profilePayloadHasOnlyApprovedMetadata() {
        val spec = AccountSettingsCatalog.spec("profiles.iptv")!!
        val value = accountProfileValue("Stéphanie", "#D3A033", "builtin:1")
        assertTrue(spec.accepts(value))
        value.addProperty("password", "must-never-be-exported"); assertFalse(spec.accepts(value))
    }
    @Test fun personalAvatarLocationIsExcluded() {
        assertEquals("", accountProfileValue("Arnaud", "#D3A033", "https://private/avatar.png")["avatar"].asString)
        assertEquals("", accountProfileValue("Arnaud", "#D3A033", "/data/user/avatar.jpg")["avatar"].asString)
    }
    @Test fun onlyBundledAvatarIdentifiersMayBeBackedUp() {
        assertEquals("animal:chat", accountProfileValue("A", "#D3A033", "animal:chat")["avatar"].asString)
        assertEquals("", accountProfileValue("A", "#D3A033", "photo-personnelle.jpg")["avatar"].asString)
        assertEquals("photo-personnelle.jpg", accountRestoredAvatar("photo-personnelle.jpg", ""))
        assertEquals("animal:chat", accountRestoredAvatar("photo-personnelle.jpg", "animal:chat"))
    }
    @Test fun profileAvatarCatalogueStaysCompleteWithoutLegacyUiSources() {
        val avatars = AccountSettingsCatalog.avatars
        assertEquals(avatars.size, avatars.distinct().size)
        assertTrue("animal:chat" in avatars)
        assertTrue("family:femme_cartoon_01" in avatars)
        assertTrue("emoji:😀" in avatars)
        assertTrue("fallback_blue" in avatars)
        assertTrue("" in avatars)
        assertFalse("photo-personnelle.jpg" in avatars)
        listOf("profiles.home", "profiles.iptv").forEach { key ->
            assertEquals(avatars, AccountSettingsCatalog.spec(key)!!.choices)
        }
    }
    @Test fun homeAppSelectionsBelongToTheInstallation() {
        listOf("favorite_apps", "hidden_apps", "content_apps").forEach { key ->
            val spec = AccountSettingsCatalog.spec("home.$key")!!
            assertEquals("device", spec.scope)
            assertTrue(spec.accepts(accountPackages(setOf("org.videolan.vlc", "com.example.tv"))))
            assertTrue(AccountSettingsCatalog.homeKey(key))
            reject { AccountSettingsCatalog.validate(AccountRowId("account", AccountSettingsCatalog.ACCOUNT_SUBJECT, spec.key), accountPackages(emptySet<String>())) }
        }
    }
    @Test fun packageSetsHaveStableSerializationRegardlessOfIterationOrder() {
        val first = accountPackages(linkedSetOf("org.videolan.vlc", "com.example.tv"))
        assertEquals(first, accountPackages(linkedSetOf("com.example.tv", "org.videolan.vlc")))
        assertFalse(AccountSettingsCatalog.spec("home.favorite_apps")!!.accepts(accountPackages(setOf("https://private.invalid/token"))))
        reject { accountPackages(setOf(1)) }
    }
    @Test fun favoritesMigrationMarkerIsProtectedButNeverExported() {
        assertTrue(AccountSettingsCatalog.homeKey("favorite_apps_version"))
        assertNull(AccountSettingsCatalog.spec("home.favorite_apps_version"))
    }
    @Test fun remoteAppSelectionsAreCanonicalSetsWithoutChangingIconOrder() {
        fun page(key: String): AccountPage = AccountProtocol.page("""{"schema":1,"initialized":true,"head_revision":1,"rows":[{"scope":"device","subject":"$device","key":"$key","value":["org.videolan.vlc","com.example.tv"],"revision":1}]}""", device)
        assertEquals(accountPackages(setOf("com.example.tv", "org.videolan.vlc")), page("home.favorite_apps").rows.single().value)
        assertEquals("org.videolan.vlc", page("home.apps_order").rows.single().value.asJsonArray[0].asString)
    }
    @Test fun invalidProfileNamesAndColorAreRejected() {
        val spec = AccountSettingsCatalog.spec("profiles.home")!!
        assertFalse(spec.accepts(accountProfileValue(" ", "#D3A033", "")))
        assertFalse(spec.accepts(accountProfileValue("a\nb", "#D3A033", "")))
        assertFalse(spec.accepts(accountProfileValue("Arnaud", "blue", "")))
    }
    @Test fun futureProfilesUseDifferentUuidEvenWithSameName() {
        val first = AccountRowId("profile", device, "profiles.iptv")
        val second = first.copy(subject = other)
        val payload = accountProfileValue("Arnaud", "#D3A033", "")
        val journal = AccountJournal().change(first, payload).change(second, payload).prepare()
        assertEquals(2, journal.pending.size); assertNotEquals(first.token(), second.token())
    }
    @Test fun sendingDoesNotConsumeDurableOperations() {
        val journal = change(); assertEquals(journal, journal.prepare())
        assertEquals(journal.pending[0].operation_id, journal.prepare().pending[0].operation_id)
    }
    @Test fun newerIntentDoesNotRewriteAnInFlightBody() {
        val first = change("small"); val next = first.change(key, JsonPrimitive("large")).prepare()
        assertEquals(first.pending, next.pending); assertEquals("large", next.desired[key.token()]!!.value.asString)
    }
    @Test fun staleSuccessKeepsTheNewerLocalIntent() {
        val j = change("small").change(key, JsonPrimitive("large"))
        val completed = ack(j, 4)
        assertTrue(completed.pending.isEmpty()); assertEquals("large", completed.desired[key.token()]!!.value.asString)
        val next = completed.prepare().pending.single()
        assertEquals(4, next.expected_revision); assertNotEquals(j.pending.single().operation_id, next.operation_id)
    }
    @Test fun ackClearsOnlyItsOwnOperation() { val j = ack(change()); assertTrue(j.desired.isEmpty()); assertEquals(1, j.rows.size) }
    @Test fun invalidAckBatchHasNoPartialEffect() {
        val j = change().change(AccountRowId("device", device, "iptv.afrLive"), JsonPrimitive(true)).prepare()
        reject { j.acknowledge(j.pending, listOf(AccountAck(j.pending[0].operation_id, "applied", 1), AccountAck(other, "applied", 2))) }
        assertEquals(2, j.pending.size); assertTrue(j.rows.isEmpty())
    }
    @Test fun duplicateAckIsRejected() {
        val j = change(); val a = AccountAck(j.pending[0].operation_id, "applied", 1)
        reject { j.acknowledge(j.pending, listOf(a, a)) }
    }
    @Test fun delayedAckCannotAcknowledgeAReplacement() {
        val old = change("small"); val next = ack(old).change(key, JsonPrimitive("large")).prepare()
        reject { next.acknowledge(old.pending, old.pending.map { AccountAck(it.operation_id, "applied", 1) }) }
    }
    @Test fun conflictRetainsIntentAndStopsAutomaticRetry() {
        val j = change(); val stopped = j.acknowledge(j.pending, listOf(AccountAck(j.pending[0].operation_id, "conflict", 7)))
        assertEquals(1, stopped.desired.size); assertEquals(1, stopped.conflicts.size); assertTrue(stopped.prepare().pending.isEmpty())
    }
    @Test fun choosingCloudDropsLocalIntentExplicitly() {
        val j = change(); val stopped = j.acknowledge(j.pending, listOf(AccountAck(j.pending[0].operation_id, "conflict", 7)))
        assertTrue(stopped.resolve(key.token(), false).desired.isEmpty())
    }
    @Test fun choosingLocalUsesFreshServerRevision() {
        val j = change(); val stopped = j.acknowledge(j.pending, listOf(AccountAck(j.pending[0].operation_id, "conflict", 7)))
            .copy(rows = listOf(AccountEntry(key.scope, key.subject, key.key, JsonPrimitive("small"), 7)))
        assertEquals(7, stopped.resolve(key.token(), true).prepare().pending.single().expected_revision)
        assertNotEquals(j.pending.single().operation_id, stopped.resolve(key.token(), true).prepare().pending.single().operation_id)
    }
    @Test fun oldAckCannotRegressCachedRevision() {
        val j = change().copy(rows = listOf(AccountEntry(key.scope, key.subject, key.key, JsonPrimitive("large"), 9)))
        assertEquals(9, ack(j, 2).rows.single().revision)
    }
    @Test fun resetIsAnExplicitTechnicalOperation() {
        val j = AccountJournal().change(key, JsonNull.INSTANCE).prepare()
        assertTrue(j.pending.single().value.isJsonNull); assertTrue(gson.toJson(j.pending).contains("\"value\":null"))
    }
    @Test fun encryptedJournalPayloadCanBeReplayedAfterProcessDeath() {
        val j = change("small").change(key, JsonPrimitive("large"))
        val recovered = gson.fromJson(gson.toJson(j), AccountJournal::class.java)
        assertEquals(j, recovered); assertEquals(j.pending.single().operation_id, recovered.prepare().pending.single().operation_id)
    }
    @Test fun absentBackupAndEmptyBackupAreDifferent() {
        assertFalse(AccountProtocol.page("{\"schema\":1,\"initialized\":false,\"head_revision\":0,\"rows\":[]}", device).initialized)
        assertTrue(AccountProtocol.page("{\"schema\":1,\"initialized\":true,\"head_revision\":0,\"rows\":[]}", device).initialized)
    }
    @Test fun missingOrStringBooleanIsNotAnEmptyBackup() {
        reject { AccountProtocol.page("{\"schema\":1,\"head_revision\":0,\"rows\":[]}", device) }
        reject { AccountProtocol.page("{\"schema\":1,\"initialized\":\"true\",\"head_revision\":0,\"rows\":[]}", device) }
    }
    @Test fun serverCannotReturnAnotherDevicesPreferences() {
        val page = AccountPage(1, true, 1, listOf(AccountEntry("device", other, "iptv.afrLive", JsonPrimitive(true), 1)))
        reject { page.validate(device) }
    }
    @Test fun duplicateRowsAndRevisionRegressionAreRejected() {
        val row = AccountEntry(key.scope, key.subject, key.key, JsonPrimitive("small"), 2)
        reject { AccountPage(1, true, 2, listOf(row, row)).validate(device) }
        reject { AccountPage(1, true, 1, listOf(row)).validate(device) }
    }
    @Test fun responseWithUnexpectedSensitiveFieldIsRejected() {
        reject { AccountProtocol.page("{\"schema\":1,\"initialized\":true,\"head_revision\":0,\"rows\":[],\"nas_token\":\"forbidden\"}", device) }
    }
    @Test fun acknowledgementsRequireNumericRevisionAndKnownStatus() {
        reject { AccountProtocol.acks("[{\"operation_id\":\"$device\",\"status\":\"applied\",\"server_revision\":\"1\"}]") }
        reject { AccountProtocol.acks("[{\"operation_id\":\"$device\",\"status\":\"unknown\",\"server_revision\":1}]") }
    }
    @Test fun settingsNeverUsePlaybackOrProviderRecordKeys() {
        assertFalse(AccountSettingsCatalog.iptvRecord("source:private")); assertFalse(AccountSettingsCatalog.iptvRecord("account:$device:play:episode"))
        assertFalse(AccountSettingsCatalog.iptvRecord("setting:pinHash")); assertTrue(AccountSettingsCatalog.iptvRecord("setting:bufferSize"))
        assertTrue(AccountSettingsCatalog.iptvRecord("account:$device:setting:audioLanguage"))
    }
    @Test fun diagnosticsDoNotExposeProfileMetadata() {
        val row = AccountEntry("profile", device, "profiles.iptv", accountProfileValue("PrivateName", "#D3A033", ""), 1)
        assertFalse(row.toString().contains("PrivateName"))
        assertFalse(AccountJournal().change(row.id(), row.value).toString().contains("PrivateName"))
    }
    @Test fun metadataIsSentBeforePreferencesOfNewProfile() {
        val preferences = AccountRowId("profile", device, "iptv.audioLanguage")
        val metadata = AccountRowId("profile", device, "profiles.iptv")
        val journal = AccountJournal().change(preferences, JsonPrimitive("fr"))
            .change(metadata, accountProfileValue("Future", "#D3A033", "")).prepare()
        assertEquals("profiles.iptv", journal.pending.first().key)
    }
    @Test fun profileResetCannotBecomeAnImplicitDeletion() {
        assertFalse(AccountSettingsCatalog.spec("profiles.home")!!.accepts(JsonNull.INSTANCE))
        reject { AccountJournal().change(AccountRowId("profile", device, "profiles.home"), JsonNull.INSTANCE) }
    }
    @Test fun invalidSeedReplyDoesNotConsumePendingSeed() {
        AccountProtocol.seeded("{\"initialized\":true}")
        reject { AccountProtocol.seeded("{\"initialized\":false}") }
        reject { AccountProtocol.seeded("{\"initialized\":\"true\"}") }
        reject { AccountProtocol.seeded("{}") }
    }
    @Test fun registrationMustAcknowledgeThisInstallation() {
        AccountProtocol.registered("{\"schema\":1,\"device\":\"$device\"}", device)
        reject { AccountProtocol.registered("{\"schema\":1,\"device\":\"$other\"}", device) }
        reject { AccountProtocol.registered("{\"schema\":\"1\",\"device\":\"$device\"}", device) }
    }
    @Test fun externalPlayerChoiceIsRestrictedToOwnDeviceAndOnePackage() {
        val spec = AccountSettingsCatalog.spec("iptv.externalPlayerPackage")!!
        assertEquals("device", spec.scope)
        assertTrue(spec.accepts(JsonPrimitive("org.videolan.vlc")))
        assertTrue(spec.accepts(JsonPrimitive("")))
        assertFalse(spec.accepts(JsonPrimitive("https://example.invalid/player")))
        assertFalse(spec.accepts(JsonPrimitive("/private/player.apk")))
    }
    @Test fun playerMenuOrderContainsOnlyKnownUniqueControls() {
        val spec = AccountSettingsCatalog.spec("iptv.playerMenuOrder")!!
        assertEquals("device", spec.scope)
        assertTrue(spec.accepts(JsonParser.parseString("[\"audio\",\"subtitles\",\"exit\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"audio\",\"audio\"]")))
        assertFalse(spec.accepts(JsonParser.parseString("[\"https://example.invalid\"]")))
        assertFalse(spec.accepts(JsonPrimitive("audio")))
    }
    @Test fun actualRecordingPaddingAndEpgCadenceValuesAreAccepted() {
        assertTrue(AccountSettingsCatalog.spec("iptv.recordingBefore")!!.accepts(JsonPrimitive("4")))
        assertTrue(AccountSettingsCatalog.spec("iptv.recordingAfter")!!.accepts(JsonPrimitive("20")))
        assertTrue(AccountSettingsCatalog.spec("iptv.epgRefreshHours")!!.accepts(JsonPrimitive("168")))
        assertEquals("device", AccountSettingsCatalog.spec("iptv.epgRefreshHours")!!.scope)
    }
    @Test fun searchPreferenceDoesNotAuthorizeSearchHistoryPayloads() {
        assertTrue(AccountSettingsCatalog.spec("iptv.searchHistory")!!.accepts(JsonPrimitive(true)))
        assertNull(AccountSettingsCatalog.spec("iptv.searches"))
        assertNull(AccountSettingsCatalog.spec("iptv.savedPrograms"))
    }
    @Test fun malformedAppliedRevisionIsRejectedBeforeConsumingIntent() {
        val j = change().copy(pending = change().pending.map { it.copy(expected_revision = 4) })
        reject { j.acknowledge(j.pending, j.pending.map { AccountAck(it.operation_id, "applied", 4) }) }
        assertEquals(1, j.pending.size)
    }

    private val iptvProfile = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val reviewContext get() = AccountSeedReviewContext(other, "https://example.invalid", device, "generation-a", "session-a")
    private fun seedValues() = linkedMapOf(
        AccountRowId("profile", other, "profiles.home").token() to accountProfileValue("Arnaud", "#D3A033", null, true),
        AccountRowId("profile", iptvProfile, "profiles.iptv").token() to accountProfileValue("Stéphanie", "#D3A033", null),
        key.token() to JsonPrimitive("medium"),
        AccountRowId("device", device, "iptv.bufferSize").token() to JsonPrimitive("large"),
        AccountRowId("profile", iptvProfile, "iptv.audioLanguage").token() to JsonPrimitive("fr")
    )
    private fun seedProfiles() = linkedMapOf(
        "home/1/instance-a" to accountProfileValue("Arnaud", "#D3A033", null, true),
        "iptv/$iptvProfile/$iptvProfile" to accountProfileValue("Stéphanie", "#D3A033", null)
    )
    private fun seedReview() = AccountSeedReview.capture(reviewContext, seedValues(), seedProfiles())

    @Test fun seedInventoryShowsAllScopesAndProfileMetadata() {
        val text = seedReview().inventory()
        listOf("Profils de l'application (1)", "Profils IPTV (1)", "Arnaud · principal", "Stéphanie", "#D3A033", "Avatar : Aucun",
            "Réglages communs", "Réglages propres à cette TV", "Préférences IPTV · Stéphanie", "Taille des icônes : Moyen",
            "Taille du tampon de lecture : Grand", "Valeur par défaut (réinitialisation)", "Exclus :").forEach { assertTrue(it, it in text) }
        listOf(device, other, iptvProfile, "session-a", "https://example.invalid").forEach { assertFalse(it, it in text) }
    }
    @Test fun seedPayloadIncludesOnlyReviewedKeysWithMetadataFirst() {
        val operations = seedReview().operations()
        assertTrue(operations.take(2).all { it.key.startsWith("profiles.") })
        assertTrue(operations.all { it.expected_revision == 0L })
        operations.forEach { it.validate() }
        assertEquals(operations.size, operations.map { it.operation_id }.toSet().size)
        assertEquals(operations.size, operations.map { it.id() }.toSet().size)
    }
    @Test fun seedMakesMissingCommonDefaultsExplicitWithoutInventingDeviceValues() {
        val operations = seedReview().operations()
        assertEquals(AccountSettingsCatalog.specs.count { it.scope == "account" }, operations.count { it.scope == "account" })
        assertTrue(operations.single { it.key == "home.home_rows" }.value.isJsonNull)
        assertFalse(operations.any { it.key == "home.apps_order" })
        assertFalse(operations.any { it.key == "iptv.subtitleLanguage" })
    }
    @Test fun seedApprovalAcceptsUnchangedDataRegardlessOfMapOrder() {
        val values = seedValues().entries.reversed().associate { it.toPair() }
        val profiles = seedProfiles().entries.reversed().associate { it.toPair() }
        assertTrue(seedReview().matches(reviewContext, values, profiles))
    }
    @Test fun changedCommonSettingInvalidatesSeedApproval() {
        val values = seedValues().toMutableMap(); values[key.token()] = JsonPrimitive("small")
        assertFalse(seedReview().matches(reviewContext, values, seedProfiles()))
    }
    @Test fun changedDeviceSettingInvalidatesSeedApproval() {
        val values = seedValues().toMutableMap(); values[AccountRowId("device", device, "iptv.bufferSize").token()] = JsonPrimitive("small")
        assertFalse(seedReview().matches(reviewContext, values, seedProfiles()))
    }
    @Test fun removedSettingInvalidatesSeedApproval() {
        val values = seedValues().toMutableMap(); values.remove(key.token())
        assertFalse(seedReview().matches(reviewContext, values, seedProfiles()))
    }
    @Test fun changedProfileNameOrAvatarInvalidatesSeedApproval() {
        for (value in listOf(accountProfileValue("Nouveau nom", "#D3A033", null, true), accountProfileValue("Arnaud", "#112233", null, true),
            accountProfileValue("Arnaud", "#D3A033", AccountSettingsCatalog.avatars.first { it.isNotEmpty() }, true))) {
            val profiles = seedProfiles().toMutableMap(); profiles["home/1/instance-a"] = value
            assertFalse(seedReview().matches(reviewContext, seedValues(), profiles))
        }
    }
    @Test fun profileAddedOrDeletedDuringReviewInvalidatesApproval() {
        val profiles = seedProfiles().toMutableMap()
        profiles["home/2/instance-b"] = accountProfileValue("Autre profil", "#D3A033", null)
        assertFalse(seedReview().matches(reviewContext, seedValues(), profiles))
        profiles.remove("home/2/instance-b"); profiles.remove("home/1/instance-a")
        assertFalse(seedReview().matches(reviewContext, seedValues(), profiles))
    }
    @Test fun recreatedHomonymousProfileInvalidatesSeedApproval() {
        val profiles = seedProfiles().toMutableMap()
        val sameName = profiles.remove("home/1/instance-a")!!
        profiles["home/1/recreated-instance"] = sameName
        assertFalse(seedReview().matches(reviewContext, seedValues(), profiles))
    }
    @Test fun accountSessionDeviceOrBindingChangeInvalidatesSeedApproval() {
        val context = reviewContext
        listOf(context.copy(owner = device), context.copy(project = "https://other.invalid"), context.copy(installation = other),
            context.copy(generation = "another-generation"), context.copy(session = "another-session"), context.copy(bindings = listOf("changed"))).forEach {
            assertFalse(seedReview().matches(it, seedValues(), seedProfiles()))
        }
    }
    @Test fun seedReviewDefensivelyCopiesMutableJsonAndContext() {
        val values = seedValues(); val profiles = seedProfiles(); val bindings = mutableListOf("original")
        val context = reviewContext.copy(bindings = bindings)
        val review = AccountSeedReview.capture(context, values, profiles)
        values.values.first().asJsonObject.addProperty("name", "Changed input")
        profiles.values.first().asJsonObject.addProperty("name", "Changed profile")
        bindings += "changed"
        assertTrue("Arnaud" in review.inventory())
        assertFalse("Changed input" in review.inventory())
        assertTrue(review.matches(reviewContext.copy(bindings = listOf("original")), seedValues(), seedProfiles()))
        assertFalse(review.matches(context, seedValues(), seedProfiles()))
    }
    @Test fun seedConfirmationUsesStableOperationsAndDefensiveCopies() {
        val review = seedReview(); val first = review.operations(); val second = review.operations()
        assertEquals(first, second)
        first.first().value.asJsonObject.addProperty("name", "Tampered copy")
        review.observedValues().getValue(AccountRowId("profile", other, "profiles.home").token()).asJsonObject.addProperty("name", "Tampered observation")
        assertTrue("Arnaud" in review.inventory())
        assertFalse("Tampered" in review.inventory())
        assertEquals(second, review.operations())
    }
    @Test fun seedReviewRejectsForbiddenFieldsAndOtherDeviceScope() {
        val forbidden = seedValues().toMutableMap()
        forbidden[AccountRowId("account", AccountSettingsCatalog.ACCOUNT_SUBJECT, "iptv.sourceUrl").token()] = JsonPrimitive("https://example.invalid/private")
        reject { AccountSeedReview.capture(reviewContext, forbidden, seedProfiles()) }
        val otherDevice = seedValues().toMutableMap()
        otherDevice[AccountRowId("device", other, "iptv.bufferSize").token()] = JsonPrimitive("large")
        reject { AccountSeedReview.capture(reviewContext, otherDevice, seedProfiles()) }
    }
    @Test fun seedReviewRejectsMissingProfileMetadataAndOrphanPreferences() {
        val values = seedValues().toMutableMap(); values.remove(AccountRowId("profile", iptvProfile, "profiles.iptv").token())
        reject { AccountSeedReview.capture(reviewContext, values, seedProfiles()) }
        val orphan = seedValues().toMutableMap()
        orphan[AccountRowId("profile", other, "iptv.autoNextEpisode").token()] = JsonPrimitive(true)
        reject { AccountSeedReview.capture(reviewContext, orphan, seedProfiles()) }
    }
    @Test fun inventoryNamesEveryCatalogueKeyAndRemoteAction() {
        AccountSettingsCatalog.specs.forEach { spec ->
            assertTrue(spec.key, AccountSeedInventory.label(spec.key).isNotBlank())
            if (spec.key.startsWith("iptv.remote.")) spec.choices.forEach {
                assertTrue(AccountSeedInventory.value(spec.key, JsonPrimitive(it)).isNotBlank())
            }
        }
    }
    @Test fun orderedAppsAreShownAndOrderChangesInvalidateApproval() {
        val id = AccountRowId("device", device, "home.apps_order").token()
        val values = seedValues().toMutableMap(); values[id] = accountAppsOrder("com.example.first|com.example.second")
        val review = AccountSeedReview.capture(reviewContext, values, seedProfiles())
        assertTrue("1. com.example.first ; 2. com.example.second" in review.inventory())
        values[id] = accountAppsOrder("com.example.second|com.example.first")
        assertFalse(review.matches(reviewContext, values, seedProfiles()))
    }
    @Test fun inventoryAndContextToStringDoNotExposeReviewedValues() {
        val review = seedReview()
        assertFalse(review.toString().contains("Arnaud"))
        assertFalse(review.toString().contains("Stéphanie"))
        assertFalse(reviewContext.toString().contains("session-a"))
    }
}
