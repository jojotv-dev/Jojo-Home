package com.jojotv.home.iptv

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import kotlin.concurrent.thread

class IptvProviderTest {
    @Test fun xtreamSeriesDetailsKeepProviderIdentityAndExposeTitleArtwork() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "get_series" -> """[{"series_id":36,"name":"Titre fournisseur","cover":"/poster.jpg","last_modified":"1700000001"}]"""
            "get_series_info" -> """{"info":{"name":"Autre titre","plot":"Synopsis fournisseur","genre":"Drame","releaseDate":"2022-04-01","tmdb":95480,"title_logo":"/title.png","backdrop_path":["/background.jpg"]},"episodes":{}}"""
            else -> "[]"
        } }.use { fixture ->
            val provider = providerFor(IptvSource("x", "Fixture", SourceType.XTREAM, fixture.url, "user", "password"))
            val original = provider.items(ContentType.SERIES, "").items.single()
            assertEquals(1_700_000_001_000L, original.addedAt)
            val details = provider.details(original)
            assertEquals(original.id, details.id); assertEquals(original.providerId, details.providerId)
            assertEquals(original.title, details.title); assertTrue(details.streamUrl.isBlank())
            assertEquals("Synopsis fournisseur", details.description); assertEquals("2022", details.year)
            assertEquals(95480, details.tmdbId); assertEquals("${fixture.url}/title.png", details.titleLogo)
            assertEquals("${fixture.url}/background.jpg", details.backdrop)
            assertEquals("36", fixture.calls.last().query["series_id"])
        }
    }

    @Test fun playlistDnsOverridesHaveIndependentConnectionsAndDoNotReplaceTheTvDefault() {
        val previous = IptvDns.config()
        try {
            IptvDns.configure(IptvDnsConfig("quad9"))
            val base = IptvHttp.searchClient
            assertSame(base, IptvDns.clientFor(base, null))
            val cloudflare = IptvDns.clientFor(base, IptvDnsConfig("cloudflare"))
            val google = IptvDns.clientFor(base, IptvDnsConfig("google"))
            val automatic = IptvDns.clientFor(base, IptvDnsConfig())
            assertNotSame(base.connectionPool, cloudflare.connectionPool)
            assertNotSame(cloudflare.connectionPool, google.connectionPool)
            assertNotSame(base.connectionPool, automatic.connectionPool)
            assertNotSame(IptvDns, automatic.dns)
            assertEquals(base.callTimeoutMillis, cloudflare.callTimeoutMillis)
            IptvDns.configure(IptvDnsConfig("adguard"))
            assertSame(cloudflare, IptvDns.clientFor(base, IptvDnsConfig("cloudflare")))
            assertSame(google, IptvDns.clientFor(base, IptvDnsConfig("google")))
            assertEquals("adguard", IptvDns.config().mode)
        } finally { IptvDns.configure(previous) }
    }

    @Test fun playlistDnsSurvivesSourceSerializationAndOlderSourcesInheritTheTvDefault() {
        val source = IptvSource("fixture", "Fixture", SourceType.M3U, "http://example.test/list.m3u",
            dns = IptvDnsConfig("custom", "192.0.2.53", "2001:db8::53").normalized())
        assertEquals(source, iptvJson.decodeFromString<IptvSource>(iptvJson.encodeToString(source)))
        assertNull(iptvJson.decodeFromString<IptvSource>(
            """{"id":"fixture","name":"Fixture","type":"M3U","url":"http://example.test/list.m3u"}""").dns)
    }

    @Test fun dnsPresetsCoverRequestedProvidersAndCannotBeOverriddenByPayloadAddresses() {
        assertEquals(listOf("Cloudflare", "Google Public DNS", "Quad9", "OpenDNS (Cisco)", "Verisign Public DNS",
            "Yandex DNS", "G-Core DNS", "Freenom World", "CleanBrowsing", "AdGuard DNS"), iptvDnsPresets.map { it.name })
        iptvDnsPresets.forEach { preset ->
            val config = IptvDnsConfig(preset.id, "untrusted.example", "999.999.999.999").normalized()
            assertEquals(preset.primary, config.primary)
            assertEquals(preset.secondary, config.secondary)
            assertNotNull(org.xbill.DNS.Address.getByAddress(config.primary))
            assertNotNull(org.xbill.DNS.Address.getByAddress(config.secondary))
        }
        assertEquals(IptvDnsConfig(), IptvDnsConfig(" Automatic ", "old-dns", "old-dns").normalized())
        val custom = IptvDnsConfig(" CUSTOM ", " 192.0.2.53 ", "2001:db8::53").normalized()
        assertEquals("192.0.2.53", custom.primary)
        assertEquals(org.xbill.DNS.Address.getByAddress("2001:db8::53"), org.xbill.DNS.Address.getByAddress(custom.secondary))
        assertEquals("", IptvDnsConfig("custom", "192.0.2.53").normalized().secondary)
    }

    @Test fun dnsConfigurationRejectsHostnamesAndInvalidNumericResolversWithoutChangingActiveDns() {
        val previous = IptvDns.config()
        try {
            IptvDns.configure(IptvDnsConfig("cloudflare"))
            listOf(IptvDnsConfig("unknown"), IptvDnsConfig("custom"), IptvDnsConfig("custom", "dns.example"),
                IptvDnsConfig("custom", "999.1.1.1"), IptvDnsConfig("custom", "0.0.0.0"),
                IptvDnsConfig("custom", "224.0.0.1"), IptvDnsConfig("custom", "https://192.0.2.53"),
                IptvDnsConfig("custom", "192.0.2.53", "invalid.example")).forEach {
                assertThrows(IllegalArgumentException::class.java) { IptvDns.configure(it) }
                assertEquals("cloudflare", IptvDns.config().mode)
            }
            assertSame(IptvDns, IptvHttp.client.dns)
            IptvDns.configure(IptvDnsConfig())
            assertEquals("automatic", IptvDns.config().mode)
            assertSame(IptvDns, IptvHttp.client.dns)
        } finally { IptvDns.configure(previous) }
    }

    @Test fun publicDnsKeepsLanAndDirectIpLookupsOnTheLocalNetwork() {
        var calls = 0
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 42))
        val resolver = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> { calls++; return listOf(address) }
        }
        assertEquals(listOf(address), IptvDns.lookupWith(resolver, "media.example.test"))
        assertEquals(1, calls)
        assertEquals("192.0.2.5", IptvDns.lookupWith(resolver, "192.0.2.5").single().hostAddress)
        assertTrue(IptvDns.lookupWith(resolver, "localhost").all { it.isLoopbackAddress })
        assertEquals(1, calls)
        listOf("NAS", "freebox.local", "nas.lan", "nas.home", "nas.internal", "MAFREEBOX.FREEBOX.FR.").forEach {
            assertTrue(iptvDnsUsesSystem(it))
        }
        assertFalse(iptvDnsUsesSystem("mafreebox.freebox.fr.public.example"))
        assertFalse(iptvDnsUsesSystem("1screen1.com"))
    }

    @Test fun dnsDiagnosticStripsPathsAndQueryParametersAndRejectsEmbeddedCredentials() {
        assertEquals("screen.example.test", iptvDnsTestHostname("http://screen.example.test:8000/c/?token=private"))
        assertEquals("screen.example.test", iptvDnsTestHostname("screen.example.test"))
        listOf("", "file:///private", "192.0.2.5", "http://[2001:db8::5]/", "http://private-user:private-password@screen.example.test/").forEach {
            assertThrows(IllegalArgumentException::class.java) { iptvDnsTestHostname(it) }
        }
        val message = companionImportFailureMessage(SourceType.STALKER, java.net.UnknownHostException("private-host"))
        assertTrue(message.contains("DNS")); assertFalse(message.contains("private-host"))
    }

    @Test fun assignedXmltvOverridesNativeGuidesAndKeepsExactChannelIds() {
        val now = System.currentTimeMillis()
        val dates = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val start = dates.format(java.util.Date(now - 60_000L))
        val end = dates.format(java.util.Date(now + 3_600_000L))
        SourceType.entries.forEach { type ->
            Fixture { call -> 200 to when {
                call.path == "/guide.xml" -> """<tv>
                    <programme channel="one" start="$start" stop="$end"><title>First</title><desc>Description</desc></programme>
                    <programme channel="two" start="$start" stop="$end"><title>Second</title></programme>
                    <programme channel="unrelated" start="$start" stop="$end"><title>Wrong channel</title></programme>
                </tv>"""
                call.path == "/playlist.m3u" -> """#EXTM3U
                    #EXTINF:-1 tvg-id="one",One
                    http://media.example.test/one.ts
                    #EXTINF:-1 tvg-id="two",Two
                    http://media.example.test/two.ts
                """.trimIndent()
                call.query["action"] == "handshake" -> """{"js":{"token":"fixture"}}"""
                call.query["action"] == "get_profile" -> """{"js":{}}"""
                call.query["action"] == "get_all_channels" ->
                    """{"js":{"data":[{"id":"1","name":"One","xmltv_id":"one"},{"id":"2","name":"Two","xmltv_id":"two"}]}}"""
                call.query["action"] == "get_live_streams" -> """[{"stream_id":1,"name":"One","epg_channel_id":"one"},{"stream_id":2,"name":"Two","epg_channel_id":"two"}]"""
                else -> "[]"
            } }.use { fixture ->
                val source = IptvSource("s", "Test", type,
                    when (type) { SourceType.M3U -> "${fixture.url}/playlist.m3u"; SourceType.STALKER -> "${fixture.url}/portal.php"; else -> fixture.url },
                    "user", "password", mac = "00:1A:79:00:00:01", epgUrl = "${fixture.url}/guide.xml")
                val provider = providerFor(source, epgPastDays = 7, epgRefreshHours = 0)
                val item = IptvItem("one", "s", "1", ContentType.LIVE, "One", epgId = "one")
                assertEquals("First", provider.epg(item).single().title)
                assertEquals("Description", provider.guide(item, now, now + 60_000L).single().description)
                assertTrue(provider.guide(item, now + 7_200_000L, now + 7_260_000L).isEmpty())
                assertEquals("Second", provider.epg(item.copy(epgId = "two")).single().title)
                assertTrue(provider.epg(item.copy(epgId = "unknown")).isEmpty())
                assertTrue(provider.epg(item.copy(epgId = "")).isEmpty())
                assertEquals(1, fixture.calls.count { it.path == "/guide.xml" })
                assertTrue(fixture.calls.none { it.query["action"] in listOf("get_short_epg", "get_simple_data_table") })
            }
        }
    }

    @Test fun excludedPlaylistContentNeverRequestsAnyProvider() {
        Fixture { 200 to "[]" }.use { fixture ->
            SourceType.entries.forEach { type ->
                val source = IptvSource("x", "Test", type, fixture.url, "user", "password", "00:1A:79:00:00:01")
                listOf(source.copy(includeLive = false) to listOf(ContentType.LIVE),
                    source.copy(includeVod = false) to listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE)).forEach { (selection, types) ->
                    val provider = providerFor(selection)
                    types.forEach { content ->
                        assertTrue(provider.categories(content).isEmpty())
                        assertEquals(IptvPage(emptyList()), provider.items(content, "", 3))
                        val pages = mutableListOf<IptvPage>()
                        provider.loadCatalogue(content, "") { _, page -> pages.add(page) }
                        assertEquals(listOf(IptvPage(emptyList())), pages)
                        assertEquals(IptvPage(emptyList()), provider.search("test", content, 2))
                    }
                    if (!selection.includeVod) assertTrue(provider.episodes(
                        IptvItem("series", selection.id, "1", ContentType.SERIES, "Test")).isEmpty())
                }
            }
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun stalkerDailyGuideUsesSelectedDateAndAllPages() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_simple_data_table" -> if (call.query["p"] == "1")
                """{"js":{"total_items":2,"data":[{"name":"First","start_timestamp":100,"stop_timestamp":200}]}}"""
                else """{"js":{"total_items":2,"data":[{"name":"Second","start_timestamp":200,"stop_timestamp":300}]}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Test", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val channel = IptvItem("live-id", "s", "7", ContentType.LIVE, "Live")
            assertEquals(listOf("First", "Second"), provider.guide(channel, 0, 400_000).map { it.title })
            val calls = fixture.calls.filter { it.query["action"] == "get_simple_data_table" }
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.query["type"] == "epg" && it.query["ch_id"] == "7" && !it.query["date"].isNullOrBlank() })
            provider.guide(channel, 0, 400_000)
            assertEquals(2, fixture.calls.count { it.query["action"] == "get_simple_data_table" })
        }
    }
    private data class Call(val path: String, val query: Map<String, String>, val cookie: String, val authorization: String)
    private class Fixture(private val response: (Call) -> Pair<Int, String>) : AutoCloseable {
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        @Volatile var writeBody: ((ByteArray, java.io.OutputStream) -> Unit)? = null
        @Volatile var responseHeaders: (Call) -> Map<String, String> = { emptyMap() }
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val worker = thread(isDaemon = true, name = "iptv-provider-fixture") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val uri = URI(reader.readLine().split(' ')[1])
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { parameter ->
                        URLDecoder.decode(parameter.substringBefore('='), "UTF-8") to URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
                    }
                    val call = Call(uri.path, query, headers["cookie"].orEmpty(), headers["authorization"].orEmpty())
                    calls += call
                    val (status, body) = response(call)
                    val bytes = body.toByteArray()
                    val cookie = if (query["action"] == "handshake") "Set-Cookie: portal_session=fixture; Path=/; HttpOnly\r\n" else ""
                    val extraHeaders = responseHeaders(call).entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                    val output = it.getOutputStream()
                    try {
                        output.write("HTTP/1.1 $status Response\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$cookie$extraHeaders\r\n".toByteArray())
                        val writer = writeBody
                        if (writer == null) output.write(bytes) else writer(bytes, output)
                        output.flush()
                    } catch (_: java.io.IOException) { /* A cancelled search closes its client socket. */ }
                }
            }
        }
        val url get() = "http://127.0.0.1:${server.localPort}"
        override fun close() { server.close(); worker.join(1000) }
    }

    @Test fun freeboxM3uImportsRtspWithoutCredentialsAndKeepsChannelVariants() {
        val streams = listOf(
            "rtsp://mafreebox.freebox.fr/fbxdvb/stream?tsid=6&nid=8442&sid=1537&frontend=9",
            "rtsp://mafreebox.freebox.fr/fbxtv_pub/stream?namespace=1&service=201&flavour=hd",
            "rtsp://mafreebox.freebox.fr/fbxtv_pub/stream?namespace=1&service=201&flavour=sd")
        val names = listOf("1 - TF1 (TNT)", "2 - France 2 (HD)", "2 - France 2 (SD)")
        Fixture { 200 to "#EXTM3U\n" + streams.mapIndexed { index, stream ->
            "#EXTINF:0,${names[index]}\n$stream\n"
        }.joinToString("") }.use { fixture ->
            val source = IptvSource("freebox", "Freebox", SourceType.M3U, "${fixture.url}/freeboxtv/playlist.m3u")
            val provider = providerFor(source)
            assertEquals("3 contenus disponibles", provider.testConnection())
            val channels = provider.items(ContentType.LIVE, "").items
            assertEquals(names, channels.map { it.title })
            assertEquals(3, channels.map { it.id }.distinct().size)
            assertEquals(streams, channels.map { provider.resolve(it).url })
            assertTrue(channels.all { provider.resolve(it).headers.isEmpty() })
            assertEquals(1, fixture.calls.size)
            assertEquals("/freeboxtv/playlist.m3u", fixture.calls.single().path)
            assertEquals("", fixture.calls.single().authorization)
            assertEquals("", fixture.calls.single().cookie)
        }
    }

    @Test fun m3uRejectsInvalidStreamUrlsAndKeepsPlaylistUrlsHttpOnly() {
        val source = IptvSource("m", "M3U", SourceType.M3U, "http://fixture/playlist.m3u")
        val invalid = listOf("rtsp:///stream", "rtsp://fixture:0/stream", "rtsp://fixture:65536/stream",
            "file:///private.ts", "javascript:alert(1)", "udp://239.0.0.1")
        val playlist = "#EXTM3U\n" + invalid.joinToString("") { "#EXTINF:0,Invalid\n$it\n" } +
            "#EXTINF:0,Valid\nrtsp://fixture:554/stream\n"
        assertEquals("rtsp://fixture:554/stream", M3uParser.parse(playlist.reader().buffered(), source).single().streamUrl)
        assertThrows(IllegalArgumentException::class.java) { httpUrl("rtsp://fixture/playlist.m3u") }
    }

    @Test fun m3uHttpFailuresAndEmptyResponsesGiveSafeCompanionMessages() {
        listOf(404, 401, 403).forEach { status ->
            Fixture { status to "private response" }.use { fixture ->
                val source = IptvSource("m", "M3U", SourceType.M3U, "${fixture.url}/missing.m3u?token=private-token")
                val failure = assertThrows(IptvProviderException::class.java) { providerFor(source).testConnection() }
                val message = companionImportFailureMessage(source.type, failure)
                assertTrue(message.contains("HTTP $status"))
                assertFalse(message.contains("private"))
                assertFalse(message.contains("identifiants"))
            }
        }
        Fixture { 200 to "<html>No playlist</html>" }.use { fixture ->
            val source = IptvSource("m", "M3U", SourceType.M3U, fixture.url)
            val failure = assertThrows(IptvProviderException::class.java) { providerFor(source).testConnection() }
            assertEquals("La playlist ne contient aucun flux HTTP, RTSP ou UDP reconnu.", companionImportFailureMessage(source.type, failure))
        }
    }

    @Test fun m3uUsesDeclaredXmltvAndGroupsSeriesEpisodes() {
        Fixture { call -> 200 to if (call.path == "/guide.xml") """
            <tv><programme channel="one" start="20990101000000 +0000" stop="20990101010000 +0000"><title>Future</title></programme></tv>
        """ else """
            #EXTM3U x-tvg-url="/guide.xml"
            #EXTINF:-1 tvg-id="one" group-title="TV",Chaîne
            /live/one.ts
            #EXTINF:-1 group-title="Séries",Exemple S02E03
            /series/3.mp4
            #EXTINF:-1 group-title="Séries",Exemple S01E01
            /series/1.mp4
        """.trimIndent() }.use { fixture ->
            val source = IptvSource("m", "M3U", SourceType.M3U, "${fixture.url}/playlist.m3u")
            val provider = providerFor(source)
            assertTrue(provider.testConnection().startsWith("3"))
            val series = provider.items(ContentType.SERIES, "").items.single()
            assertEquals("Exemple", series.title)
            assertEquals(listOf(1, 2), provider.episodes(series).map { it.season })
            assertEquals(listOf("Exemple"), provider.search("ex", ContentType.SERIES).items.map { it.title })
            assertEquals(listOf("Chaîne"), provider.search("c", ContentType.LIVE).items.map { it.title })
            assertEquals(1, fixture.calls.count { it.path == "/playlist.m3u" })
            assertEquals("${fixture.url}/series/1.mp4", provider.resolve(provider.episodes(series).first()).url)
            assertTrue(provider.epg(provider.items(ContentType.LIVE, "").items.single()).isEmpty())
            assertEquals(1, fixture.calls.count { it.path == "/guide.xml" })
        }
    }

    @Test fun xtreamKeepsProviderMetadataAndCorrectPlaybackPaths() {
        Fixture { call -> 200 to when (call.query["action"]) {
            null -> """{"user_info":{"auth":1,"status":"Active","max_connections":"2"}}"""
            "get_live_categories" -> """[{"category_id":"9","category_name":"TV"}]"""
            "get_live_streams" -> """[{"stream_id":12,"name":"Live","epg_channel_id":"epg"}]"""
            "get_vod_streams" -> """[{"stream_id":"20","name":"Film","container_extension":"mkv","added":"1700000000"}]"""
            "get_vod_info" -> """{"info":{"plot":"Synopsis fournisseur","movie_image":"/cover.jpg"}}"""
            "get_series" -> """[{"series_id":30,"name":"Série","cover":"/series.jpg"}]"""
            "get_series_info" -> """{"episodes":{"2":[{"id":33,"episode_num":3,"title":"Troisième","container_extension":"mp4","info":{"plot":"Résumé épisode"}}]}}"""
            "get_short_epg" -> """{"epg_listings":[{"title":"Sm91cm5hbA==","description":"UmVzdW3DqQ==","start_timestamp":100,"stop_timestamp":"200"}]}"""
            else -> "[]"
        } }.use { fixture ->
            val provider = providerFor(IptvSource("x", "Xtream", SourceType.XTREAM, "${fixture.url}/player_api.php", "user", "p&ss"))
            assertTrue(provider.testConnection().contains("2 connexion"))
            assertEquals("TV", provider.categories(ContentType.LIVE).single().name)
            val live = provider.items(ContentType.LIVE, "9").items.single()
            assertEquals("${fixture.url}/live/user/p&ss/12.m3u8", provider.resolve(live).url)
            assertEquals("Journal", provider.epg(live).single().title)
            val film = provider.items(ContentType.MOVIE, "").items.single()
            assertEquals(1_700_000_000_000L, film.addedAt)
            assertTrue(provider.resolve(film).url.endsWith("/20.mkv"))
            val edited = providerFor(IptvSource("x", "Xtream", SourceType.XTREAM, fixture.url, "new-user", "new-password"))
            assertEquals("${fixture.url}/movie/new-user/new-password/20.mkv", edited.resolve(film).url)
            assertEquals("Synopsis fournisseur", provider.details(film).description)
            assertEquals("${fixture.url}/cover.jpg", provider.details(film).artwork)
            val episode = provider.episodes(provider.items(ContentType.SERIES, "").items.single()).single()
            assertEquals(2, episode.season)
            assertEquals(3, episode.episode)
            assertEquals("Résumé épisode", episode.description)
            assertTrue(provider.resolve(episode).url.endsWith("/series/user/p&ss/33.mp4"))
            assertTrue(fixture.calls.all { it.query["password"] == "p&ss" })
            assertEquals("9", fixture.calls.first { it.query["action"] == "get_live_streams" }.query["category_id"])
        }
    }

    @Test fun stalkerVodPreservesAdditionDatesForMoviesAndSeries() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{"blocked":0}}"""
            "get_ordered_list" -> """{"js":{"data":[{"id":"7","name":"Title","added":"2026-10-20 12:00:00"}],"total_items":1}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Fixture", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val expected = iptvAddedAt("2026-10-20 12:00:00")
            assertTrue(expected > 0)
            assertEquals(expected, provider.items(ContentType.MOVIE, "").items.single().addedAt)
            assertEquals(expected, provider.items(ContentType.SERIES, "").items.single().addedAt)
        }
    }

    @Test fun stalkerFallsBackRenewsOnceAndPreservesSessionCookies() {
        var handshakes = 0
        var channels = 0
        Fixture { call -> if (call.path != "/stalker_portal/server/load.php") 404 to "missing" else when (call.query["action"]) {
            "handshake" -> { handshakes++; 200 to """{"js":{"token":"token$handshakes"}}""" }
            "get_profile" -> 200 to """{"js":{"blocked":0}}"""
            "get_all_channels" -> { channels++; if (channels == 1) 401 to "expired" else 200 to """{"js":{"data":[{"id":"7","name":"Live","cmd":"ffmpeg http://localhost/ch/7"}]}}""" }
            "create_link" -> 200 to """{"js":{"cmd":"ffmpeg https://media.example.test/live/7.ts?token=media"}}"""
            "get_short_epg" -> 200 to """{"js":[{"name":"Programme","time":100,"time_to":200,"descr":"Texte"}]}"""
            else -> 200 to """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/c/", mac = "00:1A:79:00:00:01", deviceId = "own-device"))
            val channel = provider.items(ContentType.LIVE, "").items.single()
            assertEquals(2, handshakes)
            assertEquals(2, channels)
            assertEquals("Bearer token2", channel.headers["Authorization"])
            assertTrue(channel.headers["Cookie"].orEmpty().contains("portal_session=fixture"))
            assertEquals(listOf(channel.id), provider.search("l", ContentType.LIVE).items.map { it.id })
            assertEquals(2, channels)
            val profile = fixture.calls.last { it.query["action"] == "get_profile" }
            assertEquals("own-device", profile.query["device_id"])
            assertFalse(profile.cookie.contains("token="))
            val request = fixture.calls.last { it.query["action"] == "get_all_channels" }
            assertEquals("Bearer token2", request.authorization)
            assertTrue(request.cookie.contains("portal_session=fixture"))
            assertTrue(request.cookie.contains("mac=00%3A1A%3A79%3A00%3A00%3A01"))
            assertTrue(request.cookie.contains("token=token2"))
            assertEquals("Programme", provider.epg(channel).single().title)
            val stream = provider.resolve(channel)
            assertEquals("https://media.example.test/live/7.ts?token=media", stream.url)
            assertFalse(stream.headers.containsKey("Authorization"))
            assertFalse(stream.headers.containsKey("Cookie"))
        }
    }

    @Test fun stalkerEmptyGlobalListLoadsAllLivePagesAndTheRequestedGenre() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_all_channels" -> """{"js":{"data":[]}}"""
            "get_ordered_list" -> when {
                call.query["genre"] == "9" -> """{"js":{"data":[{"id":"7","name":"One","tv_genre_id":"9"},{"id":"9","name":"Three","tv_genre_id":"9"}],"total_items":2,"max_page_items":2}}"""
                call.query["p"] == "1" -> """{"js":{"data":[{"id":"7","name":"One","tv_genre_id":"9"},{"id":"8","name":"Two","tv_genre_id":"10"}],"total_items":3,"max_page_items":2}}"""
                else -> """{"js":{"data":[{"id":"9","name":"Three","tv_genre_id":"9"}],"total_items":3,"max_page_items":2}}"""
            }
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val pages = mutableListOf<IptvPage>()
            provider.loadCatalogue(ContentType.LIVE, "") { _, page -> pages.add(page) }
            assertEquals(listOf(listOf("7", "8"), listOf("9")), pages.map { it.items.map { item -> item.providerId } })
            assertEquals(listOf(1, null), pages.map { it.nextPage })
            assertEquals(listOf("7", "9"), provider.items(ContentType.LIVE, "9").items.map { it.providerId })
            val calls = fixture.calls.filter { it.query["action"] == "get_ordered_list" }
            assertEquals(listOf("*", "*", "9"), calls.map { it.query["genre"] })
            assertEquals(listOf("1", "2", "1"), calls.map { it.query["p"] })
            assertTrue(calls.all { it.query["type"] == "itv" && "category" !in it.query })
            assertTrue(calls.all { it.authorization == "Bearer fixture" && it.cookie.contains("portal_session=fixture") })
            assertEquals(1, fixture.calls.count { it.query["action"] == "get_all_channels" })
        }
    }

    @Test fun stalkerLiveFallbackSearchUsesThePortalsQueryAndPagination() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> """{"js":{"data":[{"id":"${call.query["p"]}","name":"Équipe ${call.query["p"]}"}],"total_items":2,"max_page_items":1}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val first = provider.search("Équipe", ContentType.LIVE)
            val second = provider.search("Équipe", ContentType.LIVE, 1)
            assertEquals("1", first.items.single().providerId)
            assertEquals(1, first.nextPage)
            assertEquals("2", second.items.single().providerId)
            assertNull(second.nextPage)
            val calls = fixture.calls.filter { it.query["action"] == "get_ordered_list" }
            assertEquals(listOf("1", "2"), calls.map { it.query["p"] })
            assertTrue(calls.all { it.query["search"] == "Équipe" && it.query["genre"] == "*" })
            assertEquals(1, fixture.calls.count { it.query["action"] == "get_all_channels" })
        }
    }

    @Test fun stalkerNonemptyGlobalListKeepsItsCacheForAllGenresAndSearch() {
        val channels = (1..61).joinToString(",") { """{"id":"$it","name":"Live $it","tv_genre_id":"${if (it == 61) 9 else 10}"}""" }
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_all_channels" -> """{"js":{"data":[$channels]}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val first = provider.items(ContentType.LIVE, "")
            assertEquals(60, first.items.size)
            assertEquals(1, first.nextPage)
            val second = provider.items(ContentType.LIVE, "*", 1)
            assertEquals("61", second.items.single().providerId)
            assertNull(second.nextPage)
            assertEquals("61", provider.items(ContentType.LIVE, "9").items.single().providerId)
            assertEquals("61", provider.search("Live 61", ContentType.LIVE).items.single().providerId)
            assertEquals(1, fixture.calls.count { it.query["action"] == "get_all_channels" })
            assertTrue(fixture.calls.none { it.query["action"] == "get_ordered_list" })
        }
    }

    @Test fun stalkerLiveFallbackStopsWhenThePortalRepeatsAPage() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> """{"js":{"data":[{"id":"7","name":"Live"}],"total_items":9742,"max_page_items":1}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val pages = mutableListOf<IptvPage>()
            provider.loadCatalogue(ContentType.LIVE, "") { _, page -> pages.add(page) }
            assertEquals(2, pages.size)
            assertNull(pages.last().nextPage)
            assertEquals(listOf("1", "2"), fixture.calls.filter { it.query["action"] == "get_ordered_list" }.map { it.query["p"] })
        }
    }

    @Test fun stalkerLiveFallbackKeepsHttpRefusalsVisible() {
        listOf("get_all_channels", "get_ordered_list").forEach { deniedAction ->
            Fixture { call -> if (call.query["action"] == deniedAction) 403 to "private-response" else 200 to when (call.query["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                else -> """{"js":[]}"""
            } }.use { fixture ->
                val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
                val failure = assertThrows(IptvProviderException::class.java) { provider.items(ContentType.LIVE, "") }
                assertTrue(failure.message.orEmpty().contains("HTTP 403"))
                assertFalse(companionImportFailureMessage(SourceType.STALKER, failure).contains("private-response"))
                if (deniedAction == "get_all_channels") assertTrue(fixture.calls.none { it.query["action"] == "get_ordered_list" })
            }
        }
    }

    @Test fun stalkerLiveFallbackRetriesAnEmptyPageOnTheNextRequest() {
        var lists = 0
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> if (++lists == 1) """{"js":{"data":[],"total_items":0}}"""
                else """{"js":{"data":[{"id":"7","name":"Live"}],"total_items":1,"max_page_items":1}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val first = provider.items(ContentType.LIVE, "")
            assertTrue(first.items.isEmpty())
            assertNull(first.nextPage)
            assertEquals("7", provider.items(ContentType.LIVE, "").items.single().providerId)
            assertEquals(2, lists)
            assertEquals(1, fixture.calls.count { it.query["action"] == "get_all_channels" })
        }
    }

    @Test fun stalkerStopsCrossHostRedirectsBeforeSendingPortalCredentials() {
        listOf("handshake", "get_profile", "get_genres").forEach { redirectedAction ->
            Fixture { 200 to "<html>Different website</html>" }.use { other ->
                Fixture { call -> when (call.query["action"]) {
                    redirectedAction -> 301 to "redirect"
                    "handshake" -> 200 to """{"js":{"token":"private-token"}}"""
                    else -> 200 to """{"js":{}}"""
                } }.use { fixture ->
                    fixture.responseHeaders = { call -> if (call.query["action"] == redirectedAction)
                        mapOf("Location" to other.url.replace("127.0.0.1", "localhost") + "/website") else emptyMap() }
                    val source = IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/c/", mac = "00:1A:79:00:00:01")
                    val provider = providerFor(source)
                    val failure = assertThrows(IptvProviderException::class.java) {
                        provider.testConnection()
                        if (redirectedAction == "get_genres") provider.categories(ContentType.LIVE)
                    }
                    val message = companionImportFailureMessage(source.type, failure)
                    assertTrue(message.contains("redirige"))
                    assertTrue(message.contains("DNS"))
                    assertFalse(message.contains("private-token"))
                    assertFalse(message.contains(source.mac))
                    assertTrue(other.calls.isEmpty())
                    assertEquals(1, fixture.calls.count { it.query["action"] == "handshake" })
                }
            }
        }
    }

    @Test fun stalkerKeepsSameHostRedirectsAndAuthenticatedProfile() {
        Fixture { call -> when {
            call.path == "/portal.php" -> 302 to "redirect"
            call.query["action"] == "handshake" -> 200 to """{"js":{"token":"fixture-token"}}"""
            else -> 200 to """{"js":{"blocked":0}}"""
        } }.use { fixture ->
            fixture.responseHeaders = { call -> if (call.path == "/portal.php") {
                val query = call.query.entries.joinToString("&") {
                    "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                }
                mapOf("Location" to "/server/load.php?$query")
            } else emptyMap() }
            val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            assertEquals("Session du portail ouverte", provider.testConnection())
            val profile = fixture.calls.last { it.path == "/server/load.php" && it.query["action"] == "get_profile" }
            assertEquals("Bearer fixture-token", profile.authorization)
            assertTrue(profile.cookie.contains("portal_session=fixture"))
        }
    }

    @Test fun stalkerReportsHtmlWithoutReturningTheResponsePayload() {
        listOf("<html>private-account-value</html>", "\n  <!DOCTYPE html><html>private-account-value</html>").forEach { html ->
            Fixture { 200 to html }.use { fixture ->
                val source = IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01")
                val failure = assertThrows(IptvProviderException::class.java) { providerFor(source).testConnection() }
                val message = companionImportFailureMessage(source.type, failure)
                assertTrue(message.contains("page web"))
                assertFalse(message.contains("private-account-value"))
            }
        }
    }

    @Test fun stalkerReportsInvalidJsonWithoutReturningTheResponsePayload() {
        Fixture { 200 to "{\"js\":\"private-account-value" }.use { fixture ->
            val source = IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01")
            val failure = assertThrows(IptvProviderException::class.java) { providerFor(source).testConnection() }
            assertEquals("Le portail IPTV a envoyé une réponse JSON invalide.", companionImportFailureMessage(source.type, failure))
            assertFalse(failure.message.orEmpty().contains("private-account-value"))
        }
    }

    @Test fun stalkerReportsAnEmptyResponseClearly() {
        Fixture { 200 to "" }.use { fixture ->
            val source = IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01")
            val failure = assertThrows(IptvProviderException::class.java) { providerFor(source).testConnection() }
            assertEquals("Le portail IPTV a envoyé une réponse vide.", companionImportFailureMessage(source.type, failure))
        }
    }

    @Test fun xtreamPublishesEveryContentTypeBeforeTheResponseFinishesAndReusesTheCompletedCatalogue() {
        listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).forEach { type ->
            val idKey = if (type == ContentType.SERIES) "series_id" else "stream_id"
            val body = (1..121).joinToString(",", "[", "]") {
                """{"$idKey":$it,"name":"Content $it","info":{"plot":"${"x".repeat(1024)}"}}"""
            }
            val split = body.indexOf("""{"$idKey":81,""")
            val firstPage = java.util.concurrent.CountDownLatch(1)
            val early = java.util.concurrent.atomic.AtomicBoolean(false)
            Fixture { 200 to body }.apply {
                writeBody = { bytes, output ->
                    output.write(bytes, 0, split); output.flush()
                    early.set(firstPage.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    output.write(bytes, split, bytes.size - split)
                }
            }.use { fixture ->
                val provider = providerFor(IptvSource("x", "Xtream", SourceType.XTREAM, fixture.url, "user", "password"))
                val pages = mutableListOf<IptvPage>()
                provider.loadCatalogue(type, "") { index, page ->
                    assertEquals(pages.size, index)
                    pages.add(page)
                    if (index == 0) firstPage.countDown()
                }
                assertTrue("First page must precede the end of the HTTP body for $type", early.get())
                assertEquals(listOf(60, 60, 1), pages.map { it.items.size })
                assertEquals(listOf(1, 2, null), pages.map { it.nextPage })
                assertEquals((1..121).map(Int::toString), pages.flatMap { it.items }.map { it.providerId })
                val cached = mutableListOf<IptvPage>()
                provider.loadCatalogue(type, "") { _, page -> cached.add(page) }
                assertEquals(pages, cached)
                assertEquals(1, fixture.calls.size)
            }
        }
    }

    @Test fun cancelledProgressiveCatalogueCannotLeaveAPartialProviderCache() {
        val body = (1..121).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Film $it"}""" }
        Fixture { 200 to body }.use { fixture ->
            val provider = providerFor(IptvSource("x", "Xtream", SourceType.XTREAM, fixture.url, "user", "password"))
            val scope = IptvRequestScope(IptvHttp.client)
            var published = 0
            assertThrows(java.io.InterruptedIOException::class.java) {
                IptvHttp.withScope(scope) {
                    provider.loadCatalogue(ContentType.MOVIE, "") { _, page ->
                        published += page.items.size
                        scope.cancel()
                    }
                }
            }
            assertEquals(60, published)
            val loaded = mutableListOf<IptvItem>()
            provider.loadCatalogue(ContentType.MOVIE, "") { _, page -> loaded.addAll(page.items) }
            assertEquals(121, loaded.size)
            assertEquals(2, fixture.calls.size)
        }
    }

    @Test fun progressiveCatalogueHandlesEmptyAndExactPageBoundariesWithoutLosingContents() {
        listOf(0, 1, 60, 61, 120, 121).forEach { size ->
            val items = (1..size).map { IptvItem("id-$it", "x", "$it", ContentType.MOVIE, "Film $it") }
            val pages = mutableListOf<IptvPage>()
            var consumed = 0
            val source = sequence {
                items.forEach { item ->
                    if (consumed == 60) assertEquals(60, pages.first().items.size)
                    consumed++; yield(item)
                }
            }
            assertEquals(items, collectCataloguePages(source) { index, page ->
                assertEquals(pages.size, index); pages.add(page)
            })
            assertEquals(items, pages.flatMap { it.items })
            assertTrue(pages.all { it.items.size <= 60 })
            assertEquals(1, pages.count { it.nextPage == null })
            assertNull(pages.last().nextPage)
            assertEquals(size, pages.last().total)
            assertTrue(pages.dropLast(1).all { it.total == null })
        }
    }

    @Test fun playlistCountsUseCompleteTotalsInsteadOfTheFirstVisiblePage() {
        val items = (1..121).map { IptvItem("id-$it", "x", "$it", ContentType.MOVIE, "Film $it") }
        val page = searchPage(items, "", 0)
        assertEquals(60, page.items.size)
        assertEquals(121, page.total)
        assertEquals(121, searchPage(items, "", 2).total)
        assertNull(searchPage(items, "Film 12", 0).total)
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> """{"js":{"total_items":1200,"max_page_items":1,"data":[{"id":"1","name":"Film"}]}}"""
            else -> """{"js":{}}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Test", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val first = provider.items(ContentType.MOVIE, "", 0)
            assertEquals(1, first.items.size)
            assertEquals(1200, first.total)
            assertEquals(1, first.nextPage)
        }
    }

    @Test fun categoryCacheMetadataSurvivesSerialization() {
        val categories = listOf(IptvCategory("", "Tous les films"), IptvCategory("12", "Films français"))
        assertEquals(categories, iptvJson.decodeFromString<List<IptvCategory>>(iptvJson.encodeToString(categories)))
    }

    @Test fun defaultCatalogueStopsRepeatedPagesAndPreservesLegacyProviderPagination() {
        val item = IptvItem("id", "x", "1", ContentType.LIVE, "Channel")
        val requests = mutableListOf<Int>()
        val provider = object : IptvProvider {
            override fun testConnection() = "OK"
            override fun categories(type: ContentType) = emptyList<IptvCategory>()
            override fun resolve(item: IptvItem): IptvStream = error("Unused in this test")
            override fun items(type: ContentType, category: String, page: Int): IptvPage {
                requests.add(page)
                return IptvPage(listOf(item), page + 1)
            }
        }
        val pages = mutableListOf<IptvPage>()
        provider.loadCatalogue(ContentType.LIVE, "") { _, page -> pages.add(page) }
        assertEquals(listOf(0, 1), requests)
        assertEquals(1, pages.first().nextPage)
        assertNull(pages.last().nextPage)
    }

    @Test fun xtreamSearchRefinesTheCachedCatalogueAndPaginatesOnlyMatches() {
        Fixture { 200 to (1..121).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"Film $it","info":{"movie_image":"/poster.jpg"}}""" } }.use { fixture ->
            val provider = providerFor(IptvSource("x", "Xtream", SourceType.XTREAM, fixture.url, "user", "password"))
            val first = provider.search("f", ContentType.MOVIE)
            assertEquals(60, first.items.size)
            assertEquals(1, first.nextPage)
            val second = provider.search("F", ContentType.MOVIE, 1)
            assertEquals(60, second.items.size)
            assertEquals(2, second.nextPage)
            val last = provider.search("f", ContentType.MOVIE, 2)
            assertEquals(1, last.items.size)
            assertNull(last.nextPage)
            assertEquals(121, (first.items + second.items + last.items).distinctBy { it.id }.size)
            val refined = provider.search("film 12", ContentType.MOVIE)
            assertEquals(listOf("Film 12", "Film 120", "Film 121"), refined.items.map { it.title })
            assertNull(refined.nextPage)
            assertEquals("${fixture.url}/poster.jpg", refined.items.first().artwork)
            assertTrue(refined.items.all { it.sourceId == "x" })
            assertEquals(1, fixture.calls.size)
            val broader = provider.search("film 1", ContentType.MOVIE)
            assertEquals(33, broader.items.size)
            assertEquals(2, fixture.calls.size)
            val different = provider.search("film 2", ContentType.MOVIE)
            assertEquals(11, different.items.size)
            assertEquals(3, fixture.calls.size)
        }
    }

    @Test fun stalkerVodCataloguesSelectTheRootForAllPagesAndGroups() {
        listOf(ContentType.MOVIE, ContentType.SERIES).forEach { type ->
            Fixture { call -> 200 to when (call.query["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_categories" -> """{"js":[{"id":"9","title":"Catalogue"}]}"""
                "get_ordered_list" -> when {
                    listOf("movie_id", "season_id", "episode_id").any { call.query[it] != "0" } ->
                        """{"js":{"data":[],"total_items":0}}"""
                    call.query["category"] == "9" ->
                        """{"js":{"data":[{"id":"3","name":"Third","category_id":"9"}],"total_items":1,"max_page_items":2}}"""
                    call.query["p"] == "1" ->
                        """{"js":{"data":[{"id":"1","name":"First"},{"id":"2","name":"Second"}],"total_items":3,"max_page_items":2}}"""
                    else -> """{"js":{"data":[{"id":"3","name":"Third","category_id":"9"}],"total_items":3,"max_page_items":2}}"""
                }
                else -> """{"js":[]}"""
            } }.use { fixture ->
                val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
                assertEquals("9", provider.categories(type).single().id)
                val catalogue = mutableListOf<IptvItem>()
                provider.loadCatalogue(type, "") { _, batch -> catalogue += batch.items }
                assertEquals(listOf("1", "2", "3"), catalogue.map { it.providerId })
                assertTrue(catalogue.all { it.type == type })
                assertEquals(listOf("3"), provider.items(type, "9").items.map { it.providerId })
                val calls = fixture.calls.filter { it.query["action"] == "get_ordered_list" }
                assertEquals(listOf("1", "2", "1"), calls.map { it.query["p"] })
                assertEquals(listOf("*", "*", "9"), calls.map { it.query["category"] })
                assertTrue(calls.all { it.query["type"] == if (type == ContentType.MOVIE) "vod" else "series" })
            }
        }
    }

    @Test fun stalkerVodSearchSelectsTheRootAndKeepsItsQueryOnEveryPage() {
        listOf(ContentType.MOVIE, ContentType.SERIES).forEach { type ->
            Fixture { call -> 200 to when (call.query["action"]) {
                "handshake" -> """{"js":{"token":"fixture"}}"""
                "get_profile" -> """{"js":{}}"""
                "get_ordered_list" -> if (listOf("movie_id", "season_id", "episode_id").all { call.query[it] == "0" })
                    """{"js":{"data":[{"id":"${call.query["p"]}","name":"Série ${call.query["p"]}"}],"total_items":2,"max_page_items":1}}"""
                    else """{"js":{"data":[],"total_items":0}}"""
                else -> """{"js":[]}"""
            } }.use { fixture ->
                val provider = providerFor(IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
                val first = provider.search("Série", type)
                assertEquals(listOf("1"), first.items.map { it.providerId })
                assertEquals(1, first.nextPage)
                val last = provider.search("Série", type, first.nextPage!!)
                assertEquals(listOf("2"), last.items.map { it.providerId })
                assertNull(last.nextPage)
                val calls = fixture.calls.filter { it.query["action"] == "get_ordered_list" }
                assertEquals(listOf("1", "2"), calls.map { it.query["p"] })
                assertTrue(calls.all { it.query["category"] == "*" && it.query["search"] == "Série" })
            }
        }
    }

    @Test fun stalkerSearchKeepsEachTypesPaginationAndAuthenticatedArtwork() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> if (call.query["p"] == "1")
                """{"js":{"data":[{"id":"1","name":"Test one","logo":"/logo.jpg","screenshot_uri":"/fallback.jpg"},{"id":"2","name":"Test two"}],"total_items":3,"max_page_items":2}}"""
                else """{"js":{"data":[{"id":"3","name":"Test three"}],"total_items":3,"max_page_items":2}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val source = IptvSource("s", "Stalker", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01")
            val provider = providerFor(source)
            val movies = provider.search("t", ContentType.MOVIE)
            assertEquals(1, movies.nextPage)
            assertEquals("${fixture.url}/logo.jpg", movies.items.first().artwork)
            assertEquals("${fixture.url}/fallback.jpg", movies.items.first().backdrop)
            assertEquals("Bearer fixture", artworkHeaders(movies.items.first().artwork, source, movies.items.first().headers)["Authorization"])
            assertNull(provider.search("test", ContentType.MOVIE, 1).nextPage)
            assertEquals(ContentType.SERIES, provider.search("t", ContentType.SERIES).items.first().type)
            val calls = fixture.calls.filter { it.query["action"] == "get_ordered_list" }
            assertEquals(listOf("vod", "vod", "series"), calls.map { it.query["type"] })
            assertEquals(listOf("t", "test", "t"), calls.map { it.query["search"] })
            assertEquals(listOf("1", "2", "1"), calls.map { it.query["p"] })
        }
    }

    @Test fun cancellingASearchInterruptsHttpWithoutCancellingTheNextRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        Fixture { call ->
            if (call.path == "/slow") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            200 to "[]"
        }.use { fixture ->
            val scope = IptvRequestScope(IptvHttp.searchClient)
            try {
                val pending = executor.submit<Any> { IptvHttp.withScope(scope) { IptvHttp.json("${fixture.url}/slow".toHttpUrl(), emptyMap()) } }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                scope.cancel()
                val error = assertThrows(ExecutionException::class.java) { pending.get(2, TimeUnit.SECONDS) }
                assertTrue(error.cause is java.io.IOException)
                release.countDown()
                val next = executor.submit<Any> { IptvHttp.json("${fixture.url}/fresh".toHttpUrl(), emptyMap()) }
                assertNotNull(next.get(3, TimeUnit.SECONDS))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun artworkRetryPolicyRejectsPermanentFailuresAndNeverWeakensTls() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue(artworkRetryable(IptvHttpException(it))) }
        listOf(400, 401, 403, 404, 410).forEach { assertFalse(artworkRetryable(IptvHttpException(it))) }
        assertTrue(artworkRetryable(java.net.SocketTimeoutException()))
        assertTrue(artworkRetryable(java.net.UnknownHostException()))
        assertFalse(artworkRetryable(javax.net.ssl.SSLHandshakeException("fixture")))
        assertFalse(artworkRetryable(javax.net.ssl.SSLPeerUnverifiedException("fixture")))
        assertFalse(artworkRetryable(java.net.ProtocolException("fixture")))
        assertFalse(artworkRetryable(IllegalStateException("fixture")))
        assertFalse(artworkRetryable(null))
    }

    @Test fun transientArtworkHttpFailureCanRecoverWithoutChangingTheProviderUrl() {
        var requests = 0
        Fixture { if (++requests == 1) 503 to "temporarily unavailable" else 200 to "poster-fixture" }.use { fixture ->
            val url = "${fixture.url}/bad-apples.jpg".toHttpUrl()
            val failed = runCatching { IptvHttp.withScope(IptvRequestScope(IptvHttp.artworkClient)) {
                IptvHttp.read(url, emptyMap()) { it.string() }
            } }
            assertEquals(503, (failed.exceptionOrNull() as IptvHttpException).code)
            assertTrue(artworkRetryable(failed.exceptionOrNull()))
            val recovered = IptvHttp.withScope(IptvRequestScope(IptvHttp.artworkRetryClient)) {
                IptvHttp.read(url, emptyMap()) { it.string() }
            }
            assertEquals("poster-fixture", recovered)
            assertEquals(listOf("/bad-apples.jpg", "/bad-apples.jpg"), fixture.calls.map { it.path })
        }
    }

    @Test fun slowArtworkGetsOneLongerBoundedAttemptWithoutSlowingOtherFirstLoads() {
        val bodyWrites = java.util.concurrent.atomic.AtomicInteger()
        Fixture { 200 to "poster-fixture" }.use { fixture ->
            fixture.writeBody = { bytes, output ->
                if (bodyWrites.incrementAndGet() == 1) Thread.sleep(350)
                output.write(bytes)
            }
            val fast = IptvHttp.artworkClient.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS)
                .callTimeout(250, TimeUnit.MILLISECONDS).build()
            val url = "${fixture.url}/slow-poster.jpg".toHttpUrl()
            val first = runCatching { IptvHttp.withScope(IptvRequestScope(fast)) { IptvHttp.read(url, emptyMap()) { it.string() } } }
            assertTrue(first.isFailure)
            assertTrue(artworkRetryable(first.exceptionOrNull()))
            assertEquals("poster-fixture", IptvHttp.withScope(IptvRequestScope(IptvHttp.artworkRetryClient)) {
                IptvHttp.read(url, emptyMap()) { it.string() }
            })
            assertEquals(8000, IptvHttp.artworkClient.callTimeoutMillis)
            assertEquals(15000, IptvHttp.artworkRetryClient.callTimeoutMillis)
            assertEquals(2, fixture.calls.size)
        }
    }

    @Test fun cancelledArtworkScopeCannotSendItsDelayedRetry() {
        Fixture { 503 to "temporarily unavailable" }.use { fixture ->
            val scope = IptvRequestScope(IptvHttp.artworkClient)
            val url = "${fixture.url}/poster.jpg".toHttpUrl()
            val first = runCatching { IptvHttp.withScope(scope) { IptvHttp.read(url, emptyMap()) { it.string() } } }
            assertTrue(artworkRetryable(first.exceptionOrNull()))
            scope.cancel()
            assertThrows(java.io.IOException::class.java) {
                IptvHttp.withScope(scope) { IptvHttp.read(url, emptyMap()) { it.string() } }
            }
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test fun artworkHttpErrorsKeepTheirStatusButDoNotExposeTheRequestOrBody() {
        for (code in listOf(401, 403, 404, 503)) Fixture { code to "private-response" }.use { fixture ->
            val failure = assertThrows(IptvHttpException::class.java) {
                IptvHttp.withScope(IptvRequestScope(IptvHttp.artworkClient)) {
                    IptvHttp.read("${fixture.url}/private-poster?token=private-token".toHttpUrl(), emptyMap()) { it.string() }
                }
            }
            assertEquals(code, failure.code)
            assertTrue(failure.message.orEmpty().contains("HTTP $code"))
            assertFalse(failure.message.orEmpty().contains("private"))
            assertFalse(failure.message.orEmpty().contains(fixture.url))
        }
    }

    @Test fun artworkHeadersDoNotLeakPortalCredentialsToAnotherHost() {
        val source = IptvSource("s", "Test", SourceType.STALKER, "https://portal.example.test/c/")
        val headers = mapOf("Authorization" to "Bearer fixture", "Cookie" to "mac=fixture", "User-Agent" to "TV", "X-Portal-Token" to "fixture")
        assertEquals(headers, artworkHeaders("https://portal.example.test/poster.jpg", source, headers))
        assertEquals(mapOf("User-Agent" to "TV"), artworkHeaders("https://images.example.test/poster.jpg", source, headers))
        assertEquals(mapOf("User-Agent" to "TV"), artworkHeaders("invalid", source, headers))
    }

    @Test fun rejectedXtreamAccountFailsWithoutReturningCredentials() {
        Fixture { 200 to """{"user_info":{"auth":0,"status":"Expired"}}""" }.use { fixture ->
            val provider = providerFor(IptvSource("x", "Test", SourceType.XTREAM, fixture.url, "private-user", "private-password"))
            val error = assertThrows(IllegalStateException::class.java) { provider.testConnection() }
            assertFalse(error.message.orEmpty().contains("private-password"))
        }
    }
    @Test fun stalkerReadsPaginatedSeasonsAndUsesEachEpisodesOwnCommand() {
        Fixture { call -> 200 to when (call.query["action"]) {
            "handshake" -> """{"js":{"token":"fixture-token"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_ordered_list" -> when {
                call.query["movie_id"] != "10" || call.query["season_id"] == null || call.query["episode_id"] != "0" ->
                    """{"js":{"data":[],"total_items":0}}"""
                call.query["season_id"] == "11" -> """{"js":{"data":[{"id":"101","name":"Épisode","series_number":4,"cmd":"ffmpeg http://localhost/unique-episode"}],"total_items":1}}"""
                call.query["season_id"] == "12" -> """{"js":{"data":[],"total_items":0}}"""
                call.query["p"] == "1" -> """{"js":{"data":[{"id":"11","name":"Season 2"}],"total_items":2}}"""
                else -> """{"js":{"data":[{"id":"12","name":"Season 3"}],"total_items":2}}"""
            }
            "create_link" -> """{"js":{"cmd":"ffmpeg https://media.example.test/episode.mp4"}}"""
            else -> """{"js":[]}"""
        } }.use { fixture ->
            val provider = providerFor(IptvSource("s", "Test", SourceType.STALKER, "${fixture.url}/portal.php", mac = "00:1A:79:00:00:01"))
            val series = IptvItem("series-id", "s", "10", ContentType.SERIES, "Série")
            val episode = provider.episodes(series).single()
            assertEquals(2, episode.season)
            assertEquals(4, episode.episode)
            provider.resolve(episode)
            assertEquals("ffmpeg http://localhost/unique-episode", fixture.calls.last { it.query["action"] == "create_link" }.query["cmd"])
            assertTrue(fixture.calls.any { it.query["movie_id"] == "10" && it.query["p"] == "2" })
            val calls = fixture.calls.filter { it.query["action"] == "get_ordered_list" }
            assertEquals(listOf("0", "0", "11", "12"), calls.map { it.query["season_id"] })
            assertTrue(calls.all { it.query["movie_id"] == "10" && it.query["episode_id"] == "0" })
        }
    }
}
