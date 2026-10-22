package com.jojotv.home.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.json.JSONObject
import java.io.File
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import kotlin.math.roundToInt
import com.nuvio.tv.domain.model.WatchProgress
import com.jojotv.home.HomeWatchProgressEntryPoint
import com.jojotv.home.HomeWatchProgressBridge
import com.jojotv.home.NuvioPlayerActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody

@Serializable internal data class IptvSyncRecord(val changedAt: Long, val writer: String, val value: String? = null)
@Serializable internal data class IptvSyncSnapshot(val schema: Int = 1, val records: Map<String, IptvSyncRecord> = emptyMap())
@Serializable internal data class IptvSyncPlayback(val item: IptvItem, val playedAt: Long)
internal data class IptvCloudActivity(val account: String, val item: IptvItem, val favorite: Boolean, val playedAt: Long)
internal data class IptvCloudRestore(val activity: IptvCloudActivity?)

internal fun IptvLibrary.supabaseActivitySnapshot(): List<IptvCloudActivity> {
    val current = checkpointAccount(); val items = current.remembered.associateBy { it.id }
    return current.accountStates.flatMap { (account, state) -> (state.favorites + state.history).mapNotNull { id ->
        items[id]?.let { IptvCloudActivity(account, it, id in state.favorites, state.playedAt[id]?.takeIf { id in state.history } ?: 0) }
    } }
}

internal fun IptvLibrary.withSupabaseActivityIfUnchanged(account: String, kind: String, id: String,
    item: IptvItem?, playedAt: Long, deleted: Boolean, expected: IptvCloudActivity?): IptvLibrary? {
    require(kind in setOf("favorite", "history"))
    val current = supabaseActivitySnapshot().firstOrNull { it.account == account && it.item.id == id }
    if (current != expected || accounts.none { it.id == account }) return null
    if (!deleted && (item == null || sources.none { it.id == item.sourceId })) return null
    return withSupabaseActivity(account, kind, id, item, playedAt, deleted)
}

/** Local metadata resolves accepted cloud rows; this never imports a playback position. */
internal fun iptvCloudProgressTemplate(item: IptvItem, contentId: String): WatchProgress? {
    if (item.type !in setOf(ContentType.MOVIE, ContentType.EPISODE) || item.scheduledEpisode) return null
    return WatchProgress(contentId, if (item.type == ContentType.MOVIE) "movie" else "series", item.title,
        item.artwork, item.backdrop, item.titleLogo, item.id, item.season.takeIf { item.type == ContentType.EPISODE },
        item.episode.takeIf { item.type == ContentType.EPISODE }, item.title, 0, 0, 1, description = item.description)
}

internal fun iptvHomeAccountChanged(previous: IptvLibrary?, current: IptvLibrary): Boolean =
    previous == null || previous.accounts != current.accounts || previous.selectedAccountId != current.selectedAccountId

/** NAS records remain opaque locally. Cloud activity has its own encrypted, typed protocol. */
internal fun iptvCloudActivityRecord(key: String): Boolean = key.startsWith("favorite:") || key.startsWith("play:") ||
    key.startsWith("account:") && (key.substringAfter(':').substringAfter(':').startsWith("favorite:") ||
        key.substringAfter(':').substringAfter(':').startsWith("play:"))

internal fun preserveCloudIptvActivity(local: IptvLibrary, remote: IptvLibrary): IptvLibrary {
    val a = local.checkpointAccount(); val b = remote.checkpointAccount()
    val states = b.accountStates.mapValues { (id, state) -> a.accountStates[id]?.let {
        state.copy(favorites = it.favorites, history = it.history, playedAt = it.playedAt)
    } ?: state.copy(favorites = emptySet(), history = emptyList(), playedAt = emptyMap()) }
    val selected = states[b.selectedAccountId] ?: IptvAccountState()
    return b.copy(favorites = selected.favorites, history = selected.history, playedAt = selected.playedAt,
        remembered = (a.remembered + b.remembered).distinctBy { it.id },
        accountStates = states)
}
@Serializable private data class IptvNasSyncEnvelope(val value: IptvSyncSnapshot? = null)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun readIptvNasSyncSnapshot(input: InputStream): IptvSyncSnapshot? =
    iptvJson.decodeFromStream<IptvNasSyncEnvelope>(input).value

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun writeIptvNasSyncSnapshot(snapshot: IptvSyncSnapshot, output: OutputStream) {
    iptvJson.encodeToStream(IptvNasSyncEnvelope(snapshot), output)
}

internal fun iptvNasSyncBody(snapshot: IptvSyncSnapshot): RequestBody = object : RequestBody() {
    // Keep Content-Length for NAS servers that require it, without retaining
    // another complete UTF-8 payload beside the library and remote snapshot.
    private val length by lazy {
        var bytes = 0L
        writeIptvNasSyncSnapshot(snapshot, object : OutputStream() {
            override fun write(value: Int) { bytes++ }
            override fun write(value: ByteArray, offset: Int, count: Int) { bytes += count }
        })
        bytes
    }
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength() = length
    override fun writeTo(sink: okio.BufferedSink) {
        writeIptvNasSyncSnapshot(snapshot, sink.outputStream())
    }
}

internal fun iptvRecoveryLibrary(snapshot: IptvSyncSnapshot): IptvLibrary {
    check(snapshot.schema == 1) { "Version de synchronisation IPTV incompatible." }
    return mergeIptvLibrary(IptvLibrary(), snapshot.records).also {
        check(it.sources.isNotEmpty()) { "La copie NAS ne contient aucun fournisseur IPTV à récupérer." }
    }
}

/** Repair authenticated-storage failures once; healthy libraries never enter recovery. */
internal fun <T> iptvReadWithRecovery(read: () -> T, recover: () -> Unit): T {
    try {
        return read()
    } catch (problem: IllegalStateException) {
        if (problem.cause !is javax.crypto.AEADBadTagException) throw problem
        try { recover() } catch (failure: Exception) {
            problem.addSuppressed(failure)
            throw problem
        }
    }
    return read()
}

private val iptvLegacyStorageProvider by lazy { org.bouncycastle.jce.provider.BouncyCastleProvider() }

private fun iptvStreamingStorageCipher(): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding", iptvLegacyStorageProvider)

internal fun iptvStorageCipher(sdkInt: Int = android.os.Build.VERSION.SDK_INT): Cipher =
    // Android 7's Conscrypt buffers the entire GCM message even with chunked update().
    // Only the exportable storage data key uses this provider; Keystore wrapping stays native.
    if (sdkInt <= 25) iptvStreamingStorageCipher()
    else Cipher.getInstance("AES/GCM/NoPadding")

/** Keep GCM's final tag together and collect output only after authentication succeeds. */
internal fun iptvCipherFinal(cipher: Cipher, input: ByteArray, start: Int = 0, length: Int = input.size - start): ByteArray {
    require(start >= 0 && length >= 0 && start <= input.size - length)
    val output = ByteArray(cipher.getOutputSize(length))
    val end = start + length
    var offset = start
    var written = 0
    while (end - offset > 4096) {
        val count = minOf(4096, end - offset - 16)
        written += cipher.update(input, offset, count, output, written)
        offset += count
    }
    written += cipher.doFinal(input, offset, end - offset, output, written)
    return if (written == output.size) output else output.copyOf(written)
}

/** Keep bulk data outside device-specific Keystore operations; only wrap a 32-byte key. */
internal fun iptvEncryptStorage(input: ByteArray, wrapKey: (ByteArray) -> ByteArray): ByteArray {
    val dataKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrappedKey = wrapKey(dataKey.encoded)
    require(wrappedKey.size in 1..255) { "Clé de stockage IPTV incompatible." }
    // Zero cannot be a legacy IV length. Authenticate the version and wrapped key with the data.
    val header = byteArrayOf(0, 1, wrappedKey.size.toByte()) + wrappedKey
    val cipher = iptvStorageCipher().apply {
        init(Cipher.ENCRYPT_MODE, dataKey)
        updateAAD(header)
    }
    val encrypted = iptvCipherFinal(cipher, input)
    require(cipher.iv.size == 12) { "Chiffrement de stockage IPTV incompatible." }
    return header + cipher.iv + encrypted
}

internal fun iptvDecryptStorage(input: ByteArray, unwrapKey: (ByteArray) -> ByteArray): ByteArray {
    if (input.firstOrNull() != 0.toByte()) return unwrapKey(input) // Existing libraries and backups.
    require(input.size >= 3 && input[1] == 1.toByte()) { "Version de stockage IPTV incompatible." }
    val wrappedSize = input[2].toInt() and 0xff
    val headerSize = 3 + wrappedSize
    require(wrappedSize > 0 && input.size >= headerSize + 12 + 16) { "Stockage IPTV endommagé." }
    val dataKey = unwrapKey(input.copyOfRange(3, headerSize))
    require(dataKey.size == 32) { "Clé de stockage IPTV incompatible." }
    val cipher = iptvStorageCipher().apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"),
            GCMParameterSpec(128, input.copyOfRange(headerSize, headerSize + 12)))
        updateAAD(input.copyOfRange(0, headerSize))
    }
    return iptvCipherFinal(cipher, input, headerSize + 12)
}

/** Write the existing envelope without retaining a JSON string or full plaintext/ciphertext arrays. */
internal fun iptvWriteEncryptedStorage(output: OutputStream, wrapKey: (ByteArray) -> ByteArray,
    writePlaintext: (OutputStream) -> Unit): ByteArray {
    val dataKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrappedKey = wrapKey(dataKey.encoded)
    require(wrappedKey.size in 1..255) { "Clé de stockage IPTV incompatible." }
    val header = byteArrayOf(0, 1, wrappedKey.size.toByte()) + wrappedKey
    val cipher = iptvStreamingStorageCipher().apply {
        init(Cipher.ENCRYPT_MODE, dataKey)
        updateAAD(header)
    }
    require(cipher.iv.size == 12) { "Chiffrement de stockage IPTV incompatible." }
    output.write(header)
    output.write(cipher.iv)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(4096 + 16)
    writePlaintext(object : OutputStream() {
        private val single = ByteArray(1)
        override fun write(value: Int) { single[0] = value.toByte(); write(single, 0, 1) }
        override fun write(value: ByteArray, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset <= value.size - count)
            var cursor = offset
            val end = offset + count
            while (cursor < end) {
                val length = minOf(4096, end - cursor)
                digest.update(value, cursor, length)
                val written = cipher.update(value, cursor, length, buffer, 0)
                output.write(buffer, 0, written)
                cursor += length
            }
        }
    })
    output.write(buffer, 0, cipher.doFinal(buffer, 0))
    return digest.digest()
}

/** Authenticate the new envelope and compare plaintext hashes without publishing any plaintext. */
internal fun iptvEncryptedStorageDigest(input: InputStream, unwrapKey: (ByteArray) -> ByteArray): ByteArray {
    val stream = DataInputStream(input)
    val prefix = ByteArray(3).also(stream::readFully)
    require(prefix[0] == 0.toByte() && prefix[1] == 1.toByte()) { "Version de stockage IPTV incompatible." }
    val wrappedSize = prefix[2].toInt() and 0xff
    require(wrappedSize > 0) { "Stockage IPTV endommagé." }
    val wrappedKey = ByteArray(wrappedSize).also(stream::readFully)
    val dataKey = unwrapKey(wrappedKey)
    require(dataKey.size == 32) { "Clé de stockage IPTV incompatible." }
    val iv = ByteArray(12).also(stream::readFully)
    val cipher = iptvStreamingStorageCipher().apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, iv))
        updateAAD(prefix + wrappedKey)
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(4096)
    val plain = ByteArray(4096 + 16)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(plain, 0, cipher.update(buffer, 0, count, plain, 0))
    }
    digest.update(plain, 0, cipher.doFinal(plain, 0))
    return digest.digest()
}

/** A deletion remains a revision, so an offline TV cannot resurrect it. */
internal fun mergeIptvSyncRecords(local: Map<String, IptvSyncRecord>, remote: Map<String, IptvSyncRecord>): Map<String, IptvSyncRecord> =
    (local.keys + remote.keys).associateWith { key ->
        val a = local[key]; val b = remote[key]
        when {
            a == null -> checkNotNull(b)
            b == null -> a
            a.changedAt != b.changedAt -> if (a.changedAt > b.changedAt) a else b
            a.writer != b.writer -> if (a.writer > b.writer) a else b
            a.value == null -> a
            b.value == null -> b
            else -> if (a.value >= b.value) a else b
        }
    }

private data class IptvHomeSeries(val source: IptvSource, val id: String,
    val items: Map<String, IptvItem>, val metadata: Map<String, String>)

@Serializable internal data class IptvAccountState(
    val favorites: Set<String> = emptySet(),
    val settings: Map<String, String> = emptyMap(),
    val history: List<String> = emptyList(),
    val playedAt: Map<String, Long> = emptyMap(),
    val searches: List<String> = emptyList(),
)

@Serializable internal data class IptvLibrary(
    val sources: List<IptvSource> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val history: List<String> = emptyList(),
    val remembered: List<IptvItem> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val playedAt: Map<String, Long> = emptyMap(),
    val searches: List<String> = emptyList(),
    val syncRecords: Map<String, IptvSyncRecord> = emptyMap(),
    val accounts: List<IptvAccount> = emptyList(),
    val selectedAccountId: String = "",
    val accountStates: Map<String, IptvAccountState> = emptyMap(),
    val legacyAccountId: String = "",
    val profileActivityScoped: Boolean = false,
    val activityRevision: Long = 0,
)

@Serializable internal data class IptvActivityCheckpoint(
    val revision: Long,
    val accounts: Map<String, IptvAccountState>,
    val remembered: List<IptvItem>,
    val records: Map<String, IptvSyncRecord> = emptyMap(),
)

/** Keep activity durable without re-encrypting provider settings and the full NAS journal. */
internal fun IptvLibrary.activityCheckpoint(records: Map<String, IptvSyncRecord>): IptvActivityCheckpoint {
    val current = checkpointAccount()
    val states = if (current.accounts.isEmpty()) mapOf("" to IptvAccountState(current.favorites,
        history = current.history, playedAt = current.playedAt)) else current.accountStates
    val required = states.values.flatMap { it.favorites + it.history }.toSet()
    return IptvActivityCheckpoint(current.activityRevision,
        states.mapValues { (_, state) -> state.copy(settings = emptyMap(), searches = emptyList()) },
        current.remembered.filter { it.id in required }, records)
}

internal fun IptvLibrary.restoreActivityCheckpoint(checkpoint: IptvActivityCheckpoint): IptvLibrary {
    // A completed full save supersedes the journal even if the process died before deleting it.
    if (checkpoint.revision != activityRevision) return this
    val current = checkpointAccount()
    val states = current.accountStates.mapValues { (id, state) -> checkpoint.accounts[id]?.let {
        state.copy(favorites = it.favorites, history = it.history, playedAt = it.playedAt)
    } ?: state }
    val selected = if (current.accounts.isEmpty()) checkpoint.accounts[""] else states[current.selectedAccountId]
    return current.copy(accountStates = states, favorites = selected?.favorites ?: current.favorites,
        history = selected?.history ?: current.history, playedAt = selected?.playedAt ?: current.playedAt,
        remembered = (checkpoint.remembered + current.remembered).distinctBy { it.id },
        syncRecords = current.syncRecords + checkpoint.records)
}

internal fun IptvLibrary.onlyActivityChanged(next: IptvLibrary): Boolean {
    val states = accountStates.mapValues { (id, state) -> next.accountStates[id]?.let {
        state.copy(favorites = it.favorites, history = it.history, playedAt = it.playedAt)
    } ?: state }
    if (copy(favorites = next.favorites, history = next.history, playedAt = next.playedAt,
        remembered = next.remembered, accountStates = states, syncRecords = next.syncRecords) != next) return false
    return syncRecords.all { (key, value) -> next.syncRecords[key] == value ||
        key in next.syncRecords && iptvCloudActivityRecord(key) } &&
        next.syncRecords.all { (key, value) -> syncRecords[key] == value || iptvCloudActivityRecord(key) }
}

