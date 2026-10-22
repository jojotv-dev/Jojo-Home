package com.jojotv.home.iptv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.jojotv.home.MainActivity
import com.jojotv.home.SmbConnectionConfig
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable internal enum class RecordingState { PLANNED, RUNNING, COMPLETE, PARTIAL, FAILED, CANCELLED }
@Serializable internal data class IptvRecording(
    val id: String,
    val channel: IptvItem,
    val title: String,
    val start: Long,
    val end: Long,
    val state: RecordingState = RecordingState.PLANNED,
    val bytes: Long = 0,
    val file: String = "",
    val error: String = "",
    val directory: String = "",
    val accountId: String = "",
    val programStart: Long = start,
    val programEnd: Long = end,
    val beforeMinutes: Int = 0,
    val afterMinutes: Int = 0,
    val repeatDays: List<Int> = emptyList(),
    val recurrenceId: String = "",
)
@Serializable internal data class IptvReminder(val id: String, val channel: IptvItem, val title: String,
    val notifyAt: Long, val start: Long, val end: Long, val notified: Boolean = false, val accountId: String = "")

internal object IptvRecordingScheduler {
    internal val planningLock = Any()
    private fun reminderPending(context: Context, id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, IptvRecordingReceiver::class.java).setAction("${context.packageName}.REMIND.$id").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun scheduleReminder(context: Context, store: IptvStore, channel: IptvItem, program: EpgProgram) {
        val now = System.currentTimeMillis()
        require(program.start > now) { "Choisis une émission à venir." }
        requireExactAlarm(context)
        val reminder = IptvReminder(UUID.randomUUID().toString(), channel, program.title,
            maxOf(now + 1000, program.start - store.intSetting("reminderBefore", 0).coerceIn(0, 60) * 60_000L), program.start, program.end, accountId = store.selectedAccount()?.id.orEmpty())
        store.saveReminder(reminder)
        try { armReminder(context, reminder) } catch (failure: Exception) { store.removeReminder(reminder.id); throw failure }
    }
    private fun armReminder(context: Context, reminder: IptvReminder) {
        requireExactAlarm(context)
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            maxOf(System.currentTimeMillis() + 1000, reminder.notifyAt), reminderPending(context, reminder.id))
    }
    fun cancelReminder(context: Context, store: IptvStore, reminder: IptvReminder) {
        context.getSystemService(AlarmManager::class.java).cancel(reminderPending(context, reminder.id))
        context.getSystemService(NotificationManager::class.java).cancel(reminder.id.hashCode())
        store.removeReminder(reminder.id)
    }
    fun notifyReminder(context: Context, id: String) {
        val store = IptvStore(context).apply { load() }
        val reminder = store.reminders().firstOrNull { it.id == id && !it.notified } ?: return
        if (reminder.end <= System.currentTimeMillis() || store.sources().none { it.id == reminder.channel.sourceId }) return
        if (store.booleanSetting("reminderWake", false)) {
            @Suppress("DEPRECATION")
            val wake = context.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "${context.packageName}:iptv-reminder")
            wake.setReferenceCounted(false)
            wake.acquire(5_000)
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel("iptv_reminders", "Rappels IPTV", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, IptvActivity::class.java)
            .putExtra("homeItemId", reminder.channel.id).putExtra("homeShowDetails", store.setting("reminderAction", "watch") == "details"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(id.hashCode(), NotificationCompat.Builder(context, "iptv_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(reminder.title).setContentText(reminder.channel.title)
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(store.intSetting("reminderTimeout", 10).coerceIn(5, 120) * 1000L).build())
        store.saveReminder(reminder.copy(notified = true))
    }
    private fun defaultRoot(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "IPTV")
    fun root(context: Context): File {
        val path = context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE).getString("recordingFolder", "").orEmpty()
        return if (path.isBlank()) defaultRoot(context).apply { mkdirs() } else File(path)
    }
    fun rootPath(context: Context): String = context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE)
        .getString("recordingFolder", "").orEmpty().ifBlank { root(context).absolutePath }
    fun isNetwork(path: String) = path.startsWith("smb://", ignoreCase = true)
    fun isDocument(path: String) = path.startsWith("content://", ignoreCase = true)
    internal fun documentTreePath(path: String): String {
        val uri = URI(path.trim())
        val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        require(uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && segments.size == 2 && segments[0] == "tree") {
            "Choisis un dossier avec le sélecteur de système."
        }
        return uri.toString()
    }
    internal fun recordingDirectory(path: String): String = when {
        isNetwork(path) -> smbPath(path)
        isDocument(path) -> documentTreePath(path)
        else -> File(path).canonicalPath
    }
    internal fun smbPath(path: String, requireShare: Boolean = true): String {
        val uri = URI(path.trim().replace(" ", "%20"))
        require(uri.scheme.equals("smb", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "Adresse SMB invalide. Utilise smb://serveur/partage/dossier." }
        val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        require(!requireShare || segments.isNotEmpty()) { "Sélectionne un dossier partagé du serveur." }
        require(segments.all { raw ->
            val value = java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            value != "." && value != ".." && value.none { it == '/' || it == '\\' || it == '\u0000' }
        }) { "Dossier SMB invalide." }
        return "smb://${uri.rawAuthority}/${segments.joinToString("/")}".trimEnd('/')
    }
    fun connections(context: Context): List<SmbConnectionConfig> {
        val local = context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE)
        val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        return recordingConnections(SmbConnectionConfig.fromPrefsString(home.getString("smb_connections_json", ""), home.getString("smb_server", ""),
            home.getString("smb_user", ""), home.getString("smb_password", "")),
            SmbConnectionConfig.fromPrefsString(local.getString("recordingSmbConnections", ""), "", "", ""),
            local.getString("recordingSmbOverride", "").orEmpty())
    }
    internal fun recordingConnections(home: List<SmbConnectionConfig>, local: List<SmbConnectionConfig>, overrideUrl: String = ""): List<SmbConnectionConfig> {
        val preferred = local.filter { decodedSmbUrl(it.browserUrl()).equals(decodedSmbUrl(overrideUrl), true) }
        return (preferred + home + local).distinctBy { decodedSmbUrl(it.browserUrl()).lowercase(java.util.Locale.ROOT) }
    }
    private fun decodedSmbUrl(path: String) = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8").trimEnd('/') + "/"
    internal fun connectionFor(path: String, connections: List<SmbConnectionConfig>): SmbConnectionConfig? {
        val normalized = decodedSmbUrl(path)
        return connections.map { connection ->
            val folder = decodedSmbUrl(connection.browserUrl())
            val share = decodedSmbUrl(connection.shareRootUrl())
            connection to when {
                normalized.startsWith(folder, true) -> folder.length
                normalized.startsWith(share, true) -> share.length
                else -> -1
            }
        }.filter { it.second >= 0 }.maxByOrNull { it.second }?.first
    }
    fun saveConnection(context: Context, connection: SmbConnectionConfig) {
        smbPath(connection.browserUrl(), false)
        val prefs = context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE)
        val existing = SmbConnectionConfig.fromPrefsString(prefs.getString("recordingSmbConnections", ""), "", "", "")
        check(prefs.edit().putString("recordingSmbConnections", SmbConnectionConfig.toPrefsString(
            listOf(connection) + existing.filterNot { it.browserUrl().equals(connection.browserUrl(), true) }))
            .putString("recordingSmbOverride", connection.browserUrl()).commit()) {
            "Impossible de mémoriser la connexion SMB."
        }
    }
    fun smbContext(context: Context, path: String, connection: SmbConnectionConfig? = null): jcifs.CIFSContext {
        val selected = connection ?: connectionFor(path, connections(context))
        SmbConnectionConfig.ensureCryptoProvider()
        val properties = java.util.Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202"); setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "8000"); setProperty("jcifs.smb.client.soTimeout", "8000")
            setProperty("jcifs.smb.client.connTimeout", "8000"); setProperty("jcifs.smb.client.snd_buf_size", "262144")
            setProperty("jcifs.smb.client.rcv_buf_size", "262144"); setProperty("jcifs.smb.client.tcpNoDelay", "true")
        }
        val base = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(properties))
        return if (selected?.user.isNullOrBlank()) base else base.withCredentials(
            SmbConnectionConfig.authenticatorFor(selected!!.user, selected.password))
    }
    fun storage(context: Context, path: String): RecordingStorage = when {
        isNetwork(path) -> SmbRecordingStorage(smbPath(path), smbContext(context, path))
        isDocument(path) -> DocumentRecordingStorage(context, documentTreePath(path))
        else -> LocalRecordingStorage(File(path))
    }
    fun storage(context: Context, recording: IptvRecording): RecordingStorage =
        if (isDocument(recording.directory)) DocumentRecordingStorage(context, documentTreePath(recording.directory), recording.id)
        else storage(context, recordingPath(defaultRoot(context).absolutePath, recording))
    fun setRoot(context: Context, path: String) {
        if (!isNetwork(path) && !isDocument(path)) { setRoot(context, File(path)); return }
        val normalized = recordingDirectory(path)
        if (isDocument(normalized)) require(context.contentResolver.persistedUriPermissions.any {
            it.uri == Uri.parse(normalized) && it.isReadPermission && it.isWritePermission
        }) { "Sélectionne ce dossier avec le sélecteur de système pour conserver son accès."
        }
        storage(context, normalized).use { check(it.writable()) { "Dossier inaccessible en écriture." } }
        check(context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE).edit()
            .putString("recordingFolder", normalized).commit()) { "Impossible de mémoriser le dossier." }
    }
    fun setRoot(context: Context, folder: File) {
        require(folder.isAbsolute && folder.isDirectory && folder.canWrite()) { "Choisis un dossier existant accessible en écriture à Jojo Home." }
        check(context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE).edit()
            .putString("recordingFolder", folder.canonicalPath).commit()) { "Impossible de mémoriser le dossier." }
    }
    fun folder(context: Context, recording: IptvRecording): File = recordingFolder(defaultRoot(context), recording)
    internal fun recordingFolder(defaultRoot: File, recording: IptvRecording): File {
        require(recording.id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Identifiant d’enregistrement invalide." }
        val directory = when {
            recording.directory.isNotBlank() -> File(recording.directory)
            recording.file.isNotBlank() -> {
                val parent = checkNotNull(File(recording.file).parentFile)
                require(parent.name == recording.id) { "Dossier d’enregistrement invalide." }
                checkNotNull(parent.parentFile)
            }
            else -> defaultRoot
        }
        require(directory.isAbsolute) { "Le dossier doit être un chemin absolu." }
        val target = File(directory, recording.id)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "Dossier d’enregistrement invalide." }
        return target
    }
    internal fun recordingPath(defaultRoot: String, recording: IptvRecording): String {
        require(recording.id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Identifiant d’enregistrement invalide." }
        val directory = recording.directory.ifBlank {
            if (isNetwork(recording.file)) {
                val parent = recording.file.substringBeforeLast('/')
                require(parent.substringAfterLast('/') == recording.id) { "Dossier d’enregistrement invalide." }
                parent.substringBeforeLast('/')
            } else if (recording.file.isNotBlank()) {
                val parent = checkNotNull(File(recording.file).parentFile)
                require(parent.name == recording.id) { "Dossier d’enregistrement invalide." }
                checkNotNull(parent.parentFile).absolutePath
            } else defaultRoot
        }
        return if (isNetwork(directory)) "${smbPath(directory)}/${recording.id}"
            else recordingFolder(File(defaultRoot), recording).canonicalPath
    }
    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, IptvRecordingReceiver::class.java).setAction("${context.packageName}.RECORD.$id").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    internal data class Window(val start: Long, val end: Long)
    internal fun moveStart(start: Long, end: Long, newStart: Long): Window =
        Window(newStart, newStart + if (end > start) end - start else 60_000L)

    internal fun recurringProgram(start: Long, end: Long, days: List<Int>, after: Long,
                                  zone: ZoneId = ZoneId.systemDefault()): Window {
        require(start > 0 && end > start && days.isNotEmpty() && days.all { it in 1..7 }) { "Choisis au moins un jour de la semaine." }
        val beginning = Instant.ofEpochMilli(start).atZone(zone)
        val ending = Instant.ofEpochMilli(end).atZone(zone)
        val dayCount = ChronoUnit.DAYS.between(beginning.toLocalDate(), ending.toLocalDate())
        val firstDate = Instant.ofEpochMilli(maxOf(start, after)).atZone(zone).toLocalDate()
        for (offset in 0L..7L) {
            val date = firstDate.plusDays(offset)
            val from = date.atTime(beginning.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
            if (date.dayOfWeek.value in days && from >= start && from > after) {
                val until = date.plusDays(dayCount).atTime(ending.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                require(until > from) { "La fin doit être postérieure au début." }
                return Window(from, until)
            }
        }
        error("Aucune prochaine occurrence disponible.")
    }
    internal fun window(start: Long, end: Long, beforeMinutes: Int = 0, afterMinutes: Int = 0,
                        now: Long = System.currentTimeMillis()): Window {
        require(beforeMinutes in 0..60 && afterMinutes in 0..60) { "Les marges doivent être comprises entre 0 et 60 minutes." }
        require(start > 0 && end > maxOf(start, now) && end <= Long.MAX_VALUE - afterMinutes * 60_000L) {
            "Choisis une émission ou un horaire à venir."
        }
        return Window(maxOf(now, start - beforeMinutes * 60_000L), end + afterMinutes * 60_000L)
    }
    internal fun conflict(recordings: List<IptvRecording>, start: Long, end: Long, excludedId: String = ""): IptvRecording? =
        recordings.firstOrNull { it.id != excludedId && it.state in setOf(RecordingState.PLANNED, RecordingState.RUNNING) &&
            start < it.end && end > it.start }
    internal data class Plan(val program: Window, val capture: Window)
    internal fun plan(start: Long, end: Long, beforeMinutes: Int = 0, afterMinutes: Int = 0,
                      repeatDays: List<Int> = emptyList(), now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Plan {
        val program = if (repeatDays.isEmpty()) Window(start, end) else recurringProgram(start, end, repeatDays, start - 1, zone)
        val capture = window(program.start, program.end, beforeMinutes, afterMinutes, now)
        var previousEnd = capture.end
        var previousStart = program.start
        repeat(repeatDays.distinct().size) {
            val next = recurringProgram(program.start, program.end, repeatDays, previousStart, zone)
            val nextCapture = window(next.start, next.end, beforeMinutes, afterMinutes, now)
            require(previousEnd <= nextCapture.start) { "Les occurrences de cet enregistrement se chevauchent avec les marges choisies." }
            previousStart = next.start; previousEnd = nextCapture.end
        }
        return Plan(program, capture)
    }
    internal fun replanned(recording: IptvRecording, channel: IptvItem, title: String, start: Long, end: Long,
                           beforeMinutes: Int = 0, afterMinutes: Int = 0,
                           now: Long = System.currentTimeMillis(), repeatDays: List<Int> = recording.repeatDays,
                           zone: ZoneId = ZoneId.systemDefault()): IptvRecording {
        check(recording.state == RecordingState.PLANNED) { "Seul un enregistrement programmé peut être modifié." }
        require(channel.type == ContentType.LIVE) { "Choisis une chaîne TV." }
        val (program, capture) = plan(start, end, beforeMinutes, afterMinutes, repeatDays, now, zone)
        return recording.copy(channel = channel, title = title, start = capture.start, end = capture.end,
            programStart = program.start, programEnd = program.end, beforeMinutes = beforeMinutes, afterMinutes = afterMinutes,
            repeatDays = repeatDays.distinct().sorted(), recurrenceId = recording.recurrenceId.ifBlank {
                if (repeatDays.isEmpty()) "" else recording.id
            })
    }
    private fun recurringWindows(recording: IptvRecording, from: Long, until: Long, zone: ZoneId): List<Window> {
        if (recording.repeatDays.isEmpty()) return listOf(Window(recording.start, recording.end))
        val windows = mutableListOf<Window>()
        var after = maxOf(recording.programStart - 1,
            from - (recording.programEnd - recording.programStart) - recording.afterMinutes * 60_000L - 86_400_000L)
        while (true) {
            val program = recurringProgram(recording.programStart, recording.programEnd, recording.repeatDays, after, zone)
            val capture = window(program.start, program.end, recording.beforeMinutes, recording.afterMinutes, now = 0)
            if (capture.start >= until) break
            if (capture.end > from) windows += capture
            after = program.start
        }
        return windows
    }
    internal fun planningConflict(recordings: List<IptvRecording>, candidate: IptvRecording,
                                  zone: ZoneId = ZoneId.systemDefault()): IptvRecording? =
        recordings.firstOrNull { other ->
            if (other.id == candidate.id || other.state !in setOf(RecordingState.PLANNED, RecordingState.RUNNING)) false
            else if (conflict(listOf(other), candidate.start, candidate.end, candidate.id) != null) true
            else if (candidate.repeatDays.isEmpty() && other.repeatDays.isEmpty()) false
            else {
                val from = maxOf(candidate.start, other.start)
                val end = maxOf(candidate.end, other.end)
                val until = if (end <= Long.MAX_VALUE - 8 * 86_400_000L) end + 8 * 86_400_000L else end
                val first = recurringWindows(candidate, from, until, zone)
                val second = recurringWindows(other, from, until, zone)
                first.any { a -> second.any { b -> a.start < b.end && a.end > b.start } }
            }
        }
    private fun recurrenceKey(recording: IptvRecording) = recording.recurrenceId.ifBlank { recording.id }
    private fun disabledRecurrences(context: Context) = context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE)
        .getStringSet("disabledRecordingRecurrences", emptySet()).orEmpty().toSet()
    private fun recurrenceEnabled(context: Context, id: String, enabled: Boolean) {
        val values = disabledRecurrences(context).toMutableSet().apply { if (enabled) remove(id) else add(id) }
        check(context.getSharedPreferences("iptv_local", Context.MODE_PRIVATE).edit()
            .putStringSet("disabledRecordingRecurrences", values).commit()) { "Impossible de mémoriser la récurrence." }
    }
    internal fun repeating(context: Context, recording: IptvRecording) =
        recording.repeatDays.isNotEmpty() && recurrenceKey(recording) !in disabledRecurrences(context)
    internal fun nextRecurringProgram(recording: IptvRecording, now: Long = System.currentTimeMillis(),
                                     zone: ZoneId = ZoneId.systemDefault()): Window {
        var after = maxOf(recording.programStart, now - (recording.programEnd - recording.programStart)
            - recording.afterMinutes * 60_000L - 86_400_000L)
        while (true) {
            val program = recurringProgram(recording.programStart, recording.programEnd, recording.repeatDays, after, zone)
            if (program.end + recording.afterMinutes * 60_000L > now) return program
            after = program.start
        }
    }
    private fun planningRecords(context: Context, records: List<IptvRecording>): List<IptvRecording> {
        val disabled = disabledRecurrences(context)
        return records.map { if (recurrenceKey(it) in disabled) it.copy(repeatDays = emptyList()) else it }
    }
    internal fun cancelRecurrence(context: Context, store: IptvStore, recording: IptvRecording): Unit = synchronized(planningLock) {
        val current = store.recordings().firstOrNull { it.id == recording.id } ?: error("Cet enregistrement n’est plus disponible pour ce compte.")
        val key = recurrenceKey(current)
        recurrenceEnabled(context, key, false)
        store.recordings(allAccounts = true).filter { recurrenceKey(it) == key && it.state == RecordingState.PLANNED }.forEach {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context, it.id))
            store.saveRecording(it.copy(state = RecordingState.CANCELLED))
        }
    }
    internal fun renewRecurrence(context: Context, store: IptvStore, recording: IptvRecording,
                                 now: Long = System.currentTimeMillis()): IptvRecording? = synchronized(planningLock) {
        val records = store.recordings(allAccounts = true)
        val current = records.filter { recurrenceKey(it) == recurrenceKey(recording) }.maxByOrNull { it.programStart }
            ?: return@synchronized null
        if (current.id != recording.id || !repeating(context, current) ||
            current.state !in setOf(RecordingState.COMPLETE, RecordingState.PARTIAL, RecordingState.FAILED)) return@synchronized null
        val program = nextRecurringProgram(current, now)
        val next = replanned(current.copy(id = UUID.randomUUID().toString(), state = RecordingState.PLANNED,
            bytes = 0, file = "", error = "", recurrenceId = recurrenceKey(current)), current.channel, current.title,
            program.start, program.end, current.beforeMinutes, current.afterMinutes, now, current.repeatDays)
        check(planningConflict(planningRecords(context, records), next) == null) { "Impossible de programmer la prochaine occurrence : un autre enregistrement est prévu." }
        requireExactAlarm(context)
        store.saveRecording(next)
        try { arm(context, next) } catch (failure: Exception) { store.removeRecording(next.id); throw failure }
        if (current.error.startsWith("Impossible de programmer la prochaine occurrence") || current.error.startsWith("Impossible de rétablir la prochaine occurrence"))
            store.saveRecording(current.copy(error = ""))
        next
    }
    private fun requireStorage(context: Context, directory: String) {
        storage(context, directory).use {
            check(it.writable()) { "Le dossier des enregistrements est inaccessible." }
            check(it.freeBytes() > 100 * 1024 * 1024) { "Stockage insuffisant pour enregistrer." }
        }
    }
    fun schedule(context: Context, store: IptvStore, channel: IptvItem, title: String, start: Long, end: Long,
                 beforeMinutes: Int = 0, afterMinutes: Int = 0, repeatDays: List<Int> = emptyList()): IptvRecording = synchronized(planningLock) {
        require(channel.type == ContentType.LIVE) { "Choisis une chaîne TV." }
        val directory = rootPath(context)
        requireStorage(context, directory)
        val original = IptvRecording(UUID.randomUUID().toString(), channel, title, start, end,
            directory = recordingDirectory(directory),
            accountId = store.selectedAccount()?.id.orEmpty())
        val record = replanned(original, channel, title, start, end, beforeMinutes, afterMinutes, repeatDays = repeatDays)
        check(planningConflict(planningRecords(context, store.recordings(allAccounts = true)), record) == null) {
            "Un autre enregistrement est déjà prévu pendant cette plage horaire ou sur un jour de récurrence."
        }
        if (record.repeatDays.isNotEmpty() || record.start > System.currentTimeMillis() + 1000) requireExactAlarm(context)
        store.saveRecording(record)
        try { arm(context, record) } catch (failure: Exception) { store.removeRecording(record.id); throw failure }
        record
    }
    internal fun reschedule(context: Context, store: IptvStore, recordingId: String, channel: IptvItem,
                            title: String, start: Long, end: Long, beforeMinutes: Int = 0,
                            afterMinutes: Int = 0, repeatDays: List<Int> = emptyList(),
                            authorizedAccount: String? = null): IptvRecording = synchronized(planningLock) {
        val previous = store.recordings(allAccounts = authorizedAccount != null).firstOrNull { it.id == recordingId }
            ?: error("Cette programmation n’est plus disponible pour ce compte.")
        val updated = replanned(previous, channel, title, start, end, beforeMinutes, afterMinutes, repeatDays = repeatDays)
        requireStorage(context, previous.directory.ifBlank { defaultRoot(context).absolutePath })
        check(planningConflict(planningRecords(context, store.recordings(allAccounts = true)), updated) == null) {
            "Un autre enregistrement est déjà prévu pendant cette plage horaire ou sur un jour de récurrence."
        }
        check(if (authorizedAccount == null) store.ownsAccount(previous.accountId)
            else store.recordingAccount(previous) == store.resolveAccountId(authorizedAccount)) {
            "Cette programmation n’est plus disponible pour ce compte."
        }
        if (updated.repeatDays.isNotEmpty() || updated.start > System.currentTimeMillis() + 1000) requireExactAlarm(context)
        val wasEnabled = recurrenceKey(previous) !in disabledRecurrences(context)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, previous.id))
        try {
            store.saveRecording(updated)
            if (updated.recurrenceId.isNotBlank()) recurrenceEnabled(context, recurrenceKey(updated), repeatDays.isNotEmpty())
            arm(context, updated)
        } catch (failure: Exception) {
            try {
                store.saveRecording(previous)
                if (updated.recurrenceId.isNotBlank()) recurrenceEnabled(context, recurrenceKey(updated), wasEnabled)
                arm(context, previous)
            }
            catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
        updated
    }
    internal fun ensureArmed(context: Context, recording: IptvRecording) {
        check(recording.state == RecordingState.PLANNED && recording.end > System.currentTimeMillis())
        // Reuse the existing PendingIntent identity; retries replace the same alarm.
        arm(context, recording)
    }

    private fun requireExactAlarm(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) { "Autorise les alarmes et rappels pour programmer un enregistrement." }
    }
    fun arm(context: Context, recording: IptvRecording) {
        if (recording.start <= System.currentTimeMillis() + 1000) start(context, recording.id)
        else {
            requireExactAlarm(context)
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                recording.start, pending(context, recording.id))
        }
    }
    fun start(context: Context, id: String) {
        ContextCompat.startForegroundService(context, Intent(context, IptvRecordingService::class.java).putExtra("id", id))
    }
    fun cancel(context: Context, store: IptvStore, recording: IptvRecording): Unit = synchronized(planningLock) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, recording.id))
        val current = store.recordings(allAccounts = true).firstOrNull { it.id == recording.id }
        if (current?.state == RecordingState.PLANNED) {
            if (current.repeatDays.isNotEmpty()) recurrenceEnabled(context, recurrenceKey(current), false)
            store.saveRecording(current.copy(state = RecordingState.CANCELLED))
        } else if (active(recording.id)) {
            context.startService(Intent(context, IptvRecordingService::class.java).setAction("STOP").putExtra("id", recording.id))
        }
    }
    private fun active(id: String) = IptvRecordingService.activeId == id
    fun restore(context: Context, store: IptvStore) {
        val now = System.currentTimeMillis()
        store.reminders(allAccounts = true).forEach { reminder ->
            if (reminder.end <= now || store.sources().none { it.id == reminder.channel.sourceId }) cancelReminder(context, store, reminder)
            else if (!reminder.notified) runCatching { armReminder(context, reminder) }
        }
        synchronized(planningLock) {
            store.recordings(allAccounts = true).forEach { recording ->
                if (recording.state == RecordingState.RUNNING && IptvRecordingService.activeId != recording.id) {
                    store.saveRecording(recovered(context, recording, "Enregistrement interrompu par l’arrêt de l’application ou de l’appareil."))
                } else if (recording.state == RecordingState.PLANNED) {
                    if (recording.end <= now) store.saveRecording(recording.copy(state = RecordingState.FAILED, error = "Appareil indisponible à l’heure prévue."))
                    else runCatching { arm(context, recording) }.onFailure {
                        store.saveRecording(recording.copy(state = RecordingState.FAILED, error = "Impossible de rétablir l’alarme d’enregistrement."))
                    }
                }
            }
            store.recordings(allAccounts = true).groupBy(::recurrenceKey).values.mapNotNull { series -> series.maxByOrNull { it.programStart } }
                .filter { repeating(context, it) && it.state in setOf(RecordingState.COMPLETE, RecordingState.PARTIAL, RecordingState.FAILED) }
                .forEach { recording ->
                    runCatching { renewRecurrence(context, store, recording, now) }.onFailure {
                        store.saveRecording(recording.copy(error = "Impossible de rétablir la prochaine occurrence. Vérifiez les alarmes et les conflits."))
                    }
                }
        }
    }
    fun playable(folder: File): File? = listOf(File(folder, "recording.m3u8"), File(folder, "recording.ts")).firstOrNull { it.isFile && it.length() > 0 }
    fun finishManifest(file: File?) {
        if (file?.extension == "m3u8" && !file.readText().contains("#EXT-X-ENDLIST")) file.appendText("#EXT-X-ENDLIST\n")
    }
    fun playable(folder: RecordingStorage): String? = listOf("recording.m3u8", "recording.ts").firstOrNull { folder.length(it) > 0 }
    fun finishManifest(folder: RecordingStorage, name: String?) {
        if (name == "recording.m3u8") {
            val lines = folder.input(name).bufferedReader().use { it.readLines() }
            if ("#EXT-X-ENDLIST" !in lines) folder.writeManifest(lines + "#EXT-X-ENDLIST")
        }
    }
    fun recovered(context: Context, recording: IptvRecording, error: String): IptvRecording =
        recovered(recording, error) { storage(context, recording) }
    internal fun recovered(recording: IptvRecording, error: String, open: () -> RecordingStorage): IptvRecording {
        // Keep the known file and byte count when the share is temporarily offline.
        return runCatching {
            open().use { folder ->
                val name = playable(folder)
                runCatching { finishManifest(folder, name) }
                recording.copy(state = if (name == null) RecordingState.FAILED else RecordingState.PARTIAL,
                    file = name?.let(folder::path).orEmpty(), bytes = folder.bytes(), error = error)
            }
        }.getOrElse { recording.copy(state = if (recording.bytes > 0 || recording.file.isNotBlank()) RecordingState.PARTIAL else RecordingState.FAILED,
            error = error) }
    }
}

