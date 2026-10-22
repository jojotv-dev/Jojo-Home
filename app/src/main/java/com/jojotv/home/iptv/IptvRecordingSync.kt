package com.jojotv.home.iptv

import android.content.Context
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable internal data class IptvSharedRecording(
    val owner: String, val ownerLabel: String, val recording: IptvRecording,
    val revision: Long = 1, val handledCommand: String = "", val outcome: String = "", val message: String = "",
    val schema: Int = 1,
)

@Serializable internal data class IptvRecordingCommand(
    val id: String, val owner: String, val recordingId: String, val accountId: String,
    val revision: Long, val action: String, val desired: IptvRecording? = null, val schema: Int = 1,
)

internal enum class IptvRecordingDecision { IGNORE, REJECT, EDIT, CANCEL, ALREADY_APPLIED }

/** Commands change only the creator's existing alarm; foreign plans never enter recordings.bin. */
internal object IptvRecordingSync {
    private const val PLAN = "recording:"
    private const val COMMAND = "command:"
    private val lock = Any()
    private val networkLock = Any()
    private val work = Executors.newSingleThreadExecutor()
    private var running = false
    private var again = false
    private var loadedAuthority = ""
    private var cached = IptvSyncSnapshot()
    private val client by lazy { IptvHttp.client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build() }

    private data class Session(val base: String, val token: String, val authority: String)
    internal fun authority(base: String, account: String, token: String): String =
        digest("${base.trim().trimEnd('/')}\n${account.trim().lowercase(java.util.Locale.ROOT).ifBlank { token }}")

    private fun session(context: Context): Session? {
        if (!com.jojotv.home.progresspilot.NasDataSyncRouting.enabled(context)) return null
        val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val token = prefs.getString("nas_token", "").orEmpty().takeIf(String::isNotBlank) ?: return null
        val base = prefs.getString("nas_url", null)?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
            ?: com.jojotv.home.BuildConfig.JOJOTV_NAS_SYNC_URL.trimEnd('/')
        val account = prefs.getString("nas_email", "").orEmpty().trim().lowercase(java.util.Locale.ROOT)
        return Session(base, token, authority(base, account, token))
    }

    private fun snapshot(context: Context, store: IptvStore): IptvSyncSnapshot = synchronized(lock) {
        val current = session(context) ?: return@synchronized IptvSyncSnapshot()
        val state = snapshotFor(store, current)
        if (session(context) == current) state else IptvSyncSnapshot()
    }

    private fun snapshotFor(store: IptvStore, current: Session): IptvSyncSnapshot = synchronized(lock) {
        if (loadedAuthority != current.authority) {
            cached = store.readRecordingSync(current.authority)
            loadedAuthority = current.authority
        }
        cached
    }

    private fun put(context: Context, store: IptvStore, key: String, value: String?, expected: Session? = null) {
        val writer = store.recordingDeviceId()
        val changed = synchronized(lock) {
            val current = session(context)
            check(expected == null || current == expected) { "Le compte NAS a changé. Relance la demande." }
            if (current == null) return@synchronized false
            val previous = snapshotFor(store, current)
            if (previous.records[key]?.value == value && (value != null || key in previous.records)) return@synchronized false
            val at = maxOf(System.currentTimeMillis(), (previous.records.values.maxOfOrNull { it.changedAt } ?: 0L) + 1)
            val next = previous.copy(records = previous.records + (key to IptvSyncRecord(at, writer, value)))
            store.writeRecordingSync(current.authority, next)
            cached = next
            true
        }
        if (changed) request(context)
    }

    internal fun samePlan(a: IptvRecording, b: IptvRecording): Boolean = a.id == b.id && a.channel.id == b.channel.id &&
        a.title == b.title && a.programStart == b.programStart && a.programEnd == b.programEnd &&
        a.beforeMinutes == b.beforeMinutes && a.afterMinutes == b.afterMinutes && a.repeatDays == b.repeatDays

    internal fun publicPlan(recording: IptvRecording): IptvRecording = recording.copy(
        channel = IptvItem(recording.channel.id, recording.channel.sourceId, recording.channel.providerId,
            ContentType.LIVE, recording.channel.title, categoryId = recording.channel.categoryId,
            categoryName = recording.channel.categoryName), bytes = 0, file = "", directory = "", error = "")

