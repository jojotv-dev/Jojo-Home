package com.jojotv.home.progresspilot

import com.google.gson.Gson
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** No catalogue object, source credential or playback position belongs to this wire format. */
internal data class AccountContentValue(
    val profile: String, val kind: String, val key: String, val creator: String = "",
    val reference: String = "", val base_revision: Long = 0, val status: String = "",
    val deleted: Boolean = false, val sealed: String = ""
) {
    fun token() = "$profile/$kind/$key"
    fun validate() {
        require(accountUuid(profile) && kind in setOf("favorite", "history", "plan", "command"))
        require(if (kind in setOf("favorite", "history")) key.matches(Regex("[0-9a-f]{64}")) else accountUuid(key))
        require(base_revision >= 0)
        when (kind) {
            "favorite", "history" -> require(creator.isEmpty() && reference.isEmpty() && base_revision == 0L && status.isEmpty())
            "plan" -> require(accountUuid(creator) && reference.isEmpty() && base_revision == 0L && status.isEmpty())
            "command" -> require(accountUuid(creator) && accountUuid(reference) && base_revision > 0 &&
                status in setOf("pending", "applied", "rejected") && !deleted)
        }
        require(if (deleted) sealed.isEmpty() else sealed.length in 40..24000 && sealed.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
    }
    override fun toString() = "AccountContentValue($kind, payload=redacted)"
}
internal data class AccountContentRow(val value: AccountContentValue, val revision: Long) {
    fun validate() { value.validate(); require(revision > 0) }
}
internal data class AccountContentOp(val operation_id: String = UUID.randomUUID().toString(),
    val expected_revision: Long, val value: AccountContentValue) {
    fun validate() { require(accountUuid(operation_id) && expected_revision >= 0); value.validate() }
}
internal data class AccountContentAck(val operation_id: String, val status: String, val revision: Long)
internal data class AccountContentPage(val schema: Int, val next_cursor: Long, val head_revision: Long,
    val rows: List<AccountContentRow>)

/** Persisted before HTTP. Pending operations are immutable until their exact receipt arrives. */
internal data class AccountContentJournal(
    val cursor: Long = 0, val rows: Map<String, AccountContentRow> = emptyMap(),
    val desired: Map<String, AccountContentValue> = emptyMap(),
    val pending: List<AccountContentOp> = emptyList(), val conflicts: Set<String> = emptySet(),
    val writeBases: Map<String, Long> = emptyMap()
) {
    fun change(value: AccountContentValue): AccountContentJournal {
        value.validate(); val id = value.token()
        if (desired[id] == value || desired[id] == null && rows[id]?.value == value) return this
        require(desired.size < 2000 || id in desired) { "ACCOUNT_CONTENT_QUEUE_FULL" }
        return copy(desired = desired + (id to value), writeBases = writeBases + (id to
            (writeBases[id] ?: pending.firstOrNull { it.value.token() == id }?.expected_revision ?: rows[id]?.revision ?: 0)))
    }
    fun prepare(): AccountContentJournal {
        val ready = desired.filterKeys { it !in conflicts && pending.none { op -> op.value.token() == it } }
            .values.take((2000 - pending.size).coerceAtLeast(0))
        return copy(pending = pending + ready.map { AccountContentOp(expected_revision = writeBases[it.token()] ?: rows[it.token()]?.revision ?: 0, value = it) })
    }
    fun receive(page: AccountContentPage): AccountContentJournal {
        require(page.schema == 1 && page.next_cursor >= cursor && page.head_revision >= page.next_cursor && page.rows.size <= 200)
        require(page.rows.map { it.value.token() }.distinct().size == page.rows.size)
        val next = rows.toMutableMap()
        page.rows.forEach { row ->
            row.validate(); require(row.revision > cursor && row.revision <= page.next_cursor)
            val old = next[row.value.token()]
            require(old == null || row.revision > old.revision || row == old)
            next[row.value.token()] = row
        }
        require(next.size <= 10000) { "ACCOUNT_CONTENT_CACHE_FULL" }
        return copy(cursor = page.next_cursor, rows = next)
    }
    fun acknowledge(sent: List<AccountContentOp>, acks: List<AccountContentAck>): AccountContentJournal {
        require(acks.size == sent.size && acks.map { it.operation_id }.toSet() == sent.map { it.operation_id }.toSet())
        var next = this
        acks.forEach { ack ->
            require(ack.status in setOf("applied", "conflict") && ack.revision >= 0)
            val op = sent.single { it.operation_id == ack.operation_id }
            // A repeated/obsolete response cannot consume newer work.
            if (next.pending.none { it == op }) return@forEach
            val id = op.value.token()
            val current = next.rows[id]
            require(ack.status != "applied" || ack.revision > op.expected_revision)
            next = next.copy(pending = next.pending.filterNot { it.operation_id == op.operation_id },
                desired = if (ack.status == "applied" && next.desired[id] == op.value) next.desired - id else next.desired,
                conflicts = if (ack.status == "conflict") next.conflicts + id else next.conflicts,
                writeBases = if (ack.status != "applied") next.writeBases else if (next.desired[id] == op.value)
                    next.writeBases - id else next.writeBases + (id to ack.revision),
                rows = if (ack.status == "applied" && (current?.revision ?: 0) < ack.revision)
                    next.rows + (id to AccountContentRow(op.value, ack.revision)) else next.rows)
        }
        return next
    }
    fun resolve(id: String, publishLocal: Boolean): AccountContentJournal {
        require(id in conflicts)
        return copy(conflicts = conflicts - id, desired = if (publishLocal) desired else desired - id,
            writeBases = if (publishLocal) writeBases + (id to (rows[id]?.revision ?: 0)) else writeBases - id)
    }
    fun protected(id: String) = id in desired || id in conflicts || pending.any { it.value.token() == id }
}

internal data class AccountContentState(val selected: Boolean = false, val generation: String = UUID.randomUUID().toString(),
    val keyTag: String = "", val initialized: Boolean = false,
    val observed: Map<String, String> = emptyMap(), val journal: AccountContentJournal = AccountContentJournal(),
    val issue: String? = null)

/** Independent HMAC domains; AES-256-GCM, random 96-bit IV, 128-bit tag and authenticated row identity. */
internal class AccountContentCrypto(secret: ByteArray, private val owner: String) : AutoCloseable {
    private val key = secret.copyOf()
    init { require(secret.size == 32 && accountUuid(owner)) }
    private fun mac(vararg parts: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(parts.fold(ByteArray(0)) { acc, text -> val bytes = text.toByteArray(Charsets.UTF_8)
            acc + ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes })
    }
    fun id(profile: String, item: String): String {
        require(accountUuid(profile) && item.length in 1..600)
        return mac("jojo-account-content:id:v1", owner, profile, item).contentHex()
    }
    fun keyTag() = mac("jojo-account-content:key-check:v1", owner).contentHex()
    fun fingerprint(clear: String) = mac("jojo-account-content:local-value:v1", clear).contentHex()
    fun netflixId(profile: String): String {
        require(profile.isNotBlank())
        return mac("jojo-account-netflix:id:v1", owner, AccountNetflixObservation.profileKey(profile)).contentHex()
    }
    private fun netflixAad(row: AccountNetflixValue) = listOf("jojo-account-netflix:seal:v1", owner,
        row.key, row.stamp.toString(), row.writer).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    fun sealNetflix(row: AccountNetflixValue, observation: AccountNetflixSnapshot): AccountNetflixValue {
        observation.validate()
        require(row.key == netflixId(observation.profile))
        val bytes = observation.encode().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        val aes = mac("jojo-account-netflix:aes:v1", owner)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(netflixAad(row))
            return row.copy(sealed = Base64.getEncoder().encodeToString(iv + cipher.doFinal(bytes))).also { it.validate() }
        } finally { aes.fill(0); bytes.fill(0) }
    }
    fun openNetflix(row: AccountNetflixValue): AccountNetflixSnapshot {
        row.validate()
        val bytes = Base64.getDecoder().decode(row.sealed)
        require(bytes.size in 29..65564)
        val aes = mac("jojo-account-netflix:aes:v1", owner)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(netflixAad(row))
            val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
            return try { AccountNetflixSnapshot.decode(String(clear, Charsets.UTF_8)).also {
                require(row.key == netflixId(it.profile))
            } } finally { clear.fill(0) }
        } finally { aes.fill(0); bytes.fill(0) }
    }
    private fun aad(row: AccountContentValue) = listOf("jojo-account-content:seal:v1", owner, row.profile, row.kind,
        row.key, row.creator, row.reference, row.base_revision.toString()).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    fun seal(row: AccountContentValue, clear: String): AccountContentValue {
        require(!row.deleted && clear.toByteArray(Charsets.UTF_8).size <= 16000)
        val aes = mac("jojo-account-content:aes:v1", owner)
        val bytes = clear.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(aad(row))
            return row.copy(sealed = Base64.getEncoder().encodeToString(iv + cipher.doFinal(bytes))).also { it.validate() }
        } finally { aes.fill(0); bytes.fill(0) }
    }
    fun open(row: AccountContentValue): String {
        row.validate(); require(!row.deleted)
        val aes = mac("jojo-account-content:aes:v1", owner)
        val bytes = Base64.getDecoder().decode(row.sealed)
        require(bytes.size in 29..18000)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad(row)); val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
            return try { String(clear, Charsets.UTF_8) } finally { clear.fill(0) }
        } finally { aes.fill(0); bytes.fill(0) }
    }
    override fun close() { key.fill(0) }
    override fun toString() = "AccountContentCrypto(key=redacted)"
}
private fun ByteArray.contentHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal object AccountContentProtocol {
    private val gson = Gson()
    fun page(body: String) = gson.fromJson(body, AccountContentPage::class.java).also {
        require(it.schema == 1 && it.rows != null && it.next_cursor >= 0 && it.head_revision >= it.next_cursor)
        it.rows.forEach(AccountContentRow::validate)
    }
    fun acks(body: String): List<AccountContentAck> = gson.fromJson(body, Array<AccountContentAck>::class.java).toList()
}

