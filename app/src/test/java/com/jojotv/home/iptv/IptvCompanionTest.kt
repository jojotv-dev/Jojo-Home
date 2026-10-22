package com.jojotv.home.iptv

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class IptvCompanionTest {
    @Test fun companionVideosRequireAuthorizationAndRejectFilesystemPaths() {
        var reads = 0
        val server = IptvCompanionServer({ _, _ -> }, { emptyList() }, port = 0,
            videos = { action, id, page -> reads++; """{"action":"$action","folder":"$id","page":$page,"items":[]}""" })
        server.start(5000, true)
        try {
            val client = OkHttpClient(); val root = "http://127.0.0.1:${server.listeningPort}"
            fun get(path: String, token: Boolean = true) = client.newCall(Request.Builder().url(root + path)
                .apply { if (token) header("X-IPTV-Token", server.sessionToken) }.build()).execute()
            get("/api/videos", false).use { assertEquals(403, it.code) }; assertEquals(0, reads)
            for (path in listOf("/api/videos?folder=..%2F..", "/api/videos?folder=smb%3A%2F%2Fprivate-host",
                "/api/videos?page=-1", "/api/videos?page=bad", "/api/videos/artwork")) {
                get(path).use { assertEquals(400, it.code) }
            }
            assertEquals(0, reads)
            get("/api/videos?page=2").use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("\"page\":2")) }
            get("/api/videos/artwork?id=00000000-0000-4000-8000-000000000001").use { assertEquals(200, it.code) }
            assertEquals(2, reads)
        } finally { server.stop() }
    }

    @Test fun companionVideoDeletionRequiresConfirmationAndReportsRemoteCompletion() {
        var received = ""
        var operation: CompanionImportStatus? = null
        val server = IptvCompanionServer({ _, _ -> }, { emptyList() }, port = 0,
            deleteVideo = { id, handle -> received = id; operation = handle })
        server.start(5000, true)
        try {
            val client = OkHttpClient(); val root = "http://127.0.0.1:${server.listeningPort}"
            val id = "00000000-0000-4000-8000-000000000001"
            fun remove(payload: String, token: Boolean = true) = client.newCall(Request.Builder().url("$root/api/videos/delete")
                .apply { if (token) header("X-IPTV-Token", server.sessionToken) }
                .post(payload.toRequestBody("application/json".toMediaType())).build()).execute()
            remove("""{"id":"$id","confirm":true}""", false).use { assertEquals(403, it.code) }
            remove("""{"id":"$id"}""").use { assertEquals(400, it.code) }
            remove("""{"id":"../videos","confirm":true}""").use { assertEquals(400, it.code) }
            assertNull(operation)
            remove("""{"id":"$id","confirm":true}""").use {
                assertEquals(202, it.code); assertTrue(it.body!!.string().contains("importing"))
            }
            assertEquals(id, received)
            operation!!.finish(true, "Suppression confirmée.")
            client.newCall(Request.Builder().url("$root/api/status/${operation!!.id}").build()).execute().use {
                assertEquals("success", iptvJson.parseToJsonElement(it.body!!.string()).jsonObject["status"]!!.jsonPrimitive.content)
            }
        } finally { server.stop() }
    }

    @Test fun companionVideoNetworkErrorsKeepSmbCredentialsPrivate() {
        val server = IptvCompanionServer({ _, _ -> }, { emptyList() }, port = 0,
            videos = { _, _, _ -> throw java.io.IOException("smb://private-user:private-password@private-host/share/") })
        server.start(5000, true)
        try {
            OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/api/videos")
                .header("X-IPTV-Token", server.sessionToken).build()).execute().use {
                assertEquals(503, it.code); assertFalse(it.body!!.string().contains("private"))
            }
        } finally { server.stop() }
    }

    @Test fun companionCanHideMoviesAndSeriesWithoutRemovingProviderCatalogues() {
        val source = IptvSource("fixture", "Fixture", SourceType.M3U, "http://example.test/list.m3u")
        val settings = mutableMapOf<String, String>()
        val server = IptvCompanionServer({ _, _ -> }, { listOf(source) }, port = 0,
            catalogue = { _, type, _, _ -> CompanionCatalogSnapshot(listOf(IptvCategory("group", "Fixture")),
                IptvPage(listOf(IptvItem("item", source.id, "provider-item", type, "Fixture", categoryId = "group")))) },
            saveSettings = { settings.putAll(it) })
        server.start(5000, true)
        try {
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                val body = """{"sourceId":"fixture","type":"${type.name}","groupId":"group","entity":"channel","id":"item","visible":false}"""
                OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/api/catalog")
                    .header("X-IPTV-Token", server.sessionToken).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(200, it.code) }
                assertEquals("true", settings[IptvOrganization.key(source.id, type, "group", "channelHidden:item")])
            }
        } finally { server.stop() }
    }

    @Test fun companionPlaylistDnsCanBeChosenKeptChangedAndReturnedToTheTvDefault() {
        val first = CompanionProviderPayload(type = "m3u", name = "Fixture", m3uUrl = "http://example.test/list.m3u",
            dns = IptvDnsConfig("cloudflare")).source("playlist-one")
        assertEquals(IptvDnsConfig("cloudflare").normalized(), first.dns)
        val edit = CompanionProviderPayload(type = "m3u", name = "Renamed")
        assertEquals(first.dns, edit.source(first.id, first).dns)
        val custom = edit.copy(dns = IptvDnsConfig("custom", "192.0.2.53", "2001:db8::53")).source(first.id, first)
        assertEquals("custom", custom.dns?.mode)
        val inherited = edit.copy(clearDns = true).source(first.id, custom)
        assertNull(inherited.dns)
        assertEquals(IptvDnsConfig(), edit.copy(dns = IptvDnsConfig()).source(first.id, custom).dns)
        assertThrows(IllegalArgumentException::class.java) {
            edit.copy(dns = IptvDnsConfig("custom", "invalid-host")).source(first.id, first)
        }
        assertEquals("cloudflare", first.dns?.mode)
        assertNull(CompanionProviderPayload(type = "m3u", name = "Other", m3uUrl = "http://other.example.test/list.m3u").source().dns)
    }

    @Test fun dnsManagementIsAuthorizedAndDiagnosticsNeverSaveTheTestedConfiguration() {
        var active = IptvDnsConfig()
        var saves = 0
        val probes = mutableListOf<Pair<IptvDnsConfig, String>>()
        val server = IptvCompanionServer({ _, _ -> }, { emptyList() }, port = 0,
            readDns = { active }, saveDns = { active = it; saves++ }, testDns = { config, host ->
                probes += config to host
                if (host == "failure.example.test") throw java.net.UnknownHostException("http://private-user:private-password@private-host/")
                IptvDnsTestResult(host, config.mode, listOf("192.0.2.42"), 7L, false)
            })
        server.start(5000, true)
        try {
            val root = "http://127.0.0.1:${server.listeningPort}"
            val client = OkHttpClient()
            fun request(path: String, payload: String? = null, token: String? = server.sessionToken) =
                client.newCall(Request.Builder().url(root + path).apply {
                    if (token != null) header("X-IPTV-Token", token)
                    if (payload != null) post(payload.toRequestBody("application/json".toMediaType()))
                }.build()).execute()
            request("/api/dns", token = null).use { assertEquals(403, it.code) }
            request("/api/dns", """{"mode":"google"}""", token = null).use { assertEquals(403, it.code) }
            request("/api/dns/test", """{"config":{"mode":"google"},"host":"example.test"}""", token = null).use { assertEquals(403, it.code) }
            assertEquals(0, saves); assertTrue(probes.isEmpty())
            request("/api/dns").use {
                assertEquals(200, it.code)
                val body = iptvJson.parseToJsonElement(it.body!!.string()).jsonObject
                assertEquals(10, body["presets"]!!.jsonArray.size)
                assertEquals("automatic", body["config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
                assertEquals("", body["config"]!!.jsonObject["primary"]!!.jsonPrimitive.content)
                assertEquals("", body["config"]!!.jsonObject["secondary"]!!.jsonPrimitive.content)
            }
            request("/api/dns/test", """{"config":{"mode":"cloudflare"},"host":"http://screen.example.test:8000/c/?token=private"}""").use {
                val body = it.body!!.string()
                assertEquals(body, 200, it.code)
                assertFalse(body.contains("private")); assertFalse(body.contains("/c/"))
                assertTrue(body.contains("192.0.2.42"))
            }
            assertEquals("screen.example.test", probes.single().second)
            assertEquals("1.1.1.1", probes.single().first.primary)
            assertEquals(0, saves); assertEquals(IptvDnsConfig(), active)
            request("/api/dns", """{"mode":"custom","primary":"192.0.2.53","secondary":"2001:db8::53"}""").use { assertEquals(200, it.code) }
            assertEquals(1, saves); assertEquals("custom", active.mode)
            request("/api/dns").use {
                val config = iptvJson.parseToJsonElement(it.body!!.string()).jsonObject["config"]!!.jsonObject
                assertEquals("192.0.2.53", config["primary"]!!.jsonPrimitive.content)
            }
            request("/api/dns", """{"mode":"custom","primary":"private-host"}""").use {
                assertEquals(400, it.code); assertFalse(it.body!!.string().contains("private-host"))
            }
            request("/api/dns", "{private-malformed").use {
                assertEquals(400, it.code); assertFalse(it.body!!.string().contains("private-malformed"))
            }
            assertEquals(1, saves)
            request("/api/dns/test", """{"host":"failure.example.test"}""").use {
                assertEquals(400, it.code)
                val body = it.body!!.string()
                listOf("private-user", "private-password", "private-host").forEach { secret -> assertFalse(body.contains(secret)) }
            }
            request("/api/dns", """{"mode":"automatic","primary":"old","secondary":"old"}""").use {
                assertEquals(200, it.code)
                val config = iptvJson.parseToJsonElement(it.body!!.string()).jsonObject["config"]!!.jsonObject
                assertEquals("automatic", config["mode"]!!.jsonPrimitive.content)
                assertEquals("", config["primary"]!!.jsonPrimitive.content)
                assertEquals("", config["secondary"]!!.jsonPrimitive.content)
            }
            assertEquals(2, saves); assertEquals(IptvDnsConfig(), active)
        } finally { server.stop() }
    }

    @Test fun dnsManagementAcceptsTheConfiguredNasAndPhonePageHasTheDnsForm() {
        val server = IptvCompanionServer({ _, _ -> }, { emptyList() }, port = 0,
            companionNasUrl = { "http://127.0.0.1:8095" }, readDns = { IptvDnsConfig("google").normalized() })
        server.start(5000, true)
        try {
            OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.listeningPort}/api/dns").build()).execute().use {
                assertEquals(200, it.code); assertTrue(it.body!!.string().contains("8.8.8.8"))
            }
            val page = iptvPhonePage()
            assertTrue(page.contains("id=\"dnsForm\"")); assertTrue(page.contains("/api/dns/test"))
            assertTrue(page.contains("Rétablir Automatique")); assertTrue(page.contains("Test effectué sans enregistrer."))
        } finally { server.stop() }
    }

    @Test fun phonePageUsesConfiguredCompanionAddressAndDoesNotInventOne() {
        val configured = " http://192.0.2.50:9000/companion/ "
        assertEquals("http://192.0.2.50:9000/companion", iptvCompanionNasUrl(configured))
        val page = iptvPhonePage(configured)
        assertTrue(page.contains("href=\"http://192.0.2.50:9000/companion/\""))
        assertFalse(page.contains("192.168.1.32"))
        assertFalse(iptvPhonePage().contains("Ouvrir mon Companion"))
        assertTrue(iptvPhonePage().contains("Companion NAS non configurée"))
        assertTrue(iptvPhonePage("http://fixture:8095/a&b").contains("href=\"http://fixture:8095/a&amp;b/\""))
        listOf("", "file:///private", "http://fixture:0", "http://user:password@fixture:8095",
            "http://fixture:8095/?token=private", "http://fixture:8095/#private").forEach {
            assertEquals("", iptvCompanionNasUrl(it))
        }
    }

    @Test fun nasImportUsesConfiguredHostAndStartsAutomaticImport() {
        var configured = "http://127.0.0.2:8095"
        var handle: CompanionImportStatus? = null
        val server = IptvCompanionServer({ _, status -> handle = status }, { emptyList() }, port = 0,
            companionNasUrl = { configured })
        server.start(5000, true)
        try {
            val root = "http://127.0.0.1:${server.listeningPort}"
            val client = OkHttpClient()
            val payload = iptvJson.encodeToString(CompanionProviderPayload(type = "m3u", name = "Freebox", m3uUrl = "http://fixture/playlist.m3u"))
            fun send() = client.newCall(Request.Builder().url("$root/api/providers")
                .post(payload.toRequestBody("application/json".toMediaType())).build()).execute()
            send().use { assertEquals(403, it.code) }
            assertNull(handle)
            configured = "http://127.0.0.1:8095"
            send().use { assertEquals(200, it.code) }
            assertEquals("importing", handle!!.status)
            client.newCall(Request.Builder().url(root).build()).execute().use {
                assertTrue(it.body!!.string().contains("href=\"http://127.0.0.1:8095/\""))
            }
            assertFalse(iptvCompanionNasHostMatches("", "127.0.0.1"))
            assertFalse(iptvCompanionNasHostMatches(configured, ""))
            assertFalse(iptvCompanionNasHostMatches("http://192.0.2.50:8095", "192.168.1.32"))
        } finally { server.stop() }
    }

    @Test fun allCompanionProviderTypesMapToNativeSources() {
        val m3u = CompanionProviderPayload(type = "m3u", name = " Playlist ", m3uUrl = "http://fixture/playlist.m3u", epgUrl = "http://fixture/guide.xml").source("same-id")
        assertEquals(SourceType.M3U, m3u.type); assertEquals("same-id", m3u.id); assertEquals("Playlist", m3u.name)
        assertEquals("http://fixture/guide.xml", m3u.epgUrl)
        val xtream = CompanionProviderPayload(type = "xtream", name = "Test", serverUrl = "http://fixture/", username = " user ", password = " keep spaces ").source()
        assertEquals("user", xtream.username); assertEquals(" keep spaces ", xtream.password)
        val stalker = CompanionProviderPayload(type = "stalker", name = "Test", portalUrl = "http://fixture/c/", macAddress = "00:1a:79:00:00:01", deviceId = "device").source()
        assertEquals(SourceType.STALKER, stalker.type); assertEquals("device", stalker.deviceId)
    }

    @Test fun companionManagementRequiresQrAuthorizationAndKeepsProviderSecretsPrivate() {
        val source = IptvSource("provider-id", "Secure source", SourceType.XTREAM, "http://private-host/",
            username = "private-user", password = "private-password", headers = mapOf("Authorization" to "private-header"))
        val group = IptvCategory("group-id", "Original group")
        val channel = IptvItem("channel-id", source.id, "remote-id", ContentType.LIVE, "Original channel", categoryId = group.id)
        val settings = mutableMapOf(IptvOrganization.key(source.id, ContentType.LIVE, group.id, "hidden") to "true",
            IptvOrganization.key(source.id, ContentType.LIVE, group.id, "groupName") to "Renamed group")
        var saved: IptvSource? = null
        var removed = ""
        val server = IptvCompanionServer({ _, _ -> }, { listOf(source) }, port = 0,
            catalogue = { _, _, selectedGroup, _ -> CompanionCatalogSnapshot(listOf(group),
                if (selectedGroup == group.id) IptvPage(listOf(channel)) else IptvPage(emptyList())) },
            readSetting = { key, default -> settings[key] ?: default },
            saveSource = { saved = it }, removeSource = { removed = it }, saveSettings = { settings.putAll(it) })
        server.start(5000, true)
        try {
            val root = "http://127.0.0.1:${server.listeningPort}"
            val client = OkHttpClient()
            fun request(path: String, method: String = "GET", payload: String? = null, token: String? = server.sessionToken) =
                Request.Builder().url(root + path).apply { if (token != null) header("X-IPTV-Token", token) }
                    .method(method, payload?.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request("/api/providers/${source.id}", token = null)).execute().use { assertEquals(403, it.code) }
            client.newCall(request("/api/catalog?sourceId=${source.id}&type=LIVE", token = null)).execute().use { assertEquals(403, it.code) }
            client.newCall(request("/api/providers/${source.id}")).execute().use {
                val body = it.body!!.string()
                assertEquals(200, it.code)
                assertTrue(body.contains("\"hasPassword\":true"))
                listOf("private-user", "private-password", "private-header", "private-host").forEach { secret -> assertFalse(body.contains(secret)) }
            }
            val update = iptvJson.encodeToString(CompanionProviderPayload(type = "xtream", name = "Edited", includeLive = true, includeVod = false))
            client.newCall(request("/api/providers/${source.id}", "POST", update)).execute().use {
                val body = it.body!!.string()
                assertEquals(body, 200, it.code)
            }
            assertEquals("Edited", saved!!.name); assertEquals("private-password", saved!!.password)
            assertEquals("private-user", saved!!.username); assertEquals(source.url, saved!!.url)
            assertEquals(source.headers, saved!!.headers); assertFalse(saved!!.includeVod)

            client.newCall(request("/api/catalog?sourceId=${source.id}&type=LIVE")).execute().use {
                val groups = iptvJson.parseToJsonElement(it.body!!.string()).jsonObject["groups"]!!.jsonArray
                assertEquals("Renamed group", groups.single().jsonObject["name"]!!.jsonPrimitive.content)
                assertFalse(groups.single().jsonObject["visible"]!!.jsonPrimitive.boolean)
            }
            val change = """{"sourceId":"${source.id}","type":"LIVE","entity":"channel","groupId":"${group.id}","page":0,"id":"${channel.id}","name":"Edited channel","visible":false}"""
            client.newCall(request("/api/catalog", "POST", change)).execute().use { assertEquals(200, it.code) }
            assertEquals("true", settings[IptvOrganization.channelHiddenKey(channel)])
            assertEquals("Edited channel", settings[IptvOrganization.channelNameKey(channel)])
            client.newCall(request("/api/providers/${source.id}", "DELETE")).execute().use { assertEquals(200, it.code) }
            assertEquals(source.id, removed)
        } finally { server.stop() }
    }

    @Test fun invalidCredentialsAndHeaderInjectionAreRejected() {
        listOf(
            CompanionProviderPayload(type = "stalker", name = "Test", portalUrl = "http://fixture/c/", macAddress = "bad"),
            CompanionProviderPayload(type = "xtream", name = "Test", serverUrl = "http://fixture/", username = "user"),
            CompanionProviderPayload(type = "m3u", name = "Test", m3uUrl = "file:///etc/passwd"),
            CompanionProviderPayload(type = "m3u", name = "Test", m3uUrl = "http://fixture/", headers = mapOf("X-Test" to "value\r\nOther: injected")),
        ).forEach { payload -> assertThrows(IllegalArgumentException::class.java) { payload.source() } }
    }
    @Test fun networkFailuresNeverExposeAccountUrlsAndM3uDoesNotAskForCredentials() {
        val failure = java.io.IOException("Failed to connect to http://private-user:private-password@fixture/playlist.m3u?token=private-token")
        SourceType.entries.forEach { type ->
            val message = companionImportFailureMessage(type, failure)
            assertFalse(message.contains("private"))
            assertFalse(message.contains("fixture"))
            if (type == SourceType.M3U) {
                assertTrue(message.contains("playlist M3U"))
                assertFalse(message.contains("identifiants"))
            }
        }
    }
    @Test fun realHttpImportRequiresQrTokenAndTracksAutomaticImport() {
        var received: IptvSource? = null
        var handle: CompanionImportStatus? = null
        val privateSource = IptvSource("old", "Existing", SourceType.XTREAM, "http://fixture/", username = "private-user", password = "private-password")
        val server = IptvCompanionServer({ source, status -> received = source; handle = status }, { listOf(privateSource) }, port = 0)
        server.start(5000, true)
        try {
            val root = "http://127.0.0.1:${server.listeningPort}"
            val client = OkHttpClient()
            val payload = iptvJson.encodeToString(CompanionProviderPayload(type = "m3u", name = "Téléphone · Été", m3uUrl = "http://fixture/playlist.m3u"))
            fun send(token: String) = client.newCall(Request.Builder().url("$root/api/providers")
                .header("X-IPTV-Token", token).post(payload.toRequestBody("application/json".toMediaType())).build()).execute()
            send("invalid").use { assertEquals(403, it.code) }
            assertNull(received)
            send(server.sessionToken).use {
                val body = it.body!!.string()
                assertEquals(body, 200, it.code)
                val result = iptvJson.parseToJsonElement(body).jsonObject
                assertEquals("importing", result["status"]!!.jsonPrimitive.content)
            }
            assertEquals("Téléphone · Été", received!!.name)
            send(server.sessionToken).use { assertEquals(409, it.code) }
            handle!!.finish(true, "Saved")
            client.newCall(Request.Builder().url("$root/api/status/${handle!!.id}").build()).execute().use {
                assertEquals("success", iptvJson.parseToJsonElement(it.body!!.string()).jsonObject["status"]!!.jsonPrimitive.content)
            }
            client.newCall(Request.Builder().url("$root/api/providers").build()).execute().use {
                val body = it.body!!.string()
                assertTrue(body.contains("Existing")); assertFalse(body.contains("private-user")); assertFalse(body.contains("private-password")); assertFalse(body.contains("http://fixture"))
            }
            assertEquals("$root/?type=stalker#${server.sessionToken}", server.phoneUrl("127.0.0.1", SourceType.STALKER))
            client.newCall(Request.Builder().url(root).build()).execute().use { assertTrue(it.body!!.string().contains("Nouveau fournisseur")) }
        } finally { server.stop() }
    }
}
