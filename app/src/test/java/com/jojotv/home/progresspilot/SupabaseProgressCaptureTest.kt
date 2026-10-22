package com.jojotv.home.progresspilot

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SupabaseProgressCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val activeProfile = MutableStateFlow(1)
    private var capture: PilotCaptureContext? = null
    private lateinit var jobs: Job
    private lateinit var io: CoroutineScope
    private var stores = mutableMapOf<String, DataStore<Preferences>>()
    private lateinit var preferences: WatchProgressPreferences

    @Before fun setUp() {
        jobs = SupervisorJob()
        io = CoroutineScope(jobs + Dispatchers.IO)
        preferences = makePreferences()
    }

    @After fun tearDown() = runBlocking { io.cancel(); jobs.join() }

    @Test fun explicitMarkerRecoveryAndEpochRepairPreserveAllNonPilotPreferences() = runBlocking {
        enroll()
        val previous = requireNotNull(capture).scope
        capture = null // NAS_ONLY; no checkpoint import.
        val progress = SupabaseProgressPilotTest.progress()
        preferences.saveProgress(progress,1)
        preferences.enqueuePendingRemoteDeletes(listOf("nas-only-delete"),1)
        val marker = androidx.datastore.preferences.core.stringPreferencesKey("progress_pilot_profile_instance")
        val outbox = androidx.datastore.preferences.core.stringPreferencesKey("progress_pilot_delivery_outbox")
        delivery(1).edit { it[outbox] = PilotJson.encode(PilotJournal(epoch=previous.epoch,sequence=42)) }
        local(1).edit { it.remove(marker) } // Synthetic loss of the technical marker.
        val before = local(1).data.first().asMap()
        assertNull(preferences.pilotProfileInstanceId(1))
        assertTrue(runCatching { previous.checkKeyProfileInstance(null) }.isFailure)
        val instance = preferences.getOrCreatePilotProfileInstanceId(1)
        val next = previous.copy(localProfileInstanceId=instance,epoch=UUID.randomUUID().toString())
        val repaired = preferences.pilotDeliveryQueue().repairKeyEpoch(next,previous.epoch,42)
        assertEquals(before,local(1).data.first().asMap().filterKeys { it != marker })
        assertEquals(progress,preferences.getAllRawEntries(1)[progress.storageKey])
        assertEquals(setOf("nas-only-delete"),preferences.pendingRemoteDeleteKeys(1))
        assertEquals(42L,repaired.sequence)
        assertTrue(repaired.pending.isEmpty() && repaired.fingerprints.isEmpty())
        assertEquals(instance,preferences.pilotProfileInstanceId(1))
    }

    @Test fun delayedCaptureCannotPolluteKeyRepairWhileNasProgressIsPreserved() = runBlocking {
        val realDelivery = delivery(1)
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val firstWrite = AtomicBoolean(true)
        val delayed = object : DataStore<Preferences> {
            override val data = realDelivery.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (!firstWrite.compareAndSet(true, false)) return realDelivery.updateData(transform)
                waiting.complete(Unit)
                release.await()
                try { return realDelivery.updateData(transform) }
                finally { finished.complete(Unit) }
            }
        }
        preferences = WatchProgressPreferences(::local, activeProfile, { capture }, { delayed })
        enroll()
        val previous = requireNotNull(capture).scope
        val outboxKey = androidx.datastore.preferences.core.stringPreferencesKey("progress_pilot_delivery_outbox")
        realDelivery.edit { it[outboxKey] = PilotJson.encode(PilotJournal(epoch = previous.epoch, sequence = 42)) }
        val progress = SupabaseProgressPilotTest.progress()
        try {
            saveCheckpoint(progress)
            withTimeout(5000) { waiting.await() }
            capture = null // Explicit NAS_ONLY before key repair.
            val bound = previous.copy(epoch = UUID.randomUUID().toString())
            val repaired = preferences.pilotDeliveryQueue().repairKeyEpoch(bound, previous.epoch, 42)
            release.complete(Unit)
            withTimeout(5000) { finished.await() }
            assertEquals(repaired, preferences.observePilotDelivery(1).first())
            assertEquals(42L, repaired.sequence)
            assertNull(repaired.issue)
            assertTrue(repaired.pending.isEmpty())
            assertEquals(progress, preferences.getAllRawEntries()[progress.storageKey])
        } finally { release.complete(Unit) }
    }

    @Test fun nasOnlyPreservesProgressWithoutCreatingDeliveryFile() = runBlocking {
        val progress = SupabaseProgressPilotTest.progress()
        saveCheckpoint(progress)
        assertEquals(progress, preferences.getAllRawEntries()[progress.storageKey])
        assertFalse(File(temporary.root, "delivery-1.preferences_pb").exists())
        assertEquals(setOf("local-1"), stores.keys)
    }

    @Test fun activationDoesNotImportExistingProgressAndOnlyFuturePlayerCheckpointIsCaptured() = runBlocking {
        val old = SupabaseProgressPilotTest.progress()
        preferences.saveProgress(old)
        enroll()
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        saveCheckpoint(old.copy(lastWatched = 2000, position = 20_000), elapsed = 99)
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        val new = old.copy(lastWatched = 3000, position = 30_000)
        saveCheckpoint(new)
        val captured = awaitPending()
        assertEquals(1, captured.pending.size)
        assertEquals(3000L, captured.pending.values.single().payload.source_time_ms)
        assertEquals(new, preferences.getAllRawEntries()[new.storageKey])
    }

    @Test fun nasPullLegacyBatchAndMetadataWritesNeverCreateMirrorOperations() = runBlocking {
        enroll()
        val progress = SupabaseProgressPilotTest.progress()
        preferences.saveProgress(progress)
        preferences.saveProgressBatch(listOf(progress.copy(lastWatched = 2000)))
        preferences.mergeRemoteEntries(mapOf(progress.storageKey to progress.copy(lastWatched = 3000)), profileId = 1)
        preferences.applyRemoteChanges(mapOf(progress.storageKey to progress.copy(lastWatched = 4000)), emptyList(), profileId = 1)
        preferences.replaceWithRemoteEntries(mapOf(progress.storageKey to progress.copy(lastWatched = 5000)), 1)
        preferences.saveProgress(progress.copy(name = "Metadata only", lastWatched = 5000))
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        assertEquals(5000L, preferences.getAllRawEntries()[progress.storageKey]?.lastWatched)
    }

    @Test fun staleLocalCheckpointCannotTurnNewerImportedProgressIntoMirrorData() = runBlocking {
        enroll()
        val newer = SupabaseProgressPilotTest.progress(3000)
        preferences.saveProgress(newer)
        saveCheckpoint(newer.copy(lastWatched = 1000, position = 1000))
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        assertEquals(newer, preferences.getAllRawEntries()[newer.storageKey])
    }

    @Test fun onlyExplicitBoundProfileAndInstanceAreAccepted() = runBlocking {
        enroll()
        val second = SupabaseProgressPilotTest.progress()
        saveCheckpoint(second, profileId = 2)
        assertTrue(preferences.observePilotDelivery(2).first().pending.isEmpty())
        assertEquals(second, preferences.getAllRawEntries(2)[second.storageKey])
        local(1).edit { it.clear() } // Existing profile deletion clears its technical instance too.
        val recreated = preferences.getOrCreatePilotProfileInstanceId(1)
        assertNotEquals(capture!!.scope.localProfileInstanceId, recreated)
        saveCheckpoint(second.copy(lastWatched = 2000))
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        assertEquals(2000L, preferences.getAllRawEntries()[second.storageKey]?.lastWatched)
    }

    @Test fun deliveryAcknowledgementNeverAcknowledgesNasDeletesOrChangesProgress() = runBlocking {
        enroll()
        val progress = SupabaseProgressPilotTest.progress()
        saveCheckpoint(progress)
        val journal = awaitPending()
        val operation = journal.pending.values.single()
        preferences.enqueuePendingRemoteDeletes(listOf("nas-only-delete"), 1)
        val localBefore = preferences.getAllRawEntries(1)
        preferences.acknowledgePilotOperations(capture!!.scope, listOf(operation),
            listOf(PilotAcknowledgement(operation.operation_id, operation.revision, "applied")))
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        assertEquals(localBefore, preferences.getAllRawEntries(1))
        assertEquals(setOf("nas-only-delete"), preferences.pendingRemoteDeleteKeys(1))
    }

    @Test fun disablingDoesNotStopLocalSavesAndNewCheckpointsAreNotQueued() = runBlocking {
        enroll()
        val first = SupabaseProgressPilotTest.progress()
        saveCheckpoint(first)
        val before = awaitPending()
        capture = null
        val later = first.copy(lastWatched = 2000, position = 40_000)
        saveCheckpoint(later)
        assertEquals(before, preferences.observePilotDelivery(1).first())
        assertEquals(later, preferences.getAllRawEntries(1)[later.storageKey])
    }

    @Test fun explicitRemovalIsNotAPlaybackCheckpointAndCannotMakeTheQueueAResumeSource() = runBlocking {
        enroll()
        val progress = SupabaseProgressPilotTest.progress()
        saveCheckpoint(progress)
        val before = awaitPending()
        preferences.removeProgress(progress.contentId, profileId = 1)
        assertTrue(preferences.getAllRawEntries(1).isEmpty())
        assertEquals(before, preferences.observePilotDelivery(1).first())
        // The earlier unsent observation remains a delivery operation, never a resume card.
    }

    @Test fun queuedOperationSurvivesRecreatingDataStoreWithSameUuidAndRevision() = runBlocking {
        enroll()
        saveCheckpoint(SupabaseProgressPilotTest.progress())
        val before = awaitPending()
        jobs.cancel(); jobs.join()
        stores = mutableMapOf()
        jobs = SupervisorJob()
        io = CoroutineScope(jobs + Dispatchers.IO)
        preferences = makePreferences()
        assertEquals(before, preferences.observePilotDelivery(1).first())
        assertEquals(before.pending.values.single().operation_id,
            preferences.observePilotDelivery(1).first().pending.values.single().operation_id)
    }

    @Test fun deliveryDiskFailureCannotFailAcceptedLocalSave() = runBlocking {
        enroll()
        val failing = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw IOException("simulated delivery disk failure")
            }
        }
        preferences = WatchProgressPreferences(::local, activeProfile, { capture }, { failing })
        val progress = SupabaseProgressPilotTest.progress()
        saveCheckpoint(progress) // Must complete even though separate queue I/O fails.
        assertEquals(progress, preferences.getAllRawEntries()[progress.storageKey])
        val issue = withTimeout(5000) { preferences.observePilotDelivery(1).first { it.issue != null } }
        assertEquals("CAPTURE_FAILED", issue.issue)
    }

    @Test fun corruptDeliveryJournalIsQuarantinedWithoutRepairingOrOverwritingLocalProgress() = runBlocking {
        enroll()
        val outboxKey = androidx.datastore.preferences.core.stringPreferencesKey("progress_pilot_delivery_outbox")
        delivery(1).edit { it[outboxKey] = "corrupt" }
        val progress = SupabaseProgressPilotTest.progress()
        saveCheckpoint(progress)
        val journal = withTimeout(5000) { preferences.observePilotDelivery(1).first { it.issue != null } }
        assertEquals("OUTBOX_UNREADABLE", journal.issue)
        assertEquals("corrupt", delivery(1).data.first()[outboxKey])
        assertEquals(progress, preferences.getAllRawEntries()[progress.storageKey])
    }

    @Test fun freeboxReplacementCapturesOnlyAcceptedCanonicalCheckpoint() = runBlocking {
        enroll()
        val progress = SupabaseProgressPilotTest.progress().copy(contentId = "freebox:/film.mkv", contentType = "freebox")
        withContext(PilotPlaybackCheckpoint(progress.lastWatched, 101)) {
            assertTrue(preferences.saveFreeboxProgressReplacingEquivalent(progress, 1).accepted)
        }
        val before = awaitPending()
        val older = progress.copy(lastWatched = 900, position = 2000)
        withContext(PilotPlaybackCheckpoint(older.lastWatched, 101)) {
            assertFalse(preferences.saveFreeboxProgressReplacingEquivalent(older, 1).accepted)
        }
        assertEquals(before, preferences.observePilotDelivery(1).first())
        assertEquals("file", before.pending.values.single().payload.media_kind)
        assertEquals(progress, preferences.getAllRawEntries()[progress.storageKey])
    }

    private fun makePreferences() = WatchProgressPreferences(::local, activeProfile, { capture }, ::delivery)
    private fun local(id: Int) = store("local", id)
    private fun delivery(id: Int) = store("delivery", id)
    private fun store(kind: String, id: Int): DataStore<Preferences> = synchronized(stores) {
        stores.getOrPut("$kind-$id") {
            PreferenceDataStoreFactory.create(scope = io) { File(temporary.root, "$kind-$id.preferences_pb") }
        }
    }

    @Test fun thirdDeviceCopiesOnlyItsNewPlayerCheckpointsWithoutImportingHistory() = runBlocking {
        val old = SupabaseProgressPilotTest.progress()
        preferences.saveProgress(old)
        enroll(thirdDevice = true)
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        preferences.applyRemoteChanges(mapOf(old.storageKey to old.copy(lastWatched = 2000)), emptyList(), 1)
        assertTrue(preferences.observePilotDelivery(1).first().pending.isEmpty())
        val latest = old.copy(lastWatched = 3000, position = 30_000)
        saveCheckpoint(latest)
        val operation = awaitPending().pending.values.single()
        assertEquals(SupabaseProgressPilotTest.DEVICE_C, operation.payload.device_id)
        assertEquals(3000L, operation.payload.source_time_ms)
        assertEquals(latest, preferences.getAllRawEntries(1)[old.storageKey])
    }

    @Test fun disablingThirdDevicePreservesItsQueueWhileLocalNasProgressContinues() = runBlocking {
        enroll(thirdDevice = true)
        val first = SupabaseProgressPilotTest.progress()
        saveCheckpoint(first)
        val before = awaitPending()
        capture = null
        val latest = first.copy(lastWatched = 2000, position = 20_000)
        saveCheckpoint(latest)
        assertEquals(before, preferences.observePilotDelivery(1).first())
        assertEquals(latest, preferences.getAllRawEntries(1)[first.storageKey])
    }

    private suspend fun enroll(thirdDevice: Boolean = false) {
        val instance = preferences.getOrCreatePilotProfileInstanceId(1)
        val scope = PilotScope(SupabaseProgressPilotTest.OWNER, SupabaseProgressPilotTest.PROFILE, 1, instance,
            if (thirdDevice) SupabaseProgressPilotTest.DEVICE_C else SupabaseProgressPilotTest.DEVICE_A,
            SupabaseProgressPilotTest.DEVICE_A, SupabaseProgressPilotTest.DEVICE_B,
            UUID.randomUUID().toString(), deviceC = SupabaseProgressPilotTest.DEVICE_C.takeIf { thirdDevice })
        capture = PilotCaptureContext(scope, 100, ByteArray(32) { it.toByte() })
    }

    private suspend fun saveCheckpoint(progress: WatchProgress, elapsed: Long = 101, profileId: Int = 1) {
        withContext(PilotPlaybackCheckpoint(progress.lastWatched, elapsed)) { preferences.saveProgress(progress, profileId) }
    }

    private suspend fun awaitPending() = withTimeout(5000) {
        preferences.observePilotDelivery(1).first { it.pending.isNotEmpty() }
    }
}