/** Netflix's published profile, never the active Jojo Home profile or an arbitrary Android intent. */
internal data class AccountNetflixObservation(val profile: String, val content: String, val title: String,
    val description: String = "", val season: String = "", val episode: String = "",
    val duration: Long = -1, val position: Long = -1) {
    fun validate() {
        fun text(value: String, min: Int, max: Int) = value.length in min..max && value == value.trim() &&
            value.none { it.isISOControl() } && !value.contains(Regex("(?i)(?:https?://|intent:|content://|file://)"))
        require(text(profile, 1, 80) && text(title, 1, 512) && text(description, 0, 1024))
        require(content.matches(Regex("[1-9][0-9]{0,19}")))
        require(listOf(season, episode).all { it.isEmpty() || it.matches(Regex("[0-9]{1,4}")) })
        require(duration in -1..2_678_400_000L && position in -1..2_678_400_000L)
        require(duration != 0L && (duration < 0 || position <= duration))
    }
    fun encode() = com.google.gson.JsonObject().apply {
        addProperty("profile", profile); addProperty("content", content); addProperty("title", title)
        addProperty("description", description); addProperty("season", season); addProperty("episode", episode)
        addProperty("duration", duration); addProperty("position", position)
    }.toString()
    /** Missing metadata does not erase an identified episode; another episode never inherits its timing. */
    fun retainMissing(previous: AccountNetflixObservation?): AccountNetflixObservation {
        if (previous == null || profileKey(profile) != profileKey(previous.profile) || content != previous.content) return this
        val sameEpisode = title == previous.title && (description.isEmpty() || description == previous.description) &&
            (season.isEmpty() || season == previous.season) && (episode.isEmpty() || episode == previous.episode)
        return if (!sameEpisode) this else copy(description = description.ifEmpty { previous.description },
            season = season.ifEmpty { previous.season }, episode = episode.ifEmpty { previous.episode },
            duration = duration.takeIf { it > 0 } ?: previous.duration,
            position = position.takeIf { it >= 0 } ?: previous.position).also { it.validate() }
    }
    override fun toString() = "AccountNetflixObservation(data=redacted)"
    companion object {
        fun profileKey(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        fun decode(raw: String): AccountNetflixObservation {
            require(raw.toByteArray(Charsets.UTF_8).size <= 4096)
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            require(obj.keySet() == setOf("profile", "content", "title", "description", "season", "episode", "duration", "position"))
            listOf("profile", "content", "title", "description", "season", "episode").forEach {
                require(obj[it].isJsonPrimitive && obj[it].asJsonPrimitive.isString)
            }
            listOf("duration", "position").forEach { require(obj[it].isJsonPrimitive && obj[it].asJsonPrimitive.isNumber &&
                obj[it].toString().matches(Regex("-?[0-9]+")) && obj[it].asString.toLongOrNull() != null) }
            return AccountNetflixObservation(obj["profile"].asString, obj["content"].asString, obj["title"].asString,
                obj["description"].asString, obj["season"].asString, obj["episode"].asString,
                obj["duration"].asLong, obj["position"].asLong).also { it.validate() }
        }
        fun snapshot(raw: String): List<AccountNetflixObservation> {
            require(raw.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
            val array = com.google.gson.JsonParser.parseString(raw).asJsonArray
            require(array.size() <= 100)
            return array.map { decode(it.toString()) }.also { values ->
                require(values.map { profileKey(it.profile) to it.content }.distinct().size == values.size)
            }
        }
    }
}

/** One complete currently published list per Netflix profile; episode IDs may change between devices. */
internal data class AccountNetflixSnapshot(val profile: String, val cards: List<AccountNetflixObservation>) {
    fun validate() {
        require(profile.isNotBlank() && profile.length <= 80 && cards.size in 1..20)
        cards.forEach { it.validate(); require(AccountNetflixObservation.profileKey(it.profile) == AccountNetflixObservation.profileKey(profile)) }
        require(cards.map { it.content }.distinct().size == cards.size)
    }
    fun encode() = com.google.gson.JsonObject().apply {
        addProperty("profile", profile)
        add("cards", com.google.gson.JsonArray().apply { cards.forEach { add(com.google.gson.JsonParser.parseString(it.encode())) } })
    }.toString()
    fun retainMissing(previous: AccountNetflixSnapshot?): AccountNetflixSnapshot = copy(cards = cards.map { card ->
        card.retainMissing(previous?.takeIf { AccountNetflixObservation.profileKey(it.profile) ==
            AccountNetflixObservation.profileKey(profile) }?.cards?.firstOrNull { it.content == card.content })
    })
    override fun toString() = "AccountNetflixSnapshot(data=redacted)"
    companion object {
        fun decode(raw: String): AccountNetflixSnapshot {
            require(raw.toByteArray(Charsets.UTF_8).size <= 65536)
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            require(obj.keySet() == setOf("profile", "cards") && obj["profile"].isJsonPrimitive && obj["profile"].asJsonPrimitive.isString &&
                obj["cards"].isJsonArray && obj["cards"].asJsonArray.size() in 1..20)
            return AccountNetflixSnapshot(obj["profile"].asString, obj["cards"].asJsonArray.map {
                AccountNetflixObservation.decode(it.toString())
            }).also { it.validate() }
        }
    }
}

internal data class AccountNetflixValue(val key: String, val stamp: Long, val writer: String, val sealed: String = "") {
    fun validate() {
        require(key.matches(Regex("[0-9a-f]{64}")) && stamp in 1..253402300799999L && accountUuid(writer))
        require(sealed.length in 40..87420 && sealed.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
    }
    override fun toString() = "AccountNetflixValue(payload=redacted)"
}
internal data class AccountNetflixRow(val value: AccountNetflixValue, val revision: Long)
internal data class AccountNetflixPage(val cursor: Long, val head: Long, val rows: List<AccountNetflixRow>)

/** Stored inside settings.bin: ciphertext, native baselines and immutable retry values survive process death. */
internal data class AccountNetflixState(val owner: String = "", val keyTag: String = "", val cursor: Long = 0,
    val rows: Map<String, AccountNetflixValue> = emptyMap(), val pending: Map<String, AccountNetflixValue> = emptyMap(),
    val observed: Map<String, AccountNetflixSnapshot> = emptyMap()) {
    fun validate() {
        require(owner.isEmpty() || accountUuid(owner))
        require(keyTag.isEmpty() || keyTag.matches(Regex("[0-9a-f]{64}")))
        require(cursor >= 0 && rows.size <= 100 && pending.size <= 100 && observed.size <= 100)
        (rows + pending).forEach { (key, value) -> value.validate(); require(key == value.key) }
        observed.forEach { (key, value) -> require(key.matches(Regex("[0-9a-f]{64}"))); value.validate() }
    }
    fun capture(values: List<AccountNetflixObservation>, crypto: AccountContentCrypto, writer: String, now: Long): AccountNetflixState {
        require(accountUuid(writer) && now in 1..253402300799998L)
        var next = this
        values.groupBy { AccountNetflixObservation.profileKey(it.profile) }.values.forEach { cards ->
            val observed = AccountNetflixSnapshot(cards.first().profile, cards.sortedBy { it.content })
            observed.validate()
            val key = crypto.netflixId(observed.profile)
            val baseline = next.observed[key]
            val candidate = observed.retainMissing(baseline)
            if (candidate == baseline) return@forEach
            val known = next.pending[key] ?: next.rows[key]
            // An upgraded/restarted TV's first local card cannot overwrite a newer remote episode.
            if (baseline != null || known == null) {
                val value = crypto.sealNetflix(AccountNetflixValue(key, maxOf(now, (known?.stamp ?: 0) + 1), writer), candidate)
                next = next.copy(pending = next.pending + (key to value))
            }
            next = next.copy(observed = next.observed + (key to candidate))
        }
        return next.also { it.validate() }
    }
    fun receive(sent: List<AccountNetflixValue>, page: AccountNetflixPage, crypto: AccountContentCrypto): AccountNetflixState {
        require(page.cursor >= cursor && page.head >= page.cursor && page.rows.size <= 20)
        val result = rows.toMutableMap()
        (sent + page.rows.map { it.value }).forEach { value ->
            crypto.openNetflix(value)
            val previous = result[value.key]
            if (previous == null || compareValuesBy(value, previous, { it.stamp }, { it.writer }) > 0) result[value.key] = value
            else if (value.stamp == previous.stamp && value.writer == previous.writer) require(value == previous)
        }
        return copy(cursor = page.cursor, rows = result, pending = pending.filterNot { (key, value) ->
            sent.any { it.key == key && it == value }
        }).also { it.validate() }
    }
    fun view(crypto: AccountContentCrypto): List<Pair<AccountNetflixValue, AccountNetflixSnapshot>> = (rows.keys + pending.keys).map { key ->
        val remote = rows[key]; val local = pending[key]
        val value = if (local == null || remote != null && compareValuesBy(remote, local, { it.stamp }, { it.writer }) > 0) remote!! else local
        value to crypto.openNetflix(value)
    }.sortedWith(compareByDescending<Pair<AccountNetflixValue, AccountNetflixSnapshot>> { it.first.stamp }.thenBy { it.first.key })
}

internal fun accountNetflixPage(raw: String, after: Long): AccountNetflixPage {
    require(after >= 0 && raw.length <= 2 * 1024 * 1024)
    val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
    fun integer(key: String) = obj[key].isJsonPrimitive && obj[key].asJsonPrimitive.isNumber &&
        obj[key].toString().matches(Regex("[0-9]+")) && obj[key].asString.toLongOrNull() != null
    require(obj.keySet() == setOf("schema", "next_cursor", "head_revision", "rows") && integer("schema") && obj["schema"].asInt == 1 &&
        integer("next_cursor") && integer("head_revision") && obj["rows"].isJsonArray && obj["rows"].asJsonArray.size() <= 20)
    val cursor = obj["next_cursor"].asLong; val head = obj["head_revision"].asLong
    require(cursor in after..head)
    val rows = obj["rows"].asJsonArray.map { json ->
        val row = json.asJsonObject
        require(row.keySet() == setOf("key", "stamp", "writer", "sealed", "revision"))
        listOf("key", "writer", "sealed").forEach { require(row[it].isJsonPrimitive && row[it].asJsonPrimitive.isString) }
        listOf("stamp", "revision").forEach { require(row[it].isJsonPrimitive && row[it].asJsonPrimitive.isNumber &&
            row[it].toString().matches(Regex("[0-9]+")) && row[it].asString.toLongOrNull() != null) }
        AccountNetflixRow(AccountNetflixValue(row["key"].asString, row["stamp"].asLong, row["writer"].asString,
            row["sealed"].asString).also { it.validate() }, row["revision"].asLong).also { require(it.revision > after && it.revision <= cursor) }
    }
    require(rows.map { it.value.key }.distinct().size == rows.size && rows.zipWithNext().all { (a, b) -> a.revision < b.revision })
    require(if (rows.isEmpty()) cursor == after && head == after else cursor == rows.last().revision)
    return AccountNetflixPage(cursor, head, rows)
}