/** Only encode changed favourites/playback entries, never unrelated settings or suppliers. */
internal fun iptvActivityChanges(before: IptvLibrary, after: IptvLibrary, at: Long, writer: String): Map<String, IptvSyncRecord> = buildMap {
    val a = before.checkpointAccount(); val b = after.checkpointAccount()
    val oldItems = a.remembered.associateBy { it.id }; val newItems = b.remembered.associateBy { it.id }
    val owners = if (b.accounts.isEmpty()) listOf("") else b.accountStates.keys
    owners.forEach { owner ->
        fun state(value: IptvLibrary) = if (owner.isBlank()) IptvAccountState(value.favorites,
            history = value.history, playedAt = value.playedAt) else value.accountStates[owner] ?: IptvAccountState()
        val old = state(a); val next = state(b)
        val prefixes = if (owner.isBlank()) listOf("") else listOf("account:$owner:") +
            if (owner == b.legacyAccountId) listOf("") else emptyList()
        fun record(kind: String, id: String, value: String?) {
            prefixes.forEach { put("$it$kind:$id", IptvSyncRecord(at, writer, value)) }
        }
        (old.favorites + next.favorites).forEach { id ->
            if ((id in old.favorites) != (id in next.favorites) || id in next.favorites && oldItems[id] != newItems[id])
                record("favorite", id, newItems[id]?.takeIf { id in next.favorites }?.let { iptvJson.encodeToString(it) })
        }
        (old.history + next.history).toSet().forEach { id ->
            if ((id in old.history) != (id in next.history) || id in next.history &&
                (old.playedAt[id] != next.playedAt[id] || oldItems[id] != newItems[id]))
                record("play", id, newItems[id]?.takeIf { id in next.history }?.let {
                    iptvJson.encodeToString(IptvSyncPlayback(it, next.playedAt[id] ?: 0L))
                })
        }
    }
}

private fun accountSetting(name: String): Boolean =
    name == "savedPrograms" || name == "watchTimeResetAt" || name.startsWith("channelWatchTime:") ||
        name == "audioLanguage" || name == "secondaryAudioLanguage" ||
        name == "subtitleLanguage" || name == "secondarySubtitleLanguage" ||
        name == "subtitleUseForced" || name == "subtitleShowOnlyPreferredLanguages" ||
        name == "autoNextEpisode" || name == "nextEpisodeThresholdMode" ||
        name == "nextEpisodeThresholdPercent" || name == "nextEpisodeThresholdMinutesBeforeEnd"

private fun inheritedAccountSettings(settings: Map<String, String>) = settings.filterKeys { !accountSetting(it) }
private fun privateAccountSettings(settings: Map<String, String>) = settings.filterKeys(::accountSetting)

internal fun IptvLibrary.checkpointAccount(): IptvLibrary {
    val current = initializeAccounts()
    return if (current.accounts.any { it.id == current.selectedAccountId }) current.copy(accountStates = current.accountStates +
        (current.selectedAccountId to IptvAccountState(current.favorites, privateAccountSettings(current.settings),
            current.history, current.playedAt, current.searches))) else current
}

internal fun IptvLibrary.settingsSnapshot(): Pair<Map<String, String>, Map<String, Map<String, String>>> {
    val current = checkpointAccount()
    return inheritedAccountSettings(current.settings) to
        current.accountStates.mapValues { (_, state) -> state.settings.toMap() }
}

internal fun IptvLibrary.initializeAccounts(): IptvLibrary {
    if (accounts.isEmpty() || profileActivityScoped) return this
    val first = legacyAccountId.ifBlank { accounts.first().id }
    val states = accounts.associate { account ->
        val previous = accountStates[account.id] ?: IptvAccountState(
            favorites = if (account.id == first) favorites else emptySet(),
            settings = if (account.id == first) privateAccountSettings(settings) else emptyMap())
        // Old activity has no owner. Keep it only on the original account, never on every profile.
        account.id to previous.copy(settings = privateAccountSettings(previous.settings),
            history = if (account.id == first) history else previous.history,
            playedAt = if (account.id == first) playedAt else previous.playedAt,
            searches = if (account.id == first) searches else previous.searches)
    }
    val selected = selectedAccountId.takeIf { it in states } ?: accounts.first().id
    val state = states.getValue(selected)
    return copy(accountStates = states, legacyAccountId = first, selectedAccountId = selected,
        favorites = state.favorites, settings = inheritedAccountSettings(settings) + state.settings,
        history = state.history, playedAt = state.playedAt, searches = state.searches, profileActivityScoped = true)
}

internal fun IptvLibrary.selectAccount(id: String): IptvLibrary {
    val current = initializeAccounts().checkpointAccount()
    check(current.accounts.any { it.id == id }) { "Ce compte n’existe plus." }
    val state = current.accountStates.getValue(id)
    return current.copy(selectedAccountId = id, favorites = state.favorites,
        settings = inheritedAccountSettings(current.settings) + state.settings,
        history = state.history, playedAt = state.playedAt, searches = state.searches)
}

/** The selected profile belongs to this TV, independently of the large library snapshot. */
internal fun IptvLibrary.restoreLocalAccount(id: String): IptvLibrary {
    val selected = iptvAccountId(syncRecords, id)
    if (selected == selectedAccountId || accounts.none { it.id == selected }) return this
    return selectAccount(selected)
}

internal fun IptvLibrary.withPlayback(item: IptvItem, accountId: String, at: Long): IptvLibrary {
    val current = initializeAccounts().checkpointAccount()
    val owner = iptvAccountId(current.syncRecords, accountId)
    val state = if (owner == current.selectedAccountId) IptvAccountState(current.favorites,
        privateAccountSettings(current.settings), current.history, current.playedAt, current.searches)
        else current.accountStates[owner] ?: return current
    val history = (listOf(item.id) + state.history.filterNot { it == item.id }).take(100)
    val updated = state.copy(history = history,
        playedAt = (state.playedAt + (item.id to at)).filterKeys { it in history })
    val required = current.favorites + current.history + item.id +
        current.accountStates.values.flatMap { it.favorites + it.history }
    val providerItem = IptvOrganization.originalItem(item, inheritedAccountSettings(current.settings) + state.settings)
    val remembered = listOf(providerItem) + current.remembered.filter { it.id != item.id && it.id in required }
    return current.copy(accountStates = current.accountStates + (owner to updated), remembered = remembered,
        history = if (owner == current.selectedAccountId) updated.history else current.history,
        playedAt = if (owner == current.selectedAccountId) updated.playedAt else current.playedAt)
}

/** Favourite commands set the requested state; repeated clicks must never undo an addition. */
internal fun IptvLibrary.withFavorite(item: IptvItem, added: Boolean, accountId: String): IptvLibrary {
    val current = initializeAccounts().checkpointAccount()
    val owner = iptvAccountId(current.syncRecords, accountId)
    val state = if (owner == current.selectedAccountId) current.accountStates[owner] ?: IptvAccountState(
        current.favorites, privateAccountSettings(current.settings), current.history, current.playedAt, current.searches)
        else current.accountStates[owner] ?: return current
    val favorites = if (added) state.favorites + item.id
        else state.favorites - IptvStore.homeFavoriteRemovalIds(item, current.remembered)
    val updated = state.copy(favorites = favorites)
    val required = current.favorites + current.history + item.id +
        current.accountStates.values.flatMap { it.favorites + it.history }
    val remembered = if (added) listOf(IptvOrganization.originalItem(item, current.settings)) +
        current.remembered.filter { it.id != item.id && it.id in required } else current.remembered
    return current.copy(accountStates = current.accountStates + (owner to updated), remembered = remembered,
        favorites = if (owner == current.selectedAccountId) favorites else current.favorites)
}

/** History deletion preserves favourites, playback progress and every other account. */
internal fun IptvLibrary.withoutHistory(item: IptvItem?, accountId: String): IptvLibrary {
    val current = initializeAccounts().checkpointAccount()
    val owner = iptvAccountId(current.syncRecords, accountId)
    val state = if (owner == current.selectedAccountId) current.accountStates[owner] ?: IptvAccountState(
        current.favorites, privateAccountSettings(current.settings), current.history, current.playedAt, current.searches)
        else current.accountStates[owner] ?: return current
    val removed = item?.let { IptvStore.homeFavoriteRemovalIds(it, current.remembered) } ?: state.history.toSet()
    val updated = state.copy(history = state.history.filterNot { it in removed },
        playedAt = if (item == null) emptyMap() else state.playedAt.filterKeys { it !in removed })
    return current.copy(accountStates = current.accountStates + (owner to updated),
        history = if (owner == current.selectedAccountId) updated.history else current.history,
        playedAt = if (owner == current.selectedAccountId) updated.playedAt else current.playedAt)
}

/** Fill presentation gaps for this exact catalogue entry, preserving local playback inputs. */
internal fun IptvItem.withProviderMetadata(details: IptvItem): IptvItem {
    if (id != details.id || sourceId != details.sourceId || providerId != details.providerId || type != details.type) return this
    return copy(artwork = artwork.ifBlank { details.artwork }, backdrop = backdrop.ifBlank { details.backdrop },
        titleLogo = titleLogo.ifBlank { details.titleLogo }, tmdbId = tmdbId.takeIf { it > 0 } ?: details.tmdbId,
        year = year.ifBlank { details.year }, genre = genre.ifBlank { details.genre },
        duration = duration.ifBlank { details.duration }, description = description.ifBlank { details.description },
        cast = cast.ifBlank { details.cast }, director = director.ifBlank { details.director })
}

/** Replaying cloud activity must not reorder history or rewrite an unchanged library. */
internal fun IptvLibrary.withSupabaseActivity(account: String, kind: String, id: String, item: IptvItem?,
    playedAt: Long, deleted: Boolean): IptvLibrary {
    require(kind in setOf("favorite", "history"))
    if (accounts.none { it.id == account } || !deleted && (item == null || sources.none { it.id == item.sourceId })) return this
    val state = checkpointAccount().accountStates[account] ?: return this
    val known = remembered.firstOrNull { it.id == id && (item == null || it.sourceId == item.sourceId) }
    val resolved = known?.let { if (item == null) it else it.withProviderMetadata(item) } ?: item ?: return this
    val metadataChanged = known != null && resolved != known
    val removed = if (deleted) IptvStore.homeFavoriteRemovalIds(resolved, remembered) else emptySet()
    if (kind == "favorite") {
        if (deleted && state.favorites.none { it in removed } || !deleted && id in state.favorites && known != null && !metadataChanged) return this
    } else {
        if (deleted && state.history.none { it in removed } || !deleted && id in state.history && state.playedAt[id] == playedAt && known != null && !metadataChanged) return this
    }
    return (if (kind == "favorite") withFavorite(resolved, !deleted, account)
        else if (deleted) withoutHistory(resolved, account) else withPlayback(resolved, account, playedAt))
        .initializeAccounts().checkpointAccount()
}

internal fun IptvLibrary.withAccount(account: IptvAccount): IptvLibrary {
    val current = initializeAccounts().checkpointAccount()
    val next = account.copy(name = account.name.trim())
    check(next.name.isNotEmpty()) { "Saisissez un nom de compte." }
    check(next.name.length <= 40) { "Le nom du compte ne doit pas dépasser 40 caractères." }
    check(current.accounts.none { it.id != next.id && it.name.equals(next.name, ignoreCase = true) }) {
        "Un compte porte déjà ce nom."
    }
    val existing = current.accounts.any { it.id == next.id }
    val updated = if (existing) current.accounts.map { if (it.id == next.id) next else it } else current.accounts + next
    if (existing) return current.copy(accounts = updated)
    val state = current.accountStates[next.id] ?: if (current.accounts.isEmpty() && current.legacyAccountId.isBlank())
        IptvAccountState(current.favorites, privateAccountSettings(current.settings), current.history, current.playedAt, current.searches)
        else IptvAccountState()
    return current.copy(accounts = updated, selectedAccountId = next.id, accountStates = current.accountStates + (next.id to state),
        legacyAccountId = current.legacyAccountId.ifBlank { next.id }, favorites = state.favorites,
        settings = inheritedAccountSettings(current.settings) + state.settings,
        history = state.history, playedAt = state.playedAt, searches = state.searches, profileActivityScoped = true)
}

internal fun IptvLibrary.withoutAccount(id: String): IptvLibrary {
    val current = initializeAccounts().checkpointAccount()
    val remaining = current.accounts.filter { it.id != id }
    val selected = current.selectedAccountId.takeIf { selected ->
        remaining.any { it.id == selected }
    } ?: remaining.firstOrNull()?.id.orEmpty()
    val states = current.accountStates - id
    val state = states[selected] ?: IptvAccountState()
    return current.copy(accounts = remaining, accountStates = states, selectedAccountId = selected,
        favorites = state.favorites, settings = inheritedAccountSettings(current.settings) + state.settings,
        history = state.history, playedAt = state.playedAt, searches = state.searches)
}

internal fun iptvAccountId(records: Map<String, IptvSyncRecord>, id: String): String {
    var current = id
    val seen = hashSetOf<String>()
    while (seen.add(current)) current = records["accountAlias:$current"]?.value ?: return current
    return id
}

internal fun iptvPlaybackContentId(id: String, accountId: String, legacyId: String): String =
    if (!id.startsWith("iptv:") || accountId.isBlank() || accountId == legacyId) id
    else "iptv:account:$accountId:${id.removePrefix("iptv:")}"

internal fun iptvCatalogContentId(id: String): String =
    if (id.startsWith("iptv:account:")) "iptv:" + id.removePrefix("iptv:account:").substringAfter(':') else id

internal fun iptvPlaybackAccount(id: String, legacyId: String): String =
    if (id.startsWith("iptv:account:")) id.removePrefix("iptv:account:").substringBefore(':') else legacyId

/** Small immutable account state; Home can filter progress without entering the storage lock. */
internal data class IptvPlaybackScope(val selectedAccountId: String, val legacyAccountId: String,
    val aliases: Map<String, IptvSyncRecord>) {
    fun owns(id: String): Boolean = !id.startsWith("iptv:") ||
        iptvAccountId(aliases, iptvPlaybackAccount(id, legacyAccountId)) == selectedAccountId

    fun progressFor(catalogId: String, contentType: String, progress: Collection<WatchProgress>): List<WatchProgress> =
        progress.filter { it.contentType == contentType && owns(it.contentId) &&
            iptvCatalogContentId(it.contentId) == iptvCatalogContentId(catalogId) }
            .groupBy { it.season to it.episode }.values.map { entries -> entries.maxByOrNull { it.lastWatched }!! }
}

internal fun IptvLibrary.playbackScope() = IptvPlaybackScope(selectedAccountId, legacyAccountId,
    syncRecords.filterKeys { it.startsWith("accountAlias:") })

private fun iptvAccountNameKey(name: String) = "accountName:${name.trim().lowercase(java.util.Locale.ROOT)}"

internal fun iptvSyncValues(value: IptvLibrary): Map<String, String> = buildMap {
    val current = value.initializeAccounts().checkpointAccount()
    current.sources.forEach { put("source:${it.id}", iptvJson.encodeToString(it)) }
    current.syncRecords.filter { (key, record) -> key.startsWith("accountName:") && record.value != null }
        .forEach { (key, record) -> put(key, record.value!!) }
    current.accounts.forEach {
        put("profile:${it.id}", iptvJson.encodeToString(it))
        val name = iptvAccountNameKey(it.name)
        if (name !in this) put(name, it.id)
    }
    if (current.legacyAccountId.isNotBlank()) put("legacyAccount", current.legacyAccountId)
    val items = current.remembered.associateBy { it.id }
    current.settings.filterKeys { !accountSetting(it) }.forEach { (key, setting) -> put("setting:$key", setting) }
    fun accountValues(prefix: String, state: IptvAccountState) {
        state.favorites.forEach { id -> items[id]?.let { put("${prefix}favorite:$id", iptvJson.encodeToString(it)) } }
        state.settings.filterKeys(::accountSetting).forEach { (key, setting) -> put("${prefix}setting:$key", setting) }
        state.history.forEach { id -> items[id]?.let {
            put("${prefix}play:$id", iptvJson.encodeToString(IptvSyncPlayback(it, state.playedAt[id] ?: 0L)))
        } }
        if (state.searches.isNotEmpty()) put("${prefix}searches", iptvJson.encodeToString(state.searches))
    }
    if (current.legacyAccountId.isBlank()) accountValues("", IptvAccountState(current.favorites,
        privateAccountSettings(current.settings), current.history, current.playedAt, current.searches))
    else {
        current.accountStates[current.legacyAccountId]?.let { accountValues("", it) }
        current.accountStates.forEach { (id, state) -> accountValues("account:$id:", state) }
    }
}

/** Personal activity has an account namespace; ordinary parameter values are shared. */
private fun accountSyncKey(key: String): Boolean = key.startsWith("favorite:") || key.startsWith("play:") ||
    key == "searches" || key.startsWith("setting:") && accountSetting(key.removePrefix("setting:"))

/** Advertise existing accounts without overwriting newer NAS edits or deletion tombstones. */
internal fun seedIptvSyncRecords(value: IptvLibrary): IptvLibrary {
    val current = value.initializeAccounts().checkpointAccount()
    val records = current.syncRecords.toMutableMap()
    val legacyPrefix = "account:${current.legacyAccountId}:"
    iptvSyncValues(current).forEach { (key, content) ->
        if (key !in records) records[key] =
            if (current.legacyAccountId.isNotBlank() && key.startsWith(legacyPrefix))
                records[key.removePrefix(legacyPrefix)] ?: IptvSyncRecord(1, "legacy", content)
            else IptvSyncRecord(1, "legacy", content)
    }
    return current.copy(syncRecords = records)
}

