package com.nuvio.tv.core.sync

import android.util.Base64
import com.jojotv.home.BuildConfig
import com.nuvio.tv.data.local.NasSyncSessionDataStore
import com.nuvio.tv.data.local.NasSyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class NasIptvFavorite(
    val providerKey: String,
    val contentType: String,
    val remoteContentId: String,
    val sortOrder: Int,
    val addedAt: Long,
    val updatedAt: Long
)

data class NasIptvFavoritesSnapshot(
    val favorites: List<NasIptvFavorite>,
    val tombstones: Map<String, Long>,
    val updatedAt: Long = 0L
)

fun NasIptvFavorite.syncKey(): String =
    "$providerKey|$contentType|$remoteContentId"

data class NasWatchProgress(
    val key: String, val contentId: String, val contentType: String, val name: String,
    val poster: String?, val backdrop: String?, val logo: String?, val videoId: String,
    val season: Int?, val episode: Int?, val episodeTitle: String?, val position: Long,
    val duration: Long, val lastWatched: Long, val addonBaseUrl: String?, val progressPercent: Float?,
    val source: String, val traktPlaybackId: Long?, val traktMovieId: Int?, val traktShowId: Int?,
    val traktEpisodeId: Int?, val description: String?
)

data class NasWatchProgressSnapshot(val entries: List<NasWatchProgress>, val tombstones: Map<String, Long>, val updatedAt: Long = 0L)

data class NasSyncChange(
    val revision: Long,
    val profileId: Int,
    val domain: String,
    val operation: String,
    val keys: List<String>,
    val updatedAt: Long,
    val deviceId: String
)

data class NasSyncChanges(val revision: Long, val oldestAvailableRevision: Long, val changes: List<NasSyncChange>)

data class NasWatchedItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAt: Long
)

data class NasWatchedItemsSnapshot(val entries: List<NasWatchedItem>, val tombstones: Map<String, Long>)

data class NasPairingStart(
    val id: String,
    val deviceSecret: String,
    val qrUrl: String,
    val expiresAtMillis: Long,
    val pollIntervalSeconds: Int
)

data class NasPairingStatus(
    val approved: Boolean,
    val expiresAtMillis: Long,
    val pollIntervalSeconds: Int
)

data class NasBackupInfo(
    val id: String,
    val createdAt: Long,
    val size: Long,
    val reason: String
)

