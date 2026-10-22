package com.jojotv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** Fire TV's paired remote API. Credentials stay local and outside NAS settings. */
final class FireTvRemoteClient {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    // Pairing and DIAL preparation must not queue a physical Home press.
    private static final ExecutorService HOME_WORKER = Executors.newCachedThreadPool();
    private static final AtomicBoolean preparing = new AtomicBoolean();
    private static final long REMOTE_START_TIMEOUT_MS = 3_500L;
    private static final long REMOTE_RETRY_DELAY_MS = 250L;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final OkHttpClient WAKE_CLIENT = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(1000, TimeUnit.MILLISECONDS).readTimeout(1000, TimeUnit.MILLISECONDS)
            .build();
    private static OkHttpClient remoteClient;

    static final class FireTvHttpException extends java.io.IOException {
        FireTvHttpException(int statusCode) { super("Fire TV HTTP " + statusCode); }
    }

    private static String token(Context context) {
        return context.getSharedPreferences("fire_tv_remote_local", Context.MODE_PRIVATE)
                .getString("token", "");
    }
    static String status(Context context) { return token(context).isEmpty() ? "Jumelage PIN requis" : "Jumelé"; }

    // The device supplies a self-signed certificate. This exception applies only
    // to these fixed loopback connections; never to the application's HTTPS client.
    private static synchronized OkHttpClient loopbackClient() throws java.security.GeneralSecurityException {
        if (remoteClient == null) {
            SSLContext tls = SSLContext.getInstance("TLS");
            X509TrustManager trust = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            tls.init(null, new TrustManager[]{trust}, null);
            remoteClient = new OkHttpClient.Builder()
                    .sslSocketFactory(tls.getSocketFactory(), trust)
                    .hostnameVerifier((host, session) -> "localhost".equals(host))
                    .followRedirects(false).followSslRedirects(false)
                    .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2000, TimeUnit.MILLISECONDS)
                    .build();
        }
        return remoteClient;
    }

    private static String post(String path, String body, String token) throws Exception {
        Request.Builder request = new Request.Builder()
                .url("https://localhost:8080/v1/FireTV/" + path)
                .header("X-Api-Key", "0987654321")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON));
        if (!token.isEmpty()) request.header("X-Client-Token", token);
        try (Response response = loopbackClient().newCall(request.build()).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) throw new FireTvHttpException(code);
            // Launcher Manager only waits for acceptance of an app launch, not
            // for its response payload. Keep that pooled, minimal Home path.
            if (path.startsWith("app/") || response.body() == null) return "";
            try (InputStream input = response.body().byteStream();
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new java.io.IOException("Réponse trop longue");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toString("UTF-8");
            }
        }
    }

    private static void wakeRemote() throws Exception {
        Request request = new Request.Builder().url("http://localhost:8009/apps/FireTVRemote")
                .post(RequestBody.create(new byte[0], JSON)).build();
        try (Response response = WAKE_CLIENT.newCall(request).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) throw new java.io.IOException("Fire TV Remote HTTP " + code);
        }
    }

    interface RemoteRequest { void run() throws Exception; }

    static void prepareWhenNeeded(RemoteRequest probe, RemoteRequest wake) throws Exception {
        try { probe.run(); }
        catch (Exception unavailable) { wake.run(); }
    }

    static void prepare(Context context) {
        if (!FireOsHomeButtonService.isSupportedDevice()
                || !FireOsHomeButtonService.shouldKeepRunning(
                        context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                                .getBoolean(FireOsHomeButtonService.PREF_ENABLED, false),
                        FireOsHomeButtonService.isStandbyRecoveryEnabled(context))
                || !preparing.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                loopbackClient();
                prepareWhenNeeded(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", 8080), 50);
                    }
                }, FireTvRemoteClient::wakeRemote);
            } catch (Exception error) {
                android.util.Log.w("JojoFireRemote", "Remote preparation failed; Home can retry", error);
            } finally { preparing.set(false); }
        });
    }

    static int launchWhenReady(RemoteRequest launch, RemoteRequest wake,
                               LongSupplier clock, LongConsumer sleep) throws Exception {
        long deadline = clock.getAsLong() + REMOTE_START_TIMEOUT_MS;
        boolean wakeRequested = false;
        for (int attempt = 1; ; attempt++) {
            try {
                // Home normally arrives while the paired API is already running.
                // Do not put the slower DIAL wake request in that warm path.
                launch.run();
                return attempt;
            } catch (FireTvHttpException rejected) {
                throw rejected;
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                if (!wakeRequested) {
                    wakeRequested = true;
                    try { wake.run(); }
                    catch (Exception wakeError) { unavailable.addSuppressed(wakeError); }
                    // A DIAL timeout does not mean the HTTPS API is unavailable.
                    if (clock.getAsLong() < deadline) continue;
                }
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 0L) throw unavailable;
                sleep.accept(Math.min(REMOTE_RETRY_DELAY_MS, remaining));
            }
        }
    }

    static void launch(Context context, Runnable fallback) {
        launch(context, fallback, () -> true, () -> { });
    }

    static void launch(Context context, Runnable fallback, BooleanSupplier stillNeeded, Runnable completed) {
        Context app = context.getApplicationContext();
        if (!FireOsHomeButtonService.isSupportedDevice() || token(app).isEmpty()) {
            try { if (stillNeeded.getAsBoolean()) fallback.run(); }
            finally { completed.run(); }
            return;
        }
        HOME_WORKER.execute(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                int attempt = launchWhenReady(
                        () -> {
                            if (!stillNeeded.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                            post("app/" + app.getPackageName(), "", token(app));
                        },
                        () -> { if (stillNeeded.getAsBoolean()) wakeRemote(); },
                        android.os.SystemClock::elapsedRealtime, android.os.SystemClock::sleep);
                android.util.Log.i("JojoFireRemote", "Launch accepted in "
                        + (android.os.SystemClock.elapsedRealtime() - started)
                        + "ms (attempt " + attempt + ")");
            } catch (java.util.concurrent.CancellationException cancelled) {
                // A new sleep cycle, disabled setting or visible Home cancels
                // the old wake request; it must not launch a delayed fallback.
            } catch (Exception error) {
                android.util.Log.w("JojoFireRemote", "Remote launch failed; using Android fallback", error);
                if (stillNeeded.getAsBoolean()) fallback.run();
            } finally { completed.run(); }
        });
    }

    static void showPairing(Activity activity) {
        if (!FireOsHomeButtonService.isSupportedDevice()) return;
        EditText pin = new EditText(activity);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setHint("Code PIN à 4 chiffres affiché par Fire TV");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Jumeler Jojo Home avec Fire TV")
                .setMessage("Demandez le code, puis saisissez le PIN affiché par Fire TV. Si nécessaire, revenez ici avec Retour.")
                .setView(pin).setNeutralButton("Afficher le code", null)
                .setPositiveButton("Valider", null).setNegativeButton("Annuler", null).create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> WORKER.execute(() -> {
                try {
                    try { wakeRemote(); } catch (Exception ignored) { }
                    post("pin/display", "{\"friendlyName\":\"Jojo Home\"}", "");
                } catch (Exception error) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Impossible d’afficher le PIN : " + error.getMessage(), Toast.LENGTH_LONG).show());
                }
            }));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = pin.getText().toString().trim();
                if (!value.matches("[0-9]{4}")) { pin.setError("4 chiffres requis"); return; }
                WORKER.execute(() -> {
                    try {
                        JSONObject response = new JSONObject(post("pin/verify", "{\"pin\":\"" + value + "\"}", ""));
                        String paired = response.optString("token", "");
                        if (paired.isEmpty()) paired = response.optString("description", "");
                        if (paired.isEmpty()) throw new java.io.IOException("Token absent");
                        activity.getSharedPreferences("fire_tv_remote_local", Context.MODE_PRIVATE)
                                .edit().putString("token", paired).apply();
                        activity.runOnUiThread(() -> {
                            dialog.dismiss();
                            Toast.makeText(activity, "Jojo Home jumelé avec Fire TV", Toast.LENGTH_LONG).show();
                        });
                    } catch (Exception error) {
                        activity.runOnUiThread(() -> pin.setError("Jumelage refusé : " + error.getMessage()));
                    }
                });
            });
        });
        dialog.show();
    }
}
