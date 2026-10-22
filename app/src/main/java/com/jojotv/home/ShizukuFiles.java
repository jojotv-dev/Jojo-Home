package com.jojotv.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.concurrent.atomic.AtomicBoolean;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.Shizuku;

/** File access and opt-in startup through the user-installed Shizuku service. */
final class ShizukuFiles {
    static final int PERMISSION_REQUEST_CODE = 6427;

    private static final AtomicBoolean startupRunning = new AtomicBoolean();
    private static final ArrayList<Runnable> startupCallbacks = new ArrayList<>();
    private static volatile boolean startupSucceeded;
    private static volatile String startupResult = "";

    enum Availability { READY, PERMISSION_REQUIRED, UNAVAILABLE }

    static final class Entry {
        final String name;
        final String path;
        final boolean directory;
        final long size;

        Entry(String name, String path, boolean directory, long size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
        }
    }

    private ShizukuFiles() { }

    static Availability availability() {
        try {
            if (!Shizuku.pingBinder()) return Availability.UNAVAILABLE;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? Availability.READY : Availability.PERMISSION_REQUIRED;
        } catch (Throwable ignored) {
            return Availability.UNAVAILABLE;
        }
    }

    static void requestPermission() {
        if (availability() == Availability.PERMISSION_REQUIRED) Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
    }

    static List<Entry> list(String path) throws Exception {
        String command = "dir=" + shellQuote(path) + "; [ -d \"$dir\" ] || exit 2; "
                + "for f in \"$dir\"/* \"$dir\"/.[!.]* \"$dir\"/..?*; do "
                + "[ -e \"$f\" ] || continue; name=\"${f##*/}\"; "
                + "if [ -d \"$f\" ]; then type=d; size=0; else type=f; size=$(stat -c %s \"$f\" 2>/dev/null || echo 0); fi; "
                + "printf '%s\\t%s\\t%s\\t%s\\n' \"$name\" \"$f\" \"$type\" \"$size\"; done";
        ArrayList<Entry> result = new ArrayList<>();
        for (String line : runShell(command).split("\\n")) {
            String[] parts = line.split("\\t", 4);
            if (parts.length < 4) continue;
            long size;
            try { size = Long.parseLong(parts[3]); } catch (Exception ignored) { size = 0; }
            result.add(new Entry(parts[0], parts[1], "d".equals(parts[2]), size));
        }
        Collections.sort(result, Comparator.comparing((Entry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static boolean exists(String path) throws Exception {
        return "yes".equals(runShell("if [ -e " + shellQuote(path) + " ] || [ -L " + shellQuote(path)
                + " ]; then printf yes; else printf no; fi"));
    }

    static Entry stat(String path) throws Exception {
        String quoted = shellQuote(path);
        String line = runShell("[ -e " + quoted + " ] || [ -L " + quoted + " ] || exit 2; "
                + "if [ -d " + quoted + " ]; then printf 'd\\t0'; else printf 'f\\t%s' \"$(stat -c %s "
                + quoted + " 2>/dev/null || echo 0)\"; fi");
        String[] values = line.split("\\t", 2);
        boolean directory = values.length > 0 && "d".equals(values[0]);
        long size = 0;
        if (!directory && values.length > 1) try { size = Long.parseLong(values[1]); } catch (Exception ignored) { }
        return new Entry(new File(path).getName(), path, directory, size);
    }

    static void rename(String path, String name) throws Exception {
        String source = mutablePath(path);
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.indexOf('\0') >= 0) throw new IOException("Nom de fichier invalide");
        File sourceFile = new File(source);
        File parent = sourceFile.getParentFile();
        if (parent == null) throw new IOException("Renommage impossible");
        String target = mutablePath(new File(parent, name.trim()).getPath());
        runShell("[ -e " + shellQuote(source) + " ] || [ -L " + shellQuote(source) + " ]; [ ! -e "
                + shellQuote(target) + " ] && [ ! -L " + shellQuote(target) + " ] || exit 3; mv -- "
                + shellQuote(source) + " " + shellQuote(target));
    }

    static void delete(String path) throws Exception {
        String target = mutablePath(path);
        runShell("[ -e " + shellQuote(target) + " ] || [ -L " + shellQuote(target) + " ]; rm -rf -- "
                + shellQuote(target) + "; [ ! -e " + shellQuote(target) + " ] && [ ! -L "
                + shellQuote(target) + " ]");
    }

    static void makeDirectory(String path) throws Exception {
        runShell("mkdir -p -- " + shellQuote(mutablePath(path)));
    }

    static void copyPath(String source, String target) throws Exception {
        String from = mutablePath(source);
        String to = mutablePath(target);
        runShell("[ -e " + shellQuote(from) + " ] || [ -L " + shellQuote(from) + " ]; [ ! -e "
                + shellQuote(to) + " ] && [ ! -L " + shellQuote(to)
                + " ] || exit 3; cp -R -- " + shellQuote(from) + " " + shellQuote(to));
    }

    static void movePath(String source, String target) throws Exception {
        String from = mutablePath(source);
        String to = mutablePath(target);
        runShell("[ -e " + shellQuote(from) + " ] || [ -L " + shellQuote(from) + " ]; [ ! -e "
                + shellQuote(to) + " ] && [ ! -L " + shellQuote(to)
                + " ] || exit 3; mv -- " + shellQuote(from) + " " + shellQuote(to));
    }

    static void copyToFile(String path, File target) throws Exception {
        IRemoteProcess process = startProcess("sh", "-c", "cat -- " + shellQuote(path));
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream());
             java.io.InputStream error = new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream())) {
            copyToFile(input, target);
            String stderr = readText(error);
            if (process.waitFor() != 0) throw new IOException(stderr.isEmpty() ? "Lecture Shizuku impossible" : stderr);
        } catch (Exception error) {
            target.delete();
            throw error;
        } finally {
            process.destroy();
        }
    }