    internal fun decision(device: String, shared: IptvSharedRecording, local: IptvRecording?,
        command: IptvRecordingCommand, sameAccount: Boolean): IptvRecordingDecision {
        if (shared.owner != device || command.owner != device) return IptvRecordingDecision.IGNORE
        if (shared.schema != 1 || command.schema != 1 || command.id.isBlank() || command.recordingId != shared.recording.id ||
            !sameAccount || command.action !in setOf("EDIT", "CANCEL")) return IptvRecordingDecision.REJECT
        if (local != null && local.id != command.recordingId) return IptvRecordingDecision.REJECT
        if (command.id == shared.handledCommand) return IptvRecordingDecision.IGNORE
        if (command.action == "CANCEL" && (local == null || local.state == RecordingState.CANCELLED)) return IptvRecordingDecision.ALREADY_APPLIED
        if (command.action == "CANCEL") return if (local?.state == RecordingState.PLANNED && command.revision == shared.revision)
            IptvRecordingDecision.CANCEL else IptvRecordingDecision.REJECT
        if (local == null) return IptvRecordingDecision.REJECT
        val desired = command.desired ?: return IptvRecordingDecision.REJECT
        if (desired.id != local.id || desired.channel.type != ContentType.LIVE || desired.state != RecordingState.PLANNED)
            return IptvRecordingDecision.REJECT
        if (local.state in setOf(RecordingState.PLANNED, RecordingState.RUNNING) && samePlan(local, desired))
            return IptvRecordingDecision.ALREADY_APPLIED
        return if (local.state == RecordingState.PLANNED && command.revision == shared.revision)
            IptvRecordingDecision.EDIT else IptvRecordingDecision.REJECT
    }

    internal fun shared(snapshot: IptvSyncSnapshot): Map<String, IptvSharedRecording> = snapshot.records.mapNotNull { (key, record) ->
        if (!key.startsWith(PLAN) || record.value == null) return@mapNotNull null
        runCatching { iptvJson.decodeFromString<IptvSharedRecording>(record.value) }.getOrNull()?.takeIf {
            it.schema == 1 && it.owner.isNotBlank() && it.owner == record.writer && it.revision > 0 &&
                it.recording.id == key.removePrefix(PLAN)
        }?.let { it.recording.id to it }
    }.toMap()

    private fun cloud(context: Context) = com.jojotv.home.progresspilot.AccountContentRouting.runtime(context)
    internal fun plan(context: Context, store: IptvStore, id: String): IptvSharedRecording? =
        if (store.cloudActivitySelected()) cloud(context).plan(id) else shared(snapshot(context, store))[id]
    internal fun remote(context: Context, store: IptvStore, id: String): Boolean =
        plan(context, store, id)?.owner?.let { it != store.recordingDeviceId() } == true

    internal fun visible(context: Context, store: IptvStore): List<IptvRecording> {
        val local = store.recordings()
        val device = store.recordingDeviceId()
        val plans = if (store.cloudActivitySelected()) cloud(context).sharedPlans() else shared(snapshot(context, store)).values
        return local + plans.filter { it.owner != device &&
            store.ownsAccount(it.recording.accountId) &&
            local.none { saved -> saved.id == it.recording.id } }.map { it.recording }
    }

    internal fun status(context: Context, store: IptvStore, id: String): String {
        if (store.cloudActivitySelected()) return cloud(context).status(id)
        val state = snapshot(context, store)
        val shared = shared(state)[id] ?: return ""
        if (shared.owner == store.recordingDeviceId()) return ""
        val command = state.records[COMMAND + id]?.value?.let {
            runCatching { iptvJson.decodeFromString<IptvRecordingCommand>(it) }.getOrNull()
        }
        val status = if (command != null && command.id != shared.handledCommand)
            if (command.action == "CANCEL") "Annulation en attente de confirmation" else "Modification en attente de confirmation"
        else if (shared.outcome == "rejected") shared.message
        else if (shared.outcome == "applied") if (command?.action == "CANCEL") "Annulation confirmée" else "Modification confirmée"
        else when (shared.recording.state) {
            RecordingState.PLANNED -> "Programmation confirmée"
            RecordingState.CANCELLED -> "Annulé sur l’appareil créateur"
            else -> "État communiqué par l’appareil créateur"
        }
        return "${shared.ownerLabel.ifBlank { "Autre appareil" }} · $status"
    }

    private fun publish(context: Context, store: IptvStore, recording: IptvRecording, expected: Session? = null) {
        val current = expected ?: session(context) ?: return
        check(session(context) == current) { "NAS session changed" }
        val previous = shared(snapshotFor(store, current))[recording.id]
        val device = store.recordingDeviceId()
        if (previous != null && previous.owner != device) return
        if (previous == null && recording.state !in setOf(RecordingState.PLANNED, RecordingState.RUNNING)) return
        val safe = publicPlan(recording).copy(accountId = store.recordingAccount(recording))
        val revision = if (previous == null) 1 else previous.revision + if (samePlan(previous.recording, safe)) 0 else 1
        val next = (previous ?: IptvSharedRecording(device, "${Build.MANUFACTURER} ${Build.MODEL}".trim(), safe))
            .copy(recording = safe, revision = revision)
        put(context, store, PLAN + recording.id, iptvJson.encodeToString(next), expected = current)
    }