private fun iptvAccountSyncRecords(records: Map<String, IptvSyncRecord>): Map<String, IptvSyncRecord> {
    val merged = records.toMutableMap()
    // A late, pre-sync local copy must follow a previously registered name even after rename/deletion.
    records.filter { (key, record) -> key.startsWith("profile:") && record.value != null &&
        record.changedAt == 1L && record.writer == "legacy"
    }.forEach { (key, record) ->
        val id = key.removePrefix("profile:")
        val account = iptvJson.decodeFromString<IptvAccount>(record.value!!)
        val registered = records[iptvAccountNameKey(account.name)]
        val target = registered?.value?.let { iptvAccountId(merged, it) }
        if (registered != null && registered.changedAt > 1 && target != null && target != id) {
            merged["profile:$target"]?.let { previous ->
                if (previous.value != null && previous.changedAt == 1L && previous.writer == "legacy" && account.avatar.isNotBlank()) {
                    val existing = iptvJson.decodeFromString<IptvAccount>(previous.value)
                    if (existing.avatar.isBlank()) merged["profile:$target"] = previous.copy(
                        changedAt = registered.changedAt, value = iptvJson.encodeToString(existing.copy(avatar = account.avatar)))
                }
            }
            merged["accountAlias:$id"] = IptvSyncRecord(registered.changedAt, registered.writer, target)
            merged[key] = IptvSyncRecord(registered.changedAt, registered.writer)
        }
    }
    val profiles = merged.filter { (key, record) -> key.startsWith("profile:") && record.value != null &&
        iptvAccountId(merged, key.removePrefix("profile:")) == key.removePrefix("profile:")
    }.map { (key, record) -> key.removePrefix("profile:") to iptvJson.decodeFromString<IptvAccount>(record.value!!) }
    // Old TVs created UUIDs independently. Match their existing names once and retain aliases after a rename.
    profiles.groupBy { (_, account) -> account.name.trim().lowercase(java.util.Locale.ROOT) }.values.forEach { group ->
        if (group.size > 1) {
            val id = group.minOf { it.first }
            val candidates = group.associate { (key, _) -> key to merged.getValue("profile:$key") }
            val newest = candidates.values.maxOf { it.changedAt }
            val migratedAvatars = group.filter { (key, account) -> newest == 1L &&
                candidates.getValue(key).writer == "legacy" && account.avatar.isNotBlank() }
            val eligible = migratedAvatars.ifEmpty { group }
            val winner = eligible.map { (key, _) -> candidates.getValue(key) }.reduce { a, b ->
                mergeIptvSyncRecords(mapOf("profile" to a), mapOf("profile" to b)).getValue("profile")
            }
            val account = iptvJson.decodeFromString<IptvAccount>(winner.value!!).copy(id = id)
            val at = newest + 1
            merged["profile:$id"] = IptvSyncRecord(at, winner.writer, iptvJson.encodeToString(account))
            group.filter { it.first != id }.forEach { (other, _) ->
                merged["accountAlias:$other"] = IptvSyncRecord(at, winner.writer, id)
                merged["profile:$other"] = IptvSyncRecord(at, winner.writer)
            }
        }
    }
    fun include(key: String, record: IptvSyncRecord) {
        merged[key] = merged[key]?.let { previous ->
            mergeIptvSyncRecords(mapOf(key to previous), mapOf(key to record)).getValue(key)
        } ?: record
    }
    merged["legacyAccount"]?.let { record ->
        record.value?.let { merged["legacyAccount"] = record.copy(value = iptvAccountId(merged, it)) }
    }
    merged.toMap().filterKeys { it.startsWith("accountName:") }.forEach { (key, record) ->
        record.value?.let {
            merged[key] = record.copy(changedAt = maxOf(2L, record.changedAt), value = iptvAccountId(merged, it))
        }
    }
    merged.toMap().forEach { (key, record) ->
        if (key.startsWith("account:")) {
            val id = key.removePrefix("account:").substringBefore(':')
            val resolved = iptvAccountId(merged, id)
            if (resolved != id) include("account:$resolved:${key.removePrefix("account:$id:")}", record)
        }
    }
    // Older versions stored ordinary parameters per account. Preserve the newest revision globally.
    merged.toMap().forEach { (key, record) ->
        if (key.startsWith("account:")) {
            val name = key.removePrefix("account:").substringAfter(':')
            if (record.value != null && name.startsWith("setting:") && !accountSyncKey(name)) include(name, record)
        }
    }
    // Mirror only the original account's personal data for older APKs.
    merged["legacyAccount"]?.value?.let { id ->
        val otherWrites = merged.filterKeys { key -> key.startsWith("account:") &&
            iptvAccountId(merged, key.removePrefix("account:").substringBefore(':')) != id
        }.mapTo(hashSetOf()) { (key, record) -> key.removePrefix("account:").substringAfter(':') to record }
        val shared = merged.filterKeys(::accountSyncKey)
        shared.forEach { (key, record) ->
            val scoped = "account:$id:$key"
            if (merged[scoped] == record || (key to record) !in otherWrites) include(scoped, record)
        }
        shared.keys.filter { "account:$id:$it" !in merged }.forEach { merged.remove(it) }
        merged.toMap().filterKeys { it.startsWith("account:$id:") && accountSyncKey(it.removePrefix("account:$id:")) }.forEach { (key, record) ->
            merged[key.removePrefix("account:$id:")] = record
        }
    }
    return merged
}

internal fun mergeIptvLibrary(local: IptvLibrary, records: Map<String, IptvSyncRecord>): IptvLibrary {
    val current = seedIptvSyncRecords(local)
    val combined = mergeIptvSyncRecords(current.syncRecords, records).toMutableMap()
    // This anchor is permanent: a new TV's first local account must not take over shared legacy data.
    listOfNotNull(current.syncRecords["legacyAccount"], records["legacyAccount"]).filter { it.value != null }
        .minWithOrNull(compareBy<IptvSyncRecord> { it.changedAt }.thenBy { it.writer }.thenBy { it.value })
        ?.let { combined["legacyAccount"] = it }
    val merged = iptvAccountSyncRecords(combined)
    val active = merged.mapNotNull { (key, record) -> record.value?.let { key to it } }.toMap()
    val accounts = active.filterKeys { it.startsWith("profile:") &&
        iptvAccountId(merged, it.removePrefix("profile:")) == it.removePrefix("profile:")
    }.values.map { iptvJson.decodeFromString<IptvAccount>(it) }.sortedBy { it.id }
    val accountIds = accounts.map { it.id }.toSet()
    val legacyId = iptvAccountId(merged, active["legacyAccount"] ?: current.legacyAccountId)
    val selectedId = iptvAccountId(merged, current.selectedAccountId).takeIf { it in accountIds }
        ?: legacyId.takeIf { it in accountIds } ?: accounts.firstOrNull()?.id.orEmpty()
    val sources = active.filterKeys { it.startsWith("source:") }.values.map { iptvJson.decodeFromString<IptvSource>(it) }
    val sourceIds = sources.map { it.id }.toSet()
    fun accountFavorites(prefix: String) = active.filterKeys { it.startsWith("${prefix}favorite:") }.values
        .map { iptvJson.decodeFromString<IptvItem>(it) }.filter { it.sourceId in sourceIds }
    fun accountSettings(prefix: String) = active.filterKeys { it.startsWith("${prefix}setting:") &&
        accountSetting(it.removePrefix("${prefix}setting:")) }.mapKeys { it.key.removePrefix("${prefix}setting:") }
    fun accountPlayback(prefix: String) = active.filterKeys { it.startsWith("${prefix}play:") }.values
        .map { iptvJson.decodeFromString<IptvSyncPlayback>(it) }.filter { it.item.sourceId in sourceIds }
    fun accountState(prefix: String, previous: IptvAccountState): IptvAccountState {
        val playback = accountPlayback(prefix)
        val times = playback.associate { it.item.id to it.playedAt }
        val history = times.keys.sortedByDescending { times[it] }.take(100)
        return previous.copy(
            favorites = if (merged.keys.any { it.startsWith("${prefix}favorite:") })
                accountFavorites(prefix).map { it.id }.toSet() else previous.favorites,
            settings = if (merged.keys.any { it.startsWith("${prefix}setting:") &&
                accountSetting(it.removePrefix("${prefix}setting:")) }) accountSettings(prefix) else privateAccountSettings(previous.settings),
            history = if (merged.keys.any { it.startsWith("${prefix}play:") }) history else previous.history,
            playedAt = if (merged.keys.any { it.startsWith("${prefix}play:") }) times.filterKeys { it in history } else previous.playedAt,
            searches = if ("${prefix}searches" in merged) active["${prefix}searches"]
                ?.let { iptvJson.decodeFromString<List<String>>(it) }.orEmpty() else previous.searches)
    }
    val states = accounts.associate { account ->
        account.id to accountState("account:${account.id}:", current.accountStates[account.id] ?: IptvAccountState())
    }
    val unscoped = if (legacyId.isBlank() && accounts.isEmpty()) accountState("",
        IptvAccountState(current.favorites, privateAccountSettings(current.settings), current.history, current.playedAt, current.searches))
        else IptvAccountState()
    val required = states.values.flatMap { it.favorites + it.history } + unscoped.favorites + unscoped.history
    val personalItems = accounts.flatMap { account -> accountFavorites("account:${account.id}:") +
        accountPlayback("account:${account.id}:").map { it.item } } +
        if (legacyId.isBlank() && accounts.isEmpty()) accountFavorites("") + accountPlayback("").map { it.item } else emptyList()
    val items = (personalItems + current.remembered.filter { it.id in required }).distinctBy { it.id }.filter { it.sourceId in sourceIds }
    val itemIds = items.map { it.id }.toSet()
    fun clean(state: IptvAccountState) = state.copy(favorites = state.favorites.intersect(itemIds),
        history = state.history.filter { it in itemIds }, playedAt = state.playedAt.filterKeys { it in state.history && it in itemIds })
    val cleanedStates = states.mapValues { (_, state) -> clean(state) }
    val selected = cleanedStates[selectedId] ?: clean(unscoped)
    val sharedSettings = active.filterKeys { it.startsWith("setting:") && !accountSyncKey(it) }
        .mapKeys { it.key.removePrefix("setting:") }
    return current.copy(sources = sources, remembered = items.filter { it.id in required },
        favorites = selected.favorites, history = selected.history, playedAt = selected.playedAt,
        settings = sharedSettings + selected.settings,
        accounts = accounts, selectedAccountId = selectedId, legacyAccountId = legacyId, accountStates = cleanedStates,
        searches = selected.searches, syncRecords = merged, profileActivityScoped = legacyId.isNotBlank())
}

/** NAS records can still contain pre-cloud settings: neither they nor their local journal win. */
internal fun preserveSupabaseIptvSettings(local: IptvLibrary, incoming: IptvLibrary): IptvLibrary {
    val before = local.checkpointAccount()
    fun managed(key: String) = com.jojotv.home.progresspilot.AccountSettingsCatalog.iptvSetting(key)
    fun keepSettings(current: Map<String, String>, next: Map<String, String>) =
        next.filterKeys { !managed(it) } + current.filterKeys(::managed)
    val states = before.accounts.associate { account ->
        val previous = before.accountStates[account.id] ?: IptvAccountState()
        val next = incoming.accountStates[account.id] ?: previous
        account.id to next.copy(settings = keepSettings(previous.settings, next.settings))
    }
    val selected = states[before.selectedAccountId] ?: IptvAccountState(before.favorites,
        privateAccountSettings(before.settings), before.history, before.playedAt, before.searches)
    val required = states.values.flatMap { it.favorites + it.history }.toSet() + selected.favorites + selected.history
    return incoming.copy(accounts = before.accounts, selectedAccountId = before.selectedAccountId,
        legacyAccountId = before.legacyAccountId, profileActivityScoped = before.profileActivityScoped,
        settings = keepSettings(inheritedAccountSettings(before.settings), inheritedAccountSettings(incoming.settings)) + selected.settings,
        accountStates = states, favorites = selected.favorites, history = selected.history, playedAt = selected.playedAt, searches = selected.searches,
        remembered = (incoming.remembered + before.remembered.filter { it.id in required }).distinctBy { it.id })
}

@Serializable private data class IptvEpgCache(val updatedAt: Long, val programs: List<EpgProgram>)

