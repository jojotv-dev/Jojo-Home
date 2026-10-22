package com.jojotv.home

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.NasIptvFavoritesApi
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.data.local.NasSyncStateStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.data.freebox.freeboxPathFromContentId

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HomeWatchProgressEntryPoint {
    fun profileManager(): ProfileManager
    fun watchProgressPreferences(): WatchProgressPreferences
    fun nasIptvFavoritesApi(): NasIptvFavoritesApi
    fun watchProgressSyncService(): WatchProgressSyncService
    fun nasSyncStateStore(): NasSyncStateStore
    fun continueWatchingEnrichmentCache(): ContinueWatchingEnrichmentCache
}

/** Observes the active profile and polls the NAS change journal while Home is foregrounded. */
class HomeWatchProgressBridge private constructor(
    private val context: Context,
    private val profileManager: ProfileManager,
    private val preferences: WatchProgressPreferences,
    private val nasSyncApi: NasIptvFavoritesApi,
    private val syncService: WatchProgressSyncService,
    private val syncStateStore: NasSyncStateStore
) {
    fun interface SnapshotListener {
        fun onSnapshot(profileId: Int, progress: List<WatchProgress>)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bridgeJob: Job? = null
    private var observedProfileId = profileManager.activeProfileId.value

    fun activeProfileId(): Int = profileManager.activeProfileId.value

    fun start(listener: SnapshotListener) {
        start(listener, false)
    }

    internal fun startAllRawProgress(listener: SnapshotListener) {
        start(listener, true)
    }

    private fun start(listener: SnapshotListener, includeAllRawProgress: Boolean) {
        if (bridgeJob?.isActive == true) return
        bridgeJob = scope.launch {
            profileManager.activeProfileReady.first { it }
            launch(Dispatchers.IO) {
                // Account ownership is needed by the Home filter before any IPTV folder is opened.
                runCatching { com.jojotv.home.iptv.IptvStore(context).load() }.onFailure {
                    Log.w(TAG, "Home IPTV account selection unavailable: ${it.javaClass.simpleName}")
                }
            }
            launch {
                profileManager.activeProfileId.collectLatest { profileId ->
                    coroutineScope {
                        // A foreground restart must keep the last real snapshot
                        // until storage emits. Only a profile change clears it.
                        if (observedProfileId != profileId) {
                            observedProfileId = profileId
                            listener.onSnapshot(profileId, emptyList())
                        }
                        if (!includeAllRawProgress) launch(Dispatchers.IO) {
                            preferences.sourceMode(profileId).collectLatest { cloud ->
                                if (!cloud) merge(legacyChanges(), preferences.observeAllProgress(profileId).map { Unit }).conflate().collect {
                                    delay(150L)
                                    importLegacyProgress(profileId)
                                }
                            }
                        }
                        val progressFlow = if (includeAllRawProgress) preferences.observeHomeProgressSnapshot(profileId)
                            .map { (progress, hidden) -> progress.filterNot { it.storageKey in hidden } }
                            else preferences.observeContinueWatching(profileId)
                        combine(progressFlow,
                            com.jojotv.home.iptv.IptvStore.accountSelection) { progress, account ->
                            account to progress.filter {
                                !it.contentId.startsWith("iptv:") || account?.owns(it.contentId) == true
                            }
                        }.flowOn(Dispatchers.Default).distinctUntilChanged().collect { (account, progress) ->
                            // A queued result from the previous account must not reach Home after a switch.
                            if (account == com.jojotv.home.iptv.IptvStore.accountSelection.value &&
                                profileId == profileManager.activeProfileId.value) listener.onSnapshot(profileId, progress)
                        }
                    }
                }
            }
            launch {
                combine(nasSyncApi.sessionFlow, profileManager.activeProfileId) { session, profileId ->
                    session to profileId
                }.distinctUntilChanged().collectLatest { (session, profileId) ->
                    preferences.sourceMode(profileId).collectLatest { cloud ->
                        if (cloud) {
                            preferences.refreshCloud()
                            // The application runtime owns the single foreground cloud poller.
                            kotlinx.coroutines.awaitCancellation()
                        } else if (!session.token.isNullOrBlank()) pollProfile(profileId)
                    }
                }
            }
        }
    }

    fun stop() {
        bridgeJob?.cancel()
        bridgeJob = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun legacyChanges() = callbackFlow {
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val external = context.getSharedPreferences("external_playback_records", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (prefs === external || key == null || key == "file_video_resumes" ||
                key == "jojotv_home_hidden_resume_keys" ||
                key.startsWith(ExternalPlaybackStore.PREFIX) || key.startsWith(REMOVAL_PREFIX) ||
                key.startsWith(RESTORATION_PREFIX) ||
                key.startsWith("video_resume_invalidated_")) trySend(Unit)
        }
        home.registerOnSharedPreferenceChangeListener(listener)
        external.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            home.unregisterOnSharedPreferenceChangeListener(listener)
            external.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate()

    private suspend fun importLegacyProgress(profileId: Int) = withContext(com.jojotv.home.progresspilot.NasProgressMutation) {
        if (preferences.isCloud(profileId)) return@withContext
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val external = context.getSharedPreferences("external_playback_records", Context.MODE_PRIVATE)
        val records = home.all.filterKeys { it.startsWith(ExternalPlaybackStore.PREFIX) }.values + external.all.values
        val jsonRecords = records.filterIsInstance<String>()
        val removals = LegacyHomeProgress.removals(home.all, jsonRecords, profileId)
        preferences.removeHomeResumes(removals, profileId)
        val candidates = LegacyHomeProgress.parse(home.getString("file_video_resumes", "[]").orEmpty(),
            jsonRecords, profileId)
        val local = preferences.getAllRawEntries(profileId).values
        for (candidate in candidates) {
            if (candidate.lastWatched <= preferences.homeRemovalTimestamp(candidate, removals)) continue
            if (candidate.lastWatched <= syncStateStore.legacyProgressImportedAt(profileId, candidate.storageKey)) continue
            val existing = local.filter { it.storageKey == candidate.storageKey ||
                (candidate.contentType == "freebox" && it.contentType == "freebox" &&
                    freeboxPathFromContentId(it.contentId) == freeboxPathFromContentId(candidate.contentId))
            }.maxByOrNull { it.lastWatched }
            if (existing == null || candidate.lastWatched > existing.lastWatched) {
                val imported = candidate.copy(contentId = existing?.contentId ?: candidate.contentId)
                preferences.saveProgress(imported, profileId)
                syncStateStore.enqueueProgressUpsert(profileId, imported.storageKey)
            } else if (existing.contentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX) &&
                !ExternalPlaybackStore.supportedUrl(existing.videoId) && ExternalPlaybackStore.supportedUrl(candidate.videoId)) {
                // Fill a missing route without claiming that playback just advanced.
                preferences.saveProgress(existing.copy(videoId = candidate.videoId), profileId)
                syncStateStore.enqueueProgressUpsert(profileId, existing.storageKey)
            }
            // Remember the source timestamp, even if a newer canonical row won.
            // An unchanged legacy row must not resurrect a later explicit removal.
            syncStateStore.markLegacyProgressImported(profileId, candidate.storageKey, candidate.lastWatched)
        }
    }

    private suspend fun pollProfile(profileId: Int) = withContext(Dispatchers.IO) {
        var initialSnapshotLoaded = false
        var retryDelayMs = 0L
        var failureDelayMs = 0L
        while (currentCoroutineContext().isActive) {
            if (preferences.isCloud(profileId)) return@withContext
            if (retryDelayMs > 0L) delay(retryDelayMs)
            retryDelayMs = try {
                if (!nasSyncApi.hasSession()) {
                    RETRY_INTERVAL_MS
                } else {
                    syncService.migrateExistingLocalSnapshotIfNeeded(profileId).getOrElse { throw it }
                    syncService.flushPendingUpserts(profileId).getOrElse { throw it }
                    syncService.flushPendingRemoteDeletes(profileId).getOrElse { throw it }

                    val knownRevision = syncStateStore.revision(profileId)
                    val changes = nasSyncApi.pullChanges(profileId, knownRevision)
                    if (preferences.isCloud(profileId)) return@withContext
                    val localDeviceId = syncStateStore.deviceId
                    val hasForeignProgressChange = changes.changes.any { change ->
                        change.domain == WATCH_PROGRESS_DOMAIN &&
                            change.deviceId != localDeviceId &&
                            (change.profileId == 0 || change.profileId == profileId)
                    }
                    // A persisted journal cursor does not prove that the local
                    // resume snapshot is complete. Reconcile once per Home
                    // foreground session, then poll only relevant changes.
                    val needsSnapshot = !initialSnapshotLoaded || (knownRevision > 0L &&
                        SystemContinueWatching.shouldPullRemoteProgressSnapshot(
                            knownRevision,
                            changes.oldestAvailableRevision,
                            hasForeignProgressChange
                        )
                    )

                    if (needsSnapshot) {
                        val result = syncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
                        initialSnapshotLoaded = true
                        if (result.preservedLocalItems) {
                            syncService.pushToRemote(profileId).getOrElse { throw it }
                        }
                    }
                    syncStateStore.setRevision(profileId, changes.revision)
                    syncStateStore.recordSuccess(profileId, "changes", changes.revision)
                    failureDelayMs = 0L
                    POLL_INTERVAL_MS
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                syncStateStore.recordFailure(profileId, "changes", error)
                Log.w(TAG, "Home watch-progress sync failed for profile $profileId", error)
                failureDelayMs = (if (failureDelayMs == 0L) RETRY_INTERVAL_MS else failureDelayMs * 2)
                    .coerceAtMost(MAX_RETRY_INTERVAL_MS)
                failureDelayMs
            }
        }
    }

    companion object {
        private const val TAG = "HomeWatchProgressSync"
        private const val WATCH_PROGRESS_DOMAIN = "watch_progress"
        const val REMOVAL_PREFIX = "home_resume_removed_p"
        const val RESTORATION_PREFIX = "home_resume_restored_p"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val RETRY_INTERVAL_MS = 15_000L
        private const val MAX_RETRY_INTERVAL_MS = 120_000L

        @JvmStatic
        fun create(context: Context): HomeWatchProgressBridge {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                HomeWatchProgressEntryPoint::class.java
            )
            return HomeWatchProgressBridge(
                context.applicationContext,
                entryPoint.profileManager(),
                entryPoint.watchProgressPreferences(),
                entryPoint.nasIptvFavoritesApi(),
                entryPoint.watchProgressSyncService(),
                entryPoint.nasSyncStateStore()
            )
        }

        @JvmStatic fun currentProfileId(context: Context): Int = EntryPointAccessors.fromApplication(
            context.applicationContext, HomeWatchProgressEntryPoint::class.java).profileManager().activeProfileId.value

        @JvmStatic fun isCloud(context: Context): Boolean = EntryPointAccessors.fromApplication(
            context.applicationContext, HomeWatchProgressEntryPoint::class.java).watchProgressPreferences().isCloud()

        @JvmStatic fun hideCloudContent(context: Context, contentId: String, hidden: Boolean) {
            runBlocking(Dispatchers.IO) { EntryPointAccessors.fromApplication(context.applicationContext,
                HomeWatchProgressEntryPoint::class.java).watchProgressPreferences().setCloudResumeHidden(contentId, hidden) }
        }

        @JvmStatic fun resetCloudFileProgress(context: Context, path: String) {
            runBlocking(Dispatchers.IO) { EntryPointAccessors.fromApplication(context.applicationContext,
                HomeWatchProgressEntryPoint::class.java).watchProgressPreferences().resetCloudFileProgress(path) }
        }

        /** Worker-only catalog registration. No historical position is imported or transmitted. */
        @JvmStatic fun registerCloudContent(context: Context, template: WatchProgress) {
            if (!isCloud(context)) return
            runBlocking(Dispatchers.IO) { EntryPointAccessors.fromApplication(context.applicationContext,
                HomeWatchProgressEntryPoint::class.java).watchProgressPreferences().registerCloudContent(template) }
        }

        @JvmStatic fun cloudFilePosition(context: Context, path: String, size: Long, modified: Long, title: String): Long {
            val id = com.nuvio.tv.data.freebox.freeboxContentIdForFile(path, size, modified)
            val template = WatchProgress(id, "freebox", title, null, null, null, id, null, null, null, 0, 0, 1)
            registerCloudContent(context, template)
            return runBlocking(Dispatchers.IO) {
                val prefs = EntryPointAccessors.fromApplication(context.applicationContext,
                    HomeWatchProgressEntryPoint::class.java).watchProgressPreferences()
                prefs.getAllRawEntries().values.firstOrNull { it.storageKey == template.storageKey }?.position ?: 0
            }
        }

        /** The notification worker owns these observations, including while the video app is foregrounded. */
        @JvmStatic fun observeTizenProgress(context: Context, snapshot: String, observedAt: Long) {
            val entry = EntryPointAccessors.fromApplication(context.applicationContext, HomeWatchProgressEntryPoint::class.java)
            if (!entry.profileManager().activeProfileReady.value) return
            val prefs = entry.watchProgressPreferences()
            val profile = entry.profileManager().activeProfileId.value
            if (!prefs.isCloud(profile)) return
            runBlocking(Dispatchers.IO) {
                val current = prefs.getAllRawEntries(profile)
                var changed = false
                JsonParser.parseString(snapshot).asJsonArray.forEach { raw ->
                    val item = raw.asJsonObject
                    val source = item["package"]?.asString.orEmpty()
                    if (!NotificationResumeRules.isTizenTubePackage(source) ||
                        item["updatedAt"]?.asLong != observedAt || item["playbackCaptured"]?.asBoolean != true) return@forEach
                    val id = NotificationResumeRules.youtubeVideoId(item["youtubeVideoId"]?.asString)
                        ?: NotificationResumeRules.youtubeVideoId(item["intent"]?.asString) ?: return@forEach
                    val title = item["title"]?.asString.orEmpty().trim()
                    if (title.isEmpty()) return@forEach
                    val progress = WatchProgress("tizentube:$id", "video", title,
                        "https://i.ytimg.com/vi/$id/hqdefault.jpg", null, null, id, null, null, null,
                        (item["position"]?.asLong ?: 0L).coerceAtLeast(0),
                        (item["duration"]?.asLong ?: 0L).coerceAtLeast(0), observedAt,
                        description = item["description"]?.asString)
                    val previous = current[progress.storageKey]
                    if (previous != null && previous.copy(lastWatched = observedAt) == progress) return@forEach
                    if (!prefs.isCloud(profile) || entry.profileManager().activeProfileId.value != profile) return@forEach
                    withContext(com.jojotv.home.progresspilot.PilotPlaybackCheckpoint(observedAt, android.os.SystemClock.elapsedRealtime())) {
                        prefs.saveProgress(progress, profile)
                    }
                    if (item["playbackActive"]?.asBoolean == true &&
                        (previous == null || previous.position != progress.position)) {
                        val key = SystemContinueWatching.sharedResumeKey(source, title, progress.description,
                            "https://www.youtube.com/watch?v=$id", null, null)
                        com.jojotv.home.progresspilot.AccountSettingsRouting.recordResumeDismissal(context, profile,
                            "external_app_resume_$key", false)
                    }
                    changed = true
                }
                if (changed && prefs.isCloud(profile)) prefs.refreshCloud()
            }
        }

        /** Call from a worker; watch progress and user artwork overrides are separate stores. */
        @JvmStatic fun clearContinueWatchingCache(context: Context) {
            runBlocking(Dispatchers.IO) {
                EntryPointAccessors.fromApplication(context.applicationContext, HomeWatchProgressEntryPoint::class.java)
                    .continueWatchingEnrichmentCache().clearAll()
            }
        }
    }
}

/** Old unscoped Home records belong to primary profile 1; titles never establish identity. */
internal object LegacyHomeProgress {
    /** Only explicit removal state is migrated; missing records and titles are never deletion evidence. */
    fun removals(home: Map<String, *>, externalJson: List<String>, profileId: Int): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        fun put(key: String, at: Long) { if (key.isNotBlank() && at > 0L) result[key] = maxOf(result[key] ?: 0L, at) }
        val prefix = HomeWatchProgressBridge.REMOVAL_PREFIX + profileId + "_"
        home.forEach { (key, value) ->
            if (key.startsWith(prefix)) {
                val identity = key.removePrefix(prefix)
                val at = (value as? Number)?.toLong() ?: 0L
                val restoredAt = (home[HomeWatchProgressBridge.RESTORATION_PREFIX + profileId + "_" + identity] as? Number)?.toLong() ?: 0L
                if (at >= restoredAt) {
                    put(identity, at)
                    val fileMask = "com.jojotv.home.file-video|"
                    if (identity.startsWith(fileMask)) {
                        val path = identity.removePrefix(fileMask)
                        if (path.startsWith("smb://") || path.startsWith("file://") || path.startsWith("/"))
                            put(path.removePrefix("file://"), at)
                    }
                }
            }
            if (key.startsWith("video_resume_invalidated_")) {
                val path = key.removePrefix("video_resume_invalidated_")
                val owner = (home["video_resume_invalidation_profile_$path"] as? Number)?.toInt() ?: 1
                if (owner == profileId) put(path.removePrefix("file://"), (value as? Number)?.toLong() ?: 0L)
            }
        }
        fun JsonObject.text(key: String) = runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull().orEmpty()
        fun JsonObject.number(key: String) = text(key).toLongOrNull() ?: 0L
        // Historic Hero masks were unscoped and belong to the primary profile.
        // Resolve them only through an exact record/provider identity, never a title.
        val hidden = if (profileId == 1) (home["jojotv_home_hidden_resume_keys"] as? Set<*>)
            ?.filterIsInstance<String>()?.toSet().orEmpty() else emptySet()
        // Older Home versions persisted file dismissals only as exact path
        // masks. Honor that evidence even if their canonical progress arrives
        // for the first time after reconnecting to the NAS.
        val fileMask = "com.jojotv.home.file-video|"
        for (identity in hidden) {
            if (!identity.startsWith(fileMask)) continue
            val path = identity.removePrefix(fileMask)
            if (!path.startsWith("smb://") && !path.startsWith("file://") && !path.startsWith("/")) continue
            val removedAt = (home[prefix + identity] as? Number)?.toLong() ?: 0L
            val restoredAt = (home[HomeWatchProgressBridge.RESTORATION_PREFIX + profileId + "_" + identity] as? Number)?.toLong() ?: 0L
            if (removedAt == 0L && restoredAt == 0L) put(path.removePrefix("file://"), Long.MAX_VALUE)
        }
        for (json in externalJson) runCatching {
            val revision = JsonParser.parseString(json).asJsonObject
            val item = if (revision.has("value")) JsonParser.parseString(revision.text("value")).asJsonObject else revision
            if ((item.number("progressProfileId").takeIf { it > 0 } ?: 1L) != profileId.toLong()) return@runCatching
            val id = item.text("id")
            if (!id.matches(Regex("[a-f0-9]{64}"))) return@runCatching
            val removed = revision.text("deleted") == "true" || item.text("deleted") == "true" ||
                (item.text("started") == "false" && item.number("lastPlayedAt") > 0L)
            if (removed) put(ExternalPlaybackStore.CONTENT_PREFIX + id, maxOf(revision.number("changedAt"), item.number("updatedAt")))
            val recordMask = "external-playback|$id"
            val source = item.text("stalkerSource")
            val seriesId = item.text("stalkerSeriesId").ifBlank { item.text("stalkerId") }
            val seriesMask = if (item.text("category") == "series" && source.isNotBlank() && seriesId.isNotBlank()) {
                "external-series-resume|" + ExternalPlaybackStore.continueWatchingKey(
                    "series", false, id, "", "", source, seriesId, 0, 0)
            } else ""
            val maskedAt = maxOf(result[recordMask] ?: 0L, result[seriesMask] ?: 0L)
            val legacyMasked = recordMask in hidden || (seriesMask.isNotEmpty() && seriesMask in hidden)
            if (maskedAt > 0L || legacyMasked) {
                put(ExternalPlaybackStore.CONTENT_PREFIX + id, if (maskedAt > 0L) maskedAt else
                    maxOf(revision.number("changedAt"), item.number("updatedAt"), item.number("lastPlayedAt")))
            }
        }
        return result
    }

    fun parse(filesJson: String, externalJson: List<String>, profileId: Int): List<WatchProgress> {
        val files = runCatching { JsonParser.parseString(filesJson).asJsonArray.toList() }.getOrDefault(emptyList())
        val result = ArrayList<WatchProgress>()
        fun JsonObject.text(key: String) = runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull().orEmpty()
        fun JsonObject.number(key: String) = text(key).toLongOrNull() ?: 0L
        fun JsonObject.flag(key: String) = text(key) == "true"
        for (element in files) runCatching {
            val item = element.asJsonObject
            if ((item.number("profileId").takeIf { it > 0 } ?: 1L) != profileId.toLong()) return@runCatching
            val path = item.text("key").ifBlank { item.text("source") }.removePrefix("file://")
            if (path.isBlank() || item.number("position") <= 0 || item.number("updatedAt") <= 0) return@runCatching
            val type = item.text("contentType").ifBlank { if (path.startsWith("smb://")) "freebox" else "video" }
            val id = item.text("contentId").ifBlank { if (type == "freebox") "freebox:$path" else path }
            result += WatchProgress(id, type, item.text("title"), null, null, null, id, null, null, null,
                item.number("position"), item.number("duration"), item.number("updatedAt"))
        }
        for (json in externalJson) runCatching {
            val item = JsonParser.parseString(json).asJsonObject
            if ((item.number("progressProfileId").takeIf { it > 0 } ?: 1L) != profileId.toLong() ||
                !item.flag("started") || item.flag("deleted")) return@runCatching
            val id = item.text("id")
            if (!id.matches(Regex("[a-f0-9]{64}"))) return@runCatching
            val live = item.text("category") == "live"
            val timestamp = item.number("lastPlayedAt").takeIf { it > 0 } ?: item.number("updatedAt")
            if (timestamp <= 0 || (!live && item.number("position") <= 0)) return@runCatching
            result += WatchProgress(ExternalPlaybackStore.CONTENT_PREFIX + id, if (live) "iptv" else "home-external",
                item.text("title"), item.text("artwork").ifBlank { item.text("poster") }, item.text("backdrop"), item.text("logo"),
                item.text("url"), item.number("season").toInt().takeIf { it > 0 },
                item.number("episode").toInt().takeIf { it > 0 }, item.text("episodeTitle"),
                if (live) 0 else item.number("position"), if (live) 0 else item.number("duration"), timestamp,
                description = item.text("description"))
        }
        return result.groupBy { it.storageKey }.values.map { it.maxBy { row -> row.lastWatched } }
    }
}