    internal fun removed(context: Context, store: IptvStore, id: String) {
        if (plan(context, store, id)?.owner == store.recordingDeviceId()) put(context, store, PLAN + id, null)
    }

    internal fun localChanged(context: Context, store: IptvStore, recording: IptvRecording) {
        if (store.cloudActivitySelected()) { runCatching { cloud(context).recordingChanged(recording) }; return }
        // A metadata failure must never invalidate the local alarm or recording.
        // Byte counters are stripped from the shared plan, so progress updates do not trigger NAS requests.
        runCatching { publish(context, store, recording) }.onFailure { failed(context) }
    }

    internal fun localRemoved(context: Context, store: IptvStore, id: String) {
        if (store.cloudActivitySelected()) { runCatching { cloud(context).recordingRemoved(id) }; return }
        runCatching { removed(context, store, id) }.onFailure { failed(context) }
    }

    internal fun edit(context: Context, store: IptvStore, id: String, expectedRevision: Long, channel: IptvItem,
        title: String, start: Long, end: Long, before: Int, after: Int, days: List<Int>): IptvRecording {
        if (store.cloudActivitySelected()) return cloud(context).edit(id, expectedRevision, channel, title, start, end, before, after, days)
        val current = checkNotNull(session(context)) { "Connecte cet appareil au NAS." }
        val shared = requireRemote(context, store, id, current)
        check(shared.revision == expectedRevision) { "La programmation a changé. Rouvre-la avant de la modifier." }
        val desired = IptvRecordingScheduler.replanned(shared.recording, channel, title, start, end, before, after, repeatDays = days)
        val command = IptvRecordingCommand(UUID.randomUUID().toString(), shared.owner, id, shared.recording.accountId,
            expectedRevision, "EDIT", desired)
        put(context, store, COMMAND + id, iptvJson.encodeToString(command), expected = current)
        return shared.recording
    }

    internal fun cancel(context: Context, store: IptvStore, id: String) {
        if (store.cloudActivitySelected()) { cloud(context).cancel(id); return }
        val current = checkNotNull(session(context)) { "Connecte cet appareil au NAS." }
        val shared = requireRemote(context, store, id, current)
        put(context, store, COMMAND + id, iptvJson.encodeToString(IptvRecordingCommand(UUID.randomUUID().toString(),
            shared.owner, id, shared.recording.accountId, shared.revision, "CANCEL")), expected = current)
    }

    private fun requireRemote(context: Context, store: IptvStore, id: String, current: Session): IptvSharedRecording {
        check(session(context) == current) { "Le compte NAS a changé. Relance la demande." }
        val shared = checkNotNull(shared(snapshotFor(store, current))[id]) { "Cette programmation n’est plus disponible." }
        check(shared.owner != store.recordingDeviceId() && store.ownsAccount(shared.recording.accountId) &&
            shared.recording.state == RecordingState.PLANNED) { "Cette programmation n’est plus modifiable pour ce compte." }
        return shared
    }

    private fun applyCommands(context: Context, store: IptvStore, current: Session) {
        val commands = snapshotFor(store, current).records.filterKeys { it.startsWith(COMMAND) }.mapNotNull { (key, record) ->
            record.value?.let { runCatching { iptvJson.decodeFromString<IptvRecordingCommand>(it) }.getOrNull() }
                ?.takeIf { it.recordingId == key.removePrefix(COMMAND) }
        }
        commands.forEach { command -> synchronized(IptvRecordingScheduler.planningLock) {
            if (session(context) != current) return@forEach
            val shared = shared(snapshotFor(store, current))[command.recordingId] ?: return@forEach
            val local = store.recordings(allAccounts = true).firstOrNull { it.id == command.recordingId }
            val sameAccount = store.resolveAccountId(command.accountId) == store.resolveAccountId(shared.recording.accountId) &&
                store.accounts().any { store.resolveAccountId(it.id) == store.resolveAccountId(command.accountId) } &&
                (local == null || store.recordingAccount(local) == store.resolveAccountId(command.accountId))
            val action = decision(store.recordingDeviceId(), shared, local, command, sameAccount)
            if (action == IptvRecordingDecision.IGNORE) return@forEach
            val applied = runCatching {
                when (action) {
                    IptvRecordingDecision.EDIT -> {
                        val desired = checkNotNull(command.desired)
                        val existing = checkNotNull(local)
                        check(store.sources().any { it.id == desired.channel.sourceId })
                        val channel = if (desired.channel.id == existing.channel.id) existing.channel else desired.channel
                        IptvRecordingScheduler.reschedule(context, store, command.recordingId, channel, desired.title,
                            desired.programStart, desired.programEnd, desired.beforeMinutes, desired.afterMinutes,
                            desired.repeatDays, authorizedAccount = command.accountId)
                    }
                    IptvRecordingDecision.CANCEL -> IptvRecordingScheduler.cancel(context, store, checkNotNull(local))
                    IptvRecordingDecision.ALREADY_APPLIED -> if (command.action == "EDIT" && local?.state == RecordingState.PLANNED)
                        IptvRecordingScheduler.ensureArmed(context, checkNotNull(local)) else Unit
                    else -> error("Rejected command")
                }
            }.isSuccess
            val latest = shared(snapshotFor(store, current))[command.recordingId] ?: shared
            val confirmed = if (applied && command.action == "CANCEL")
                latest.copy(recording = latest.recording.copy(state = RecordingState.CANCELLED)) else latest
            put(context, store, PLAN + command.recordingId, iptvJson.encodeToString(confirmed.copy(
                handledCommand = command.id, outcome = if (applied) "applied" else "rejected",
                message = if (applied) "Demande appliquée" else "Demande refusée : état, horaires, conflit, stockage ou permission à vérifier sur l’appareil créateur.")), expected = current)
        } }
    }