internal class DocumentRecordingStorage(context: Context, path: String, private val recordingId: String? = null) : RecordingStorage {
    private val resolver = context.contentResolver
    private val root = checkNotNull(DocumentFile.fromTreeUri(context, Uri.parse(path))) { "Dossier système invalide." }
    private var folder: DocumentFile?
    private val files = ConcurrentHashMap<String, DocumentFile>()
    init {
        require(recordingId == null || recordingId.matches(Regex("[a-fA-F0-9-]{36}"))) { "Identifiant d’enregistrement invalide." }
        folder = if (recordingId == null) root else root.findFile(recordingId)
    }
    override val directory: String get() = checkNotNull(folder) { "Dossier d’enregistrement introuvable." }.uri.toString()
    override fun create() {
        if (folder == null && recordingId != null) folder = root.createDirectory(recordingId)
        check(folder?.isDirectory == true && writable()) { "Dossier d’enregistrement inaccessible en écriture." }
    }
    override fun writable(): Boolean {
        val target = folder ?: return false
        if (!target.isDirectory || !target.canRead() || !target.canWrite()) return false
        return resolver.query(target.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && (it.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        } ?: false
    }
    private fun file(name: String, create: Boolean = false): DocumentFile? {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "Nom de fichier d’enregistrement invalide."
        }
        files[name]?.let { return it }
        val target = folder ?: return null
        val document = target.findFile(name) ?: if (create) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.')) ?: when {
                name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                name.endsWith(".m4s") -> "video/iso.segment"
                else -> "application/octet-stream"
            }
            checkNotNull(target.createFile(mime, name)) { "Impossible de créer le fichier d’enregistrement." }.also {
                check(it.name == name) { "Ce stockage ne conserve pas les noms des fichiers d’enregistrement." }
            }
        } else null
        return document?.also { check(it.isFile) { "Fichier d’enregistrement invalide." }; files[name] = it }
    }
    override fun output(name: String): OutputStream = checkNotNull(resolver.openOutputStream(
        checkNotNull(file(name, true)) { "Dossier d’enregistrement introuvable." }.uri, "wt")) { "Fichier inaccessible en écriture." }
    override fun input(name: String, offset: Long): InputStream {
        require(offset >= 0)
        val input = checkNotNull(resolver.openInputStream(checkNotNull(file(name)) { "Fichier d’enregistrement introuvable." }.uri))
        try {
            var remaining = offset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped else {
                    check(input.read() != -1) { "Position de lecture hors du fichier." }; remaining--
                }
            }
            return input
        } catch (failure: Exception) { input.close(); throw failure }
    }
    override fun path(name: String) = checkNotNull(file(name)) { "Fichier d’enregistrement introuvable." }.uri.toString()
    override fun length(name: String) = file(name)?.length() ?: 0L
    override fun bytes() = folder?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    // SAF exposes no portable free-space query; provider write failures still stop capture.
    override fun freeBytes() = Long.MAX_VALUE
    override fun trimTransportStream() {
        val document = file("recording.ts") ?: return
        checkNotNull(resolver.openFileDescriptor(document.uri, "rw")).use {
            val size = Os.fstat(it.fileDescriptor).st_size
            Os.ftruncate(it.fileDescriptor, size / 188 * 188)
        }
    }
    override fun writeManifest(lines: List<String>) {
        val name = "recording.m3u8"
        val previous = if (length(name) > 0) input(name).bufferedReader().use { it.readLines() } else emptyList()
        check(lines.take(previous.size) == previous) { "La playlist de l’enregistrement a été modifiée." }
        if (lines.size == previous.size) return
        // Appending preserves earlier segments without relying on an unsupported atomic rename.
        val document = checkNotNull(file(name, true)) { "Dossier d’enregistrement introuvable." }
        checkNotNull(resolver.openOutputStream(document.uri, if (previous.isEmpty()) "wt" else "wa")).bufferedWriter().use {
            it.write(lines.drop(previous.size).joinToString("\n", postfix = "\n"))
        }
    }
    override fun delete() {
        require(recordingId != null) { "Le dossier racine des enregistrements ne peut pas être supprimé." }
        folder?.let { check(it.isDirectory && it.delete()) { "Impossible de supprimer l’enregistrement du dossier système." } }
        files.clear(); folder = null
    }
}

