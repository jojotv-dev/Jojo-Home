package com.jojotv.home.iptv

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class RecordingSegment(val sequence: Long, val url: String, val duration: Double,
    val key: String = "", val iv: String = "", val init: String = "", val discontinuity: Boolean = false)
internal data class RecordingPlaylist(val variant: String, val segments: List<RecordingSegment>, val ended: Boolean)

/** A recording keeps the same destination throughout capture, recovery and playback. */
internal interface RecordingStorage : Closeable {
    val directory: String
    fun create()
    fun writable(): Boolean
    fun output(name: String): OutputStream
    fun input(name: String, offset: Long = 0): InputStream
    fun length(name: String): Long
    fun bytes(): Long
    fun freeBytes(): Long
    fun trimTransportStream()
    fun writeManifest(lines: List<String>)
    fun delete()
    fun path(name: String): String = "$directory/$name"
    fun sync(output: OutputStream) { output.flush() }
    override fun close() {}
}

internal class LocalRecordingStorage(private val folder: File) : RecordingStorage {
    override val directory: String get() = folder.canonicalPath
    override fun create() { check(folder.isDirectory || folder.mkdirs()) { "Dossier d’enregistrement inaccessible." } }
    override fun writable() = folder.isDirectory && folder.canWrite()
    override fun output(name: String): OutputStream = File(folder, name).outputStream()
    override fun input(name: String, offset: Long): InputStream = File(folder, name).inputStream().apply {
        channel.position(offset)
    }
    override fun length(name: String) = File(folder, name).takeIf { it.isFile }?.length() ?: 0L
    override fun bytes() = folder.listFiles()?.sumOf { it.length() } ?: 0L
    override fun freeBytes() = folder.usableSpace
    override fun sync(output: OutputStream) { (output as java.io.FileOutputStream).fd.sync() }
    override fun trimTransportStream() {
        java.io.RandomAccessFile(File(folder, "recording.ts"), "rw").use { it.setLength(it.length() / 188 * 188) }
    }
    override fun writeManifest(lines: List<String>) {
        val temporary = File(folder, "manifest.tmp")
        temporary.writeText(lines.joinToString("\n", postfix = "\n"))
        check(temporary.renameTo(File(folder, "recording.m3u8"))) { "Impossible d’écrire l’enregistrement." }
    }
    override fun delete() { check(!folder.exists() || folder.deleteRecursively()) { "Impossible de supprimer l’enregistrement." } }
}

/** Keep the provider sequence, not a temporary segment URL, as the duplicate key. */
internal object RecordingHlsParser {
    private val attributes = Regex("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]+))")
    fun parse(base: String, value: String): RecordingPlaylist {
        fun attrs(line: String) = attributes.findAll(line).associate { it.groupValues[1] to it.groupValues[2].ifBlank { it.groupValues[3] } }
        fun resolve(path: String) = base.toHttpUrl().resolve(path)?.toString() ?: error("Adresse HLS incompatible.")
        var sequence = 0L
        var duration = 0.0
        var key = ""
        var iv = ""
        var init = ""
        var variant = false
        var discontinuity = false
        val variants = mutableListOf<String>()
        val segments = mutableListOf<RecordingSegment>()
        value.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLong()
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    check(!attrs(line).containsKey("AUDIO")) { "Ce flux HLS utilise un son séparé. Choisis une autre qualité de la chaîne pour enregistrer." }
                    variant = true
                }
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDouble()
                line.startsWith("#EXT-X-KEY:") -> {
                    val fields = attrs(line)
                    check(fields["METHOD"] in setOf("NONE", "AES-128")) { "Ce chiffrement HLS ne permet pas l’enregistrement local." }
                    key = if (fields["METHOD"] == "NONE") "" else resolve(fields["URI"] ?: error("Clé HLS absente."))
                    iv = fields["IV"].orEmpty()
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    check(!attrs(line).containsKey("BYTERANGE")) { "Ce flux HLS utilise des plages de données non prises en charge pour l’enregistrement." }
                    init = resolve(attrs(line)["URI"] ?: error("Initialisation HLS absente."))
                }
                line.startsWith("#EXT-X-BYTERANGE") ->
                    error("Ce flux HLS utilise des plages de données non prises en charge pour l’enregistrement.")
                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                line.isNotBlank() && !line.startsWith('#') -> {
                    if (variant) { variants += resolve(line); variant = false }
                    else if (duration > 0) {
                        segments += RecordingSegment(sequence++, resolve(line), duration, key, iv, init, discontinuity)
                        duration = 0.0; discontinuity = false
                    }
                }
            }
        }
        return RecordingPlaylist(variants.firstOrNull().orEmpty(), segments, value.contains("#EXT-X-ENDLIST"))
    }
    fun decrypt(bytes: ByteArray, key: ByteArray, segment: RecordingSegment): ByteArray {
        val hex = segment.iv.removePrefix("0x").ifBlank { segment.sequence.toString(16) }.padStart(32, '0')
        require(hex.length == 32) { "Vecteur HLS incompatible." }
        val iv = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(bytes)
    }
}

/** Stream directly to disk; no account URL is written to the local manifest or logs. */
internal class IptvRecordingStream {
    private val baseClient = IptvHttp.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private var client = baseClient
    val stopped = AtomicBoolean(false)
    @Volatile private var call: Call? = null
    fun stop() { stopped.set(true); call?.cancel() }
    private fun <T> read(url: String, headers: Map<String, String>, consume: (okhttp3.Response) -> T): T {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return client.newCall(request).also { call = it }.execute().use {
            check(it.isSuccessful) { "Le fournisseur a interrompu le flux (HTTP ${it.code})." }
            consume(it)
        }
    }
    fun capture(stream: IptvStream, folder: File, end: Long, progress: (Long) -> Unit): File =
        File(capture(stream, LocalRecordingStorage(folder), end, progress))

