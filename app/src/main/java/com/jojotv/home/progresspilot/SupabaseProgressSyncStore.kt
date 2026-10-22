package com.jojotv.home.progresspilot

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal interface CloudProgressAccess {
    val settings: kotlinx.coroutines.flow.StateFlow<PilotLocalSettings>
    fun cloudContext(): PilotCaptureContext?
    fun cloudRequestPermit(capture: PilotCaptureContext): PilotRequestPermit
    fun replaceSession(previous: PilotAuthSession, next: PilotAuthSession)
    fun recordDelivery(issue: String?, acknowledged: Boolean = false, retryAtMs: Long = 0)
}

private fun cloudStoreFactory(context: Context): (PilotLocalSettings) -> DataStore<Preferences> {
    val stores = mutableMapOf<String, DataStore<Preferences>>()
    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return { settings -> synchronized(stores) {
        val name = MessageDigest.getInstance("SHA-256").digest(cloudNamespace(settings).toByteArray(Charsets.UTF_8)).hex()
        stores.getOrPut(name) {
            PreferenceDataStoreFactory.create(scope = jobs) {
                File(context.noBackupFilesDir, "progress-cloud/$name.preferences_pb")
            }
        }
    } }
}

internal fun cloudNamespace(settings: PilotLocalSettings): String {
    val bound = requireNotNull(settings.scope)
    require(java.util.UUID.fromString(settings.cloudKeyEpoch).toString() == settings.cloudKeyEpoch)
    return listOf(settings.endpoint?.url, bound.ownerId, bound.profileBindingId,
        bound.localProfileInstanceId, bound.deviceId, bound.epoch, settings.cloudKeyEpoch).joinToString("\u0000")
}

internal const val CLOUD_CATALOG_TEMPLATE_LIMIT = 512