internal class SmbRecordingStorage(override val directory: String, private val context: jcifs.CIFSContext) : RecordingStorage {
    private val folder = jcifs.smb.SmbFile("$directory/", context)
    private fun file(name: String) = jcifs.smb.SmbFile(folder, name)
    override fun create() { if (!folder.exists()) folder.mkdirs() }
    override fun writable() = folder.isDirectory && folder.canWrite()
    override fun output(name: String): OutputStream {
        val remote = file(name)
        val output = try { remote.outputStream } catch (failure: Exception) { remote.close(); throw failure }
        return object : java.io.FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
            override fun close() { try { super.close() } finally { remote.close() } }
        }
    }
    override fun input(name: String, offset: Long): InputStream {
        val remote = file(name)
        val input = try { remote.openInputStream().apply { check(skip(offset) == offset) } }
            catch (failure: Exception) { remote.close(); throw failure }
        return object : java.io.FilterInputStream(input) {
            override fun close() { try { super.close() } finally { remote.close() } }
        }
    }
    override fun length(name: String) = file(name).use { if (it.isFile) it.length() else 0L }
    override fun bytes() = if (!folder.exists()) 0L else folder.listFiles().sumOf { it.use { entry -> entry.length() } }
    override fun freeBytes() = folder.diskFreeSpace
    override fun trimTransportStream() { file("recording.ts").use { remote ->
        if (remote.exists()) remote.openRandomAccess("rw").use { it.setLength(it.length() / 188 * 188) }
    } }
    override fun writeManifest(lines: List<String>) {
        file("manifest.tmp").use { temporary ->
            temporary.outputStream.bufferedWriter().use { it.write(lines.joinToString("\n", postfix = "\n")) }
            file("recording.m3u8").use { temporary.renameTo(it, true) }
        }
    }
    override fun delete() { if (folder.exists()) folder.delete(); check(!folder.exists()) { "Impossible de supprimer l’enregistrement SMB." } }
    override fun close() { folder.close(); context.close() }
}