    fun capture(stream: IptvStream, folder: RecordingStorage, end: Long, progress: (Long) -> Unit): String {
        client = IptvDns.clientFor(baseClient, stream.dns)
        folder.create()
        val headers = stream.headers
        val target = "recording.ts"
        var playlist: String? = null
        var finalUrl = stream.url
        read(stream.url, headers) { response ->
            finalUrl = response.request.url.toString()
            val input = PushbackInputStream(checkNotNull(response.body).byteStream(), 16)
            val prefix = ByteArray(16)
            var count = 0
            while (count < prefix.size) {
                val length = input.read(prefix, count, prefix.size - count)
                if (length < 0) break
                count += length
            }
            check(count > 0) { "Le fournisseur a envoyé un flux vide." }
            input.unread(prefix, 0, count)
            if (String(prefix, 0, count, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) {
                playlist = input.bufferedReader().readText()
            } else {
                check(prefix[0] == 0x47.toByte()) { "Ce flux ne fournit pas de vidéo MPEG-TS ou HLS enregistrable." }
                try { folder.output(target).use { output ->
                    val bytes = ByteArray(64 * 1024)
                    var size = 0L
                    var checked = 0L
                    while (!stopped.get() && System.currentTimeMillis() < end) {
                        val length = input.read(bytes)
                        check(length >= 0) { "Le fournisseur a fermé le flux avant la fin prévue." }
                        output.write(bytes, 0, length); size += length
                        if (checked == 0L || size - checked > 1024 * 1024) { checkSpace(folder); progress(size); checked = size }
                    }
                    folder.sync(output)
                } } finally {
                    // A timed stop may split the last 188-byte TS packet.
                    folder.trimTransportStream()
                }
            }
        }
        val initial = playlist ?: return folder.path(target)
        val manifest = "recording.m3u8"
        val lines = mutableListOf("#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-TARGETDURATION:60", "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:EVENT")
        val keys = mutableMapOf<String, ByteArray>()
        val maps = mutableMapOf<String, String>()
        var lastSequence: Long? = null
        var lastMap = ""
        var segments = 0
        var value = initial
        var url = finalUrl
        var variantDepth = 0
        while (!stopped.get() && System.currentTimeMillis() < end) {
            val parsed = RecordingHlsParser.parse(url, value)
            if (parsed.variant.isNotBlank()) {
                check(++variantDepth <= 3) { "Playlist HLS incompatible." }
                url = parsed.variant; value = read(url, headers) { checkNotNull(it.body).string() }; continue
            }
            check(parsed.segments.isNotEmpty()) { "Aucun segment vidéo dans la playlist." }
            // Start at the latest complete segment, not the beginning of the provider's live window.
            val fresh = if (lastSequence == null) parsed.segments.takeLast(1) else parsed.segments.filter { it.sequence > lastSequence!! }
            if (lastSequence != null && fresh.isNotEmpty()) check(fresh.first().sequence == lastSequence!! + 1) {
                "Le flux a perdu des segments. L’enregistrement est conservé partiellement."
            }
            for (segment in fresh) {
                if (stopped.get() || System.currentTimeMillis() >= end) break
                checkSpace(folder)
                if (segment.discontinuity) lines += "#EXT-X-DISCONTINUITY"
                if (segment.init.isNotBlank() && segment.init != lastMap) {
                    val name = maps.getOrPut(segment.init) {
                        val name = "init-${maps.size}.mp4"
                        read(segment.init, headers) { response -> folder.output(name).use { checkNotNull(response.body).byteStream().copyTo(it) } }
                        name
                    }
                    lines += "#EXT-X-MAP:URI=\"$name\""; lastMap = segment.init
                }
                val name = "part-${segments++}.${if (segment.init.isBlank()) "ts" else "m4s"}"
                read(segment.url, headers) { response ->
                    folder.output(name).use { output ->
                        if (segment.key.isBlank()) checkNotNull(response.body).byteStream().copyTo(output)
                        else {
                            val key = keys.getOrPut(segment.key) { read(segment.key, headers) { checkNotNull(it.body).bytes() } }
                            output.write(RecordingHlsParser.decrypt(checkNotNull(response.body).bytes(), key, segment))
                        }
                        folder.sync(output)
                    }
                }
                lines += "#EXTINF:${segment.duration},"; lines += name
                folder.writeManifest(lines)
                lastSequence = segment.sequence
                progress(folder.bytes())
            }
            check(!parsed.ended || System.currentTimeMillis() >= end) { "Le fournisseur a terminé le flux avant la fin prévue." }
            val wait = ((parsed.segments.last().duration * 500).toLong()).coerceIn(500, 5000)
            val until = minOf(System.currentTimeMillis() + wait, end)
            while (!stopped.get() && System.currentTimeMillis() < until) Thread.sleep(100)
            if (!stopped.get() && System.currentTimeMillis() < end) value = read(url, headers) { checkNotNull(it.body).string() }
        }
        check(folder.length(manifest) > 0) { "Aucun segment n’a pu être enregistré." }
        folder.writeManifest(lines + "#EXT-X-ENDLIST")
        return folder.path(manifest)
    }
    private fun checkSpace(folder: RecordingStorage) { check(folder.freeBytes() > 100 * 1024 * 1024) { "Stockage insuffisant : l’enregistrement a été arrêté." } }
}