/** A dedicated no-backup cache/outbox. No NAS snapshot is copied into it. */
@Singleton
class SupabaseProgressSyncStore internal constructor(
    private val pilot: CloudProgressAccess,
    private val activeProfileId: kotlinx.coroutines.flow.StateFlow<Int>,
    private val api: SupabaseProgressApi,
    private val dataStoreForScope: (PilotLocalSettings) -> DataStore<Preferences>,
    private val profileInstance: suspend (Int) -> String?,
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    @Inject constructor(@ApplicationContext context: Context, pilot: SupabaseProgressPilotStore,
        profiles: ProfileManager, api: SupabaseProgressApi, profileStores: ProfileDataStoreFactory) : this(
        object : CloudProgressAccess {
            override val settings = pilot.settings
            override fun cloudContext() = pilot.cloudContext()
            override fun cloudRequestPermit(capture: PilotCaptureContext) = pilot.cloudRequestPermit(capture)
            override fun replaceSession(previous: PilotAuthSession, next: PilotAuthSession) = pilot.replaceSession(previous, next)
            override fun recordDelivery(issue: String?, acknowledged: Boolean, retryAtMs: Long) =
                pilot.recordDelivery(issue, retryAtMs, acknowledged)
        }, profiles.activeProfileId, api, cloudStoreFactory(context), { profileId ->
            profileStores.get(profileId, "watch_progress_preferences").data.first()[stringPreferencesKey("progress_pilot_profile_instance")]
        })
    private val gson = Gson()
    private val io = Mutex()
    private var retryDelayMs = 0L
    private var checkedCatalogueIdentity: String? = null
    private val progressKey = stringPreferencesKey("watch_progress_map")
    private val watchedKey = stringSetPreferencesKey("watched_items")
    private val hiddenKey = stringSetPreferencesKey("cloud_resume_hidden")
    private val journalKey = stringPreferencesKey("cloud_progress_journal_v1")
    private val indexKey = stringPreferencesKey("cloud_progress_index_v1")
    private val templatesKey = stringPreferencesKey("cloud_catalog_templates_v1")
    private val clockKey = stringPreferencesKey("cloud_checkpoint_order_v1")
    private val clockType = object : TypeToken<Map<String, CloudCheckpointOrder>>() {}.type
    private val progressType = object : TypeToken<Map<String, WatchProgress>>() {}.type
    private val indexType = object : TypeToken<Map<String, String>>() {}.type

    fun isCloud(profileId: Int): Boolean = pilot.settings.value.let {
        it.mode == PilotMode.SUPABASE_PROGRESS && it.scope?.localProfileId == profileId
    }

    fun sourceMode(profileId: Int): Flow<Boolean> = pilot.settings.map {
        it.mode == PilotMode.SUPABASE_PROGRESS && it.scope?.localProfileId == profileId
    }.distinctUntilChanged()

    internal fun currentIdentity(): String? = pilot.settings.value.takeIf {
        it.mode == PilotMode.SUPABASE_PROGRESS && it.scope?.localProfileId == activeProfileId.value
    }?.let(::identity)

    private fun identity(settings: PilotLocalSettings) = cloudNamespace(settings)
    private fun raw(settings: PilotLocalSettings) = dataStoreForScope(settings)

    private fun checkIdentity(expected: PilotLocalSettings) {
        if (pilot.settings.value.mode != PilotMode.SUPABASE_PROGRESS ||
            identity(pilot.settings.value) != identity(expected) ||
            pilot.settings.value.session?.sessionId != expected.session?.sessionId ||
            pilot.settings.value.cloudActivationId != expected.cloudActivationId ||
            pilot.settings.value.session?.ownerId != expected.scope?.ownerId ||
            activeProfileId.value != expected.scope?.localProfileId) throw PilotRequestsStopped()
    }

    private suspend fun checkProfile(expected: PilotLocalSettings) {
        checkIdentity(expected)
        val bound = requireNotNull(expected.scope)
        require(profileInstance(bound.localProfileId) == bound.localProfileInstanceId) { "CLOUD_PROFILE_INSTANCE_CHANGED" }
        checkIdentity(expected)
    }

    /** The wrapper's flow switches even when only the source changes, not the profile id. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun route(profileId: Int, nas: DataStore<Preferences>, watched: Boolean = false): DataStore<Preferences> =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = pilot.settings.map {
                if (it.mode == PilotMode.SUPABASE_PROGRESS && it.scope?.localProfileId == profileId) it else null
            }.distinctUntilChanged { a, b -> a?.cloudActivationId == b?.cloudActivationId &&
                a?.scope == b?.scope && a?.mode == b?.mode }.flatMapLatest { selected ->
                if (selected == null) nas.data else kotlinx.coroutines.flow.flow {
                    checkProfile(selected)
                    emitAll(raw(selected).data)
                }
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (kotlin.coroutines.coroutineContext[NasProgressMutation.Key] != null) {
                    require(!isCloud(profileId)) { "NAS_PROGRESS_SOURCE_NOT_SELECTED" }
                    return nas.updateData { before ->
                        require(!isCloud(profileId)) { "NAS_PROGRESS_SOURCE_CHANGED" }
                        transform(before)
                    }
                }
                val expected = pilot.settings.value
                if (!isCloud(profileId)) return nas.updateData(transform)
                checkProfile(expected)
                val capture = pilot.cloudContext() ?: throw PilotRequestsStopped()
                val event = kotlin.coroutines.coroutineContext[PilotPlaybackCheckpoint]
                return raw(expected).updateData { before ->
                    checkProfile(expected)
                    val after = transform(before).toMutablePreferences()
                    var journal = journal(before)
                    val previous = progress(before)
                    val next = progress(after).toMutableMap()
                    val hidden = before[hiddenKey].orEmpty().toMutableSet()
                    val clocks = before[clockKey]?.let { gson.fromJson<Map<String, CloudCheckpointOrder>>(it, clockType) }
                        .orEmpty().toMutableMap()
                    if (watched) {
                        val oldWatched = watchedItems(before)
                        val newWatched = watchedItems(after)
                        for (item in (oldWatched + newWatched).distinctBy { Triple(it.contentId, it.season, it.episode) }) {
                            val candidates = next.values.filter { it.contentId == item.contentId &&
                                it.season == item.season && it.episode == item.episode }
                            // WatchedItem has no provider: do not guess among ambiguous namespaces.
                            require(candidates.size <= 1) { "CLOUD_WATCHED_IDENTITY_AMBIGUOUS" }
                            val existing = candidates.singleOrNull() ?: continue
                            val completed = newWatched.any { it.contentId == item.contentId &&
                                it.season == item.season && it.episode == item.episode }
                            if (completed != existing.isCompleted()) next[existing.storageKey] = existing.copy(
                                position = if (completed) existing.duration.coerceAtLeast(1) else 0,
                                duration = existing.duration.coerceAtLeast(1), progressPercent = if (completed) 100f else 0f,
                                lastWatched = maxOf(System.currentTimeMillis(), existing.lastWatched + 1))
                        }
                    }
                    val index = index(before).toMutableMap()
                    val rejected = mutableMapOf<String, WatchProgress>()
                    for ((key, value) in next) {
                        val row = CloudProgressRow.from(capture, value, includeMetadata = false) ?: continue
                        index[row.content_key] = key
                        if (previous[key]?.let { CloudProgressRow.from(capture, it, includeMetadata = false)?.sameValue(row) } != true) {
                            if (event != null) {
                                val order = clocks[key]
                                if (order?.activation == expected.cloudActivationId && order.elapsed > event.createdAtElapsedMs) {
                                    previous[key]?.let { rejected[key] = it }
                                    continue
                                }
                                clocks[key] = CloudCheckpointOrder(expected.cloudActivationId, event.createdAtElapsedMs)
                            }
                            journal = journal.enqueue(row.copy(sealed_metadata = CloudResumeMetadata.seal(capture, value)))
                            hidden.remove(key)
                        }
                    }
                    next.putAll(rejected)
                    // Cache eviction during an upsert must never turn into a remote deletion.
                    if (next.all { (key, value) -> previous[key] == value }) {
                        for ((key, value) in previous.filterKeys { it !in next }) {
                            CloudProgressRow.from(capture, value, hidden = true)?.let {
                                journal = journal.enqueue(it.copy(source_time_ms = System.currentTimeMillis()))
                            }
                            // Retain the metadata locally for resolving a future explicit restore.
                            next[key] = value
                            hidden.add(key)
                        }
                    }
                    after[progressKey] = gson.toJson(next)
                    after[indexKey] = gson.toJson(index)
                    after[hiddenKey] = hidden
                    after[journalKey] = gson.toJson(journal)
                    after[clockKey] = gson.toJson(clocks)
                    after[watchedKey] = next.values.filter { it.isCompleted() }.map { gson.toJson(
                        WatchedItem(it.contentId, it.contentType, it.name, it.season, it.episode, it.lastWatched)) }.toSet()
                    checkIdentity(expected)
                    after
                }
            }
        }

    private fun progress(preferences: Preferences): Map<String, WatchProgress> =
        preferences[progressKey]?.let { requireNotNull(gson.fromJson<Map<String, WatchProgress>>(it, progressType)) } ?: emptyMap()
    private fun index(preferences: Preferences): Map<String, String> =
        preferences[indexKey]?.let { requireNotNull(gson.fromJson<Map<String, String>>(it, indexType)) } ?: emptyMap()
    private fun watchedItems(preferences: Preferences): List<WatchedItem> =
        preferences[watchedKey].orEmpty().map { gson.fromJson(it, WatchedItem::class.java) }
    internal fun journal(preferences: Preferences): CloudProgressJournal {
        val raw = preferences[journalKey] ?: return CloudProgressJournal()
        val objectValue = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val required = setOf("incarnation", "sequence", "cursor", "rows", "writeBases", "pending", "transmitted", "conflicts")
        require(objectValue.keySet().containsAll(required) && objectValue.keySet().all { it in required || it == "issue" })
        val state = requireNotNull(gson.fromJson(objectValue, CloudProgressJournal::class.java))
        require(java.util.UUID.fromString(state.incarnation).toString() == state.incarnation && state.sequence >= 0 && state.cursor >= 0)
        require(state.pending.size <= 1000 && state.pending.map { it.operation_id }.distinct().size == state.pending.size)
        require(state.transmitted.all { id -> state.pending.any { it.operation_id == id } })
        (state.pending + state.conflicts.values).forEach {
            require(java.util.UUID.fromString(it.operation_id).toString() == it.operation_id &&
                it.sequence in 1..state.sequence && it.expected_revision >= 0)
            it.payload.validate()
        }
        state.rows.forEach { (key, value) -> require(key == value.content_key && value.server_revision > 0); value.validate() }
        require(state.writeBases.values.all { it >= 0 })
        return state
    }

    suspend fun setResumeHidden(contentId: String, hidden: Boolean) {
        val expected = pilot.settings.value
        checkProfile(expected)
        val capture = pilot.cloudContext() ?: throw PilotRequestsStopped()
        raw(expected).edit { prefs ->
            checkProfile(expected)
            var state = journal(prefs)
            val masks = prefs[hiddenKey].orEmpty().toMutableSet()
            for ((key, progress) in progress(prefs).filterValues { it.contentId == contentId }) {
                val row = CloudProgressRow.from(capture, progress, hidden) ?: continue
                state = state.enqueue(row.copy(source_time_ms = System.currentTimeMillis()))
                if (hidden) masks.add(key) else masks.remove(key)
            }
            prefs[hiddenKey] = masks
            prefs[journalKey] = gson.toJson(state)
        }
    }

    suspend fun resetFileProgress(path: String) {
        val expected = pilot.settings.value
        checkProfile(expected)
        val capture = pilot.cloudContext() ?: throw PilotRequestsStopped()
        val elapsed = elapsedRealtimeMs()
        raw(expected).edit { prefs ->
            checkProfile(expected)
            var state = journal(prefs)
            val map = progress(prefs).toMutableMap()
            val masks = prefs[hiddenKey].orEmpty().toMutableSet()
            val clocks = prefs[clockKey]?.let { gson.fromJson<Map<String, CloudCheckpointOrder>>(it, clockType) }
                .orEmpty().toMutableMap()
            for ((key, value) in map.toMap()) {
                if (com.nuvio.tv.data.freebox.freeboxPathFromContentId(value.contentId) != path && value.contentId != path) continue
                val reset = value.copy(position = 0, progressPercent = 0f,
                    lastWatched = maxOf(System.currentTimeMillis(), value.lastWatched + 1))
                val row = CloudProgressRow.from(capture, reset) ?: continue
                state = state.enqueue(row)
                map[key] = reset
                masks.remove(key)
                clocks[key] = CloudCheckpointOrder(expected.cloudActivationId, elapsed)
            }
            prefs[progressKey] = gson.toJson(map)
            prefs[hiddenKey] = masks
            prefs[journalKey] = gson.toJson(state)
            prefs[clockKey] = gson.toJson(clocks)
            prefs[watchedKey] = map.values.filter { it.isCompleted() }.map { gson.toJson(
                WatchedItem(it.contentId, it.contentType, it.name, it.season, it.episode, it.lastWatched)) }.toSet()
        }
    }

    /** Catalog metadata only: never enqueue an old position or import a NAS snapshot. */
    suspend fun registerContent(template: WatchProgress) = registerContents(listOf(template))

    suspend fun prepareResume(id: String, season: Int?, episode: Int?) {
        val expected = pilot.settings.value
        checkProfile(expected)
        val prefs = raw(expected).data.first()
        val templates = catalogue(prefs).first
        val candidates = (templates.values + progress(prefs).values).filter {
            it.contentId == id && it.season == season && it.episode == episode
        }.distinctBy { it.storageKey }
        require(candidates.size <= 1) { "CLOUD_RESUME_IDENTITY_AMBIGUOUS" }
        val template = candidates.singleOrNull() ?: WatchProgress(id,
            if (id.startsWith("freebox:")) "freebox" else if (season != null && episode != null) "series" else "movie",
            "", null, null, null, id, season, episode, null, 0, 0, 1)
        checkProfile(expected)
        refreshContent(template)
    }

    suspend fun refreshContent(template: WatchProgress) {
        val expected = pilot.settings.value
        checkProfile(expected)
        registerContent(template)
        val capture = pilot.cloudContext() ?: return
        val key = capture.contentKey(template.storageKey)
        sync(targetKey = key, expectedSource = expected)
        checkProfile(expected)
        raw(expected).edit { prefs ->
            checkProfile(expected)
            val state = journal(prefs)
            // A foreign background pull must not rebase the next active-player checkpoint.
            prefs[journalKey] = gson.toJson(state.copy(writeBases = state.writeBases +
                (key to (state.rows[key]?.server_revision ?: 0))))
        }
    }

    suspend fun registerContents(templates: Collection<WatchProgress>) {
        val expected = pilot.settings.value
        if (currentIdentity() == null || templates.isEmpty()) return
        val capture = pilot.cloudContext() ?: return
        raw(expected).edit { prefs ->
            checkProfile(expected)
            val previous = progress(prefs)
            val map = previous.toMutableMap()
            val (previousTemplates, truncated) = catalogue(prefs)
            val cachedTemplates = LinkedHashMap(previousTemplates)
            val previousIndex = index(prefs)
            val identities = previousIndex.toMutableMap()
            for (template in templates) {
                val key = CloudProgressRow.from(capture, template.copy(position = 0, duration = 0,
                    progressPercent = null, lastWatched = 1), includeMetadata = false)?.content_key ?: continue
                val old = map[template.storageKey]
                if (old == null) cachedTemplates[template.storageKey] =
                    template.copy(position = 0, duration = 0, progressPercent = null, lastWatched = 1)
                else {
                    cachedTemplates.remove(template.storageKey)
                    map[template.storageKey] = old.copy(name = template.name.takeIf { it.isNotBlank() } ?: old.name,
                        poster = template.poster ?: old.poster, backdrop = template.backdrop ?: old.backdrop,
                        logo = template.logo ?: old.logo, description = template.description ?: old.description)
                }
                identities[key] = template.storageKey
            }
            if (!truncated && map == previous && cachedTemplates == previousTemplates && identities == previousIndex) return@edit
            // Resolve received rows before evicting unplayed catalogue entries.
            val projected = project(prefs, journal(prefs), map, cachedTemplates, identities)
            cachedTemplates.keys.removeAll(projected.keys)
            while (cachedTemplates.size > CLOUD_CATALOG_TEMPLATE_LIMIT) cachedTemplates.remove(cachedTemplates.keys.first())
            val retained = (identities + index(prefs)).filterValues { it in projected || it in cachedTemplates }
            if (truncated || cachedTemplates != previousTemplates) prefs[templatesKey] = gson.toJson(cachedTemplates)
            if (retained != previousIndex) prefs[indexKey] = gson.toJson(retained)
        }
    }

    /** Old catalogues can be megabytes long; never materialize the entire map just to trim it. */
    private fun catalogue(prefs: Preferences): Pair<LinkedHashMap<String, WatchProgress>, Boolean> {
        val result = linkedMapOf<String, WatchProgress>()
        val json = prefs[templatesKey] ?: return result to false
        var truncated = false
        gson.newJsonReader(StringReader(json)).use { reader ->
            if (reader.peek() == JsonToken.NULL) return result to false
            val adapter = gson.getAdapter(WatchProgress::class.java)
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val value = adapter.read(reader) ?: continue
                result[key] = value
                if (result.size > CLOUD_CATALOG_TEMPLATE_LIMIT) {
                    result.remove(result.keys.first()); truncated = true
                }
            }
            reader.endObject()
        }
        return result to truncated
    }

    private fun trimCatalogue(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val (templates, truncated) = catalogue(prefs)
        if (!truncated) return
        val saved = progress(prefs)
        prefs[templatesKey] = gson.toJson(templates)
        prefs[indexKey] = gson.toJson(index(prefs).filterValues { it in saved || it in templates })
    }

    /** Apply only accepted rows; the technical queue is never a resume source. */
    private fun project(prefs: androidx.datastore.preferences.core.MutablePreferences, state: CloudProgressJournal,
                        metadata: Map<String, WatchProgress>? = null,
                        templates: Map<String, WatchProgress> = catalogue(prefs).first,
                        identities: Map<String, String> = index(prefs)): Map<String, WatchProgress> {
        val previous = progress(prefs)
        val map = (metadata ?: previous).toMutableMap()
        val hidden = prefs[hiddenKey].orEmpty().toMutableSet()
        val resolvedIdentities = identities.toMutableMap()
        val capture = pilot.cloudContext()
        for ((key, row) in state.rows) {
            if (state.pending.any { it.payload.content_key == key }) continue
            val received = capture?.let { CloudResumeMetadata.open(it, row) }
            val localKey = received?.storageKey ?: identities[key] ?: continue
            val local = map[localKey] ?: templates[localKey] ?: received ?: continue
            resolvedIdentities[key] = localKey
            map[localKey] = local.copy(position = row.position_ms,
                duration = row.duration_ms, progressPercent = when (row.watch_state) {
                    "completed" -> 100f; "unseen" -> 0f; else -> row.progress_percent
                },
                lastWatched = row.received_time_ms.takeIf { it > 0 } ?: row.source_time_ms)
            if (row.resume_hidden) hidden.add(localKey) else hidden.remove(localKey)
        }
        if (resolvedIdentities != index(prefs)) prefs[indexKey] = gson.toJson(resolvedIdentities)
        if (hidden != prefs[hiddenKey].orEmpty()) prefs[hiddenKey] = hidden
        if (map != previous) {
            prefs[progressKey] = gson.toJson(map)
            prefs[watchedKey] = map.values.filter { it.isCompleted() }.map {
                gson.toJson(WatchedItem(it.contentId, it.contentType, it.name, it.season, it.episode, it.lastWatched))
            }.toSet()
        }
        return map
    }

    /** Bounded foreground/manual refresh. HTTP failures retain cloud mode and its local cache. */
    internal suspend fun sync(force: Boolean = false, targetKey: String? = null,
                              expectedSource: PilotLocalSettings? = null): Result<Unit> {
        var attempt: PilotLocalSettings? = null
        var deferred = false
        return try {
        withTimeout(20_000) { io.withLock {
            if (expectedSource != null) checkIdentity(expectedSource)
            val expected = pilot.settings.value
            if (currentIdentity() == null) return@withLock
            attempt = expected
            checkIdentity(expected)
            if (!force && expected.nextAttemptMs > System.currentTimeMillis()) {
                deferred = true
                return@withLock
            }
            val bound = requireNotNull(expected.scope)
            checkProfile(expected)
            val data = raw(expected)
            var session = requireNotNull(pilot.settings.value.session)
            val endpoint = requireNotNull(expected.endpoint)
            val capture = pilot.cloudContext() ?: throw PilotRequestsStopped()
            val permit = pilot.cloudRequestPermit(capture)
            if (session.expiresAtSeconds <= System.currentTimeMillis() / 1000 + 60) {
                val refreshed = api.refreshSession(endpoint, session, permit)
                checkIdentity(expected)
                pilot.replaceSession(session, refreshed)
                session = refreshed
            }
            // Profile incarnation comes from the original NAS namespace, never from the cloud cache.
            var sent = emptyList<CloudProgressOperation>()
            var incarnation = ""
            data.edit { prefs ->
                checkIdentity(expected)
                if (checkedCatalogueIdentity != identity(expected)) trimCatalogue(prefs)
                val latest = journal(prefs)
                sent = latest.batch()
                incarnation = latest.incarnation
                if (sent.isNotEmpty())
                    prefs[journalKey] = gson.toJson(latest.copy(transmitted = latest.transmitted + sent.map { it.operation_id }))
            }
            checkedCatalogueIdentity = identity(expected)
            if (sent.isNotEmpty()) {
                val acks = api.writeProgressChanges(endpoint, session, bound, expected.cloudKeyEpoch,
                    incarnation, sent, permit)
                data.edit { prefs ->
                    checkIdentity(expected)
                    val next = journal(prefs).acknowledge(sent, acks)
                    prefs[journalKey] = gson.toJson(next)
                    project(prefs, next)
                }
            }
            val state = journal(data.data.first())
            val page = api.readProgressChanges(endpoint, session, bound, expected.cloudKeyEpoch, state.cursor, permit, targetKey)
            data.edit { prefs ->
                checkIdentity(expected)
                val previous = journal(prefs)
                val next = previous.receive(page)
                if (next != previous) {
                    prefs[journalKey] = gson.toJson(next)
                    project(prefs, next)
                }
            }
            checkIdentity(expected)
            retryDelayMs = 0
            pilot.recordDelivery(null, acknowledged = true)
        } }
        if (deferred) Result.failure(IllegalStateException("CLOUD_RETRY_DEFERRED")) else Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            recordFailure(attempt, "CLOUD_SYNC_TIMEOUT")
        } catch (error: Exception) {
            recordFailure(attempt, if (error is PilotHttpFailure && error.status in setOf(401,403))
                "CLOUD_AUTH_REQUIRED" else "CLOUD_SYNC_DEFERRED")
        }
    }

    private fun recordFailure(expected: PilotLocalSettings?, code: String): Result<Unit> {
        if (expected != null) runCatching {
            checkIdentity(expected)
            retryDelayMs = if (retryDelayMs == 0L) 5_000 else (retryDelayMs * 2).coerceAtMost(120_000)
            pilot.recordDelivery(code, retryAtMs = System.currentTimeMillis() + retryDelayMs)
        }
        return Result.failure(IllegalStateException(code))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeJournal(): Flow<CloudProgressJournal> = pilot.settings.distinctUntilChanged { a, b ->
        a.mode == b.mode && a.cloudActivationId == b.cloudActivationId && a.scope == b.scope
    }.flatMapLatest {
        if (it.mode == PilotMode.SUPABASE_PROGRESS && it.scope != null) raw(it).data.map(::journal)
        else kotlinx.coroutines.flow.flowOf(CloudProgressJournal(incarnation = ""))
    }

    /** Explicit conflict resolution never runs as part of a retry. */
    internal suspend fun firstConflict(): CloudConflictChoice? {
        val expected = pilot.settings.value
        checkProfile(expected)
        val prefs = raw(expected).data.first()
        val state = journal(prefs)
        val entry = state.conflicts.entries.firstOrNull() ?: return null
        val template = index(prefs)[entry.key]?.let { progress(prefs)[it] }
        val title = template?.name?.takeIf { it.isNotBlank() } ?: "Contenu non résolu"
        return CloudConflictChoice(entry.value.operation_id, state.rows[entry.key]?.server_revision ?: 0,
            "$title\nPosition locale : ${entry.value.payload.position_ms / 1000} s\n" +
                "Position Supabase : ${(state.rows[entry.key]?.position_ms ?: 0) / 1000} s")
    }

    internal suspend fun resolveFirstConflict(useLocal: Boolean, expectedChoice: CloudConflictChoice? = null) {
        val expected = pilot.settings.value
        checkIdentity(expected)
        raw(expected).edit { prefs ->
            checkProfile(expected)
            var state = journal(prefs)
            val entry = state.conflicts.entries.firstOrNull() ?: return@edit
            if (expectedChoice != null && (entry.value.operation_id != expectedChoice.operationId ||
                    (state.rows[entry.key]?.server_revision ?: 0) != expectedChoice.remoteRevision)) throw CloudConflictChanged()
            state = state.copy(conflicts = state.conflicts - entry.key, writeBases = state.writeBases +
                (entry.key to (state.rows[entry.key]?.server_revision ?: 0)))
            if (useLocal) state = state.enqueue(entry.value.payload)
            prefs[journalKey] = gson.toJson(state)
            project(prefs, state)
        }
    }
}