/** App-private data, encrypted with data keys protected by Android Keystore. */
class IptvStore(context: Context) {
    private val appContext = context.applicationContext
    private val accountSelectionPrefs = appContext.getSharedPreferences("iptv_local_selection", Context.MODE_PRIVATE)
    private val folder = File(context.noBackupFilesDir, "iptv").apply { mkdirs() }
    private val file = AtomicFile(File(folder, "library.bin"))
    private val activityFile = AtomicFile(File(folder, "activity.bin"))
    private val recordingFile = AtomicFile(File(folder, "recordings.bin"))
    private val reminderFile = AtomicFile(File(folder, "reminders.bin"))
    private val key: SecretKey by lazy {
        // Library and recording sync can start together in separate store instances.
        // Serialize first creation of the existing alias; never replace an existing key.
        synchronized(storageKeyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("jojo_iptv_library", null) as? SecretKey) ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            ).apply {
                init(KeyGenParameterSpec.Builder("jojo_iptv_library", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    private var library: IptvLibrary
        get() = sharedLibrary
        set(value) { sharedLibrary = value }

    fun load() {
        if (libraryLoaded) return
        synchronized(homeLibraryLock) {
            if (libraryLoaded) return@synchronized
            if (file.baseFile.exists()) {
                try {
                    library = iptvJson.decodeFromString<IptvLibrary>(decrypt(file.readFully()))
                } catch (problem: javax.crypto.AEADBadTagException) {
                    // Probe the existing key in memory only; never replace unreadable data or log its contents.
                    if (!storageFailureDiagnosed) {
                        storageFailureDiagnosed = true
                        val roundTrip = runCatching { decrypt(encrypt("jojo-iptv-storage-check")) == "jojo-iptv-storage-check" }.getOrDefault(false)
                        val backup = AtomicFile(File(folder, "backup.bin"))
                        val backupReadable = backup.baseFile.exists() && runCatching {
                            iptvJson.decodeFromString<IptvLibrary>(decrypt(backup.readFully()))
                        }.isSuccess
                        android.util.Log.e("JojoHomeIptv", "Local storage authentication failed; cryptoRoundTrip=$roundTrip; backupReadable=$backupReadable")
                    }
                    throw IllegalStateException("Le stockage IPTV local est illisible. Les données ont été conservées ; une récupération est nécessaire.", problem)
                }
            }
            if (activityFile.baseFile.exists()) {
                val checkpoint = iptvJson.decodeFromString<IptvActivityCheckpoint>(decrypt(activityFile.readFully()))
                if (checkpoint.revision == library.activityRevision) {
                    library = library.restoreActivityCheckpoint(checkpoint)
                    activitySyncRecords = checkpoint.records
                }
            }
            val seeded = seedIptvSyncRecords(library)
            val restored = seeded.restoreLocalAccount(accountSelectionPrefs.getString("account_id", "").orEmpty())
            if (seeded != library) persist(restored) else {
                library = restored
                publishHomeFavorites()
            }
        }
    }
    /** Blocking recovery belongs to startup/playback workers, never Home's UI cache reads. */
    internal fun loadWithNasRecovery() = iptvReadWithRecovery({ load() }) { recoverFromNas() }
    fun sources(): List<IptvSource> = library.sources
    internal fun playlistEnabled(id: String): Boolean = IptvPlaylistSettings.enabled(library.settings, id)
    // Shared by the native Home settings and the IPTV account screens.
    fun accounts(): List<IptvAccount> = library.accounts
    fun selectedAccount(): IptvAccount? {
        val current = library
        return current.accounts.firstOrNull { it.id == current.selectedAccountId } ?: current.accounts.firstOrNull()
    }
    fun playbackContentId(id: String): String {
        val current = library
        return iptvPlaybackContentId(id, current.selectedAccountId, current.legacyAccountId)
    }
    internal fun playbackAccountId(id: String): String {
        val current = library
        return iptvAccountId(current.syncRecords, iptvPlaybackAccount(id, current.legacyAccountId))
    }
    internal fun ownsAccount(id: String): Boolean {
        val current = library
        return iptvAccountId(current.syncRecords, id.ifBlank { current.legacyAccountId }) == current.selectedAccountId
    }
    internal fun resolveAccountId(id: String): String = iptvAccountId(library.syncRecords, id)
    fun saveAccount(account: IptvAccount) = synchronized(homeLibraryLock) {
        val id = iptvAccountId(library.syncRecords, account.id)
        check("profile:$id" !in library.syncRecords || library.syncRecords["profile:$id"]?.value != null) {
            "Ce compte n’existe plus. Choisissez un autre compte."
        }
        commit(library.withAccount(account.copy(id = id)))
    }
    fun deleteAccount(id: String) = synchronized(homeLibraryLock) {
        commit(library.withoutAccount(iptvAccountId(library.syncRecords, id)))
    }
    fun selectAccount(id: String) = synchronized(homeLibraryLock) {
        val selectedId = iptvAccountId(library.syncRecords, id)
        if (selectedId == library.selectedAccountId) return@synchronized
        val next = library.selectAccount(selectedId)
        // Publish cached profile data without serializing/encrypting the entire
        // library. Keep the rare legacy ownership migration on its existing path.
        if (!library.profileActivityScoped) commit(next) else {
            library = next
            rememberAccountSelection(next.selectedAccountId)
            publishHomeFavorites()
        }
    }
    private fun avatarBaseUrl(): String = appContext.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        .getString("nas_url", null)?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
        ?: com.jojotv.home.BuildConfig.JOJOTV_NAS_SYNC_URL.trimEnd('/')
    private fun avatarCatalogFile(base: String = avatarBaseUrl()) = AtomicFile(File(appContext.cacheDir, "iptv-avatars-${digest(base)}.json"))
    fun cachedAvatars(): List<IptvAvatar> {
        val base = avatarBaseUrl()
        sharedAvatarCatalog?.takeIf { it.first == base }?.let { return it.second }
        return synchronized(avatarCatalogLock) {
            sharedAvatarCatalog?.takeIf { it.first == base }?.let { return@synchronized it.second }
            runCatching {
                iptvJson.decodeFromString<IptvAvatarCatalog>(avatarCatalogFile(base).readFully().toString(Charsets.UTF_8)).avatars
            }.getOrDefault(emptyList()).also { sharedAvatarCatalog = base to it }
        }
    }
    fun fetchAvatars(): List<IptvAvatar> {
        val base = avatarBaseUrl()
        val request = Request.Builder().url("$base/api/ui-avatars/catalog.json").build()
        return IptvHttp.artworkClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Le catalogue d’avatars du NAS est indisponible." }
            val payload = checkNotNull(response.body).string()
            val catalog = iptvJson.decodeFromString<IptvAvatarCatalog>(payload)
            check(catalog.version == 1 && catalog.avatars.isNotEmpty()) { "Le catalogue d’avatars du NAS est vide ou incompatible." }
            synchronized(avatarCatalogLock) {
                write(avatarCatalogFile(base), payload.toByteArray(Charsets.UTF_8))
                sharedAvatarCatalog = base to catalog.avatars
            }
            catalog.avatars
        }
    }
    fun cachedAvatarBitmap(avatar: IptvAvatar): Bitmap? = avatarBitmaps.get("${avatarBaseUrl()}/${avatar.image}")
    // Background workers warm saved accounts from disk before creating their views.
    fun preloadAccountAvatars(): List<IptvAvatar> {
        val avatars = cachedAvatars()
        val selected = accounts().map { it.avatar }.toSet()
        val base = avatarBaseUrl()
        avatars.filter { it.image.isNotBlank() && (it.id in selected || it.emoji.isNotBlank() && it.emoji in selected) }.forEach { avatar ->
            runCatching {
                val cacheKey = "$base/${avatar.image}"
                if (avatarBitmaps.get(cacheKey) == null && avatar.image.matches(Regex("[a-z0-9_-]+\\.png"))) {
                    val cached = File(appContext.cacheDir, "iptv-avatar-${digest(base)}-${avatar.image}")
                    if (cached.isFile) synchronized(cached.absolutePath.intern()) {
                        if (avatarBitmaps.get(cacheKey) == null) decodeAvatarBitmap(cached, cacheKey)
                    }
                }
            }
        }
        return avatars
    }
    fun avatarBitmap(avatar: IptvAvatar): Bitmap? {
        check(avatar.image.matches(Regex("[a-z0-9_-]+\\.png"))) { "Image d’avatar invalide." }
        val base = avatarBaseUrl()
        val cacheKey = "$base/${avatar.image}"
        avatarBitmaps.get(cacheKey)?.let { return it }
        val cached = File(appContext.cacheDir, "iptv-avatar-${digest(base)}-${avatar.image}")
        synchronized(cached.absolutePath.intern()) {
            avatarBitmaps.get(cacheKey)?.let { return it }
            if (!cached.exists()) {
                val request = Request.Builder().url("$base/api/ui-avatars/${avatar.image}").build()
                IptvHttp.artworkClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "L’avatar du NAS est indisponible." }
                    write(AtomicFile(cached), checkNotNull(response.body).bytes())
                }
            }
            return decodeAvatarBitmap(cached, cacheKey)
        }
    }
    private fun decodeAvatarBitmap(cached: File, cacheKey: String): Bitmap? {
        if (!cached.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cached.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { cached.delete(); return null }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
        return BitmapFactory.decodeFile(cached.absolutePath, options)?.also { avatarBitmaps.put(cacheKey, it) }
    }
    fun setting(name: String, default: String): String = library.settings[name] ?: default
    fun booleanSetting(name: String, default: Boolean): Boolean = setting(name, default.toString()).toBooleanStrictOrNull() ?: default
    fun intSetting(name: String, default: Int): Int = setting(name, default.toString()).toIntOrNull() ?: default
    fun setSetting(name: String, value: String) = synchronized(homeLibraryLock) { commit(library.copy(settings = library.settings + (name to value))) }
    fun setSettings(values: Map<String, String>) = synchronized(homeLibraryLock) {
        if (values.all { (name, value) -> library.settings[name] == value }) return@synchronized
        commit(library.copy(settings = library.settings + values))
    }

    /** UI reads keep the last published settings while a serialized library write is in progress. */
    internal fun settingsSnapshot(): Pair<Map<String, String>, Map<String, Map<String, String>>> = homeSettingsSnapshot

    /** Only the explicit settings catalogue may consume this projection, never the library backup. */
    internal fun supabaseSettingsSnapshot(): Pair<Map<String, String>, Map<String, Map<String, String>>> = synchronized(homeLibraryLock) {
        val current = settingsSnapshot()
        (current.first + if (accountSelectionPrefs.contains("remember_last_profile"))
            mapOf("rememberLastProfile" to rememberLastProfile().toString()) else emptyMap()) to current.second
    }

    internal fun supabaseActivitySnapshot(): List<IptvCloudActivity> = synchronized(homeLibraryLock) {
        library.supabaseActivitySnapshot()
    }

    /** Compare and restore one activity entry; no player/progress API and no local alarm is called. */
    internal fun restoreSupabaseActivity(account: String, kind: String, id: String, item: IptvItem?, playedAt: Long,
        deleted: Boolean, expected: IptvCloudActivity?): IptvCloudRestore? = synchronized(homeLibraryLock) {
        val next = library.withSupabaseActivityIfUnchanged(account, kind, id, item, playedAt, deleted, expected)
            ?: return@synchronized null
        if (next != library) persistActivity(next)
        // Return the state actually applied, before a later local deletion can change it.
        IptvCloudRestore(library.supabaseActivitySnapshot().firstOrNull { it.account == account && it.item.id == id })
    }

    internal fun cloudActivitySelected(): Boolean = com.jojotv.home.progresspilot.AccountContentRouting.selected(appContext)

    private fun cloudManaged(key: String): Boolean =
        com.jojotv.home.progresspilot.AccountSettingsRouting.managesIptv(appContext, key) || cloudActivitySelected() && iptvCloudActivityRecord(key)

    internal fun restoreSupabaseProfile(account: IptvAccount, expected: IptvAccount? = null, compare: Boolean = false) = synchronized(homeLibraryLock) {
        if (compare && library.accounts.firstOrNull { it.id == account.id } != expected) return@synchronized false
        val selected = library.selectedAccountId
        val next = library.withAccount(account).restoreLocalAccount(selected).checkpointAccount()
        if (next != library) persist(next)
        true
    }

    internal fun restoreSupabaseSettings(accountId: String?, values: Map<String, String?>, expected: Map<String, String?>) = synchronized(homeLibraryLock) {
        var next = library.checkpointAccount()
        val current = if (accountId == null) next.settings + if (accountSelectionPrefs.contains("remember_last_profile"))
            mapOf("rememberLastProfile" to rememberLastProfile().toString()) else emptyMap() else next.accountStates[accountId]?.settings.orEmpty()
        if ("organization" in values) {
            require(accountId == null && values.keys == setOf("organization") && expected.keys == values.keys)
            val patched = com.jojotv.home.progresspilot.AccountIptvOrganization.patch(next.settings, expected["organization"], values["organization"])
                ?: return@synchronized
            if (patched != next.settings) persist(next.copy(settings = patched))
            return@synchronized
        }
        if (expected.any { (key, value) -> current[key] != value }) return@synchronized
        fun patched(before: Map<String, String>): Map<String, String> = before.toMutableMap().apply {
            values.filterKeys { it != "rememberLastProfile" }.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
        }
        if (accountId == null) {
            require(values.keys.none(::accountSetting))
            if ("rememberLastProfile" in values) check(accountSelectionPrefs.edit().apply {
                if (values["rememberLastProfile"] == null) remove("remember_last_profile")
                else putBoolean("remember_last_profile", values["rememberLastProfile"] == "true")
            }.commit()) { "ACCOUNT_IPTV_STORAGE_FAILED" }
            next = next.copy(settings = patched(next.settings))
        } else {
            require(next.accounts.any { it.id == accountId } && values.keys.all(::accountSetting))
            val state = next.accountStates[accountId] ?: IptvAccountState()
            val updated = state.copy(settings = patched(state.settings))
            next = next.copy(accountStates = next.accountStates + (accountId to updated))
            if (next.selectedAccountId == accountId) next = next.copy(settings = inheritedAccountSettings(next.settings) + updated.settings)
        }
        // Remote settings are not fresh local NAS edits. Sources/activity retain their normal path.
        if (next != library) persist(next)
    }
    fun organization(source: String, type: ContentType, group: String?, name: String, default: String): String =
        IptvOrganization.value(library.settings, source, type, group, name, default)
    fun organizeItems(items: List<IptvItem>, source: String, type: ContentType, group: String,
        selectionSettings: Map<String, String> = emptyMap()): List<IptvItem> {
        val current = library
        val settings = if (selectionSettings.isEmpty()) current.settings else current.settings + selectionSettings
        val values = IptvOrganization.items(items, settings, current.favorites, source, type, if (group == "allPlaylists") "" else group)
        return if (group == "allPlaylists" && current.settings["groupChannelsByPlaylist"] != "false") {
            val ranks = IptvOrganization.sources(current.sources, current.settings["sourceSort"] ?: "name",
                IptvOrganization.ids(current.settings["sourceOrder"] ?: "[]")).map { it.id }.withIndex().associate { it.value to it.index }
            val grouped = values.sortedBy { ranks[it.sourceId] ?: Int.MAX_VALUE }
            if (current.settings["favoritesFirst"] == "true") grouped.sortedBy { if (it.id in current.favorites) 0 else 1 } else grouped
        } else values
    }
    fun channelTitle(item: IptvItem): String = IptvOrganization.channelTitle(item.title, library.settings)
    internal fun originalItem(item: IptvItem): IptvItem = IptvOrganization.originalItem(item, library.settings)
    fun recordWatchTime(id: String, duration: Long, accountId: String = selectedAccount()?.id.orEmpty()) = synchronized(homeLibraryLock) {
        val state = library.accountStates[accountId]
        val settings = if (accountId == library.selectedAccountId) library.settings else state?.settings ?: return@synchronized
        val now = System.currentTimeMillis()
        val elapsed = duration.coerceAtMost(now - (settings["watchTimeResetAt"]?.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
        if (elapsed > 0) {
            val key = "channelWatchTime:$id"
            val updated = settings + (key to ((settings[key]?.toLongOrNull() ?: 0L) + elapsed).toString())
            if (accountId == library.selectedAccountId) commit(library.copy(settings = updated))
            else if (state != null) commit(library.copy(accountStates = library.accountStates + (accountId to state.copy(settings = updated))))
        }
    }
    fun resetWatchTime() = synchronized(homeLibraryLock) {
        commit(library.copy(settings = library.settings.filterKeys { !it.startsWith("channelWatchTime:") } +
            ("watchTimeResetAt" to System.currentTimeMillis().toString())))
    }
    fun groupBlocked(item: IptvItem): Boolean = IptvOrganization.blocked(library.settings, item)
    fun connectionSource(source: IptvSource): IptvSource = sourceWithUserAgent(source, setting("userAgent", ""))
    fun searches(): List<String> = library.searches
    fun recordSearch(query: String) = synchronized(homeLibraryLock) {
        if (booleanSetting("searchHistory", true)) commit(library.copy(searches = (listOf(query) + library.searches.filterNot { it.equals(query, true) }).take(15)))
    }
    fun clearSearches() = synchronized(homeLibraryLock) { commit(library.copy(searches = emptyList())) }
    fun removeSearch(query: String) = synchronized(homeLibraryLock) { commit(library.copy(searches = library.searches.filterNot { it.equals(query, true) })) }
    fun backup() = synchronized(homeLibraryLock) { writeLibrary(AtomicFile(File(folder, "backup.bin")), library) }
    fun hasBackup(): Boolean = File(folder, "backup.bin").exists()
    fun restoreBackup() = synchronized(homeLibraryLock) {
        val restored = iptvJson.decodeFromString<IptvLibrary>(decrypt(AtomicFile(File(folder, "backup.bin")).readFully()))
        commit(restored)
        restored.sources.forEach { clearCache(it.id) }
    }
    fun epgCached(item: IptvItem, day: String): List<EpgProgram>? {
        val target = epgFile(item, day)
        if (!target.baseFile.exists()) return null
        val cached = iptvJson.decodeFromString<IptvEpgCache>(decrypt(target.readFully()))
        val hours = intSetting("epgRefreshHours", 24).coerceIn(0, 168)
        return cached.programs.takeIf { hours == 0 || System.currentTimeMillis() - cached.updatedAt < hours * 3_600_000L }
    }
    fun cacheEpg(item: IptvItem, day: String, programs: List<EpgProgram>) {
        write(epgFile(item, day), encrypt(iptvJson.encodeToString(IptvEpgCache(System.currentTimeMillis(), programs))))
    }
    private fun epgFile(item: IptvItem, day: String) = AtomicFile(File(folder, "${digest(item.sourceId)}_epg_${digest(item.id + day)}.bin"))
    internal fun searchEpg(query: String): List<Pair<IptvItem, EpgProgram>> {
        if (query.isBlank()) return emptyList()
        val active = sources().filter { it.includeLive }.map { it.id }.toSet()
        val prefixes = active.map { "${digest(it)}_" }
        val remembered = library.remembered
        val cached = folder.listFiles().orEmpty().asSequence().takeWhile { !Thread.currentThread().isInterrupted }.filter { file ->
            file.name.endsWith(".bin") && !file.name.contains("_epg_") && prefixes.any { file.name.startsWith(it) }
        }.flatMap { file -> runCatching {
            decryptBytes(AtomicFile(file).readFully()).inputStream().use { input ->
                iptvCatalogueObjects(input).takeWhile { !Thread.currentThread().isInterrupted }
                    .filter { it.string("type") == ContentType.LIVE.name }
                    .map { iptvJson.decodeFromJsonElement(IptvItem.serializer(), it) }.toList()
            }
        }.getOrDefault(emptyList()).asSequence() }
        val now = System.currentTimeMillis()
        val pastDays = intSetting("epgPastDays", 1).coerceIn(1, 14)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        val days = listOf("current") + (-pastDays..14).map { day ->
            val date = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, day) }
            format.format(date.time)
        }
        // Discard VOD and duplicate cached pages before retaining the live catalogue.
        val channels = (remembered.asSequence() + cached).filter { it.type == ContentType.LIVE && it.sourceId in active }
            .distinctBy { it.id }.toList()
        val visible = channels.groupBy { it.sourceId }.flatMap { (source, items) -> organizeItems(items, source, ContentType.LIVE, "") }
        return visible.flatMap { channel ->
            if (Thread.currentThread().isInterrupted) return emptyList()
            val programs = days.flatMap { day -> runCatching { epgCached(channel, day).orEmpty() }.getOrDefault(emptyList()) }
            val offset = organization(channel.sourceId, channel.type, channel.categoryId, "epgOffsetMinutes", "0").toIntOrNull() ?: 0
            searchPrograms(channel, programs, query, now, pastDays, booleanSetting("searchPastWithoutReplay", false), offset,
                booleanSetting("epgDescriptions", true)).map { channel to it }
        }.sortedBy { it.second.start }
    }
    fun clearEpgCache(source: String) {
        folder.listFiles()?.filter { it.name.startsWith("${digest(source)}_epg_") }?.forEach { it.delete() }
    }
    fun save(source: IptvSource) = synchronized(homeLibraryLock) {
        val previous = library.sources.firstOrNull { it.id == source.id }
        val next = library.copy(sources = if (previous == null) library.sources + source else
            library.sources.map { if (it.id == source.id) source else it })
        commit(next)
        // Edited credentials/URLs must not reuse a catalogue from the old connection.
        if (previous == null || previous.copy(name = source.name) != source) clearCache(source.id)
    }
    fun remove(source: IptvSource) = synchronized(homeLibraryLock) {
        val removed = library.remembered.filter { it.sourceId == source.id }.map { it.id }.toSet()
        commit(library.copy(sources = library.sources.filterNot { it.id == source.id },
            remembered = library.remembered.filterNot { it.sourceId == source.id },
            favorites = library.favorites - removed, history = library.history.filterNot { it in removed },
            playedAt = library.playedAt.filterKeys { it !in removed },
            accountStates = library.accountStates.mapValues { (_, state) -> state.copy(favorites = state.favorites - removed,
                history = state.history.filterNot { it in removed }, playedAt = state.playedAt.filterKeys { it !in removed }) }))
        clearCache(source.id)
    }
    fun favorite(item: IptvItem): Boolean = item.id in library.favorites
    fun rememberedItem(id: String): IptvItem? = library.remembered.firstOrNull { it.id == id }
        ?: reminders().firstOrNull { it.channel.id == id }?.channel

    internal fun registerCloudCatalogue(items: Collection<IptvItem>, accountId: String = library.selectedAccountId,
        profileId: Int = progressEntryPoint(appContext).profileManager().activeProfileId.value) {
        val entry = progressEntryPoint(appContext)
        val preferences = entry.watchProgressPreferences()
        if (!preferences.isCloud(profileId) || entry.profileManager().activeProfileId.value != profileId) return
        val current = library
        if (current.selectedAccountId != accountId) return
        val templates = items.mapNotNull { item ->
            iptvCloudProgressTemplate(item, iptvPlaybackContentId(homeContentId(item), current.selectedAccountId, current.legacyAccountId))
        }
        runBlocking {
            if (entry.profileManager().activeProfileId.value == profileId && library.selectedAccountId == accountId)
                preferences.registerCloudContents(templates)
        }
    }

    internal fun registerCloudActivityContents() = registerCloudCatalogue(library.remembered)

    internal fun vodProgress(): List<WatchProgress> {
        val entry = progressEntryPoint(appContext)
        val profile = entry.profileManager().activeProfileId.value
        val preferences = entry.watchProgressPreferences()
        return runBlocking {
            val (progress, cloudHidden) = preferences.getHomeProgressSnapshot(profile)
            val hidden = if (preferences.isCloud(profile)) cloudHidden else {
                val localHidden = appContext.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                    .getStringSet("jojotv_home_hidden_resume_keys", emptySet()).orEmpty()
                progress.filter { "external-playback|${it.contentId}" in localHidden }
                    .mapTo(hashSetOf()) { it.storageKey }
            }
            visibleVodProgress(progress, hidden)
        }
    }

    internal fun vodProgress(item: IptvItem): List<WatchProgress> {
        val entry = progressEntryPoint(appContext)
        val profile = entry.profileManager().activeProfileId.value
        val scope = library.playbackScope()
        return runBlocking { scope.progressFor(homeContentId(item),
            if (item.type == ContentType.MOVIE) "movie" else "series",
            entry.watchProgressPreferences().getAllRawEntries(profile).values) }
    }

    internal fun setVodWatched(item: IptvItem, watched: Boolean) {
        registerCloudCatalogue(listOf(item))
        check(!item.scheduledEpisode && item.type in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE))
        val entry = progressEntryPoint(appContext)
        val profile = entry.profileManager().activeProfileId.value
        val contentId = playbackContentId(homeContentId(item))
        runBlocking {
            val progress = entry.watchProgressPreferences().getAllRawEntries(profile).values.toList()
            if (item.type == ContentType.SERIES && !watched) {
                entry.watchProgressPreferences().saveProgressBatch(homeUnwatchedProgress(item, contentId, progress,
                    System.currentTimeMillis()), profile)
            } else {
                val type = if (item.type == ContentType.MOVIE) "movie" else "series"
                val previous = progress.filter { it.contentType == type && it.contentId == contentId &&
                    (item.type != ContentType.EPISODE || it.season == item.season && it.episode == item.episode) }.maxByOrNull { it.lastWatched }
                val base = previous ?: WatchProgress(contentId, type, item.title, item.artwork, item.backdrop, null,
                    item.id, item.season, item.episode, item.title, 0, 1, System.currentTimeMillis(), description = item.description)
                entry.watchProgressPreferences().saveProgress(base.copy(position = if (watched) base.duration.coerceAtLeast(1) else 0,
                    duration = base.duration.coerceAtLeast(1), progressPercent = if (watched) 100f else 0f,
                    lastWatched = maxOf(System.currentTimeMillis(), base.lastWatched + 1)), profile)
            }
        }
    }
    fun toggleFavorite(item: IptvItem) {
        synchronized(homeLibraryLock) { setFavorite(item, !favorite(item)) }
    }
    fun setFavorite(item: IptvItem, added: Boolean, accountId: String = selectedAccount()?.id.orEmpty()): Boolean = synchronized(homeLibraryLock) {
        val owner = resolveAccountId(accountId)
        check(owner == library.selectedAccountId || library.accounts.any { it.id == owner }) { "Ce compte n’existe plus." }
        val next = library.withFavorite(item, added, owner)
        if (next != library) commitActivity(next)
        if (!added) setHomeResumeHidden(homeContentId(item), true, owner)
        if (owner == library.selectedAccountId) favorite(item) else item.id in library.accountStates[owner]?.favorites.orEmpty()
    }
    private fun removeFavorite(item: IptvItem) {
        setFavorite(item, false)
    }
    private fun setHomeResumeHidden(contentId: String, hide: Boolean, accountId: String = library.selectedAccountId) {
        val preferences = progressEntryPoint(appContext).watchProgressPreferences()
        if (preferences.isCloud()) {
            runBlocking { preferences.setCloudResumeHidden(iptvPlaybackContentId(contentId, accountId, library.legacyAccountId), hide) }
            return
        }
        val key = "external-playback|" + iptvPlaybackContentId(contentId, accountId, library.legacyAccountId)
        val prefs = appContext.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val hidden = prefs.getStringSet("jojotv_home_hidden_resume_keys", emptySet()).orEmpty().toMutableSet()
        val profile = HomeWatchProgressBridge.currentProfileId(appContext)
        val removedKey = HomeWatchProgressBridge.REMOVAL_PREFIX + profile + "_" + key
        val restoredKey = HomeWatchProgressBridge.RESTORATION_PREFIX + profile + "_" + key
        val at = maxOf(System.currentTimeMillis(), prefs.getLong(if (hide) restoredKey else removedKey, 0L) + 1L)
        if (hide) hidden.add(key) else hidden.remove(key)
        // The IPTV Activity can run without a surviving Home preferences listener.
        val revisions = runCatching { JSONObject(prefs.getString("nas_setting_changes", "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        val writer = syncWriter(appContext)
        for (changedKey in listOf("jojotv_home_hidden_resume_keys", if (hide) removedKey else restoredKey)) {
            val previous = revisions.optJSONObject(changedKey)?.optLong("changedAt", 0L) ?: 0L
            revisions.put(changedKey, JSONObject().put("changedAt", maxOf(at, previous + 1L)).put("writer", writer))
        }
        prefs.edit().putStringSet("jojotv_home_hidden_resume_keys", hidden)
            .putLong(if (hide) removedKey else restoredKey, at)
            .putString("nas_setting_changes", revisions.toString())
            .remove("continue_watching_home_cache")
            .putBoolean("nas_home_pending", true).putLong("nas_home_updated_at", at).apply()
    }
    fun recordPlayback(item: IptvItem, accountId: String = selectedAccount()?.id.orEmpty()) = synchronized(homeLibraryLock) {
        val next = library.withPlayback(item, accountId, System.currentTimeMillis())
        if (next != library) commitActivity(next)
        setHomeResumeHidden(homeContentId(item), false, accountId)
    }
    fun removeHistory(item: IptvItem? = null, accountId: String = selectedAccount()?.id.orEmpty()) = synchronized(homeLibraryLock) {
        val next = library.withoutHistory(item, accountId)
        if (next != library) commitActivity(next)
    }
    fun saved(type: ContentType, history: Boolean): List<IptvItem> {
        val current = library
        val items = current.remembered.associateBy { it.id }
        val ids = if (history) current.history else current.favorites.toList()
        val oldest = System.currentTimeMillis() - (current.settings["historyDays"]?.toIntOrNull() ?: 7).coerceIn(1, 365) * 86_400_000L
        return ids.filter { !history || (current.playedAt[it]?.takeIf { at -> at > 0 } ?: Long.MAX_VALUE) >= oldest }.mapNotNull(items::get).filter {
            playlistEnabled(it.sourceId) && (it.type == type || type == ContentType.SERIES && it.type == ContentType.EPISODE)
        }
    }
    internal fun organizeSaved(items: List<IptvItem>, type: ContentType): List<IptvItem> {
        val key = IptvOrganization.key("", type, "favorites", "channelSort")
        return IptvOrganization.sortedItems(items, mapOf(key to setting(key, "playlist")), emptySet(), "", type, "favorites")
    }
    private fun remember(item: IptvItem): List<IptvItem> {
        val required = library.favorites + library.accountStates.values.flatMap { it.favorites + it.history } + library.history + item.id
        return listOf(IptvOrganization.originalItem(item, library.settings)) + library.remembered.filter { it.id != item.id && it.id in required }
    }
    private fun commitActivity(next: IptvLibrary) {
        val saved = next.initializeAccounts().checkpointAccount()
        val cloud = cloudActivitySelected()
        val changes = if (cloud) emptyMap() else iptvActivityChanges(library, saved,
            maxOf(System.currentTimeMillis(), (library.syncRecords.values.maxOfOrNull { it.changedAt } ?: 0L) + 1), syncWriter(appContext))
        persistActivity(saved.copy(syncRecords = library.syncRecords + changes), activitySyncRecords + changes)
        if (cloud) com.jojotv.home.progresspilot.AccountContentRouting.runtime(appContext).kick()
        else if (changes.isNotEmpty()) requestSync(appContext, null)
    }
    private fun persistActivity(next: IptvLibrary, records: Map<String, IptvSyncRecord> = activitySyncRecords) {
        write(activityFile, encrypt(iptvJson.encodeToString(next.activityCheckpoint(records))))
        activitySyncRecords = records
        library = next
        publishHomeFavorites()
    }
    private fun commit(next: IptvLibrary) {
        val before = iptvSyncValues(library)
        val saved = next.initializeAccounts().checkpointAccount()
        val after = iptvSyncValues(saved)
        val records = library.syncRecords.toMutableMap()
        val at = maxOf(System.currentTimeMillis(), (records.values.maxOfOrNull { it.changedAt } ?: 0L) + 1)
        val writer = syncWriter(appContext)
        val changedKeys = (before.keys + after.keys).filter { before[it] != after[it] }
        changedKeys.filterNot(::cloudManaged).forEach { name ->
            records[name] = IptvSyncRecord(at, writer, after[name])
        }
        val changedAccounts = changedKeys.filter { it.startsWith("profile:") &&
            !com.jojotv.home.progresspilot.AccountSettingsRouting.managesIptv(appContext, it) }
            .map { it.removePrefix("profile:") }.toSet()
        if (changedAccounts.isNotEmpty()) records.filter { (key, record) -> key.startsWith("accountName:") && record.value != null && record.value in changedAccounts }
            .forEach { (key, record) -> records[key] = record.copy(changedAt = at, writer = writer) }
        val syncChanged = records != library.syncRecords
        persist(saved.copy(syncRecords = records))
        if (cloudActivitySelected() && changedKeys.any(::iptvCloudActivityRecord))
            com.jojotv.home.progresspilot.AccountContentRouting.runtime(appContext).kick()
        if (syncChanged) requestSync(appContext, null)
    }
    private fun persist(next: IptvLibrary) {
        val saved = next.copy(activityRevision = library.activityRevision + 1)
        writeLibrary(file, saved)
        library = saved
        activityFile.delete()
        activitySyncRecords = emptyMap()
        rememberAccountSelection(next.selectedAccountId)
        publishHomeFavorites()
    }

    private fun rememberAccountSelection(id: String) {
        if (accountSelectionPrefs.getString("account_id", "") != id) {
            accountSelectionPrefs.edit().putString("account_id", id).apply()
        }
    }

    internal fun rememberLastProfile(): Boolean = accountSelectionPrefs.getBoolean("remember_last_profile", false)

    internal fun setRememberLastProfile(enabled: Boolean) {
        accountSelectionPrefs.edit().putBoolean("remember_last_profile", enabled).apply()
    }

    /** Catalogue dates only add shared settings; diffing every account's history here is unnecessary. */
    private fun addChannelDates(values: Map<String, String>) {
        if (values.isEmpty()) return
        val records = library.syncRecords.toMutableMap()
        val at = maxOf(System.currentTimeMillis(), (records.values.maxOfOrNull { it.changedAt } ?: 0L) + 1)
        val writer = syncWriter(appContext)
        values.forEach { (name, value) -> records["setting:$name"] = IptvSyncRecord(at, writer, value) }
        persist(library.copy(settings = library.settings + values, syncRecords = records))
        requestSync(appContext, null)
    }

    private fun mergeRemote(records: Map<String, IptvSyncRecord>): Boolean = synchronized(homeLibraryLock) {
        val accepted = records.filterKeys { !cloudManaged(it) }
        val merged = mergeIptvLibrary(library, accepted)
        val settings = if (com.jojotv.home.progresspilot.AccountSettingsRouting.selected(appContext))
            preserveSupabaseIptvSettings(library, merged) else merged
        val next = if (cloudActivitySelected()) preserveCloudIptvActivity(library, settings) else settings
        if (next == library) return@synchronized false
        val activityOnly = library.onlyActivityChanged(next)
        val changed = activityOnly || iptvSyncValues(library) != iptvSyncValues(next)
        val changedSources = (library.sources + next.sources).map { it.id }.distinct().filter { id ->
            library.sources.firstOrNull { it.id == id } != next.sources.firstOrNull { it.id == id }
        }
        if (activityOnly) persistActivity(next, activitySyncRecords + next.syncRecords.filter { (key, value) -> library.syncRecords[key] != value })
        else persist(next)
        changedSources.forEach(::clearCache)
        if (changedSources.isNotEmpty()) homeSeries = null
        changed
    }

    /** Retain the unreadable library and never publish an empty NAS snapshot during recovery. */
    internal fun recoverFromNas() {
        try { syncWithNas(appContext, this) } catch (problem: Exception) {
            // Exceptions can contain account URLs: log types and frames only.
            val types = generateSequence<Throwable>(problem) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name }
            val frames = problem.stackTrace.take(12).joinToString(" | ")
            android.util.Log.e("JojoHomeIptv", "NAS recovery failed: $types; $frames")
            throw problem
        }
    }

    private fun restoreNasSnapshot(snapshot: IptvSyncSnapshot): Boolean = synchronized(homeLibraryLock) {
        try {
            load()
            return@synchronized false // Another sync may already have repaired the local library.
        } catch (problem: IllegalStateException) {
            if (problem.cause !is javax.crypto.AEADBadTagException) throw problem
        }
        check(!com.jojotv.home.progresspilot.AccountSettingsRouting.selected(appContext) && !cloudActivitySelected()) {
            "La bibliothèque IPTV est illisible. Les profils et paramètres Supabase restent protégés ; la récupération NAS nécessite un choix explicite de source. Données locales conservées."
        }
        val recovered = iptvRecoveryLibrary(snapshot).copy(activityRevision = library.activityRevision + 1)
            .restoreLocalAccount(accountSelectionPrefs.getString("account_id", "").orEmpty())
        // Do not lose local schedules or recover into a state that still cannot finish startup.
        try { recordings(); reminders() } catch (problem: Exception) {
            throw IllegalStateException("Les rappels ou les enregistrements locaux sont aussi illisibles. Récupération interrompue ; données conservées.", problem)
        }
        val payload = iptvJson.encodeToString(recovered)
        val encrypted = encrypt(payload)
        val checked = try { iptvJson.decodeFromString<IptvLibrary>(decrypt(encrypted)) } catch (problem: javax.crypto.AEADBadTagException) {
            android.util.Log.e("JojoHomeIptv", "Full library crypto check failed; plainBytes=${payload.toByteArray(Charsets.UTF_8).size}; sealedBytes=${encrypted.size}")
            throw IllegalStateException("Le chiffrement de la bibliothèque complète ne fonctionne pas sur cet appareil. Données locales conservées.", problem)
        }
        check(checked == recovered) { "Vérification du stockage IPTV impossible." }
        val archive = AtomicFile(File(folder, "library-unreadable-${UUID.randomUUID()}.bin"))
        write(archive, file.readFully())
        write(file, encrypted)
        library = recovered
        activityFile.delete()
        activitySyncRecords = emptyMap()
        publishHomeFavorites()
        recovered.sources.forEach { clearCache(it.id) }
        homeSeries = null
        android.util.Log.i("JojoHomeIptv", "Local library recovered from NAS; sources=${recovered.sources.size}; favorites=${recovered.favorites.size}")
        true
    }
    private fun publishHomeFavorites() {
        val current = library
        val previous = homeProjectionLibrary
        val accountChanged = iptvHomeAccountChanged(previous, current)
        var favoritesChanged = false
        if (accountScope.value?.selectedAccountId != current.selectedAccountId) homeSeries = null
        if (previous == null || previous.remembered !== current.remembered || previous.favorites !== current.favorites ||
            previous.sources !== current.sources || current.sources.any {
                IptvPlaylistSettings.enabled(previous.settings, it.id) != IptvPlaylistSettings.enabled(current.settings, it.id)
            }) {
            val projection = projectHomeFavorites(current.remembered, current.favorites,
                current.sources.filter { IptvPlaylistSettings.enabled(current.settings, it.id) }.map { it.id }.toSet())
            val changed = homeSnapshot != projection
            homeSnapshot = projection
            favoritesChanged = changed
        }
        homeSettings = current.settings
        val settings = current.settingsSnapshot()
        if (settings != homeSettingsSnapshot) homeSettingsSnapshot = settings
        homeProjectionLibrary = current
        accountScope.value = current.playbackScope()
        libraryLoaded = true
        if (favoritesChanged) homeFavoriteRevision.incrementAndGet()
        if (accountChanged) homeAccountRevision.incrementAndGet()
        if (favoritesChanged || accountChanged) notifyHomeChanges()
    }
    fun cache(source: String, type: ContentType, category: String, page: Int, items: List<IptvItem>,
        registerContents: Boolean = true, total: Int? = null) {
        if (registerContents) registerCloudCatalogue(items)
        if (type == ContentType.LIVE) synchronized(homeLibraryLock) {
            val now = System.currentTimeMillis().toString()
            val added = items.filter { "channelAdded:${it.id}" !in library.settings }.associate { "channelAdded:${it.id}" to now }
            addChannelDates(added)
        }
        val target = cacheFile(source, type, category, page)
        synchronized(target.baseFile.absolutePath.intern()) {
            write(target, encrypt(iptvJson.encodeToString(items)))
        }
        if (category.isBlank() && total != null) cachePlaylistCount(source, type, total)
    }
    internal fun cachePlaylistCount(source: String, type: ContentType, total: Int) {
        val target = cacheFile(source, type, "", -2)
        synchronized(target.baseFile.absolutePath.intern()) { write(target, encrypt(total.coerceAtLeast(0).toString())) }
    }
    internal fun playlistCounts(source: String): Map<ContentType, Int> = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
        .mapNotNull { type ->
            val target = cacheFile(source, type, "", -2)
            val total = synchronized(target.baseFile.absolutePath.intern()) {
                runCatching { decrypt(target.readFully()).toIntOrNull() }.getOrNull()
            }
            total?.let { type to it }
        }.toMap()
    internal fun playlistUpdatedAt(source: String): Long = cacheFile(source, ContentType.LIVE, "", -3).baseFile.lastModified()
    internal fun markPlaylistUpdated(source: String) {
        val target = cacheFile(source, ContentType.LIVE, "", -3)
        synchronized(target.baseFile.absolutePath.intern()) { write(target, encrypt(System.currentTimeMillis().toString())) }
    }
    fun cached(source: String, type: ContentType, category: String, page: Int): List<IptvItem> {
        val target = cacheFile(source, type, category, page)
        return synchronized(target.baseFile.absolutePath.intern()) {
            if (target.baseFile.exists()) iptvJson.decodeFromString(decrypt(target.readFully())) else emptyList()
        }
    }
    internal fun cacheCategories(source: String, type: ContentType, values: List<IptvCategory>) {
        val target = cacheFile(source, type, "", -1)
        synchronized(target.baseFile.absolutePath.intern()) { write(target, encrypt(iptvJson.encodeToString(values))) }
    }
    internal fun cachedCategories(source: String, type: ContentType): List<IptvCategory>? {
        val target = cacheFile(source, type, "", -1)
        return synchronized(target.baseFile.absolutePath.intern()) {
            if (target.baseFile.exists()) iptvJson.decodeFromString(decrypt(target.readFully())) else null
        }
    }
    private fun cacheFile(source: String, type: ContentType, category: String, page: Int): AtomicFile =
        AtomicFile(File(folder, "${digest(source)}_${digest("${type.name}:$category:$page")}.bin"))
    fun clearCache(source: String) {
        // Only this app's cache files, scoped to this exact connection.
        folder.listFiles()?.filter { it.name.startsWith("${digest(source)}_") }?.forEach { it.delete() }
    }
    internal fun recordings(allAccounts: Boolean = false): List<IptvRecording> {
        val values = synchronized(recordingLock) {
            if (recordingFile.baseFile.exists())
                iptvJson.decodeFromString<List<IptvRecording>>(decrypt(recordingFile.readFully())) else emptyList()
        }
        return if (allAccounts) values else values.filter { ownsAccount(it.accountId) }
    }

    internal fun recordingDeviceId(): String = if (cloudActivitySelected())
        com.jojotv.home.progresspilot.AccountContentRouting.runtime(appContext).installationId() else syncWriter(appContext)
    internal fun recordingAccount(recording: IptvRecording): String =
        resolveAccountId(recording.accountId.ifBlank { library.legacyAccountId })

    internal fun readRecordingSync(authority: String): IptvSyncSnapshot = synchronized(recordingLock) {
        require(authority.matches(Regex("[0-9a-f]{64}")))
        val sync = AtomicFile(File(folder, "recording-sync-$authority.bin"))
        if (sync.baseFile.exists()) iptvJson.decodeFromString<IptvSyncSnapshot>(decrypt(sync.readFully())) else IptvSyncSnapshot()
    }

    internal fun writeRecordingSync(authority: String, snapshot: IptvSyncSnapshot) = synchronized(recordingLock) {
        require(authority.matches(Regex("[0-9a-f]{64}")))
        write(AtomicFile(File(folder, "recording-sync-$authority.bin")), encrypt(iptvJson.encodeToString(snapshot)))
    }
    internal fun saveRecording(recording: IptvRecording) {
        val saved = synchronized(homeLibraryLock) { synchronized(recordingLock) {
            val previous = recordings(true)
            val owner = recording.accountId.ifBlank {
                previous.firstOrNull { it.id == recording.id }?.accountId?.ifBlank { library.legacyAccountId }
                    ?: library.selectedAccountId
            }
            val values = previous.filterNot { it.id == recording.id } + recording.copy(accountId = owner)
            write(recordingFile, encrypt(iptvJson.encodeToString(values)))
            values.last()
        } }
        IptvRecordingSync.localChanged(appContext, this, saved)
    }
    internal fun removeRecording(id: String) {
        synchronized(recordingLock) {
            write(recordingFile, encrypt(iptvJson.encodeToString(recordings(true).filterNot { it.id == id })))
        }
        IptvRecordingSync.localRemoved(appContext, this, id)
    }
    internal fun reminders(allAccounts: Boolean = false): List<IptvReminder> {
        val values = synchronized(recordingLock) {
            if (reminderFile.baseFile.exists())
                iptvJson.decodeFromString<List<IptvReminder>>(decrypt(reminderFile.readFully())) else emptyList()
        }
        return if (allAccounts) values else values.filter { ownsAccount(it.accountId) }
    }
    internal fun saveReminder(reminder: IptvReminder) = synchronized(homeLibraryLock) {
        synchronized(recordingLock) {
            val previous = reminders(true)
            val owner = reminder.accountId.ifBlank {
                previous.firstOrNull { it.id == reminder.id }?.accountId?.ifBlank { library.legacyAccountId }
                    ?: library.selectedAccountId
            }
            write(reminderFile, encrypt(iptvJson.encodeToString(previous.filterNot { it.id == reminder.id } + reminder.copy(accountId = owner))))
        }
    }
    internal fun removeReminder(id: String) = synchronized(recordingLock) {
        write(reminderFile, encrypt(iptvJson.encodeToString(reminders(true).filterNot { it.id == id })))
    }
    companion object {
        private val storageKeyLock = Any()
        private val avatarCatalogLock = Any()
        @Volatile private var sharedAvatarCatalog: Pair<String, List<IptvAvatar>>? = null
        private val avatarBitmaps = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val recordingLock = Any()
        private val homeLibraryLock = Any()
        private var storageFailureDiagnosed = false
        private var nasSyncFailureDiagnosed = false
        @Volatile private var sharedLibrary = IptvLibrary()
        private var activitySyncRecords = emptyMap<String, IptvSyncRecord>()
        @Volatile private var libraryLoaded = false
        private var homeProjectionLibrary: IptvLibrary? = null
        private val homeFavoriteRevision = java.util.concurrent.atomic.AtomicLong()
        /** Native Home observes local/cloud projection changes independently of NAS callbacks. */
        @JvmStatic fun homeFavoritesRevision(): Long = homeFavoriteRevision.get()
        private val homeAccountRevision = java.util.concurrent.atomic.AtomicLong()
        @JvmStatic fun homeAccountsRevision(): Long = homeAccountRevision.get()
        private val homeArtworkRevision = java.util.concurrent.atomic.AtomicLong()
        private val homeChangeObservers = java.util.concurrent.CopyOnWriteArraySet<Runnable>()
        @JvmStatic fun homeArtworkRevision(): Long = homeArtworkRevision.get()
        @JvmStatic fun observeHomeChanges(observer: Runnable) { homeChangeObservers.add(observer) }
        @JvmStatic fun removeHomeChangeObserver(observer: Runnable) { homeChangeObservers.remove(observer) }
        @JvmStatic fun homeArtworkChanged() { homeArtworkRevision.incrementAndGet(); notifyHomeChanges() }
        private fun notifyHomeChanges() { homeChangeObservers.forEach { it.run() } }
        private val accountScope = MutableStateFlow<IptvPlaybackScope?>(null)
        internal val accountSelection = accountScope.asStateFlow()
        private val nasSyncLock = Any()
        private val nasSyncWork = Executors.newSingleThreadExecutor()
        private var nasSyncRunning = false
        private var nasSyncAgain = false
        private val nasSyncCallbacks = mutableListOf<Runnable>()
        @Volatile private var homeSnapshot: Map<String, String>? = null
        @Volatile private var homeSettings: Map<String, String> = emptyMap()
        @Volatile private var homeSettingsSnapshot = emptyMap<String, String>() to emptyMap<String, Map<String, String>>()
        @Volatile private var homeSeries: IptvHomeSeries? = null
        private val homeLoadLock = Any()
        private val homeLoadWork = Executors.newSingleThreadExecutor { task -> Thread(task, "JojoHomeIptvLoad") }
        private var homeLoadScheduled = false

        private fun syncWriter(context: Context): String {
            val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            return synchronized(prefs) {
                prefs.getString("nas_device_id", "").orEmpty().ifBlank {
                    "jojohome-${UUID.randomUUID()}".also { prefs.edit().putString("nas_device_id", it).commit() }
                }
            }
        }

        /** The authenticated NAS secret endpoint encrypts this payload at rest; never use a public blob. */
        @JvmStatic fun requestSync(context: Context, onChanged: Runnable?) {
            val app = context.applicationContext
            IptvRecordingSync.request(app)
            if (!com.jojotv.home.progresspilot.NasDataSyncRouting.enabled(app)) return
            if (app.getSharedPreferences("home_settings", Context.MODE_PRIVATE).getString("nas_token", "").isNullOrBlank()) return
            synchronized(nasSyncLock) {
                onChanged?.let(nasSyncCallbacks::add)
                if (nasSyncRunning) {
                    if (onChanged == null) nasSyncAgain = true
                    return
                }
                nasSyncRunning = true
            }
            nasSyncWork.execute {
                var changed = false
                while (true) {
                    val prefs = app.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                    val before = synchronized(homeLibraryLock) { sharedLibrary }
                    try {
                        changed = syncWithNas(app) || changed
                        nasSyncFailureDiagnosed = false
                        prefs.edit().remove("nas_iptv_sync_error").putLong("nas_iptv_sync_success", System.currentTimeMillis()).apply()
                    } catch (problem: Exception) {
                        // Network/library exceptions can contain account URLs. Keep diagnostics generic.
                        if (!nasSyncFailureDiagnosed) {
                            nasSyncFailureDiagnosed = true
                            val types = generateSequence<Throwable>(problem) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name }
                            val frames = problem.stackTrace.take(12).joinToString(" | ")
                            android.util.Log.e("JojoHomeIptv", "NAS sync failed: $types; $frames")
                        }
                        prefs.edit().putString("nas_iptv_sync_error", "Synchronisation IPTV NAS indisponible. Nouvelle tentative automatique.").apply()
                    } finally {
                        changed = changed || synchronized(homeLibraryLock) { before != sharedLibrary }
                    }
                    var callbacks = emptyList<Runnable>()
                    val repeat = synchronized(nasSyncLock) {
                        if (nasSyncAgain) { nasSyncAgain = false; true } else {
                            nasSyncRunning = false
                            callbacks = nasSyncCallbacks.toList(); nasSyncCallbacks.clear()
                            false
                        }
                    }
                    if (!repeat) {
                        if (changed) callbacks.forEach { it.run() }
                        break
                    }
                }
            }
        }

        private fun syncWithNas(context: Context, recovery: IptvStore? = null): Boolean {
            if (!com.jojotv.home.progresspilot.NasDataSyncRouting.enabled(context)) {
                if (recovery != null) error("La synchronisation NAS est désactivée sur cette TV.")
                return false
            }
            val prefs = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            val token = prefs.getString("nas_token", "").orEmpty()
            if (recovery != null) check(token.isNotBlank()) { "Connecte cette TV au NAS avant de récupérer les données IPTV." }
            if (token.isBlank()) return false
            val base = prefs.getString("nas_url", null)?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
                ?: com.jojotv.home.BuildConfig.JOJOTV_NAS_SYNC_URL.trimEnd('/')
            val address = "$base/api/sync/profiles/1/secrets/jojo-home-iptv"
            val writer = syncWriter(context)
            fun request(value: IptvSyncSnapshot? = null): IptvSyncSnapshot? {
                val builder = Request.Builder().url(address).header("Authorization", "Bearer $token")
                    .header("X-JojoTV-Device-Id", writer)
                if (value != null) builder.put(iptvNasSyncBody(value))
                return IptvHttp.client.newCall(builder.build()).execute().use { response ->
                    check(response.isSuccessful) { "NAS HTTP ${response.code}" }
                    // Stream both directions: rebuilding a multi-megabyte JSON
                    // String/JSONObject exhausted the TV heap during playback.
                    readIptvNasSyncSnapshot(checkNotNull(response.body).byteStream())
                }
            }
            val remote = request() ?: IptvSyncSnapshot()
            check(remote.schema == 1) { "Version de synchronisation IPTV incompatible." }
            fun sameSession() = com.jojotv.home.progresspilot.NasDataSyncRouting.enabled(context) &&
                prefs.getString("nas_token", "") == token &&
                (prefs.getString("nas_url", null)?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
                    ?: com.jojotv.home.BuildConfig.JOJOTV_NAS_SYNC_URL.trimEnd('/')) == base
            // A logout/account switch during a request must never apply its response to the new session.
            if (!sameSession()) {
                if (recovery != null) error("Le compte NAS a changé. Relance la récupération.")
                return false
            }
            var changed = if (recovery != null) synchronized(homeLibraryLock) {
                check(sameSession()) { "Le compte NAS a changé. Relance la récupération." }
                recovery.restoreNasSnapshot(remote)
            } else synchronized(homeLibraryLock) {
                if (!sameSession()) return@synchronized false
                val store = IptvStore(context)
                var restored = false
                iptvReadWithRecovery({ store.load() }) {
                    check(sameSession()) { "Le compte NAS a changé. Relance la récupération." }
                    restored = store.restoreNasSnapshot(remote)
                }
                restored || store.mergeRemote(remote.records)
            }
            val snapshot = synchronized(homeLibraryLock) { IptvSyncSnapshot(records = sharedLibrary.syncRecords.filterKeys {
                !com.jojotv.home.progresspilot.AccountSettingsRouting.managesIptv(context, it) &&
                    !(com.jojotv.home.progresspilot.AccountContentRouting.selected(context) && iptvCloudActivityRecord(it))
            }) }
            val comparableRemote = remote.records.filterKeys { !com.jojotv.home.progresspilot.AccountSettingsRouting.managesIptv(context, it) &&
                !(com.jojotv.home.progresspilot.AccountContentRouting.selected(context) && iptvCloudActivityRecord(it)) }
            if (recovery == null && sameSession() && snapshot.records != comparableRemote) {
                request(snapshot)?.let { published ->
                    check(published.schema == 1) { "Version de synchronisation IPTV incompatible." }
                    synchronized(homeLibraryLock) {
                        if (sameSession()) changed = IptvStore(context).mergeRemote(published.records) || changed
                    }
                }
            }
            return changed
        }

        fun applyPlayerSettings(context: Context, contentId: String?, base: PlayerSettings): PlayerSettings {
            if (contentId?.startsWith("iptv:") != true) return base
            homeSnapshot(context)
            return playerSettings(homeSettings, base, contentId.substringBeforeLast(':').substringAfterLast(':') == "live")
        }
        fun hasBufferSetting(context: Context, contentId: String?): Boolean {
            if (contentId?.startsWith("iptv:") != true) return false
            homeSnapshot(context)
            return homeSettings["bufferSize"] in setOf("none", "small", "medium", "large", "extraLarge")
        }

        internal fun playerSettings(values: Map<String, String>, base: PlayerSettings, live: Boolean = true): PlayerSettings {
            fun threshold(key: String, fallback: Float, min: Float, max: Float): Float =
                values[key]?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.let { (it.coerceIn(min, max) * 2f).roundToInt() / 2f } ?: fallback
            val seconds = when (values["bufferSize"]) { "none" -> 0; "small" -> 15; "medium" -> 30; "large" -> 60; "extraLarge" -> 120; else -> null }
            val legacyAfr = values["afr"]?.let { runCatching { FrameRateMatchingMode.valueOf(it) }.getOrNull() } ?: base.frameRateMatchingMode
            val customAfr = listOf("afrLive", "afrVod", "afrRefresh", "afrResolution").any(values::containsKey)
            val afrEnabled = values[if (live) "afrLive" else "afrVod"]?.toBooleanStrictOrNull() ?: (legacyAfr != FrameRateMatchingMode.OFF)
            val afrMode = if (!customAfr) legacyAfr else if (afrEnabled &&
                (values["afrRefresh"] != "false" || values["afrResolution"] == "true")) FrameRateMatchingMode.START_STOP else FrameRateMatchingMode.OFF
            return base.copy(
                bufferSettings = seconds?.let { base.bufferSettings.copy(minBufferMs = it * 1000,
                    maxBufferMs = it * 2000, bufferForPlaybackMs = if (it == 0) 0 else 1500,
                    bufferForPlaybackAfterRebufferMs = if (it == 0) 0 else 3000) } ?: base.bufferSettings,
                decoderPriority = when (values["decoderPriority"]) { "hardware" -> 0; "software" -> 2; else -> base.decoderPriority },
                frameRateMatchingMode = afrMode,
                resolutionMatchingEnabled = if (customAfr) afrMode != FrameRateMatchingMode.OFF && values["afrResolution"] == "true" else base.resolutionMatchingEnabled,
                osdClockEnabled = values["playerClock"]?.toBooleanStrictOrNull() ?: base.osdClockEnabled,
                skipIntroEnabled = values["skipIntroEnabled"]?.toBooleanStrictOrNull() ?: base.skipIntroEnabled,
                streamAutoPlayNextEpisodeEnabled = if (live) base.streamAutoPlayNextEpisodeEnabled
                    else values["autoNextEpisode"]?.toBooleanStrictOrNull() ?: true,
                nextEpisodeThresholdMode = if (live) base.nextEpisodeThresholdMode else values["nextEpisodeThresholdMode"]
                    ?.let { runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrNull() } ?: base.nextEpisodeThresholdMode,
                nextEpisodeThresholdPercent = if (live) base.nextEpisodeThresholdPercent
                    else threshold("nextEpisodeThresholdPercent", base.nextEpisodeThresholdPercent, 95f, 100f),
                nextEpisodeThresholdMinutesBeforeEnd = if (live) base.nextEpisodeThresholdMinutesBeforeEnd
                    else threshold("nextEpisodeThresholdMinutesBeforeEnd", base.nextEpisodeThresholdMinutesBeforeEnd, 0f, 3.5f),
                preferredAudioLanguage = values["audioLanguage"] ?: base.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = if ("secondaryAudioLanguage" in values)
                    values["secondaryAudioLanguage"]?.takeUnless { it == "none" }
                    else base.secondaryPreferredAudioLanguage,
                subtitleStyle = base.subtitleStyle.copy(
                    preferredLanguage = values["subtitleLanguage"] ?: base.subtitleStyle.preferredLanguage,
                    secondaryPreferredLanguage = if ("secondarySubtitleLanguage" in values)
                        values["secondarySubtitleLanguage"]?.takeUnless { it == "none" }
                        else base.subtitleStyle.secondaryPreferredLanguage,
                    useForcedSubtitles = values["subtitleUseForced"]?.toBooleanStrictOrNull()
                        ?: base.subtitleStyle.useForcedSubtitles,
                    showOnlyPreferredLanguages = values["subtitleShowOnlyPreferredLanguages"]?.toBooleanStrictOrNull()
                        ?: base.subtitleStyle.showOnlyPreferredLanguages),
                rewindStepSeconds = values["seekSeconds"]?.toIntOrNull()?.coerceIn(5, 120) ?: base.rewindStepSeconds,
                forwardStepSeconds = values["seekSeconds"]?.toIntOrNull()?.coerceIn(5, 120) ?: base.forwardStepSeconds,
            )
        }

        internal fun playerAppearance(context: Context, contentId: String?): Map<String, String> {
            if (contentId?.startsWith("iptv:") != true) return emptyMap()
            homeSnapshot(context)
            return homeSettings
        }

        internal fun playerPanelTimeout(values: Map<String, String>): Long =
            (values["playerPanelTimeout"]?.toLongOrNull()?.coerceIn(0, 60) ?: 5L) * 1000L

        internal fun playerMenuOrder(value: String, defaults: List<String>): List<String> =
            (IptvOrganization.ids(value).filter { it in defaults } + defaults).distinct()

        internal fun sourceWithUserAgent(source: IptvSource, userAgent: String): IptvSource {
            if (userAgent.isBlank() || source.headers.keys.any { it.equals("User-Agent", true) }) return source
            return source.copy(headers = source.headers + ("User-Agent" to userAgent))
        }

        internal fun searchPrograms(channel: IptvItem, programs: List<EpgProgram>, query: String, now: Long,
            pastDays: Int, includePastWithoutReplay: Boolean, offsetMinutes: Int = 0, descriptions: Boolean = true): List<EpgProgram> {
            if (query.isBlank()) return emptyList()
            return epgPrograms(programs, now, pastDays, descriptions, offsetMinutes)
                .filter { program -> program.title.contains(query.trim(), true) &&
                    (program.end > now || includePastWithoutReplay || channel.catchupDays > 0 &&
                        program.start >= now - channel.catchupDays * 86_400_000L) }
                .distinctBy { it.start to it.title }
        }

        internal fun epgPrograms(programs: List<EpgProgram>, now: Long, pastDays: Int,
            descriptions: Boolean, offsetMinutes: Int): List<EpgProgram> {
            val offset = offsetMinutes.coerceIn(-720, 720) * 60_000L
            val oldest = now - pastDays.coerceIn(1, 14) * 86_400_000L
            return programs.map { it.copy(start = it.start + offset, end = it.end + offset,
                description = if (descriptions) it.description else "") }.filter { it.end > oldest }.sortedBy { it.start }
        }

        private fun homeSnapshot(context: Context): Map<String, String> {
            if (homeSnapshot == null) {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    synchronized(homeLoadLock) {
                        if (!homeLoadScheduled) {
                            homeLoadScheduled = true
                            val app = context.applicationContext
                            homeLoadWork.execute {
                                try { IptvStore(app).load() } catch (problem: Exception) {
                                    android.util.Log.w("JojoHomeIptv", "Home library unavailable: ${problem.javaClass.simpleName}")
                                } finally { synchronized(homeLoadLock) { homeLoadScheduled = false } }
                            }
                        }
                    }
                } else if (runCatching { IptvStore(context).load() }.isFailure) return emptyMap()
            }
            return homeSnapshot.orEmpty()
        }
        internal fun ownsPlaybackContentId(context: Context, id: String): Boolean {
            if (!id.startsWith("iptv:")) return true
            homeSnapshot(context)
            return accountScope.value?.owns(id) == true
        }
        @JvmStatic fun catalogContentId(id: String): String = iptvCatalogContentId(id)

        @JvmStatic fun homeFavorites(context: Context, category: String): List<JSONObject> =
            homeSnapshot(context).values.map(::JSONObject).filter { it.optString("category") == category }

        @JvmStatic fun homeFavorite(context: Context, id: String): JSONObject {
            if (id.startsWith("iptv:account:") && !ownsPlaybackContentId(context, id)) return JSONObject()
            val catalogId = iptvCatalogContentId(id)
            return (homeSnapshot(context)[catalogId] ?: homeSeries?.metadata?.get(catalogId))?.let(::JSONObject) ?: JSONObject()
        }

        // Blocking bridges run on Home's workers. URLs/headers only enter the player Intent.
        @JvmStatic fun enrichHomeMovieRuntime(context: Context, id: String) {
            val store = homeStore(context)
            val catalogId = iptvCatalogContentId(id)
            val item = store.rememberedItem(catalogId)?.takeIf { it.type == ContentType.MOVIE } ?: return
            val source = store.sources().firstOrNull { it.id == item.sourceId }?.let(store::connectionSource) ?: return
            val provider = providerFor(source)
            // Stalker's catalogue response carries the full fiche; its default details() only echoes the input.
            val details = if (source.type == SourceType.STALKER) provider.search(
                IptvMediaPresentation.searchTitle(item.title), item.type).items.singleOrNull {
                    it.id == item.id && it.sourceId == item.sourceId && it.providerId == item.providerId && it.type == item.type
                } ?: return else provider.details(item)
            synchronized(homeLibraryLock) {
                val currentStore = homeStore(context)
                val current = currentStore.rememberedItem(catalogId) ?: return
                val currentSource = currentStore.sources().firstOrNull { it.id == current.sourceId }
                    ?.let(currentStore::connectionSource)
                if (currentSource != source) return
                val enriched = current.withProviderMetadata(details)
                if (enriched == current) return
                currentStore.commit(currentStore.library.copy(remembered = currentStore.remember(enriched)))
            }
        }

        @JvmStatic fun applyHomeFavoriteProgress(context: Context, items: List<JSONObject>): List<JSONObject> {
            if (items.none { it.optString("id").startsWith("iptv:") && it.optString("category") in listOf("movies", "series") }) return items
            homeStore(context) // Load the selected account without rewriting the cloud catalogue.
            val entry = progressEntryPoint(context)
            val profile = entry.profileManager().activeProfileId.value
            val (progress, hidden) = runBlocking { entry.watchProgressPreferences().getHomeProgressSnapshot(profile) }
            return items.map { item ->
                val id = item.optString("id")
                val category = item.optString("category")
                if (!id.startsWith("iptv:") || category !in listOf("movies", "series")) item else {
                    val current = sharedLibrary
                    val contentId = iptvPlaybackContentId(id, current.selectedAccountId, current.legacyAccountId)
                    val saved = homeFavoritePlaybackProgress(contentId, category, progress)
                    JSONObject(item.toString()).put("position", saved?.position ?: 0)
                        .put("duration", saved?.duration ?: 0).put("watched", saved?.isCompleted() == true)
                        .put("seriesStarted", category == "series" && homeFavoriteSeriesStarted(contentId, progress))
                        .put("resumeHidden", homeFavoriteResumeHidden(contentId, category, progress, hidden))
                        .put("progressProfileId", profile)
                }
            }
        }

        internal fun visibleVodProgress(progress: Collection<WatchProgress>, hidden: Set<String>): List<WatchProgress> =
            progress.filter { it.contentType in listOf("movie", "series") }.groupBy { it.storageKey }
                .values.map { it.maxByOrNull { saved -> saved.lastWatched }!! }.filterNot { it.storageKey in hidden }

        internal fun homeFavoritePlaybackProgress(id: String, category: String, progress: List<WatchProgress>): WatchProgress? {
            val type = when (category) { "movies" -> "movie"; "series" -> "series"; else -> return null }
            val matching = progress.filter { it.contentId == id && it.contentType == type }
            if (category == "movies") return matching.maxByOrNull { it.lastWatched }
            return matching.groupBy { it.season to it.episode }.values.mapNotNull { records ->
                records.maxByOrNull { it.lastWatched }
            }.filter { it.position > 0 && !it.isCompleted() }.maxByOrNull { it.lastWatched }
        }

        internal fun homeFavoriteResumeHidden(id: String, category: String, progress: List<WatchProgress>, hidden: Set<String>): Boolean {
            val type = when (category) { "movies" -> "movie"; "series" -> "series"; else -> return false }
            val latest = progress.filter { it.contentId == id && it.contentType == type }.maxByOrNull { it.lastWatched }
            return latest != null && latest.storageKey in hidden
        }

        internal fun homeFavoriteSeriesStarted(id: String, progress: List<WatchProgress>): Boolean =
            progress.filter { it.contentId == id && it.contentType == "series" }
                .groupBy { it.season to it.episode }.values.any { records ->
                    // Completing the available episodes does not finish watching the series.
                    // Keep the newest state per episode so an explicit unwatched reset wins.
                    val latest = records.maxByOrNull { it.lastWatched }!!
                    latest.position > 0L || latest.progressPercentage > 0f
                }

        @JvmStatic fun homeSeriesDetails(context: Context, id: String): JSONObject {
            val store = IptvStore(context).apply { loadWithNasRecovery() }
            val favorite = homeItem(store, id)
            val accountId = store.selectedAccount()?.id.orEmpty()
            val series = if (favorite.type == ContentType.EPISODE) favorite.copy(
                id = homeContentId(favorite), providerId = favorite.seriesId, type = ContentType.SERIES) else favorite
            check(series.type == ContentType.SERIES)
            val source = store.connectionSource(checkNotNull(store.sources().firstOrNull { it.id == series.sourceId }))
            val cached = homeSeries?.takeIf { it.source == source && it.id == series.id }
            val episodes = cached?.items?.values?.toList() ?: providerFor(source).episodes(series).map {
                it.copy(categoryId = series.categoryId, categoryName = series.categoryName,
                    artwork = it.artwork.ifBlank { series.artwork }, backdrop = it.backdrop.ifBlank { series.backdrop })
            }
            val entry = progressEntryPoint(context)
            val profile = entry.profileManager().activeProfileId.value
            val progress = runBlocking { entry.watchProgressPreferences().getAllRawEntries(profile).values.toList() }
            val payload = homeSeriesPayload(series, episodes, progress,
                iptvPlaybackContentId(series.id, accountId, store.library.legacyAccountId))
            val records = iptvJson.parseToJsonElement(payload).jsonObject["seasons"] as JsonArray
            val metadata = records.flatMap { (it.jsonObject["episodes"] as JsonArray).toList() }
                .associate { it.jsonObject.string("id") to it.toString() }
            return synchronized(homeLibraryLock) {
                check(store.library.selectedAccountId == accountId) { "Le compte IPTV a changé." }
                homeSeries = IptvHomeSeries(source, series.id, episodes.associateBy { it.id }, metadata)
                JSONObject(payload)
            }
        }

        private fun homeItem(store: IptvStore, id: String, refreshMissing: Boolean = false): IptvItem {
            check(!id.startsWith("iptv:account:") || store.ownsAccount(store.playbackAccountId(id))) {
                "Ce contenu appartient à un autre compte."
            }
            val catalogId = iptvCatalogContentId(id)
            val item = store.rememberedItem(catalogId)
                ?: store.library.remembered.firstOrNull { it.type == ContentType.EPISODE && homeContentId(it) == catalogId }
                    ?.let { it.copy(id = catalogId, providerId = it.seriesId, type = ContentType.SERIES) }
                ?: homeSeries?.takeIf { cached ->
                    store.sources().firstOrNull { it.id == cached.source.id }?.let(store::connectionSource) == cached.source
                }?.items?.get(catalogId) ?: run {
                    if (refreshMissing && store.cloudActivitySelected() &&
                        progressEntryPoint(store.appContext).watchProgressPreferences().isCloud()) {
                        // The numeric checkpoint can arrive before its separately synchronized history item.
                        // Refresh that approved transport before resolving playback; never derive a provider ID from its hash.
                        runBlocking { com.jojotv.home.progresspilot.AccountContentRouting.runtime(store.appContext).sync() }
                        store.load()
                        return homeItem(store, id)
                    }
                    error("Ce contenu IPTV n’est plus disponible.")
                }
            check(store.playlistEnabled(item.sourceId)) { "Cette liste de lecture est désactivée." }
            return item
        }

        private fun homeStore(context: Context): IptvStore = synchronized(homeLibraryLock) { IptvStore(context).apply { load() } }

        @JvmStatic @JvmOverloads fun homePlayerIntent(context: Context, id: String, startFromBeginning: Boolean,
            playerPackage: String?, season: Int = 0, episode: Int = 0): Intent {
            val store = IptvStore(context).apply { loadWithNasRecovery() }
            val remembered = homeItem(store, id, refreshMissing = true)
            val item = if ((remembered.type == ContentType.SERIES ||
                    remembered.type == ContentType.EPISODE && remembered.seriesId.isNotBlank()) && season > 0 && episode > 0) {
                // Episode folders must resolve the same current catalogue entry as the Home Hero,
                // rather than play an older command retained in playback history.
                homeSeriesDetails(context, if (remembered.type == ContentType.EPISODE) homeContentId(remembered) else id)
                checkNotNull(homeResumeEpisode(remembered, homeSeries?.items?.values?.toList().orEmpty(), season, episode)) {
                    "Cet épisode IPTV n’est plus disponible."
                }
            } else remembered
            check(item.type != ContentType.SERIES)
            store.registerCloudCatalogue(listOf(item))
            val source = store.connectionSource(checkNotNull(store.sources().firstOrNull { it.id == item.sourceId }))
            val stream = providerFor(source, store.intSetting("epgPastDays", 1), store.intSetting("epgRefreshHours", 24),
                store.setting("udpProxy", "")).resolve(item)
            if (startFromBeginning && item.type == ContentType.EPISODE) setHomeEpisodeWatched(context, item.id, false)
            val mode = store.organization(item.sourceId, item.type, item.categoryId, "externalPlayerMode", "never")
            val preferred = playerPackage ?: if (mode == "all" || mode == "vod" && item.type != ContentType.LIVE)
                store.setting("externalPlayerPackage", "") else ""
            val series = if (item.type == ContentType.EPISODE) {
                store.rememberedItem(homeContentId(item))
                    ?: homeSeries?.items?.get(homeContentId(item))
            } else null
            val presentationItem = series ?: item
            val presentation = if (item.type == ContentType.LIVE) null else IptvMediaPresentation.cachedDetails(context, presentationItem)
            val presentationId = playbackTmdbId(item, series).takeIf { it > 0 } ?: presentation?.tmdbId ?: 0
            return Intent(context, NuvioPlayerActivity::class.java)
                .putExtra(NuvioPlayerActivity.EXTRA_STREAM_URL, stream.url)
                .putExtra(NuvioPlayerActivity.EXTRA_STREAM_HEADERS, iptvJson.encodeToString(stream.headers))
                .putExtra(NuvioPlayerActivity.EXTRA_IPTV_DNS, stream.dns?.let { iptvJson.encodeToString(it) })
                .putExtra(NuvioPlayerActivity.EXTRA_PLAYER_PACKAGE, preferred)
                .putExtra(NuvioPlayerActivity.EXTRA_CONTENT_ID, store.playbackContentId(homeContentId(item)))
                .putExtra(NuvioPlayerActivity.EXTRA_IPTV_ACCOUNT_ID, store.selectedAccount()?.id.orEmpty())
                .putExtra(NuvioPlayerActivity.EXTRA_CONTENT_TYPE, when (item.type) {
                    ContentType.LIVE -> "iptv"; ContentType.MOVIE -> "movie"; else -> "series"
                })
                .putExtra(NuvioPlayerActivity.EXTRA_TITLE, item.title)
                .putExtra(NuvioPlayerActivity.EXTRA_MEDIA_PRESENTATION,
                    if (item.type == ContentType.LIVE) "" else IptvMediaPresentation.playerPresentation(item, presentation, series).toString())
                .putExtra(NuvioPlayerActivity.EXTRA_DESCRIPTION, item.description)
                .putExtra(NuvioPlayerActivity.EXTRA_POSTER, item.artwork)
                .putExtra(NuvioPlayerActivity.EXTRA_BACKDROP, item.backdrop)
                .putExtra(NuvioPlayerActivity.EXTRA_TITLE_LOGO, if (item.type == ContentType.LIVE) "" else
                    IptvMediaPresentation.titleLogo(presentationItem, presentation,
                        com.jojotv.home.OnlineAppIconLoader.cachedTitleLogo(context,
                            if (item.type == ContentType.MOVIE) "movie" else "tv", presentationId)))
                .putExtra(NuvioPlayerActivity.EXTRA_TMDB_ID, presentationId)
                .putExtra(NuvioPlayerActivity.EXTRA_TMDB_MEDIA_TYPE, when (item.type) {
                    ContentType.MOVIE -> "movie"; ContentType.EPISODE -> "tv"; else -> ""
                })
                .putExtra(NuvioPlayerActivity.EXTRA_SEASON, item.season)
                .putExtra(NuvioPlayerActivity.EXTRA_EPISODE, item.episode)
                .putExtra(NuvioPlayerActivity.EXTRA_EPISODE_TITLE, item.title)
                .putExtra("startFromBeginning", startFromBeginning || item.type == ContentType.LIVE)
        }

        @JvmStatic @JvmOverloads fun recordHomePlayback(context: Context, id: String,
            accountId: String? = null) = synchronized(homeLibraryLock) {
            val store = homeStore(context)
            store.recordPlayback(homeItem(store, id), accountId ?: store.selectedAccount()?.id.orEmpty())
        }

        @JvmStatic fun homePlaybackAllowed(context: Context, pin: String?): Boolean {
            return homePlaybackAllowed(context, pin, null)
        }
        @JvmStatic fun homePlaybackAllowed(context: Context, pin: String?, id: String?): Boolean {
            homeSnapshot(context)
            if (!libraryLoaded) return false
            val settings = homeSettings
            val hash = settings["pinHash"].orEmpty()
            val item = id?.let { wanted ->
                val catalogId = iptvCatalogContentId(wanted)
                sharedLibrary.remembered.firstOrNull { it.id == catalogId } ?: homeSeries?.items?.get(catalogId)
            }
            val locked = IptvOrganization.playbackLocked(settings, item)
            return hash.isBlank() || !locked || pin != null && digest(settings["pinSalt"].orEmpty() + pin) == hash
        }
        @JvmStatic fun homePinRememberMillis(): Long = when (homeSettings["pinRemember"]) {
            "session" -> Long.MAX_VALUE; "15" -> 900_000L; else -> 0L
        }

        internal fun playbackResumePosition(context: Context, id: String, season: Int?, episode: Int?): Long {
            val entry = progressEntryPoint(context)
            val profile = entry.profileManager().activeProfileId.value
            return runBlocking {
                entry.watchProgressPreferences().getAllRawEntries(profile).values.filter {
                    it.contentId == id && (season == null || it.season == season) && (episode == null || it.episode == episode)
                }.maxByOrNull { it.lastWatched }?.position ?: 0L
            }
        }

        private fun progressEntryPoint(context: Context): HomeWatchProgressEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, HomeWatchProgressEntryPoint::class.java)

