package com.jojotv.home.iptv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal const val IPTV_COMPANION_PORT = 8093

internal fun iptvCompanionNasUrl(value: String): String = runCatching {
    val uri = URI(httpUrl(value))
    require(uri.port == -1 || uri.port in 1..65535)
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    uri.toASCIIString().trimEnd('/')
}.getOrDefault("")

internal fun iptvCompanionNasHostMatches(url: String, address: String): Boolean = runCatching {
    val base = iptvCompanionNasUrl(url)
    if (base.isBlank() || address.isBlank()) return@runCatching false
    val remote = InetAddress.getByName(address)
    InetAddress.getAllByName(URI(base).host).any { it == remote }
}.getOrDefault(false)

/** Wire format already sent by JojoTV Companion; credentials stay out of responses. */
@Serializable internal data class CompanionProviderPayload(
    val type: String = "", val name: String = "", val m3uUrl: String = "",
    val serverUrl: String = "", val username: String = "", val password: String = "",
    val portalUrl: String = "", val macAddress: String = "", val deviceId: String = "",
    val epgUrl: String = "", val headers: Map<String, String> = emptyMap(),
    val clearEpgUrl: Boolean = false, val clearHeaders: Boolean = false,
    val includeLive: Boolean? = null, val includeVod: Boolean? = null,
    val dns: IptvDnsConfig? = null, val clearDns: Boolean = false,
) {
    fun source(id: String = UUID.randomUUID().toString(), existing: IptvSource? = null): IptvSource {
        require(name.isNotBlank()) { "Nom du fournisseur obligatoire." }
        val kind = when (type.trim().lowercase()) {
            "m3u" -> SourceType.M3U; "xtream" -> SourceType.XTREAM; "stalker" -> SourceType.STALKER
            else -> throw IllegalArgumentException("Type de fournisseur inconnu.")
        }
        require(existing == null || existing.type == kind) { "Le type du fournisseur ne peut pas être changé pendant son édition." }
        val saved = existing?.takeIf { it.type == kind }
        val resolvedUsername = username.trim().ifBlank { saved?.username.orEmpty() }
        val resolvedPassword = password.ifBlank { saved?.password.orEmpty() }
        if (kind == SourceType.XTREAM) require(resolvedUsername.isNotBlank() && resolvedPassword.isNotBlank()) { "Utilisateur et mot de passe obligatoires." }
        val mac = macAddress.trim().ifBlank { saved?.mac.orEmpty() }
        if (kind == SourceType.STALKER) require(mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) { "Adresse MAC invalide." }
        require(headers.keys.all { it.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) } && headers.values.none { '\r' in it || '\n' in it }) { "Headers HTTP invalides." }
        val enteredUrl = when (kind) { SourceType.M3U -> m3uUrl; SourceType.XTREAM -> serverUrl; SourceType.STALKER -> portalUrl }
        val url = httpUrl(enteredUrl.trim().ifBlank { saved?.url.orEmpty() })
        val epg = when {
            clearEpgUrl -> ""
            epgUrl.isBlank() -> saved?.epgUrl.orEmpty()
            else -> httpUrl(epgUrl.trim())
        }
        val resolvedHeaders = when {
            clearHeaders -> emptyMap()
            headers.isNotEmpty() -> headers
            else -> saved?.headers.orEmpty()
        }
        val live = includeLive ?: saved?.includeLive ?: true
        val vod = includeVod ?: saved?.includeVod ?: true
        require(live || vod) { "Active au moins les chaînes TV ou la VOD." }
        return IptvSource(id, name.trim(), kind, url, resolvedUsername, resolvedPassword, mac,
            deviceId.trim().ifBlank { saved?.deviceId.orEmpty() }, epg, resolvedHeaders, live, vod,
            when { clearDns -> null; dns != null -> dns.normalized(); else -> saved?.dns })
    }
}

@Serializable internal data class CompanionCatalogChange(
    val sourceId: String = "", val type: String = "", val entity: String = "",
    val id: String = "", val groupId: String = "", val page: Int = 0,
    val name: String? = null, val visible: Boolean? = null, val all: Boolean = false,
    val orderIds: List<String>? = null,
)

internal data class CompanionCatalogSnapshot(val categories: List<IptvCategory>, val page: IptvPage = IptvPage(emptyList()))

@Serializable
internal data class CompanionVideoDeletion(val id: String = "", val confirm: Boolean = false)

internal class CompanionImportStatus {
    val id: String = UUID.randomUUID().toString()
    @Volatile var status = "importing"
    @Volatile var message = "Ajout automatique du fournisseur sur la TV JojoHome IPTV…"
    fun finish(success: Boolean, text: String) { message = text; status = if (success) "success" else "error" }
}

