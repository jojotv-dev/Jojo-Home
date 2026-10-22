package com.jojotv.home.progresspilot

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import com.jojotv.home.iptv.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint @InstallIn(SingletonComponent::class)
internal interface AccountContentEntryPoint { fun accountContent(): SupabaseAccountContentRuntime }
internal class AccountContentSeedPreview(val identity: String, val generation: String,
    val values: Map<String, String>, val inventory: String) {
    override fun toString() = "AccountContentSeedPreview(data=redacted)"
}

object AccountContentRouting {
    @JvmStatic fun selected(context: Context): Boolean = context.getSharedPreferences(AccountSettingsRouting.PREFS,
        Context.MODE_PRIVATE).getBoolean("content", false)
    internal fun runtime(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext,
        AccountContentEntryPoint::class.java).accountContent()
    @JvmStatic fun artworkChanged(context: Context) {
        IptvStore.homeArtworkChanged()
        if (selected(context)) runtime(context).kick()
    }
}

/** Independent from playback and the NAS transport. All local alarms remain on their creator. */
@Singleton internal class SupabaseAccountContentRuntime @Inject constructor(
    @ApplicationContext private val context: Context, private val storage: SupabaseAccountStore,
    private val auth: SupabaseProgressPilotStore, private val api: SupabaseProgressApi,
    private val profiles: ProfileManager, private val watch: WatchProgressPreferences
) {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private val recordingSignals = ConcurrentHashMap<String, IptvCloudPlan>()
    @Volatile private var nextAutoAttempt = 0L
    private val permits = ConcurrentHashMap.newKeySet<PilotRequestPermit>()
    private val feedback = MutableStateFlow("Favoris, historique et programmations : source actuelle conservée.")
    val message = feedback.asStateFlow()
    val state get() = storage.state.value.content
    internal fun installationId() = storage.state.value.installation
    private val gson = Gson()
    private var receiver: Pair<AccountPairReceiver, AccountPairRequest>? = null
    private val endpoint = PilotEndpoint("https://jtdnwdbapkdkpgtceauh.supabase.co", "sb_publishable_MsqNmrTTNFEB5wLu2KNa-Q_AK6w8Wc4")
    private val store get() = IptvStore(context).apply { load() }

    fun start() {
        if (!started.compareAndSet(false, true) || !com.jojotv.home.BuildConfig.JOJO_PROGRESS_PILOT_COMPILED) return
        jobs.launch { SupabaseAppForeground.state.collect { visible -> if (visible) kick() } }
        jobs.launch { while (true) {
            if (SupabaseAppForeground.state.value &&
                state.selected && automaticAllowed()) sync()
            delay(15_000)
        } }
        if (state.selected) schedule()
    }
    private fun manualRetry() = state.issue in setOf("ACCOUNT_CONTENT_HTTP_400", "ACCOUNT_CONTENT_HTTP_401",
        "ACCOUNT_CONTENT_HTTP_403", "ACCOUNT_CONTENT_HTTP_404", "ACCOUNT_CONTENT_KEY_CHANGED", "ACCOUNT_CONTENT_REMOTE_KEY_MISMATCH")
    private fun automaticAllowed() = !manualRetry() && android.os.SystemClock.elapsedRealtime() >= nextAutoAttempt
    internal fun requiresManualRetry() = manualRetry()
    internal fun retryDue() = android.os.SystemClock.elapsedRealtime() >= nextAutoAttempt
    fun kick() { if (state.selected && automaticAllowed()) {
        requested.set(true)
        if (draining.compareAndSet(false, true)) jobs.launch {
            try {
                do {
                    requested.set(false); delay(300)
                    if (!state.selected || !automaticAllowed() || !sync()) break
                } while (requested.get())
            } finally { draining.set(false); if (requested.get() && state.selected && automaticAllowed()) kick() }
        }
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK + "-flush", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AccountContentWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()) }
    } }
    internal fun recordingChanged(recording: IptvRecording) {
        val plan = IptvCloudPlan.from(recording)
        if (recordingSignals.size > 2000) recordingSignals.clear()
        if (recordingSignals.put(recording.id, plan) != plan) kick()
    }
    internal fun recordingRemoved(id: String) { recordingSignals.remove(id); kick() }

    private fun credentials() = storage.state.value.let { mapOf("p_device" to it.installation, "p_proof" to it.proof) }
    private fun identity(): String {
        val s = storage.state.value; val account = auth.settings.value
        require(s.selected && s.restored && s.initialized && s.owner == account.session?.ownerId && s.project == endpoint.url &&
            account.endpoint == endpoint) { "ACCOUNT_RESTORE_REQUIRED" }
        return "${s.owner}/${s.installation}/${s.generation}/${account.session!!.sessionId}"
    }
    private fun crypto(): AccountContentCrypto {
        val s = storage.state.value; val key = auth.accountContentKey(s.owner)
        return try { AccountContentCrypto(key, s.owner) } finally { key.fill(0) }
    }
    private suspend fun rpc(name: String, body: Map<String, Any>, expected: String, budgetMs: Long? = null): String {
        check(identity() == expected)
        var session = requireNotNull(auth.settings.value.session)
        val permit = auth.accountRequest(endpoint, session); permits += permit
        // A recording preflight has one global deadline. Revoking its permit cancels
        // blocking OkHttp too; a coroutine timeout alone would not interrupt it.
        val deadline = budgetMs?.let { budget -> jobs.launch { delay(budget.coerceAtLeast(1)); auth.finishRequest(permit) } }
        return try {
            if (session.expiresAtSeconds < System.currentTimeMillis() / 1000 + 90) {
                val next = api.refreshSession(endpoint, session, permit); auth.replaceSession(session, next); session = next
            }
            check(identity() == expected)
            api.accountRpc(endpoint, session, name, body, permit).also { check(identity() == expected) }
        } finally { deadline?.cancel(); permits -= permit; auth.finishRequest(permit) }
    }

    /** Selection never imports earlier NAS activity or schedules. Their explicit publication is separate. */
    suspend fun select() = withContext(Dispatchers.IO) { mutex.withLock {
        val identity = identity(); val local = store
        if (state.selected) return@withLock
        recordingSignals.clear(); nextAutoAttempt = 0
        require(local.accounts().all { a -> storage.state.value.bindings.any { it.domain == "iptv" && it.localId == a.id && it.instance == a.id } }) {
            "ACCOUNT_PROFILE_LINK_REQUIRED"
        }
        crypto().use { crypto ->
            require(state.keyTag.isEmpty() || state.keyTag == crypto.keyTag()) { "ACCOUNT_CONTENT_KEY_CHANGED" }
            // Probe the approved schema before changing a local route; ignore this page until selection is durable.
            val probe = AccountContentProtocol.page(rpc("account_content_read", credentials() + mapOf("p_after" to 0L), identity))
            probe.rows.filterNot { it.value.deleted }.forEach { crypto.open(it.value) }
            val baseline = snapshot(local, crypto).mapValues { fingerprint(it.key, it.value, crypto) }
            storage.update { it.copy(content = it.content.copy(selected = true, generation = UUID.randomUUID().toString(),
                keyTag = crypto.keyTag(), initialized = true, observed = baseline, issue = null)) }
        }
        schedule()
    }; sync() }

    fun stop() {
        storage.update { it.copy(content = it.content.copy(selected = false, generation = UUID.randomUUID().toString())) }
        permits.toList().forEach(auth::finishRequest); permits.clear()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(WORK + "-flush")
        feedback.value = "Synchronisation IPTV cloud arrêtée. File conservée ; aucun renvoi automatique vers le NAS."
    }
    internal fun progressSelected() {
        require(auth.settings.value.mode == PilotMode.SUPABASE_PROGRESS)
        feedback.value = "Progression Supabase sélectionnée sur le profil associé. Aucun historique NAS importé."
    }

    /** Fetch pending commands before a local alarm, without waiting for a busy sync or an unavailable cloud. */
    internal fun preflight() = runBlocking(Dispatchers.IO) {
        if (!state.selected || manualRetry() || !mutex.tryLock()) { kick(); return@runBlocking }
        val generation = state.generation; val startedAt = android.os.SystemClock.elapsedRealtime()
        fun remaining() = (3000 - (android.os.SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
        var expected = ""
        fun valid() = state.selected && state.generation == generation && remaining() > 0 && runCatching { identity() == expected }.getOrDefault(false)
        try {
            expected = identity()
            crypto().use { crypto ->
                require(crypto.keyTag() == state.keyTag)
                do {
                    check(valid())
                    val page = AccountContentProtocol.page(rpc("account_content_read", credentials() + mapOf("p_after" to state.journal.cursor), expected, remaining()))
                    check(valid()); storage.update { it.copy(content = it.content.copy(journal = it.content.journal.receive(page))) }
                    if (page.next_cursor == page.head_revision) break
                } while (valid())
                if (valid()) applyCommands(store, crypto, ::valid)
            }
        } catch (_: Exception) {
            // Local scheduling remains authoritative. No unconfirmed cancellation is reported as applied.
        } finally { mutex.unlock(); kick() }
    }

    private fun profile(account: String) = storage.state.value.bindings.singleOrNull {
        it.domain == "iptv" && it.localId == account && it.instance == account
    }?.cloudId
    private fun localProfile(cloud: String) = storage.state.value.bindings.singleOrNull {
        it.domain == "iptv" && it.cloudId == cloud && it.instance == it.localId
    }?.localId

    private fun snapshot(local: IptvStore, crypto: AccountContentCrypto,
                         activity: List<IptvCloudActivity> = local.supabaseActivitySnapshot()): Map<String, String> = buildMap {
        activity.forEach { entry ->
            val profile = profile(entry.account) ?: return@forEach
            val key = crypto.id(profile, entry.item.id)
            val item = IptvCloudItem.from(entry.item)
            val artwork = cloudArtworkChoices(context, item)
            if (entry.favorite) put("$profile/favorite/$key", cloudPlayback(IptvCloudPlayback(item, artwork = artwork)))
            if (entry.playedAt > 0) put("$profile/history/$key", cloudPlayback(IptvCloudPlayback(item, entry.playedAt, artwork)))
        }
        local.recordings(true).forEach { recording ->
            val profile = profile(local.recordingAccount(recording)) ?: return@forEach
            val id = "$profile/plan/${recording.id}"
            val existing = state.journal.rows[id]?.value ?: state.journal.desired[id]
            if (existing == null && recording.state !in setOf(RecordingState.PLANNED, RecordingState.RUNNING)) return@forEach
            if (existing != null && existing.creator != storage.state.value.installation) return@forEach
            put(id, cloudPlan(IptvCloudPlan.from(recording)))
        }
    }
    private fun value(token: String, clear: String?, crypto: AccountContentCrypto): AccountContentValue {
        val parts = token.split('/'); require(parts.size == 3 && parts[1] != "command")
        val row = AccountContentValue(parts[0], parts[1], parts[2], creator = if (parts[1] == "plan") storage.state.value.installation else "",
            deleted = clear == null)
        return if (clear == null) row.also { it.validate() } else crypto.seal(row, clear)
    }
    private fun capture(local: IptvStore, crypto: AccountContentCrypto, publishExisting: Boolean = false) {
        val current = snapshot(local, crypto); val fingerprints = current.mapValues { fingerprint(it.key, it.value, crypto) }
        storage.update { s ->
            var journal = s.content.journal
            (if (publishExisting) current.keys else s.content.observed.keys + current.keys).forEach { id ->
                if (publishExisting || s.content.observed[id] != fingerprints[id]) journal = journal.change(value(id, current[id], crypto))
            }
            s.copy(content = s.content.copy(observed = fingerprints, journal = journal))
        }
    }
    private fun fingerprint(token: String, clear: String, crypto: AccountContentCrypto): String =
        crypto.fingerprint(if (token.split('/')[1] in setOf("favorite", "history")) cloudPlaybackTracked(clear) else clear)
    internal suspend fun seedPreview(): AccountContentSeedPreview = withContext(Dispatchers.IO) {
        val expected = identity(); val initial = state; require(initial.selected)
        // This read-only review must not queue behind HTTP and remote restoration.
        // Publication still takes the mutex and compares the exact reviewed values.
        crypto().use { require(it.keyTag() == initial.keyTag)
            val values = snapshot(store, it).toMap()
            require(identity() == expected && state.selected && state.generation == initial.generation && state.keyTag == initial.keyTag) {
                "ACCOUNT_SEED_PREVIEW_CHANGED"
            }
            AccountContentSeedPreview(expected, initial.generation, values, inventory(values)) }
    }
    internal suspend fun publishExisting(preview: AccountContentSeedPreview) = withContext(Dispatchers.IO) { mutex.withLock {
        require(state.selected && identity() == preview.identity && state.generation == preview.generation)
        crypto().use { require(it.keyTag() == state.keyTag && snapshot(store, it) == preview.values) { "ACCOUNT_SEED_PREVIEW_CHANGED" }
            capture(store, it, true) }
    }; sync() }
    private fun inventory(values: Map<String, String>): String {
        val kinds = values.keys.map { it.split('/')[1] }
        return "${kinds.count { it == "favorite" }} favori(s), ${kinds.count { it == "history" }} lecture(s) et " +
            "${kinds.count { it == "plan" }} programmation(s) locales. " +
            "Les références de contenu et titres seront chiffrés. Aucun flux, URL, chemin, mot de passe ou fichier vidéo ne sera copié."
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (!state.selected) return@withLock true
        val generation = state.generation
        var expected = ""
        fun valid() = state.selected && state.generation == generation && runCatching { identity() == expected }.getOrDefault(false)
        try {
            expected = identity()
            crypto().use { crypto ->
                require(crypto.keyTag() == state.keyTag) { "ACCOUNT_CONTENT_KEY_CHANGED" }
                val local = store; capture(local, crypto)
                var pages = 0
                do {
                    val page = AccountContentProtocol.page(rpc("account_content_read", credentials() + mapOf("p_after" to state.journal.cursor), expected))
                    check(valid()); storage.update { it.copy(content = it.content.copy(journal = it.content.journal.receive(page))) }
                    pages++
                    if (page.next_cursor == page.head_revision) break
                } while (pages < 10)
                check(valid())
                restoreActivity(local, crypto, ::valid)
                applyCommands(local, crypto, ::valid)
                capture(local, crypto)
                storage.update { it.copy(content = it.content.copy(journal = it.content.journal.prepare())) }
                val sent = state.journal.pending.take(50)
                if (sent.isNotEmpty()) {
                    val acks = AccountContentProtocol.acks(rpc("account_content_write", credentials() + mapOf("p_ops" to sent), expected))
                    check(valid()); storage.update { it.copy(content = it.content.copy(journal = it.content.journal.acknowledge(sent, acks))) }
                }
            }
            check(valid()); storage.update { it.copy(content = it.content.copy(issue = null)) }
            nextAutoAttempt = 0
            feedback.value = when {
                state.journal.conflicts.isNotEmpty() -> "${state.journal.conflicts.size} conflit(s) IPTV à examiner ; aucune fusion automatique."
                state.journal.pending.isNotEmpty() || state.journal.desired.isNotEmpty() -> "Modifications IPTV conservées dans la file ; envoi en cours."
                else -> "Favoris, historique et programmations synchronisés."
            }
            true
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            nextAutoAttempt = android.os.SystemClock.elapsedRealtime() +
                maxOf(60_000L, (failure as? PilotHttpFailure)?.retryAfterMs ?: 0L)
            if (state.generation == generation) runCatching { storage.update { it.copy(content = it.content.copy(issue = when {
                failure is PilotHttpFailure -> "ACCOUNT_CONTENT_HTTP_${failure.status}"
                failure is javax.crypto.AEADBadTagException -> "ACCOUNT_CONTENT_REMOTE_KEY_MISMATCH"
                failure.message == "ACCOUNT_CONTENT_KEY_CHANGED" -> "ACCOUNT_CONTENT_KEY_CHANGED"
                else -> "ACCOUNT_CONTENT_SYNC_FAILED"
            })) } }
            feedback.value = if (failure is javax.crypto.AEADBadTagException)
                "La clé de cette TV ne déchiffre pas les échanges du compte. Synchronisation bloquée, données et file conservées ; vérifiez la clé depuis une TV associée."
            else "Synchronisation IPTV interrompue. Données et file conservées ; vérifier la connexion, les profils et la clé."
            false
        }
    } }

    private fun restoreActivity(local: IptvStore, crypto: AccountContentCrypto, valid: () -> Boolean) {
        val activity = local.supabaseActivitySnapshot()
        val beforeValues = snapshot(local, crypto, activity)
        val expectedEntries = activity.associateBy { it.account to it.item.id }.toMutableMap()
        var artworkChanged = false
        state.journal.rows.values.filter { it.value.kind in setOf("favorite", "history") && !state.journal.protected(it.value.token()) }.forEach { row ->
            check(valid()); val value = row.value; val account = localProfile(value.profile) ?: return@forEach
            val item = if (value.deleted) null else cloudPlayback(crypto.open(value))
            if (item != null) require(crypto.id(value.profile, item.item.id) == value.key)
            val previous = if (item != null) expectedEntries[account to item.item.id] else
                expectedEntries.values.firstOrNull { it.account == account && crypto.id(value.profile, it.item.id) == value.key }
            val before = beforeValues[value.token()]
            // Local edits made while HTTP was in flight belong to the next outbox pass.
            if (before?.let { fingerprint(value.token(), it, crypto) } != state.observed[value.token()]) return@forEach
            val id = item?.item?.id ?: previous?.item?.id ?: return@forEach
            local.restoreSupabaseActivity(account, value.kind, id, item?.item?.item(), item?.playedAt ?: 0, value.deleted, previous)?.let { restored ->
                check(valid())
                if (item != null && restoreCloudArtwork(context, item)) artworkChanged = true
                val updated = restored.activity
                if (updated == null) expectedEntries.remove(account to id) else expectedEntries[account to id] = updated
                val after = updated?.takeIf { if (value.kind == "favorite") it.favorite else it.playedAt > 0 }?.let {
                    val metadata = IptvCloudItem.from(it.item)
                    cloudPlayback(IptvCloudPlayback(metadata, if (value.kind == "history") it.playedAt else 0,
                        cloudArtworkChoices(context, metadata)))
                }
                storage.update { s -> s.copy(content = s.content.copy(observed = if (after == null) s.content.observed - value.token()
                    else s.content.observed + (value.token() to fingerprint(value.token(), after, crypto)))) }
            }
        }
        check(valid())
        // Accepted progress is hashed: received catalogue metadata must resolve it locally.
        local.registerCloudActivityContents()
        if (artworkChanged) IptvStore.homeArtworkChanged()
    }

    internal fun sharedPlans(): List<IptvSharedRecording> {
        if (!state.selected) return emptyList()
        return runCatching { identity(); crypto().use { crypto -> state.journal.rows.values.mapNotNull { row ->
            val value = row.value
            if (value.kind != "plan" || value.deleted) return@mapNotNull null
            val account = localProfile(value.profile) ?: return@mapNotNull null
            val plan = cloudPlan(crypto.open(value)); require(plan.id == value.key)
            IptvSharedRecording(value.creator, "Autre appareil", plan.recording(account), row.revision)
        } } }.getOrDefault(emptyList())
    }
    internal fun plan(id: String) = sharedPlans().firstOrNull { it.recording.id == id }
    internal fun status(id: String): String {
        if (state.journal.desired.values.any { it.kind == "command" && it.reference == id }) return "Demande conservée dans la file locale · envoi en attente"
        val row = state.journal.rows.values.filter { it.value.kind == "command" && it.value.reference == id && !it.value.deleted }.maxByOrNull { it.revision }
        return when (row?.value?.status) {
            "pending" -> "Demande reçue par Supabase · en attente de l’appareil créateur"
            "applied" -> "Demande confirmée par l’appareil créateur"
            "rejected" -> "Demande refusée par l’appareil créateur · vérifier horaires, état et stockage"
            else -> "Programmation conservée sur l’appareil créateur"
        }
    }
    internal fun edit(id: String, revision: Long, channel: IptvItem, title: String, start: Long, end: Long,
        before: Int, after: Int, days: List<Int>): IptvRecording {
        val shared = requireNotNull(plan(id)); require(shared.revision == revision)
        val desired = IptvRecordingScheduler.replanned(shared.recording, channel, title, start, end, before, after, repeatDays = days)
        enqueueCommand(shared, IptvCloudCommand("EDIT", IptvCloudPlan.from(desired)))
        return shared.recording
    }
    internal fun cancel(id: String) { enqueueCommand(requireNotNull(plan(id)), IptvCloudCommand("CANCEL")) }
    private fun enqueueCommand(plan: IptvSharedRecording, command: IptvCloudCommand) {
        identity(); require(state.selected && plan.owner != storage.state.value.installation && plan.recording.state == RecordingState.PLANNED && store.ownsAccount(plan.recording.accountId))
        val profile = requireNotNull(profile(plan.recording.accountId))
        crypto().use { crypto ->
            val value = AccountContentValue(profile, "command", UUID.randomUUID().toString(), plan.owner,
                plan.recording.id, plan.revision, "pending")
            val sealed = crypto.seal(value, cloudCommand(command, plan.recording.id))
            storage.update { it.copy(content = it.content.copy(journal = it.content.journal.change(sealed))) }
        }
        schedule(); kick()
    }
    private fun applyCommands(local: IptvStore, crypto: AccountContentCrypto, valid: () -> Boolean) {
        state.journal.rows.values.filter { it.value.kind == "command" && it.value.status == "pending" &&
            it.value.creator == storage.state.value.installation && !state.journal.protected(it.value.token()) }.sortedBy { it.revision }.forEach { row ->
            synchronized(IptvRecordingScheduler.planningLock) {
                check(valid()); val value = row.value
                val account = localProfile(value.profile) ?: return@forEach
                val command = cloudCommand(crypto.open(value), value.reference)
                val shared = state.journal.rows["${value.profile}/plan/${value.reference}"] ?: return@forEach
                val current = local.recordings(true).firstOrNull { it.id == value.reference }
                val desired = command.desired?.recording(account)
                val done = runCatching {
                    require(shared.value.creator == storage.state.value.installation && !shared.value.deleted)
                    require(current == null || local.recordingAccount(current) == account)
                    when {
                        command.action == "CANCEL" && current?.state == RecordingState.CANCELLED -> Unit
                        command.action == "EDIT" && current != null && desired != null &&
                            current.state in setOf(RecordingState.PLANNED, RecordingState.RUNNING) && IptvRecordingSync.samePlan(current, desired) -> {
                            if (current.state == RecordingState.PLANNED) IptvRecordingScheduler.ensureArmed(context, current)
                        }
                        else -> {
                            require(shared.revision == value.base_revision && current?.state == RecordingState.PLANNED)
                            require(IptvCloudPlan.from(current) == cloudPlan(crypto.open(shared.value))) {
                                "Local plan changed before remote command"
                            }
                            if (command.action == "CANCEL") IptvRecordingScheduler.cancel(context, local, current)
                            else {
                                require(desired != null && local.sources().any { it.id == desired.channel.sourceId })
                                val channel = if (desired.channel.id == current.channel.id) current.channel else desired.channel
                                IptvRecordingScheduler.reschedule(context, local, current.id, channel, desired.title,
                                    desired.programStart, desired.programEnd, desired.beforeMinutes, desired.afterMinutes, desired.repeatDays,
                                    authorizedAccount = account)
                            }
                        }
                    }
                }.isSuccess
                check(valid())
                storage.update { it.copy(content = it.content.copy(journal = it.content.journal.change(value.copy(status = if (done) "applied" else "rejected")))) }
            }
        }
    }
    internal suspend fun resolve(id: String, publish: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        storage.update { it.copy(content = it.content.copy(journal = it.content.journal.resolve(id, publish))) }
    }; sync() }

    /** Escrow is initialized once by a verified existing installation; new TVs use the same account alone. */
    internal suspend fun recoverAccount(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = identity()
        val initial = auth.settings.value
        val settings = storage.state.value
        val primary = settings.bindings.singleOrNull { it.domain == "home" && it.localId == "1" }
            ?: error("ACCOUNT_PROFILE_LINK_REQUIRED")
        val instance = watch.getOrCreatePilotProfileInstanceId(1)
        require(primary.instance == instance) { "ACCOUNT_PROFILE_LINK_REQUIRED" }
        fun fingerprint() = MessageDigest.getInstance("SHA-256").digest(listOf(identity(), primary.cloudId, instance,
            auth.settings.value.scope?.epoch.orEmpty(), auth.settings.value.cloudKeyEpoch,
            auth.settings.value.hmacKeyBase64).joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (settings.recoveryIdentity == fingerprint()) return@withLock
        val nonce = UUID.randomUUID().toString()
        val body = (credentials() + mapOf("p_target" to initial.deviceId, "p_home_profile" to primary.cloudId,
            "p_nonce" to nonce)).toMutableMap()
        val existingKey = if (initial.scope == null) null else try { auth.accountContentKey(settings.owner) }
            catch (failure: IllegalArgumentException) {
                if (failure.message != "ACCOUNT_CONTENT_KEY_REQUIRED") throw failure
                null
            }
        val response = try {
            if (existingKey != null) {
                body["p_secret"] = java.util.Base64.getEncoder().encodeToString(existingKey)
                body["p_key_epoch"] = initial.cloudKeyEpoch
            }
            rpc("account_sync_recover", body, expected)
        } finally { existingKey?.fill(0) }
        val grant = accountRecoveryGrant(response)
        grant.validate(settings.owner, settings.installation, initial.deviceId,
            requireNotNull(initial.session).sessionId, primary.cloudId, nonce)
        val key = grant.secretBytes()
        try {
            check(identity() == expected)
            auth.installAccountRecovery(grant, key, instance)
            storage.update { it.copy(recoveryIdentity = fingerprint()) }
        } finally { key.fill(0) }
    } }

    internal suspend fun selectProgress() {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ProgressPilotEntryPoint::class.java)
        val configured = auth.settings.value
        if (configured.mode != PilotMode.SUPABASE_PROGRESS) {
            profiles.activeProfileReady.first { it }
            require(profiles.activeProfileId.value == configured.scope?.localProfileId) { "ACCOUNT_PRIMARY_PROFILE_REQUIRED" }
            entry.runtime().activateCloud(configured.cloudKeyEpoch)
        }
    }
    internal fun stopProgress() = EntryPointAccessors.fromApplication(context.applicationContext,
        ProgressPilotEntryPoint::class.java).runtime().disable()

    // Optional legacy pairing; normal sign-in now recovers through the account.
    internal suspend fun requestPairing(): AccountPairRequest = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = identity(); val s = storage.state.value; val account = auth.settings.value
        require(account.scope == null && !account.everActivated && account.mode == PilotMode.NAS_ONLY)
        val primary = s.bindings.single { it.domain == "home" && it.localId == "1" }
        watch.getOrCreatePilotProfileInstanceId(1)
        val wrapping = AccountPairReceiver()
        val body = rpc("account_pair_request", credentials() + mapOf("p_target" to account.deviceId,
            "p_profile" to primary.cloudId, "p_nonce" to wrapping.nonce, "p_public_key" to wrapping.publicKey), expected)
        gson.fromJson(body, AccountPairRequest::class.java).also {
            it.validate(System.currentTimeMillis()); require(it.owner == s.owner && it.installation == s.installation &&
                it.device == account.deviceId && it.session == account.session?.sessionId && it.nonce == wrapping.nonce &&
                it.public_key == wrapping.publicKey && it.home_profile == primary.cloudId)
            receiver = wrapping to it
            feedback.value = "Code à saisir sur la TV déjà associée : ${it.code()} (10 minutes)."
        }
    } }
    internal suspend fun pendingPairings(): List<AccountPairRequest> = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = identity()
        val requests = gson.fromJson(rpc("account_pair_list", credentials(), expected), Array<AccountPairRequest>::class.java).toList()
        require(requests.size <= 5)
        requests.filter { row -> runCatching { row.validate(System.currentTimeMillis()); require(row.owner == storage.state.value.owner) }.isSuccess }
    } }
    internal suspend fun approvePairing(request: AccountPairRequest, code: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = identity(); val configured = auth.settings.value
        val bound = requireNotNull(configured.scope)
        val primary = storage.state.value.bindings.single { it.domain == "home" && it.localId == bound.localProfileId.toString() }
        require(request.home_profile == primary.cloudId && request.owner == bound.ownerId && configured.cloudKeyEpoch.isNotBlank())
        val key = auth.accountContentKey(bound.ownerId)
        val grant = try { sealAccountPair(request, code, bound.profileBindingId, configured.cloudKeyEpoch,
            bound.deviceA, bound.deviceB, key, System.currentTimeMillis()) } finally { key.fill(0) }
        rpc("account_pair_approve", credentials() + mapOf("p_request" to request.id, "p_grant" to grant), expected)
        feedback.value = "Clé chiffrée pour le nouvel appareil. Celui-ci doit finaliser sa réception."
    } }
    internal suspend fun collectPairing() = withContext(Dispatchers.IO) { mutex.withLock {
        val expected = identity(); val (wrapping, request) = requireNotNull(receiver) { "ACCOUNT_PAIR_RESTART_REQUIRED" }
        val grant = gson.fromJson(rpc("account_pair_collect", credentials() + mapOf("p_request" to request.id), expected), AccountPairGrant::class.java)
        val secret = wrapping.unwrap(grant, request)
        try {
            profiles.activeProfileReady.first { it }; require(profiles.activeProfileId.value == 1)
            val binding = storage.state.value.bindings.single { it.cloudId == request.home_profile && it.domain == "home" && it.localId == "1" }
            require(binding.instance.isNotBlank())
            val instance = requireNotNull(watch.pilotDeliveryQueue().profileInstanceId(1))
            check(identity() == expected)
            auth.installAccountEnrollment(grant, secret, 1, instance)
            receiver = null
        } finally { secret.fill(0) }
        feedback.value = "Appareil associé, clé enregistrée. NAS_ONLY conservé ; sélection de la progression cloud à confirmer séparément."
    } }
    private fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AccountContentWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    companion object { const val WORK = "jojo-account-content" }
}

class AccountContentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = AccountContentRouting.runtime(applicationContext)
        if (!runtime.state.selected) return Result.success()
        if (runtime.requiresManualRetry()) return Result.failure()
        if (!runtime.retryDue()) return Result.retry()
        val success = runtime.sync(); val journal = runtime.state.journal
        return if (success && journal.pending.isEmpty() && journal.desired.keys.all { it in journal.conflicts }) Result.success()
        else if (runtime.state.issue?.startsWith("ACCOUNT_CONTENT_HTTP_4") == true &&
            runtime.state.issue != "ACCOUNT_CONTENT_HTTP_429") Result.failure() else Result.retry()
    }
}
