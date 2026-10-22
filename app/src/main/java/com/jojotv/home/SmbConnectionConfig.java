package com.jojotv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SmbConnectionConfig {
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String ENTRY_SEPARATOR = "\n";

    public final String label;
    public final String server;
    public final String user;
    public final String password;

    public SmbConnectionConfig(String label, String server, String user, String password) {
        this.label = label == null ? "" : label.trim();
        this.server = server == null ? "" : server.trim();
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
    }

    public String displayLabel() {
        String value = label == null ? "" : label.trim();
        if (!value.isEmpty()) return value;
        return defaultLabelForServer(server);
    }

    public String browserUrl() {
        return browserUrlFor(server);
    }

    public String shareRootUrl() {
        String url = browserUrl();
        int serverEnd = url.indexOf('/', "smb://".length());
        if (serverEnd < 0) return url;
        int shareEnd = url.indexOf('/', serverEnd + 1);
        return shareEnd < 0 ? url : url.substring(0, shareEnd + 1);
    }

    public static String browserUrlFor(String rawServer) {
        String value = rawServer == null ? "" : rawServer.trim().replace('\\', '/');
        if (value.isEmpty()) return "";
        while (value.startsWith("/")) value = value.substring(1);
        if (value.regionMatches(true, 0, "smb:", 0, 4)) value = value.substring(4);
        while (value.startsWith("/")) value = value.substring(1);
        value = value.replaceAll("/{2,}", "/");
        return "smb://" + value + (value.endsWith("/") ? "" : "/");
    }

    public String normalizedServer() {
        String value = browserUrl();
        if (value.regionMatches(true, 0, "smb://", 0, 6)) value = value.substring(6);
        return value.replaceAll("/+$", "");
    }

    public static synchronized java.security.Provider ensureCryptoProvider() throws java.security.NoSuchAlgorithmException {
        // jCIFS owns one process-wide provider. initProvider() rejects reuse,
        // including a provider already initialized by another SMB entry point.
        java.security.Provider provider = jcifs.util.Crypto.getProvider();
        java.security.MessageDigest.getInstance("MD4", provider);
        return provider;
    }

    public static jcifs.smb.NtlmPasswordAuthenticator authenticatorFor(String user, String password) {
        String login = user == null ? "" : user;
        int separator = login.indexOf('\\');
        if (separator > 0) {
            return new ExplicitDomainAuthenticator(login.substring(0, separator), login.substring(separator + 1), password);
        }
        return new jcifs.smb.NtlmPasswordAuthenticator("", login, password);
    }

    private static final class ExplicitDomainAuthenticator extends jcifs.smb.NtlmPasswordAuthenticator {
        private ExplicitDomainAuthenticator(String domain, String user, String password) {
            // jcifs' public constructor splits at '@' before '\\'. Preserve the
            // explicit domain, including MicrosoftAccount with a full email.
            super(null, domain, user, password);
        }
    }

    public static List<String> freeboxDeletePathSegments(String smbPath) {
        try {
            java.net.URI uri = new java.net.URI(smbPath.replace(" ", "%20"));
            if (!"smb".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return java.util.Collections.emptyList();
            List<String> segments = new ArrayList<>();
            for (String raw : uri.getRawPath().split("/", -1)) {
                if (raw.isEmpty()) continue;
                String segment = java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
                if (segment.equals(".") || segment.equals("..") || segment.contains("/")
                        || segment.contains("\\") || segment.indexOf('\0') >= 0) return java.util.Collections.emptyList();
                segments.add(segment);
            }
            return segments.size() >= 2 ? segments : java.util.Collections.emptyList();
        } catch (Exception ignored) {
            return java.util.Collections.emptyList();
        }
    }

    public boolean matches(String url, String user, String password) {
        String candidateUrl = url == null ? "" : url.trim();
        String candidateUser = user == null ? "" : user;
        String candidatePassword = password == null ? "" : password;
        return browserUrl().equals(candidateUrl) && this.user.equals(candidateUser) && this.password.equals(candidatePassword);
    }

    public static String defaultLabelForServer(String server) {
        String value = server == null ? "" : server.trim();
        if (value.isEmpty()) return "Réseau";
        if (value.startsWith("smb://")) value = value.substring("smb://".length());
        value = value.replaceAll("/+$", "");
        int separator = value.indexOf('/');
        String host = separator >= 0 ? value.substring(0, separator) : value;
        return host.isEmpty() ? "Réseau" : host;
    }

    public static List<SmbConnectionConfig> fromPrefsString(String serialized, String legacyServer, String legacyUser, String legacyPassword) {
        List<SmbConnectionConfig> result = new ArrayList<>();
        if (serialized != null && !serialized.trim().isEmpty()) {
            String[] entries = serialized.split(ENTRY_SEPARATOR, -1);
            for (String entry : entries) {
                if (entry == null || entry.trim().isEmpty()) continue;
                String[] values = entry.split(FIELD_SEPARATOR, -1);
                if (values.length < 2) continue;
                String label = unescape(values[0]);
                String server = unescape(values[1]);
                String user = values.length >= 3 ? unescape(values[2]) : "";
                String password = values.length >= 4 ? unescape(values[3]) : "";
                if (!server.isEmpty()) result.add(new SmbConnectionConfig(label, server, user, password));
            }
        }
        if (!result.isEmpty()) return result;
        if (legacyServer != null && !legacyServer.trim().isEmpty()) {
            result.add(new SmbConnectionConfig("NAS", legacyServer.trim(), legacyUser == null ? "" : legacyUser, legacyPassword == null ? "" : legacyPassword));
        }
        return result;
    }

    public static String toPrefsString(List<SmbConnectionConfig> connections) {
        StringBuilder builder = new StringBuilder();
        if (connections != null) {
            for (SmbConnectionConfig connection : connections) {
                if (connection == null || connection.server == null || connection.server.trim().isEmpty()) continue;
                if (builder.length() > 0) builder.append(ENTRY_SEPARATOR);
                builder.append(escape(connection.displayLabel()))
                        .append(FIELD_SEPARATOR)
                        .append(escape(connection.server))
                        .append(FIELD_SEPARATOR)
                        .append(escape(connection.user))
                        .append(FIELD_SEPARATOR)
                        .append(escape(connection.password));
            }
        }
        return builder.toString();
    }

    static final class SyncEntry {
        final SmbConnectionConfig connection;
        final long changedAt;
        final String writer;
        final boolean deleted;

        SyncEntry(SmbConnectionConfig connection, long changedAt, String writer, boolean deleted) {
            this.connection = deleted ? null : connection;
            this.changedAt = changedAt;
            this.writer = writer == null ? "" : writer;
            this.deleted = deleted;
        }
    }

    static String syncKey(SmbConnectionConfig connection) {
        String server = connection.normalizedServer().toLowerCase(Locale.ROOT);
        return server.length() + ":" + server + connection.user.toLowerCase(Locale.ROOT);
    }

    static Map<String, SyncEntry> seedSyncEntries(Map<String, SyncEntry> saved, List<SmbConnectionConfig> connections) {
        Map<String, SyncEntry> result = new LinkedHashMap<>(saved);
        // Existing readers have no edit clock. They must not defeat a later
        // edit/deletion downloaded from another TV during migration.
        for (SmbConnectionConfig connection : connections) {
            result.putIfAbsent(syncKey(connection), new SyncEntry(connection, 0L, "", false));
        }
        return result;
    }

    static Map<String, SyncEntry> recordSyncChanges(Map<String, SyncEntry> saved,
            List<SmbConnectionConfig> before, List<SmbConnectionConfig> after, long changedAt, String writer) {
        Map<String, SyncEntry> result = seedSyncEntries(saved, before);
        Map<String, SmbConnectionConfig> previous = new LinkedHashMap<>(), current = new LinkedHashMap<>();
        for (SmbConnectionConfig connection : before) previous.put(syncKey(connection), connection);
        for (SmbConnectionConfig connection : after) current.put(syncKey(connection), connection);
        long editTime = Math.max(1L, changedAt);
        for (SyncEntry entry : result.values()) editTime = Math.max(editTime, entry.changedAt + 1L);
        for (Map.Entry<String, SmbConnectionConfig> entry : current.entrySet()) {
            if (!sameConnection(previous.get(entry.getKey()), entry.getValue())) {
                result.put(entry.getKey(), new SyncEntry(entry.getValue(), editTime, writer, false));
            }
        }
        for (String key : previous.keySet()) {
            if (!current.containsKey(key)) result.put(key, new SyncEntry(null, editTime, writer, true));
        }
        return result;
    }

    static Map<String, SyncEntry> mergeSyncEntries(Map<String, SyncEntry> local, Map<String, SyncEntry> remote) {
        Map<String, SyncEntry> result = new LinkedHashMap<>(local);
        for (Map.Entry<String, SyncEntry> entry : remote.entrySet()) {
            SyncEntry previous = result.get(entry.getKey());
            if (previous == null || compareSyncEntries(entry.getValue(), previous) > 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    static List<SmbConnectionConfig> connectionsFromSync(Map<String, SyncEntry> entries, List<SmbConnectionConfig> current) {
        Map<String, SmbConnectionConfig> result = new LinkedHashMap<>();
        // Keep this TV's default reader and order; append newly shared readers.
        for (SmbConnectionConfig connection : current) {
            String key = syncKey(connection);
            SyncEntry entry = entries.get(key);
            if (entry != null && !entry.deleted) result.put(key, entry.connection);
        }
        List<String> keys = new ArrayList<>(entries.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            SyncEntry entry = entries.get(key);
            if (!entry.deleted) result.putIfAbsent(key, entry.connection);
        }
        return new ArrayList<>(result.values());
    }

    static boolean sameSyncEntries(Map<String, SyncEntry> left, Map<String, SyncEntry> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (String key : left.keySet()) {
            SyncEntry a = left.get(key), b = right.get(key);
            if (a.changedAt != b.changedAt || a.deleted != b.deleted || !a.writer.equals(b.writer)
                    || !sameConnection(a.connection, b.connection)) return false;
        }
        return true;
    }

    private static int compareSyncEntries(SyncEntry a, SyncEntry b) {
        int compared = Long.compare(a.changedAt, b.changedAt);
        if (compared == 0) compared = Boolean.compare(a.deleted, b.deleted);
        if (compared == 0) compared = a.writer.compareTo(b.writer);
        if (compared == 0) compared = toPrefsString(Collections.singletonList(a.connection))
                .compareTo(toPrefsString(Collections.singletonList(b.connection)));
        return compared;
    }

    private static boolean sameConnection(SmbConnectionConfig a, SmbConnectionConfig b) {
        if (a == null || b == null) return a == b;
        return a.label.equals(b.label) && a.server.equals(b.server)
                && a.user.equals(b.user) && a.password.equals(b.password);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(FIELD_SEPARATOR, "\\u001f").replace(ENTRY_SEPARATOR, "\\n");
    }

    private static String unescape(String value) {
        if (value == null) return "";
        return value.replace("\\n", ENTRY_SEPARATOR).replace("\\u001f", FIELD_SEPARATOR).replace("\\\\", "\\");
    }
}
