package com.jojotv.home.iptv

import org.junit.Assert.*
import org.junit.Test

class SupabaseIptvSettingsTest {
    @Test fun legacyNasCannotRestoreOldGroupOrganizationOverTheCloudChoices() {
        val key = IptvOrganization.key("provider", ContentType.LIVE, "12", "groupName")
        val local = current().copy(settings = current().settings + (key to "Nom cloud"))
        val nas = local.copy(settings = local.settings + (key to "Ancien nom NAS"))
        val restored = preserveSupabaseIptvSettings(local, nas)
        assertEquals("Nom cloud", restored.settings[key])
        assertEquals(local.selectedAccountId, restored.selectedAccountId)
        assertEquals(local.accountStates, restored.accountStates)
    }

    @Test fun legacyNasStopsByDefaultOnlyAfterBothCloudDomainsAreSelected() {
        val routing = com.jojotv.home.progresspilot.NasDataSyncRouting
        assertTrue(routing.enabled(null, false, false))
        assertTrue(routing.enabled(null, true, false))
        assertTrue(routing.enabled(null, false, true))
        assertFalse(routing.enabled(null, true, true))
    }
    @Test fun explicitNasTransportChoiceRemainsIndependentOfCloudDomains() {
        val routing = com.jojotv.home.progresspilot.NasDataSyncRouting
        for (settings in listOf(false, true)) for (activity in listOf(false, true)) {
            assertFalse(routing.enabled(false, settings, activity))
            assertTrue(routing.enabled(true, settings, activity))
        }
    }
    @Test fun nasTransportOptionIsLocalAndNotPartOfTheCloudSettingsCatalog() {
        assertFalse(com.jojotv.home.progresspilot.AccountSettingsCatalog.homeKey("nas_data_sync_enabled"))
        assertFalse(com.jojotv.home.progresspilot.AccountSettingsCatalog.iptvRecord("setting:nas_data_sync_enabled"))
    }

    private val first = IptvAccount("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Profil A")
    private val second = IptvAccount("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Profil B")
    private fun current() = IptvLibrary(accounts = listOf(first, second), selectedAccountId = second.id,
        legacyAccountId = first.id, profileActivityScoped = true,
        settings = mapOf("bufferSize" to "large", "audioLanguage" to "fr", "confirmExit" to "true", "pinHash" to "local-pin"),
        accountStates = mapOf(first.id to IptvAccountState(settings = mapOf("audioLanguage" to "en")),
            second.id to IptvAccountState(settings = mapOf("audioLanguage" to "fr"))))

    @Test fun settingsProjectionKeepsSharedAndProfileSettingsSeparate() {
        val snapshot = current().settingsSnapshot()
        assertEquals("large", snapshot.first["bufferSize"])
        assertFalse("audioLanguage" in snapshot.first)
        assertEquals("en", snapshot.second[first.id]?.get("audioLanguage"))
        assertEquals("fr", snapshot.second[second.id]?.get("audioLanguage"))
    }

    @Test fun deletingHistoryDoesNotChangeSettingsOrTheSelectedProfile() {
        val movie = IptvItem("iptv:source:movie:17", "source", "17", ContentType.MOVIE, "Movie")
        val before = current().withPlayback(movie, second.id, 100)
        assertTrue(movie.id in before.history)
        val snapshot = before.settingsSnapshot()
        val after = before.withoutHistory(movie, second.id)
        assertFalse(movie.id in after.history)
        assertEquals(second.id, after.selectedAccountId)
        assertEquals(snapshot, after.settingsSnapshot())
    }

    @Test fun settingsProjectionCheckpointsPrivateChangesWithoutChangingAnOlderSnapshot() {
        val before = current()
        val snapshot = before.settingsSnapshot()
        val after = before.copy(settings = before.settings + ("audioLanguage" to "es"))
        assertEquals("es", after.settingsSnapshot().second[second.id]?.get("audioLanguage"))
        assertEquals("fr", snapshot.second[second.id]?.get("audioLanguage"))
        assertEquals(snapshot, before.selectAccount(first.id).settingsSnapshot())
    }

