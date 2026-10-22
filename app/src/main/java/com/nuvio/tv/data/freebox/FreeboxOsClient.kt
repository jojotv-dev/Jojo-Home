package com.nuvio.tv.data.freebox



import android.util.Base64
import android.util.Log

import com.nuvio.tv.data.local.FreeboxAuthUpdate

import com.nuvio.tv.data.local.FreeboxConnectionSettings

import com.nuvio.tv.data.local.FreeboxFolderUpdate

import com.nuvio.tv.data.local.FreeboxSessionUpdate

import javax.crypto.Mac

import javax.crypto.spec.SecretKeySpec

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

import okhttp3.MediaType.Companion.toMediaType

import okhttp3.OkHttpClient

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

import okhttp3.RequestBody.Companion.toRequestBody

import org.json.JSONArray

import org.json.JSONObject

import java.io.File
import okio.ByteString.Companion.toByteString
import java.time.Instant

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap



data class FreeboxFileEntry(

    val name: String,

    val path: String,

    val encodedPath: String? = null,

    val isDirectory: Boolean,

    val size: Long? = null,

    val durationMs: Long? = null,

    val modifiedMs: Long? = null

)

data class FreeboxStorageInfo(
    val totalBytes: Long?,
    val availableBytes: Long?
)

data class FreeboxEpgSnapshot(
    val channels: List<FreeboxEpgChannel>,
    val programmes: List<FreeboxEpgProgram>
)



@Singleton