    static void copyToStream(String path, OutputStream output) throws Exception {
        IRemoteProcess process = startProcess("sh", "-c", "cat -- " + shellQuote(path));
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream());
             java.io.InputStream error = new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream())) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            String stderr = readText(error);
            if (process.waitFor() != 0) throw new IOException(stderr.isEmpty() ? "Lecture Shizuku impossible" : stderr);
        } finally {
            process.destroy();
        }
    }

    static void copyFromStream(java.io.InputStream input, String path) throws Exception {
        String target = mutablePath(path);
        IRemoteProcess process = startProcess("sh", "-c", "cat > " + shellQuote(target));
        try (java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(process.getOutputStream());
             java.io.InputStream error = new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream())) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.close();
            String stderr = readText(error);
            if (process.waitFor() != 0) throw new IOException(stderr.isEmpty() ? "Écriture Shizuku impossible" : stderr);
        } catch (Exception error) {
            runShell("rm -f -- " + shellQuote(target));
            throw error;
        } finally {
            process.destroy();
        }
    }

    static String mutablePath(String path) throws IOException {
        if (path == null || path.trim().isEmpty() || !path.startsWith("/")) throw new IOException("Chemin Shizuku invalide");
        String value = path.trim().replaceAll("/+$", "");
        for (String segment : value.split("/")) if (segment.equals(".") || segment.equals(".."))
            throw new IOException("Chemin Shizuku invalide");
        boolean shared = value.startsWith("/storage/emulated/0/") || value.startsWith("/sdcard/");
        boolean removable = value.matches("^/storage/(?!emulated(?:/|$)|self(?:/|$))[^/]+/.+")
                || value.matches("^/mnt/media_rw/[^/]+/.+");
        if (!shared && !removable) throw new IOException("Les modifications Shizuku sont limitées au stockage partagé.");
        return value;
    }

    static File copyToCache(Context context, String path, String name) throws Exception {
        File cache = new File(context.getCacheDir(), "shizuku-files");
        if (!cache.exists() && !cache.mkdirs()) throw new java.io.IOException("Cache Shizuku indisponible");
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(cache, safe.isEmpty() ? "fichier" : safe);
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        int number = 1;
        while (target.exists()) target = new File(cache, stem + " (" + number++ + ")" + extension);
        // The shell can read the source, but cannot write in the app's private cache.
        IRemoteProcess process = startProcess("sh", "-c", "cat " + shellQuote(path));
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream());
             java.io.InputStream error = new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream())) {
            copyToFile(input, target);
            String stderr = readText(error);
            if (process.waitFor() != 0) throw new IOException(stderr.isEmpty() ? "Lecture Shizuku impossible" : stderr);
            return target;
        } catch (Exception error) {
            target.delete();
            throw error;
        } finally {
            process.destroy();
        }
    }

    static void copyToFile(java.io.InputStream input, File target) throws IOException {
        try (OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } catch (IOException error) {
            target.delete();
            throw error;
        }
    }

    static boolean autoStartEnabled(Context context) {
        // ADB authorization belongs to this installation, not to NAS-synced settings.
        return context.getSharedPreferences("jojo_home_shizuku", Context.MODE_PRIVATE)
                .getBoolean("auto_start", false);
    }

    static void setAutoStartEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("jojo_home_shizuku", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_start", enabled).apply();
    }

    static String startupStatus() {
        if (startupRunning.get() || (!startupSucceeded && !startupResult.isEmpty())) return startupResult;
        Availability state = availability();
        if (state == Availability.READY) return "Shizuku démarré et autorisé";
        if (state == Availability.PERMISSION_REQUIRED) return "Shizuku démarré · autoriser Jojo Home";
        return "ADB réseau · port 5555";
    }

    static void startIfEnabled(Context context) {
        if (autoStartEnabled(context) && availability() == Availability.UNAVAILABLE) {
            startWithAdb(context, false, null);
        }
    }

    static synchronized void startWithAdb(Context context, boolean allowAuthorization, Runnable finished) {
        if (finished != null) startupCallbacks.add(finished);
        if (!startupRunning.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        startupSucceeded = false;
        startupResult = allowAuthorization
                ? "Connexion 5555 · accepte « Toujours autoriser » sur la TV"
                : "Démarrage de Shizuku…";
        new Thread(() -> {
            boolean authenticated = false;
            try {
                android.content.pm.ApplicationInfo manager = application.getPackageManager()
                        .getApplicationInfo("moe.shizuku.privileged.api", 0);
                for (int attempt = 0; attempt < 4; attempt++) {
                    if (!allowAuthorization && !autoStartEnabled(application)) {
                        startupResult = "Démarrage automatique désactivé";
                        return;
                    }
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
                        socket.setSoTimeout(allowAuthorization ? 60000 : 8000);
                        socket.setTcpNoDelay(true);
                        LocalAdb adb = new LocalAdb(socket);
                        adb.authenticate(allowAuthorization);
                        authenticated = true;
                        if (!allowAuthorization && !autoStartEnabled(application)) {
                            startupResult = "Démarrage automatique désactivé";
                            return;
                        }
                        if (availability() == Availability.UNAVAILABLE) {
                            startupResult = "Démarrage du service Shizuku…";
                            socket.setSoTimeout(20000);
                            String binary = shellQuote(manager.nativeLibraryDir + "/libshizuku.so");
                            String command = "if [ -x " + binary + " ]; then " + binary + " "
                                    + shellQuote("--apk=" + manager.sourceDir) + "; else sh "
                                    + shellQuote("/sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
                                    + "; fi; printf '\\nJOJO_SHIZUKU_EXIT:%s\\n' \"$?\"";
                            if (!adb.shell(command).contains("JOJO_SHIZUKU_EXIT:0")) {
                                throw new IOException("Shizuku starter failed");
                            }
                        }
                        break;
                    } catch (ConnectException error) {
                        if (attempt == 3) throw error;
                        SystemClock.sleep(2000);
                    }
                }
                long deadline = SystemClock.elapsedRealtime() + 8000;
                while (availability() == Availability.UNAVAILABLE && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(200);
                }
                startupSucceeded = availability() != Availability.UNAVAILABLE;
                startupResult = !startupSucceeded
                        ? "Shizuku ne répond pas après son démarrage"
                        : "Connexion ADB autorisée pour les prochains démarrages";
            } catch (PackageManager.NameNotFoundException error) {
                startupResult = "Installe Shizuku sur cet appareil";
            } catch (ConnectException error) {
                startupResult = "Active le débogage réseau sur le port 5555";
            } catch (SocketTimeoutException error) {
                startupResult = authenticated ? "Le démarrage de Shizuku a pris trop de temps"
                        : "Autorisation ADB en attente · relance le test et accepte sur la TV";
            } catch (AdbAuthorizationException error) {
                startupResult = "Connexion ADB à autoriser · lance « Configurer et tester Shizuku »";
            } catch (Exception error) {
                startupResult = "Connexion Shizuku impossible · vérifie le débogage réseau 5555";
            } finally {
                ArrayList<Runnable> callbacks;
                synchronized (ShizukuFiles.class) {
                    startupRunning.set(false);
                    callbacks = new ArrayList<>(startupCallbacks);
                    startupCallbacks.clear();
                }
                Handler main = new Handler(Looper.getMainLooper());
                for (Runnable callback : callbacks) main.post(callback);
            }
        }, "JojoHomeShizukuStartup").start();
    }

    private static KeyStore.PrivateKeyEntry adbKey() throws Exception {
        String alias = "jojo_home_shizuku_adb";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            generator.generateKeyPair();
        }
        return (KeyStore.PrivateKeyEntry) store.getEntry(alias, null);
    }

    private static byte[] adbPublicKey(RSAPublicKey key) {
        BigInteger modulus = key.getModulus();
        BigInteger word = BigInteger.ONE.shiftLeft(32);
        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64).putInt(modulus.mod(word).modInverse(word).negate().intValue());
        for (BigInteger number : new BigInteger[] { modulus, BigInteger.ONE.shiftLeft(4096).mod(modulus) }) {
            for (int index = 0; index < 64; index++) {
                buffer.putInt(number.intValue());
                number = number.shiftRight(32);
            }
        }
        buffer.putInt(key.getPublicExponent().intValue());
        return (Base64.encodeToString(buffer.array(), Base64.NO_WRAP) + " JojoHome@localhost\0")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class AdbAuthorizationException extends IOException { }

    /** One local ADB shell session; no remote hosts or general-purpose command API. */
    private static final class LocalAdb {
        private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
        private static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
        private final DataInputStream input;
        private final OutputStream output;

        LocalAdb(Socket socket) throws IOException {
            input = new DataInputStream(socket.getInputStream());
            output = socket.getOutputStream();
        }

        void authenticate(boolean allowAuthorization) throws Exception {
            write(CNXN, 0x01000000, 4096, "host::JojoHome\0".getBytes(StandardCharsets.UTF_8));
            Packet reply = read();
            if (reply.command == AUTH && reply.arg0 == 1) {
                if (reply.data.length != 20) throw new IOException("Invalid ADB token");
                KeyStore.PrivateKeyEntry key = adbKey();
                // ADB sends an already-hashed SHA-1 token. Do not hash it again.
                Signature signer = Signature.getInstance("NONEwithRSA");
                signer.initSign(key.getPrivateKey());
                signer.update(new byte[] { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 });
                signer.update(reply.data);
                write(AUTH, 2, 0, signer.sign());
                reply = read();
                if (reply.command == AUTH && reply.arg0 == 1) {
                    // Only an explicit settings action may trigger Android's ADB confirmation.
                    if (!allowAuthorization) throw new AdbAuthorizationException();
                    write(AUTH, 3, 0, adbPublicKey((RSAPublicKey) key.getCertificate().getPublicKey()));
                    reply = read();
                }
            }
            if (reply.command != CNXN) throw new AdbAuthorizationException();
        }

        String shell(String command) throws IOException {
            write(OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            Packet reply = read();
            if (reply.command != OKAY || reply.arg1 != 1) throw new IOException("ADB shell unavailable");
            int remoteId = reply.arg0;
            StringBuilder result = new StringBuilder();
            while (true) {
                reply = read();
                if (reply.arg0 != remoteId || reply.arg1 != 1) throw new IOException("Invalid ADB stream");
                if (reply.command == CLSE) {
                    write(CLSE, 1, remoteId, new byte[0]);
                    return result.toString();
                }
                if (reply.command != WRTE) throw new IOException("Invalid ADB shell packet");
                if (result.length() > 16384) throw new IOException("ADB startup output too long");
                result.append(new String(reply.data, StandardCharsets.UTF_8));
                write(OKAY, 1, remoteId, new byte[0]);
            }
        }

        private void write(int command, int arg0, int arg1, byte[] data) throws IOException {
            int checksum = 0;
            for (byte value : data) checksum += value & 255;
            ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum).putInt(~command);
            output.write(header.array());
            output.write(data);
            output.flush();
        }

        private Packet read() throws IOException {
            byte[] bytes = new byte[24];
            input.readFully(bytes);
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int command = header.getInt(), arg0 = header.getInt(), arg1 = header.getInt();
            int length = header.getInt(), checksum = header.getInt(), magic = header.getInt();
            if (magic != ~command || length < 0 || length > 65536) throw new IOException("Invalid ADB header");
            byte[] data = new byte[length];
            input.readFully(data);
            int actualChecksum = 0;
            for (byte value : data) actualChecksum += value & 255;
            if (checksum != actualChecksum) throw new IOException("Invalid ADB checksum");
            return new Packet(command, arg0, arg1, data);
        }
    }

    private static final class Packet {
        final int command, arg0, arg1;
        final byte[] data;

        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private static IRemoteProcess startProcess(String... command) throws Exception {
        if (availability() != Availability.READY) throw new IllegalStateException("Autorisation Shizuku requise");
        IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
        if (service == null) throw new IllegalStateException("Service Shizuku indisponible");
        return service.newProcess(command, null, null);
    }

    private static String runShell(String command) throws Exception {
        IRemoteProcess process = startProcess("sh", "-c", command);
        String stdout;
        String stderr;
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream());
             java.io.InputStream error = new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream())) {
            stdout = readText(input);
            stderr = readText(error);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new java.io.IOException(stderr.isEmpty() ? "Commande Shizuku échouée" : stderr);
        return stdout;
    }

    private static String readText(java.io.InputStream input) throws java.io.IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) { if (result.length() > 0) result.append('\n'); result.append(line); }
        return result.toString();
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
