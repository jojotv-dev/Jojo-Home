package com.jojotv.home.progresspilot

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

// Shared by dispatch and request permits so an approved RPC cannot be blocked by a second stale list.
internal val accountRpcNames = setOf("account_settings_register", "account_settings_read", "account_settings_read_v2", "account_settings_seed", "account_settings_write",
    "account_content_read", "account_content_write", "account_pair_request", "account_pair_list", "account_pair_approve", "account_pair_collect",
    "account_sync_recover", "account_resume_masks_sync", "account_home_artwork_sync", "account_netflix_resumes_sync")

data class PilotEndpoint(val url: String, val publishableKey: String) {
    fun validate() {
        val parsed = try { url.toHttpUrl() } catch (_: Exception) { error("Adresse Supabase invalide.") }
        require(parsed.isHttps && parsed.host.matches(Regex("[a-z0-9-]+\\.supabase\\.co")) &&
            parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
            "Utilise l'adresse HTTPS racine du projet Supabase de test."
        }
        require(publishableKey.startsWith("sb_publishable_") && publishableKey.none(Char::isWhitespace)) {
            "Une clé publishable est requise ; les clés serveur sont interdites."
        }
    }

    override fun toString() = "PilotEndpoint(key=redacted)"
}

class PilotAuthSession(
    val ownerId: String, val sessionId: String, val accessToken: String,
    val refreshToken: String, val expiresAtSeconds: Long
) {
    override fun toString() = "PilotAuthSession(authenticated=true, tokens=redacted)"
}

internal fun pilotSignInInputMessage(email: String, password: String): String? = when {
    email.isBlank() -> "Renseigne l'e-mail du compte Supabase de test."
    password.isBlank() -> "Renseigne le mot de passe dans le champ « Mot de passe Supabase de test »."
    else -> null
}

/** Fixed messages only: exception payloads can contain credentials or response bodies. */
internal fun pilotSignInFailureMessage(failure: Exception): String = when (failure) {
    is PilotHttpFailure -> "Supabase a refusé la connexion (HTTP ${failure.status})."
    is UnknownHostException -> "Le nom du projet Supabase ne peut pas être résolu (DNS)."
    is SocketTimeoutException -> "La connexion Supabase a dépassé le délai d'attente."
    is SSLException -> "La connexion HTTPS à Supabase a échoué (TLS)."
    is ConnectException -> "Le projet Supabase est injoignable depuis cet appareil."
    is GeneralSecurityException -> "La session n'a pas pu être enregistrée dans le stockage sécurisé."
    is IOException -> if (failure.message == "SUPABASE_SESSION_INVALID")
        "La réponse de connexion ne contient pas une session Supabase valide."
        else "Erreur de connexion ou de stockage local (IOException)."
    is IllegalArgumentException -> "Vérifie les paramètres publics et l'état du pilote (IllegalArgumentException)."
    is IllegalStateException -> "La connexion est indisponible dans cet état du pilote (IllegalStateException)."
    else -> "La connexion Supabase n'a pas abouti."
}

internal val PilotScope.registrationPath: String
    get() = if (deviceC == null) "rest/v1/rpc/pilot_register_device" else "rest/v1/rpc/pilot_register_third_device"

class PilotHttpRequest(val url: String, val headers: Map<String, String>, val body: String) {
    val method = "POST"
    override fun toString() = "PilotHttpRequest(POST, credentials=redacted)"
}

class PilotHttpResponse(val status: Int, val body: String, val retryAfterSeconds: Long? = null) {
    override fun toString() = "PilotHttpResponse(status=$status, body=redacted)"
}

internal interface PilotHttpExecutor {
    /** Creates a cancellable call without starting network I/O. */
    fun prepare(request: PilotHttpRequest): PilotHttpCall
}

internal interface PilotHttpCall {
    fun execute(): PilotHttpResponse
    fun cancel()
}

class PilotRequestsStopped : IOException("PILOT_REQUESTS_STOPPED")

/** A process-local capability; never persisted, logged or sent. */
class PilotRequestPermit internal constructor(
    internal val endpoint: PilotEndpoint,
    internal val ownerId: String?,
    internal val paths: Set<String>,
    internal val scope: PilotScope?,
    internal val activationId: String?
) {
    override fun toString() = "PilotRequestPermit(redacted)"
}