internal fun iptvLanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { !it.name.startsWith("tun") && !it.name.startsWith("wg") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

/** Local API used by the Companion and phone import page. */
internal class IptvCompanionServer(
    private val submitted: (IptvSource, CompanionImportStatus) -> Unit,
    private val summaries: () -> List<IptvSource>,
    port: Int = IPTV_COMPANION_PORT,
    private val companionNasUrl: () -> String = { "" },
    private val catalogue: (IptvSource, com.jojotv.home.iptv.ContentType, String, Int) -> CompanionCatalogSnapshot = { _, _, _, _ -> CompanionCatalogSnapshot(emptyList()) },
    private val readSetting: (String, String) -> String = { _, default -> default },
    private val saveSource: (IptvSource) -> Unit = {},
    private val removeSource: (String) -> Unit = {},
    private val saveSettings: (Map<String, String>) -> Unit = {},
    private val readDns: () -> IptvDnsConfig = { IptvDns.config() },
    private val saveDns: (IptvDnsConfig) -> Unit = { IptvDns.configure(it) },
    private val testDns: (IptvDnsConfig, String) -> IptvDnsTestResult = IptvDns::test,
    private val videos: (String, String, Int) -> String = { _, _, _ -> throw IllegalStateException("Ouvre Jojo Home sur cette TV.") },
    private val deleteVideo: (String, CompanionImportStatus) -> Unit = { _, status -> status.finish(false, "Suppression indisponible.") },
) : NanoHTTPD(port) {
    val sessionToken = UUID.randomUUID().toString()
    private val statuses = ConcurrentHashMap<String, CompanionImportStatus>()
    fun phoneUrl(ip: String, type: SourceType = SourceType.M3U): String = "http://$ip:$listeningPort/?type=${type.name.lowercase()}#$sessionToken"
    private fun json(body: String, code: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(code, "application/json; charset=utf-8", body).apply { addHeader("Cache-Control", "no-store") }
    private fun result(status: String, message: String, id: String = ""): String = buildJsonObject {
        put("status", status); put("message", message); if (status == "error") put("error", message); if (id.isNotEmpty()) put("id", id)
    }.toString()
    private fun authorized(session: IHTTPSession): Boolean = session.headers["x-iptv-token"] == sessionToken ||
        iptvCompanionNasHostMatches(companionNasUrl(), session.remoteIpAddress)
    private fun requestBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toLongOrNull()
        require(length != null && length in 1..262_144) { "Requête trop grande ou invalide." }
        val files = hashMapOf<String, String>()
        session.headers["content-type"] = "application/json; charset=utf-8"
        session.parseBody(files)
        return files["postData"].orEmpty()
    }
    private fun source(sourceId: String): IptvSource? = summaries().firstOrNull { it.id == sourceId }
    private fun contentType(value: String): com.jojotv.home.iptv.ContentType = value.trim().uppercase(Locale.ROOT).let {
        require(it in setOf("LIVE", "MOVIE", "SERIES")) { "Type de contenu invalide." }
        com.jojotv.home.iptv.ContentType.valueOf(it)
    }
    private fun error(failure: Exception): Response = json(result("error",
        failure.message?.takeIf { !it.contains("http", true) } ?: "La modification IPTV a échoué."), Response.Status.BAD_REQUEST)
    private fun providerDetails(value: IptvSource): String = buildJsonObject {
        put("id", value.id); put("name", value.name); put("type", value.type.name)
        put("hasPassword", value.password.isNotEmpty()); put("hasHeaders", value.headers.isNotEmpty())
        put("hasEpg", value.epgUrl.isNotBlank()); put("usernameConfigured", value.username.isNotBlank())
        put("macConfigured", value.mac.isNotBlank()); put("deviceIdConfigured", value.deviceId.isNotBlank())
        put("includeLive", value.includeLive); put("includeVod", value.includeVod)
        put("dns", iptvJson.encodeToJsonElement(value.dns))
    }.toString()
    private fun providerRoute(session: IHTTPSession, sourceId: String): Response {
        if (!authorized(session)) return json(result("error", "Connexion Companion requise."), Response.Status.FORBIDDEN)
        val current = source(sourceId) ?: return json(result("not_found", "Fournisseur introuvable."), Response.Status.NOT_FOUND)
        return try {
            when (session.method) {
                Method.GET -> json(providerDetails(current))
                Method.POST -> {
                    val updated = iptvJson.decodeFromString<CompanionProviderPayload>(requestBody(session)).source(current.id, current)
                    saveSource(updated)
                    json(providerDetails(updated))
                }
                Method.DELETE -> {
                    removeSource(current.id)
                    json(result("success", "Fournisseur supprimé."))
                }
                else -> json(result("error", "Méthode non autorisée."), Response.Status.METHOD_NOT_ALLOWED)
            }
        } catch (failure: Exception) { error(failure) }
    }
    private fun catalogueRoute(session: IHTTPSession): Response {
        if (!authorized(session)) return json(result("error", "Connexion Companion requise."), Response.Status.FORBIDDEN)
        return try {
            if (session.method == Method.GET) {
                val sourceId = session.parameters["sourceId"]?.firstOrNull().orEmpty()
                val source = source(sourceId) ?: return json(result("not_found", "Fournisseur introuvable."), Response.Status.NOT_FOUND)
                val type = contentType(session.parameters["type"]?.firstOrNull().orEmpty())
                require(source.includes(type)) { "Cette source ne fournit pas ce type de contenu." }
                val groupId = session.parameters["groupId"]?.firstOrNull().orEmpty()
                val page = session.parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 0
                require(page in 0..1000) { "Page invalide." }
                val snapshot = catalogue(source, type, groupId, page)
                val selectedGroup = snapshot.categories.firstOrNull { it.id == groupId }
                if (groupId.isNotBlank() && selectedGroup == null) return json(result("not_found", "Groupe introuvable."), Response.Status.NOT_FOUND)
                val orderedGroups = IptvOrganization.groups(snapshot.categories,
                    catalogueSetting(source.id, type, null, "groupSort", "playlist"),
                    IptvOrganization.ids(catalogueSetting(source.id, type, null, "groupOrder:${type.name}", "[]"))) { group ->
                    catalogueSetting(source.id, type, group.id, "groupName").ifBlank { group.name }
                }
                val settings = buildJsonObject {
                    put("sourceId", source.id); put("type", type.name)
                    put("groups", buildJsonArray {
                        orderedGroups.forEach { group ->
                            val hidden = IptvOrganization.groupHidden(group.id,
                                catalogueSetting(source.id, type, group.id, "hidden"),
                                catalogueSetting(source.id, type, null, "newGroupsVisible:${type.name}", "true"),
                                catalogueSetting(source.id, type, null, "knownGroups:${type.name}", "[]"))
                            add(buildJsonObject {
                                put("id", group.id)
                                put("name", catalogueSetting(source.id, type, group.id, "groupName").ifBlank { group.name })
                                put("visible", !hidden)
                            })
                        }
                    })
                    put("items", buildJsonArray {
                        snapshot.page.items.forEach { item ->
                            add(buildJsonObject {
                                put("id", item.id)
                                put("name", catalogueSetting(source.id, type, item.categoryId, "channelName:${item.id}").ifBlank { item.title })
                                put("visible", catalogueSetting(source.id, type, item.categoryId, "channelHidden:${item.id}") != "true")
                                put("groupId", item.categoryId)
                            })
                        }
                    })
                    snapshot.page.nextPage?.let { put("nextPage", it) }
                }
                json(settings.toString())
            } else if (session.method == Method.POST) {
                val change = iptvJson.decodeFromString<CompanionCatalogChange>(requestBody(session))
                val source = source(change.sourceId) ?: return json(result("not_found", "Fournisseur introuvable."), Response.Status.NOT_FOUND)
                val type = contentType(change.type)
                require(source.includes(type)) { "Cette source ne fournit pas ce type de contenu." }
                require(change.page in 0..1000) { "Page invalide." }
                val snapshot = catalogue(source, type, change.groupId, change.page)
                val changes = linkedMapOf<String, String>()
                when (change.entity.lowercase(Locale.ROOT)) {
                    "group" -> {
                        if (change.orderIds != null) {
                            val ids = snapshot.categories.map { it.id }
                            require(change.orderIds.size == ids.size && change.orderIds.toSet() == ids.toSet()) { "L’ordre des groupes est incomplet." }
                            changes[IptvOrganization.key(source.id, type, null, "groupOrder:${type.name}")] = iptvJson.encodeToString(change.orderIds)
                            changes[IptvOrganization.key(source.id, type, null, "groupSort")] = "manual"
                        }
                        if (change.all || change.id.isNotBlank()) {
                            val targets = if (change.all) snapshot.categories else listOfNotNull(snapshot.categories.firstOrNull { it.id == change.id })
                            require(targets.isNotEmpty()) { "Groupe introuvable." }
                            change.visible?.let { visible -> targets.forEach { group ->
                                changes[IptvOrganization.key(source.id, type, group.id, "hidden")] = (!visible).toString()
                            } }
                            if (!change.name.isNullOrBlank()) {
                                require(!change.all && change.name.length <= 120) { "Nom de groupe invalide." }
                                changes[IptvOrganization.key(source.id, type, change.id, "groupName")] = change.name.trim()
                            } else if (change.name != null && !change.all) {
                                changes[IptvOrganization.key(source.id, type, change.id, "groupName")] = ""
                            }
                        }
                    }
                    "channel" -> {
                        val targets = if (change.all) snapshot.page.items else listOfNotNull(snapshot.page.items.firstOrNull { it.id == change.id })
                        require(targets.isNotEmpty()) { "Chaîne introuvable dans cette page." }
                        change.visible?.let { visible -> targets.forEach { changes[IptvOrganization.channelHiddenKey(it)] = (!visible).toString() } }
                        change.name?.let { name ->
                            require(!change.all && name.length <= 200) { "Nom de chaîne invalide." }
                            changes[IptvOrganization.channelNameKey(targets.single())] = name.trim()
                        }
                    }
                    else -> throw IllegalArgumentException("Élément IPTV invalide.")
                }
                require(changes.isNotEmpty()) { "Aucune modification à enregistrer." }
                saveSettings(changes)
                json(buildJsonObject { put("saved", true); put("changed", changes.size) }.toString())
            } else json(result("error", "Méthode non autorisée."), Response.Status.METHOD_NOT_ALLOWED)
        } catch (failure: Exception) { error(failure) }
    }
    private fun catalogueSetting(source: String, type: com.jojotv.home.iptv.ContentType, group: String?, name: String, default: String = ""): String =
        readSetting(IptvOrganization.key(source, type, group?.takeIf { it.isNotBlank() }, name), default)
    private fun dnsRoute(session: IHTTPSession): Response {
        if (!authorized(session)) return json(result("error", "Scanne le QR code affiché sur cette TV."), Response.Status.FORBIDDEN)
            .apply { addHeader("Connection", "close") }
        return try {
            if (session.uri == "/api/dns/test") {
                val body = iptvJson.parseToJsonElement(requestBody(session)).jsonObject
                val config = iptvJson.decodeFromJsonElement<IptvDnsConfig>(body["config"] ?: JsonObject(emptyMap())).normalized()
                val hostname = iptvDnsTestHostname(body.string("host"))
                json(iptvJson.encodeToString(testDns(config, hostname)))
            } else {
                if (session.method == Method.POST) saveDns(iptvJson.decodeFromString<IptvDnsConfig>(requestBody(session)).normalized())
                val config = readDns()
                json(buildJsonObject {
                    put("config", buildJsonObject {
                        put("mode", config.mode)
                        put("primary", config.primary)
                        put("secondary", config.secondary)
                    })
                    put("presets", iptvJson.encodeToJsonElement(iptvDnsPresets))
                }.toString())
            }
        } catch (failure: IllegalArgumentException) {
            json(result("error", if (failure is kotlinx.serialization.SerializationException) "Configuration DNS invalide." else failure.message.orEmpty()), Response.Status.BAD_REQUEST)
        } catch (failure: IptvProviderException) {
            json(result("error", failure.message.orEmpty()), Response.Status.BAD_REQUEST)
        } catch (_: Exception) {
            json(result("error", "Le test ou l’enregistrement DNS a échoué. Vérifie la connexion de la TV."), Response.Status.BAD_REQUEST)
        }
    }
    private fun videosRoute(session: IHTTPSession): Response {
        if (!authorized(session)) return json(result("error", "Connexion Companion requise."), Response.Status.FORBIDDEN)
        return try {
            if (session.method == Method.GET) {
                val artwork = session.uri.endsWith("/artwork")
                val id = session.parameters[if (artwork) "id" else "folder"]?.firstOrNull().orEmpty()
                require(id.isEmpty() && !artwork || id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Élément Vidéos invalide." }
                val page = session.parameters["page"]?.firstOrNull()?.let { it.toIntOrNull() ?: -1 } ?: 0
                require(page in 0..1000) { "Page invalide." }
                json(videos(if (artwork) "artwork" else "list", id, page))
            } else {
                val deletion = iptvJson.decodeFromString<CompanionVideoDeletion>(requestBody(session))
                require(deletion.confirm) { "Confirme la suppression du fichier ou du dossier." }
                require(deletion.id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Élément Vidéos invalide." }
                val handle = CompanionImportStatus().apply { message = "Suppression en cours…" }
                statuses[handle.id] = handle
                deleteVideo(deletion.id, handle)
                json(result(handle.status, handle.message, handle.id), Response.Status.ACCEPTED)
            }
        } catch (failure: IllegalArgumentException) {
            json(result("error", if (failure is kotlinx.serialization.SerializationException) "Demande Vidéos invalide." else failure.message.orEmpty()), Response.Status.BAD_REQUEST)
        } catch (_: Exception) {
            json(result("error", "Dossier Vidéos indisponible. Vérifie que Jojo Home est ouvert et que le lecteur SMB est accessible."), Response.Status.SERVICE_UNAVAILABLE)
        }
    }
    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", iptvPhonePage(companionNasUrl())).apply {
            addHeader("Cache-Control", "no-store"); addHeader("Referrer-Policy", "no-referrer")
        }
        session.method == Method.GET && session.uri == "/api/ping" -> json(result("ok", "JojoHome IPTV prêt."))
        session.method == Method.GET && session.uri == "/api/providers" -> json(iptvJson.encodeToString(summaries().map { source ->
            mapOf("id" to source.id, "name" to source.name, "type" to source.type.name, "status" to "ACTIVE")
        }))
        session.uri.startsWith("/api/providers/") && session.method in setOf(Method.GET, Method.POST, Method.DELETE) ->
            providerRoute(session, session.uri.removePrefix("/api/providers/").substringBefore('/'))
        session.uri == "/api/catalog" && session.method in setOf(Method.GET, Method.POST) -> catalogueRoute(session)
        session.uri == "/api/videos" && session.method == Method.GET -> videosRoute(session)
        session.uri == "/api/videos/delete" && session.method == Method.POST -> videosRoute(session)
        session.uri == "/api/videos/artwork" && session.method == Method.GET -> videosRoute(session)
        (session.uri == "/api/dns" && session.method in setOf(Method.GET, Method.POST)) ||
            (session.uri == "/api/dns/test" && session.method == Method.POST) -> dnsRoute(session)
        session.method == Method.GET && session.uri.startsWith("/api/status/") -> {
            val handle = statuses[session.uri.removePrefix("/api/status/")]
            json(if (handle == null) result("not_found", "Demande introuvable.") else result(handle.status, handle.message, handle.id))
        }
        session.method == Method.POST && session.uri == "/api/providers" -> submit(session).apply {
            // Rejected requests may have an unread body; do not reuse that connection.
            addHeader("Connection", "close")
        }
        else -> json(result("error", "Action non prise en charge par JojoHome IPTV."), Response.Status.NOT_FOUND)
    }
    private fun submit(session: IHTTPSession): Response {
        // Only the QR session token or the configured NAS can submit an import.
        if (!authorized(session))
            return json(result("error", "Scanne le QR code affiché sur cette TV."), Response.Status.FORBIDDEN)
        if (statuses.values.any { it.status in setOf("pending", "importing") })
            return json(result("error", "Un fournisseur est déjà en cours d’importation."), Response.Status.CONFLICT)
        return try {
            val source = iptvJson.decodeFromString<CompanionProviderPayload>(requestBody(session)).source()
            if (statuses.size >= 32) statuses.clear()
            val handle = CompanionImportStatus()
            statuses[handle.id] = handle
            submitted(source, handle)
            json(result(handle.status, handle.message, handle.id))
        } catch (failure: IllegalArgumentException) {
            json(result("error", failure.message?.takeIf { !it.contains("http", true) } ?: "Données du fournisseur invalides."), Response.Status.BAD_REQUEST)
        } catch (_: Exception) {
            json(result("error", "Impossible de lire la demande."), Response.Status.BAD_REQUEST)
        }
    }
}

/** Keeps the TV-side management API available without opening the IPTV screen. */
internal object IptvCompanionRuntime {
    @Volatile private var home = java.lang.ref.WeakReference<com.jojotv.home.MainActivity>(null)
    @JvmStatic fun bindHome(activity: com.jojotv.home.MainActivity) { home = java.lang.ref.WeakReference(activity) }
    @JvmStatic fun unbindHome(activity: com.jojotv.home.MainActivity) { if (home.get() === activity) home.clear() }
    private val lock = Any()
    private val startup = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iptv-companion-startup").apply { isDaemon = true }
    }
    private val imports = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "iptv-companion-import").apply { isDaemon = true }
    }
    @Volatile private var instance: IptvCompanionServer? = null
    @Volatile private var activeSubmit: ((IptvSource, CompanionImportStatus) -> Unit)? = null
    @Volatile private var activeReady: (() -> Unit)? = null
    @Volatile private var loading = false
    @Volatile var startError: String = "Le serveur Companion démarre…"
        private set
    @Volatile var revision: Long = 0L
        private set

    val server: IptvCompanionServer? get() = instance

    fun start(context: Context) {
        synchronized(lock) {
            if (instance != null || loading) return
            loading = true
            startError = "Le serveur Companion démarre…"
        }
        val app = context.applicationContext
        startup.execute {
            // Device-local: changing a TV's DNS must not change other TVs through NAS/profile sync.
            val dnsPreferences = app.getSharedPreferences("iptv_network", Context.MODE_PRIVATE)
            val savedDns = runCatching {
                iptvJson.decodeFromString<IptvDnsConfig>(dnsPreferences.getString("dns", null) ?: "{}").normalized()
            }.getOrDefault(IptvDnsConfig())
            IptvDns.configure(savedDns)
            val store = IptvStore(app)
            runCatching { store.load() }.onFailure {
                android.util.Log.w("JojoHomeIPTV", "Companion store load failed: ${it.javaClass.simpleName}")
            }
            val candidate = IptvCompanionServer(
                submitted = { source, handle ->
                    val active = activeSubmit
                    if (active != null) active(source, handle) else imports.execute { importSource(store, source, handle) }
                },
                summaries = { store.sources() },
                companionNasUrl = {
                    iptvCompanionNasUrl(app.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                        .getString("nas_url", null).orEmpty())
                },
                catalogue = { source, type, groupId, page ->
                    val provider = providerFor(store.connectionSource(source), store.intSetting("epgPastDays", 1),
                        store.intSetting("epgRefreshHours", 24), store.setting("udpProxy", ""))
                    CompanionCatalogSnapshot(provider.categories(type),
                        if (groupId.isBlank()) IptvPage(emptyList()) else provider.items(type, groupId, page))
                },
                readSetting = { key, default -> store.setting(key, default) },
                saveSource = { source -> store.save(source); revision++ },
                removeSource = { id -> store.sources().firstOrNull { it.id == id }?.let(store::remove); revision++ },
                saveSettings = { values -> store.setSettings(values); revision++ },
                videos = { action, id, page ->
                    val activity = home.get() ?: throw IllegalStateException("Ouvre Jojo Home sur cette TV.")
                    activity.companionVideos(action, id, page).toString()
                },
                deleteVideo = { id, handle -> imports.execute {
                    try {
                        val activity = home.get() ?: throw IllegalStateException("Ouvre Jojo Home sur cette TV.")
                        activity.companionVideos("delete", id, 0)
                        handle.finish(true, "Suppression confirmée sur le serveur.")
                    } catch (failure: Exception) {
                        android.util.Log.w("JojoHomeIPTV", "Companion deletion failed: ${failure.javaClass.simpleName}")
                        handle.finish(false, "Suppression non confirmée. Actualise le dossier ; vérifie les droits SMB et que le fichier n’a pas changé.")
                    }
                } },
                saveDns = { config ->
                    IptvDns.configure(config)
                    dnsPreferences.edit().putString("dns", iptvJson.encodeToString(config)).apply()
                    revision++
                }
            )
            try {
                candidate.start(5000, true)
                instance = candidate
                startError = ""
            } catch (failure: Exception) {
                candidate.stop()
                startError = "Le serveur Companion n’a pas pu démarrer sur le port 8093."
                android.util.Log.w("JojoHomeIPTV", "Companion server start failed: ${failure.javaClass.simpleName}")
            } finally {
                synchronized(lock) { loading = false }
            }
            if (instance === candidate) activeReady?.invoke()
        }
    }

    fun bindActivity(
        onSubmitted: (IptvSource, CompanionImportStatus) -> Unit,
        onServerStarted: () -> Unit
    ) {
        activeSubmit = onSubmitted
        activeReady = onServerStarted
        if (instance != null) onServerStarted()
    }

    fun unbindActivity() {
        activeSubmit = null
        activeReady = null
    }

    private fun importSource(store: IptvStore, incoming: IptvSource, handle: CompanionImportStatus) {
        val previous = store.sources().firstOrNull {
            it.type == incoming.type && it.url == incoming.url && it.username == incoming.username && it.mac.equals(incoming.mac, true)
        }
        val source = incoming.copy(id = previous?.id ?: incoming.id,
            epgUrl = incoming.epgUrl.ifBlank { if (incoming.type == SourceType.M3U) "" else previous?.epgUrl.orEmpty() },
            includeLive = previous?.includeLive ?: incoming.includeLive,
            includeVod = previous?.includeVod ?: incoming.includeVod)
        handle.status = "importing"
        handle.message = "Vérification du fournisseur sur la TV…"
        try {
            providerFor(source).testConnection()
            store.save(source)
            revision++
            handle.finish(true, "Fournisseur enregistré dans JojoHome IPTV.")
        } catch (failure: Exception) {
            android.util.Log.w("JojoHomeIPTV", "Companion import failed: ${failure.javaClass.name}")
            handle.finish(false, companionImportFailureMessage(source.type, failure))
        }
    }
}

