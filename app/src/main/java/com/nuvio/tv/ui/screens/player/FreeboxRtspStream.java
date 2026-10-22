package com.nuvio.tv.ui.screens.player;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Freebox multiposte uses MPEG-TS over RTP/UDP, not the elementary RTSP formats. */
final class FreeboxRtspStream implements Closeable {
    private static final int TIMEOUT_MS = 8000;
    private static final int TS_PACKET_SIZE = 188;
    private final byte[] packetBuffer = new byte[65535];
    private final DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
    private volatile boolean closed;
    private volatile boolean requestInProgress;
    private volatile Socket controlSocket;
    private volatile DatagramSocket rtpSocket;
    private volatile DatagramSocket rtcpSocket;
    private InputStream input;
    private OutputStream output;
    private String streamUrl;
    private String userAgent;
    private String session;
    private int sequence;
    private int payloadType;
    private int payloadOffset;
    private int payloadEnd;
    private long keepAliveIntervalMs = 30000;
    private long nextKeepAliveMs;

    void open(String url, String agent) throws IOException {
        try {
            URI uri = new URI(url);
            if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw new IOException("Adresse RTSP Freebox invalide.");
            }
            streamUrl = uri.toASCIIString();
            userAgent = agent == null || agent.contains("\r") || agent.contains("\n")
                    ? "JojoHome" : agent;
            int port = uri.getPort() == -1 ? 554 : uri.getPort();
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            InetAddress address = addresses[0];
            for (InetAddress candidate : addresses) {
                if (candidate instanceof Inet4Address) {
                    address = candidate;
                    break;
                }
            }
            controlSocket = new Socket();
            ensureOpen();
            controlSocket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);
            controlSocket.setSoTimeout(TIMEOUT_MS);
            input = new BufferedInputStream(controlSocket.getInputStream());
            output = controlSocket.getOutputStream();