    internal fun request(context: Context) {
        val app = context.applicationContext
        if (com.jojotv.home.progresspilot.AccountContentRouting.selected(app)) { cloud(app).kick(); return }
        if (session(app) == null) return
        synchronized(lock) {
            if (running) { again = true; return }
            running = true
        }
        work.execute {
            while (true) {
                val prefs = app.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                try {
                    syncNow(app, IptvStore(app).apply { load() })
                    prefs.edit().remove("nas_recording_sync_error").apply()
                } catch (_: Exception) {
                    // Keep both the local job and encrypted outbox; never expose a URL or token in diagnostics.
                    failed(app)
                }
                val repeat = synchronized(lock) {
                    if (again) { again = false; true } else { running = false; false }
                }
                if (!repeat) break
            }
        }
    }

    internal fun failureMessage(context: Context): String = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        .let { if (com.jojotv.home.progresspilot.AccountContentRouting.selected(context)) cloud(context).message.value
            else it.getString("nas_recording_sync_error", "").orEmpty() }

    private fun failed(context: Context) {
        context.getSharedPreferences("home_settings", Context.MODE_PRIVATE).edit().putString("nas_recording_sync_error",
            "Synchronisation des programmations NAS indisponible. Nouvelle tentative automatique.").apply()
    }

    internal fun syncNow(context: Context, store: IptvStore, preflight: Boolean = false): Unit = synchronized(networkLock) {
        if (store.cloudActivitySelected()) {
            if (preflight) cloud(context).preflight() else cloud(context).kick()
            return@synchronized
        }
        val current = session(context) ?: return@synchronized
        store.recordings(allAccounts = true).forEach { publish(context, store, it, expected = current) }
        fun request(value: IptvSyncSnapshot? = null): IptvSyncSnapshot {
            check(session(context) == current) { "NAS session changed" }
            val builder = Request.Builder().url("${current.base}/api/sync/profiles/1/secrets/jojo-home-iptv-recordings")
                .header("Authorization", "Bearer ${current.token}").header("X-JojoTV-Device-Id", store.recordingDeviceId())
            if (value != null) builder.put(iptvNasSyncBody(value))
            return client.newCall(builder.build()).execute().use { response ->
                if (value == null && response.code == 404) IptvSyncSnapshot() else {
                    check(response.isSuccessful) { "NAS recording sync unavailable" }
                    readIptvNasSyncSnapshot(checkNotNull(response.body).byteStream()) ?: IptvSyncSnapshot()
                }
            }
        }
        fun merge(remote: IptvSyncSnapshot) = synchronized(lock) mergeLock@{
            check(remote.schema == 1) { "Unsupported recording sync schema" }
            if (session(context) != current) return@mergeLock
            val previous = snapshotFor(store, current)
            val next = previous.copy(records = mergeIptvSyncRecords(previous.records, remote.records))
            if (next != previous) { store.writeRecordingSync(current.authority, next); cached = next }
        }
        val remote = request()
        if (session(context) != current) return@synchronized
        merge(remote)
        applyCommands(context, store, current)
        if (session(context) != current) return@synchronized
        val next = snapshotFor(store, current)
        if (preflight) IptvRecordingSync.request(context) else if (next.records != remote.records) merge(request(next))
    }
}
