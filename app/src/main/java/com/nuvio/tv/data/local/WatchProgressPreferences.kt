package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.util.UUID
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.domain.model.WatchProgress
import com.jojotv.home.progresspilot.PilotCaptureContext
import com.jojotv.home.progresspilot.PilotJournal
import com.jojotv.home.progresspilot.PilotJson
import com.jojotv.home.progresspilot.PilotOperation
import com.jojotv.home.progresspilot.PilotAcknowledgement
import com.jojotv.home.progresspilot.PilotPlaybackCheckpoint
import com.jojotv.home.progresspilot.PilotDeliveryQueue
import com.jojotv.home.progresspilot.PilotScope
import com.jojotv.home.progresspilot.SupabaseProgressPilotStore
import com.jojotv.home.progresspilot.SupabaseProgressProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class WatchProgressPreferences internal constructor(
    private val dataStoreForProfile: (Int) -> DataStore<Preferences>,
    private val activeProfileId: StateFlow<Int>,
    private val pilotCapture: () -> PilotCaptureContext? = { null },
    private val pilotDeliveryStore: (Int) -> DataStore<Preferences>? = { null },
    private val cloudStore: () -> com.jojotv.home.progresspilot.SupabaseProgressSyncStore? = { null }
) {
    @Inject constructor(factory: ProfileDataStoreFactory, profileManager: ProfileManager,
                        pilot: dagger.Lazy<SupabaseProgressPilotStore>,
                        cloud: dagger.Lazy<com.jojotv.home.progresspilot.SupabaseProgressSyncStore>) :
        this({ profileId -> factory.get(profileId, FEATURE) }, profileManager.activeProfileId, {
            if (com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) pilot.get().captureContext() else null
        }, { profileId ->
            if (com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) pilot.get().deliveryStore(profileId) else null
        }, { if (com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) cloud.get() else null })

    companion object {
        private const val TAG = "WatchProgressPrefs"
        private const val FEATURE = "watch_progress_preferences"
    }

    private fun store(profileId: Int = activeProfileId.value): DataStore<Preferences> =
        cloudStore()?.route(profileId, dataStoreForProfile(profileId)) ?: dataStoreForProfile(profileId)

    fun isCloud(profileId: Int = activeProfileId.value): Boolean = cloudStore()?.isCloud(profileId) == true
    fun sourceMode(profileId: Int): Flow<Boolean> = cloudStore()?.sourceMode(profileId) ?: flowOf(false)
    suspend fun refreshCloud() { if (isCloud()) cloudStore()?.sync() }
    suspend fun registerCloudContent(progress: WatchProgress) { if (isCloud()) cloudStore()?.registerContent(progress) }
    suspend fun registerCloudContents(progress: Collection<WatchProgress>) { if (isCloud()) cloudStore()?.registerContents(progress) }
    suspend fun setCloudResumeHidden(contentId: String, hidden: Boolean) {
        if (isCloud()) cloudStore()?.setResumeHidden(contentId, hidden)
    }
    suspend fun resetCloudFileProgress(path: String) {
        if (isCloud()) cloudStore()?.resetFileProgress(path)
    }
    suspend fun prepareCloudResume(id: String, season: Int? = null, episode: Int? = null) {
        if (!isCloud()) return
        cloudStore()?.prepareResume(id, season, episode)
    }

    private val gson = Gson()
    private val watchProgressKey = stringPreferencesKey("watch_progress_map")
    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_push_ms")
    private val deltaCursorKey = longPreferencesKey("watch_progress_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watch_progress_delta_initialized")
    private val pendingRemoteDeleteKeys = stringSetPreferencesKey("pending_remote_delete_keys")
    private val pendingRemoteDeleteIntentsKey = stringPreferencesKey("pending_remote_delete_intents_v1")
    private val pilotInstanceKey = stringPreferencesKey("progress_pilot_profile_instance")
    private val pilotOutboxKey = stringPreferencesKey("progress_pilot_delivery_outbox")
    private val pilotIssueKey = stringPreferencesKey("progress_pilot_capture_issue")
    private val pilotCaptureIssues = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val pilotJobs by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** Explicit enrollment only; this key is not part of the playback progress map. */
    suspend fun getOrCreatePilotProfileInstanceId(profileId: Int): String {
        var result = ""
        dataStoreForProfile(profileId).edit { prefs ->
            result = prefs[pilotInstanceKey] ?: java.util.UUID.randomUUID().toString().also { prefs[pilotInstanceKey] = it }
        }
        return result
    }

    fun observePilotDelivery(profileId: Int): Flow<PilotJournal> {
        val delivery = pilotDeliveryStore(profileId) ?: return flowOf(PilotJournal())
        return delivery.data.combine(pilotCaptureIssues) { prefs, issues ->
            try {
                PilotJson.decode(prefs[pilotOutboxKey]).let { it.copy(issue = issues[profileId] ?: prefs[pilotIssueKey] ?: it.issue) }
            } catch (_: Exception) {
                PilotJournal(issue = "OUTBOX_UNREADABLE")
            }
        }
    }

    suspend fun pilotProfileInstanceId(profileId: Int): String? = dataStoreForProfile(profileId).data.first()[pilotInstanceKey]

    /** Writes delivery bookkeeping only. Never acknowledges or changes NAS state. */
    suspend fun acknowledgePilotOperations(scope: PilotScope,
                                           sent: Collection<PilotOperation>, acks: Collection<PilotAcknowledgement>) {
        if (pilotProfileInstanceId(scope.localProfileId) != scope.localProfileInstanceId) return
        pilotDeliveryStore(scope.localProfileId)?.edit { prefs ->
            val journal = PilotJson.decode(prefs[pilotOutboxKey])
            prefs[pilotOutboxKey] = PilotJson.encode(journal.acknowledge(scope.epoch, sent, acks))
        }
    }

    fun pilotDeliveryQueue(): PilotDeliveryQueue = object : PilotDeliveryQueue {
        override fun observe(profileId: Int) = observePilotDelivery(profileId)
        override suspend fun profileInstanceId(profileId: Int) = pilotProfileInstanceId(profileId)
        override suspend fun acknowledge(scope: PilotScope, sent: Collection<PilotOperation>,
                                         acks: Collection<PilotAcknowledgement>) = acknowledgePilotOperations(scope, sent, acks)
        override suspend fun repairKeyEpoch(scope: PilotScope, previousEpoch: String, sequenceFloor: Long): PilotJournal {
            require(pilotProfileInstanceId(scope.localProfileId) == scope.localProfileInstanceId)
            val delivery = requireNotNull(pilotDeliveryStore(scope.localProfileId))
            var repaired: PilotJournal? = null
            delivery.edit { prefs ->
                require(pilotProfileInstanceId(scope.localProfileId) == scope.localProfileInstanceId)
                require(pilotCaptureIssues.value[scope.localProfileId] == null && prefs[pilotIssueKey] == null)
                repaired = PilotJson.decode(prefs[pilotOutboxKey]).forKeyRepair(previousEpoch, scope.epoch, sequenceFloor)
                prefs[pilotOutboxKey] = PilotJson.encode(requireNotNull(repaired))
            }
            return requireNotNull(repaired)
        }
    }

    private fun pilotContext(profileId: Int, profileInstance: String?): PilotCaptureContext? {
        if (pilotCaptureIssues.value[profileId] != null) return null
        val context = try { pilotCapture() } catch (_: Exception) { null } ?: return null
        return context.takeIf { it.scope.localProfileId == profileId &&
            profileInstance == it.scope.localProfileInstanceId }
    }

    private fun capturePilotCheckpoint(profileId: Int, profileInstance: String?,
                                       progress: WatchProgress?, event: PilotPlaybackCheckpoint?) {
        if (event == null || progress == null) return
        try {
            val context = pilotContext(profileId, profileInstance) ?: return
            val payload = SupabaseProgressProjector.checkpoint(context, event, progress) ?: return
            // The committed local checkpoint and NAS caller never await delivery-file I/O.
            pilotJobs.launch {
                try {
                    val current = pilotContext(profileId, profileInstance)
                    if (current?.scope != context.scope || current.activationBarrierElapsedMs != context.activationBarrierElapsedMs)
                        return@launch
                    val delivery = pilotDeliveryStore(profileId) ?: return@launch
                    delivery.edit { prefs ->
                        val admitted = pilotContext(profileId, profileInstance)
                        if (admitted?.scope != context.scope ||
                            admitted.activationBarrierElapsedMs != context.activationBarrierElapsedMs ||
                            admitted.activationId != context.activationId) return@edit
                        try {
                            val journal = PilotJson.decode(prefs[pilotOutboxKey]).enqueue(context, payload)
                            prefs[pilotOutboxKey] = PilotJson.encode(journal)
                        } catch (_: Exception) { prefs[pilotIssueKey] = "OUTBOX_UNREADABLE" }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    pilotCaptureIssues.value = pilotCaptureIssues.value + (profileId to "CAPTURE_FAILED")
                }
            }
        } catch (_: Exception) {
            pilotCaptureIssues.value = pilotCaptureIssues.value + (profileId to "CAPTURE_FAILED")
        }
    }

    internal data class FreeboxProgressUpsertResult(
        val accepted: Boolean,
        val removedRemoteKeys: Set<String>
    )

    // Gson fields must stay stable across minified APK upgrades, like the queue format.
    @androidx.annotation.Keep
    data class PendingRemoteDelete(val deletedAt: Long, val version: String)

    private fun pendingDeleteIntents(prefs: Preferences): Map<String, PendingRemoteDelete> = runCatching {
        val type = object : TypeToken<Map<String, PendingRemoteDelete>>() {}.type
        val decoded: Map<String, PendingRemoteDelete> = gson.fromJson(
            prefs[pendingRemoteDeleteIntentsKey] ?: "{}", type)
        decoded.filter { (key, intent) -> key in prefs[pendingRemoteDeleteKeys].orEmpty() &&
            intent.deletedAt in 1L..9_007_199_254_740_991L && !intent.version.isNullOrBlank() }
    }.getOrDefault(emptyMap())

    private fun queueRemoteDeletes(prefs: MutablePreferences,
                                   deletions: Map<String, Long>) {
        val intents = pendingDeleteIntents(prefs).toMutableMap()
        for ((key, deletedAt) in deletions) {
            require(deletedAt in 1L..9_007_199_254_740_991L) { "Invalid progress deletion timestamp" }
            if (deletedAt >= (intents[key]?.deletedAt ?: 0L)) {
                intents[key] = PendingRemoteDelete(deletedAt, UUID.randomUUID().toString())
            }
        }
        prefs[pendingRemoteDeleteKeys] = prefs[pendingRemoteDeleteKeys].orEmpty() + deletions.keys
        prefs[pendingRemoteDeleteIntentsKey] = gson.toJson(intents)
    }

    /** Persist the action time once; retries must never turn it into a new deletion. */
    suspend fun enqueuePendingRemoteDeletes(
        keys: Collection<String>,
        profileId: Int = activeProfileId.value,
        deletedAt: Long = System.currentTimeMillis()
    ) {
        val normalized = keys.map(String::trim).filter(String::isNotEmpty).toSet()
        if (normalized.isEmpty()) return
        store(profileId).edit { prefs ->
            queueRemoteDeletes(prefs, normalized.associateWith { deletedAt })
        }
    }

    internal fun homeRemovalTimestamp(progress: WatchProgress, removals: Map<String, Long>): Long {
        val path = when {
            progress.contentId.startsWith("freebox:") -> freeboxPathFromContentId(progress.contentId)
            progress.contentType == "video" -> progress.contentId.removePrefix("file://")
            else -> ""
        }
        return maxOf(removals[progress.storageKey] ?: 0L, removals[progress.contentId] ?: 0L,
            if (path.isEmpty()) 0L else removals[path] ?: 0L)
    }

    /** A Home removal and its durable NAS intent are committed together. */
    suspend fun removeHomeResumes(removals: Map<String, Long>, profileId: Int): Set<String> {
        if (removals.isEmpty()) return emptySet()
        val removed = linkedSetOf<String>()
        store(profileId).edit { prefs ->
            val local = parseProgressMap(prefs[watchProgressKey] ?: "{}").toMutableMap()
            val deletions = linkedMapOf<String, Long>()
            local.entries.removeAll { (key, item) ->
                val removedAt = homeRemovalTimestamp(item, removals)
                if (removedAt > 0L && item.lastWatched <= removedAt) {
                    removed += key
                    removed += item.storageKey
                    // Keep undated historic masks local. Promoting the observed
                    // row's timestamp would let the same mask delete every replay.
                    if (removedAt != Long.MAX_VALUE) for (identity in setOf(key, item.storageKey)) {
                        deletions[identity] = maxOf(deletions[identity] ?: 0L, removedAt)
                    }
                    true
                } else false
            }
            if (removed.isNotEmpty()) {
                prefs[watchProgressKey] = gson.toJson(local)
                prefs[pendingRemoteDeleteKeys] = prefs[pendingRemoteDeleteKeys].orEmpty() + removed
                queueRemoteDeletes(prefs, deletions)
            }
        }
        return removed
    }

    suspend fun pendingRemoteDeleteKeys(profileId: Int = activeProfileId.value): Set<String> =
        store(profileId).data.first()[pendingRemoteDeleteKeys].orEmpty()

    suspend fun pendingRemoteDeleteIntents(profileId: Int = activeProfileId.value): Map<String, PendingRemoteDelete> =
        pendingDeleteIntents(store(profileId).data.first())

    suspend fun acknowledgeRemoteDeleteIntents(
        sent: Map<String, PendingRemoteDelete>,
        profileId: Int = activeProfileId.value
    ) {
        if (sent.isEmpty()) return
        store(profileId).edit { prefs ->
            val current = pendingDeleteIntents(prefs)
            val acknowledged = sent.keys.filter { current[it] == sent[it] }.toSet()
            if (acknowledged.isEmpty()) return@edit
            val remaining = prefs[pendingRemoteDeleteKeys].orEmpty() - acknowledged
            if (remaining.isEmpty()) prefs.remove(pendingRemoteDeleteKeys)
            else prefs[pendingRemoteDeleteKeys] = remaining
            prefs[pendingRemoteDeleteIntentsKey] = gson.toJson(current - acknowledged)
        }
    }

    /** A legacy caller has no version evidence and cannot acknowledge a newer intent. */
    suspend fun acknowledgeRemoteDeletes(
        keys: Collection<String>,
        profileId: Int = activeProfileId.value
    ) {
        val acknowledged = keys.map(String::trim).filter(String::isNotEmpty).toSet()
        if (acknowledged.isEmpty()) return
        store(profileId).edit { prefs ->
            val unversioned = acknowledged - pendingDeleteIntents(prefs).keys
            val remaining = prefs[pendingRemoteDeleteKeys].orEmpty() - unversioned
            if (remaining.isEmpty()) prefs.remove(pendingRemoteDeleteKeys)
            else prefs[pendingRemoteDeleteKeys] = remaining
        }
    }

    /** Persisted timestamp of the last successful push to remote. */
    suspend fun getLastSuccessfulPushMs(profileId: Int = activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    suspend fun setLastSuccessfulPushMs(timestampMs: Long, profileId: Int = activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[lastSuccessfulPushMsKey] = timestampMs
        }
    }

    suspend fun getDeltaCursor(profileId: Int = activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = activeProfileId.value): Boolean {
        val prefs = store(profileId).data.first()
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
    }

    /**
     * Get all watch progress items, sorted by last watched (most recent first)
     * For series, only returns the series-level entry (not individual episode entries)
     * to avoid duplicates in continue watching.
     *
     * JSON parsing, grouping, and sorting are performed off the main thread.
     * Results are cached — re-parsing only happens when the raw JSON actually changes.
     */
    @Volatile private var cachedProgressKey: String? = null
    @Volatile private var cachedProgressResult: List<WatchProgress>? = null

    fun observeAllProgress(profileId: Int): Flow<List<WatchProgress>> =
        store(profileId).data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"

            // Fast path: if JSON hasn't changed, return cached result immediately.
            val cached = cachedProgressResult
            val cacheKey = "$profileId:$json"
            if (cacheKey == cachedProgressKey && cached != null) {
                return@map cached
            }

            val allItems = parseProgressMap(json)

            // Group only records with the same stable content/provider/episode ID.
            // Title-based grouping merged distinct films and series with homonymous titles.
            // When lastWatched is equal (e.g. batch mark-as-watched), prefer the highest season/episode.
            val latestByContent = allItems.values
                .groupBy(::continueWatchingIdentity)
                .mapValues { (_, items) ->
                    items.maxWithOrNull(
                        compareBy<WatchProgress> { it.lastWatched }
                            .thenBy { it.season ?: 0 }
                            .thenBy { it.episode ?: 0 }
                    )
                }
                .values
                .filterNotNull()

            val result = latestByContent.sortedByDescending { it.lastWatched }

            // Cache for next emission
            cachedProgressKey = cacheKey
            cachedProgressResult = result
            result
        }.flowOn(Dispatchers.Default)

    val allProgress: Flow<List<WatchProgress>> =
        activeProfileId.flatMapLatest(::observeAllProgress)

    @Volatile private var cachedRawProgress: Pair<String, List<WatchProgress>>? = null

    val allRawProgress: Flow<List<WatchProgress>> = activeProfileId.flatMapLatest { pid ->
        observeHomeProgressSnapshot(pid).map { it.first }
    }

    internal fun observeHomeProgressSnapshot(profileId: Int): Flow<Pair<List<WatchProgress>, Set<String>>> =
        store(profileId).data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val cached = cachedRawProgress
            val cacheKey = "$profileId:$json"
            val result = if (cached != null && cacheKey == cached.first) cached.second else {
                parseProgressMap(json).values.sortedByDescending { it.lastWatched }.also {
                    cachedRawProgress = cacheKey to it
                }
            }
            result to preferences[stringSetPreferencesKey("cloud_resume_hidden")].orEmpty()
        }.flowOn(Dispatchers.Default)

    /**
     * Get every started item, including fully played videos.  The user owns
     * the Continue Watching history and decides when an entry is removed.
     */
    private fun startedItems(list: List<WatchProgress>): List<WatchProgress> = list.filter { progress ->
        progress.position > 0L || progress.progressPercent?.let { it > 0f } == true ||
            ((progress.isLiveChannel || progress.contentId.startsWith("tizentube:")) && progress.lastWatched > 0L)
    }

    val continueWatching: Flow<List<WatchProgress>> = activeProfileId.flatMapLatest(::observeContinueWatching)

    fun observeContinueWatching(profileId: Int): Flow<List<WatchProgress>> =
        observeAllProgress(profileId).combine(store(profileId).data) { all, prefs ->
            val hidden = prefs[stringSetPreferencesKey("cloud_resume_hidden")].orEmpty()
            startedItems(all).filterNot { it.storageKey in hidden }
        }

    /**
     * Get watch progress for a specific content item
     */
    fun getProgress(contentId: String): Flow<WatchProgress?> {
        return store().data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            // Try direct key first (movies), then find latest episode entry (series).
            val candidates = map.values.filter { it.contentId == contentId }
            // Callers of this legacy API do not supply a provider/type. Never
            // guess a resume if that ID belongs to multiple namespaces.
            if (candidates.map { it.contentType to it.addonBaseUrl.orEmpty() }.distinct().size > 1) null
            else candidates.maxByOrNull { it.lastWatched }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return store().data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            val candidates = map.values.filter {
                it.contentId == contentId && it.season == season && it.episode == episode 
            }
            if (candidates.map { it.storageKey }.distinct().size > 1) null
            else candidates.maxByOrNull { it.lastWatched }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Get all episode progress for a series
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return store().data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            map.values
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season!! to it.episode!!) }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress, profileId: Int = activeProfileId.value) {
        val pilotEvent = coroutineContext[PilotPlaybackCheckpoint]
        var pilotAccepted: WatchProgress? = null
        var pilotInstance: String? = null
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            upsertProgressEntries(map, listOf(progress))

            val pruned = pruneOldItems(map)
            preferences[watchProgressKey] = gson.toJson(pruned)
            if (pilotEvent != null) {
                pilotAccepted = pruned[progress.storageKey]
                pilotInstance = preferences[pilotInstanceKey]
            }
        }
        capturePilotCheckpoint(profileId, pilotInstance, pilotAccepted, pilotEvent)
    }

    suspend fun saveProgressBatch(progressList: List<WatchProgress>, profileId: Int = activeProfileId.value) {
        if (progressList.isEmpty()) return
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            upsertProgressEntries(map, progressList)
            val pruned = pruneOldItems(map)
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    /**
     * Remove watch progress for a specific item
     */
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null,
                               profileId: Int = activeProfileId.value) {
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()

            val beforeSize = map.size
            map.entries.removeAll { (_, item) -> item.contentId == contentId &&
                (season == null || episode == null || (item.season == season && item.episode == episode)) }
            Log.d(
                TAG,
                "removeProgress start contentId=$contentId season=$season episode=$episode entriesBefore=$beforeSize"
            )

            if (season != null && episode != null) {
                // Remove specific episode progress + the series-level entry
                // so the item disappears from continue watching
                val key = "${contentId}_s${season}e${episode}"
                map.remove(key)
                map.remove(contentId)
                removeFreeboxEquivalentProgress(map, contentId)
                Log.d(TAG, "removeProgress episodeKey=$key existsAfter=${map.containsKey(key)}")
            } else {
                // Remove all progress for this content
                val keysToRemove = map.keys.filter { key ->
                    key == contentId ||
                        key.startsWith("${contentId}_s") ||
                        isSameFreeboxProgressTarget(key, map[key], contentId)
                }
                Log.d(TAG, "removeProgress removingKeys=${keysToRemove.joinToString()}")
                keysToRemove.forEach { map.remove(it) }
            }

            Log.d(TAG, "removeProgress complete contentId=$contentId entriesAfter=${map.size}")
            preferences[watchProgressKey] = gson.toJson(map)
        }
    }

    /**
     * Atomically replaces every legacy/fingerprinted entry for the same Freebox
     * file. A resume can otherwise be saved once with the path-only id and once
     * with the fingerprinted id, producing two Continue Watching cards.
     */
    internal suspend fun saveFreeboxProgressReplacingEquivalent(
        progress: WatchProgress,
        profileId: Int = activeProfileId.value
    ): FreeboxProgressUpsertResult {
        val pilotEvent = coroutineContext[PilotPlaybackCheckpoint]
        var pilotAccepted: WatchProgress? = null
        var pilotInstance: String? = null
        var result = FreeboxProgressUpsertResult(
            accepted = false,
            removedRemoteKeys = emptySet()
        )
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            val incomingKey = createKey(progress)
            val exactExisting = map[incomingKey]
            val equivalentEntries = map.filter { (key, existing) ->
                isSameFreeboxProgressTarget(key, existing, progress.contentId)
            }
            val newestEquivalent = equivalentEntries.values.maxByOrNull { it.lastWatched }

            // Player callbacks are asynchronous. Do not let an older callback
            // recreate the identity that a newer resume has just replaced.
            if (!isCloud(profileId) && newestEquivalent != null && progress.lastWatched < newestEquivalent.lastWatched) {
                Log.d(
                    TAG,
                    "Ignoring stale Freebox progress save key=$incomingKey " +
                        "incoming=${progress.lastWatched} existing=${newestEquivalent.lastWatched}"
                )
                return@edit
            }

            val removedKeys = equivalentEntries.filterValues { it.contentId != progress.contentId }.keys
                .filterNot { it == incomingKey }
                .toSet()
            val progressToSave = exactExisting?.let { existing ->
                progress.copy(
                    name = progress.name.takeIf { it.isNotBlank() } ?: existing.name,
                    poster = progress.poster ?: existing.poster,
                    backdrop = progress.backdrop ?: existing.backdrop,
                    logo = progress.logo ?: existing.logo,
                    description = progress.description?.takeIf { it.isNotBlank() }
                        ?: existing.description,
                    episodeTitle = progress.episodeTitle ?: existing.episodeTitle
                )
            } ?: progress
            equivalentEntries.keys.forEach { map.remove(it) }
            upsertProgressEntries(map, listOf(progressToSave))
            preferences[watchProgressKey] = gson.toJson(pruneOldItems(map))
            if (pilotEvent != null) {
                pilotAccepted = map[incomingKey]
                pilotInstance = preferences[pilotInstanceKey]
            }
            result = FreeboxProgressUpsertResult(
                accepted = true,
                removedRemoteKeys = removedKeys
            )
        }
        capturePilotCheckpoint(profileId, pilotInstance, pilotAccepted, pilotEvent)
        return result
    }

    private fun removeFreeboxEquivalentProgress(
        map: MutableMap<String, WatchProgress>,
        contentId: String
    ) {
        val keysToRemove = map.keys.filter { key ->
            key == contentId || isSameFreeboxProgressTarget(key, map[key], contentId)
        }
        keysToRemove.forEach { map.remove(it) }
    }

    private fun isSameFreeboxProgressTarget(
        key: String,
        progress: WatchProgress?,
        targetContentId: String
    ): Boolean {
        if (!targetContentId.startsWith("freebox:", ignoreCase = true)) return false
        val targetPath = freeboxPathFromContentId(targetContentId)
        if (targetPath.isBlank()) return false
        val keyPath = if (key.startsWith("freebox:", ignoreCase = true)) {
            freeboxPathFromContentId(key.withoutEpisodeProgressSuffix())
        } else {
            null
        }
        val progressContentPath = progress?.contentId
            ?.takeIf { it.startsWith("freebox:", ignoreCase = true) }
            ?.let(::freeboxPathFromContentId)
        val progressVideoPath = progress?.videoId
            ?.takeIf { it.startsWith("freebox:", ignoreCase = true) }
            ?.let(::freeboxPathFromContentId)
        return keyPath == targetPath ||
            progressContentPath == targetPath ||
            progressVideoPath == targetPath
    }

    private fun String.withoutEpisodeProgressSuffix(): String =
        replace(Regex("_s\\d+e\\d+$"), "")

    /**
     * Remove watch progress for multiple episodes in a single DataStore transaction.
     */
    suspend fun removeProgressBatch(contentId: String, episodes: List<Pair<Int, Int>>,
                                    profileId: Int = activeProfileId.value) {
        if (episodes.isEmpty()) return
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            map.entries.removeAll { (_, item) -> item.contentId == contentId &&
                (item.season to item.episode) in episodes }
            for ((season, episode) in episodes) {
                map.remove("${contentId}_s${season}e${episode}")
            }
            map.remove(contentId)
            Log.d(TAG, "removeProgressBatch contentId=$contentId removed=${episodes.size} episodes entriesAfter=${map.size}")
            preferences[watchProgressKey] = gson.toJson(map)
        }
    }

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress) {
        // If the incoming duration is a dummy sentinel (≤ 1ms), check for an
        // existing local entry with a real duration from prior playback.
        // This creates a proper completed entry that syncs correctly cross-device.
        val effectiveDuration = if (progress.duration <= 1L) {
            val key = createKey(progress)
            val existing = getAllRawEntries()[key]
            existing?.duration?.takeIf { it > 1L } ?: progress.duration
        } else {
            progress.duration
        }

        val completedProgress = progress.copy(
            position = effectiveDuration,
            duration = effectiveDuration,
            lastWatched = System.currentTimeMillis()
        )
        saveProgress(completedProgress)
    }

    /**
     * Mark multiple items as completed in a single DataStore transaction.
     */
    suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val rawEntries = getAllRawEntries()
        val now = System.currentTimeMillis()
        val completed = progressList.map { progress ->
            val effectiveDuration = if (progress.duration <= 1L) {
                val key = createKey(progress)
                rawEntries[key]?.duration?.takeIf { it > 1L } ?: progress.duration
            } else {
                progress.duration
            }
            progress.copy(
                position = effectiveDuration,
                duration = effectiveDuration,
                lastWatched = now
            )
        }
        saveProgressBatch(completed)
    }

    /**
     * Returns the raw key→WatchProgress map from DataStore (for sync push).
     *
     * @param profileId Explicit profile to read from. Prevents race conditions
     *   when the active profile changes between scheduling and execution of a sync.
     */
    suspend fun getAllRawEntries(profileId: Int = activeProfileId.value): Map<String, WatchProgress> =
        withContext(Dispatchers.Default) {
            val preferences = store(profileId).data.first()
            val json = preferences[watchProgressKey] ?: "{}"
            parseProgressMap(json)
        }

    /** One storage emission: a remote dismissal must agree with the displayed progress. */
    internal suspend fun getHomeProgressSnapshot(profileId: Int): Pair<List<WatchProgress>, Set<String>> =
        observeHomeProgressSnapshot(profileId).first()

    /**
     * Merges remote entries into local storage. Newer lastWatched wins per key.
     *
     * @param profileId Explicit profile to write to. Prevents race conditions
     *   when the active profile changes between pull and merge operations.
     */
    suspend fun mergeRemoteEntries(
        remoteEntries: Map<String, WatchProgress>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = activeProfileId.value,
        removeMissingRemoteEntries: Boolean = true
    ): Boolean {
        require(!isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
        var preservedLocalItems = false
        Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${remoteEntries.size} remote entries, lastPushMs=$lastSuccessfulPushMs, profile=$profileId, removeMissing=$removeMissingRemoteEntries")
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val local = parseProgressMap(json).toMutableMap()
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${local.size} existing local entries")

            // Remove local entries that no longer exist on remote - but protect
            // entries created after the last successful push (they haven't reached
            // remote yet, so their absence doesn't mean deletion on another device).
            if (removeMissingRemoteEntries && remoteEntries.isNotEmpty()) {
                val removedKeys = local.keys - remoteEntries.keys
                removedKeys.forEach { key ->
                    val localEntry = local[key]
                    if (localEntry != null && localEntry.lastWatched > lastSuccessfulPushMs) {
                        Log.d("WatchProgressPrefs", "  preserved key=$key (lastWatched=${localEntry.lastWatched} > lastPush=$lastSuccessfulPushMs)")
                        preservedLocalItems = true
                    } else {
                        local.remove(key)
                        Log.d("WatchProgressPrefs", "  removed key=$key (not in remote)")
                    }
                }
            }

            for ((key, remote) in remoteEntries) {
                val existing = local[key]
                if (existing == null || remote.lastWatched > existing.lastWatched) {
                    local[key] = mergeDisplayMetadata(remote, existing)
                    Log.d("WatchProgressPrefs", "  merged key=$key (existing=${existing != null})")
                } else if (existing.lastWatched > remote.lastWatched && existing.lastWatched > lastSuccessfulPushMs) {
                    Log.d("WatchProgressPrefs", "  skipped key=$key (local is newer)")
                    preservedLocalItems = true
                } else {
                    Log.d("WatchProgressPrefs", "  skipped key=$key (already synced)")
                }
            }

            val pruned = pruneOldItems(local)
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
        return preservedLocalItems
    }

    suspend fun applyRemoteChanges(
        upserts: Map<String, WatchProgress>,
        deletes: Collection<String>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = activeProfileId.value,
        deletionTimes: Map<String, Long> = emptyMap()
    ): Boolean {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return false
        }
        require(!isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
        var preservedLocalItems = false
        var beforeCount = 0
        var afterCount = 0
        store(profileId).edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val local = parseProgressMap(json).toMutableMap()
            beforeCount = local.size

            deletes.forEach { key ->
                val matching = local.filter { (storedKey, item) ->
                    storedKey == key || item.storageKey == key || item.legacyStorageKey == key
                }
                // A legacy key shared by distinct providers cannot identify a safe delete.
                if (matching.values.map { it.storageKey }.distinct().size <= 1) {
                    matching.forEach { (storedKey, item) ->
                        if (item.lastWatched <= (deletionTimes[key] ?: Long.MAX_VALUE)) local.remove(storedKey)
                        else preservedLocalItems = true
                    }
                }
            }

            upserts.forEach { (key, remote) ->
                val existing = local.values.filter { it.storageKey == remote.storageKey }.maxByOrNull { it.lastWatched }
                val deletedAt = maxOf(deletionTimes[key] ?: 0L,
                    deletionTimes[remote.storageKey] ?: 0L, deletionTimes[remote.legacyStorageKey] ?: 0L)
                if (remote.lastWatched <= deletedAt) return@forEach
                if (WatchProgress.shouldApplyRemoteProgress(existing?.lastWatched, remote.lastWatched)) {
                    upsertProgressEntries(local, listOf(mergeDisplayMetadata(remote, existing)))
                } else if (WatchProgress.hasNewerUnpushedLocalProgress(
                        existing?.lastWatched, remote.lastWatched, lastSuccessfulPushMs
                    )) {
                    preservedLocalItems = true
                }
            }

            val pruned = pruneOldItems(local)
            afterCount = pruned.size
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size} preservedLocal=$preservedLocalItems")
        return preservedLocalItems
    }

    suspend fun replaceWithRemoteEntries(
        remoteEntries: Map<String, WatchProgress>,
        profileId: Int = activeProfileId.value
    ) {
        require(!isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
        Log.d("WatchProgressPrefs", "replaceWithRemoteEntries: ${remoteEntries.size} remote entries, profile=$profileId")
        store(profileId).edit { preferences ->
            val currentJson = preferences[watchProgressKey] ?: "{}"
            val current = parseProgressMap(currentJson)
            if (remoteEntries.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "replaceWithRemoteEntries: remote empty while local has ${current.size} entries; preserving local watch progress")
                return@edit
            }
            val merged = remoteEntries.mapValues { (key, remote) ->
                mergeDisplayMetadata(remote, current[key])
            }.toMutableMap()
            val pruned = pruneOldItems(merged)
            require(!isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
            Log.d("WatchProgressPrefs", "replaceWithRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    /**
     * Clear all watch progress
     */
    suspend fun clearAll() {
        store().edit { preferences ->
            preferences.remove(watchProgressKey)
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
    }

    private fun createKey(progress: WatchProgress): String = progress.storageKey

    private fun continueWatchingIdentity(progress: WatchProgress): String = WatchProgress.stableIdentity(
        progress.contentType,
        progress.contentId,
        progress.addonBaseUrl,
        progress.season,
        progress.episode
    )

    private fun upsertProgressEntries(
        map: MutableMap<String, WatchProgress>,
        progressList: List<WatchProgress>
    ) {
        progressList.forEach { progress ->
            val canonicalProgress = if (progress.isLiveChannel) progress.copy(position = 0, duration = 0, progressPercent = null) else progress
            val key = createKey(canonicalProgress)
            val equivalent = map.filterValues { it.storageKey == key }
            val existing = equivalent.values.maxByOrNull { it.lastWatched }
            // Position callbacks may reach DataStore out of order (for example
            // when leaving the player while a periodic snapshot is still queued).
            // The callback timestamp is captured with the snapshot, so never let
            // that older snapshot undo a more recent resume point.
            if (!isCloud() && existing != null && progress.lastWatched < existing.lastWatched) {
                Log.d(
                    TAG,
                    "Ignoring stale progress save key=$key incoming=${progress.lastWatched} " +
                        "existing=${existing.lastWatched}"
                )
                return@forEach
            }
            equivalent.keys.forEach { map.remove(it) }
            // Preserve display metadata (poster, backdrop, logo, name) from the existing
            // entry when the incoming save has null values — prevents a mid-playback
            // position update from wiping artwork that was saved on first play.
            map[key] = if (existing != null) {
                canonicalProgress.copy(
                    name = canonicalProgress.name.takeIf { it.isNotBlank() } ?: existing.name,
                    poster = canonicalProgress.poster ?: existing.poster,
                    backdrop = canonicalProgress.backdrop ?: existing.backdrop,
                    logo = canonicalProgress.logo ?: existing.logo,
                    description = canonicalProgress.description?.takeIf { it.isNotBlank() }
                        ?: existing.description,
                    episodeTitle = canonicalProgress.episodeTitle ?: existing.episodeTitle,
                )
            } else {
                canonicalProgress
            }

            // Remove legacy series-level mirror key if this is an episode entry.
            // Mirror keys caused race conditions with stale progress data.
            if (canonicalProgress.season != null && canonicalProgress.episode != null) {
                val seriesKey = canonicalProgress.contentId
                if (seriesKey != key && map[seriesKey]?.storageKey == key) {
                    map.remove(seriesKey)
                }
            }
        }
    }

    private fun mergeDisplayMetadata(remote: WatchProgress, existing: WatchProgress?): WatchProgress {
        if (existing == null) return remote
        // JojoHome can provide a newer, exact Freebox presentation.  Do not let
        // an older JojoTV cache permanently mask it: after a pull, non-empty
        // remote fields are authoritative while empty fields still preserve the
        // known value.
        if (remote.contentId.startsWith("freebox:", ignoreCase = true)) {
            return remote.copy(
                name = remote.name.takeIf { it.isNotBlank() } ?: existing.name,
                poster = remote.poster ?: existing.poster,
                backdrop = remote.backdrop ?: existing.backdrop,
                logo = remote.logo ?: existing.logo,
                description = remote.description?.takeIf { it.isNotBlank() } ?: existing.description,
                episodeTitle = remote.episodeTitle ?: existing.episodeTitle,
                addonBaseUrl = remote.addonBaseUrl ?: existing.addonBaseUrl
            )
        }
        return remote.copy(
            name = existing.name.takeIf { it.isNotBlank() } ?: remote.name.takeIf { it.isNotBlank() } ?: existing.name,
            poster = existing.poster ?: remote.poster,
            backdrop = existing.backdrop ?: remote.backdrop,
            logo = existing.logo ?: remote.logo,
            description = existing.description?.takeIf { it.isNotBlank() }
                ?: remote.description,
            episodeTitle = existing.episodeTitle ?: remote.episodeTitle,
            addonBaseUrl = remote.addonBaseUrl ?: existing.addonBaseUrl
        )
    }

    private fun parseProgressMap(json: String): Map<String, WatchProgress> {
        return try {
            // Parse entry-by-entry so one malformed value doesn't wipe the entire map.
            val root = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            val parsed = mutableMapOf<String, WatchProgress>()
            root.entrySet().forEach { (key, value) ->
                runCatching {
                    parseWatchProgressFromJson(value)
                }.onSuccess { watchProgress ->
                    if (watchProgress != null) parsed[key] = watchProgress
                }.onFailure {
                    Log.w(TAG, "Skipping malformed watch progress entry for key=$key")
                }
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse progress data", e)
            // Backward compatibility with previously stored direct WatchProgress payloads.
            runCatching {
                val fallbackType = object : TypeToken<Map<String, WatchProgress>>() {}.type
                gson.fromJson<Map<String, WatchProgress>>(json, fallbackType) ?: emptyMap()
            }.getOrElse { emptyMap() }
        }
    }

    private fun parseWatchProgressFromJson(value: JsonElement): WatchProgress? {
        val obj = when {
            value.isJsonObject -> value.asJsonObject
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                runCatching { gson.fromJson(value.asString, JsonObject::class.java) }.getOrNull()
            }
            else -> null
        } ?: return null
        val contentId = obj.getString("contentId", "content_id")?.takeIf { it.isNotBlank() } ?: return null
        val contentType = obj.getString("contentType", "content_type")?.takeIf { it.isNotBlank() } ?: return null
        val videoId = obj.getString("videoId", "video_id")?.takeIf { it.isNotBlank() } ?: contentId
        val lastWatched = obj.getLong("lastWatched", "last_watched") ?: return null

        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = obj.getString("name").orEmpty(),
            poster = obj.getString("poster"),
            backdrop = obj.getString("backdrop"),
            logo = obj.getString("logo"),
            videoId = videoId,
            season = obj.getInt("season"),
            episode = obj.getInt("episode"),
            episodeTitle = obj.getString("episodeTitle", "episode_title"),
            position = obj.getLong("position") ?: 0L,
            duration = obj.getLong("duration") ?: 0L,
            lastWatched = lastWatched,
            addonBaseUrl = obj.getString("addonBaseUrl", "addon_base_url"),
            progressPercent = obj.getFloat("progressPercent", "progress_percent"),
            source = obj.getString("source")?.takeIf { it.isNotBlank() } ?: WatchProgress.SOURCE_LOCAL,
            traktPlaybackId = obj.getLong("traktPlaybackId", "trakt_playback_id"),
            traktMovieId = obj.getInt("traktMovieId", "trakt_movie_id"),
            traktShowId = obj.getInt("traktShowId", "trakt_show_id"),
            traktEpisodeId = obj.getInt("traktEpisodeId", "trakt_episode_id"),
            description = obj.getString("description")
        )
    }

    private fun JsonObject.getString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            return runCatching { value.asString }.getOrNull()
        }
        return null
    }

    private fun JsonObject.getLong(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asLong }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toLong() }.getOrNull()?.let { return it }
            runCatching { value.asString.toLong() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getInt(vararg keys: String): Int? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asInt }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toInt() }.getOrNull()?.let { return it }
            runCatching { value.asString.toInt() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getFloat(vararg keys: String): Float? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asFloat }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toFloat() }.getOrNull()?.let { return it }
            runCatching { value.asString.toFloat() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun pruneOldItems(map: MutableMap<String, WatchProgress>): Map<String, WatchProgress> {
        return map
    }
}
