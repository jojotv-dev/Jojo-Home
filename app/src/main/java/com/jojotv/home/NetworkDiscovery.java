package com.jojotv.home;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Small, bounded SMB discovery helper for Android TV local networks. */
final class NetworkDiscovery {
    private NetworkDiscovery() { }

    static List<String> discoverSmbHosts() {
        String prefix = localIpv4Prefix();
        if (prefix == null) return Collections.emptyList();
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(24);
        for (int host = 1; host <= 254; host++) {
            final String address = prefix + host;
            pool.execute(() -> {
                if (isOpen(address, 445, 350) || isOpen(address, 139, 350)) found.add(address);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(12, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        Collections.sort(found, NetworkDiscovery::compareIpv4);
        return new ArrayList<>(found);
    }

    private static boolean isOpen(String address, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(address, port), timeoutMs);
            return true;
        } catch (IOException ignored) { return false; }
    }

    private static String localIpv4Prefix() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    byte[] bytes = address.getAddress();
                    return (bytes[0] & 255) + "." + (bytes[1] & 255) + "." + (bytes[2] & 255) + ".";
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static int compareIpv4(String left, String right) {
        return Integer.compare(ipValue(left), ipValue(right));
    }

    private static int ipValue(String value) {
        int result = 0;
        for (String part : value.split("\\.")) result = (result << 8) | Integer.parseInt(part);
        return result;
    }
}