/** The existing Media3 player reads TS and relative HLS segments through loopback. */
internal class IptvRecordingPlayback(private val storage: RecordingStorage) : NanoHTTPD("127.0.0.1", 0) {
    private val token = UUID.randomUUID().toString()
    fun url(name: String) = "http://127.0.0.1:$listeningPort/$token/$name"
    override fun serve(session: IHTTPSession): Response {
        val name = session.uri.removePrefix("/$token/")
        if (!session.uri.startsWith("/$token/") || !playbackFile(name) || session.method !in listOf(Method.GET, Method.HEAD))
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Fichier indisponible.")
        return try {
            val size = storage.length(name)
            if (size <= 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Fichier indisponible.")
            val range = playbackRange(session.headers["range"], size)
                ?: return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Plage invalide.")
                    .apply { addHeader("Content-Range", "bytes */$size") }
            val mime = when (name.substringAfterLast('.')) {
                "m3u8" -> "application/vnd.apple.mpegurl"; "ts" -> "video/mp2t"; else -> "video/mp4"
            }
            newFixedLengthResponse(if (session.headers["range"] == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                mime, storage.input(name, range.first), range.last - range.first + 1).apply {
                addHeader("Accept-Ranges", "bytes")
                if (session.headers["range"] != null) addHeader("Content-Range", "bytes ${range.first}-${range.last}/$size")
            }
        } catch (_: Exception) { newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stockage indisponible.") }
    }
    override fun stop() { super.stop(); storage.close() }
    companion object {
        internal fun playbackFile(name: String) = name in listOf("recording.ts", "recording.m3u8") ||
            name.matches(Regex("(?:part-[0-9]+\\.(?:ts|m4s)|init-[0-9]+\\.mp4)"))
        internal fun playbackRange(header: String?, size: Long): LongRange? {
            if (size <= 0) return null
            if (header == null) return 0L until size
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header) ?: return null
            val first = match.groupValues[1]
            val last = match.groupValues[2]
            if (first.isBlank()) {
                val count = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return maxOf(0, size - count) until size
            }
            val start = first.toLongOrNull()?.takeIf { it < size } ?: return null
            val end = if (last.isBlank()) size - 1 else last.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
            return (start..end).takeIf { start <= end }
        }
    }
}

class IptvRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val result = goAsync()
            Thread {
                try {
                    val store = IptvStore(context); store.load()
                    IptvRecordingSync.request(context)
                    IptvRecordingScheduler.restore(context, store)
                }
                finally { result.finish() }
            }.start()
        } else if (intent.action?.startsWith("${context.packageName}.REMIND.") == true) {
            val result = goAsync()
            Thread {
                try { intent.getStringExtra("id")?.let { IptvRecordingScheduler.notifyReminder(context, it) } }
                finally { result.finish() }
            }.start()
        } else intent.getStringExtra("id")?.let { IptvRecordingScheduler.start(context, it) }
    }
}

