package com.jojotv.home.iptv

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class IptvRecordingSyncTest {
    private val channel = IptvItem("channel", "provider", "42", ContentType.LIVE, "Channel",
        streamUrl = "https://provider.test/private-user/private-password/stream", command = "private-provider-command",
        headers = mapOf("Authorization" to "Bearer private-token"))
    private fun local() = IptvRecording("recording-1", channel, "Programme", 20_000, 80_000,
        accountId = "arnaud", directory = "smb://nas/private-storage/", file = "private-recording.ts", bytes = 12345)
    private fun shared(recording: IptvRecording = local()) =
        IptvSharedRecording("device-A", "Living room TV", IptvRecordingSync.publicPlan(recording), revision = 7)
    private fun edit() = IptvRecordingCommand("command-1", "device-A", "recording-1", "arnaud", 7,
        "EDIT", local().copy(title = "Changed programme"))

    @Test fun sharedPlanOmitsPrivatePlaybackAndStorageFields() {
        val plan = IptvRecordingSync.publicPlan(local())
        val payload = iptvJson.encodeToString(shared(plan))
        assertEquals("", plan.directory)
        assertEquals("", plan.file)
        assertEquals(0L, plan.bytes)
        assertEquals("", plan.channel.streamUrl)
        assertEquals("", plan.channel.command)
        assertTrue(plan.channel.headers.isEmpty())
        assertFalse(payload.contains("private-"))
        assertFalse(payload.contains("smb://"))
        assertEquals("arnaud", plan.accountId)
        assertEquals(channel.id, plan.channel.id)
    }

    @Test fun sharedPlanAndCommandsRoundTripWithoutChangingTheirOwner() {
        val shared = shared()
        val command = edit()
        assertEquals(shared, iptvJson.decodeFromString<IptvSharedRecording>(iptvJson.encodeToString(shared)))
        assertEquals(command, iptvJson.decodeFromString<IptvRecordingCommand>(iptvJson.encodeToString(command)))
        assertEquals("device-A", command.owner)
    }

    @Test fun foreignDeviceNeverExecutesAnImportedSchedule() {
        assertEquals(IptvRecordingDecision.IGNORE, IptvRecordingSync.decision("device-B", shared(), local(), edit(), true))
        assertEquals(IptvRecordingDecision.IGNORE, IptvRecordingSync.decision("device-A", shared(), local(), edit().copy(owner = "device-B"), true))
    }

    @Test fun anotherAccountCannotModifyTheLocalAlarm() {
        assertEquals(IptvRecordingDecision.REJECT, IptvRecordingSync.decision("device-A", shared(), local(), edit(), false))
    }

    @Test fun pendingEditDoesNotChangeTheConfirmedPlanBeforeOwnerAppliesIt() {
        val confirmed = shared()
        assertEquals(IptvRecordingDecision.EDIT, IptvRecordingSync.decision("device-A", confirmed, local(), edit(), true))
        assertEquals("Programme", confirmed.recording.title)
        assertEquals("", confirmed.handledCommand)
        assertEquals("", confirmed.outcome)
    }

    @Test fun staleEditCannotOverwriteANewerLocalPlan() {
        assertEquals(IptvRecordingDecision.REJECT, IptvRecordingSync.decision("device-A", shared().copy(revision = 8), local(), edit(), true))
    }

    @Test fun replayAfterLostAcknowledgementDoesNotCreateAnotherRecording() {
        val applied = local().copy(title = "Changed programme")
        assertEquals(IptvRecordingDecision.ALREADY_APPLIED,
            IptvRecordingSync.decision("device-A", shared(applied).copy(revision = 8), applied, edit(), true))
    }

    @Test fun retryAfterAnAppliedEditHasStartedDoesNotModifyTheRunningCapture() {
        val running = local().copy(title = "Changed programme", state = RecordingState.RUNNING)
        assertEquals(IptvRecordingDecision.ALREADY_APPLIED,
            IptvRecordingSync.decision("device-A", shared(running).copy(revision = 8), running, edit(), true))
        assertEquals(IptvRecordingDecision.REJECT,
            IptvRecordingSync.decision("device-A", shared(running), running,
                edit().copy(desired = running.copy(title = "Another change", state = RecordingState.PLANNED)), true))
    }

    @Test fun acknowledgedCommandIsIgnoredWhenReceivedAgain() {
        assertEquals(IptvRecordingDecision.IGNORE,
            IptvRecordingSync.decision("device-A", shared().copy(handledCommand = "command-1", outcome = "applied"), local(), edit(), true))
    }

    @Test fun cancellationIsLimitedToTheCreatorsScheduledJob() {
        val command = edit().copy(action = "CANCEL", desired = null)
        assertEquals(IptvRecordingDecision.CANCEL, IptvRecordingSync.decision("device-A", shared(), local(), command, true))
        assertEquals(IptvRecordingDecision.REJECT,
            IptvRecordingSync.decision("device-A", shared(), local(), command.copy(revision = 1), true))
        for (state in listOf(RecordingState.RUNNING, RecordingState.COMPLETE, RecordingState.PARTIAL, RecordingState.FAILED)) {
            assertEquals(IptvRecordingDecision.REJECT,
                IptvRecordingSync.decision("device-A", shared(), local().copy(state = state), command, true))
        }
    }

    @Test fun cancellingAnAlreadyCancelledOrMissingLocalJobIsIdempotent() {
        val command = edit().copy(action = "CANCEL", desired = null)
        assertEquals(IptvRecordingDecision.ALREADY_APPLIED,
            IptvRecordingSync.decision("device-A", shared(), local().copy(state = RecordingState.CANCELLED), command, true))
        assertEquals(IptvRecordingDecision.ALREADY_APPLIED,
            IptvRecordingSync.decision("device-A", shared(), null, command, true))
        assertEquals(IptvRecordingDecision.REJECT, IptvRecordingSync.decision("device-A", shared(), null, edit(), true))
    }

    @Test fun malformedOrUnsupportedRequestsAreRejected() {
        val bad = listOf(edit().copy(schema = 2), edit().copy(id = ""), edit().copy(recordingId = "other"),
            edit().copy(action = "DELETE"), edit().copy(desired = null),
            edit().copy(desired = local().copy(id = "other")),
            edit().copy(desired = local().copy(channel = channel.copy(type = ContentType.MOVIE))))
        bad.forEach { command -> assertEquals(IptvRecordingDecision.REJECT,
            IptvRecordingSync.decision("device-A", shared(), local(), command, true)) }
    }

    @Test fun fileSizeStatusAndPathUpdatesDoNotConflictWithEditingThePlan() {
        assertTrue(IptvRecordingSync.samePlan(local(), local().copy(bytes = 99999, file = "another.ts",
            directory = "/different/storage", state = RecordingState.RUNNING)))
        assertFalse(IptvRecordingSync.samePlan(local(), local().copy(title = "Changed")))
        assertFalse(IptvRecordingSync.samePlan(local(), local().copy(programStart = 30_000)))
    }

    @Test fun captureByteCountersDoNotChangeTheSharedPayload() {
        val before = IptvRecordingSync.publicPlan(local())
        val after = IptvRecordingSync.publicPlan(local().copy(bytes = 99999, directory = "/other/storage",
            file = "other.ts", error = "private-error-details"))
        assertEquals(before, after)
        assertEquals(iptvJson.encodeToString(before), iptvJson.encodeToString(after))
    }

    @Test fun metadataMustBePublishedByItsOwnerUnderItsOwnRecordingId() {
        val value = iptvJson.encodeToString(shared())
        val rows = IptvSyncSnapshot(records = mapOf(
            "recording:recording-1" to IptvSyncRecord(1, "device-A", value),
            "recording:wrong-key" to IptvSyncRecord(2, "device-A", value),
            "recording:forged" to IptvSyncRecord(3, "device-B", value),
            "recording:new-schema" to IptvSyncRecord(4, "device-A", iptvJson.encodeToString(shared().copy(schema = 2)))))
        assertEquals(setOf("recording-1"), IptvRecordingSync.shared(rows).keys)
        assertEquals(4, rows.records.size)
    }

    @Test fun mergingConcurrentMetadataAndCommandsRetainsBothAndDeletionTombstones() {
        val owner = mapOf("recording:recording-1" to IptvSyncRecord(10, "device-A", iptvJson.encodeToString(shared())))
        val requester = mapOf("command:recording-1" to IptvSyncRecord(20, "device-B", iptvJson.encodeToString(edit())))
        val merged = mergeIptvSyncRecords(owner, requester)
        assertEquals(merged, mergeIptvSyncRecords(requester, owner))
        assertEquals(2, merged.size)
        val deleted = mergeIptvSyncRecords(merged, mapOf("recording:recording-1" to IptvSyncRecord(30, "device-A")))
        assertTrue(IptvRecordingSync.shared(IptvSyncSnapshot(records = deleted)).isEmpty())
        assertNotNull(deleted["command:recording-1"]?.value)
    }

    @Test fun anEditRetainsTheCreatorsStorageAndRecordingIdentity() {
        val proposed = edit().desired!!.copy(directory = "/requester/storage", file = "requester.ts")
        val applied = IptvRecordingScheduler.replanned(local(), proposed.channel, proposed.title,
            proposed.programStart, proposed.programEnd, proposed.beforeMinutes, proposed.afterMinutes,
            now = 10_000, repeatDays = proposed.repeatDays)
        assertEquals(local().directory, applied.directory)
        assertEquals(local().file, applied.file)
        assertEquals(local().accountId, applied.accountId)
        assertEquals(local().id, applied.id)
    }

    @Test fun journalNamespaceSeparatesNasAccountsAndServersWithoutKeepingTheirCredentials() {
        val first = IptvRecordingSync.authority("http://nas.test/", "Arnaud@example.test", "token-1")
        assertEquals(first, IptvRecordingSync.authority("http://nas.test", " arnaud@example.test ", "token-2"))
        assertNotEquals(first, IptvRecordingSync.authority("http://nas.test", "other@example.test", "token-1"))
        assertNotEquals(first, IptvRecordingSync.authority("http://other-nas.test", "arnaud@example.test", "token-1"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.contains("token-1"))
        assertNotEquals(IptvRecordingSync.authority("http://nas.test", "", "token-1"),
            IptvRecordingSync.authority("http://nas.test", "", "token-2"))
    }
}