class FreeboxOsClient @Inject constructor(

    private val httpClient: OkHttpClient

) {
    private val epgProgrammeDetailsCache = ConcurrentHashMap<String, FreeboxEpgProgram>()
    private val epgByTimeCache = ConcurrentHashMap<Long, FreeboxEpgSnapshot>()
    private val epgRequestMutex = Mutex()
    private var lastEpgRequestAtMs: Long = 0L

    suspend fun requestAuthorization(settings: FreeboxConnectionSettings): Result<FreeboxAuthUpdate> = withContext(Dispatchers.IO) {

        runCatching {

            val body = JSONObject()

                .put("app_id", APP_ID)

                .put("app_name", APP_NAME)

                .put("app_version", APP_VERSION)

                .put("device_name", settings.username.ifBlank { "Jojo Home" })

                .toString()

                .toRequestBody(JSON_MEDIA_TYPE)

            val json = executeJson(

                Request.Builder()

                    .url(apiUrl(settings.address, "login/authorize/"))

                    .post(body)

                    .build()

            )

            val result = json.getJSONObject("result")

            FreeboxAuthUpdate(

                appToken = result.getString("app_token"),

                trackId = result.getInt("track_id"),

                status = "pending"

            )

        }

    }



    suspend fun refreshAuthorizationStatus(settings: FreeboxConnectionSettings): Result<String> = withContext(Dispatchers.IO) {

        runCatching {

            require(settings.authTrackId >= 0) { "Aucune demande d'autorisation Freebox en cours." }

            val json = executeJson(

                Request.Builder()

                    .url(apiUrl(settings.address, "login/authorize/${settings.authTrackId}"))

                    .get()

                    .build()

            )

            json.getJSONObject("result").getString("status")

        }

    }



    suspend fun openSession(settings: FreeboxConnectionSettings): Result<FreeboxSessionUpdate> = withContext(Dispatchers.IO) {

        runCatching {

            require(settings.appToken.isNotBlank()) { "Autorisez d'abord Jojo Home sur la Freebox." }

            val challenge = executeJson(

                Request.Builder()

                    .url(apiUrl(settings.address, "login/"))

                    .get()

                    .build()

            ).getJSONObject("result").getString("challenge")

            val password = hmacSha1Hex(settings.appToken, challenge)

            val body = JSONObject()

                .put("app_id", APP_ID)

                .put("app_version", APP_VERSION)

                .put("password", password)

                .toString()

                .toRequestBody(JSON_MEDIA_TYPE)

            val json = executeJson(

                Request.Builder()

                    .url(apiUrl(settings.address, "login/session/"))

                    .post(body)

                    .build()

            )

            val result = json.getJSONObject("result")

            FreeboxSessionUpdate(

                sessionToken = result.getString("session_token"),

                status = "connected"

            )

        }

    }



    suspend fun loadRootFolders(settings: FreeboxConnectionSettings): Result<FreeboxFolderUpdate> = withContext(Dispatchers.IO) {

        runCatching {

            val sessionToken = settings.sessionToken.ifBlank {

                openSession(settings).getOrThrow().sessionToken

            }

            val folders = listDirectory(settings.address, sessionToken, FREEBOX_ROOT)

                .filter { it.isDirectory }

                .map { it.name }

                .toSet()

            val visible = if (settings.serverFoldersConfigured) {

                settings.visibleServerFolders.intersect(folders)

            } else {

                folders

            }

            FreeboxFolderUpdate(

                folders = folders,

                visibleFolders = visible,

                sidebarFolders = settings.sidebarServerFolders.intersect(folders)

            )

        }

    }



    suspend fun listDirectory(settings: FreeboxConnectionSettings, path: String): Result<List<FreeboxFileEntry>> = withContext(Dispatchers.IO) {

        runCatching {

            val sessionToken = settings.sessionToken.ifBlank {

                openSession(settings).getOrThrow().sessionToken

            }

            listDirectory(settings.address, sessionToken, path)

        }

    }

    suspend fun storageInfo(settings: FreeboxConnectionSettings): Result<FreeboxStorageInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = settings.sessionToken.ifBlank {
                openSession(settings).getOrThrow().sessionToken
            }
            storageInfo(settings.address, sessionToken)
        }
    }

    suspend fun loadTvGuide(settings: FreeboxConnectionSettings): Result<FreeboxEpgSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.hasSavedConnection) { "Connexion Freebox non configuree." }
            val sessionToken = settings.sessionToken.ifBlank {
                openSession(settings).getOrThrow().sessionToken
            }
            executeEpgRequest { tvGuide(settings.address, sessionToken) }
        }
    }

    /**
     * Loads the programme grid at a precise instant. The native Freebox EPG API is
     * time-based, which is essential for enriching recordings that are no longer
     * in the current guide window.
     */
    suspend fun loadTvGuideAt(
        settings: FreeboxConnectionSettings,
        instantMs: Long
    ): Result<FreeboxEpgSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.hasSavedConnection) { "Connexion Freebox non configuree." }
            val rangeStartMs = instantMs - instantMs.mod(EPG_BY_TIME_RANGE_MS)
            epgByTimeCache[rangeStartMs]?.let { return@runCatching it }
            val sessionToken = settings.sessionToken.ifBlank {
                openSession(settings).getOrThrow().sessionToken
            }
            epgRequestMutex.withLock {
                epgByTimeCache[rangeStartMs]?.let { return@withLock it }
                executeEpgRequestLocked { tvGuideAt(settings.address, sessionToken, rangeStartMs) }
                    .also { snapshot -> epgByTimeCache[rangeStartMs] = snapshot }
            }
        }
    }

    /**
     * The Freebox by_time endpoint only returns compact programme rows. The
     * official Freebox OS UI loads synopsis and artwork from programs/{id}.
     * Cache those immutable broadcast details to avoid hitting the EPG quota
     * again when Home recomposes or reloads the Videos row.
     */
    suspend fun loadTvProgrammeDetails(
        settings: FreeboxConnectionSettings,
        programme: FreeboxEpgProgram
    ): Result<FreeboxEpgProgram> = withContext(Dispatchers.IO) {
        runCatching {
            val programmeId = programme.externalId?.takeIf { it.isNotBlank() }
                ?: return@runCatching programme
            epgProgrammeDetailsCache[programmeId]?.let { cached ->
                return@runCatching cached.copy(
                    channelId = programme.channelId,
                    startTime = programme.startTime,
                    endTime = programme.endTime
                )
            }
            val sessionToken = settings.sessionToken.ifBlank {
                openSession(settings).getOrThrow().sessionToken
            }
            val detailed = executeEpgRequest {
                tvProgrammeDetails(
                    address = settings.address,
                    sessionToken = sessionToken,
                    programme = programme
                )
            }
            epgProgrammeDetailsCache[programmeId] = detailed
            detailed
        }
    }




    suspend fun probeDurationMs(
        settings: FreeboxConnectionSettings,
        entry: FreeboxFileEntry
    ): Long? = withContext(Dispatchers.IO) {
        var retriever: android.media.MediaMetadataRetriever? = null
        var extractor: android.media.MediaExtractor? = null
        try {
            val sessionToken = settings.sessionToken.ifBlank {
                openSession(settings).getOrThrow().sessionToken
            }
            val url = downloadUrl(settings.copy(sessionToken = sessionToken), entry.path, entry.encodedPath)
            val headers = sessionHeaders(sessionToken)

            retriever = android.media.MediaMetadataRetriever()
            val retrieverDuration = runCatching {
                retriever.setDataSource(url, headers)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            }.getOrNull()
            if (retrieverDuration != null) return@withContext retrieverDuration

            extractor = android.media.MediaExtractor()
            extractor.setDataSource(url, headers)
            val extractorDurationMs = (0 until extractor.trackCount)
                .mapNotNull { trackIndex ->
                    val format = extractor.getTrackFormat(trackIndex)
                    if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        format.getLong(android.media.MediaFormat.KEY_DURATION)
                            .takeIf { it > 0L }
                            ?.div(1000L)
                    } else {
                        null
                    }
                }
                .maxOrNull()
            if (extractorDurationMs == null) {
                Log.w(TAG, "Duree Freebox absente des metadonnees: ${entry.path}")
            }
            extractorDurationMs
        } catch (error: Exception) {
            Log.w(TAG, "Echec du sondage de duree Freebox: ${entry.path}", error)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
    fun downloadUrl(settings: FreeboxConnectionSettings, path: String, encodedPath: String? = null): String =

        apiUrl(settings.address, "dl/" + (encodedPath?.let(::encodePathSegment) ?: encodeFreeboxPath(path)))

    /** Checks the exact video endpoint with a one-byte request before ExoPlayer opens it. */
    suspend fun canReadDownload(
        settings: FreeboxConnectionSettings,
        sessionToken: String,
        path: String,
        encodedPath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(downloadUrl(settings.copy(sessionToken = sessionToken), path, encodedPath))
                .header("Range", "bytes=0-0")
                .get()
                .apply {
                    sessionHeaders(sessionToken).forEach { (name, value) -> header(name, value) }
                }
                .build()
            httpClient.newCall(request).execute().use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Serves one random-access chunk to the FFmpeg thumbnail extractor.
     * Freebox recordings can be several gigabytes, so copying the complete
     * file locally before extracting five frames is not acceptable.
     */
    internal fun readDownloadRange(
        settings: FreeboxConnectionSettings,
        sessionToken: String,
        path: String,
        encodedPath: String?,
        offset: Long,
        requestedSize: Int,
        destination: ByteArray
    ): Int {
        if (requestedSize <= 0) return 0
        val endInclusive = offset + requestedSize - 1L
        val request = Request.Builder()
            .url(downloadUrl(settings.copy(sessionToken = sessionToken), path, encodedPath))
            .header("Range", "bytes=$offset-$endInclusive")
            .get()
            .apply {
                sessionHeaders(sessionToken).forEach { (name, value) -> header(name, value) }
            }
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.code == 416) return@use 0
            check(response.isSuccessful) { "Lecture Freebox impossible: HTTP ${response.code}" }
            check(offset == 0L || response.code == 206) {
                "La Freebox n'a pas honore la plage demandee a l'offset $offset."
            }
            val stream = checkNotNull(response.body) { "Reponse Freebox sans contenu." }.byteStream()
            var total = 0
            while (total < requestedSize) {
                val read = stream.read(destination, total, requestedSize - total)
                if (read < 0) break
                total += read
            }
            total
        }
    }


    suspend fun rename(settings: FreeboxConnectionSettings, path: String, encodedPath: String?, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = settings.sessionToken.ifBlank { openSession(settings).getOrThrow().sessionToken }
            val body = JSONObject()
                .put("src", freeboxApiPath(path, encodedPath))
                .put("dst", newName)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            executeJson(
                Request.Builder()
                    .url(apiUrl(settings.address, "fs/rename/"))
                    .header("X-Fbx-App-Auth", sessionToken)
                    .post(body)
                    .build()
            )
            Unit
        }
    }

    suspend fun deleteFiles(settings: FreeboxConnectionSettings, files: List<FreeboxFileEntry>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = settings.sessionToken.ifBlank { openSession(settings).getOrThrow().sessionToken }
            val body = JSONObject()
                .put("files", JSONArray(files.map { freeboxApiPath(it.path, it.encodedPath) }))
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            executeJson(
                Request.Builder()
                    .url(apiUrl(settings.address, "fs/rm/"))
                    .header("X-Fbx-App-Auth", sessionToken)
                    .post(body)
                    .build()
            )
            Unit
        }
    }

    suspend fun copyFiles(settings: FreeboxConnectionSettings, files: List<FreeboxFileEntry>, destinationPath: String): Result<Unit> =
        runFileTask(settings, "fs/cp/", files, destinationPath)

    suspend fun moveFiles(settings: FreeboxConnectionSettings, files: List<FreeboxFileEntry>, destinationPath: String): Result<Unit> =
        runFileTask(settings, "fs/mv/", files, destinationPath)

    private suspend fun runFileTask(
        settings: FreeboxConnectionSettings,
        endpoint: String,
        files: List<FreeboxFileEntry>,
        destinationPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionToken = settings.sessionToken.ifBlank { openSession(settings).getOrThrow().sessionToken }
            val body = JSONObject()
                .put("files", JSONArray(files.map { freeboxApiPath(it.path, it.encodedPath) }))
                .put("dst", freeboxApiPath(destinationPath, null))
                .put("mode", "both")
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            executeJson(
                Request.Builder()
                    .url(apiUrl(settings.address, endpoint))
                    .header("X-Fbx-App-Auth", sessionToken)
                    .post(body)
                    .build()
            )
            Unit
        }
    }



    fun sessionHeaders(sessionToken: String): Map<String, String> =

        mapOf(

            "X-Fbx-App-Auth" to sessionToken,

            "Cookie" to "FreeboxOS=$sessionToken"

        )



    suspend fun downloadToFile(

        settings: FreeboxConnectionSettings,

        sessionToken: String,

        path: String,

        encodedPath: String?,

        destination: File

    ): Result<File> = withContext(Dispatchers.IO) {

        runCatching {

            destination.parentFile?.mkdirs()

            val requestBuilder = Request.Builder()

                .url(downloadUrl(settings.copy(sessionToken = sessionToken), path, encodedPath))

                .get()

            sessionHeaders(sessionToken).forEach { (name, value) ->

                requestBuilder.header(name, value)

            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->

                if (!response.isSuccessful) {

                    error("Freebox HTTP ${response.code}: ${response.message}")

                }

                response.body?.byteStream()?.use { input ->

                    destination.outputStream().use { output -> input.copyTo(output) }

                }

            }

            destination

        }

    }



    /** Sends a completed local capture using Freebox OS v4 WebSocket upload.
     * A name conflict is reported; it is never overwritten silently. */
    suspend fun uploadFile(settings: FreeboxConnectionSettings, source: File, destinationDirectory: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(source.isFile && source.length() > 0L) { "Fichier local introuvable." }
            val token = settings.sessionToken.ifBlank { openSession(settings).getOrThrow().sessionToken }
            val opened = Channel<Unit>(1)
            val messages = Channel<JSONObject>(Channel.UNLIMITED)
            val failures = Channel<Throwable>(1)
            val url = normalizeBaseUrl(settings.address)
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/api/v4/ws/upload"
            val socket = httpClient.newWebSocket(Request.Builder().url(url).header("X-Fbx-App-Auth", token).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.trySend(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) { runCatching { JSONObject(text) }.getOrNull()?.let { messages.trySend(it) } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failures.trySend(t) }
            })
            try {
                withTimeout(15_000L) { opened.receive() }
                val requestId = (System.nanoTime() and Int.MAX_VALUE.toLong()).toInt()
                val start = JSONObject().put("action", "upload_start").put("request_id", requestId)
                    .put("size", source.length()).put("dirname", Base64.encodeToString(normalizeFreeboxPath(destinationDirectory).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    .put("filename", source.name).put("force", "missing")
                check(socket.send(start.toString())) { "Connexion Freebox indisponible." }
                awaitUploadResponse(messages, failures, requestId, "upload_start")
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) { val count = input.read(buffer); if (count <= 0) break; check(socket.send(buffer.toByteString(0, count))) { "Upload interrompu." } }
                }
                check(socket.send(JSONObject().put("action", "upload_finalize").put("request_id", requestId).toString())) { "Finalisation impossible." }
                awaitUploadResponse(messages, failures, requestId, "upload_finalize")
            } finally { socket.close(1000, "done"); opened.close(); messages.close(); failures.close() }
        }
    }

    /** Confirms once against the authoritative parent directory that the exact path is absent. */
    suspend fun confirmFileAbsent(
        settings: FreeboxConnectionSettings,
        path: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val target = normalizeFreeboxPath(path)
            val parent = target.substringBeforeLast('/', missingDelimiterValue = FREEBOX_ROOT)
                .ifBlank { FREEBOX_ROOT }
            val entries = listDirectory(settings, parent).getOrThrow()
            val stillPresent = entries.any { entry ->
                normalizeFreeboxPath(entry.path) == target
            }
            !stillPresent
        }
    }

    private suspend fun awaitUploadResponse(messages: Channel<JSONObject>, failures: Channel<Throwable>, requestId: Int, action: String) {
        withTimeout(45_000L) {
            while (true) {
                val message = select<JSONObject> { messages.onReceive { it }; failures.onReceive { throw it } }
                if (message.optInt("request_id", -1) != requestId || message.optString("action") != action) continue
                if (!message.optBoolean("success", false)) error("Upload Freebox refuse: ${message.optString("error_code", message.optString("msg"))}")
                return@withTimeout
            }
        }
    }
    private fun listDirectory(address: String, sessionToken: String, path: String): List<FreeboxFileEntry> {

        val normalizedPath = normalizeFreeboxPath(path)

        val json = executeJson(

            Request.Builder()

                .url(apiUrl(address, "fs/ls/${encodeFreeboxPath(normalizedPath)}"))

                .header("X-Fbx-App-Auth", sessionToken)

                .get()

                .build()

        )

        return parseEntries(json.opt("result"), normalizedPath)

            .sortedWith(compareByDescending<FreeboxFileEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    }

    private fun storageInfo(address: String, sessionToken: String): FreeboxStorageInfo {
        val endpoints = listOf("storage/partition/", "storage/disk/")
        endpoints.forEach { endpoint ->
            val json = runCatching {
                executeJson(
                    Request.Builder()
                        .url(apiUrl(address, endpoint))
                        .header("X-Fbx-App-Auth", sessionToken)
                        .get()
                        .build()
                )
            }.getOrNull() ?: return@forEach

            parseStorageInfo(json.opt("result"))?.let { return it }
        }
        error("Informations de stockage Freebox indisponibles.")
    }

    private fun tvGuide(address: String, sessionToken: String): FreeboxEpgSnapshot {
        // Keep recent broadcasts: recordings are enriched shortly after they
        // finish and need the episode-specific EPG synopsis, not only TMDB's
        // generic programme description.
        val currentSeconds = System.currentTimeMillis() / 1000L
        // Recent recordings need their programme information too, while the IPTV guide
        // still needs the upcoming week.
        val nowSeconds = currentSeconds - 7L * 24L * 60L * 60L
        val endSeconds = currentSeconds + 7L * 24L * 60L * 60L
        val endpoints = listOf(
            "tv/epg/?start=$nowSeconds&end=$endSeconds",
            "tv/epg/?from=$nowSeconds&to=$endSeconds",
            "tv/programs/?start=$nowSeconds&end=$endSeconds",
            "tv/programmes/?start=$nowSeconds&end=$endSeconds",
            "tv/guide/?start=$nowSeconds&end=$endSeconds",
            "tv/epg/",
            "tv/programs/",
            "tv/programmes/",
            "tv/programme/",
            "tv/guide/",
            "tv/channels/"
        )
        val failures = mutableListOf<String>()

        endpoints.forEach { endpoint ->
            val json = runCatching {
                executeJson(
                    Request.Builder()
                        .url(apiUrl(address, endpoint))
                        .header("X-Fbx-App-Auth", sessionToken)
                        .get()
                        .build()
                )
            }.getOrElse { error ->
                if (error.isFreeboxRateLimit()) throw error
                failures.add("$endpoint: ${error.message}")
                return@forEach
            }

            parseFreeboxTvGuideSnapshot(json.opt("result")).takeIf { snapshot ->
                snapshot.channels.isNotEmpty() && snapshot.programmes.isNotEmpty()
            }?.let { snapshot ->
                Log.d(TAG, "EPG Freebox charge depuis $endpoint: ${snapshot.channels.size} chaines, ${snapshot.programmes.size} programmes")
                return snapshot
            }
        }

        error("Guide TV Freebox indisponible. Endpoints testes: ${failures.take(5).joinToString(" | ")}")
    }

    private fun tvGuideAt(address: String, sessionToken: String, instantMs: Long): FreeboxEpgSnapshot {
        val seconds = (instantMs / 1000L).coerceAtLeast(0L)
        // `by_time` is the native endpoint exposed by Freebox OS for the EPG.
        // The millisecond fallback keeps compatibility with firmware variants that
        // accepted a Java timestamp instead of Unix seconds.
        val endpoints = listOf(
            "tv/epg/by_time/$seconds",
            "tv/epg/by_time/$instantMs"
        )
        val failures = mutableListOf<String>()

        endpoints.forEach { endpoint ->
            val json = runCatching {
                executeJson(
                    Request.Builder()
                        .url(apiUrl(address, endpoint))
                        .header("X-Fbx-App-Auth", sessionToken)
                        .get()
                        .build()
                )
            }.getOrElse { error ->
                if (error.isFreeboxRateLimit()) throw error
                failures.add("$endpoint: ${error.message}")
                return@forEach
            }
            val snapshot = parseFreeboxTvGuideSnapshot(json.opt("result"))
            if (snapshot.programmes.isNotEmpty()) {
                Log.d(TAG, "EPG Freebox charge depuis $endpoint: ${snapshot.programmes.size} programmes")
                return snapshot
            }
        }

        error("Guide TV Freebox indisponible a l'instant demande. Endpoints testes: ${failures.joinToString(" | ")}")
    }

    private fun tvProgrammeDetails(
        address: String,
        sessionToken: String,
        programme: FreeboxEpgProgram
    ): FreeboxEpgProgram {
        val programmeId = programme.externalId?.takeIf { it.isNotBlank() } ?: return programme
        val json = executeJson(
            Request.Builder()
                .url(apiUrl(address, "tv/epg/programs/${encodePathSegment(programmeId)}"))
                .header("X-Fbx-App-Auth", sessionToken)
                .get()
                .build()
        )
        val details = json.optJSONObject("result") ?: return programme
        return programme.copy(
            title = firstText(details, "title", "name", "label") ?: programme.title,
            description = firstText(details, "desc", "description", "summary", "synopsis")
                ?: programme.description,
            imageUrl = firstText(details, "picture", "image_url", "imageUrl", "poster", "thumbnail", "cover")
                ?.let { resolveFreeboxEpgImageUrl(address, it) }
                ?: programme.imageUrl,
            genre = firstText(details, "genre", "category", "type") ?: programme.genre,
            rating = firstText(details, "rating", "parental_rating") ?: programme.rating,
            lang = firstText(details, "lang", "language") ?: programme.lang
        )
    }

    internal fun parseFreeboxTvGuideSnapshot(result: Any?): FreeboxEpgSnapshot {
        val channelMap = linkedMapOf<String, FreeboxEpgChannel>()
        val programmes = mutableListOf<FreeboxEpgProgram>()

        fun programmeCollectionChannelHint(key: String, value: Any?): String? {
            if (key.startsWith("uuid-", ignoreCase = true)) return key
            val objectValue = value as? JSONObject ?: return null
            val containsProgramme = objectValue.keys().asSequence().any { childKey ->
                objectValue.optJSONObject(childKey)?.let { child ->
                    firstText(child, "title", "name", "label") != null &&
                        hasAny(child, FREEBOX_PROGRAMME_START_KEYS)
                } == true
            }
            return key.takeIf { containsProgramme }
        }

        fun collect(value: Any?, channelHint: String? = null) {
            when (value) {
                is JSONObject -> {
                    parseChannel(value)?.let { channel ->
                        channelMap[channel.xmltvChannelId] = channel
                    }
                    parseProgramme(value, channelHint)?.let { parsed ->
                        programmes.add(parsed.programme)
                        if (parsed.channelName.isNotBlank() && !channelMap.containsKey(parsed.programme.channelId)) {
                            channelMap[parsed.programme.channelId] = FreeboxEpgChannel(
                                epgSourceId = 0L,
                                xmltvChannelId = parsed.programme.channelId,
                                displayName = parsed.channelName,
                                iconUrl = parsed.channelIcon
                            )
                        }
                    }
                    value.keys().forEach { key ->
                        val child = value.opt(key)
                        collect(child, programmeCollectionChannelHint(key, child) ?: channelHint)
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) collect(value.opt(index), channelHint)
                }
            }
        }

        collect(result)
        return FreeboxEpgSnapshot(
            channels = channelMap.values.toList(),
            programmes = programmes.distinctBy { "${it.channelId}|${it.startTime}|${it.endTime}|${it.title}" }
        )
    }

    private data class ParsedFreeboxProgramme(
        val programme: FreeboxEpgProgram,
        val channelName: String,
        val channelIcon: String?
    )

    private fun parseChannel(obj: JSONObject): FreeboxEpgChannel? {
        if (hasAny(obj, FREEBOX_PROGRAMME_START_KEYS)) return null
        val id = firstText(obj, "xmltv_id", "epg_channel_id", "channel_id", "channelId", "channel_uuid", "channelUuid", "uuid", "id", "uid")
            ?: return null
        val name = firstText(obj, "display_name", "displayName", "label", "name", "title")
            ?: return null
        return FreeboxEpgChannel(
            epgSourceId = 0L,
            xmltvChannelId = id,
            displayName = name,
            iconUrl = firstText(obj, "icon_url", "iconUrl", "logo_url", "logoUrl", "logo", "icon")
        )
    }

    private fun parseProgramme(obj: JSONObject, channelHint: String? = null): ParsedFreeboxProgramme? {
        val title = firstText(obj, "title", "name", "label") ?: return null
        val startTime = firstTimeMillis(obj, FREEBOX_PROGRAMME_START_KEYS) ?: return null
        val endTime = firstTimeMillis(obj, FREEBOX_PROGRAMME_END_KEYS)
            ?: firstDurationMillis(obj)?.let { duration -> startTime + duration }
            ?: return null
        if (endTime <= startTime) return null

        val channelObject = firstJsonObject(obj, "channel", "station", "service")
        val channelId = firstText(obj, "xmltv_id", "epg_channel_id", "channel_id", "channelId", "channel_uuid", "channelUuid", "service_id", "serviceId", "channel")
            ?: channelObject?.let {
                firstText(it, "xmltv_id", "epg_channel_id", "channel_id", "channelId", "channel_uuid", "channelUuid", "uuid", "id", "uid", "name")
            }
            ?: channelHint
            ?: return null
        val channelName = channelObject?.let {
            firstText(it, "display_name", "displayName", "label", "name", "title")
        }.orEmpty()
        val channelIcon = channelObject?.let {
            firstText(it, "icon_url", "iconUrl", "logo_url", "logoUrl", "logo", "icon")
        }

        return ParsedFreeboxProgramme(
            programme = FreeboxEpgProgram(
                externalId = firstText(obj, "id", "program_id", "programId", "uuid"),
                channelId = channelId,
                title = title,
                description = firstText(obj, "desc", "description", "summary", "synopsis").orEmpty(),
                startTime = startTime,
                endTime = endTime,
                lang = firstText(obj, "lang", "language").orEmpty(),
                rating = firstText(obj, "rating", "parental_rating"),
                imageUrl = firstText(obj, "picture", "image_url", "imageUrl", "poster", "thumbnail", "cover"),
                genre = firstText(obj, "genre", "category", "type")
            ),
            channelName = channelName.ifBlank { channelId },
            channelIcon = channelIcon
        )
    }

    private fun hasAny(obj: JSONObject, keys: List<String>): Boolean =
        keys.any { obj.has(it) && !obj.isNull(it) }

    private fun firstText(obj: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            if (!obj.has(key) || obj.isNull(key)) return@firstNotNullOfOrNull null
            when (val value = obj.opt(key)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }
                is Number -> value.toString()
                is JSONObject -> firstText(value, "id", "uuid", "name", "title")
                else -> null
            }
        }

    private fun firstJsonObject(obj: JSONObject, vararg keys: String): JSONObject? =
        keys.firstNotNullOfOrNull { key -> obj.optJSONObject(key) }

    private fun firstTimeMillis(obj: JSONObject, keys: List<String>): Long? =
        keys.firstNotNullOfOrNull { key -> obj.optTimeMillis(key) }

    private fun firstDurationMillis(obj: JSONObject): Long? =
        listOf("duration_ms", "durationMillis", "duration_millis", "duration", "length")
            .firstNotNullOfOrNull { key -> obj.optDurationValue(key) }

    private fun JSONObject.optTimeMillis(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong().let { raw -> if (raw < 10_000_000_000L) raw * 1000L else raw }
            is String -> {
                val trimmed = value.trim()
                trimmed.toLongOrNull()?.let { raw -> if (raw < 10_000_000_000L) raw * 1000L else raw }
                    ?: runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
                    ?: runCatching { Instant.parse(trimmed.replace(' ', 'T')).toEpochMilli() }.getOrNull()
            }
            else -> null
        }?.takeIf { it > 0L }
    }

    internal fun parseStorageInfo(result: Any?): FreeboxStorageInfo? {
        val candidates = mutableListOf<FreeboxStorageCandidate>()

        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val totalBytes = parseStorageValue(value, "total", "total_bytes", "size", "capacity", "capacity_bytes")
                        ?.takeIf { it > 0L }
                    val availableBytes = parseStorageValue(value, "free", "free_bytes", "available", "available_bytes", "unused")
                        ?.takeIf { it >= 0L }
                    if (totalBytes != null || availableBytes != null) {
                        candidates.add(
                            FreeboxStorageCandidate(
                                totalBytes = totalBytes,
                                availableBytes = availableBytes,
                                mounted = value.optBoolean("mounted", false) ||
                                    value.optBoolean("is_mounted", false) ||
                                    value.optString("state").equals("mounted", ignoreCase = true) ||
                                    value.optString("status").equals("mounted", ignoreCase = true) ||
                                    value.optString("mount_point").isNotBlank() ||
                                    value.optString("mountPoint").isNotBlank(),
                                partitionLike = value.has("partition") ||
                                    value.has("partition_id") ||
                                    value.has("partitionId") ||
                                    value.has("fsck_result") ||
                                    value.has("mount_point") ||
                                    value.has("mountPoint") ||
                                    value.has("free_bytes")
                            )
                        )
                    }
                    value.keys().forEach { key -> collect(value.opt(key)) }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) collect(value.opt(index))
                }
            }
        }

        collect(result)
        return candidates
            .maxWithOrNull(
                compareBy<FreeboxStorageCandidate> { it.mounted }
                    .thenBy { it.partitionLike }
                    .thenBy { it.totalBytes ?: 0L }
            )
            ?.let { FreeboxStorageInfo(totalBytes = it.totalBytes, availableBytes = it.availableBytes) }
    }

    private data class FreeboxStorageCandidate(
        val totalBytes: Long?,
        val availableBytes: Long?,
        val mounted: Boolean,
        val partitionLike: Boolean
    )

    private fun parseStorageValue(obj: JSONObject, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            obj.optLong(key).takeIf { it != 0L }
        }



    private fun executeJson(request: Request): JSONObject {

        httpClient.newCall(request).execute().use { response ->

            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {

                error("Freebox HTTP ${response.code}: ${body.ifBlank { response.message }}")

            }

            val json = JSONObject(body)

            if (!json.optBoolean("success", false)) {

                val errorCode = json.optString("error_code", "unknown_error")

                val message = json.optString("msg", errorCode)

                error(message.ifBlank { errorCode })

            }

            return json

        }

    }



    private fun parseEntries(result: Any?, parentPath: String): List<FreeboxFileEntry> {

        val array = when (result) {

            is JSONArray -> result

            is JSONObject -> result.optJSONArray("entries") ?: result.optJSONArray("files") ?: JSONArray()

            else -> JSONArray()

        }

        return buildList {

            for (index in 0 until array.length()) {

                val item = array.optJSONObject(index) ?: continue

                val type = item.optString("type")

                val mimetype = item.optString("mimetype")

                val isDirectory = type.equals("dir", true) ||

                    type.equals("directory", true) ||

                    mimetype.equals("inode/directory", true)

                val name = item.optString("name").ifBlank { item.optString("path").substringAfterLast('/') }

                if (name.isBlank() || name == "." || name == "..") continue

                val rawPath = item.optString("path")

                val decodedPath = decodeFreeboxPath(rawPath)

                val path = decodedPath ?: rawPath.takeIf { it.startsWith("/") } ?: joinFreeboxPath(parentPath, name)

                val encodedPath = when {

                    rawPath.isBlank() -> null

                    decodedPath != null -> rawPath

                    rawPath.startsWith("/") -> null

                    else -> rawPath

                }

                add(

                    FreeboxFileEntry(

                        name = name,

                        path = normalizeFreeboxPath(path),

                        encodedPath = encodedPath,

                        isDirectory = isDirectory,

                        size = parseFileSize(item),

                        durationMs = parseDurationMs(item),

                        modifiedMs = parseModifiedMs(item)

                    )

                )

            }

        }

    }



    private fun parseFileSize(item: JSONObject): Long? {
        val sizeKeys = listOf("size", "file_size", "filesize", "bytes")
        sizeKeys.firstNotNullOfOrNull { key ->
            item.optLong(key).takeIf { it > 0L }
        }?.let { return it }
        val nested = listOf("stat", "metadata", "file").mapNotNull { item.optJSONObject(it) }
        return nested.firstNotNullOfOrNull { obj ->
            sizeKeys.firstNotNullOfOrNull { key -> obj.optLong(key).takeIf { it > 0L } }
        }
    }

    private fun parseModifiedMs(item: JSONObject): Long? {
        val secondsKeys = listOf("mtime", "modification", "modified", "created")
        secondsKeys.firstNotNullOfOrNull { key ->
            item.optLong(key).takeIf { it > 0L }?.let { raw -> if (raw < 10_000_000_000L) raw * 1000L else raw }
        }?.let { return it }
        val nested = listOf("stat", "metadata", "file").mapNotNull { item.optJSONObject(it) }
        return nested.firstNotNullOfOrNull { obj ->
            secondsKeys.firstNotNullOfOrNull { key ->
                obj.optLong(key).takeIf { it > 0L }?.let { raw -> if (raw < 10_000_000_000L) raw * 1000L else raw }
            }
        }
    }

    private fun parseDurationMs(item: JSONObject): Long? {

        val directKeys = listOf("duration_ms", "durationMillis", "duration_millis", "duration")

        directKeys.firstNotNullOfOrNull { key -> item.optDurationValue(key) }?.let { return it }



        val nestedKeys = listOf("media_info", "mediaInfo", "video_info", "videoInfo", "metadata", "metadatas")

        nestedKeys.forEach { key ->

            val nested = item.optJSONObject(key) ?: return@forEach

            directKeys.firstNotNullOfOrNull { durationKey -> nested.optDurationValue(durationKey) }?.let { return it }

        }

        return null

    }



    private fun JSONObject.optDurationValue(key: String): Long? {

        if (!has(key) || isNull(key)) return null

        val raw = opt(key) ?: return null

        val value = when (raw) {

            is Number -> raw.toDouble()

            is String -> raw.trim().toDoubleOrNull() ?: raw.toDurationMillisOrNull()?.toDouble()

            else -> null

        } ?: return null

        if (value <= 0.0) return null

        return if (key.contains("ms", ignoreCase = true) || value > 10_000.0) {

            value.toLong()

        } else {

            (value * 1000.0).toLong()

        }.takeIf { it > 0L }

    }

    private fun String.toDurationMillisOrNull(): Long? {
        val parts = split(':').map { it.trim() }
        if (parts.size !in 2..3) return null
        val numeric = parts.map { it.toLongOrNull() ?: return null }
        return when (numeric.size) {
            2 -> {
                val (minutes, seconds) = numeric
                if (seconds < 0 || seconds >= 60) return null
                (minutes * 60 + seconds) * 1000L
            }
            3 -> {
                val (hours, minutes, seconds) = numeric
                if (minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) return null
                (hours * 3600 + minutes * 60 + seconds) * 1000L
            }
            else -> null
        }
    }

    private fun apiUrl(address: String, endpoint: String): String {

        val base = normalizeBaseUrl(address)

        return "$base/api/$API_VERSION/$endpoint"

    }



    private fun normalizeBaseUrl(address: String): String {

        val trimmed = address.trim().trimEnd('/')

        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {

            trimmed

        } else {

            "http://$trimmed"

        }

        return withScheme.replace(Regex("/api/v\\d+$"), "")

    }

    private suspend fun <T> executeEpgRequest(block: () -> T): T =
        epgRequestMutex.withLock { executeEpgRequestLocked(block) }

    private suspend fun <T> executeEpgRequestLocked(block: () -> T): T {
        val elapsed = System.currentTimeMillis() - lastEpgRequestAtMs
        if (elapsed < EPG_MIN_REQUEST_INTERVAL_MS) {
            delay(EPG_MIN_REQUEST_INTERVAL_MS - elapsed)
        }
        return try {
            block()
        } catch (firstError: Exception) {
            if (!firstError.isFreeboxRateLimit()) throw firstError
            delay(EPG_RATE_LIMIT_RETRY_DELAY_MS)
            block()
        } finally {
            lastEpgRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun Throwable.isFreeboxRateLimit(): Boolean {
        val text = message.orEmpty()
        return text.contains("rate_limit", ignoreCase = true) ||
            text.contains("too many requests", ignoreCase = true) ||
            text.contains("HTTP 429", ignoreCase = true)
    }

    private fun resolveFreeboxEpgImageUrl(address: String, value: String): String {
        val image = value.trim()
        if (image.startsWith("http://") || image.startsWith("https://")) return image
        if (image.startsWith("//")) return "https:$image"
        val base = normalizeBaseUrl(address)
        return if (image.startsWith('/')) "$base$image" else "$base/$image"
    }



    private fun normalizeFreeboxPath(path: String): String {

        val trimmed = path.trim().replace('\\', '/').trimEnd('/')

        if (trimmed.isBlank() || trimmed == "/") return FREEBOX_ROOT

        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"

    }



    private fun encodeFreeboxPath(path: String): String =

        encodePathSegment(encodeFreeboxApiPath(path))


    private fun freeboxApiPath(path: String, encodedPath: String?): String =
        encodedPath ?: encodeFreeboxApiPath(path)


    private fun encodeFreeboxApiPath(path: String): String =
        Base64.encodeToString(normalizeFreeboxPath(path).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)



    private fun encodePathSegment(value: String): String =

        URLEncoder.encode(value, "UTF-8")



    private fun decodeFreeboxPath(path: String): String? {

        if (path.isBlank() || path.startsWith("/")) return null

        return runCatching {

            String(Base64.decode(path, Base64.DEFAULT), Charsets.UTF_8)

                .takeIf { it.startsWith("/") }

        }.getOrNull()

    }



    private fun joinFreeboxPath(parentPath: String, name: String): String {

        val parent = normalizeFreeboxPath(parentPath).trimEnd('/')

        return "$parent/$name"

    }



    private fun hmacSha1Hex(secret: String, challenge: String): String {

        val mac = Mac.getInstance("HmacSHA1")

        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))

        return mac.doFinal(challenge.toByteArray(Charsets.UTF_8)).joinToString(separator = "") { byte ->

            "%02x".format(byte)

        }

    }



    companion object {

        private const val TAG = "FreeboxOsClient"

        private const val API_VERSION = "v15"

        private const val FREEBOX_ROOT = "/Freebox"

        private const val APP_ID = "com.nuvio.tv.freebox"

        private const val APP_NAME = "Jojo Home"

        private const val APP_VERSION = "1.0"

        // Freebox OS itself aligns by_time requests on one-hour programme ranges.
        // Reusing that range prevents one request per recording.
        private const val EPG_BY_TIME_RANGE_MS = 60L * 60L * 1000L
        private const val EPG_MIN_REQUEST_INTERVAL_MS = 1_200L
        private const val EPG_RATE_LIMIT_RETRY_DELAY_MS = 3_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val FREEBOX_PROGRAMME_START_KEYS = listOf(
            "start",
            "start_time",
            "startTime",
            "begin",
            "begin_time",
            "beginTime",
            "date_start",
            "dateStart",
            "date",
            "starts_at",
            "startsAt",
            "timestamp"
        )

        private val FREEBOX_PROGRAMME_END_KEYS = listOf(
            "end",
            "end_time",
            "endTime",
            "stop",
            "stop_time",
            "stopTime",
            "date_end",
            "dateEnd",
            "ends_at",
            "endsAt"
        )

    }

}
