package com.jojotv.home.progresspilot

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SupabaseProgressSyncTest {
    private fun metadataContext(profile: String = SupabaseProgressPilotTest.PROFILE, secret: Int = 7,
                                owner: String = SupabaseProgressPilotTest.OWNER) =
        PilotCaptureContext(PilotScope(owner, profile, 1, SupabaseProgressPilotTest.INSTANCE,
            SupabaseProgressPilotTest.DEVICE_A, SupabaseProgressPilotTest.DEVICE_A, SupabaseProgressPilotTest.DEVICE_B,
            SupabaseProgressPilotTest.EPOCH), Long.MAX_VALUE, ByteArray(32) { secret.toByte() })

    private fun portableProgress() = SupabaseProgressPilotTest.progress().copy(contentId = "freebox:smb://nas/Videos/Film.mkv",
        contentType = "freebox", videoId = "freebox:smb://nas/Videos/Film.mkv", addonBaseUrl = null)

    @Test fun encryptedResumeReconstructsPresentationWithoutImportingAPositionOrProviderCredentials() {
        val context = metadataContext()
        val saved = portableProgress().copy(videoId = "https://provider.invalid/user/password/movie.mp4")
        val row = requireNotNull(CloudProgressRow.from(context, saved))
        val wire = Gson().toJson(row)
        assertFalse(wire.contains(saved.name)); assertFalse(wire.contains("smb://nas")); assertFalse(wire.contains("password"))
        val restored = requireNotNull(CloudResumeMetadata.open(context, row))
        assertEquals(saved.contentId, restored.contentId); assertEquals(saved.name, restored.name)
        assertEquals(saved.storageKey, restored.storageKey); assertEquals(0L, restored.position); assertEquals(0L, restored.duration)
        assertNull(restored.poster); assertEquals(saved.contentId, restored.videoId)
    }

    @Test fun encryptedResumeRejectsAnotherProfileKeyAndContentIdentity() {
        val row = requireNotNull(CloudProgressRow.from(metadataContext(), portableProgress()))
        for ((context, candidate) in listOf(metadataContext(secret = 8) to row,
            metadataContext(owner = SupabaseProgressPilotTest.OTHER_OWNER) to row,
            metadataContext(SupabaseProgressPilotTest.OTHER_OWNER) to row,
            metadataContext() to row.copy(content_key = "b".repeat(64)))) {
            assertTrue(runCatching { CloudResumeMetadata.open(context, candidate) }.isFailure)
        }
    }

    @Test fun metadataEncryptionDoesNotCreateAPlaybackChangeAndRetriesKeepTheirCiphertext() {
        val context = metadataContext(); val saved = portableProgress()
        val first = requireNotNull(CloudProgressRow.from(context, saved))
        val second = requireNotNull(CloudProgressRow.from(context, saved))
        assertNotEquals(first.sealed_metadata, second.sealed_metadata); assertTrue(first.sameValue(second))
        val journal = CloudProgressJournal().enqueue(first)
        val restored = Gson().fromJson(Gson().toJson(journal), CloudProgressJournal::class.java)
        assertEquals(first.sealed_metadata, restored.batch().single().payload.sealed_metadata)
    }

    @Test fun privateSmbCredentialsAndUnsupportedAddonIdentitiesNeverEnterResumeMetadata() {
        val context = metadataContext()
        assertNull(CloudProgressRow.from(context, portableProgress().copy(contentId = "freebox:smb://user:password@nas/Videos/Film.mkv"))!!.sealed_metadata)
        assertNull(CloudProgressRow.from(context, portableProgress().copy(addonBaseUrl = "https://user:password@provider.invalid"))!!.sealed_metadata)
    }

    @Test fun multilineSynopsesRemainPortable() {
        val context = metadataContext(); val saved = portableProgress().copy(description = "Première ligne.\nDeuxième ligne.")
        val restored = CloudResumeMetadata.open(context, requireNotNull(CloudProgressRow.from(context, saved)))
        assertEquals(saved.description, restored?.description)
    }

    @Test fun oversizedPresentationCannotPreventTheNumericCheckpointFromBeingSaved() {
        val context = metadataContext()
        val saved = portableProgress().copy(contentId = "freebox:/" + "é".repeat(3000), name = "é".repeat(500),
            description = "😀".repeat(2500))
        val row = requireNotNull(CloudProgressRow.from(context, saved))
        assertEquals(saved.position, row.position_ms); assertNull(row.sealed_metadata); row.validate()
    }

    private fun row(position: Long = 10, time: Long = 1000, key: String = "a".repeat(64)) =
        CloudProgressRow(key, "file", position, 100, null, "in_progress", false, time)

    @Test fun freshJournalDoesNotImportAnySnapshot() {
        val journal = CloudProgressJournal()
        assertTrue(journal.pending.isEmpty() && journal.rows.isEmpty())
        assertEquals(0L, journal.cursor)
    }

    @Test fun unsentCheckpointsCoalesceButSequenceNeverRegresses() {
        val result = CloudProgressJournal().enqueue(row()).enqueue(row(20, 2000))
        assertEquals(2L, result.sequence)
        assertEquals(1, result.pending.size)
        assertEquals(20L, result.pending.single().payload.position_ms)
    }

    @Test fun transmittedOperationIsImmutableUntilExactAcknowledgement() {
        val first = CloudProgressJournal().enqueue(row())
        val sent = first.batch()
        val later = first.copy(transmitted = sent.map { it.operation_id }.toSet()).enqueue(row(20,2000))
        assertEquals(sent.single(), later.pending.first())
        val acked = later.acknowledge(sent, listOf(CloudProgressAck(sent.single().operation_id,"applied",7)))
        assertEquals(1, acked.pending.size)
        assertEquals(7L, acked.pending.single().expected_revision)
        assertEquals(20L, acked.pending.single().payload.position_ms)
        assertTrue(acked.transmitted.isEmpty())
    }

    @Test fun lostResponseReplayDoesNotAcknowledgeANewerOperation() {
        val first = CloudProgressJournal().enqueue(row())
        val sent = first.batch()
        val later = first.copy(transmitted=sent.map { it.operation_id }.toSet()).enqueue(row(25,2000))
        val acked = later.acknowledge(sent,listOf(CloudProgressAck(sent.single().operation_id,"duplicate",1)))
        assertEquals(25L,acked.pending.single().payload.position_ms)
        assertEquals(1L,acked.rows.values.single().server_revision)
    }

    @Test fun duplicateAcknowledgementAfterRemovalHasNoEffect() {
        val first=CloudProgressJournal().enqueue(row()); val sent=first.batch()
        val acks=listOf(CloudProgressAck(sent.single().operation_id,"applied",1))
        val done=first.acknowledge(sent,acks)
        assertEquals(done,done.acknowledge(sent,acks))
    }

    @Test fun conflictRetainsLocalProposalAndBlocksAutomaticRebase() {
        val first=CloudProgressJournal().enqueue(row()); val sent=first.batch()
        val conflict=first.acknowledge(sent,listOf(CloudProgressAck(sent.single().operation_id,"conflict",5)))
        assertTrue(conflict.pending.isEmpty())
        assertEquals(10L,conflict.conflicts.values.single().payload.position_ms)
        val newer=conflict.enqueue(row(30,3000))
        assertTrue(newer.pending.isEmpty())
        assertEquals(30L,newer.conflicts.values.single().payload.position_ms)
    }

    @Test fun serverRevisionWinsEvenWhenItsClockOrPositionIsSmaller() {
        val initial=CloudProgressJournal(rows=mapOf(row().content_key to row(90,9000).copy(server_revision=1)))
        val received=initial.receive(CloudProgressPage(listOf(row(5,100).copy(server_revision=2)),2,2))
        assertEquals(5L,received.rows.values.single().position_ms)
        assertEquals(100L,received.rows.values.single().source_time_ms)
    }

    @Test fun partialPageDoesNotDeleteMissingRows() {
        val initial=CloudProgressJournal(rows=mapOf(row().content_key to row().copy(server_revision=1)))
        val received=initial.receive(CloudProgressPage(listOf(row(key="b".repeat(64)).copy(server_revision=2)),2,4))
        assertEquals(2,received.rows.size)
        assertEquals(2L,received.cursor)
    }

    @Test fun explicitResetAndLogicalRemovalAreDifferentOperations() {
        val reset=row(0).copy(watch_state="unseen")
        val hide=row(90).copy(watch_state="completed",resume_hidden=true)
        reset.validate();hide.validate()
        assertFalse(reset.resume_hidden)
        assertEquals("completed",hide.watch_state)
    }

    @Test fun malformedAcknowledgementsAreRejectedAsAWhole() {
        val first=CloudProgressJournal().enqueue(row());val sent=first.batch()
        for (acks in listOf(emptyList(),listOf(CloudProgressAck("unknown","applied",1)),
            listOf(CloudProgressAck(sent.single().operation_id,"applied",0)),
            listOf(CloudProgressAck(sent.single().operation_id,"unknown",1)))) {
            assertTrue(runCatching { first.acknowledge(sent,acks) }.isFailure)
            assertEquals(1,first.pending.size)
        }
    }

    @Test fun malformedCursorAndNegativePositionAreRejected() {
        assertTrue(runCatching { row(-1).validate() }.isFailure)
        assertTrue(runCatching { row().copy(progress_percent=Float.NaN).validate() }.isFailure)
        assertTrue(runCatching { CloudProgressJournal(cursor=5).receive(CloudProgressPage(emptyList(),4,5)) }.isFailure)
        assertTrue(runCatching { CloudProgressJournal().receive(CloudProgressPage(emptyList(),6,5)) }.isFailure)
    }

    @Test fun journalRoundTripPreservesOperationsConflictsAndTransmissionFence() {
        val first=CloudProgressJournal().enqueue(row())
        val original=first.copy(transmitted=first.pending.map { it.operation_id }.toSet())
        assertEquals(original,Gson().fromJson(Gson().toJson(original),CloudProgressJournal::class.java))
    }

    @Test fun apiPayloadContainsNoTitlePathUrlOrProviderId() {
        val encoded=Gson().toJson(CloudProgressJournal().enqueue(row()).pending.single())
        assertFalse(encoded.contains("title") || encoded.contains("url") || encoded.contains("path") || encoded.contains("videoId"))
        assertTrue(encoded.contains("content_key"))
    }

    @Test fun backgroundForeignPullDoesNotRebaseAnActivePlayer() {
        val original=row().copy(server_revision=1)
        val started=CloudProgressJournal(rows=mapOf(original.content_key to original),writeBases=mapOf(original.content_key to 1L))
        val otherDevice=started.receive(CloudProgressPage(listOf(row(70,3000).copy(server_revision=2)),2,2))
        val checkpoint=otherDevice.enqueue(row(40,4000))
        assertEquals(1L,checkpoint.pending.single().expected_revision)
    }

    @Test fun coalescingUnsentCheckpointRetainsItsOriginalExpectedRevision() {
        val first=CloudProgressJournal().enqueue(row())
        val foreign=first.receive(CloudProgressPage(listOf(row(70).copy(server_revision=2)),2,2))
        assertEquals(0L,foreign.enqueue(row(40,4000)).pending.single().expected_revision)
    }

    @Test fun keyRepairCannotReuseOldCloudGenerationOrCacheNamespace() {
        val scope=PilotScope(SupabaseProgressPilotTest.OWNER,SupabaseProgressPilotTest.PROFILE,1,
            SupabaseProgressPilotTest.INSTANCE,SupabaseProgressPilotTest.DEVICE_A,
            SupabaseProgressPilotTest.DEVICE_A,SupabaseProgressPilotTest.DEVICE_B,java.util.UUID.randomUUID().toString())
        val generation=java.util.UUID.randomUUID().toString()
        val original=PilotLocalSettings(scope=scope,cloudKeyEpoch=generation,cloudKeyBindingEpoch=scope.epoch)
        original.validateCloudGeneration(generation)
        val repaired=original.copy(scope=scope.copy(epoch=java.util.UUID.randomUUID().toString()))
        assertTrue(runCatching { repaired.validateCloudGeneration(generation) }.isFailure)
        repaired.validateCloudGeneration(java.util.UUID.randomUUID().toString())
        assertNotEquals(cloudNamespace(original),cloudNamespace(repaired))
    }
}