class IptvRecordingService : Service() {
    companion object { @Volatile internal var activeId = ""; private const val NOTIFICATION = 8401 }
    private val worker = Executors.newSingleThreadExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private var capture: IptvRecordingStream? = null
    private var wake: PowerManager.WakeLock? = null
    private var interruption = ""
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id").orEmpty()
        if (intent?.action == "STOP") {
            if (id == activeId) capture?.stop()
            if (activeId.isBlank()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeId.isNotBlank()) return START_NOT_STICKY
        activeId = id
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel("iptv_recordings", "Enregistrements IPTV", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("Préparation de l’enregistrement…", id))
        worker.execute {
            val store = IptvStore(this)
            var recording: IptvRecording? = null
            try {
                store.load()
                runCatching { IptvRecordingSync.syncNow(this, store, preflight = true) }
                val (current, source) = synchronized(IptvRecordingScheduler.planningLock) {
                    val planned = store.recordings(allAccounts = true).firstOrNull { it.id == id && it.state == RecordingState.PLANNED }
                        ?: return@execute
                    if (planned.repeatDays.isNotEmpty() && !IptvRecordingScheduler.repeating(this, planned)) {
                        store.saveRecording(planned.copy(state = RecordingState.CANCELLED)); return@execute
                    }
                    // An alarm already in flight must not start a recording moved to a later time.
                    if (planned.start > System.currentTimeMillis() + 1000) return@execute
                    recording = planned
                    check(planned.end > System.currentTimeMillis()) { "L’horaire de cet enregistrement est dépassé." }
                    val provider = store.sources().firstOrNull { it.id == planned.channel.sourceId } ?: error("Le fournisseur a été supprimé.")
                    recording = planned.copy(state = RecordingState.RUNNING)
                    store.saveRecording(recording!!)
                    planned to provider
                }
                notifications.notify(NOTIFICATION, notification(if (store.ownsAccount(current.accountId)) current.title else "Enregistrement en cours…", id))
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:iptv-recording").apply {
                    acquire((current.end - System.currentTimeMillis()).coerceAtLeast(1000) + 60_000)
                }
                val engine = IptvRecordingStream().also { capture = it }
                val stop = timers.schedule({ engine.stop() }, (current.end - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                try {
                    val stream = providerFor(store.connectionSource(source), udpProxy = store.setting("udpProxy", "")).resolve(current.channel)
                    IptvRecordingScheduler.storage(this, current).use { folder ->
                        engine.capture(stream, folder, current.end) { bytes ->
                            recording = current.copy(state = RecordingState.RUNNING, bytes = bytes,
                                file = IptvRecordingScheduler.playable(folder)?.let(folder::path).orEmpty())
                            store.saveRecording(recording!!)
                        }
                    }
                } catch (failure: Exception) {
                    if (!engine.stopped.get()) throw failure
                } finally { stop.cancel(false) }
                check(interruption.isBlank()) { interruption }
                IptvRecordingScheduler.storage(this, current).use { folder ->
                    val name = IptvRecordingScheduler.playable(folder)
                    check(name != null) { "Aucune vidéo n’a pu être enregistrée." }
                    IptvRecordingScheduler.finishManifest(folder, name)
                    store.saveRecording(current.copy(state = RecordingState.COMPLETE, file = folder.path(name), bytes = folder.bytes()))
                }
            } catch (failure: Exception) {
                recording?.let { current ->
                    store.saveRecording(IptvRecordingScheduler.recovered(this, current,
                        if (failure is IllegalStateException) failure.message.orEmpty() else "Flux interrompu ou stockage indisponible."))
                }
            } finally {
                wake?.takeIf { it.isHeld }?.release(); wake = null; capture = null; activeId = ""
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                recording?.let { previous ->
                    runCatching { IptvRecordingScheduler.renewRecurrence(applicationContext, store, previous) }.onFailure {
                        synchronized(IptvRecordingScheduler.planningLock) {
                            store.recordings(allAccounts = true).firstOrNull { it.id == previous.id }?.let {
                                store.saveRecording(it.copy(error = "Impossible de programmer la prochaine occurrence. Vérifiez les alarmes et les conflits."))
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(title: String, id: String): android.app.Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, IptvRecordingService::class.java).setAction("STOP").putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 8401, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "iptv_recordings").setSmallIcon(android.R.drawable.presence_video_busy)
            .setContentTitle("Enregistrement IPTV").setContentText(title).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter", stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { interruption = "La durée maximale du service Android a été atteinte."; capture?.stop(); stopSelf(startId) }
    override fun onDestroy() {
        capture?.stop(); worker.shutdown(); timers.shutdownNow()
        super.onDestroy()
    }
}