        /** Reset canonical progress without removing the Home favorite or its catalogue metadata. */
        @JvmStatic fun markHomeUnwatched(context: Context, id: String, profileId: Int): Boolean {
            val (item, contentId) = synchronized(homeLibraryLock) {
                val store = homeStore(context)
                val item = homeItem(store, id)
                if (item.type !in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE)) return false
                val owner = if (id.startsWith("iptv:account:")) store.playbackAccountId(id)
                    else store.library.selectedAccountId
                item to iptvPlaybackContentId(homeContentId(item), owner, store.library.legacyAccountId)
            }
            val preferences = progressEntryPoint(context).watchProgressPreferences()
            runBlocking {
                val progress = preferences.getAllRawEntries(profileId).values.toList()
                preferences.saveProgressBatch(homeUnwatchedProgress(item, contentId, progress, System.currentTimeMillis()), profileId)
            }
            return true
        }

        internal fun homeUnwatchedProgress(item: IptvItem, contentId: String, progress: List<WatchProgress>,
            resetAt: Long): List<WatchProgress> {
            val type = when (item.type) {
                ContentType.MOVIE -> "movie"
                ContentType.SERIES, ContentType.EPISODE -> "series"
                else -> return emptyList()
            }
            return progress.filter { it.contentId == contentId && it.contentType == type &&
                (item.type != ContentType.EPISODE || it.season == item.season && it.episode == item.episode) }
                .groupBy { it.storageKey }.values.map { records ->
                    val latest = records.maxByOrNull { it.lastWatched }!!
                    latest.copy(position = 0L, progressPercent = 0f, lastWatched = maxOf(resetAt, latest.lastWatched + 1))
                }
        }

        @JvmStatic fun setHomeEpisodeWatched(context: Context, id: String, watched: Boolean) {
            val store = homeStore(context)
            val item = homeItem(store, id)
            check(item.type == ContentType.EPISODE)
            store.setVodWatched(item, watched)
        }

        internal fun homeContentId(item: IptvItem): String = if (item.type == ContentType.EPISODE && item.seriesId.isNotBlank())
            itemId(item.sourceId, ContentType.SERIES, item.seriesId) else item.id

        /** IntroDB needs the series identity, never an episode's own TMDB number. */
        internal fun playbackTmdbId(item: IptvItem, series: IptvItem? = null): Int = when (item.type) {
            ContentType.EPISODE -> series?.takeIf { it.type == ContentType.SERIES &&
                it.sourceId == item.sourceId && it.id == homeContentId(item) }?.tmdbId ?: 0
            ContentType.MOVIE, ContentType.SERIES -> item.tmdbId
            ContentType.LIVE -> 0
        }.coerceAtLeast(0)

        internal fun homeFavoriteRemovalIds(item: IptvItem, remembered: List<IptvItem>): Set<String> =
            if (item.type == ContentType.SERIES) remembered.filter { homeContentId(it) == item.id }
                .mapTo(linkedSetOf(item.id)) { it.id } else setOf(item.id)

        /** Home favorites use catalogue IDs; resumes also carry the owning IPTV account. */
        @JvmStatic fun homeResumeDismissalKey(id: String): String = homeResumeDismissalKey(id, sharedLibrary)

        internal fun homeResumeDismissalKey(id: String, current: IptvLibrary): String {
            val owner = if (id.startsWith("iptv:account:")) iptvPlaybackAccount(id, current.legacyAccountId)
                else current.selectedAccountId
            val catalogId = iptvCatalogContentId(id)
            val contentId = current.remembered.firstOrNull { it.id == catalogId }?.let(::homeContentId) ?: catalogId
            val playbackId = iptvPlaybackContentId(contentId, owner, current.legacyAccountId)
            return "external-playback|$playbackId"
        }

        /** Capture even the legacy account explicitly for an asynchronous Home removal. */
        @JvmStatic fun homeRemovalContentId(id: String): String = homeRemovalContentId(id, sharedLibrary)

        internal fun homeRemovalContentId(id: String, current: IptvLibrary): String {
            if (id.startsWith("iptv:account:")) return id
            val owner = current.selectedAccountId.ifBlank { current.legacyAccountId }
            return if (owner.isBlank()) id else "iptv:account:$owner:${id.removePrefix("iptv:")}"
        }

        internal fun homeSeriesPayload(series: IptvItem, episodes: List<IptvItem>, progress: List<WatchProgress>,
            progressContentId: String = series.id): String = buildJsonObject {
            put("seasons", buildJsonArray {
                episodes.filter { it.type == ContentType.EPISODE && it.sourceId == series.sourceId && it.seriesId == series.seriesId.ifBlank { series.providerId } }.distinctBy { it.id }
                    .sortedWith(compareBy({ it.season }, { it.episode })).groupBy { it.season }.forEach { (season, items) ->
                        add(buildJsonObject {
                            put("number", season)
                            put("episodes", buildJsonArray { items.forEach { item ->
                                val saved = progress.filter { it.contentType == "series" && it.contentId == progressContentId && it.season == item.season && it.episode == item.episode }.maxByOrNull { it.lastWatched }
                                add(buildJsonObject {
                                    iptvJson.parseToJsonElement(homeMetadata(item, false)).jsonObject.forEach { (key, value) -> put(key, value) }
                                    put("title", series.title); put("episodeTitle", item.title)
                                    put("iptvSeriesId", series.id)
                                    put("artwork", item.artwork.ifBlank { series.artwork })
                                    put("backdrop", item.backdrop.ifBlank { series.backdrop })
                                    put("position", saved?.position ?: 0); put("duration", saved?.duration ?: 0)
                                    put("watched", saved?.isCompleted() == true)
                                    put("started", (saved?.position ?: 0) > 0)
                                })
                            } })
                        })
                    }
            })
        }.toString()

        @JvmStatic fun removeHomeFavorite(context: Context, id: String): Boolean = synchronized(homeLibraryLock) {
            val store = homeStore(context)
            if (id.startsWith("iptv:account:") && !store.ownsAccount(store.playbackAccountId(id))) return@synchronized false
            val item = runCatching { homeItem(store, id) }.getOrNull()
            if (item == null) {
                // A resume can outlive its catalogue metadata or an already removed favorite.
                val catalogId = iptvCatalogContentId(id)
                if (catalogId in store.library.favorites) store.commit(store.library.copy(favorites = store.library.favorites - catalogId))
                store.setHomeResumeHidden(catalogId, true)
                return@synchronized true
            }
            val favorite = if (item.type == ContentType.EPISODE && item.seriesId.isNotBlank())
                item.copy(id = homeContentId(item), providerId = item.seriesId, type = ContentType.SERIES) else item
            store.removeFavorite(favorite)
            store.library.favorites.none { it in homeFavoriteRemovalIds(favorite, store.library.remembered) }
        }

        internal fun nextEpisode(current: IptvItem, episodes: List<IptvItem>): IptvItem? {
            val series = episodes.filter { !it.scheduledEpisode && it.type == ContentType.EPISODE && it.sourceId == current.sourceId && it.seriesId == current.seriesId }
            val ordered = if (series.all { it.season > 0 && it.episode > 0 }) series.sortedWith(compareBy({ it.season }, { it.episode })) else series
            val index = ordered.indexOfFirst { it.id == current.id }
            return if (index < 0) null else ordered.getOrNull(index + 1)
        }

        internal fun homeResumeEpisode(series: IptvItem, episodes: List<IptvItem>, season: Int, episode: Int): IptvItem? =
            episodes.firstOrNull { it.type == ContentType.EPISODE && it.sourceId == series.sourceId &&
                it.seriesId == series.seriesId.ifBlank { series.providerId } && it.season == season && it.episode == episode }

        // Home receives metadata and an opaque local reference, never account URLs or headers.
        internal fun projectHomeFavorites(items: List<IptvItem>, favorites: Set<String>, sources: Set<String>): Map<String, String> {
            val remembered = items.associateBy { it.id }
            return favorites.mapNotNull { id ->
                val item = remembered[id]?.takeIf { it.sourceId in sources } ?: return@mapNotNull null
                id to homeMetadata(item, true)
            }.toMap()
        }

        private fun homeMetadata(item: IptvItem, favorite: Boolean): String {
            val category = when (item.type) {
                ContentType.MOVIE -> "movies"
                ContentType.LIVE -> "live"
                ContentType.SERIES, ContentType.EPISODE -> "series"
            }
            return buildJsonObject {
                put("id", item.id); put("category", category)
                put("favorite", favorite); put("favoriteSource", "jojo-iptv")
                put("title", item.title); put("artwork", item.artwork); put("backdrop", item.backdrop)
                put("description", item.description); put("year", item.year); put("genre", item.genre)
                put("rating", item.rating); put("cast", item.cast); put("director", item.director)
                put("providerRuntime", item.duration)
                if (item.tmdbId > 0) put("tmdbId", item.tmdbId)
                if (item.titleLogo.isNotBlank()) put("titleLogo", item.titleLogo)
                if (item.airDate.isNotBlank()) put("airDate", item.airDate)
                if (item.season > 0) put("season", item.season)
                if (item.episode > 0) put("episode", item.episode)
            }.toString()
        }
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeLibrary(target: AtomicFile, value: IptvLibrary) {
        val pending = File.createTempFile("library-", ".bin", folder)
        try {
            val expected = pending.outputStream().use { output ->
                iptvWriteEncryptedStorage(output, ::encryptBytes) { iptvJson.encodeToStream(value, it) }
            }
            // AtomicFile's output descriptor is write-only. Verify the encrypted temporary file
            // through its own input stream before starting the atomic replacement.
            pending.inputStream().use { input ->
                check(MessageDigest.isEqual(expected, iptvEncryptedStorageDigest(input, ::decryptLegacyBytes))) {
                    "Vérification du stockage IPTV impossible. Données locales conservées."
                }
            }
            val output = target.startWrite()
            try {
                pending.inputStream().use { it.copyTo(output) }
                target.finishWrite(output)
            } catch (failure: Throwable) {
                target.failWrite(output)
                throw failure
            }
        } finally { pending.delete() }
    }

    private fun encrypt(value: String): ByteArray {
        val plain = value.toByteArray(Charsets.UTF_8)
        val encrypted = iptvEncryptStorage(plain, ::encryptBytes)
        check(decryptBytes(encrypted).contentEquals(plain)) { "Vérification du stockage IPTV impossible. Données locales conservées." }
        return encrypted
    }
    private fun encryptBytes(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = iptvCipherFinal(cipher, value)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }
    private fun decrypt(value: ByteArray): String = String(decryptBytes(value), Charsets.UTF_8)
    private fun decryptBytes(value: ByteArray): ByteArray = iptvDecryptStorage(value, ::decryptLegacyBytes)
    private fun decryptLegacyBytes(value: ByteArray): ByteArray {
        val size = value.first().toInt()
        require(size == 12 && value.size > size + 1) { "Stockage IPTV endommagé." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(1, size + 1)))
        }
        return iptvCipherFinal(cipher, value, size + 1)
    }
    private fun write(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (failure: Exception) { target.failWrite(output); throw failure }
    }
}
