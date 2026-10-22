package com.jojotv.home.progresspilot

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AccountSettingsEntryPoint { fun accountSettings(): SupabaseAccountRuntime }

internal class AccountSeedPreview(val review: AccountSeedReview, val bindings: List<AccountProfileBinding>, val localProfiles: List<String>) {
    override fun toString() = "AccountSeedPreview(data=redacted)"
}

@Singleton
class SupabaseAccountRuntime @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val storage: SupabaseAccountStore,
    private val local: SupabaseAccountLocal,
    private val auth: SupabaseProgressPilotStore,
    private val api: SupabaseProgressApi,
    private val content: SupabaseAccountContentRuntime
) {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val loginMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val permits = ConcurrentHashMap.newKeySet<PilotRequestPermit>()
    private var registered = ""
    private val settingsReadCursor = AccountReadCursor()
    private var resumeApiUnavailable = false
    private var resumeDebounce: kotlinx.coroutines.Job? = null
    private var resumeSyncCache: Pair<String, AccountResumeMaskSync>? = null
    private var artworkApiUnavailable = false
    private var artworkSyncCache: Pair<String, AccountHomeArtworkSync>? = null
    private var netflixApiUnavailable = false
    @Volatile private var nativeNetflixSnapshot: String? = null
    @Volatile private var netflixView: Pair<String, String>? = null
    private var renderedNetflix: AccountNetflixState? = null
    private var renderedNetflixProfiles: Set<String>? = null
    private val feedback = MutableStateFlow("Sauvegarde des paramètres désactivée.")
    val message = feedback.asStateFlow()
    internal val state = storage.state
    internal val authenticated get() = auth.settings.value.session != null
    internal val canSeed get() = auth.settings.value.deviceId == "f8648865-cd01-4be7-bd4a-2e2c7b6659ba"
    private val endpoint = PilotEndpoint("https://jtdnwdbapkdkpgtceauh.supabase.co", "sb_publishable_MsqNmrTTNFEB5wLu2KNa-Q_AK6w8Wc4")

    fun start() {
        if (!started.compareAndSet(false, true)) return
        content.start()
        jobs.launch {
            var wasVisible = false
            SupabaseAppForeground.state.collect { visible ->
                if ((visible || wasVisible) && state.value.selected) kick()
                wasVisible = visible
            }
        }
        context.getSharedPreferences("home_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(homeChanges)
        jobs.launch {
            while (true) {
                if (SupabaseAppForeground.state.value && state.value.selected && !requiresManualRetry()) sync()
                delay(60_000) // Settings are sparse; progress keeps its own independent cadence.
            }
        }
        jobs.launch {
            while (true) {
                delay(15_000)
                if (SupabaseAppForeground.state.value && state.value.selected && state.value.restored &&
                    (!resumeApiUnavailable || !artworkApiUnavailable || !netflixApiUnavailable))
                    syncHomeResumeMasks()
            }
        }
    }
    private val homeChanges = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && AccountSettingsCatalog.homeKey(key) && state.value.selected) kick()
        if (key != null && AccountHomeArtwork.preference(key)) {
            AccountSettingsRouting.homeArtworkChanged(key)
            if (state.value.selected) kickResumeMasks()
        }
    }
    internal fun resumeMasksChanged() { if (state.value.selected) kickResumeMasks() }
    internal fun netflixObserved(snapshot: String) {
        if (snapshot == nativeNetflixSnapshot) return
        nativeNetflixSnapshot = snapshot
        if (state.value.selected && auth.settings.value.mode == PilotMode.SUPABASE_PROGRESS) kickResumeMasks()
    }
    internal fun netflixSnapshot(): String = netflixView?.takeIf {
        state.value.selected && state.value.restored && state.value.owner == auth.settings.value.session?.ownerId &&
            auth.settings.value.mode == PilotMode.SUPABASE_PROGRESS && it.first == state.value.owner
    }?.second ?: "[]"
    private fun kickResumeMasks() {
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-home-resumes", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AccountSettingsWorker>()
                .setInputData(androidx.work.workDataOf("home_resumes_only" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        resumeDebounce?.cancel()
        resumeDebounce = jobs.launch { delay(1200); jobs.launch { syncHomeResumeMasks() } }
    }
    private var debounce: kotlinx.coroutines.Job? = null
    private fun requiresManualRetry() = state.value.issue in setOf("ACCOUNT_HTTP_400", "ACCOUNT_HTTP_401", "ACCOUNT_HTTP_403", "ACCOUNT_HTTP_404",
        "ACCOUNT_RECOVERY_HTTP_400", "ACCOUNT_RECOVERY_HTTP_401", "ACCOUNT_RECOVERY_HTTP_403", "ACCOUNT_RECOVERY_HTTP_404", "ACCOUNT_RECOVERY_BLOCKED", "ACCOUNT_RECOVERY_NOT_READY")
    private fun kick() {
        debounce?.cancel()
        debounce = jobs.launch { delay(1200); if (!requiresManualRetry()) jobs.launch { sync() } }
    }

    suspend fun connect(email: String, password: String) = withContext(Dispatchers.IO) {
        require(pilotSignInInputMessage(email, password) == null) { "ACCOUNT_CREDENTIALS_REQUIRED" }
        mutex.withLock {
            if (state.value.restored) captureChanges(deferNewSettings = true)
            val permit = auth.authorizeAccountSignIn(endpoint)
            try {
                val session = api.signIn(endpoint, email, password, permit)
                validateRetarget(session.ownerId)
                auth.saveAccountSession(endpoint, session)
            }
            finally { auth.finishRequest(permit) }
            selectCurrentAccount()
            storage.update { it.copy(login = localChoices().copy(pending = true), recoveryIdentity = null) }
        }
        sync()
    }

    suspend fun restoreCurrentAccount() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (state.value.restored && auth.settings.value.session?.ownerId == state.value.owner) captureChanges(deferNewSettings = true)
            selectCurrentAccount()
        }
        sync()
    }
    private fun validateRetarget(owner: String) {
        val previous = state.value
        require(previous.owner.isEmpty() || previous.owner == owner || (previous.seed == null &&
            !previous.content.selected && previous.content.journal.pending.isEmpty() && previous.content.journal.desired.isEmpty() &&
            previous.content.journal.conflicts.isEmpty() && previous.journal.pending.isEmpty() && previous.journal.desired.isEmpty() && previous.journal.conflicts.isEmpty())) {
            "ACCOUNT_PREVIOUS_OPERATIONS_UNSENT"
        }
    }
    private fun selectCurrentAccount() {
        val current = auth.settings.value
        require(current.endpoint == endpoint && current.session != null) { "ACCOUNT_SIGN_IN_REQUIRED" }
        validateRetarget(current.session.ownerId)
        storage.update { old ->
            val base = if (old.owner == current.session.ownerId && old.project == endpoint.url) old else AccountLocalState()
            base.copy(owner = current.session.ownerId, project = endpoint.url, selected = true,
                generation = UUID.randomUUID().toString(), restored = false, backupKnownMissing = false, issue = null)
        }
        registered = ""
        resumeApiUnavailable = false
        artworkApiUnavailable = false
        netflixApiUnavailable = false
        feedback.value = "Compte connecté. Restauration des profils et paramètres…"
    }

    fun stop() {
        require(!content.state.selected) { "ACCOUNT_CONTENT_STOP_FIRST" }
        // Revoke only account requests. Neither progress mode nor its requests are stopped.
        storage.update { it.copy(selected = false, generation = UUID.randomUUID().toString()) }
        permits.toList().forEach { auth.finishRequest(it) }; permits.clear()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        feedback.value = "Sauvegarde des paramètres arrêtée. La progression conserve sa source."
    }

    private fun localChoices() = state.value.login ?: AccountLoginChoices(
        auth.settings.value.mode == PilotMode.SUPABASE_PROGRESS || auth.settings.value.scope == null,
        content.state.selected || auth.settings.value.scope == null)

    internal suspend fun setProgressSync(enabled: Boolean) {
        storage.update { it.copy(login = localChoices().copy(progress = enabled, pending = enabled)) }
        if (!enabled) { content.stopProgress(); return }
        if (!state.value.selected || !state.value.restored) restoreCurrentAccount()
        else { storage.update { it.copy(issue = null) }; completeLogin() }
    }

    internal suspend fun setContentSync(enabled: Boolean) {
        storage.update { it.copy(login = localChoices().copy(iptv = enabled, pending = enabled)) }
        if (!enabled) { content.stop(); return }
        if (!state.value.selected || !state.value.restored) restoreCurrentAccount()
        else { storage.update { it.copy(issue = null) }; completeLogin() }
    }

    /** Restore first, then recover the same key and select sources. No old local history is published. */
    private suspend fun completeLogin(): Boolean {
        if (!state.value.selected || !state.value.restored || !state.value.initialized ||
            state.value.issue == "ACCOUNT_PROFILE_LINK_REQUIRED") return true
        if (!loginMutex.tryLock()) return true
        val initial = state.value
        val choices = localChoices()
        fun valid() = state.value.selected && state.value.restored && state.value.generation == initial.generation &&
            state.value.owner == initial.owner && state.value.login == choices
        try {
            if (initial.login == null) storage.update { it.copy(login = choices) }
            if (!choices.progress && !choices.iptv && auth.settings.value.scope == null) {
                if (valid()) storage.update { it.copy(login = choices.copy(pending = false)) }
                return true
            }
            content.recoverAccount()
            if (!valid()) return false
            if (choices.pending) {
                if (choices.progress) content.selectProgress()
                if (!valid()) return false
                if (choices.iptv) {
                    val synced = if (content.state.selected) content.sync() else content.select()
                    if (!synced) {
                        if (valid()) feedback.value = content.message.value
                        if (!content.requiresManualRetry()) schedule()
                        return false
                    }
                }
                if (!valid()) return false
                storage.update { it.copy(login = choices.copy(pending = false), issue = null) }
                feedback.value = "Compte restauré. Les synchronisations choisies sont activées."
            }
            return true
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (!valid()) return false
            storage.update { it.copy(issue = when (failure) {
                is AccountRecoveryNotReady -> "ACCOUNT_RECOVERY_NOT_READY"
                is PilotHttpFailure -> "ACCOUNT_RECOVERY_HTTP_${failure.status}"
                else -> "ACCOUNT_RECOVERY_BLOCKED"
            }) }
            feedback.value = when {
                failure is AccountRecoveryNotReady -> "Profils et paramètres restaurés. Préparez une seule fois la récupération sur une TV déjà associée : mettez-la à jour, puis ouvrez Compte Supabase → Options avancées → Réessayer la récupération du compte. Revenez ensuite réessayer sur cet appareil."
                failure.message == "ACCOUNT_PRIMARY_PROFILE_REQUIRED" -> "Profils et paramètres restaurés. Sélectionnez le profil principal puis réessayez la reprise de lecture."
                failure.message == "ACCOUNT_CONTENT_KEY_CHANGED" -> "La clé du compte ne correspond pas à cette TV. Les données et les positions sont conservées."
                failure.message == "ACCOUNT_RECOVERY_IDENTITY_CHANGED" -> "L’association du compte doit être vérifiée. Les données et les positions sont conservées."
                failure is PilotHttpFailure && failure.status == 404 -> "Profils et paramètres restaurés. La récupération automatique nécessite l’extension serveur du compte."
                failure is PilotHttpFailure && failure.status in setOf(400, 403) -> "Profils et paramètres restaurés. La récupération sécurisée du compte n’est pas prête ou n’est pas autorisée. Les données restent conservées."
                else -> "Compte connecté. La récupération automatique est interrompue ; les données sont conservées. Réessayez depuis les options avancées."
            }
            if (failure !is AccountRecoveryNotReady && failure is java.io.IOException &&
                (failure !is PilotHttpFailure || failure.status == 429 || failure.status >= 500)) schedule()
            return false
        } finally { loginMutex.unlock() }
    }

    internal suspend fun seedReferenceDevice(preview: AccountSeedPreview) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = seedContext()
            val snapshot = local.snapshot(state.value.copy(bindings = preview.bindings))
            require(preview.review.matches(context, snapshot.values, snapshot.profiles.associate { it.token() to it.value })) {
                "ACCOUNT_SEED_PREVIEW_CHANGED"
            }
            storage.update { old ->
                // Persist only the reviewed payload, after rechecking account/session and pending work.
                require(seedContext() == context && old.seed == null) { "ACCOUNT_SEED_PREVIEW_CHANGED" }
                old.copy(bindings = preview.bindings, knownLocalProfiles = preview.localProfiles,
                    seed = AccountSeed(operations = preview.review.operations()), observed = preview.review.observedValues())
            }
        }
        sync()
    }

    private fun seedContext(): AccountSeedReviewContext {
        val state = state.value
        val account = auth.settings.value
        require(state.selected && state.backupKnownMissing && !state.initialized && state.seed == null && canSeed &&
            account.endpoint == endpoint && account.session?.ownerId == state.owner && state.project == endpoint.url) {
            "ACCOUNT_SEED_REFERENCE_REQUIRED"
        }
        return AccountSeedReviewContext(state.owner, state.project, state.installation, state.generation,
            account.session!!.sessionId, state.bindings.map { "${it.domain}/${it.cloudId}/${it.localId}/${it.instance}" })
    }

    internal suspend fun seedPreview(): AccountSeedPreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = seedContext()
            val bound = local.prepareSeedProfiles()
            val snapshot = local.snapshot(bound)
            require(bound.bindings.map { "${it.domain}/${it.localId}/${it.instance}" }.toSet() ==
                snapshot.profiles.map { it.token() }.toSet() && seedContext() == context) { "ACCOUNT_SEED_PREVIEW_CHANGED" }
            require(snapshot.profiles.all { AccountSettingsCatalog.spec("profiles.${it.domain}")!!.accepts(it.value) }) {
                "ACCOUNT_PROFILE_METADATA_INVALID"
            }
            AccountSeedPreview(AccountSeedReview.capture(context, snapshot.values, snapshot.profiles.associate { it.token() to it.value }),
                bound.bindings.toList(), bound.knownLocalProfiles.toList())
        }
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val settingsSynced = mutex.withLock {
            val initial = state.value
            if (!initial.selected) return@withLock true
            val sessionId = auth.settings.value.session?.sessionId
            fun valid() = state.value.selected && state.value.generation == initial.generation &&
                auth.settings.value.endpoint == endpoint && auth.settings.value.session?.ownerId == initial.owner &&
                auth.settings.value.session?.sessionId == sessionId
            try {
                check(valid()) { "ACCOUNT_SIGN_IN_REQUIRED" }
                if (initial.restored) captureChanges(deferNewSettings = true)
                val before = local.snapshot(state.value)
                val registration = "${initial.generation}/$sessionId/${initial.installation}"
                if (registered != registration) {
                    val body = rpc("account_settings_register", mapOf("p_device" to initial.installation, "p_proof" to initial.proof,
                        "p_platform" to if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "firetv" else "androidtv"), ::valid)
                    AccountProtocol.registered(body, initial.installation)
                    registered = registration
                }
                state.value.seed?.let { seed ->
                    val reply = rpc("account_settings_seed", credentials(initial) + mapOf("p_operation" to seed.operation_id, "p_ops" to seed.operations), ::valid)
                    AccountProtocol.seeded(reply)
                    check(valid())
                    storage.update { it.copy(seed = null) }
                }
                val afterRevision = settingsReadCursor.after(registration, state.value.restored, state.value.cursor)
                val body = rpc("account_settings_read_v2", credentials(initial) + mapOf("p_after" to afterRevision), ::valid)
                val response = AccountProtocol.page(body, initial.installation)
                require(response.head_revision >= state.value.cursor) { "ACCOUNT_REVISION_REGRESSED" }
                val page = response.copy(rows = if (afterRevision == 0L) response.rows else
                    state.value.journal.rows.filterNot { old -> response.rows.any { it.id() == old.id() } } + response.rows)
                check(valid())
                if (!page.initialized) {
                    storage.update { it.copy(initialized = false, backupKnownMissing = true, issue = null) }
                    feedback.value = if (canSeed) "Aucune sauvegarde commune. Cet appareil est la référence .150." else "Première sauvegarde en attente sur la .150. Les valeurs locales restent conservées."
                    return@withLock true
                }
                val complete = local.bindRemoteProfiles(page, ::valid)
                // Save remote revisions before applying; an interrupted restore is replayed, never published as defaults.
                storage.update { old -> old.copy(initialized = true, backupKnownMissing = false, cursor = page.head_revision, journal = old.journal.copy(rows = page.rows)) }
                local.restore(page, before, ::valid)
                check(valid())
                val after = local.snapshot(state.value)
                storage.update { old ->
                    val observed = (if (old.restored) old.observed else before.values.filterKeys { !it.startsWith("device/") }).toMutableMap()
                    page.rows.forEach { row -> val id = row.id().token()
                        if ((after.values[id] ?: JsonNull.INSTANCE) == row.value && id !in old.journal.desired && old.journal.pending.none { it.id() == row.id() }) {
                            if (row.value.isJsonNull) observed.remove(id) else observed[id] = row.value
                        }
                    }
                    old.copy(restored = true, observed = observed,
                        knownLocalProfiles = (old.knownLocalProfiles + after.profiles.map { it.token() }).distinct(),
                        issue = if (complete) null else "ACCOUNT_PROFILE_LINK_REQUIRED")
                }
                settingsReadCursor.applied(registration)
                captureChanges()
                storage.update { it.copy(journal = it.journal.prepare()) } // Persist before any HTTP call.
                val sent = state.value.journal.pending.take(50)
                if (sent.isNotEmpty()) {
                    val reply = rpc("account_settings_write", credentials(initial) + mapOf("p_ops" to sent), ::valid)
                    val acks = AccountProtocol.acks(reply)
                    check(valid())
                    storage.update { it.copy(journal = it.journal.acknowledge(sent, acks)) }
                }
                val masksSynced = syncResumeMasks()
                val artworkSynced = syncHomeArtwork()
                val netflixSynced = syncNetflix()
                val current = state.value
                feedback.value = when {
                    current.journal.conflicts.isNotEmpty() -> "Paramètres restaurés. ${current.journal.conflicts.size} conflit(s) à résoudre."
                    !complete -> "Paramètres restaurés. Des profils locaux doivent être associés sans fusion automatique."
                    current.journal.pending.isNotEmpty() || current.journal.desired.isNotEmpty() -> "Paramètres restaurés. Envoi des modifications en cours."
                    else -> "Profils et paramètres restaurés. Sauvegarde synchronisée."
                }
                if (!masksSynced) feedback.value += if (resumeApiUnavailable)
                    " Le partage des retraits de l’accueil nécessite l’extension serveur."
                    else " Les retraits de l’accueil restent en attente de synchronisation."
                if (!artworkSynced) feedback.value += if (artworkApiUnavailable)
                    " Le partage des images Freebox nécessite l’extension serveur."
                    else " Les images Freebox restent en attente de synchronisation."
                if (!netflixSynced) feedback.value += if (netflixApiUnavailable)
                    " Le partage des cartes Netflix nécessite l’extension serveur."
                    else " Les cartes Netflix restent en attente de synchronisation."
                if (current.journal.pending.isNotEmpty() || current.journal.desired.isNotEmpty()) schedule()
                (masksSynced || resumeApiUnavailable) && (artworkSynced || artworkApiUnavailable) && (netflixSynced || netflixApiUnavailable)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (!valid()) return@withLock false
                if (valid()) storage.update { it.copy(issue = when (failure) {
                    is PilotHttpFailure -> "ACCOUNT_HTTP_${failure.status}"
                    else -> "ACCOUNT_SYNC_FAILED"
                }) }
                feedback.value = if (failure is PilotHttpFailure && failure.status == 404)
                    "Compte connecté. Le schéma des paramètres doit encore être appliqué ; aucune sauvegarde n'a été publiée."
                else "Synchronisation des paramètres interrompue. Les données locales et la file sont conservées."
                if (valid() && failure !is IllegalArgumentException && failure !is IllegalStateException &&
                    (failure !is PilotHttpFailure || failure.status == 429 || failure.status >= 500)) schedule()
                false
            }
        }
        if (settingsSynced) completeLogin() else false
    }

    private suspend fun captureChanges(deferNewSettings: Boolean = false) {
        var current = state.value
        if (!current.restored) return
        current = local.discoverNewProfiles()
        val snapshot = local.snapshot(current)
        storage.update { old ->
            var journal = old.journal
            val deferred = mutableSetOf<String>()
            (old.observed.keys + snapshot.values.keys).forEach { id ->
                val value = snapshot.values[id] ?: JsonNull.INSTANCE
                val row = parseId(id)
                // An upgraded TV must restore newly shared keys before treating its old local values as edits.
                if (deferNewSettings && row.key in AccountSettingsCatalog.extendedKeys && id !in old.observed &&
                    old.journal.rows.none { it.id() == row }) { deferred += id; return@forEach }
                if ((old.observed[id] ?: JsonNull.INSTANCE) != value) {
                    if (row.scope == "profile") {
                        val binding = old.bindings.firstOrNull { it.cloudId == row.subject }
                        if (binding == null || snapshot.profiles.none { it.domain == binding.domain && it.id == binding.localId && it.instance == binding.instance }) return@forEach
                    }
                    // Absence is never interpreted as a profile deletion.
                    if (!row.key.startsWith("profiles.") || !value.isJsonNull) journal = journal.change(row, value)
                }
            }
            old.copy(journal = journal, observed = snapshot.values.filterKeys { it !in deferred })
        }
    }

    internal suspend fun syncHomeResumeMasks(): Boolean = mutex.withLock {
        val masks = syncResumeMasks()
        val artwork = syncHomeArtwork()
        val netflix = syncNetflix()
        (masks || resumeApiUnavailable) && (artwork || artworkApiUnavailable) && (netflix || netflixApiUnavailable)
    }

    private suspend fun syncNetflix(): Boolean {
        val initial = state.value
        if (!initial.selected || !initial.restored || auth.settings.value.mode != PilotMode.SUPABASE_PROGRESS) return true
        if (netflixApiUnavailable) return false
        val sessionId = auth.settings.value.session?.sessionId
        fun valid() = state.value.selected && state.value.generation == initial.generation && state.value.owner == initial.owner &&
            auth.settings.value.session?.ownerId == initial.owner && auth.settings.value.session?.sessionId == sessionId &&
            auth.settings.value.mode == PilotMode.SUPABASE_PROGRESS
        return try {
            val registration = "${initial.generation}/$sessionId/${initial.installation}"
            if (registered != registration) {
                AccountProtocol.registered(rpc("account_settings_register", credentials(initial) + mapOf("p_platform" to
                    if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "firetv" else "androidtv"), ::valid), initial.installation)
                registered = registration
            }
            val secret = auth.accountContentKey(initial.owner)
            val crypto = try { AccountContentCrypto(secret, initial.owner) } finally { secret.fill(0) }
            crypto.use {
                val saved = state.value.netflix.takeIf { it.owner == initial.owner }
                    ?: AccountNetflixState(owner = initial.owner, keyTag = it.keyTag())
                require(saved.keyTag.isEmpty() || saved.keyTag == it.keyTag()) { "ACCOUNT_NETFLIX_KEY_CHANGED" }
                // Fetch before observing the first local snapshot, so an old native recommendation
                // cannot seed over another TV's already published episode.
                var page = accountNetflixPage(rpc("account_netflix_resumes_sync", credentials(initial) +
                    mapOf("p_after" to saved.cursor, "p_ops" to saved.pending.values.take(1)), ::valid), saved.cursor)
                check(valid()) { "ACCOUNT_SESSION_CHANGED" }
                var next = saved.receive(saved.pending.values.take(1), page, it).copy(keyTag = it.keyTag())
                repeat(5) {
                    if (next.cursor < page.head) {
                        page = accountNetflixPage(rpc("account_netflix_resumes_sync", credentials(initial) +
                            mapOf("p_after" to next.cursor, "p_ops" to emptyList<Any>()), ::valid), next.cursor)
                        check(valid()) { "ACCOUNT_SESSION_CHANGED" }
                        next = next.receive(emptyList(), page, crypto)
                    }
                }
                if (next.cursor < page.head) {
                    storage.update { state -> state.copy(netflix = next) }
                    kickResumeMasks()
                    return false
                }
                val native = nativeNetflixSnapshot?.let(AccountNetflixObservation::snapshot)
                native?.let { values -> next = next.capture(values, it, initial.installation, System.currentTimeMillis()) }
                check(valid()) { "ACCOUNT_SESSION_CHANGED" }
                storage.update { state -> state.copy(netflix = next) } // Immutable ciphertext before HTTP, including retries.
                val sent = next.pending.values.take(1)
                if (sent.isNotEmpty()) {
                    val response = accountNetflixPage(rpc("account_netflix_resumes_sync", credentials(initial) +
                        mapOf("p_after" to next.cursor, "p_ops" to sent), ::valid), next.cursor)
                    check(valid()) { "ACCOUNT_SESSION_CHANGED" }
                    next = next.receive(sent, response, it)
                    storage.update { state -> state.copy(netflix = next) }
                }
                val rendered = renderedNetflix
                val profiles = native?.map { AccountNetflixObservation.profileKey(it.profile) }?.toSet()?.takeIf { it.isNotEmpty() }
                if (rendered == null || rendered.owner != initial.owner || rendered.keyTag != next.keyTag || rendered.rows != next.rows ||
                    rendered.pending != next.pending || renderedNetflixProfiles != profiles) {
                    val view = com.google.gson.JsonArray().apply { next.view(it).filter { (_, observation) ->
                        profiles == null || AccountNetflixObservation.profileKey(observation.profile) in profiles
                    }.forEach { (value, observation) ->
                        add(com.google.gson.JsonParser.parseString(observation.encode()).asJsonObject.apply {
                            addProperty("key", value.key); addProperty("stamp", value.stamp)
                        })
                    } }.toString()
                    check(valid()) { "ACCOUNT_SESSION_CHANGED" }
                    renderedNetflix = next
                    renderedNetflixProfiles = profiles
                    if (netflixView != (initial.owner to view)) {
                        netflixView = initial.owner to view
                        val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                        prefs.edit().putLong(AccountSettingsRouting.NETFLIX_CHANGED,
                            prefs.getLong(AccountSettingsRouting.NETFLIX_CHANGED, 0L) + 1L).apply()
                    }
                }
                if (next.pending.isNotEmpty()) kickResumeMasks()
                next.pending.isEmpty()
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure is PilotHttpFailure && failure.status == 404) netflixApiUnavailable = true
            android.util.Log.w("HomeNetflixResumes", "Netflix resumes pending: ${failure.javaClass.simpleName}")
            false
        }
    }

    private suspend fun syncHomeArtwork(): Boolean {
        val initial = state.value
        if (!initial.selected || !initial.restored) return true
        if (artworkApiUnavailable) return false
        val sessionId = auth.settings.value.session?.sessionId
        fun valid() = state.value.selected && state.value.generation == initial.generation && state.value.owner == initial.owner &&
            auth.settings.value.session?.ownerId == initial.owner && auth.settings.value.session?.sessionId == sessionId
        return try {
            val registration = "${initial.generation}/$sessionId/${initial.installation}"
            if (registered != registration) {
                AccountProtocol.registered(rpc("account_settings_register", credentials(initial) + mapOf("p_platform" to
                    if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "firetv" else "androidtv"), ::valid), initial.installation)
                registered = registration
            }
            val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            val key = AccountHomeArtwork.SYNC_PREFIX + initial.installation
            val raw = home.getString(key, "{}").orEmpty()
            val saved = artworkSyncCache?.takeIf { it.first == raw }?.second
                ?: com.google.gson.Gson().fromJson(raw, AccountHomeArtworkSync::class.java)
            require(saved.cursor >= 0 && saved.known.size <= 2000 && saved.published.size <= 2000)
            (saved.known.values + saved.published.values).forEach { it.validate() }
            require((saved.known + saved.published).all { (key, value) -> key == value.key })
            val current = AccountSettingsRouting.homeArtwork(context, saved.known)
            val captured = saved.copy(known = current)
            val capturedJson = com.google.gson.Gson().toJson(captured)
            // Persist the chosen clocks before the HTTP request, including when its response is lost.
            if (capturedJson != raw) check(home.edit().putString(key, capturedJson).commit()) { "ACCOUNT_ARTWORK_STORAGE_FAILED" }
            artworkSyncCache = capturedJson to captured
            val sent = captured.pending(current)
            val page = accountHomeArtworkPage(rpc("account_home_artwork_sync", credentials(initial) +
                mapOf("p_after" to captured.cursor, "p_ops" to sent), ::valid), captured.cursor)
            check(valid()) { "ACCOUNT_SESSION_CHANGED" }
            val nextValues = current.toMutableMap()
            page.rows.forEach { row ->
                check(valid()) { "ACCOUNT_SESSION_CHANGED" }
                nextValues[row.value.key] = AccountSettingsRouting.restoreHomeArtwork(context, row.value, nextValues[row.value.key])
            }
            val next = captured.acknowledge(sent, page, nextValues)
            val encoded = com.google.gson.Gson().toJson(next)
            check(valid()) { "ACCOUNT_SESSION_CHANGED" }
            if (encoded != capturedJson) check(home.edit().putString(key, encoded).commit()) { "ACCOUNT_ARTWORK_STORAGE_FAILED" }
            artworkSyncCache = encoded to next
            val pending = next.pending(AccountSettingsRouting.homeArtwork(context, next.known)).isNotEmpty() || page.cursor < page.head
            if (pending) kickResumeMasks()
            !pending
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure is PilotHttpFailure && failure.status == 404) artworkApiUnavailable = true
            android.util.Log.w("HomeArtworkSync", "Artwork pending: ${failure.javaClass.simpleName}")
            false
        }
    }

    /** Independent of IPTV activity and its library; old servers/clients keep their settings protocol. */
    private suspend fun syncResumeMasks(): Boolean {
        val initial = state.value
        if (!initial.selected || !initial.restored) return true
        if (resumeApiUnavailable) return false
        val sessionId = auth.settings.value.session?.sessionId
        fun valid() = state.value.selected && state.value.generation == initial.generation && state.value.owner == initial.owner &&
            auth.settings.value.session?.ownerId == initial.owner && auth.settings.value.session?.sessionId == sessionId
        return try {
            val registration = "${initial.generation}/$sessionId/${initial.installation}"
            if (registered != registration) {
                val registeredBody = rpc("account_settings_register", credentials(initial) + mapOf("p_platform" to
                    if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "firetv" else "androidtv"), ::valid)
                AccountProtocol.registered(registeredBody, initial.installation)
                registered = registration
            }
            val bindings = initial.bindings.filter { it.domain == "home" }
            val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            val storageKey = AccountResumeMasks.SYNC_PREFIX + initial.installation
            val raw = home.getString(storageKey, "{}").orEmpty()
            val saved = resumeSyncCache?.takeIf { it.first == raw }?.second
                ?: com.google.gson.Gson().fromJson(raw, AccountResumeMaskSync::class.java)
            require(saved.cursor >= 0 && saved.published.size <= 10000)
            val ids = bindings.map { it.cloudId }.sorted()
            val previous = if (saved.bindings == ids) saved else AccountResumeMaskSync(ids)
            val values = bindings.flatMap { binding -> AccountSettingsRouting.resumeMasks(context, binding)
                .map { (key, stamp) -> AccountResumeMask(binding.cloudId, key, stamp).also { it.validate() } } }
            val sent = previous.pending(values)
            check(home.edit().commit()) { "ACCOUNT_RESUME_STORAGE_FAILED" }
            val body = rpc("account_resume_masks_sync", credentials(initial) + mapOf("p_after" to previous.cursor,
                "p_ops" to sent.map { mapOf("profile" to it.profile, "key" to it.key, "stamp" to it.stamp) }), ::valid)
            val page = accountResumeMaskPage(body, previous.cursor)
            check(valid()) { "ACCOUNT_SESSION_CHANGED" }
            page.rows.groupBy { it.profile }.forEach { (profile, rows) ->
                if (state.value.bindings.any { it.domain == "home" && it.cloudId == profile })
                    AccountSettingsRouting.restoreResumeMasks(context, profile, rows.associate { it.key to it.stamp })
            }
            val next = previous.acknowledge(sent, page)
            val encoded = com.google.gson.Gson().toJson(next)
            check(valid()) { "ACCOUNT_SESSION_CHANGED" }
            if (encoded != raw) check(home.edit().putString(storageKey, encoded).commit()) { "ACCOUNT_RESUME_STORAGE_FAILED" }
            resumeSyncCache = encoded to next
            val latest = bindings.flatMap { binding -> AccountSettingsRouting.resumeMasks(context, binding)
                .map { (key, stamp) -> AccountResumeMask(binding.cloudId, key, stamp) } }
            val pending = next.pending(latest).isNotEmpty() || page.next_cursor < page.head_revision
            if (pending) kickResumeMasks()
            !pending
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure is PilotHttpFailure && failure.status == 404) resumeApiUnavailable = true
            android.util.Log.w("HomeResumeMasks", "Resume masks pending: ${failure.javaClass.simpleName}")
            false
        }
    }

    suspend fun resolveConflict(id: String, keepLocal: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock { storage.update { state -> state.copy(journal = state.journal.resolve(id, keepLocal)) } }
        sync()
    }

    internal suspend fun profileLinks(): Pair<List<AccountEntry>, List<AccountLocalProfile>> = mutex.withLock {
        state.value.journal.rows.filter { it.key.startsWith("profiles.") && !it.value.isJsonNull && state.value.bindings.none { b -> b.cloudId == it.subject } } to
            local.snapshot(state.value).profiles.filter { p -> state.value.bindings.none { it.domain == p.domain && it.localId == p.id && it.instance == p.instance } }
    }
    internal suspend fun linkProfile(row: AccountEntry, profile: AccountLocalProfile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!content.state.selected) { "ACCOUNT_CONTENT_STOP_FIRST" }
            require(row in state.value.journal.rows && row.key == "profiles.${profile.domain}")
            if (profile.domain == "home") require(row.value.asJsonObject["primary"].asBoolean == (profile.id == "1"))
            require(local.snapshot(state.value).profiles.any { it == profile })
            captureChanges()
            storage.update { state -> require(state.bindings.none { it.cloudId == row.subject || it.domain == profile.domain && it.localId == profile.id })
                state.copy(bindings = state.bindings + AccountProfileBinding(profile.domain, row.subject, profile.id, profile.instance), restored = false) }
        }
        sync()
    }

    private fun credentials(state: AccountLocalState) = mapOf("p_device" to state.installation, "p_proof" to state.proof)
    private suspend fun rpc(name: String, parameters: Map<String, Any>, valid: () -> Boolean): String {
        check(valid()) { "ACCOUNT_SESSION_CHANGED" }
        var session = auth.settings.value.session ?: error("ACCOUNT_SIGN_IN_REQUIRED")
        val permit = auth.accountRequest(endpoint, session)
        permits += permit
        return try {
            if (session.expiresAtSeconds < System.currentTimeMillis() / 1000 + 90) {
                val next = api.refreshSession(endpoint, session, permit)
                try { auth.replaceSession(session, next) }
                catch (failure: IllegalArgumentException) {
                    if (auth.settings.value.session?.sessionId != session.sessionId) throw failure
                }
                session = auth.settings.value.session ?: error("ACCOUNT_SIGN_IN_REQUIRED")
            }
            check(valid())
            api.accountRpc(endpoint, session, name, parameters, permit).also { check(valid()) { "ACCOUNT_SESSION_CHANGED" } }
        } finally { permits -= permit; auth.finishRequest(permit) }
    }
    private fun parseId(token: String): AccountRowId = token.split('/', limit = 3).let {
        require(it.size == 3); AccountRowId(it[0], it[1], it[2])
    }
    private fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AccountSettingsWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }
    companion object { internal const val WORK = "jojo-account-settings" }
}

class AccountSettingsWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = EntryPointAccessors.fromApplication(applicationContext, AccountSettingsEntryPoint::class.java).accountSettings()
        if (!runtime.state.value.selected) return Result.success()
        if (inputData.getBoolean("home_resumes_only", false))
            return if (runtime.syncHomeResumeMasks()) Result.success() else Result.retry()
        val success = runtime.sync()
        val state = runtime.state.value
        if (state.issue in setOf("ACCOUNT_HTTP_400", "ACCOUNT_HTTP_401", "ACCOUNT_HTTP_403", "ACCOUNT_HTTP_404", "ACCOUNT_RECOVERY_NOT_READY")) return Result.failure()
        return if (success && state.journal.pending.isEmpty() && state.journal.desired.keys.all { it in state.journal.conflicts }) Result.success() else Result.retry()
    }
}
