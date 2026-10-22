package com.jojotv.home

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.sync.NasWatchProgress
import com.nuvio.tv.core.sync.NasWatchProgressSnapshot
import com.nuvio.tv.core.sync.WatchProgressRemote
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.data.local.NasSyncStateStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class WatchProgressSyncServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = MutableStateFlow(1)
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: WatchProgressPreferences
    private lateinit var state: NasSyncStateStore
    private lateinit var remote: Remote
    private lateinit var service: WatchProgressSyncService
    private val stores = mutableMapOf<Int, DataStore<Preferences>>()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stores.clear()
        preferences = WatchProgressPreferences({ id ->
            synchronized(stores) {
                stores.getOrPut(id) {
                    PreferenceDataStoreFactory.create(scope = scope) {
                        File(temporary.root, "progress-$id.preferences_pb")
                    }
                }
            }
        }, profile)
        state = NasSyncStateStore(memoryPreferences())
        remote = Remote()
        service = WatchProgressSyncService(preferences, profile, remote, state)
    }

    @After fun tearDown() { scope.cancel() }

    @Test fun progressReadsDoNotRunOnThePlayerCallerThread() = runBlocking {
        val movie = progress("freebox:recording", 100).copy(contentType = "freebox")
        val episode = progress("series", 200).copy(contentType = "iptv_series", season = 2, episode = 3)
        preferences.saveProgress(movie, 1)
        preferences.saveProgress(episode, 1)
        val snapshot = stores.getValue(1).data.first()
        val readerThreads = ConcurrentLinkedQueue<Thread>()
        val trackedStore = object : DataStore<Preferences> {
            override val data = flow {
                readerThreads.add(Thread.currentThread())
                emit(snapshot)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("This store is read-only")
        }
        val reads = WatchProgressPreferences({ trackedStore }, profile)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { playerDispatcher ->
            withContext(playerDispatcher) {
                val playerThread = Thread.currentThread()
                assertEquals(movie, reads.getProgress(movie.contentId).first())
                assertEquals(episode, reads.getEpisodeProgress("series", 2, 3).first())
                assertEquals(mapOf((2 to 3) to episode), reads.getAllEpisodeProgress("series").first())
                assertEquals(setOf(movie, episode), reads.getAllRawEntries(1).values.toSet())
                assertEquals(4, readerThreads.size)
                assertTrue(readerThreads.all { it !== playerThread })
            }
        }
    }

    @Test fun episodeBecomesWatchedAtExactlyNinetyThreePercent() {
        val episode = progress("series-episode", 100).copy(
            contentType = "series", season = 1, episode = 1, duration = 100_000L,
            position = 92_999L
        )
        assertFalse(episode.isCompleted())
        assertTrue(episode.isInProgress())
        val completed = episode.copy(position = 93_000L)
        assertTrue(completed.isCompleted())
        assertFalse(completed.isInProgress())
        assertTrue(episode.copy(position = 100_000L).isCompleted())
        assertFalse(episode.copy(duration = 0L).isCompleted())
        assertFalse(episode.copy(progressPercent = 92.99f).isCompleted())
        assertTrue(episode.copy(progressPercent = 93f).isCompleted())
        assertTrue(episode.copy(contentType = "video", position = 93_000L).isCompleted())
        assertFalse(episode.copy(contentType = "video", position = 92_999L).isCompleted())
    }

    @Test fun episodeThresholdPreservesMovieAndLiveRules() {
        val movie = progress("movie", 100).copy(
            contentType = "movie", duration = 100_000L, position = 90_000L
        )
        assertTrue(movie.isCompleted())
        assertFalse(movie.copy(position = 89_999L).isCompleted())
        assertFalse(movie.copy(contentType = "iptv", progressPercent = 100f).isCompleted())
    }

    @Test fun completedEpisodeThresholdSurvivesNasRoundTrip() = runBlocking {
        val episode = progress("series-episode", 100).copy(
            contentType = "series", season = 1, episode = 1, duration = 100_000L, position = 93_000L
        )
        remote.put(1, episode)
        service.migrateExistingLocalSnapshotIfNeeded(1).getOrThrow()
        val restored = preferences.getAllRawEntries(1).values.first { it.contentId == episode.contentId }
        assertEquals(episode.season, restored.season)
        assertEquals(episode.episode, restored.episode)
        assertTrue(restored.isCompleted())
        assertFalse(restored.isInProgress())
    }

    @Test fun initialMigrationMergesEvenWhenLocalHasMoreEntries() = runBlocking {
        preferences.saveProgress(progress("local-a", 100))
        preferences.saveProgress(progress("local-b", 100))
        preferences.saveProgress(progress("shared", 100))
        remote.put(1, progress("remote-only", 200))
        remote.put(1, progress("shared", 300))
        service.migrateExistingLocalSnapshotIfNeeded(1).getOrThrow()
        assertEquals(setOf("local-a", "local-b", "shared", "remote-only"), remote.rows(1).values.map { it.contentId }.toSet())
        assertEquals(300L, preferences.getAllRawEntries(1).values.first { it.contentId == "shared" }.lastWatched)
        assertTrue(remote.deletes.isEmpty())
        assertTrue(state.migrationCompleted(1))
    }

    @Test fun olderTombstoneDoesNotSkipUnrelatedUpsertsOrDeletes() = runBlocking {
        preferences.saveProgress(progress("new-local", 500))
        preferences.saveProgress(progress("deleted", 100))
        remote.tombstones["new-local"] = 200
        remote.tombstones["deleted"] = 200
        remote.put(1, progress("new-remote", 300))
        val result = service.syncDeltaFromRemote(1).getOrThrow()
        assertTrue(result.preservedLocalItems)
        assertEquals(setOf("new-local", "new-remote"), preferences.getAllRawEntries(1).values.map { it.contentId }.toSet())
    }

    @Test fun pendingDeleteAndSnapshotCompleteWithoutNestedMutexDeadlock() = runBlocking {
        preferences.saveProgress(progress("deleted", 100), 1)
        remote.put(1, progress("deleted", 100))
        remote.put(1, progress("keep", 200))
        preferences.enqueuePendingRemoteDeletes(listOf("deleted"), 1)
        withTimeout(5000) { service.syncDeltaFromRemote(1).getOrThrow() }
        assertEquals(setOf("keep"), preferences.getAllRawEntries(1).values.map { it.contentId }.toSet())
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
        assertEquals(listOf(1 to setOf("deleted")), remote.deletes)
    }

    @Test fun failedMigrationRemainsRetryableAndDoesNotEraseRemote() = runBlocking {
        preferences.saveProgress(progress("local", 100))
        remote.put(1, progress("remote", 200))
        remote.online = false
        assertTrue(service.migrateExistingLocalSnapshotIfNeeded(1).isFailure)
        assertFalse(state.migrationCompleted(1))
        remote.online = true
        service.migrateExistingLocalSnapshotIfNeeded(1).getOrThrow()
        assertEquals(setOf("local", "remote"), remote.rows(1).values.map { it.contentId }.toSet())
        assertTrue(remote.deletes.isEmpty())
    }

    @Test fun capturedProfileSurvivesProfileChangeDuringPush() = runBlocking {
        preferences.saveProgress(progress("first", 100), 1)
        remote.beforePush = { profile.value = 2 }
        service.pushToRemote(1).getOrThrow()
        assertEquals(setOf("first"), remote.rows(1).values.map { it.contentId }.toSet())
        assertTrue(preferences.getAllRawEntries(2).isEmpty())
        assertTrue(preferences.getLastSuccessfulPushMs(1) > 0)
        assertEquals(0L, preferences.getLastSuccessfulPushMs(2))
    }

    @Test fun concurrentInitializationsMergeOnlyOnce() = runBlocking {
        preferences.saveProgress(progress("local", 100))
        remote.put(1, progress("remote", 200))
        withTimeout(5000) {
            val first = async { service.migrateExistingLocalSnapshotIfNeeded(1).getOrThrow() }
            val second = async { service.migrateExistingLocalSnapshotIfNeeded(1).getOrThrow() }
            first.await()
            second.await()
        }
        assertEquals(1, remote.pushes)
        assertEquals(2, remote.rows(1).values.map { it.contentId }.distinct().size)
    }

    @Test fun acknowledgementCannotDropCheckpointWrittenDuringPush() = runBlocking {
        val first = progress("concurrent", 100)
        preferences.saveProgress(first)
        state.enqueueProgressUpsert(1, first.storageKey)
        remote.beforePush = {
            preferences.saveProgress(first.copy(position = 7000, lastWatched = 200))
            state.enqueueProgressUpsert(1, first.storageKey)
        }
        service.pushToRemote(1).getOrThrow()
        assertEquals(setOf(first.storageKey), state.pendingProgressUpserts(1))
        remote.beforePush = {}
        service.flushPendingUpserts(1).getOrThrow()
        assertTrue(state.pendingProgressUpserts(1).isEmpty())
        assertEquals(7000L, remote.rows(1).getValue(first.storageKey).position)
    }

    @Test fun staleSingleCallbackPublishesNewestSavedCheckpoint() = runBlocking {
        val old = progress("rewind", 100)
        val latest = old.copy(position = 3000, lastWatched = 200)
        preferences.saveProgress(latest)
        state.enqueueProgressUpsert(1, latest.storageKey)
        service.pushSingleToRemote(old.storageKey, old, 1).getOrThrow()
        assertEquals(3000L, remote.rows(1).getValue(latest.storageKey).position)
    }

    @Test fun offlineSinglePushDefersWithoutLosingTheLatestCheckpoint() = runBlocking {
        var now = 0L
        service = WatchProgressSyncService(preferences, profile, remote, state) { now }
        var attempts = 0
        remote.beforePush = { attempts++; throw java.io.IOException("NAS unavailable") }
        val old = progress("offline", 100)
        preferences.saveProgress(old)
        state.enqueueProgressUpsert(1, old.storageKey)
        assertTrue(service.pushSingleToRemote(old.storageKey, old, 1).isFailure)

        val latest = old.copy(position = 7000, lastWatched = 200)
        preferences.saveProgress(latest)
        state.enqueueProgressUpsert(1, latest.storageKey)
        now = 14_999L
        service.pushSingleToRemote(old.storageKey, old, 1).getOrThrow()
        assertEquals(1, attempts)
        assertEquals(setOf(latest.storageKey), state.pendingProgressUpserts(1))
        assertEquals(0L, preferences.getLastSuccessfulPushMs(1))

        now = 15_000L
        remote.beforePush = { attempts++ }
        service.pushSingleToRemote(old.storageKey, old, 1).getOrThrow()
        assertEquals(2, attempts)
        assertEquals(7000L, remote.rows(1).getValue(latest.storageKey).position)
        assertTrue(state.pendingProgressUpserts(1).isEmpty())
        assertTrue(preferences.getLastSuccessfulPushMs(1) > 0L)
    }

    @Test fun singlePushBackoffIsBoundedScopedAndResetBySuccessfulFullPush() = runBlocking {
        var now = 0L
        service = WatchProgressSyncService(preferences, profile, remote, state) { now }
        var attempts = 0
        remote.beforePush = { attempts++; throw java.io.IOException("NAS unavailable") }
        val item = progress("retry", 100)
        preferences.saveProgress(item)
        state.enqueueProgressUpsert(1, item.storageKey)
        assertTrue(service.pushSingleToRemote(item.storageKey, item, 1).isFailure)
        for (delay in listOf(15_000L, 30_000L, 60_000L, 120_000L, 120_000L)) {
            val previousAttempts = attempts
            now += delay - 1L
            service.pushSingleToRemote(item.storageKey, item, 1).getOrThrow()
            assertEquals(previousAttempts, attempts)
            now++
            assertTrue(service.pushSingleToRemote(item.storageKey, item, 1).isFailure)
            assertEquals(previousAttempts + 1, attempts)
        }

        remote.beforePush = { attempts++ }
        preferences.saveProgress(item, 2)
        state.enqueueProgressUpsert(2, item.storageKey)
        service.pushSingleToRemote(item.storageKey, item, 2).getOrThrow()
        assertTrue(state.pendingProgressUpserts(2).isEmpty())
        assertEquals(setOf(item.storageKey), state.pendingProgressUpserts(1))

        service.pushToRemote(1).getOrThrow()
        val latest = item.copy(position = 9000, lastWatched = 200)
        preferences.saveProgress(latest)
        state.enqueueProgressUpsert(1, latest.storageKey)
        val attemptsBefore = attempts
        service.pushSingleToRemote(latest.storageKey, latest, 1).getOrThrow()
        assertEquals(attemptsBefore + 1, attempts)
        assertEquals(9000L, remote.rows(1).getValue(latest.storageKey).position)
    }

    @Test fun cancelledSinglePushDoesNotStartAnOfflineBackoff() = runBlocking {
        service = WatchProgressSyncService(preferences, profile, remote, state) { 0L }
        val item = progress("cancelled", 100)
        preferences.saveProgress(item)
        state.enqueueProgressUpsert(1, item.storageKey)
        remote.beforePush = { throw CancellationException("cancelled") }
        try {
            service.pushSingleToRemote(item.storageKey, item, 1)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(setOf(item.storageKey), state.pendingProgressUpserts(1))
        }
        remote.beforePush = {}
        service.pushSingleToRemote(item.storageKey, item, 1).getOrThrow()
        assertEquals(1, remote.pushes)
        assertTrue(state.pendingProgressUpserts(1).isEmpty())
    }

    @Test fun storageSeparatesProvidersTypesAndEpisodes() = runBlocking {
        val base = progress("same-id", 100)
        val variants = listOf(base, base.copy(addonBaseUrl = "provider-a"),
            base.copy(addonBaseUrl = "provider-b"), base.copy(contentType = "movie"),
            base.copy(contentType = "series", season = 1, episode = 1),
            base.copy(contentType = "series", season = 2, episode = 1))
        preferences.saveProgressBatch(variants)
        assertEquals(variants.size, preferences.getAllRawEntries().size)
        assertEquals(variants.size, variants.map { it.storageKey }.distinct().size)
        assertNull(preferences.getProgress("same-id").first())
    }

    @Test fun newerRewindWinsOverLargerOldPosition() = runBlocking {
        val old = progress("rewind", 100).copy(position = 60000)
        preferences.saveProgress(old)
        remote.put(1, old.copy(position = 5000, lastWatched = 200))
        service.syncDeltaFromRemote(1).getOrThrow()
        assertEquals(5000L, preferences.getAllRawEntries().values.single().position)
    }

    @Test fun tombstoneChecksTimestampInsideStorageTransaction() = runBlocking {
        val latest = progress("still-playing", 300)
        preferences.saveProgress(latest)
        preferences.applyRemoteChanges(emptyMap(), listOf(latest.legacyStorageKey),
            profileId = 1, deletionTimes = mapOf(latest.legacyStorageKey to 200L))
        assertEquals(300L, preferences.getAllRawEntries().values.single().lastWatched)
    }

    @Test fun remoteLegacyAndNewAliasProduceOneLocalEpisode() = runBlocking {
        val episode = progress("show", 100).copy(contentType = "series", season = 1, episode = 2)
        remote.put(1, episode)
        preferences.saveProgress(episode.copy(lastWatched = 200, position = 5000))
        service.pushToRemote(1).getOrThrow()
        service.syncDeltaFromRemote(1).getOrThrow()
        assertEquals(1, preferences.getAllRawEntries().size)
        assertEquals(5000L, preferences.getAllRawEntries().values.single().position)
    }

    @Test fun liveDvrDoesNotBecomeVodDurationOrCompletion() = runBlocking {
        val live = progress("channel", 100).copy(contentType = "iptv", position = 150000, duration = 180000)
        preferences.saveProgress(live)
        val stored = preferences.getAllRawEntries().values.single()
        assertEquals(0L, stored.position)
        assertEquals(0L, stored.duration)
        assertEquals(0L, live.remainingTime)
        assertFalse(live.isCompleted())
        assertEquals(1, preferences.observeContinueWatching(1).first().size)
    }

    @Test fun shortVideoAndDescriptionSurviveRoundTrip() = runBlocking {
        val short = progress("short", 100).copy(duration = 30000, position = 5000, description = "Original synopsis")
        preferences.saveProgress(short)
        service.pushToRemote(1).getOrThrow()
        assertEquals(short, preferences.getAllRawEntries().values.single())
        assertEquals(25000L, short.remainingTime)
        assertEquals("Original synopsis", remote.rows(1).values.single().description)
    }

    @Test fun legacyUnscopedEntriesBelongOnlyToPrimaryProfile() {
        val files = """[{"key":"smb://nas/share/video.mp4","title":"Same title","position":5000,"duration":30000,"updatedAt":100}]"""
        val primary = LegacyHomeProgress.parse(files, emptyList(), 1)
        assertEquals(1, primary.size)
        assertEquals("freebox:smb://nas/share/video.mp4", primary.single().contentId)
        assertTrue(LegacyHomeProgress.parse(files, emptyList(), 2).isEmpty())
        val scoped = files.replace("\"updatedAt\":100", "\"updatedAt\":100,\"profileId\":2")
        assertTrue(LegacyHomeProgress.parse(scoped, emptyList(), 1).isEmpty())
        assertEquals(1, LegacyHomeProgress.parse(scoped, emptyList(), 2).size)
    }

    @Test fun legacyExternalPreservesEpisodeRouteAndLiveSemantics() {
        val id = "a".repeat(64)
        val episode = """{"id":"$id","category":"series","title":"Same title","started":true,"season":2,"episode":3,"position":4000,"duration":30000,"lastPlayedAt":100,"url":"http://example.invalid/episode"}"""
        val row = LegacyHomeProgress.parse("[]", listOf(episode), 1).single()
        assertEquals(2, row.season)
        assertEquals(3, row.episode)
        assertEquals("http://example.invalid/episode", row.videoId)
        val live = episode.replace("series", "live").replace("\"season\":2,\"episode\":3,", "")
        val channel = LegacyHomeProgress.parse("[]", listOf(live), 1).single()
        assertTrue(channel.isLiveChannel)
        assertEquals(0L, channel.position)
        assertEquals(0L, channel.duration)
    }

    @Test fun offlineCheckpointRemainsQueuedUntilTransportReturns() = runBlocking {
        val item = progress("offline", 100)
        preferences.saveProgress(item)
        state.enqueueProgressUpsert(1, item.storageKey)
        remote.online = false
        assertTrue(service.flushPendingUpserts(1).isFailure)
        assertEquals(setOf(item.storageKey), state.pendingProgressUpserts(1))
        remote.online = true
        service.flushPendingUpserts(1).getOrThrow()
        assertTrue(state.pendingProgressUpserts(1).isEmpty())
        assertEquals(item.position, remote.rows(1).getValue(item.storageKey).position)
    }

    @Test fun explicitRemovalDeletesOldRemoteAliasAsWell() = runBlocking {
        val item = progress("removed", 100)
        remote.put(1, item)
        preferences.saveProgress(item)
        service.pushToRemote(1).getOrThrow()
        preferences.removeProgress(item.contentId)
        preferences.enqueuePendingRemoteDeletes(listOf(item.storageKey), 1)
        service.syncDeltaFromRemote(1).getOrThrow()
        assertTrue(remote.rows(1).isEmpty())
        assertTrue(preferences.getAllRawEntries(1).isEmpty())
    }

    @Test fun remainingTimeHandlesUnknownFinishedAndInvalidPosition() {
        val item = progress("duration", 100)
        assertEquals(0L, item.copy(duration = 0).remainingTime)
        assertEquals(0L, item.copy(position = 190000).remainingTime)
        assertEquals(180000L, item.copy(position = -5000).remainingTime)
        assertEquals(180000L, item.copy(position = -5000).resolveResumePosition(180000).let { item.duration - it })
        assertEquals(180000L, item.copy(position = 190000).resolveResumePosition(180000))
    }

    @Test fun initialResumeStartsAtTheSavedPositionBeforeTheFirstFrame() {
        val saved = progress("resume", 100).copy(position = 120000L, duration = 180000L)
        assertEquals(120000L, com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(saved, null, false))
        // A placeholder duration must not truncate an absolute position to one millisecond.
        assertEquals(120000L, com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(saved.copy(duration = 1L), null, false))
    }

    @Test fun initialResumeConvertsKnownPercentageAndDefersUnknownDuration() {
        val saved = progress("percentage", 100).copy(position = 0L, duration = 180000L, progressPercent = 50f)
        assertEquals(90000L, com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(saved, null, false))
        assertNull(com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(saved.copy(duration = 0L), null, false))
    }

    @Test fun initialResumeRetainsExplicitPendingSeekWhenNoSavedPositionIsAvailable() {
        assertEquals(45000L, com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(null, 45000L, false))
        assertNull(com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(null, 0L, false))
        assertNull(com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(null, -1L, false))
    }

    @Test fun initialResumeNeverSeeksALiveChannelOrAResetProgressRecord() {
        val saved = progress("channel", 100).copy(position = 120000L, duration = 180000L)
        assertNull(com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(saved, 45000L, true))
        assertNull(com.nuvio.tv.ui.screens.player.initialResumeSeekPosition(saved.copy(position = 0L, progressPercent = 0f), null, false))
    }

    @Test fun initialIptvMovieAndEpisodeResumeCanStartBeforeTheFirstFrame() {
        for (type in listOf("movie", "series", "SERIES"))
            assertTrue(com.nuvio.tv.ui.screens.player.initialResumeAutoplayOnReady("iptv:fixture", type, 120000L))
    }

    @Test fun initialIptvResumeAutoplayNeverAffectsLiveOrUnrelatedPlayback() {
        for (type in listOf("iptv", "freebox", "video", "episode", "", null))
            assertFalse(com.nuvio.tv.ui.screens.player.initialResumeAutoplayOnReady("iptv:fixture", type, 120000L))
        for (id in listOf("freebox:fixture", "home-external:fixture", "tt1234", "", null))
            assertFalse(com.nuvio.tv.ui.screens.player.initialResumeAutoplayOnReady(id, "series", 120000L))
    }

    @Test fun initialIptvResumeAutoplayNeverChangesPlaybackFromTheBeginning() {
        for (position in listOf(null, 0L, -1L))
            assertFalse(com.nuvio.tv.ui.screens.player.initialResumeAutoplayOnReady("iptv:fixture", "movie", position))
    }

    @Test fun initialResumeConfirmsItsPositionWithoutAcceptingAnIgnoredSeek() {
        assertTrue(com.nuvio.tv.ui.screens.player.initialResumeSeekReached(120000L, 120000L))
        assertTrue(com.nuvio.tv.ui.screens.player.initialResumeSeekReached(120000L, 120500L))
        assertFalse(com.nuvio.tv.ui.screens.player.initialResumeSeekReached(120000L, 0L))
        assertFalse(com.nuvio.tv.ui.screens.player.initialResumeSeekReached(120000L, 119999L))
        assertFalse(com.nuvio.tv.ui.screens.player.initialResumeSeekReached(120000L, 180000L))
        assertFalse(com.nuvio.tv.ui.screens.player.initialResumeSeekReached(null, 120000L))
    }

    @Test fun homonymousMoviesStaySeparateAfterTransportRoundTrip() = runBlocking {
        val movies = listOf(progress("film-one", 100).copy(contentType = "movie", name = "Same title"),
            progress("film-two", 200).copy(contentType = "movie", name = "Same title"))
        preferences.saveProgressBatch(movies)
        service.pushToRemote(1).getOrThrow()
        service.syncDeltaFromRemote(1).getOrThrow()
        assertEquals(setOf("film-one", "film-two"), preferences.getAllRawEntries().values.map { it.contentId }.toSet())
    }

    @Test fun historicalFileDismissalRemovesItsStaleNasProgressAfterReconnection() = runBlocking {
        val path = "smb://nas/share/video.mkv"
        val item = progress("freebox:$path", 100L).copy(contentType = "freebox")
        preferences.saveProgress(item)
        val home = mutableMapOf<String, Any>("jojotv_home_hidden_resume_keys" to
            setOf("com.jojotv.home.file-video|$path", "com.jojotv.home.file-video|Only a title|"))
        val removals = LegacyHomeProgress.removals(home, emptyList(), 1)
        assertEquals(setOf(path), removals.keys)
        preferences.removeHomeResumes(removals, 1)
        assertTrue(preferences.getAllRawEntries(1).isEmpty())
        assertTrue(preferences.pendingRemoteDeleteKeys(1).contains(item.storageKey))
        assertTrue(LegacyHomeProgress.removals(home, emptyList(), 2).isEmpty())
        home[HomeWatchProgressBridge.RESTORATION_PREFIX + "1_com.jojotv.home.file-video|$path"] = 201L
        assertTrue(LegacyHomeProgress.removals(home, emptyList(), 1).isEmpty())
    }

    @Test fun fileHeroDismissalSurvivesAnEmptyOrLateCanonicalSnapshot() {
        val path = "smb://nas/share/video.mkv"
        val identity = "com.jojotv.home.file-video|$path"
        val home = mapOf(HomeWatchProgressBridge.REMOVAL_PREFIX + "1_" + identity to 200L)
        assertEquals(200L, LegacyHomeProgress.removals(home, emptyList(), 1)[path])
        assertTrue(LegacyHomeProgress.removals(home, emptyList(), 2).isEmpty())
    }

    @Test fun fileHeroDismissalIsLiftedOnlyByANewerExplicitPlayback() {
        val path = "smb://nas/share/video.mkv"
        val identity = "com.jojotv.home.file-video|$path"
        val home = mutableMapOf<String, Any>(HomeWatchProgressBridge.REMOVAL_PREFIX + "1_" + identity to 200L)
        home[HomeWatchProgressBridge.RESTORATION_PREFIX + "1_" + identity] = 199L
        assertEquals(200L, LegacyHomeProgress.removals(home, emptyList(), 1)[path])
        home[HomeWatchProgressBridge.RESTORATION_PREFIX + "1_" + identity] = 201L
        assertTrue(LegacyHomeProgress.removals(home, emptyList(), 1).isEmpty())
    }

    @Test fun homeRemovalCommitsLocalDeleteAndRetryQueueTogether() = runBlocking {
        val removed = progress("home-external:" + "a".repeat(64), 100)
        val sameTitle = progress("home-external:" + "b".repeat(64), 100).copy(name = removed.name)
        preferences.saveProgressBatch(listOf(removed, sameTitle))
        preferences.removeHomeResumes(mapOf(removed.storageKey to 200L), 1)
        assertEquals(listOf(sameTitle), preferences.getAllRawEntries().values.toList())
        assertTrue(removed.storageKey in preferences.pendingRemoteDeleteKeys(1))
        assertTrue(preferences.pendingRemoteDeleteKeys(2).isEmpty())
    }

    @Test fun externalHomeRemovalByRecordIdRemovesLegacyEpisodeMirrorsButNotSameTitleRecords() = runBlocking {
        val removed = progress("home-external:" + "c".repeat(64), 100)
            .copy(contentType = "home-external", name = "Cape Fear")
        val episodeMirror = removed.copy(season = 1, episode = 1, lastWatched = 150)
        val sameTitle = removed.copy(contentId = "home-external:" + "d".repeat(64), name = removed.name)
        preferences.saveProgressBatch(listOf(removed, episodeMirror, sameTitle))

        val removals = LegacyHomeProgress.removals(
            mapOf(HomeWatchProgressBridge.REMOVAL_PREFIX + "1_" + removed.contentId to 200L),
            emptyList(), 1)
        preferences.removeHomeResumes(removals, 1)

        assertEquals(listOf(sameTitle), preferences.getAllRawEntries(1).values.toList())
        assertTrue(removed.storageKey in preferences.pendingRemoteDeleteKeys(1))
        assertTrue(episodeMirror.storageKey in preferences.pendingRemoteDeleteKeys(1))
    }

    @Test fun providerHeroMaskDeletesOrphanProgressAndSyncsToDeviceWithoutProviderRecord() = runBlocking {
        val id = "e".repeat(64)
        val removed = progress("home-external:$id", 100).copy(contentType = "home-external")
        val mirror = removed.copy(season = 1, episode = 1, lastWatched = 150)
        val otherProvider = removed.copy(contentId = "home-external:" + "f".repeat(64))
        preferences.saveProgressBatch(listOf(removed, mirror, otherProvider))
        listOf(removed, mirror, otherProvider).forEach { remote.put(1, it) }
        service.pushToRemote(1).getOrThrow()
        val marker = "external-series-resume|vod-provider|provider-a|series|show-7|0|0"
        val record = """{"id":"$id","category":"series","stalkerSource":"provider-a","stalkerSeriesId":"show-7","started":true,"lastPlayedAt":150,"updatedAt":200}"""
        val otherRecord = """{"id":"${"f".repeat(64)}","category":"series","stalkerSource":"provider-b","stalkerSeriesId":"show-7","started":true,"lastPlayedAt":100,"updatedAt":200}"""
        val removals = LegacyHomeProgress.removals(
            mapOf("jojotv_home_hidden_resume_keys" to setOf(marker)), listOf(record, otherRecord), 1)
        assertEquals(200L, removals[removed.contentId])
        assertFalse(otherProvider.contentId in removals)
        preferences.removeHomeResumes(removals, 1)
        service.flushPendingRemoteDeletes(1).getOrThrow()
        assertEquals(listOf(otherProvider), preferences.getAllRawEntries(1).values.toList())
        assertEquals(setOf(otherProvider.contentId), remote.rows(1).values.map { it.contentId }.toSet())
        // The receiving device needs only NAS deletion state, not provider metadata.
        preferences.saveProgressBatch(listOf(removed, mirror), 1)
        service.syncDeltaFromRemote(1).getOrThrow()
        assertEquals(listOf(otherProvider), preferences.getAllRawEntries(1).values.toList())
    }

    @Test fun timestampedProviderHeroMaskPreservesLaterPlaybackAndOtherProfile() = runBlocking {
        val id = "e".repeat(64)
        val old = progress("home-external:$id", 100)
        val replay = old.copy(season = 1, episode = 2, lastWatched = 300)
        preferences.saveProgressBatch(listOf(old, replay), 1)
        preferences.saveProgress(old, 2)
        val marker = "external-series-resume|vod-provider|provider-a|series|show-7|0|0"
        val record = """{"id":"$id","category":"series","stalkerSource":"provider-a","stalkerSeriesId":"show-7","started":true,"lastPlayedAt":300,"updatedAt":350}"""
        val home = mapOf(HomeWatchProgressBridge.REMOVAL_PREFIX + "1_" + marker to 200L)
        val removals = LegacyHomeProgress.removals(home, listOf(record), 1)
        assertEquals(200L, removals[old.contentId])
        preferences.removeHomeResumes(removals, 1)
        assertEquals(listOf(replay), preferences.getAllRawEntries(1).values.toList())
        assertTrue(LegacyHomeProgress.removals(home, listOf(record), 2).isEmpty())
        assertEquals(old, preferences.getAllRawEntries(2).values.single())
    }

    @Test fun legacyHeroMasksDoNotGuessProviderIdentityFromTitle() {
        val id = "e".repeat(64)
        val home = mapOf("jojotv_home_hidden_resume_keys" to setOf(
            "external-series-resume|vod-provider|provider-a|series|show-7|0|0"))
        val withoutProvider = """{"id":"$id","category":"series","title":"Same title","started":true,"lastPlayedAt":100,"updatedAt":200}"""
        assertTrue(LegacyHomeProgress.removals(home, listOf(withoutProvider), 1).isEmpty())
        val providerRecord = """{"id":"$id","category":"series","stalkerSource":"provider-a","stalkerId":"show-7","started":true,"updatedAt":200,"progressProfileId":2}"""
        assertTrue(LegacyHomeProgress.removals(home, listOf(providerRecord), 2).isEmpty())
    }

    @Test fun homeRemovalSurvivesOfflineTransportAndRemovesNasAliases() = runBlocking {
        val item = progress("removed-home", 100)
        preferences.saveProgress(item)
        remote.put(1, item)
        service.pushToRemote(1).getOrThrow()
        preferences.removeHomeResumes(mapOf(item.storageKey to 200L), 1)
        remote.online = false
        assertTrue(service.flushPendingRemoteDeletes(1).isFailure)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isNotEmpty())
        remote.online = true
        service.syncDeltaFromRemote(1).getOrThrow()
        assertTrue(remote.rows(1).isEmpty())
        assertTrue(preferences.getAllRawEntries().isEmpty())
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun olderHomeRemovalPreservesNewPlaybackAndOtherProfile() = runBlocking {
        val item = progress("replayed", 300)
        preferences.saveProgress(item, 1)
        preferences.saveProgress(item.copy(lastWatched = 100), 2)
        preferences.removeHomeResumes(mapOf(item.storageKey to 200L), 1)
        assertEquals(item, preferences.getAllRawEntries(1).values.single())
        assertEquals(100L, preferences.getAllRawEntries(2).values.single().lastWatched)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun legacyDeletionUsesExplicitIdentityAndRetainsNeverPlayedFavorites() {
        val id = "a".repeat(64)
        val deleted = """{"id":"$id","deleted":true,"updatedAt":200}"""
        val stopped = """{"id":"${"b".repeat(64)}","started":false,"lastPlayedAt":100,"updatedAt":250,"position":9000}"""
        val favorite = """{"id":"${"c".repeat(64)}","started":false,"favorite":true,"updatedAt":300}"""
        val result = LegacyHomeProgress.removals(emptyMap<String, Any>(), listOf(deleted, stopped, favorite), 1)
        assertEquals(2, result.size)
        assertEquals(200L, result["home-external:$id"])
        assertTrue(LegacyHomeProgress.removals(emptyMap<String, Any>(), listOf(deleted), 2).isEmpty())
    }

    @Test fun fileAndCanonicalHomeRemovalMarkersRespectProfiles() {
        val item = progress("file", 100)
        val settings = mapOf("video_resume_invalidated_file:///video.mp4" to 200L,
            "video_resume_invalidation_profile_file:///video.mp4" to 2,
            HomeWatchProgressBridge.REMOVAL_PREFIX + "2_" + item.storageKey to 250L)
        assertTrue(LegacyHomeProgress.removals(settings, emptyList(), 1).isEmpty())
        val removals = LegacyHomeProgress.removals(settings, emptyList(), 2)
        assertEquals(200L, removals["/video.mp4"])
        assertEquals(250L, removals[item.storageKey])
    }

    @Test fun directRetryAfterAnotherFlushAcknowledgedTheIntentIsAnIdempotentSuccess() = runBlocking {
        remote.put(1, progress("already-acknowledged", 100))
        preferences.enqueuePendingRemoteDeletes(listOf("already-acknowledged"), 1, 200)
        service.flushPendingRemoteDeletes(1).getOrThrow()
        service.deleteFromRemote(listOf("already-acknowledged"), 1).getOrThrow()
        assertEquals(1, remote.deletes.size)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun partiallyAcknowledgedBatchOnlySendsTheIntentsStillPending() = runBlocking {
        remote.put(1, progress("first-delete", 100))
        remote.put(1, progress("second-delete", 100))
        val keys = listOf("first-delete", "second-delete")
        preferences.enqueuePendingRemoteDeletes(keys, 1, 200)
        service.deleteFromRemote(listOf("first-delete"), 1).getOrThrow()
        service.deleteFromRemote(keys, 1).getOrThrow()
        assertEquals(listOf(1 to setOf("first-delete"), 1 to setOf("second-delete")), remote.deletes)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun oldHomeDeletionCannotEraseANewerStoppedCheckpointOnAnotherDevice() = runBlocking {
        val old = progress("freebox:smb://nas/share/video.mkv", 100).copy(contentType = "freebox")
        val stopped = old.copy(position = 5000, lastWatched = 300)
        preferences.saveProgress(old, 1)
        preferences.removeHomeResumes(mapOf(old.storageKey to 200L), 1)
        remote.put(1, stopped)
        service.syncDeltaFromRemote(1).getOrThrow()
        assertEquals(stopped.lastWatched, remote.rows(1).values.single().lastWatched)
        assertEquals(stopped.position, preferences.getAllRawEntries(1).values.single().position)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
        assertTrue(remote.datedDeletes.single().second.values.all { it == 200L })
    }

    @Test fun atomicRemoteGuardProtectsPlaybackThatArrivesAfterAliasLookup() = runBlocking {
        val old = progress("race", 100)
        val replay = old.copy(position = 2000, lastWatched = 300)
        remote.put(1, old)
        preferences.enqueuePendingRemoteDeletes(listOf(old.storageKey), 1, 200)
        remote.beforeDelete = { remote.put(1, replay) }
        service.flushPendingRemoteDeletes(1).getOrThrow()
        assertEquals(replay.lastWatched, remote.rows(1).values.single().lastWatched)
        assertEquals(replay.position, remote.rows(1).values.single().position)
    }

    @Test fun freshLocalPlaybackSurvivesAnOlderQueuedDeletion() = runBlocking {
        val replay = progress("local-replay", 300)
        preferences.enqueuePendingRemoteDeletes(listOf(replay.storageKey), 1, 200)
        preferences.saveProgress(replay, 1)
        service.pushToRemote(1).getOrThrow()
        service.syncDeltaFromRemote(1).getOrThrow()
        assertEquals(replay.lastWatched, remote.rows(1).values.single().lastWatched)
        assertEquals(replay.lastWatched, preferences.getAllRawEntries(1).values.single().lastWatched)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun undatedHistoricMaskNeverPromotesANewlyObservedRowIntoADatedRemoteDeletion() = runBlocking {
        val old = progress("freebox:smb://nas/share/video.mkv", 100).copy(contentType = "freebox")
        preferences.saveProgress(old, 1)
        preferences.removeHomeResumes(mapOf(old.contentId to Long.MAX_VALUE), 1)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isNotEmpty())
        assertTrue(preferences.pendingRemoteDeleteIntents(1).isEmpty())
        remote.put(1, old.copy(lastWatched = 300))
        service.syncDeltaFromRemote(1).getOrThrow()
        preferences.removeHomeResumes(mapOf(old.contentId to Long.MAX_VALUE), 1)
        service.syncDeltaFromRemote(1).getOrThrow()
        assertTrue(remote.deletes.isEmpty())
        assertTrue(preferences.pendingRemoteDeleteIntents(1).isEmpty())
        assertEquals(300L, remote.rows(1).values.single().lastWatched)
    }

    @Test fun failedDeleteKeepsItsOriginalTimestampAndVersionForRetry() = runBlocking {
        remote.put(1, progress("offline-delete", 100))
        preferences.enqueuePendingRemoteDeletes(listOf("offline-delete"), 1, 200)
        val intent = preferences.pendingRemoteDeleteIntents(1)
        remote.online = false
        assertTrue(service.flushPendingRemoteDeletes(1).isFailure)
        assertEquals(intent, preferences.pendingRemoteDeleteIntents(1))
        remote.online = true
        service.flushPendingRemoteDeletes(1).getOrThrow()
        assertEquals(200L, remote.tombstones.getValue("offline-delete"))
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun deletionIntentSurvivesDataStoreReopeningWithoutBeingRedated() = runBlocking {
        preferences.enqueuePendingRemoteDeletes(listOf("persisted-delete"), 1, 200)
        val before = preferences.pendingRemoteDeleteIntents(1)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        setUp()
        assertEquals(before, preferences.pendingRemoteDeleteIntents(1))
        assertTrue("persisted-delete" in preferences.pendingRemoteDeleteKeys(1))
    }

    @Test fun staleAcknowledgementCannotDropANewDeleteEvenAtTheSameMillisecond() = runBlocking {
        remote.put(1, progress("same-key", 100))
        preferences.enqueuePendingRemoteDeletes(listOf("same-key"), 1, 200)
        val old = preferences.pendingRemoteDeleteIntents(1).getValue("same-key")
        remote.beforeDelete = { preferences.enqueuePendingRemoteDeletes(listOf("same-key"), 1, 200) }
        service.flushPendingRemoteDeletes(1).getOrThrow()
        val newer = preferences.pendingRemoteDeleteIntents(1).getValue("same-key")
        assertEquals(old.deletedAt, newer.deletedAt)
        assertNotEquals(old.version, newer.version)
        remote.beforeDelete = {}
        service.flushPendingRemoteDeletes(1).getOrThrow()
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
    }

    @Test fun anOlderEnqueueOrUnversionedAckCannotReplaceOrClearTheNewIntent() = runBlocking {
        preferences.enqueuePendingRemoteDeletes(listOf("new-delete"), 1, 300)
        val before = preferences.pendingRemoteDeleteIntents(1)
        preferences.enqueuePendingRemoteDeletes(listOf("new-delete"), 1, 200)
        preferences.acknowledgeRemoteDeletes(listOf("new-delete"), 1)
        assertEquals(before, preferences.pendingRemoteDeleteIntents(1))
        assertTrue("new-delete" in preferences.pendingRemoteDeleteKeys(1))
    }

    @Test fun legacyQueueWithoutDatesIsRetainedWithoutDeletingRemoteOrBlockingReads() = runBlocking {
        val item = progress("undated-delete", 300)
        remote.put(1, item)
        preferences.getAllRawEntries(1)
        stores.getValue(1).edit { it[stringSetPreferencesKey("pending_remote_delete_keys")] = setOf(item.contentId) }
        service.syncDeltaFromRemote(1).getOrThrow()
        assertTrue(remote.deletes.isEmpty())
        assertEquals(item.lastWatched, preferences.getAllRawEntries(1).values.single().lastWatched)
        assertTrue(item.contentId in preferences.pendingRemoteDeleteKeys(1))
        assertTrue(preferences.pendingRemoteDeleteIntents(1).isEmpty())
    }

    @Test fun corruptIntentMetadataCannotBecomeAnUnguardedDelete() = runBlocking {
        preferences.enqueuePendingRemoteDeletes(listOf("corrupt-delete"), 1, 200)
        stores.getValue(1).edit { it[stringPreferencesKey("pending_remote_delete_intents_v1")] = "{broken" }
        remote.put(1, progress("corrupt-delete", 300))
        service.flushPendingRemoteDeletes(1).getOrThrow()
        assertTrue(remote.deletes.isEmpty())
        assertTrue("corrupt-delete" in preferences.pendingRemoteDeleteKeys(1))
    }

    @Test fun unsupportedCompanionKeepsTheIntentWithoutAnUnguardedFallback() = runBlocking {
        preferences.enqueuePendingRemoteDeletes(listOf("unsupported"), 1, 200)
        val before = preferences.pendingRemoteDeleteIntents(1)
        remote.supportsConditionalDeletes = false
        assertTrue(service.flushPendingRemoteDeletes(1).isFailure)
        assertTrue(remote.deletes.isEmpty())
        assertEquals(before, preferences.pendingRemoteDeleteIntents(1))
    }

    @Test fun deleteAndAcknowledgementKeepTheirCapturedProfileAfterASwitch() = runBlocking {
        remote.put(1, progress("profile-delete", 100))
        remote.put(2, progress("profile-delete", 100))
        preferences.enqueuePendingRemoteDeletes(listOf("profile-delete"), 1, 200)
        preferences.enqueuePendingRemoteDeletes(listOf("profile-delete"), 2, 300)
        remote.beforeDelete = { profile.value = 2 }
        service.flushPendingRemoteDeletes(1).getOrThrow()
        assertTrue(remote.rows(1).isEmpty())
        assertEquals(1, remote.rows(2).size)
        assertTrue(preferences.pendingRemoteDeleteKeys(1).isEmpty())
        assertTrue("profile-delete" in preferences.pendingRemoteDeleteKeys(2))
    }

    private fun progress(id: String, watched: Long) = WatchProgress(
        id, "video", id, null, null, null, id, null, null, null,
        position = 12000, duration = 180000, lastWatched = watched
    )

    private class Remote : WatchProgressRemote {
        var online = true
        var pushes = 0
        var beforePush: suspend () -> Unit = {}
        var beforeDelete: suspend () -> Unit = {}
        var supportsConditionalDeletes = true
        private val profileTombstones = mutableMapOf<Int, MutableMap<String, Long>>()
        private fun tombstones(profile: Int) = profileTombstones.getOrPut(profile) { linkedMapOf() }
        val tombstones get() = tombstones(1)
        val deletes = mutableListOf<Pair<Int, Set<String>>>()
        val datedDeletes = mutableListOf<Pair<Int, Map<String, Long>>>()
        private val profiles = mutableMapOf<Int, MutableMap<String, NasWatchProgress>>()
        fun rows(profile: Int) = profiles.getOrPut(profile) { linkedMapOf() }
        fun put(profile: Int, progress: WatchProgress) {
            rows(profile)[progress.contentId] = NasWatchProgress(
                progress.contentId, progress.contentId, progress.contentType, progress.name,
                null, null, null, progress.videoId, progress.season, progress.episode, null,
                progress.position, progress.duration, progress.lastWatched, progress.addonBaseUrl,
                null, progress.source, null, null, null, null, null
            )
        }
        override suspend fun hasSession() = online
        override suspend fun pullWatchProgress(profileId: Int): NasWatchProgressSnapshot {
            check(online)
            return NasWatchProgressSnapshot(rows(profileId).values.toList(), tombstones(profileId).toMap())
        }
        override suspend fun pushWatchProgress(profileId: Int, entries: List<NasWatchProgress>) {
            check(online)
            beforePush()
            pushes++
            entries.forEach { item ->
                if (item.lastWatched > (tombstones(profileId)[item.key] ?: 0L) &&
                    item.lastWatched >= (rows(profileId)[item.key]?.lastWatched ?: 0L)) {
                    rows(profileId)[item.key] = item
                    tombstones(profileId).remove(item.key)
                }
            }
        }
        override suspend fun deleteWatchProgressConditionally(profileId: Int, deletions: Map<String, Long>) {
            check(online)
            check(supportsConditionalDeletes)
            beforeDelete()
            deletes += profileId to deletions.keys.toSet()
            datedDeletes += profileId to deletions.toMap()
            deletions.forEach { (key, deletedAt) ->
                if ((rows(profileId)[key]?.lastWatched ?: 0L) <= deletedAt) {
                    rows(profileId).remove(key)
                    tombstones(profileId)[key] = maxOf(tombstones(profileId)[key] ?: 0L, deletedAt)
                }
            }
        }
    }

    /** Only Android preferences are substituted; progress uses actual file-backed DataStore. */
    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> {
                    val updates = mutableMapOf<String, Any?>()
                    var clear = false
                    lateinit var editor: SharedPreferences.Editor
                    editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, edit, arguments ->
                        when (edit.name) {
                            "commit", "apply" -> {
                                if (clear) values.clear()
                                updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                                if (edit.name == "commit") true else null
                            }
                            "clear" -> { clear = true; editor }
                            "remove" -> { updates[arguments[0] as String] = null; editor }
                            else -> { updates[arguments[0] as String] = arguments[1]; editor }
                        }
                    } as SharedPreferences.Editor
                    editor
                }
                "contains" -> values.containsKey(args[0])
                "getAll" -> values.toMap()
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                else -> values[args[0]] ?: args[1]
            }
        } as SharedPreferences
    }
}