    @Test fun oldNasSnapshotCannotOverwriteCloudNamesOrDeviceSelection() {
        val local = current()
        val incoming = local.copy(accounts = listOf(first.copy(name = "Ancien nom NAS")), selectedAccountId = first.id)
        val result = preserveSupabaseIptvSettings(local, incoming)
        assertEquals(local.accounts, result.accounts); assertEquals(second.id, result.selectedAccountId)
        assertEquals(first.id, result.legacyAccountId)
    }
    @Test fun nasDeletionCannotDeleteCloudProfileOrChangeItsAvatar() {
        val local = current()
        val incoming = local.copy(accounts = listOf(first.copy(name = "Ancien", avatar = "nas:photo")),
            selectedAccountId = first.id, legacyAccountId = second.id, accountStates = emptyMap())
        val restored = preserveSupabaseIptvSettings(local, incoming)
        assertEquals(local.accounts, restored.accounts)
        assertEquals(local.selectedAccountId, restored.selectedAccountId)
        assertEquals(local.legacyAccountId, restored.legacyAccountId)
        assertEquals("fr", restored.accountStates[second.id]!!.settings["audioLanguage"])
    }
    @Test fun profileCreatedOnlyOnNasIsNotAutomaticallyMixedIntoCloudProfiles() {
        val local = current()
        val foreign = IptvAccount("cccccccc-cccc-4ccc-8ccc-cccccccccccc", first.name)
        val incoming = local.copy(accounts = local.accounts + foreign,
            accountStates = local.accountStates + (foreign.id to IptvAccountState(settings = mapOf("audioLanguage" to "es"))))
        val restored = preserveSupabaseIptvSettings(local, incoming)
        assertEquals(local.accounts, restored.accounts)
        assertFalse(foreign.id in restored.accountStates)
        assertEquals("en", restored.accountStates[first.id]!!.settings["audioLanguage"])
    }
    @Test fun oldLocalNasJournalCannotReapplyPreCloudSettings() {
        val local = current(); val incoming = local.copy(settings = mapOf("bufferSize" to "small", "confirmExit" to "false", "pinHash" to "nas-pin"))
        val result = preserveSupabaseIptvSettings(local, incoming)
        assertEquals("large", result.settings["bufferSize"]); assertEquals("true", result.settings["confirmExit"])
        assertEquals("nas-pin", result.settings["pinHash"])
    }
    @Test fun languagePreferencesStayWithTheirProfiles() {
        val local = current(); val incoming = local.copy(accountStates = mapOf(
            first.id to IptvAccountState(settings = mapOf("audioLanguage" to "de")),
            second.id to IptvAccountState(settings = mapOf("audioLanguage" to "es"))))
        val result = preserveSupabaseIptvSettings(local, incoming)
        assertEquals("en", result.accountStates[first.id]!!.settings["audioLanguage"])
        assertEquals("fr", result.accountStates[second.id]!!.settings["audioLanguage"])
        assertEquals("fr", result.settings["audioLanguage"])
    }
    @Test fun excludedActivityKeepsItsExistingNasPath() {
        val local = current(); val incoming = local.copy(accountStates = local.accountStates +
            (second.id to IptvAccountState(settings = mapOf("audioLanguage" to "es"), searches = listOf("NAS activity"))))
        val result = preserveSupabaseIptvSettings(local, incoming)
        assertEquals(listOf("NAS activity"), result.searches)
        assertEquals("fr", result.settings["audioLanguage"])
    }
    @Test fun newCloudProfileDoesNotDisappearWhenNasDoesNotKnowIt() {
        val local = current(); val incoming = local.copy(accounts = listOf(first), accountStates = mapOf(first.id to IptvAccountState()))
        val result = preserveSupabaseIptvSettings(local, incoming)
        assertTrue(result.accounts.any { it.id == second.id }); assertTrue(second.id in result.accountStates)
        assertEquals("fr", result.accountStates[second.id]!!.settings["audioLanguage"])
    }
    @Test fun sourceChangesRemainIndependentOfSettingsProtection() {
        val local = current(); val source = IptvSource("source", "Fixture", SourceType.M3U, "https://example.invalid/fixture")
        val result = preserveSupabaseIptvSettings(local, local.copy(sources = listOf(source)))
        assertEquals(listOf(source), result.sources); assertEquals("large", result.settings["bufferSize"])
    }
    @Test fun restoringAnotherProfileDoesNotSwitchTheActiveOne() {
        val local = current()
        val third = IptvAccount("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Profil C")
        val next = local.withAccount(third).restoreLocalAccount(local.selectedAccountId).checkpointAccount()
        assertEquals(second.id, next.selectedAccountId); assertEquals("fr", next.settings["audioLanguage"])
        assertEquals(3, next.accounts.size)
    }
    @Test fun historyFavoriteAndPlayerSettingUpdatesDoNotRefreshTheAccountHeader() {
        val before = current()
        val movie = IptvItem("iptv:source:movie:17", "source", "17", ContentType.MOVIE, "Movie")
        assertFalse(iptvHomeAccountChanged(before, before.copy(history = listOf(movie.id))))
        assertFalse(iptvHomeAccountChanged(before, before.copy(favorites = setOf(movie.id), remembered = listOf(movie))))
        assertFalse(iptvHomeAccountChanged(before, before.copy(settings = before.settings + ("bufferSize" to "small"))))
    }
    @Test fun accountSelectionNameAvatarAndMembershipRefreshTheHeader() {
        val before = current()
        assertTrue(iptvHomeAccountChanged(null, before))
        assertTrue(iptvHomeAccountChanged(before, before.copy(selectedAccountId = first.id)))
        assertTrue(iptvHomeAccountChanged(before, before.copy(accounts = listOf(first, second.copy(name = "Renamed")))))
        assertTrue(iptvHomeAccountChanged(before, before.copy(accounts = listOf(first, second.copy(avatar = "local-avatar")))))
        assertTrue(iptvHomeAccountChanged(before, before.copy(accounts = listOf(second))))
        assertFalse(iptvHomeAccountChanged(before, before.copy()))
    }
}
