package com.jojotv.home.iptv

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class IptvRecordingStreamTest {
    private class NetworkStorage : RecordingStorage {
        override val directory = "smb://nas.test/recordings/test"
        private val files = mutableMapOf<String, ByteArray>()
        override fun create() {}
        override fun writable() = true
        override fun output(name: String) = object : java.io.ByteArrayOutputStream() {
            override fun flush() { files[name] = toByteArray() }
            override fun close() { flush() }
        }
        override fun input(name: String, offset: Long) = files.getValue(name).inputStream().apply { skip(offset) }
        override fun length(name: String) = files[name]?.size?.toLong() ?: 0
        override fun bytes() = files.values.sumOf { it.size.toLong() }
        override fun freeBytes() = 1024 * 1024 * 1024L
        override fun trimTransportStream() { files["recording.ts"]?.let { files["recording.ts"] = it.copyOf(it.size / 188 * 188) } }
        override fun writeManifest(lines: List<String>) { files["recording.m3u8"] = lines.joinToString("\n", postfix = "\n").toByteArray() }
        override fun delete() { files.clear() }
    }

    @Test fun hlsCaptureCreatesPlayableLocalManifestWithoutAccountUrls() {
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            var playlistReads = 0
            val worker = thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val body = if (path.startsWith("/live.m3u8")) {
                            playlistReads++
                            ("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:8\n#EXTINF:0.1,\n/segment8.ts?token=private\n" +
                                if (playlistReads > 1) "#EXTINF:0.1,\n/segment9.ts?token=private\n" else "").toByteArray()
                        } else ByteArray(188).apply { this[0] = 0x47 }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        }
                    }
                }
            }
            val folder = kotlin.io.path.createTempDirectory("iptv-hls-test").toFile()
            try {
                val file = IptvRecordingStream().capture(IptvStream("http://127.0.0.1:${server.localPort}/live.m3u8", emptyMap()), folder, System.currentTimeMillis() + 1300) { }
                val manifest = file.readText()
                assertTrue(manifest.contains("#EXT-X-ENDLIST"))
                assertTrue(manifest.contains("part-0.ts"))
                assertTrue(manifest.contains("part-1.ts"))
                assertFalse(manifest.contains("token"))
                assertFalse(manifest.contains("http"))
                assertEquals(188L, File(folder, "part-1.ts").length())
            } finally { server.close(); worker.join(1000); folder.deleteRecursively() }
        }
    }
    @Test fun slidingPlaylistKeepsSequenceEncryptionAndDiscontinuity() {
        val playlist = RecordingHlsParser.parse("https://example.test/live/index.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:31
            #EXT-X-KEY:METHOD=AES-128,URI="/key",IV=0x0000000000000000000000000000001f
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.5,
            part1.m4s?token=temporary
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:5,
            part2.m4s
        """.trimIndent())
        assertEquals(listOf(31L, 32L), playlist.segments.map { it.sequence })
        assertEquals("https://example.test/key", playlist.segments.first().key)
        assertEquals("https://example.test/live/init.mp4", playlist.segments.last().init)
        assertTrue(playlist.segments.last().discontinuity)
        assertEquals("", playlist.segments.last().key)
    }
    @Test fun masterAndUnsupportedLayoutsAreExplicit() {
        assertEquals("https://example.test/variant.m3u8", RecordingHlsParser.parse("https://example.test/master.m3u8",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1234\nvariant.m3u8").variant)
        assertThrows(IllegalStateException::class.java) {
            RecordingHlsParser.parse("https://example.test/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"audio\"\nvariant.m3u8")
        }
        assertThrows(IllegalStateException::class.java) {
            RecordingHlsParser.parse("https://example.test/live.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"10@0\"")
        }
    }
    @Test fun aesWithoutIvUsesMediaSequence() {
        val plain = "recorded segment".toByteArray()
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16).apply { this[15] = 7 }
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(plain)
        assertArrayEquals(plain, RecordingHlsParser.decrypt(encrypted, key, RecordingSegment(7, "url", 4.0)))
    }
    @Test fun networkDestinationCapturesRelativeHlsSegmentsWithoutLocalFiles() {
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val body = if (path == "/live.m3u8") "#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n#EXTINF:0.1,\n/part.m4s?private=account\n".toByteArray()
                            else ByteArray(188) { 1 }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        }
                    }
                }
            }
            try {
                val destination = NetworkStorage()
                val path = IptvRecordingStream().capture(IptvStream("http://127.0.0.1:${server.localPort}/live.m3u8", emptyMap()),
                    destination, System.currentTimeMillis() + 800) {}
                assertEquals("${destination.directory}/recording.m3u8", path)
                val manifest = destination.input("recording.m3u8").bufferedReader().readText()
                assertTrue(manifest.contains("#EXT-X-MAP:URI=\"init-0.mp4\""))
                assertTrue(manifest.contains("part-0.m4s"))
                assertTrue(manifest.contains("#EXT-X-ENDLIST"))
                assertFalse(manifest.contains("http")); assertFalse(manifest.contains("account"))
                assertEquals(188L, destination.length("part-0.m4s"))
                assertEquals(188L, destination.length("init-0.mp4"))
            } finally { server.close(); worker.join(1000) }
        }
    }
    @Test fun recordingPlaybackServesSeekRangesAndRelativeSegmentsOnly() {
        val folder = kotlin.io.path.createTempDirectory("iptv-playback-test").toFile()
        val playback = IptvRecordingPlayback(LocalRecordingStorage(folder))
        try {
            File(folder, "recording.ts").writeBytes(ByteArray(1880) { (it % 128).toByte() })
            File(folder, "part-0.ts").writeBytes(ByteArray(188))
            File(folder, "recording.m3u8").writeText("#EXTM3U\n#EXTINF:1,\npart-0.ts\n#EXT-X-ENDLIST\n")
            playback.start(5000, true)
            val request = java.net.URL(playback.url("recording.ts")).openConnection() as java.net.HttpURLConnection
            request.setRequestProperty("Range", "bytes=188-375")
            assertEquals(206, request.responseCode)
            assertEquals("bytes 188-375/1880", request.getHeaderField("Content-Range"))
            assertEquals(188, request.inputStream.use { it.readBytes().size }); request.disconnect()
            val manifestUrl = java.net.URL(playback.url("recording.m3u8"))
            assertTrue(manifestUrl.readText().contains("part-0.ts"))
            assertEquals(188, java.net.URL(manifestUrl, "part-0.ts").readBytes().size)
            val invalid = java.net.URL(playback.url("manifest.tmp")).openConnection() as java.net.HttpURLConnection
            assertEquals(404, invalid.responseCode); invalid.disconnect()
        } finally { playback.stop(); folder.deleteRecursively() }
    }
    @Test fun playbackRejectsTraversalAndInvalidSeekRanges() {
        assertFalse(IptvRecordingPlayback.playbackFile("../recording.ts"))
        assertFalse(IptvRecordingPlayback.playbackFile("part-0.ts/other"))
        assertFalse(IptvRecordingPlayback.playbackFile("manifest.tmp"))
        assertEquals(100L..199L, IptvRecordingPlayback.playbackRange("bytes=100-", 200))
        assertEquals(150L..199L, IptvRecordingPlayback.playbackRange("bytes=-50", 200))
        assertNull(IptvRecordingPlayback.playbackRange("bytes=200-", 200))
        assertNull(IptvRecordingPlayback.playbackRange("bytes=20-10", 200))
        assertNull(IptvRecordingPlayback.playbackRange("bytes=0-1,5-6", 200))
        assertNull(IptvRecordingPlayback.playbackRange("bytes=9999999999999999999999-", 200))
    }
    @Test fun offlineSharePreservesKnownPartialRecordingInsteadOfLosingItsLocation() {
        val record = IptvRecording("12345678-1234-1234-1234-123456789abc",
            IptvItem("channel", "source", "provider", ContentType.LIVE, "TV"), "Programme", 1, 2,
            state = RecordingState.RUNNING, bytes = 2_000_000, file = "smb://nas.test/recordings/id/recording.ts", directory = "smb://nas.test/recordings")
        val recovered = IptvRecordingScheduler.recovered(record, "Stockage indisponible.") { throw java.io.IOException("Share offline") }
        assertEquals(RecordingState.PARTIAL, recovered.state)
        assertEquals(record.file, recovered.file)
        assertEquals(record.directory, recovered.directory)
        assertEquals(record.bytes, recovered.bytes)
        assertEquals("Stockage indisponible.", recovered.error)
    }
    @Test fun transportStreamIsSavedUntilDeadlineWithoutBufferingWholeStream() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                        runCatching { repeat(100) { out.write(ByteArray(188 * 100).apply { indices.filter { it % 188 == 0 }.forEach { this[it] = 0x47 } }); out.flush(); Thread.sleep(20) } }
                    }
                }
            }
            val folder = kotlin.io.path.createTempDirectory("iptv-recording-test").toFile()
            try {
                val file = IptvRecordingStream().capture(IptvStream("http://127.0.0.1:${server.localPort}/live.ts", emptyMap()), folder, System.currentTimeMillis() + 400) { }
                assertTrue(file.length() > 0)
                assertEquals(0x47, file.inputStream().use { it.read() })
                assertEquals(0L, file.length() % 188)
            } finally { folder.deleteRecursively(); worker.join(2500) }
        }
    }

    @Test fun transportStreamCanWriteDirectlyToNetworkStorageAndTrimItsLastPacket() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                        var sent = 0
                        runCatching { repeat(100) {
                            val bytes = ByteArray(188 * 100 + 7) { if ((sent + it) % 188 == 0) 0x47.toByte() else 0 }
                            out.write(bytes); sent += bytes.size; out.flush(); Thread.sleep(20)
                        } }
                    }
                }
            }
            val storage = NetworkStorage()
            try {
                val path = IptvRecordingStream().capture(IptvStream("http://127.0.0.1:${server.localPort}/live.ts", emptyMap()),
                    storage, System.currentTimeMillis() + 400) {}
                assertEquals("${storage.directory}/recording.ts", path)
                assertTrue(storage.length("recording.ts") > 0)
                assertEquals(0x47, storage.input("recording.ts").use { it.read() })
                assertEquals(0L, storage.length("recording.ts") % 188)
                assertTrue(storage.input("recording.ts").use { it.readBytes() }.filterIndexed { index, _ -> index % 188 == 0 }.all { it == 0x47.toByte() })
            } finally { worker.join(2500) }
        }
    }
}
