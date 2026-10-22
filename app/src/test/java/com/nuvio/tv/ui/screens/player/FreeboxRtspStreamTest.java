package com.nuvio.tv.ui.screens.player;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class FreeboxRtspStreamTest {
    @Test(timeout = 5000)
    public void playsUdpMpegTsWithoutChangingThePlaylistUrl() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            FreeboxRtspStream stream = new FreeboxRtspStream();
            try {
                stream.open(server.url(), "JojoHome test");
                byte[] ts = transportPackets(2);
                server.send(rtp(ts, 33, false));
                assertArrayEquals(ts, readFully(stream, ts.length, 37));
                assertTrue(server.requests.contains("DESCRIBE " + server.url()));
                assertTrue(server.requests.contains("SETUP " + server.url()));
                assertTrue(server.requests.contains("PLAY " + server.url()));
                assertTrue(server.transport.startsWith("RTP/AVP;unicast;client_port="));
                assertEquals(0, server.clientPort % 2);
                assertEquals(server.clientPort + 1, server.clientRtcpPort);
            } finally {
                stream.close();
                stream.close();
            }
            assertTrue(server.teardown.await(1, TimeUnit.SECONDS));
            assertNull(server.failure.get());
        }
    }

    @Test(timeout = 5000)
    public void acceptsTheFreeboxSourcePortWhenItDiffersFromTheAdvertisedPort() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            server.advertisedPortOffset = 2;
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                stream.open(server.url(), "JojoHome");
                byte[] ts = transportPackets(1);
                server.send(rtp(ts, 33, false));
                assertArrayEquals(ts, readFully(stream, ts.length, 2048));
            }
        }
    }

    @Test(timeout = 5000)
    public void stripsCsrcExtensionAndPaddingFromRtp() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                stream.open(server.url(), "JojoHome");
                byte[] ts = transportPackets(7);
                server.send(rtp(ts, 33, true));
                assertArrayEquals(ts, readFully(stream, ts.length, 256));
            }
        }
    }

    @Test(timeout = 5000)
    public void ignoresWrongPayloadsAndMalformedPackets() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                stream.open(server.url(), "JojoHome");
                byte[] ts = transportPackets(2);
                server.send(rtp(ts, 96, false));
                server.send(new byte[] {(byte) 0x80, (byte) 200, 0, 0});
                byte[] wrongSync = rtp(ts, 33, false);
                wrongSync[12] = 0;
                server.send(wrongSync);
                server.send(rtp(new byte[187], 33, false));
                byte[] invalidExtension = rtp(ts, 33, true);
                invalidExtension[18] = 0x7f;
                server.send(invalidExtension);
                byte[] invalidPadding = rtp(ts, 33, true);
                invalidPadding[invalidPadding.length - 1] = 0;
                server.send(invalidPadding);
                server.send(rtp(ts, 33, false));
                assertArrayEquals(ts, readFully(stream, ts.length, 2048));
            }
        }
    }

    @Test(timeout = 5000)
    public void acceptsDynamicMp2tPayloadAndRelativeTrackControl() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 MP2T/90000\r\na=control:track1\r\n")) {
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                stream.open(server.url(), "JojoHome");
                byte[] ts = transportPackets(1);
                server.send(rtp(ts, 96, false));
                assertArrayEquals(ts, readFully(stream, ts.length, 2048));
                assertTrue(server.requests.contains("SETUP " + server.baseUrl() + "track1"));
                assertTrue(server.requests.contains("PLAY " + server.url()));
            }
        }
    }

    @Test(timeout = 5000)
    public void rejectsUnsupportedFormatsBeforeSettingUpAStream() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n")) {
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                try {
                    stream.open(server.url(), "JojoHome");
                    fail("Unsupported elementary stream should fail");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("MPEG-TS"));
                }
                assertEquals(Collections.singletonList("DESCRIBE " + server.url()), server.requests);
            }
        }
    }

    @Test(timeout = 5000)
    public void releasesTheSessionWhenPlayFailsWithoutExposingTheResponseBody() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            server.failPlay = true;
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                try {
                    stream.open(server.url(), "JojoHome");
                    fail("PLAY should fail");
                } catch (IOException expected) {
                    assertEquals("Freebox : erreur RTSP 453.", expected.getMessage());
                    assertFalse(expected.getMessage().contains("private-response"));
                }
            }
            assertTrue(server.teardown.await(1, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5000)
    public void closeUnblocksTheReceiverWhenChangingChannel() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            FreeboxRtspStream stream = new FreeboxRtspStream();
            stream.open(server.url(), "JojoHome");
            AtomicReference<Throwable> result = new AtomicReference<>();
            CountDownLatch reading = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                reading.countDown();
                try { stream.read(new byte[188], 0, 188); }
                catch (Throwable error) { result.set(error); }
            });
            reader.setDaemon(true);
            reader.start();
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            stream.close();
            reader.join(1000);
            assertFalse("UDP read must stop promptly", reader.isAlive());
            assertTrue(result.get() instanceof IOException);
            assertTrue(server.teardown.await(1, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5000)
    public void keepsTheFreeboxSessionAliveWhileReading() throws Exception {
        try (RtspServer server = new RtspServer("m=video 0 RTP/AVP 33\r\n")) {
            server.sessionTimeout = 2;
            try (FreeboxRtspStream stream = new FreeboxRtspStream()) {
                stream.open(server.url(), "JojoHome");
                Thread.sleep(1100);
                byte[] ts = transportPackets(1);
                server.send(rtp(ts, 33, false));
                assertArrayEquals(ts, readFully(stream, ts.length, 2048));
                assertTrue(server.requests.contains("OPTIONS " + server.url()));
                assertEquals("test-session", server.lastSession);
            }
        }
    }

    private static byte[] readFully(FreeboxRtspStream stream, int size, int chunkSize) throws IOException {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) offset += stream.read(result, offset, Math.min(chunkSize, size - offset));
        return result;
    }

    private static byte[] transportPackets(int count) {
        byte[] ts = new byte[count * 188];
        Arrays.fill(ts, (byte) 0x55);
        for (int offset = 0; offset < ts.length; offset += 188) ts[offset] = 0x47;
        return ts;
    }

    private static byte[] rtp(byte[] ts, int payloadType, boolean extended) {
        int headerSize = extended ? 28 : 12;
        int padding = extended ? 8 : 0;
        byte[] packet = new byte[headerSize + ts.length + padding];
        packet[0] = (byte) (extended ? 0xb1 : 0x80);
        packet[1] = (byte) payloadType;
        if (extended) {
            packet[18] = 0;
            packet[19] = 2;
            packet[packet.length - 1] = (byte) padding;
        }
        System.arraycopy(ts, 0, packet, headerSize, ts.length);
        return packet;
    }

    private static final class RtspServer implements Closeable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final DatagramSocket udp = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch teardown = new CountDownLatch(1);
        final Thread thread;
        volatile Socket connection;
        volatile boolean closed;
        volatile boolean failPlay;
        volatile int sessionTimeout = 60;
        volatile int advertisedPortOffset;
        volatile int clientPort;
        volatile int clientRtcpPort;
        volatile String transport;
        volatile String lastSession;

        RtspServer(String sdp) throws IOException {
            thread = new Thread(() -> {
                try {
                    connection = listener.accept();
                    BufferedReader input = new BufferedReader(new InputStreamReader(
                            connection.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = input.readLine()) != null) {
                        String[] request = line.split(" ", 3);
                        requests.add(request[0] + " " + request[1]);
                        Map<String, String> headers = new LinkedHashMap<>();
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            headers.put(line.substring(0, colon).toLowerCase(Locale.US),
                                    line.substring(colon + 1).trim());
                        }
                        String extra = "";
                        String body = "";
                        int code = 200;
                        switch (request[0]) {
                            case "DESCRIBE":
                                body = "v=0\r\na=control:*\r\n" + sdp;
                                extra = "Content-Type: application/sdp\r\nContent-Base: " + baseUrl() + "\r\n";
                                break;
                            case "SETUP":
                                transport = headers.get("transport");
                                String[] ports = transport.substring(transport.indexOf("client_port=") + 12).split("-");
                                clientPort = Integer.parseInt(ports[0]);
                                clientRtcpPort = Integer.parseInt(ports[1]);
                                extra = "Session: test-session;timeout=" + sessionTimeout + "\r\n"
                                        + "Transport: " + transport + ";server_port=" + (udp.getLocalPort() + advertisedPortOffset)
                                        + "-" + (udp.getLocalPort() + advertisedPortOffset + 1) + "\r\n";
                                break;
                            case "PLAY":
                            case "OPTIONS":
                                lastSession = headers.get("session");
                                if (failPlay && request[0].equals("PLAY")) {
                                    code = 453;
                                    body = "private-response";
                                }
                                break;
                            case "TEARDOWN":
                                lastSession = headers.get("session");
                                teardown.countDown();
                                return;
                            default:
                                throw new AssertionError("Unexpected request: " + request[0]);
                        }
                        byte[] content = body.getBytes(StandardCharsets.UTF_8);
                        String response = "RTSP/1.0 " + code + " Response\r\nCSeq: " + headers.get("cseq")
                                + "\r\n" + extra + "Content-Length: " + content.length + "\r\n\r\n";
                        OutputStream output = connection.getOutputStream();
                        // Split the response to exercise byte-exact RTSP parsing over TCP.
                        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                        output.write(responseBytes, 0, 7);
                        output.write(responseBytes, 7, responseBytes.length - 7);
                        output.write(content);
                        output.flush();
                    }
                } catch (Throwable error) {
                    if (!closed) failure.set(error);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() { return "rtsp://127.0.0.1:" + listener.getLocalPort() + "/fbxtv_pub/"; }
        String url() { return baseUrl() + "stream?namespace=1&service=201&flavour=hd"; }

        void send(byte[] packet) throws IOException {
            udp.send(new DatagramPacket(packet, packet.length, InetAddress.getByName("127.0.0.1"), clientPort));
        }

        @Override public void close() throws IOException {
            closed = true;
            udp.close();
            listener.close();
            if (connection != null) connection.close();
            try { thread.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
