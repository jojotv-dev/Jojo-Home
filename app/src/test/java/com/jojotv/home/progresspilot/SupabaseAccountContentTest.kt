package com.jojotv.home.progresspilot

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class SupabaseAccountContentTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val profile = "22222222-2222-4222-8222-222222222222"
    private val device = "33333333-3333-4333-8333-333333333333"
    private val key = ByteArray(32) { it.toByte() }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        catch (_: javax.crypto.AEADBadTagException) {}
    }
    private fun value(clear: String = "favorite") = AccountContentCrypto(key, owner).use {
        it.seal(AccountContentValue(profile, "favorite", it.id(profile, "iptv:movie:17")), clear)
    }
    private fun ack(journal: AccountContentJournal, status: String = "applied", revision: Long = 1) = journal.acknowledge(journal.pending,
        journal.pending.map { AccountContentAck(it.operation_id, status, revision) })

    private fun netflix(content: String = "80000103", episode: String = "3", name: String = "Stéphanie") =
        AccountNetflixObservation(name, content, "Série fictive", "Reprendre la lecture : épisode $episode", "1", episode, 3600000, 900000)

    @Test fun netflixEncryptedSnapshotIsBoundToAccountProfileClockAndWriter() {
        AccountContentCrypto(key, owner).use { crypto ->
            val id = crypto.netflixId("Stéphanie")
            val snapshot = AccountNetflixSnapshot("Stéphanie", listOf(netflix()))
            val row = crypto.sealNetflix(AccountNetflixValue(id, 1000, device), snapshot)
            assertFalse(row.sealed.contains("Stéphanie")); assertFalse(row.sealed.contains("80000103"))
            assertEquals(snapshot, crypto.openNetflix(row))
            assertEquals(id, crypto.netflixId(" STÉPHANIE "))
            assertNotEquals(id, crypto.netflixId("Arnaud"))
            reject { crypto.openNetflix(row.copy(stamp = 1001)) }
            reject { crypto.openNetflix(row.copy(writer = profile)) }
            reject { crypto.openNetflix(row.copy(key = crypto.netflixId("Arnaud"))) }
            AccountContentCrypto(ByteArray(32) { 99 }, owner).use { other -> reject { other.openNetflix(row) } }
            AccountContentCrypto(key, profile).use { other -> reject { other.openNetflix(row) } }
        }
    }
    @Test fun netflixEpisodeSixReplacesEpisodeThreeForAllThreeInstallationsDespiteChangingContentId() {
        AccountContentCrypto(key, owner).use { crypto ->
            for (sender in 0..2) {
                val clients = MutableList(3) { AccountNetflixState() }
                val first = clients[sender].capture(listOf(netflix()), crypto, device, 1000)
                val before = first.pending.values.single()
                clients.indices.forEach { index -> clients[index] = clients[index].receive(emptyList(),
                    AccountNetflixPage(1, 1, listOf(AccountNetflixRow(before, 1))), crypto) }
                clients[sender] = first.receive(first.pending.values.toList(), AccountNetflixPage(1, 1,
                    listOf(AccountNetflixRow(before, 1))), crypto)
                val changed = clients[sender].capture(listOf(netflix("80000106", "6")), crypto, device, 2000)
                val after = changed.pending.values.single()
                assertEquals(before.key, after.key)
                clients.indices.forEach { index -> clients[index] = clients[index].receive(emptyList(),
                    AccountNetflixPage(2, 2, listOf(AccountNetflixRow(after, 2))), crypto) }
                clients.forEach { client ->
                    val restored = client.view(crypto).single().second.cards.single()
                    assertEquals("80000106", restored.content); assertEquals("6", restored.episode)
                    assertEquals("Stéphanie", restored.profile)
                }
            }
        }
    }
    @Test fun netflixFirstOldNativeSnapshotCannotOverwriteRemoteEpisodeAfterRestart() {
        AccountContentCrypto(key, owner).use { crypto ->
            val remote = AccountNetflixState().capture(listOf(netflix("80000106", "6")), crypto, device, 1000).pending.values.single()
            val receiver = AccountNetflixState().receive(emptyList(), AccountNetflixPage(1, 1,
                listOf(AccountNetflixRow(remote, 1))), crypto).capture(listOf(netflix()), crypto, profile, 2000)
            assertTrue(receiver.pending.isEmpty())
            assertEquals("6", receiver.view(crypto).single().second.cards.single().episode)
            val restarted = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(receiver), AccountNetflixState::class.java)
            assertTrue(restarted.capture(listOf(netflix()), crypto, profile, 3000).pending.isEmpty())
            assertEquals("6", restarted.view(crypto).single().second.cards.single().episode)
            assertFalse(restarted.capture(listOf(netflix("80000107", "7")), crypto, profile, 4000).pending.isEmpty())
        }
    }
    @Test fun netflixUnchangedNotificationsNeverAdvanceClockOrCiphertextAndRetryIsImmutable() {
        AccountContentCrypto(key, owner).use { crypto ->
            val first = AccountNetflixState().capture(listOf(netflix()), crypto, device, 1000)
            assertEquals(first, first.capture(listOf(netflix()), crypto, device, 5000))
            val persisted = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(first), AccountNetflixState::class.java)
            val sent = first.pending.values.single()
            assertEquals(sent, persisted.pending.values.single())
            val newer = persisted.capture(listOf(netflix("80000106", "6")), crypto, device, 5000)
            val lateReply = newer.receive(listOf(sent), AccountNetflixPage(1, 1, listOf(AccountNetflixRow(sent, 1))), crypto)
            assertEquals("6", lateReply.view(crypto).single().second.cards.single().episode)
            assertEquals(1, lateReply.pending.size)
        }
    }
    @Test fun netflixMissingTimingNeverBorrowsAnotherEpisodesProgressAndProfilesRemainSeparate() {
        val previous = netflix()
        assertEquals(previous, previous.copy(duration = -1, position = -1).retainMissing(previous))
        val next = netflix("80000106", "6").copy(duration = -1, position = -1)
        assertEquals(next, next.retainMissing(previous))
        assertEquals(next.copy(profile = "Arnaud"), next.copy(profile = "Arnaud").retainMissing(previous))
        AccountContentCrypto(key, owner).use { crypto ->
            val state = AccountNetflixState().capture(listOf(previous, netflix(name = "Arnaud")), crypto, device, 1000)
            assertEquals(2, state.pending.size)
            assertEquals(setOf("Stéphanie", "Arnaud"), state.view(crypto).map { it.second.profile }.toSet())
        }
    }
    @Test fun netflixSnapshotRejectsArbitraryIntentsPrivateFieldsAndAmbiguousDuplicateCards() {
        reject { netflix().copy(profile = "").validate() }
        reject { netflix().copy(content = "https://www.netflix.com/watch/1").validate() }
        reject { netflix().copy(description = "file:///private/data").validate() }
        reject { netflix().copy(position = 3600001).validate() }
        reject { AccountNetflixObservation.decode(netflix().encode().dropLast(1) + ",\"intent\":\"secret\"}") }
        reject { AccountNetflixSnapshot("Stéphanie", listOf(netflix(), netflix())).validate() }
        reject { AccountNetflixSnapshot("Stéphanie", listOf(netflix(name = "Arnaud"))).validate() }
    }
    @Test fun netflixPageRequiresOrderedUniqueRevisionsAndExactWireShape() {
        AccountContentCrypto(key, owner).use { crypto ->
            val value = AccountNetflixState().capture(listOf(netflix()), crypto, device, 1000).pending.values.single()
            val gson = com.google.gson.Gson()
            fun page(rows: String, next: Long = 1) = """{"schema":1,"next_cursor":$next,"head_revision":$next,"rows":$rows}"""
            val row = gson.toJson(value).dropLast(1) + ",\"revision\":1}"
            assertEquals(value, accountNetflixPage(page("[$row]"), 0).rows.single().value)
            reject { accountNetflixPage(page("[$row,$row]"), 0) }
            reject { accountNetflixPage(page("[]"), 0) }
            reject { accountNetflixPage(page("[$row]"), 1) }
            reject { accountNetflixPage(page("[$row]").dropLast(1) + ",\"extra\":true}", 0) }
        }
    }

    @Test fun ciphertextHidesTitleAndRawId() {
        val value = value("Tyson · iptv:movie:17")
        assertFalse(value.sealed.contains("Tyson")); assertFalse(value.key.contains("movie"))
        AccountContentCrypto(key, owner).use { assertEquals("Tyson · iptv:movie:17", it.open(value)) }
    }
    @Test fun repeatedSealUsesIndependentIv() { assertNotEquals(value().sealed, value().sealed) }
    @Test fun sameKeyAcrossInstallationsKeepsContentIdentity() {
        AccountContentCrypto(key, owner).use { a -> AccountContentCrypto(key, owner).use { b ->
            assertEquals(a.id(profile, "item"), b.id(profile, "item")) } }
    }
    @Test fun everyInstallationCanPublishAFavoriteAndAnotherCanRemoveItForAllThree() {
        for (sender in 0..2) {
            val clients = MutableList(3) { AccountContentJournal() }
            val favorite = value("shared favorite")
            fun publish(index: Int, change: AccountContentValue, revision: Long) {
                val sending = clients[index].change(change).prepare()
                assertEquals(revision - 1, sending.pending.single().expected_revision)
                clients[index] = ack(sending, revision = revision)
                val page = AccountContentPage(1, revision, revision, listOf(AccountContentRow(change, revision)))
                clients.indices.forEach { target -> clients[target] = clients[target].receive(page) }
            }
            publish(sender, favorite, 1)
            clients.forEach { client ->
                AccountContentCrypto(key, owner).use { assertEquals("shared favorite", it.open(client.rows.getValue(favorite.token()).value)) }
            }
            publish((sender + 1) % 3, favorite.copy(deleted = true, sealed = ""), 2)
            clients.forEach { client ->
                assertTrue(client.rows.getValue(favorite.token()).value.deleted)
                assertTrue(client.pending.isEmpty()); assertTrue(client.desired.isEmpty()); assertTrue(client.conflicts.isEmpty())
                assertEquals(2L, client.cursor)
            }
        }
    }
    @Test fun profileAndAccountSeparateHashes() {
        AccountContentCrypto(key, owner).use { a -> AccountContentCrypto(key, device).use { b ->
            assertNotEquals(a.id(profile, "item"), a.id(device, "item")); assertNotEquals(a.id(profile, "item"), b.id(profile, "item")) } }
    }
    @Test fun rowIdentityIsAuthenticated() { val v = value(); AccountContentCrypto(key, owner).use { reject { it.open(v.copy(profile = device)) } } }
    @Test fun keyChangeCannotDecryptEarlierValues() { val v = value(); AccountContentCrypto(ByteArray(32) { 9 }, owner).use { reject { it.open(v) } } }
    @Test fun modifiedPacketFailsAuthentication() {
        val v = value(); val bytes = Base64.getDecoder().decode(v.sealed); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        AccountContentCrypto(key, owner).use { reject { it.open(v.copy(sealed = Base64.getEncoder().encodeToString(bytes))) } }
    }
    @Test fun deleteHasNoResidualPayload() { value().copy(deleted = true, sealed = "").validate(); reject { value().copy(deleted = true).validate() } }
    @Test fun wireCannotMasqueradeAsSettingOrProgress() { reject { value().copy(kind = "setting").validate() }; reject { value().copy(kind = "progress").validate() } }
    @Test fun payloadContainsOnlyTheNineReviewedWireFields() {
        assertEquals(setOf("profile", "kind", "key", "creator", "reference", "base_revision", "status", "deleted", "sealed"),
            com.google.gson.Gson().toJsonTree(value()).asJsonObject.keySet())
    }
    @Test fun rawIdentifiersAreRefusedAsCloudKeys() { reject { value().copy(key = "iptv:movie:17").validate() } }
    @Test fun favoriteCannotClaimCreatorOrCommandReference() { reject { value().copy(creator = device).validate() }; reject { value().copy(reference = profile).validate() } }
    @Test fun nothingIsQueuedByDefault() { assertFalse(AccountContentState().selected); assertTrue(AccountContentState().journal.pending.isEmpty()) }
    @Test fun transmittedOperationStaysImmutableWhenUserEditsAgain() {
        val first = value("first"); val second = value("second")
        val j = AccountContentJournal().change(first).prepare(); val changed = j.change(second).prepare()
        assertEquals(j.pending, changed.pending); assertEquals(second, changed.desired[first.token()])
    }
    @Test fun exactAckRetainsNewerLocalEdit() {
        val first = value("first"); val second = value("second")
        val j = AccountContentJournal().change(first).prepare().change(second)
        val next = ack(j).prepare(); assertEquals(second, next.pending.single().value); assertEquals(1, next.pending.single().expected_revision)
    }
    @Test fun duplicateAckCannotConsumeNewWork() {
        val j = AccountContentJournal().change(value("first")).prepare(); val sent = j.pending
        val next = ack(j).change(value("second")).prepare()
        assertEquals(next, next.acknowledge(sent, sent.map { AccountContentAck(it.operation_id, "applied", 1) }))
    }
    @Test fun ackWithForeignOperationIsRejected() { val j = AccountContentJournal().change(value()).prepare()
        reject { j.acknowledge(j.pending, listOf(AccountContentAck(UUID.randomUUID().toString(), "applied", 1))) } }
    @Test fun conflictIsHeldUntilExplicitResolution() {
        val j = ack(AccountContentJournal().change(value()).prepare(), "conflict", 3)
        assertTrue(j.prepare().pending.isEmpty()); assertEquals(1, j.conflicts.size)
        assertTrue(j.resolve(j.conflicts.single(), false).desired.isEmpty())
    }
    @Test fun keepingLocalUsesLatestServerRevision() {
        val mine = value("mine"); val theirs = value("theirs")
        val j = ack(AccountContentJournal().change(mine).prepare(), "conflict", 5)
            .receive(AccountContentPage(1, 5, 5, listOf(AccountContentRow(theirs, 5))))
        assertEquals(5, j.resolve(mine.token(), true).prepare().pending.single().expected_revision)
    }
    @Test fun offlineEditKeepsItsOriginalBaseWhenANewerRemoteRevisionArrives() {
        val old = value("old"); val remote = value("remote"); val mine = value("mine")
        val j = AccountContentJournal().receive(AccountContentPage(1, 1, 1, listOf(AccountContentRow(old, 1))))
            .change(mine).receive(AccountContentPage(1, 8, 8, listOf(AccountContentRow(remote, 8)))).prepare()
        assertEquals(1, j.pending.single().expected_revision)
    }
    @Test fun editDuringLostAckDoesNotUseAnotherDevicesNewerRevisionAsItsBase() {
        val sent = AccountContentJournal().change(value("first")).prepare()
        val j = sent.change(value("second")).receive(AccountContentPage(1, 8, 8, listOf(AccountContentRow(value("remote"), 8))))
        val next = ack(j, revision = 2).prepare()
        assertEquals(2, next.pending.single().expected_revision)
    }
    @Test fun staleReadCannotRegressCursor() { reject { AccountContentJournal(cursor = 5).receive(AccountContentPage(1, 4, 4, emptyList())) } }
    @Test fun emptyPageCanAdvanceAcrossSupersededRevisions() {
        assertEquals(12, AccountContentJournal(cursor = 5).receive(AccountContentPage(1, 12, 12, emptyList())).cursor)
    }
    @Test fun duplicateRowsAndImpossiblePageBoundsAreRejected() {
        val row = AccountContentRow(value(), 1)
        reject { AccountContentJournal().receive(AccountContentPage(1, 1, 1, listOf(row, row))) }
        reject { AccountContentJournal().receive(AccountContentPage(1, 1, 0, listOf(row))) }
    }
    @Test fun pendingDeleteRemainsProtectedFromIncomingValue() {
        val gone = value().copy(deleted = true, sealed = "")
        assertTrue(AccountContentJournal().change(gone).prepare().protected(gone.token()))
    }

    @Test fun historyDeletionSurvivesRestartAndANewerRemoteHistoryRow() {
        val history = value("history payload").let { original ->
            AccountContentCrypto(key, owner).use { it.seal(original.copy(kind = "history", sealed = ""), "history payload") }
        }
        val gone = history.copy(deleted = true, sealed = "")
        val queued = AccountContentJournal(rows = mapOf(history.token() to AccountContentRow(history, 1)))
            .change(gone).prepare()
        val gson = com.google.gson.Gson()
        val reopened = gson.fromJson(gson.toJson(queued), AccountContentJournal::class.java)
        val received = reopened.receive(AccountContentPage(1, 2, 2, listOf(AccountContentRow(history, 2))))
        assertTrue(received.protected(history.token()))
        assertEquals(gone, received.desired[history.token()])
        assertEquals(queued.pending, received.pending)
    }

    @Test fun lateHistoryAdditionAcknowledgementCannotConsumeALocalDeletion() {
        val history = AccountContentCrypto(key, owner).use {
            it.seal(AccountContentValue(profile, "history", it.id(profile, "item")), "history")
        }
        val gone = history.copy(deleted = true, sealed = "")
        val sending = AccountContentJournal().change(history).prepare()
        val deleted = sending.change(gone)
        val next = deleted.acknowledge(sending.pending,
            sending.pending.map { AccountContentAck(it.operation_id, "applied", 1) }).prepare()
        assertTrue(next.protected(history.token()))
        assertEquals(gone, next.pending.single().value)
        assertEquals(1L, next.pending.single().expected_revision)
    }
    @Test fun savedOutboxRoundTripsWithStableOperations() {
        val j = AccountContentJournal().change(value()).prepare(); val gson = com.google.gson.Gson()
        assertEquals(j, gson.fromJson(gson.toJson(j), AccountContentJournal::class.java))
    }
    @Test fun commandAcknowledgementCannotBeDelete() {
        val command = AccountContentValue(profile, "command", device, creator = device, reference = profile,
            base_revision = 1, status = "pending", sealed = value().sealed)
        command.validate(); reject { command.copy(deleted = true).validate() }; reject { command.copy(base_revision = 0).validate() }
    }
}