            Response description = request("DESCRIBE", streamUrl, "Accept: application/sdp\r\n");
            String trackUrl = selectTrack(description);
            bindUdpSockets(controlSocket.getLocalAddress());
            Response setup = request("SETUP", trackUrl, "Transport: RTP/AVP;unicast;client_port="
                    + rtpSocket.getLocalPort() + "-" + rtcpSocket.getLocalPort() + "\r\n");
            String sessionHeader = setup.headers.get("session");
            if (sessionHeader == null || sessionHeader.trim().isEmpty()) {
                throw new IOException("Session RTSP Freebox absente.");
            }
            session = sessionHeader.split(";", 2)[0].trim();
            for (String parameter : sessionHeader.split(";")) {
                if (parameter.trim().startsWith("timeout=")) {
                    try {
                        long seconds = Long.parseLong(parameter.trim().substring(8));
                        keepAliveIntervalMs = Math.max(1000L, Math.min(30000L, seconds * 500L));
                    } catch (NumberFormatException ignored) { }
                }
            }
            String transport = setup.headers.get("transport");
            if (transport == null || transport.toUpperCase(Locale.US).contains("TCP")
                    || transport.toLowerCase(Locale.US).contains("multicast")) {
                throw new IOException("Transport UDP Freebox indisponible.");
            }
            request("PLAY", streamUrl, "");
            nextKeepAliveMs = nowMs() + keepAliveIntervalMs;
        } catch (URISyntaxException e) {
            close();
            throw new IOException("Adresse RTSP Freebox invalide.");
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        ensureOpen();
        if (nowMs() >= nextKeepAliveMs) {
            request("OPTIONS", streamUrl, "");
            nextKeepAliveMs = nowMs() + keepAliveIntervalMs;
        }
        long deadline = nowMs() + TIMEOUT_MS;
        while (payloadOffset == payloadEnd) {
            ensureOpen();
            long remaining = deadline - nowMs();
            if (remaining <= 0) throw new SocketTimeoutException("Aucun paquet vidéo Freebox reçu.");
            rtpSocket.setSoTimeout((int) remaining);
            packet.setLength(packetBuffer.length);
            rtpSocket.receive(packet);
            // Freebox can transmit from a different UDP port than server_port
            // advertises. Keep the peer check, using our dedicated client port.
            if (!packet.getAddress().equals(controlSocket.getInetAddress())) continue;
            unpackRtp(packet.getLength());
        }
        int count = Math.min(length, payloadEnd - payloadOffset);
        System.arraycopy(packetBuffer, payloadOffset, buffer, offset, count);
        payloadOffset += count;
        return count;
    }

    private void unpackRtp(int size) {
        if (size < 12 || (packetBuffer[0] & 0xc0) != 0x80
                || (packetBuffer[1] & 0x7f) != payloadType) return;
        int start = 12 + (packetBuffer[0] & 0x0f) * 4;
        if (start > size) return;
        if ((packetBuffer[0] & 0x10) != 0) {
            if (start + 4 > size) return;
            int extensionWords = ((packetBuffer[start + 2] & 0xff) << 8)
                    | (packetBuffer[start + 3] & 0xff);
            start += 4 + extensionWords * 4;
        }
        int end = size;
        if ((packetBuffer[0] & 0x20) != 0) {
            int padding = packetBuffer[size - 1] & 0xff;
            if (padding == 0) return;
            end -= padding;
        }
        if (start >= end || (end - start) % TS_PACKET_SIZE != 0) return;
        for (int pos = start; pos < end; pos += TS_PACKET_SIZE) {
            if (packetBuffer[pos] != 0x47) return;
        }
        payloadOffset = start;
        payloadEnd = end;
    }

    private String selectTrack(Response description) throws IOException {
        String control = null;
        String sessionControl = null;
        boolean inMedia = false;
        boolean selected = false;
        int candidatePayload = -1;
        for (String line : description.body.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("m=")) {
                if (selected) break;
                inMedia = true;
                control = null;
                candidatePayload = -1;
                String[] fields = line.substring(2).split("\\s+");
                if (fields.length >= 4 && fields[2].equalsIgnoreCase("RTP/AVP")) {
                    for (int i = 3; i < fields.length; i++) {
                        if (fields[i].equals("33")) {
                            payloadType = 33;
                            selected = true;
                            break;
                        }
                    }
                    try { candidatePayload = Integer.parseInt(fields[3]); }
                    catch (NumberFormatException ignored) { }
                }
            } else if (inMedia && line.startsWith("a=rtpmap:")) {
                String[] mapping = line.substring(9).split("\\s+", 2);
                if (mapping.length == 2 && mapping[0].equals(Integer.toString(candidatePayload))
                        && mapping[1].equalsIgnoreCase("MP2T/90000")) {
                    payloadType = candidatePayload;
                    selected = true;
                }
            } else if (line.startsWith("a=control:")) {
                if (inMedia) control = line.substring(10).trim();
                else sessionControl = line.substring(10).trim();
            }
        }
        if (!selected) throw new IOException("Ce flux Freebox ne contient pas de MPEG-TS RTP.");
        if (control == null || control.equals("*")) control = sessionControl;
        if (control == null || control.equals("*")) return streamUrl;
        try {
            URI base = new URI(description.headers.getOrDefault("content-base", streamUrl));
            URI track = base.resolve(control);
            URI stream = new URI(streamUrl);
            if (!"rtsp".equalsIgnoreCase(track.getScheme())
                    || !stream.getHost().equalsIgnoreCase(track.getHost())
                    || track.getRawUserInfo() != null) {
                throw new IOException("Piste RTSP Freebox invalide.");
            }
            return track.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IOException("Piste RTSP Freebox invalide.");
        }
    }

    private void bindUdpSockets(InetAddress address) throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            ensureOpen();
            DatagramSocket candidate = new DatagramSocket(new InetSocketAddress(address, 0));
            int port = candidate.getLocalPort();
            if ((port & 1) != 0) {
                candidate.close();
                try { candidate = new DatagramSocket(new InetSocketAddress(address, port - 1)); }
                catch (SocketException e) { continue; }
                port--;
            }
            rtpSocket = candidate;
            try {
                rtcpSocket = new DatagramSocket(new InetSocketAddress(address, port + 1));
                rtpSocket.setReceiveBufferSize(1024 * 1024);
                ensureOpen();
                return;
            } catch (SocketException e) {
                rtpSocket.close();
                if (rtcpSocket != null) rtcpSocket.close();
            }
        }
        throw new IOException("Impossible d'ouvrir les ports UDP Freebox.");
    }

    private Response request(String method, String url, String extraHeaders) throws IOException {
        ensureOpen();
        requestInProgress = true;
        try {
            int currentSequence = ++sequence;
            writeRequest(method, url, extraHeaders, currentSequence);
            String status = readLine();
            String[] statusParts = status.split(" ", 3);
            if (statusParts.length < 2 || !statusParts[0].equals("RTSP/1.0")) {
                throw new IOException("Réponse RTSP Freebox invalide.");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (int count = 0; ; count++) {
                String line = readLine();
                if (line.isEmpty()) break;
                if (count >= 64) throw new IOException("Réponse RTSP Freebox trop longue.");
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
            int bodyLength;
            int code;
            try {
                code = Integer.parseInt(statusParts[1]);
                bodyLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                if (headers.containsKey("cseq")
                        && Integer.parseInt(headers.get("cseq")) != currentSequence) {
                    throw new IOException("Séquence RTSP Freebox invalide.");
                }
            } catch (NumberFormatException e) {
                throw new IOException("Réponse RTSP Freebox invalide.");
            }
            if (bodyLength < 0 || bodyLength > 65536) {
                throw new IOException("Réponse RTSP Freebox trop longue.");
            }
            byte[] body = new byte[bodyLength];
            int read = 0;
            while (read < bodyLength) {
                int count = input.read(body, read, bodyLength - read);
                if (count == -1) throw new EOFException("Connexion RTSP Freebox interrompue.");
                read += count;
            }
            if (code < 200 || code >= 300) throw new IOException("Freebox : erreur RTSP " + code + ".");
            return new Response(headers, new String(body, StandardCharsets.UTF_8));
        } finally {
            requestInProgress = false;
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        for (int count = 0; count < 8192; count++) {
            int value = input.read();
            if (value == -1) throw new EOFException("Connexion RTSP Freebox interrompue.");
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
        }
        throw new IOException("Réponse RTSP Freebox trop longue.");
    }

    private void writeRequest(String method, String url, String extraHeaders, int cseq) throws IOException {
        String message = method + " " + url + " RTSP/1.0\r\nCSeq: " + cseq
                + "\r\nUser-Agent: " + userAgent + "\r\n"
                + (session == null ? "" : "Session: " + session + "\r\n")
                + extraHeaders + "\r\n";
        output.write(message.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Flux RTSP Freebox fermé.");
    }

    private static long nowMs() {
        return System.nanoTime() / 1000000L;
    }

    @Override
    public void close() {
        boolean wasClosed = closed;
        closed = true;
        if (rtpSocket != null) rtpSocket.close();
        if (rtcpSocket != null) rtcpSocket.close();
        if (!wasClosed && session != null && output != null && !requestInProgress) {
            try { writeRequest("TEARDOWN", streamUrl, "", ++sequence); }
            catch (IOException ignored) { }
        }
        if (controlSocket != null) {
            try { controlSocket.close(); }
            catch (IOException ignored) { }
        }
    }

    private static final class Response {
        final Map<String, String> headers;
        final String body;

        Response(Map<String, String> headers, String body) {
            this.headers = headers;
            this.body = body;
        }
    }
}
