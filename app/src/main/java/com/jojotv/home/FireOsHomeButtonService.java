package com.jojotv.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fire OS Home interception compatible with Launcher Manager's logcat path.
 * Fire OS 6 exposes Home presses through several vendor log signatures rather
 * than through the normal Android key event pipeline.
 */
public final class FireOsHomeButtonService extends Service {
    public static final String PREF_ENABLED = "fire_os_home_button_enabled";
    public static final String PREF_METHOD = "fire_os_home_trigger_method";
    public static final String PREF_TOKEN = "fire_os_home_token";
    public static final String PREF_LAST_TRIGGER = "fire_os_home_last_trigger";
    public static final String METHOD_AUTO = "AUTO";
    public static final String METHOD_CUSTOM_HOME = "CustomHomeButtonHandler";
    public static final String METHOD_HOME_LISTENER = "HomeListener";
    public static final String METHOD_ACTIVITY_MANAGER = "ActivityManager";
    public static final String METHOD_OZ_DCS = "OZ-DCS:HomePressReceiver";

    private static final String CHANNEL = "fire_os_home_button";
    private static final String ACTION_RECOVER_STANDBY = "com.jojotv.home.RECOVER_FIRE_STANDBY";
    private static final String PREF_PENDING_WAKE = "fire_os_pending_wake";
    private static final long DEBOUNCE_MS = 500L;
    private static final Pattern HOME_LOG_ENTRY = Pattern.compile(
            "^\\s*(\\d+)\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+(.+?): (.*)$");
    private final AtomicBoolean running = new AtomicBoolean();
    private final HomePressTracker homePressTracker = new HomePressTracker();
    private final Handler wakeHandler = new Handler(Looper.getMainLooper());
    private final WakeRecoveryTracker wakeRecovery = new WakeRecoveryTracker();
    private final Runnable checkWakeRecovery = this::checkWakeRecovery;
    private static volatile FireOsHomeButtonService activeService;
    private static volatile boolean homeResumed;
    private String resumedComponent = "";
    private Thread readerThread;
    private volatile Process logcatProcess;
    private volatile String triggerMethod = METHOD_OZ_DCS;
    private boolean standbyReceiverRegistered;
    private final BroadcastReceiver standbyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_DREAMING_STARTED.equals(action)) {
                armStandbyRecovery();
            } else {
                beginStandbyRecovery();
            }
        }
    };

    public static boolean isSupportedDevice() {
        return isSupportedDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT);
    }

    static boolean isSupportedDevice(String manufacturer, String model, int sdk) {
        return "Amazon".equalsIgnoreCase(manufacturer) && model != null
                && model.toUpperCase(Locale.ROOT).startsWith("AFT") && sdk <= 25;
    }

    public static boolean hasReadLogs(Context context) {
        return context.checkCallingOrSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasOverlay(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public static boolean isBatteryWhitelisted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager power = (PowerManager) context.getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static String ensureToken(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("home_settings", MODE_PRIVATE);
        String token = prefs.getString(PREF_TOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            prefs.edit().putString(PREF_TOKEN, token).apply();
        }
        return token;
    }

    public static String regenerateToken(Context context) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        context.getSharedPreferences("home_settings", MODE_PRIVATE).edit().putString(PREF_TOKEN, token).apply();
        return token;
    }

    public static void startIfEnabled(Context context) {
        if (!isSupportedDevice()) return;
        BootReceiver.enableFireTvBootPriority(context);
        android.content.SharedPreferences prefs = context.getSharedPreferences("home_settings", MODE_PRIVATE);
        if (!shouldKeepRunning(prefs.getBoolean(PREF_ENABLED, false), isStandbyRecoveryEnabled(context))
                || !hasReadLogs(context)) {
            try { context.stopService(new Intent(context, FireOsHomeButtonService.class)); }
            catch (RuntimeException ignored) { }
            return;
        }
        if (prefs.getBoolean(PREF_ENABLED, false)) ensureToken(context);
        try {
            context.startService(new Intent(context, FireOsHomeButtonService.class));
        } catch (RuntimeException ignored) { }
    }

    public static void stop(Context context) {
        if (isSupportedDevice()) { startIfEnabled(context); return; }
        try { context.stopService(new Intent(context, FireOsHomeButtonService.class)); }
        catch (RuntimeException ignored) { }
    }

    static boolean shouldKeepRunning(boolean homeEnabled, boolean afterStandby) {
        return homeEnabled || afterStandby;
    }

    static boolean isStandbyRecoveryEnabled(Context context) {
        return context.getSharedPreferences("home_settings", MODE_PRIVATE)
                .getBoolean("autostart_after_standby", true);
    }

    static void recoverAfterStandby(Context context) {
        if (!isSupportedDevice() || !isStandbyRecoveryEnabled(context) || !hasReadLogs(context)) return;
        try {
            context.startService(new Intent(context, FireOsHomeButtonService.class).setAction(ACTION_RECOVER_STANDBY));
        } catch (RuntimeException error) {
            android.util.Log.w("JojoFireWake", "Wake service request failed", error);
        }
    }

    static void onHomeVisibilityChanged(boolean resumed) {
        if (!isSupportedDevice()) return;
        homeResumed = resumed;
        FireOsHomeButtonService service = activeService;
        if (service != null) service.wakeHandler.post(() -> {
            if (!service.running.get()) return;
            if (resumed) {
                service.resumedComponent = "com.jojotv.home/.MainActivity";
                service.wakeRecovery.observeHome();
            }
            if (service.wakeRecovery.active) service.scheduleWakeCheck();
        });
    }

    public static void test(Context context) {
        context.getSharedPreferences("home_settings", MODE_PRIVATE)
                .edit().putString(PREF_LAST_TRIGGER, "Test").apply();
        launchHome(context);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (!isSupportedDevice()) { stopSelf(); return; }
        activeService = this;
        triggerMethod = getSharedPreferences("home_settings", MODE_PRIVATE)
                .getString(PREF_METHOD, METHOD_OZ_DCS);
        Notification.Builder notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Bouton Home Fire Stick", NotificationManager.IMPORTANCE_LOW));
            notification = new Notification.Builder(this, CHANNEL);
        } else {
            notification = new Notification.Builder(this);
        }
        // Supported Fire OS 6 devices are API 25: they also need a foreground service.
        startForeground(4811, notification.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Jojo Home")
                .setContentText("Retour Jojo Home Fire Stick actif")
                .setOngoing(true).build());
        IntentFilter standbyFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        standbyFilter.addAction(Intent.ACTION_SCREEN_OFF);
        standbyFilter.addAction(Intent.ACTION_USER_PRESENT);
        standbyFilter.addAction(Intent.ACTION_DREAMING_STARTED);
        standbyFilter.addAction(Intent.ACTION_DREAMING_STOPPED);
        try {
            registerReceiver(standbyReceiver, standbyFilter);
            standbyReceiverRegistered = true;
        } catch (RuntimeException ignored) { }
        running.set(true);
        readerThread = new Thread(this::readLogcat, "JojoHome-FireOsHome");
        readerThread.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        android.content.SharedPreferences prefs = getSharedPreferences("home_settings", MODE_PRIVATE);
        if (!isSupportedDevice()
                || !shouldKeepRunning(prefs.getBoolean(PREF_ENABLED, false), isStandbyRecoveryEnabled(this))
                || !hasReadLogs(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        triggerMethod = prefs.getString(PREF_METHOD, METHOD_OZ_DCS);
        FireTvRemoteClient.prepare(this);
        if (intent != null && ACTION_RECOVER_STANDBY.equals(intent.getAction())) {
            beginStandbyRecovery();
        } else if (isInteractive() && (prefs.getBoolean(PREF_PENDING_WAKE, false)
                || (intent == null && isAmazonHomeForeground()))) {
            // START_STICKY may recreate us after the wake broadcast was missed.
            beginStandbyRecovery();
        }
        return START_STICKY;
    }

    private boolean isInteractive() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private boolean isAmazonHomeForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        try {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            return !tasks.isEmpty() && tasks.get(0).topActivity != null
                    && isAmazonHomeComponent(tasks.get(0).topActivity.flattenToShortString());
        } catch (RuntimeException ignored) { return false; }
    }

    private void armStandbyRecovery() {
        wakeRecovery.sleep();
        wakeHandler.removeCallbacks(checkWakeRecovery);
        homeResumed = false;
        resumedComponent = "";
        getSharedPreferences("home_settings", MODE_PRIVATE).edit()
                .putBoolean(PREF_PENDING_WAKE, isStandbyRecoveryEnabled(this)).apply();
        FireTvRemoteClient.prepare(this);
    }

    private void beginStandbyRecovery() {
        if (!running.get() || !isStandbyRecoveryEnabled(this)) return;
        if (!wakeRecovery.start(android.os.SystemClock.elapsedRealtime())) return;
        resumedComponent = "";
        getSharedPreferences("home_settings", MODE_PRIVATE).edit().putBoolean(PREF_PENDING_WAKE, true).apply();
        android.util.Log.i("JojoFireWake", "Verifying Home after standby");
        scheduleWakeCheck();
    }

    private void scheduleWakeCheck() {
        wakeHandler.removeCallbacks(checkWakeRecovery);
        wakeHandler.post(checkWakeRecovery);
    }

    private void checkWakeRecovery() {
        if (!running.get() || !isStandbyRecoveryEnabled(this)) wakeRecovery.cancel();
        boolean otherApp = !resumedComponent.isEmpty() && !isAmazonHomeComponent(resumedComponent)
                && !"com.jojotv.home/.MainActivity".equals(resumedComponent)
                && !"com.jojotv.home/com.jojotv.home.MainActivity".equals(resumedComponent);
        int action = wakeRecovery.nextAction(android.os.SystemClock.elapsedRealtime(), isInteractive(), homeResumed, otherApp);
        if (action == WakeRecoveryTracker.DONE) {
            wakeHandler.removeCallbacks(checkWakeRecovery);
            getSharedPreferences("home_settings", MODE_PRIVATE).edit().putBoolean(PREF_PENDING_WAKE, false).apply();
            android.util.Log.i("JojoFireWake", "Wake verification ended; Home observed=" + wakeRecovery.homeSeen);
            return;
        }
        if (action == WakeRecoveryTracker.LAUNCH) {
            int cycle = wakeRecovery.generation;
            java.util.function.BooleanSupplier stillNeeded = () -> wakeRecovery.isCurrent(cycle)
                    && isStandbyRecoveryEnabled(this) && isInteractive() && !homeResumed;
            Runnable completed = () -> wakeHandler.post(() -> {
                wakeRecovery.completed(cycle, android.os.SystemClock.elapsedRealtime());
                if (wakeRecovery.isCurrent(cycle)) scheduleWakeCheck();
            });
            android.util.Log.i("JojoFireWake", "Wake Home attempt " + wakeRecovery.attempts);
            if (wakeRecovery.attempts == WakeRecoveryTracker.MAX_ATTEMPTS) {
                try { if (stillNeeded.getAsBoolean()) launchHomeFallback(this); }
                finally { completed.run(); }
            } else {
                FireTvRemoteClient.launch(this, () -> launchHomeFallback(this), stillNeeded, completed);
            }
        }
        wakeHandler.postDelayed(checkWakeRecovery, 250L);
    }

    /** Acknowledging the remote API is not proof that Home stayed visible. */
    static final class WakeRecoveryTracker {
        static final int WAIT = 0, LAUNCH = 1, DONE = 2;
        static final int MAX_ATTEMPTS = 3;
        volatile boolean active;
        volatile int generation;
        int attempts;
        boolean homeSeen;
        private boolean inFlight;
        private long deadline, nextLaunchAt;
        private long lastSuccessAt = -1L;

        boolean start(long now) {
            if (active || (lastSuccessAt >= 0L && now - lastSuccessAt < 1_000L)) return false;
            generation++;
            active = true;
            attempts = 0;
            homeSeen = inFlight = false;
            nextLaunchAt = now;
            deadline = now + 10_000L;
            return true;
        }

        void sleep() { cancel(); lastSuccessAt = -1L; }
        void cancel() { active = false; generation++; }
        boolean isCurrent(int cycle) { return active && generation == cycle; }
        void observeHome() { if (active) homeSeen = true; }

        void completed(int cycle, long now) {
            if (!isCurrent(cycle)) return;
            inFlight = false;
            nextLaunchAt = now + 750L;
        }

        int nextAction(long now, boolean interactive, boolean homeForeground, boolean otherApp) {
            if (!active) return DONE;
            if (now >= deadline) {
                if (interactive && homeForeground) { homeSeen = true; lastSuccessAt = now; }
                cancel();
                return DONE;
            }
            if (!interactive) return WAIT;
            if (homeForeground) {
                homeSeen = true;
                // Observe Amazon's delayed wake restoration, without relaunching
                // an already-visible Home. The 10s observation is not a launch delay.
                return WAIT;
            }
            if (homeSeen && otherApp) { lastSuccessAt = now; cancel(); return DONE; }
            if (inFlight || attempts >= MAX_ATTEMPTS || now < nextLaunchAt) return WAIT;
            attempts++;
            inFlight = true;
            return LAUNCH;
        }
    }

    private void readLogcat() {
        while (running.get()) {
            Process process = null;
            try {
                long startedAt = System.currentTimeMillis();
                // Verified on Fire OS 6: -T 1 keeps the stream live without replaying
                // the log buffer. Filter before reading instead of parsing every log.
                process = Runtime.getRuntime().exec(new String[] { "logcat", "-b", "main", "-b", "system", "-b", "events",
                        "-v", "epoch", "-T", "1", "-e",
                        "amazon.intent.action.HOME_PRESSED|getCustomHomeReceiver: package:|START.*com.amazon.tv.launcher|com[.]amazon[.]tv[.]launcher/"
                                + "|Waking up|Going to sleep|Stopping dream|\\[[0-9]+,[^,]+,[0-9]+,[A-Za-z0-9_.]+/" });
                logcatProcess = process;
                if (!running.get()) return;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (running.get() && (line = reader.readLine()) != null) {
                        handleStandbyLog(line, startedAt);
                        HomeLogEvent event = parseHomeLogEvent(line, startedAt);
                        if (event != null && getSharedPreferences("home_settings", MODE_PRIVATE).getBoolean(PREF_ENABLED, false)
                                && matchesTriggerMethod(event, triggerMethod)) handleHomeSignature(line, event);
                    }
                }
                if (running.get()) android.util.Log.w("JojoFireHome", "Home log reader ended; reconnecting");
            } catch (Exception error) {
                if (running.get()) android.util.Log.w("JojoFireHome", "Home log reader failed; reconnecting", error);
            } finally {
                if (process != null) process.destroy();
                if (logcatProcess == process) logcatProcess = null;
            }
            if (running.get()) {
                try { Thread.sleep(250L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    static String parseStandbyLog(String line, long startedAt) {
        Matcher entry = HOME_LOG_ENTRY.matcher(line);
        if (!entry.matches()) return null;
        try {
            if (Long.parseLong(entry.group(1)) * 1_000L + Long.parseLong(entry.group(2)) < startedAt) return null;
        } catch (NumberFormatException invalidTimestamp) { return null; }
        String source = entry.group(3).trim();
        String message = entry.group(4);
        if ("PowerManagerService".equals(source)) {
            if (message.startsWith("Going to sleep")) return "sleep";
            if (message.startsWith("Waking up")) return "wake";
        }
        if ("DreamManagerService".equals(source) && message.startsWith("Stopping dream")) return "wake";
        if ("am_resume_activity".equals(source) && message.startsWith("[") && message.endsWith("]")) {
            String[] fields = message.substring(1, message.length() - 1).split(",", -1);
            if (fields.length == 4 && fields[3].trim().matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) {
                return fields[3].trim();
            }
        }
        return null;
    }

    static boolean isAmazonHomeComponent(String component) {
        return component.startsWith("com.amazon.tv.launcher/.ui.HomeActivity")
                || component.startsWith("com.amazon.tv.launcher/com.amazon.tv.launcher.ui.HomeActivity");
    }

    private void handleStandbyLog(String line, long startedAt) {
        String event = parseStandbyLog(line, startedAt);
        if (event == null) return;
        wakeHandler.post(() -> {
            if (!running.get()) return;
            if ("sleep".equals(event)) armStandbyRecovery();
            else if ("wake".equals(event)) beginStandbyRecovery();
            else {
                resumedComponent = event;
                if (wakeRecovery.active) scheduleWakeCheck();
                else if (isAmazonHomeComponent(event) && isInteractive()
                        && getSharedPreferences("home_settings", MODE_PRIVATE).getBoolean(PREF_PENDING_WAKE, false)) {
                    // A cached Home resuming also recovers a missed wake broadcast.
                    beginStandbyRecovery();
                }
            }
        });
    }

    static final class HomeLogEvent {
        final long timestamp;
        final String source;

        HomeLogEvent(long timestamp, String source) {
            this.timestamp = timestamp;
            this.source = source;
        }
    }

    static HomeLogEvent parseHomeLogEvent(String line, long startedAt) {
        Matcher entry = HOME_LOG_ENTRY.matcher(line);
        if (!entry.matches()) return null;
        long timestamp;
        try {
            timestamp = Long.parseLong(entry.group(1)) * 1_000L + Long.parseLong(entry.group(2));
        } catch (NumberFormatException invalidTimestamp) {
            return null;
        }
        // Reject history on reconnect, but do not drop a real press just because
        // the app took over two seconds to read it.
        if (timestamp < startedAt) return null;
        String source = entry.group(3).trim();
        String lower = entry.group(4).toLowerCase(Locale.ROOT);
        boolean vendorHome = (source.equals("HomeListener") || source.endsWith(":HomeListener")
                || source.equals("OZ-DCS:HomePressReceiver"))
                && lower.contains("amazon.intent.action.home_pressed");
        boolean customHome = (source.equals("CustomHomeButtonHandler") || source.endsWith(":CustomHomeButtonHandler"))
                && lower.contains("getcustomhomereceiver: package:");
        boolean activityHome = source.equals("ActivityManager") && lower.contains("start ")
                && lower.contains("com.amazon.tv.launcher")
                && (lower.contains("android.intent.category.home") || lower.contains(".ui.homeactivity"));
        boolean activityEvent = false;
        if ((source.equals("am_new_intent") || source.equals("am_create_activity"))
                && lower.startsWith("[") && lower.endsWith("]")) {
            String[] fields = lower.substring(1, lower.length() - 1).split(",", -1);
            // A cached Amazon Home can receive a new MAIN intent without logging
            // START or either vendor broadcast. Match the event's component/action,
            // not an Amazon package mentioned elsewhere in the payload.
            if (fields.length == 8) {
                String component = fields[3].trim();
                activityEvent = (component.startsWith("com.amazon.tv.launcher/.ui.homeactivity")
                        || component.startsWith("com.amazon.tv.launcher/com.amazon.tv.launcher.ui.homeactivity"))
                        && fields[4].trim().equals("android.intent.action.main");
            }
        }

        // Check the emitting tag, never a vendor name copied inside Jojo's own logs.
        return vendorHome || customHome || activityHome || activityEvent ? new HomeLogEvent(timestamp, source) : null;
    }

    static boolean matchesTriggerMethod(HomeLogEvent event, String method) {
        if (METHOD_OZ_DCS.equals(method)) return METHOD_OZ_DCS.equals(event.source);
        if (METHOD_HOME_LISTENER.equals(method)) {
            return METHOD_HOME_LISTENER.equals(event.source) || event.source.endsWith(":HomeListener");
        }
        if (METHOD_CUSTOM_HOME.equals(method)) {
            return METHOD_CUSTOM_HOME.equals(event.source) || event.source.endsWith(":CustomHomeButtonHandler");
        }
        if (METHOD_ACTIVITY_MANAGER.equals(method)) {
            return METHOD_ACTIVITY_MANAGER.equals(event.source) || "am_new_intent".equals(event.source)
                    || "am_create_activity".equals(event.source);
        }
        return true; // AUTO, including an unknown preference displayed as AUTO.
    }

    static final class HomePressTracker {
        private long lastLaunchAt;
        private long lastActivityAt;
        private long lastStartAt;
        private long lastIntentAt;
        private String lastVendorSource;
        private boolean awaitingActivity;
        private HomeLogEvent awaitingPrimary;

        boolean shouldLaunch(HomeLogEvent event) {
            boolean activityManager = "ActivityManager".equals(event.source);
            if (activityManager || "am_new_intent".equals(event.source) || "am_create_activity".equals(event.source)) {
                if (event.timestamp <= (activityManager ? lastStartAt : lastIntentAt)) return false;
                if (activityManager) lastStartAt = event.timestamp;
                else lastIntentAt = event.timestamp;
                lastActivityAt = Math.max(lastActivityAt, event.timestamp);
                // START and the corresponding events-buffer entry are one press.
                // Consume their pair so two quick presses can each launch Jojo.
                boolean pairedPrimary = awaitingPrimary != null
                        && activityManager != "ActivityManager".equals(awaitingPrimary.source)
                        && Math.abs(event.timestamp - awaitingPrimary.timestamp) <= 200L;
                awaitingPrimary = pairedPrimary ? null : event;
                // A vendor signal normally precedes START by only a few milliseconds.
                boolean alreadyLaunched = awaitingActivity
                        && Math.abs(event.timestamp - lastLaunchAt) <= 200L;
                awaitingActivity = false;
                if (alreadyLaunched || pairedPrimary) return false;
                // Each distinct HOME intent is a new press, even inside the vendor
                // deduplication window. A late HomeListener must not hide it.
            } else {
                if (lastActivityAt > 0L && event.timestamp - lastActivityAt < 1_500L) return false;
                long gap = event.timestamp - lastLaunchAt;
                if (lastLaunchAt > 0L && (gap < DEBOUNCE_MS
                        || (!event.source.equals(lastVendorSource) && gap < 1_500L))) return false;
                lastVendorSource = event.source;
                awaitingActivity = true;
            }
            lastLaunchAt = event.timestamp;
            return true;
        }
    }

    private void handleHomeSignature(String line, HomeLogEvent event) {
        if (!homePressTracker.shouldLaunch(event)) return;
        // Let TiviMate return to its own navigation after playback. The
        // launcher redirect is only for other foreground applications.
        if (isTiviMateForeground()) {
            android.util.Log.i("JojoFireHome", "Home redirect skipped: TiviMate is foreground");
            return;
        }
        android.util.Log.i("JojoFireHome", "Home event from " + event.source);
        getSharedPreferences("home_settings", MODE_PRIVATE).edit()
                .putString(PREF_LAST_TRIGGER, line.length() > 180 ? line.substring(line.length() - 180) : line).apply();
        launchHome(this);
    }

    private boolean isTiviMateForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        try {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks.isEmpty() || tasks.get(0).topActivity == null) return false;
            String packageName = tasks.get(0).topActivity.getPackageName();
            return "ar.tvplayer.tv".equals(packageName)
                    || (packageName != null && packageName.toLowerCase(Locale.ROOT).contains("tivimate"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void launchHome(Context context) {
        FireTvRemoteClient.launch(context, () -> launchHomeFallback(context));
    }

    private static void launchHomeFallback(Context context) {
        Intent launch = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                // Launcher Manager uses a component-targeted MAIN intent with
                // NEW_TASK only. REORDER_TO_FRONT/SINGLE_TOP causes a slow
                // warm transition on Fire OS 6 when the Amazon launcher has
                // just been brought forward.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            PendingIntent pending = PendingIntent.getActivity(
                    context, 4602, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            pending.send();
        } catch (Exception pendingLaunchError) {
            try { context.startActivity(launch); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void onDestroy() {
        running.set(false);
        if (activeService == this) activeService = null;
        wakeRecovery.cancel();
        wakeHandler.removeCallbacksAndMessages(null);
        Process process = logcatProcess;
        if (process != null) process.destroy();
        if (readerThread != null) readerThread.interrupt();
        if (standbyReceiverRegistered) {
            try { unregisterReceiver(standbyReceiver); }
            catch (IllegalArgumentException ignored) { }
            standbyReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
