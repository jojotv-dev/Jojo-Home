package com.jojotv.home.progresspilot

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.jojotv.home.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.NasSyncSessionDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProgressPilotEntryPoint {
    fun runtime(): SupabaseProgressPilotRuntime
    fun store(): SupabaseProgressPilotStore
    fun preferences(): WatchProgressPreferences
    fun profiles(): ProfileManager
}

internal class SupabaseForegroundState {
    private val activities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private val visible = MutableStateFlow(false)
    val state: kotlinx.coroutines.flow.StateFlow<Boolean> = visible

    @Synchronized fun started(activity: Any) { activities.add(activity); visible.value = activities.isNotEmpty() }
    @Synchronized fun stopped(activity: Any) { activities.remove(activity); visible.value = activities.isNotEmpty() }
}

/** Register in Application.onCreate, before asynchronous runtime initialization can miss an Activity. */
internal object SupabaseAppForeground {
    private val registered = AtomicBoolean(false)
    private val activities = SupabaseForegroundState()
    val state get() = activities.state

    fun start(application: android.app.Application) {
        if (!registered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { activities.started(activity) }
            override fun onActivityStopped(activity: android.app.Activity) { activities.stopped(activity) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SupabaseProgressPilotRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SupabaseProgressPilotStore,
    private val api: SupabaseProgressApi,
    private val preferences: WatchProgressPreferences,
    private val nasSession: NasSyncSessionDataStore,
    private val profiles: ProfileManager,
    private val cloud: SupabaseProgressSyncStore
) {
    private val queue = preferences.pilotDeliveryQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        // A failed pilot observer must never terminate the NAS/Companion application.
        runCatching {
            if (store.settings.value.mode == PilotMode.SUPABASE_PROGRESS) store.recordDelivery("CLOUD_OBSERVER_FAILED")
            else disable("PILOT_OBSERVER_FAILED")
        }
    })
    private val started = AtomicBoolean(false)
    private val nasReady = MutableStateFlow(false)
    private val deliveryMutex = Mutex()
    private val schedulingMutex = Mutex()

    private val access = object : PilotDeliveryAccess {
        override fun current() = store.captureContext()
        override fun endpoint() = store.settings.value.endpoint ?: error("Configuration absente.")
        override fun session() = store.settings.value.session ?: error("Session absente.")
        override fun requestPermit(context: PilotCaptureContext) = store.requestPermit(context)
        override fun registeredSessionId() = store.settings.value.registeredSessionId
        override fun nextAttemptMs() = store.settings.value.nextAttemptMs
        override fun replaceSession(previous: PilotAuthSession, next: PilotAuthSession) = store.replaceSession(previous, next)
        override fun registered(scope: PilotScope, sessionId: String) = store.markRegistered(scope, sessionId)
        override fun delivered() = store.recordDelivery(null, acknowledged = true)
        override fun retry(code: String, retryAtMs: Long) = store.recordDelivery(code, retryAtMs)
        override fun pause(code: String) { disable(code) }
    }
    private val engine = PilotDeliveryEngine(access, queue, api)

    fun start() {
        if (!BuildConfig.JOJO_PROGRESS_PILOT_COMPILED || !started.compareAndSet(false, true)) return
        scope.launch { profiles.activeProfileId.collect { store.cloudProfileChanged(it) } }
        scope.launch {
            nasSession.sessionFlow.catch {
                if (store.settings.value.mode != PilotMode.SUPABASE_PROGRESS)
                    runCatching { disable("NAS_IDENTITY_UNAVAILABLE") }
                nasReady.value = true
            }.collect { session ->
                try {
                    store.updateNasAuthority(session.baseUrl, session.email, !session.token.isNullOrBlank())
                } catch (_: Exception) {
                    runCatching { disable("LOCAL_STORE_FAILED") }
                } finally { nasReady.value = true }
            }
        }
        scope.launch {
            store.settings.map { it.mode to it.scope?.epoch }.distinctUntilChanged().flatMapLatest { (mode, _) ->
                if (mode == PilotMode.NAS_WITH_SUPABASE_MIRROR) {
                    val profileId = store.settings.value.scope?.localProfileId
                    if (profileId == null) flowOf(PilotJournal()) else queue.observe(profileId).catch {
                        runCatching { disable("OUTBOX_UNREADABLE") }
                        emit(PilotJournal(issue = "OUTBOX_UNREADABLE"))
                    }
                } else {
                    if (mode == PilotMode.NAS_ONLY) cancelPilotWork()
                    flowOf(PilotJournal())
                }
            }.debounce(1000).collect { journal ->
                if (journal.issue != null && store.settings.value.mode == PilotMode.NAS_WITH_SUPABASE_MIRROR)
                    runCatching { disable(journal.issue) }
                else if (journal.pending.isNotEmpty() && store.settings.value.mode == PilotMode.NAS_WITH_SUPABASE_MIRROR)
                    scheduleFlush()
            }
        }
        scope.launch {
            combine(store.settings.map { Triple(it.mode, it.cloudActivationId, it.scope) }.distinctUntilChanged(),
                profiles.activeProfileId, SupabaseAppForeground.state) { selected, profile, visible -> Triple(selected, profile, visible) }
                .collectLatest { (selected, profile, visible) ->
                    if (!visible || selected.first != PilotMode.SUPABASE_PROGRESS || selected.third?.localProfileId != profile) return@collectLatest
                    while (currentCoroutineContext().isActive) {
                        cloud.sync()
                        delay(5_000)
                    }
                }
        }
        scope.launch {
            cloud.observeJournal().map { it.pending.map { op -> op.operation_id } }.distinctUntilChanged()
                .debounce(1_000).collect { pending -> if (pending.isNotEmpty()) scheduleFlush() }
        }
    }

    suspend fun awaitLocalIdentity() {
        start()
        nasReady.first { it }
        profiles.activeProfileReady.first { it }
    }

    suspend fun signIn(endpoint: PilotEndpoint, email: String, password: String) = withContext(Dispatchers.IO) {
        val permit = store.authorizeSignIn(endpoint)
        try { store.saveSession(endpoint, api.signIn(endpoint, email, password, permit)) }
        finally { store.finishRequest(permit) }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        disable()
        val saved = store.settings.value
        try {
            if (saved.endpoint != null && saved.session != null) {
                val permit = store.authorizeSignOut(saved.endpoint, saved.session)
                try { api.signOutCurrentSession(saved.endpoint, saved.session, permit) }
                finally { store.finishRequest(permit) }
            }
        } finally { store.clearSession() }
    }

    suspend fun activate() = withContext(Dispatchers.IO) {
        awaitLocalIdentity()
        val bound = store.settings.value.scope ?: error("Associe le profil et les deux appareils.")
        require(profiles.profiles.value.any { it.id == bound.localProfileId } &&
            queue.profileInstanceId(bound.localProfileId) == bound.localProfileInstanceId) {
            "Le profil a été supprimé ou recréé ; l'association doit être revue."
        }
        val journal = queue.observe(bound.localProfileId).first()
        require(journal.issue == null && (journal.epoch.isEmpty() || journal.epoch == bound.epoch)) {
            "La file du pilote nécessite une revue avant activation."
        }
        store.activate()
    }

    suspend fun activateCloud(keyEpoch: String) = withContext(Dispatchers.IO) {
        profiles.activeProfileReady.first { it }
        val bound = requireNotNull(store.settings.value.scope)
        require(profiles.activeProfileId.value == bound.localProfileId &&
            queue.profileInstanceId(bound.localProfileId) == bound.localProfileInstanceId)
        val expected = store.settings.value
        expected.validateCloudGeneration(keyEpoch.trim())
        val permit = store.authorizeCloudProbe(expected)
        try {
            var session = requireNotNull(expected.session)
            val endpoint = requireNotNull(expected.endpoint)
            if (session.expiresAtSeconds <= System.currentTimeMillis() / 1000 + 60) {
                val refreshed = api.refreshSession(endpoint, session, permit)
                store.replaceSession(session, refreshed)
                session = refreshed
            }
            // Verify migration, enabled scope, session and RLS before changing the local source.
            // This page is deliberately not applied to either local cache during the probe.
            api.readProgressChanges(endpoint, session, bound, keyEpoch.trim(), 0, permit)
            require(profiles.activeProfileId.value == bound.localProfileId &&
                queue.profileInstanceId(bound.localProfileId) == bound.localProfileInstanceId)
            store.activateCloud(keyEpoch.trim(), expected)
        } finally { store.finishRequest(permit) }
    }

    internal suspend fun firstCloudConflict() = cloud.firstConflict()
    internal suspend fun resolveCloudConflict(useLocal: Boolean, expected: CloudConflictChoice) {
        cloud.resolveFirstConflict(useLocal, expected); scheduleFlush()
    }
    internal fun cloudJournal() = cloud.observeJournal()

    /** Memory-only import remains bound to the enrolled account, installation, NAS and active profile. */
    internal suspend fun keyImportScope(): PilotScope {
        awaitLocalIdentity()
        val expected = store.settings.value
        val bound = store.keyScope(expected)
        if (!profiles.activeProfileReady.value || profiles.activeProfileId.value != bound.localProfileId ||
            profiles.profiles.value.none { it.id == bound.localProfileId })
            throw PilotKeyCheckBlocked(PilotKeyCheckIssue.PROFILE_CHANGED)
        return bound
    }

    internal suspend fun keyCheckScope(): PilotScope {
        val bound = keyImportScope()
        bound.checkKeyProfileInstance(queue.profileInstanceId(bound.localProfileId))
        return bound
    }

    internal suspend fun confirmKey(challenge: String, reference: String) = withContext(Dispatchers.IO) {
        deliveryMutex.withLock {
            val bound = keyCheckScope()
            val expected = store.settings.value
            val journal = queue.observe(bound.localProfileId).first()
            require(keyCheckScope() == bound)
            store.confirmKey(expected, journal, challenge, reference)
        }
    }

    internal suspend fun repairKey(candidate: String, challenge: String, reference: String,
                                    recoverProfileInstance: Boolean = false) = withContext(Dispatchers.IO) {
        deliveryMutex.withLock {
            val bound = if (recoverProfileInstance) keyImportScope() else keyCheckScope()
            val expected = store.settings.value
            val journal = queue.observe(bound.localProfileId).first()
            expected.validateKeyRepairJournal(journal)
            store.candidateKeyMatches(expected, candidate, challenge, reference) // Before creating any marker.
            if (recoverProfileInstance) require(bound.localProfileId == 1) { "La réassociation d'un profil secondaire exige une revue." }
            if (expected.keyRepairFromEpoch.isNotEmpty())
                bound.checkKeyProfileInstance(queue.profileInstanceId(bound.localProfileId))
            val instance = if (recoverProfileInstance) preferences.getOrCreatePilotProfileInstanceId(bound.localProfileId)
                else bound.localProfileInstanceId
            require(keyImportScope() == bound)
            require(queue.profileInstanceId(bound.localProfileId) == instance)
            val prepared = store.beginKeyRepair(expected, journal, candidate, challenge, reference, instance)
            if (prepared.keyRepairFromEpoch.isNotEmpty()) {
                cancelPilotWork()
                val repaired = queue.repairKeyEpoch(requireNotNull(prepared.scope),
                    prepared.keyRepairFromEpoch, prepared.keyRepairSequence)
                require(keyCheckScope() == prepared.scope)
                store.finishKeyRepair(prepared, repaired, challenge, reference)
            }
        }
    }

    fun disable(issue: String? = null) {
        try { store.disable(issue) } finally { cancelPilotWork() }
    }

    fun scheduleFlush() {
        if (!BuildConfig.JOJO_PROGRESS_PILOT_COMPILED || store.settings.value.mode == PilotMode.NAS_ONLY) return
        scope.launch {
            schedulingMutex.withLock {
                try {
                    if (store.settings.value.mode == PilotMode.NAS_ONLY) return@withLock
                    val manager = WorkManager.getInstance(context)
                    val existing = manager.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS)
                    if (existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED })
                        return@withLock
                    val request = OneTimeWorkRequestBuilder<SupabaseProgressPilotWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag(WORK_TAG).build()
                    if (store.settings.value.mode == PilotMode.NAS_ONLY) return@withLock
                    // At most one follow-up behind a running worker; offline checkpoints coalesce in the file.
                    manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                } catch (_: Exception) {
                    runCatching { store.recordDelivery("SCHEDULING_FAILED") }
                }
            }
        }
    }

    internal suspend fun flushPending(): PilotDeliveryResult = withContext(Dispatchers.IO) {
        if (store.settings.value.mode == PilotMode.SUPABASE_PROGRESS) {
            profiles.activeProfileReady.first { it }
            return@withContext if (cloud.sync().isSuccess) PilotDeliveryResult.DONE else PilotDeliveryResult.RETRY
        }
        awaitLocalIdentity()
        val bound = store.settings.value.scope
        if (bound != null && profiles.profiles.value.none { it.id == bound.localProfileId }) {
            disable("PROFILE_ASSOCIATION_CHANGED")
            return@withContext PilotDeliveryResult.STOPPED
        }
        deliveryMutex.withLock { engine.flush() }
    }

    internal suspend fun hasPending(): Boolean {
        if (store.settings.value.mode == PilotMode.SUPABASE_PROGRESS)
            return cloud.currentIdentity() != null && cloud.observeJournal().first().pending.isNotEmpty()
        val selected = store.settings.value.scope ?: return false
        return queue.observe(selected.localProfileId).first().pending.isNotEmpty()
    }

    private fun cancelPilotWork() { WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG) }

    companion object {
        private const val WORK_NAME = "jojo_progress_pilot_delivery_v1"
        private const val WORK_TAG = "jojo_progress_pilot_only"
    }
}

class SupabaseProgressPilotWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) return Result.success()
        val runtime = EntryPointAccessors.fromApplication(applicationContext, ProgressPilotEntryPoint::class.java).runtime()
        return when (runtime.flushPending()) {
            PilotDeliveryResult.RETRY -> Result.retry()
            PilotDeliveryResult.STOPPED -> Result.success()
            PilotDeliveryResult.DONE -> {
                if (runtime.hasPending()) runtime.scheduleFlush()
                Result.success()
            }
        }
    }
}