/** Serializes permission revocation with call creation, including not-yet-started calls. */
@Singleton
class PilotRequestGate @Inject constructor() {
    private val lock = Any()
    private val permits = mutableSetOf<PilotRequestPermit>()
    private val calls = mutableMapOf<PilotHttpCall, PilotRequestPermit>()

    internal fun allow(endpoint: PilotEndpoint, ownerId: String?, paths: Set<String>,
                       scope: PilotScope? = null, activationId: String? = null): PilotRequestPermit = synchronized(lock) {
        endpoint.validate()
        PilotRequestPermit(endpoint, ownerId, paths, scope, activationId).also { permits += it }
    }

    internal fun execute(executor: PilotHttpExecutor, permit: PilotRequestPermit, request: PilotHttpRequest): PilotHttpResponse {
        val call = synchronized(lock) {
            if (permit !in permits) throw PilotRequestsStopped()
            require(permit.paths.any { request.url == permit.endpoint.url.trimEnd('/') + "/" + it })
            executor.prepare(request).also { calls[it] = permit }
        }
        try {
            return call.execute().also {
                synchronized(lock) { if (permit !in permits) throw PilotRequestsStopped() }
            }
        } catch (failure: IOException) {
            synchronized(lock) { if (permit !in permits) throw PilotRequestsStopped() }
            throw failure
        } finally { synchronized(lock) { calls.remove(call) } }
    }

    internal fun finish(permit: PilotRequestPermit) = synchronized(lock) {
        permits -= permit
        calls.filterValues { it === permit }.keys.forEach { it.cancel() }
        Unit
    }

    fun stop() = synchronized(lock) {
        permits.clear()
        calls.keys.forEach { it.cancel() }
    }
}

class PilotHttpFailure(val status: Int, val retryAfterMs: Long? = null) : IOException("SUPABASE_HTTP_$status")
internal class AccountRecoveryNotReady : IOException("ACCOUNT_RECOVERY_NOT_READY")