@Singleton
class NasIptvFavoritesApi @Inject constructor(
    private val sessionStore: NasSyncSessionDataStore,
    private val syncStateStore: NasSyncStateStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val thumbnailClient = client.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = BuildConfig.JOJOTV_NAS_SYNC_URL.trimEnd('/')

    val accountEmailFlow = sessionStore.emailFlow
    val sessionTokenFlow = sessionStore.tokenFlow

    suspend fun register(email: String, password: String) {
        authenticate("/api/sync/auth/register", email, password)
    }

    suspend fun login(email: String, password: String) {
        authenticate("/api/sync/auth/login", email, password)
    }

    suspend fun signOut() = sessionStore.clear()

    suspend fun hasSession(): Boolean = sessionStore.token() != null

    suspend fun ensureSession(nuvioUserId: String?, email: String?): Boolean {
        if (sessionStore.token() != null) return true
        if (nuvioUserId.isNullOrBlank() || email.isNullOrBlank() || baseUrl.isBlank()) return false
        val body = JSONObject().put("nuvioUserId", nuvioUserId).put("email", email).toString()
        val response = execute(
            Request.Builder().url("$baseUrl/api/sync/auth/bootstrap-nuvio")
                .post(body.toRequestBody(jsonMediaType)).build()
        )
        syncStateStore.bindAccount(email)
        sessionStore.saveSession(response.getString("token"), email)
        return true
    }

    suspend fun startDevicePairing(deviceName: String): NasPairingStart {
        if (baseUrl.isBlank()) throw IllegalStateException("Adresse du serveur NAS absente.")
        val body = JSONObject().put("deviceName", deviceName.trim().take(120)).toString()
        val response = execute(
            Request.Builder().url("$baseUrl/api/sync/pairings")
                .post(body.toRequestBody(jsonMediaType)).build()
        )
        return NasPairingStart(
            id = response.getString("id"),
            deviceSecret = response.getString("deviceSecret"),
            qrUrl = response.getString("qrUrl"),
            expiresAtMillis = response.getLong("expiresAt"),
            pollIntervalSeconds = response.optInt("pollIntervalSeconds", 2).coerceAtLeast(1)
        )
    }

    suspend fun pollDevicePairing(id: String, deviceSecret: String): NasPairingStatus {
        if (baseUrl.isBlank()) throw IllegalStateException("Adresse du serveur NAS absente.")
        val url = "$baseUrl/api/sync/pairings/${java.net.URLEncoder.encode(id, "UTF-8")}?" +
            "deviceSecret=${java.net.URLEncoder.encode(deviceSecret, "UTF-8")}"
        val response = execute(Request.Builder().url(url).get().build())
        val approved = response.optString("status") == "approved"
        if (approved) {
            val account = response.getJSONObject("account")
            syncStateStore.bindAccount(account.getString("email"))
            sessionStore.saveSession(response.getString("token"), account.getString("email"))
        }
        return NasPairingStatus(
            approved = approved,
            expiresAtMillis = response.optLong("expiresAt", 0L),
            pollIntervalSeconds = response.optInt("pollIntervalSeconds", 2).coerceAtLeast(1)
        )
    }

    suspend fun pull(profileId: Int): NasIptvFavoritesSnapshot {
        val response = execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/iptv-favorites").get().build())
        val items = response.optJSONArray("favorites") ?: JSONArray()
        val favorites = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(NasIptvFavorite(
                    providerKey = item.optString("providerKey"),
                    contentType = item.optString("contentType"),
                    remoteContentId = item.optString("remoteContentId"),
                    sortOrder = item.optInt("sortOrder"),
                    addedAt = item.optLong("addedAt"),
                    updatedAt = item.optLong("updatedAt")
                ))
            }
        }
        val tombstones = response.optJSONArray("tombstones") ?: JSONArray()
        return NasIptvFavoritesSnapshot(
            favorites = favorites,
            tombstones = buildMap {
                for (index in 0 until tombstones.length()) {
                    val item = tombstones.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val deletedAt = item.optLong("deletedAt")
                    if (key.isNotEmpty() && deletedAt > 0L) put(key, deletedAt)
                }
            },
            updatedAt = response.optLong("updatedAt")
        )
    }

    suspend fun push(profileId: Int, favorites: List<NasIptvFavorite>) {
        val items = JSONArray()
        favorites.forEach { item ->
            items.put(JSONObject()
                .put("providerKey", item.providerKey)
                .put("contentType", item.contentType)
                .put("remoteContentId", item.remoteContentId)
                .put("sortOrder", item.sortOrder)
                .put("addedAt", item.addedAt)
                .put("updatedAt", item.updatedAt))
        }
        val body = JSONObject().put("favorites", items).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/iptv-favorites")
            .put(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun delete(profileId: Int, keys: Collection<String>) {
        val values = JSONArray().apply {
            keys.map(String::trim).filter(String::isNotEmpty).distinct().forEach(::put)
        }
        if (values.length() == 0) return
        val body = JSONObject().put("keys", values).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/iptv-favorites")
            .delete(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun pullProfileSettings(profileId: Int): JSONObject? {
        val response = execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/settings").get().build())
        return response.optJSONObject("settings")
    }

    suspend fun pushProfileSettings(profileId: Int, settings: JSONObject) {
        val body = JSONObject().put("settings", settings).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/settings")
            .put(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun pullWatchProgress(profileId: Int): NasWatchProgressSnapshot {
        val response = execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/watch-progress").get().build())
        val entries = response.optJSONArray("entries") ?: JSONArray()
        val tombstones = response.optJSONArray("tombstones") ?: JSONArray()
        return NasWatchProgressSnapshot(
            entries = buildList {
                for (index in 0 until entries.length()) entries.optJSONObject(index)?.let { add(it.toNasWatchProgress()) }
            },
            tombstones = buildMap {
                for (index in 0 until tombstones.length()) {
                    val item = tombstones.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val deletedAt = item.optLong("deletedAt")
                    if (key.isNotEmpty() && deletedAt > 0L) put(key, deletedAt)
                }
            },
            updatedAt = response.optLong("updatedAt")
        )
    }

    suspend fun pullChanges(profileId: Int, sinceRevision: Long): NasSyncChanges {
        val url = "$baseUrl/api/sync/changes?profileId=$profileId&since=${sinceRevision.coerceAtLeast(0L)}"
        val response = execute(authorizedRequest(url).get().build())
        val values = response.optJSONArray("changes") ?: JSONArray()
        return NasSyncChanges(
            revision = response.optLong("revision"),
            oldestAvailableRevision = response.optLong("oldestAvailableRevision"),
            changes = buildList {
                for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    val keys = item.optJSONArray("keys") ?: JSONArray()
                    add(NasSyncChange(
                        revision = item.optLong("revision"),
                        profileId = item.optInt("profileId"),
                        domain = item.optString("domain"),
                        operation = item.optString("operation"),
                        keys = buildList { for (keyIndex in 0 until keys.length()) add(keys.optString(keyIndex)) },
                        updatedAt = item.optLong("updatedAt"),
                        deviceId = item.optString("deviceId")
                    ))
                }
            }
        )
    }

    suspend fun pushWatchProgress(profileId: Int, entries: List<NasWatchProgress>) {
        val body = JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } }).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/watch-progress")
            .put(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun deleteWatchProgress(profileId: Int, keys: Collection<String>) {
        val values = JSONArray().apply { keys.map(String::trim).filter(String::isNotEmpty).distinct().forEach(::put) }
        if (values.length() == 0) return
        val body = JSONObject().put("keys", values).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/watch-progress")
            .delete(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun pullWatchedItems(profileId: Int): NasWatchedItemsSnapshot {
        val response = execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/watched-items").get().build())
        val entries = response.optJSONArray("entries") ?: JSONArray()
        val tombstones = response.optJSONArray("tombstones") ?: JSONArray()
        return NasWatchedItemsSnapshot(
            entries = buildList { for (index in 0 until entries.length()) entries.optJSONObject(index)?.let { add(it.toNasWatchedItem()) } },
            tombstones = buildMap {
                for (index in 0 until tombstones.length()) {
                    val item = tombstones.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val deletedAt = item.optLong("deletedAt")
                    if (key.isNotEmpty() && deletedAt > 0L) put(key, deletedAt)
                }
            }
        )
    }

    suspend fun pushWatchedItems(profileId: Int, entries: Collection<NasWatchedItem>) {
        val body = JSONObject().put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } }).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/watched-items").put(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun deleteWatchedItems(profileId: Int, entries: Collection<NasWatchedItem>) {
        if (entries.isEmpty()) return
        val body = JSONObject().put("keys", JSONArray().apply { entries.forEach { put(it.toKeyJson()) } }).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/watched-items").delete(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun pullProfileBlob(profileId: Int, key: String): JSONObject? {
        val response = execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/blobs/$key").get().build())
        return response.optJSONObject("value")
    }

    suspend fun pushProfileBlob(profileId: Int, key: String, value: JSONObject) {
        val body = JSONObject().put("value", value).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/blobs/$key").put(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun pullProfileBlobJson(profileId: Int, key: String): String? =
        pullProfileBlob(profileId, key)?.optString("json")?.takeIf { it.isNotBlank() }

    suspend fun pushProfileBlobJson(profileId: Int, key: String, value: String) {
        pushProfileBlob(profileId, key, JSONObject().put("json", value))
    }

    suspend fun deleteProfileData(profileId: Int) {
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId").delete().build())
    }

    /** Binary personal asset used by the bounded JojoTV visual backup cache. */
    suspend fun putProfileAsset(profileId: Int, key: String, file: File, contentType: String) {
        require(file.isFile && file.length() > 0L) { "Image de sauvegarde absente." }
        val safeKey = key.trim()
        require(safeKey.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Clé d'image invalide." }
        withContext(Dispatchers.IO) {
            val request = authorizedRequest("$baseUrl/api/sync/profiles/$profileId/assets/$safeKey")
                .put(file.asRequestBody(contentType.toMediaType()))
                .build()
            execute(request)
        }
    }

    suspend fun getProfileAsset(profileId: Int, key: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        val safeKey = key.trim()
        if (!safeKey.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) return@withContext false
        val request = authorizedRequest("$baseUrl/api/sync/profiles/$profileId/assets/$safeKey").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext false
            if (!response.isSuccessful) {
                if (response.code == 401) sessionStore.clearIfCurrent(request.header("Authorization"), request.url.toString().substringBefore("/api/"))
                throw IllegalStateException("NAS HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) return@withContext false
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(destination)) {
                destination.delete()
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            true
        }
    }

    /**
     * Asks the private NAS Companion to decode real Freebox video frames with
     * FFmpeg, then stores the returned JPEGs in the local app cache. The NAS
     * bearer token never appears in an image URL consumed by the UI.
     */
    suspend fun getFreeboxThumbnailFrames(
        profileId: Int,
        path: String,
        size: Long?,
        durationMs: Long?,
        modifiedMs: Long?,
        count: Int,
        destinationDirectory: File
    ): List<File> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || sessionStore.token() == null) return@withContext emptyList()
        val wantedCount = count.coerceIn(1, 5)
        destinationDirectory.mkdirs()
        val knownDuration = durationMs?.takeIf { it > 0L }
        if (knownDuration != null) {
            val expectedPositions = listOf(0.28, 0.12, 0.44, 0.60, 0.76)
                .map { fraction -> (knownDuration * fraction).roundToLong().coerceIn(1_000L, (knownDuration - 1_000L).coerceAtLeast(1_000L)) }
                .distinct()
                .take(wantedCount)
            val cached = expectedPositions.map { positionMs ->
                freeboxFrameCacheFile(destinationDirectory, path, size, modifiedMs, positionMs)
            }
            if (cached.size == wantedCount && cached.all { it.isUsableJpeg() }) return@withContext cached
        }
        val body = JSONObject()
            .put("path", path)
            .put("size", size ?: 0L)
            .put("durationMs", durationMs ?: 0L)
            .put("modifiedMs", modifiedMs ?: 0L)
            .put("count", wantedCount)
            .toString()
        val request = authorizedRequest("$baseUrl/api/sync/profiles/$profileId/freebox-os/thumbnails")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val responseJson = thumbnailClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", text) }
            if (!response.isSuccessful) {
                // A Freebox OS authorization error must not sign out the NAS account.
                throw IllegalStateException(json.optString("error", "NAS HTTP ${response.code}"))
            }
            json
        }
        val frames = responseJson.optJSONArray("frames") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until frames.length()) {
                val frame = frames.optJSONObject(index) ?: continue
                val positionMs = frame.optLong("positionMs", -1L).takeIf { it >= 0L } ?: continue
                val bytes = runCatching {
                    Base64.decode(frame.optString("jpegBase64"), Base64.DEFAULT)
                }.getOrNull() ?: continue
                if (bytes.size < 1024 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) continue
                val target = freeboxFrameCacheFile(destinationDirectory, path, size, modifiedMs, positionMs)
                val cacheKey = target.nameWithoutExtension
                val temporary = File(destinationDirectory, "$cacheKey.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                add(target)
            }
        }
    }

    suspend fun listBackups(app: String = "jojohome"): List<NasBackupInfo> {
        val response = execute(authorizedRequest("$baseUrl/api/sync/backups/$app").get().build())
        val backups = response.optJSONArray("backups") ?: JSONArray()
        return buildList {
            for (index in 0 until backups.length()) {
                val item = backups.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isNotEmpty()) add(
                    NasBackupInfo(
                        id = id,
                        createdAt = item.optLong("createdAt"),
                        size = item.optLong("size"),
                        reason = item.optString("reason", "scheduled")
                    )
                )
            }
        }
    }

    /** Creates a NAS-side snapshot and activates the daily 03:00 policy for this app. */
    suspend fun createBackup(app: String = "jojohome"): NasBackupInfo {
        val response = execute(authorizedRequest("$baseUrl/api/sync/backups/$app")
            .post("{}".toRequestBody(jsonMediaType)).build())
        val item = response.getJSONObject("backup")
        return NasBackupInfo(
            id = item.getString("id"),
            createdAt = item.getLong("createdAt"),
            size = item.getLong("size"),
            reason = item.optString("reason", "manual")
        )
    }

    suspend fun restoreBackup(id: String, app: String = "jojohome") {
        require(id.isNotBlank()) { "Sauvegarde NAS invalide." }
        val encodedId = java.net.URLEncoder.encode(id, "UTF-8")
        execute(authorizedRequest("$baseUrl/api/sync/backups/$app/$encodedId/restore")
            .post("{}".toRequestBody(jsonMediaType)).build())
    }

    suspend fun pullProfilePins(): Map<Int, Boolean> {
        val response = execute(authorizedRequest("$baseUrl/api/sync/profile-pins").get().build())
        val pins = response.optJSONArray("pins") ?: JSONArray()
        return buildMap { for (index in 0 until pins.length()) pins.optJSONObject(index)?.let { put(it.optInt("profileId"), it.optBoolean("enabled")) } }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String?) {
        val body = JSONObject().put("pin", pin).put("currentPin", currentPin).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/pin").put(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun clearProfilePin(profileId: Int, currentPin: String?) {
        val body = JSONObject().put("currentPin", currentPin).toString()
        execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/pin").delete(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Boolean {
        val body = JSONObject().put("pin", pin).toString()
        return execute(authorizedRequest("$baseUrl/api/sync/profiles/$profileId/pin").post(body.toRequestBody(jsonMediaType)).build()).optBoolean("unlocked")
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", text) }
            if (!response.isSuccessful) {
                if (response.code == 401) sessionStore.clearIfCurrent(request.header("Authorization"), request.url.toString().substringBefore("/api/"))
                throw IllegalStateException(json.optString("error", "NAS HTTP ${response.code}"))
            }
            json
        }
    }

    private suspend fun authenticate(path: String, email: String, password: String) {
        if (baseUrl.isBlank()) throw IllegalStateException("Adresse du serveur NAS absente.")
        val normalizedEmail = email.trim().lowercase()
        val body = JSONObject().put("email", normalizedEmail).put("password", password).toString()
        val response = execute(Request.Builder().url("$baseUrl$path").post(body.toRequestBody(jsonMediaType)).build())
        val accountEmail = response.getJSONObject("account").getString("email")
        syncStateStore.bindAccount(accountEmail)
        sessionStore.saveSession(response.getString("token"), accountEmail)
    }

    private fun JSONObject.toNasWatchProgress() = NasWatchProgress(
        key = optString("key"), contentId = optString("contentId"), contentType = optString("contentType"), name = optString("name"),
        poster = optString("poster").ifBlank { null }, backdrop = optString("backdrop").ifBlank { null }, logo = optString("logo").ifBlank { null }, videoId = optString("videoId"),
        season = optNullableInt("season"), episode = optNullableInt("episode"), episodeTitle = optString("episodeTitle").ifBlank { null }, position = optLong("position"),
        duration = optLong("duration"), lastWatched = optLong("lastWatched"), addonBaseUrl = optString("addonBaseUrl").ifBlank { null },
        progressPercent = if (has("progressPercent") && !isNull("progressPercent")) optDouble("progressPercent").toFloat() else null, source = optString("source"),
        traktPlaybackId = if (has("traktPlaybackId") && !isNull("traktPlaybackId")) optLong("traktPlaybackId") else null, traktMovieId = optNullableInt("traktMovieId"), traktShowId = optNullableInt("traktShowId"),
        traktEpisodeId = optNullableInt("traktEpisodeId"), description = optString("description").ifBlank { null }
    )

    private fun JSONObject.toNasWatchedItem() = NasWatchedItem(
        contentId = optString("contentId"), contentType = optString("contentType"), title = optString("title"),
        season = optNullableInt("season"), episode = optNullableInt("episode"), watchedAt = optLong("watchedAt")
    )

    private fun JSONObject.optNullableInt(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null

    private fun NasWatchProgress.toJson() = JSONObject()
        .put("key", key).put("contentId", contentId).put("contentType", contentType).put("name", name).put("poster", poster).put("backdrop", backdrop).put("logo", logo)
        .put("videoId", videoId).put("season", season).put("episode", episode).put("episodeTitle", episodeTitle).put("position", position).put("duration", duration)
        .put("lastWatched", lastWatched).put("addonBaseUrl", addonBaseUrl).put("progressPercent", progressPercent).put("source", source)
        .put("traktPlaybackId", traktPlaybackId).put("traktMovieId", traktMovieId).put("traktShowId", traktShowId).put("traktEpisodeId", traktEpisodeId).put("description", description)

    private fun NasWatchedItem.toJson() = JSONObject()
        .put("contentId", contentId).put("contentType", contentType).put("title", title)
        .put("season", season).put("episode", episode).put("watchedAt", watchedAt)

    private fun NasWatchedItem.toKeyJson() = JSONObject()
        .put("contentId", contentId).put("season", season).put("episode", episode)

    private suspend fun authorizedRequest(url: String): Request.Builder {
        val token = sessionStore.token() ?: throw IllegalStateException("Connexion NAS requise.")
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("X-JojoTV-Device-Id", syncStateStore.deviceId)
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun freeboxFrameCacheFile(
        directory: File,
        path: String,
        size: Long?,
        modifiedMs: Long?,
        positionMs: Long
    ): File {
        val key = "$path|${size ?: 0L}|${modifiedMs ?: 0L}|$positionMs|nas-v1".sha256()
        return File(directory, "$key.jpg")
    }

    private fun File.isUsableJpeg(): Boolean = isFile && length() >= 1024L && inputStream().use { input ->
        input.read() == 0xff && input.read() == 0xd8
    }
}