internal fun iptvPhonePage(companionNasUrl: String = ""): String {
    val url = iptvCompanionNasUrl(companionNasUrl).replace("&", "&amp;").replace("\"", "&quot;")
    val companion = if (url.isBlank()) "Adresse du Companion NAS non configurée dans Jojo Home."
        else """<a href="$url/" target="_blank" rel="noreferrer">Ouvrir mon Companion</a>"""
    return """<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>JojoHome IPTV · Nouveau fournisseur</title>
<style>:root{color-scheme:dark;font-family:system-ui,sans-serif}*{box-sizing:border-box}body{margin:0;background:#000;color:#fff;padding:24px}main{max-width:600px;margin:auto}h1{font-size:26px;color:#d3a033}label{display:block;margin:18px 0 6px}input,select,textarea,button{font:inherit;width:100%;border-radius:10px;padding:13px;border:1px solid #444;background:#151515;color:#fff}button{margin-top:22px;background:#d3a033;color:#172230;font-weight:700;cursor:pointer}button:disabled{opacity:.6}a{color:#d3a033}small,p{color:#ccc;line-height:1.5}.hidden{display:none}input:focus,select:focus,textarea:focus,button:focus{outline:3px solid #d3a033;outline-offset:2px}</style></head>
<body><main><h1>JojoHome IPTV</h1><button id="dnsOpen" type="button">DNS par défaut de cette TV</button>
<section id="dnsPanel" class="hidden" aria-labelledby="dnsTitle"><h2 id="dnsTitle">DNS par défaut de Jojo Home</h2><p>Ce réglage est propre à cette TV. Les playlists sans DNS spécifique l’utilisent pour le catalogue et la lecture. Un DNS différent ne garantit pas la résolution d’un refus HTTP 403.</p>
<form id="dnsForm"><label for="dnsMode">Fournisseur DNS</label><select id="dnsMode"></select><p id="dnsNote"></p>
<label for="dnsPrimary">DNS principal</label><input id="dnsPrimary" placeholder="Adresse IPv4 ou IPv6" autocomplete="off"><label for="dnsSecondary">DNS secondaire (facultatif)</label><input id="dnsSecondary" placeholder="Adresse IPv4 ou IPv6" autocomplete="off">
<label for="dnsHost">Serveur à tester</label><input id="dnsHost" placeholder="exemple.com" autocomplete="off"><button id="dnsTest" type="button">Tester depuis cette TV</button><button id="dnsSave" type="submit">Enregistrer sur cette TV</button><button id="dnsReset" type="button">Rétablir Automatique</button></form><p id="dnsStatus" role="status" aria-live="polite"></p></section>
<h2>Nouveau fournisseur</h2><p>Remplis les informations sur ton téléphone et envoie-les : le fournisseur sera ajouté automatiquement sur la TV.</p><form id="form">
<label for="type">Type de fournisseur</label><select id="type"><option value="m3u">Playlist M3U</option><option value="xtream">Xtream Codes</option><option value="stalker">Portail Stalker</option></select>
<label for="name">Nom du fournisseur</label><input id="name" required maxlength="120" autocomplete="off">
<section data-type="m3u"><label for="m3uUrl">URL de la playlist M3U</label><input id="m3uUrl" type="url" inputmode="url" placeholder="http://…/playlist.m3u"><label for="epgUrl">URL XMLTV (facultative)</label><input id="epgUrl" type="url" inputmode="url"></section>
<section data-type="xtream" class="hidden"><label for="serverUrl">URL du serveur</label><input id="serverUrl" type="url" inputmode="url" placeholder="http://serveur:8080"><label for="username">Utilisateur</label><input id="username" autocomplete="off"><label for="password">Mot de passe</label><input id="password" type="password" autocomplete="new-password"></section>
<section data-type="stalker" class="hidden"><label for="portalUrl">URL du portail</label><input id="portalUrl" type="url" inputmode="url" placeholder="http://serveur/c/"><label for="macAddress">Adresse MAC autorisée</label><input id="macAddress" placeholder="00:1A:79:00:00:00" autocomplete="off"><label for="deviceId">Identifiant du terminal (si demandé)</label><input id="deviceId" autocomplete="off"></section>
<section id="playlistDns"><label for="playlistDnsMode">DNS de cette playlist (facultatif)</label><select id="playlistDnsMode"><option value="tv">Utiliser le DNS par défaut de la TV</option></select><p id="playlistDnsNote">Aucun DNS spécifique : cette playlist suivra le réglage de la TV.</p><div id="playlistDnsAddresses" class="hidden"><label for="playlistDnsPrimary">DNS principal</label><input id="playlistDnsPrimary" placeholder="Adresse IPv4 ou IPv6" autocomplete="off"><label for="playlistDnsSecondary">DNS secondaire (facultatif)</label><input id="playlistDnsSecondary" placeholder="Adresse IPv4 ou IPv6" autocomplete="off"></div><button id="playlistDnsTest" type="button" disabled>Tester le DNS de cette playlist</button><p id="playlistDnsStatus" role="status" aria-live="polite"></p></section>
<label for="headers">Headers HTTP (facultatifs, un par ligne : Nom: valeur)</label><textarea id="headers" rows="2"></textarea><button id="send" type="submit">Envoyer à cette TV</button></form><p id="status" role="status" aria-live="polite"></p>
<p><small>Jojo Home doit être lancé sur la TV. Le module IPTV n’a pas besoin d’être ouvert.</small></p><p>$companion<br><small>Pour cibler cette TV : IP locale de la TV, port $IPTV_COMPANION_PORT.</small></p></main>
<script>const byId=id=>document.getElementById(id),token=location.hash.slice(1);let waiting=false;const selectedType=new URLSearchParams(location.search).get('type');if(['m3u','xtream','stalker'].includes(selectedType))byId('type').value=selectedType;
byId('type').onchange=()=>document.querySelectorAll('[data-type]').forEach(section=>{const selected=section.dataset.type===byId('type').value;section.classList.toggle('hidden',!selected);section.querySelectorAll('input').forEach(input=>input.disabled=!selected)});byId('type').onchange();
if(!token){byId('send').disabled=true;byId('status').textContent='Scanne le QR code affiché dans Nouveau fournisseur sur ta TV.'}
byId('form').onsubmit=async event=>{event.preventDefault();if(waiting)return;waiting=true;byId('send').disabled=true;try{const body={};['type','name','m3uUrl','epgUrl','serverUrl','username','password','portalUrl','macAddress','deviceId'].forEach(key=>body[key]=byId(key).disabled?'':byId(key).value.trim());body.password=byId('password').disabled?'':byId('password').value;body.headers={};for(const line of byId('headers').value.split('\n').filter(line=>line.trim())){const index=line.indexOf(':');if(index<1)throw Error('Chaque header doit être au format Nom: valeur.');body.headers[line.slice(0,index).trim()]=line.slice(index+1).trim()}
Object.assign(body,playlistDnsPayload());byId('status').textContent='Envoi à la TV…';const response=await fetch('/api/providers',{method:'POST',headers:{'content-type':'application/json','x-iptv-token':token},body:JSON.stringify(body)});let data=await response.json();if(!response.ok)throw Error(data.message||'Envoi impossible.');byId('status').textContent=data.message;const id=data.id;while(['pending','importing'].includes(data.status)){await new Promise(resolve=>setTimeout(resolve,1500));const check=await fetch('/api/status/'+encodeURIComponent(id),{cache:'no-store'});data=await check.json();byId('status').textContent=data.message}if(data.status==='success'){byId('form').reset();byId('type').onchange();playlistDnsChanged()}}catch(error){byId('status').textContent=error.message||'La TV n’est plus disponible.'}finally{waiting=false;byId('send').disabled=!token}};
let dnsPresets=[],dnsBusy=false;
const dnsRequest=async(path,body)=>{const response=await fetch(path,{method:body?'POST':'GET',cache:'no-store',headers:{'content-type':'application/json','x-iptv-token':token},body:body?JSON.stringify(body):undefined});const data=await response.json();if(response.status===404)throw Error('Cette TV nécessite une version de Jojo Home qui prend en charge le réglage DNS.');if(!response.ok)throw Error(data.error||data.message||'DNS indisponible.');return data};
const dnsConfig=()=>({mode:byId('dnsMode').value,primary:byId('dnsPrimary').value.trim(),secondary:byId('dnsSecondary').value.trim()});
const dnsModeChanged=()=>{const mode=byId('dnsMode').value,preset=dnsPresets.find(value=>value.id===mode);['dnsPrimary','dnsSecondary'].forEach((id,index)=>{const input=byId(id);input.readOnly=mode!=='custom';input.disabled=mode==='automatic';if(preset)input.value=index?preset.secondary:preset.primary;else if(mode==='automatic')input.value=''});byId('dnsPrimary').required=mode==='custom';byId('dnsNote').textContent=preset?(preset.note||'Adresses du fournisseur DNS sélectionné.'):(mode==='automatic'?'DNS fourni par le réseau de la TV. Les noms du réseau local utilisent toujours ce DNS.':'Saisis une ou deux adresses IP de serveur DNS.')};
const displayDns=data=>{dnsPresets=data.presets||[];const select=byId('dnsMode');select.replaceChildren();[{id:'automatic',name:'Automatique'},...dnsPresets,{id:'custom',name:'Personnalisé'}].forEach(value=>{const option=document.createElement('option');option.value=value.id;option.textContent=value.name;select.append(option)});select.value=data.config.mode;byId('dnsPrimary').value=data.config.primary||'';byId('dnsSecondary').value=data.config.secondary||'';dnsModeChanged()};
const dnsAction=async action=>{if(dnsBusy)return;dnsBusy=true;byId('dnsForm').querySelectorAll('button,input,select').forEach(input=>input.disabled=true);try{byId('dnsStatus').textContent='Connexion à la TV…';await action()}catch(error){byId('dnsStatus').textContent=error.message||'La TV n’est plus disponible.'}finally{dnsBusy=false;byId('dnsForm').querySelectorAll('button,input,select').forEach(input=>input.disabled=false);dnsModeChanged()}};
byId('dnsMode').onchange=()=>{dnsModeChanged();byId('dnsStatus').textContent='Choix de DNS modifié. Teste-le puis enregistre-le sur cette TV.'};
byId('dnsPrimary').oninput=byId('dnsSecondary').oninput=()=>{byId('dnsStatus').textContent='Adresses DNS modifiées. Teste-les puis enregistre-les sur cette TV.'};
byId('dnsOpen').disabled=!token;byId('dnsOpen').onclick=()=>{byId('dnsPanel').classList.remove('hidden');dnsAction(async()=>{displayDns(await dnsRequest('/api/dns'));byId('dnsStatus').textContent='Configuration actuelle chargée depuis cette TV.'})};
byId('dnsForm').onsubmit=event=>{event.preventDefault();const config=dnsConfig();dnsAction(async()=>{displayDns(await dnsRequest('/api/dns',config));byId('dnsStatus').textContent='DNS enregistré pour les prochaines connexions de Jojo Home sur cette TV.'})};
byId('dnsReset').onclick=()=>dnsAction(async()=>{displayDns(await dnsRequest('/api/dns',{mode:'automatic'}));byId('dnsStatus').textContent='DNS Automatique rétabli sur cette TV.'});
byId('dnsTest').onclick=()=>{const config=dnsConfig(),host=byId('dnsHost').value.trim();dnsAction(async()=>{const result=await dnsRequest('/api/dns/test',{config,host});byId('dnsStatus').textContent=result.host+' → '+result.addresses.join(', ')+' · '+result.elapsedMs+' ms. '+(result.systemForLocalHost?'DNS du réseau local utilisé. ':'')+'Test effectué sans enregistrer.'})};
const playlistDnsPayload=()=>{const mode=byId('playlistDnsMode').value;if(mode==='tv')return{dns:null,clearDns:true};const primary=byId('playlistDnsPrimary').value.trim(),secondary=byId('playlistDnsSecondary').value.trim();if(mode==='custom'&&!primary)throw Error('Saisis le DNS principal de cette playlist.');return{dns:{mode,primary,secondary},clearDns:false}};
const playlistDnsChanged=()=>{const mode=byId('playlistDnsMode').value,preset=dnsPresets.find(value=>value.id===mode);byId('playlistDnsAddresses').classList.toggle('hidden',mode==='tv'||mode==='automatic');['playlistDnsPrimary','playlistDnsSecondary'].forEach((id,index)=>{const input=byId(id);input.readOnly=mode!=='custom';input.disabled=mode==='tv'||mode==='automatic';if(preset)input.value=index?preset.secondary:preset.primary;else if(mode==='tv'||mode==='automatic')input.value=''});byId('playlistDnsPrimary').required=mode==='custom';byId('playlistDnsNote').textContent=preset?(preset.note||'Ce DNS sera utilisé uniquement pour cette playlist.'):(mode==='tv'?'Aucun DNS spécifique : cette playlist suivra le réglage de la TV.':mode==='automatic'?'Cette playlist utilisera le DNS fourni par le réseau de la TV.':'Saisis une ou deux adresses IP DNS. Elles seront enregistrées avec cette playlist.')};
byId('playlistDnsMode').onchange=playlistDnsChanged;
byId('playlistDnsTest').onclick=async()=>{const button=byId('playlistDnsTest'),status=byId('playlistDnsStatus');try{button.disabled=true;status.textContent='Test depuis cette TV…';const payload=playlistDnsPayload(),config=payload.dns||(await dnsRequest('/api/dns')).config;const address=byId(byId('type').value==='m3u'?'m3uUrl':byId('type').value==='xtream'?'serverUrl':'portalUrl').value.trim();let host;try{host=new URL(address).hostname}catch(_){throw Error('Renseigne d’abord l’adresse du fournisseur.')}const result=await dnsRequest('/api/dns/test',{config,host});status.textContent=result.host+' → '+result.addresses.join(', ')+' · '+result.elapsedMs+' ms. Test effectué sans enregistrer.'}catch(error){status.textContent=error.message}finally{button.disabled=false}};
if(token)dnsRequest('/api/dns').then(data=>{dnsPresets=data.presets||[];const select=byId('playlistDnsMode');[{id:'automatic',name:'Automatique (réseau de la TV)'},...dnsPresets,{id:'custom',name:'Personnalisé'}].forEach(value=>{const option=document.createElement('option');option.value=value.id;option.textContent=value.name;select.append(option)});byId('playlistDnsTest').disabled=false;playlistDnsChanged()}).catch(error=>{byId('playlistDnsStatus').textContent=error.message});
</script></body></html>"""
}