private class OkHttpPilotExecutor : PilotHttpExecutor {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override fun prepare(request: PilotHttpRequest): PilotHttpCall {
        val builder = Request.Builder().url(request.url)
            .post(request.body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
        return object : PilotHttpCall {
            override fun execute() = call.execute().use { response ->
                PilotHttpResponse(response.code, response.body?.string().orEmpty(), response.header("Retry-After")?.toLongOrNull())
            }
            override fun cancel() = call.cancel()
        }
    }
}

/** Write-only progress API. No GET, SELECT or progress download method exists. */
@Singleton
class SupabaseProgressApi internal constructor(
    private val executor: PilotHttpExecutor,
    private val requests: PilotRequestGate,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    @Inject constructor(requests: PilotRequestGate) : this(OkHttpPilotExecutor(), requests)

    private val gson = Gson()
    private val cloudGson = com.google.gson.GsonBuilder().serializeNulls().create()

    fun signIn(endpoint: PilotEndpoint, email: String, password: String, permit: PilotRequestPermit): PilotAuthSession {
        require(email.isNotBlank() && password.isNotBlank()) { "Compte Supabase de test requis." }
        return parseSession(post(endpoint, "auth/v1/token?grant_type=password", null,
            mapOf("email" to email.trim(), "password" to password), permit)).also {
                require(permit.ownerId == null || permit.ownerId == it.ownerId) { "Propriétaire du pilote différent." }
            }
    }

    fun refreshSession(endpoint: PilotEndpoint, previous: PilotAuthSession, permit: PilotRequestPermit): PilotAuthSession {
        val refreshed = parseSession(post(endpoint, "auth/v1/token?grant_type=refresh_token", null,
            mapOf("refresh_token" to previous.refreshToken), permit))
        require(refreshed.ownerId == previous.ownerId && refreshed.sessionId == previous.sessionId) {
            "La session renouvelée ne correspond pas au pilote."
        }
        return refreshed
    }

    fun signOutCurrentSession(endpoint: PilotEndpoint, session: PilotAuthSession, permit: PilotRequestPermit) {
        post(endpoint, "auth/v1/logout?scope=local", session, emptyMap<String, String>(), permit)
    }

    /** Account settings have their own RPC/RLS contract, independent of progress associations. */
    internal fun accountRpc(endpoint: PilotEndpoint, session: PilotAuthSession, name: String,
                            body: Any, permit: PilotRequestPermit): String {
        require(name in accountRpcNames)
        require(permit.ownerId == session.ownerId)
        return post(endpoint, "rest/v1/rpc/$name", session, body, permit, cloud = true).also {
            require(it.length <= 2 * 1024 * 1024) { "ACCOUNT_RESPONSE_TOO_LARGE" }
        }
    }

    fun registerDevice(endpoint: PilotEndpoint, session: PilotAuthSession, scope: PilotScope, permit: PilotRequestPermit) {
        scope.validate()
        require(!scope.accountEnrolled) { "L'association automatique utilise les RPC du compte, sans inscription au miroir historique." }
        require(session.ownerId == scope.ownerId && permit.scope == scope) { "Compte du pilote différent." }
        val parameters = mutableMapOf(
            "p_profile_binding_id" to scope.profileBindingId,
            "p_device_a" to scope.deviceA,
            "p_device_b" to scope.deviceB,
            "p_device_id" to scope.deviceId
        )
        scope.deviceC?.let { parameters["p_device_c"] = it }
        post(endpoint, scope.registrationPath, session, parameters, permit)
    }

    fun writeBatch(endpoint: PilotEndpoint, session: PilotAuthSession, scope: PilotScope,
                   operations: List<PilotOperation>, permit: PilotRequestPermit): List<PilotAcknowledgement> {
        require(operations.isNotEmpty() && operations.size <= 50) { "Lot du pilote invalide." }
        scope.validate()
        require(session.ownerId == scope.ownerId && permit.scope == scope && operations.all {
            it.payload.owner_id == scope.ownerId && it.payload.profile_binding_id == scope.profileBindingId &&
                it.payload.device_id == scope.deviceId && it.payload.content_key.matches(Regex("[0-9a-f]{64}")) &&
                it.payload.media_kind in setOf("film", "episode", "file") &&
                it.payload.source_time_ms > 0 && it.payload.position_ms >= 0 && it.payload.duration_ms >= 0 &&
                (it.payload.progress_percent == null || (it.payload.progress_percent.isFinite() && it.payload.progress_percent in 0f..100f)) &&
                it.revision > 0 && UUID.fromString(it.operation_id).toString() == it.operation_id
        }) { "Une opération appartient à une autre association." }
        val result = post(endpoint, "rest/v1/rpc/pilot_write_progress", session, mapOf("p_ops" to operations), permit)
        return try {
            val tree = JsonParser.parseString(result).asJsonArray
            require(tree.all { it.isJsonObject && it.asJsonObject.keySet() == setOf("operation_id", "revision", "status") })
            gson.fromJson(result, Array<PilotAcknowledgement>::class.java).toList().also { acks ->
                require(acks.size == operations.size && acks.map { it.operation_id }.distinct().size == acks.size)
                require(acks.all { ack -> operations.any { it.operation_id == ack.operation_id && it.revision == ack.revision } &&
                    ack.status in setOf("applied", "duplicate", "stale", "conflict") })
            }
        } catch (_: Exception) { throw IOException("SUPABASE_ACK_INVALID") }
    }

    internal fun readProgressChanges(endpoint: PilotEndpoint, session: PilotAuthSession, scope: PilotScope,
                                     keyEpoch: String, after: Long, permit: PilotRequestPermit,
                                     contentKey: String? = null): CloudProgressPage {
        require(scope == permit.scope && scope.ownerId == session.ownerId && after >= 0)
        require(UUID.fromString(keyEpoch).toString() == keyEpoch)
        require(contentKey == null || contentKey.matches(Regex("[0-9a-f]{64}")))
        val body = post(endpoint, "rest/v1/rpc/progress_sync_read_v2", session,
            mapOf("p_profile" to scope.profileBindingId, "p_epoch" to keyEpoch, "p_after" to after, "p_content" to contentKey), permit)
        val tree = JsonParser.parseString(body).asJsonObject
        require(tree.keySet() == setOf("rows", "next_cursor", "head_revision", "point"))
        val allowed = setOf("content_key", "media_kind", "position_ms", "duration_ms", "progress_percent",
            "watch_state", "resume_hidden", "source_time_ms", "server_revision", "received_time_ms", "sealed_metadata")
        require(tree.getAsJsonArray("rows").all { it.isJsonObject && it.asJsonObject.keySet() == allowed })
        require(tree["point"].isJsonNull || tree["point"].isJsonObject && tree["point"].asJsonObject.keySet() == allowed)
        return gson.fromJson(tree, CloudProgressPage::class.java).also { page ->
            require(page.rows.size <= 200 && page.next_cursor in after..page.head_revision)
            page.rows.forEach { it.validate(); require(it.server_revision > 0) }
            page.point?.let { it.validate(); require(contentKey != null && it.content_key == contentKey && it.server_revision > 0) }
        }
    }

    internal fun writeProgressChanges(endpoint: PilotEndpoint, session: PilotAuthSession, scope: PilotScope,
        keyEpoch: String, incarnation: String, operations: List<CloudProgressOperation>, permit: PilotRequestPermit): List<CloudProgressAck> {
        require(scope == permit.scope && scope.ownerId == session.ownerId && operations.size in 1..50)
        require(UUID.fromString(keyEpoch).toString() == keyEpoch && UUID.fromString(incarnation).toString() == incarnation)
        operations.forEach { it.payload.validate(); require(it.sequence > 0 && it.expected_revision >= 0) }
        val body = post(endpoint, "rest/v1/rpc/progress_sync_write_v2", session, mapOf(
            "p_profile" to scope.profileBindingId, "p_epoch" to keyEpoch, "p_incarnation" to incarnation, "p_ops" to operations), permit, cloud = true)
        val tree = JsonParser.parseString(body).asJsonArray
        require(tree.all { it.isJsonObject && it.asJsonObject.keySet() == setOf("operation_id", "status", "server_revision") })
        return gson.fromJson(tree, Array<CloudProgressAck>::class.java).toList().also {
            // Validate the whole response before acknowledging even its first member.
            CloudProgressJournal(pending = operations).acknowledge(operations, it)
        }
    }

    private fun post(endpoint: PilotEndpoint, path: String, session: PilotAuthSession?, body: Any, permit: PilotRequestPermit,
                     cloud: Boolean = false): String {
        endpoint.validate()
        require(endpoint == permit.endpoint && (session == null || session.ownerId == permit.ownerId))
        val headers = mutableMapOf("apikey" to endpoint.publishableKey, "Content-Type" to "application/json")
        session?.let { headers["Authorization"] = "Bearer ${it.accessToken}" }
        val response = requests.execute(executor, permit,
            PilotHttpRequest(endpoint.url.trimEnd('/') + "/" + path, headers, (if (cloud) cloudGson else gson).toJson(body)))
        if (response.status !in 200..299) {
            // SQLSTATE 55000 is HTTP 500 in PostgREST. Recognize only this
            // explicit prerequisite, without retaining the server's error body.
            if (path == "rest/v1/rpc/account_sync_recover" && response.status == 500 && response.body.length <= 4096 &&
                runCatching {
                    val error = JsonParser.parseString(response.body).asJsonObject
                    listOf("code", "message").all { error[it]?.isJsonPrimitive == true && error[it].asJsonPrimitive.isString } &&
                        error["code"].asString == "55000" && error["message"].asString == "ACCOUNT_RECOVERY_NOT_READY"
                }.getOrDefault(false)) throw AccountRecoveryNotReady()
            throw PilotHttpFailure(response.status,
                response.retryAfterSeconds?.coerceIn(0, 3600)?.times(1000))
        }
        return response.body
    }

    private fun parseSession(body: String): PilotAuthSession = try {
        val result = gson.fromJson(body, JsonObject::class.java)
        val token = result["access_token"].asString
        val claims = gson.fromJson(token.split('.')[1].decodeBase64()!!.utf8(), JsonObject::class.java)
        val owner = result.getAsJsonObject("user")["id"].asString
        val session = claims["session_id"].asString
        require(UUID.fromString(owner).toString() == owner && UUID.fromString(session).toString() == session &&
            claims["sub"].asString == owner)
        val expiry = result["expires_at"]?.asLong ?: (nowSeconds() + result["expires_in"].asLong)
        PilotAuthSession(owner, session, token, result["refresh_token"].asString, expiry)
    } catch (_: Exception) { throw IOException("SUPABASE_SESSION_INVALID") }
}
