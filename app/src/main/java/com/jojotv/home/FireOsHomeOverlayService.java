package com.jojotv.home;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.ActivityManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/** Fire OS 6 Home redirect for devices using the system-dialog overlay path. */
public final class FireOsHomeOverlayService extends Service {
    private static final String ACTION_START = "com.jojotv.home.action.START_HOME_OVERLAY";
    private static final String ACTION_SHOW_BOOT_COVER = "com.jojotv.home.action.SHOW_BOOT_COVER";
    private static final String ACTION_HIDE_BOOT_COVER = "com.jojotv.home.action.HIDE_BOOT_COVER";
    private static final String ACTION_SUSPEND_REDIRECT = "com.jojotv.home.action.SUSPEND_HOME_REDIRECT";
    private static final String EXTRA_SUSPEND_MILLIS = "suspend_millis";
    private static final long COVER_FAILSAFE_MILLIS = 4_000L;
    private static volatile FireOsHomeOverlayService activeInstance;

    private WindowManager windowManager;
    private HomeKeyView homeKeyView;
    private View bootCover;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long redirectSuppressedUntil;
    private long lastRedirectAt;
    private boolean systemDialogsReceiverRegistered;
    private final Runnable coverFailsafe = this::hideBootCoverNow;
    private final BroadcastReceiver systemDialogsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent == null ? null : intent.getAction())) return;
            String reason = intent.getStringExtra("reason");
            if (reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("homekey")) redirectHome();
        }
    };

    public static boolean isSupportedDevice() {
        return FireOsHomeButtonService.isSupportedDevice();
    }

    public static boolean hasOverlayPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public static void ensureStarted(Context context, boolean showBootCover) {
        if (!isSupportedDevice() || !hasOverlayPermission(context)) return;
        Intent service = new Intent(context, FireOsHomeOverlayService.class)
                .setAction(showBootCover ? ACTION_SHOW_BOOT_COVER : ACTION_START);
        try {
            context.startService(service);
        } catch (RuntimeException ignored) {
            // Fire OS may still be bringing up the user during direct boot.
        }
    }

    public static void hideBootCover(Context context) {
        if (!isSupportedDevice() || !hasOverlayPermission(context)) return;
        FireOsHomeOverlayService instance = activeInstance;
        if (instance != null) instance.hideBootCoverNow();
        try {
            context.startService(new Intent(context, FireOsHomeOverlayService.class).setAction(ACTION_HIDE_BOOT_COVER));
        } catch (RuntimeException ignored) { }
    }

    public static void suspendRedirect(Context context, long durationMillis) {
        if (!isSupportedDevice() || !hasOverlayPermission(context)) return;
        FireOsHomeOverlayService instance = activeInstance;
        if (instance != null) instance.applyRedirectSuspension(durationMillis);
        Intent service = new Intent(context, FireOsHomeOverlayService.class)
                .setAction(ACTION_SUSPEND_REDIRECT)
                .putExtra(EXTRA_SUSPEND_MILLIS, Math.max(0L, durationMillis));
        try {
            context.startService(service);
        } catch (RuntimeException ignored) { }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, FireOsHomeOverlayService.class));
        } catch (RuntimeException ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            registerReceiver(systemDialogsReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            systemDialogsReceiverRegistered = true;
        } catch (RuntimeException ignored) { }
        attachHomeKeyView();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        attachHomeKeyView();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_SHOW_BOOT_COVER.equals(action)) showBootCover();
        else if (ACTION_HIDE_BOOT_COVER.equals(action)) hideBootCoverNow();
        else if (ACTION_SUSPEND_REDIRECT.equals(action)) {
            long duration = intent == null ? 0L : intent.getLongExtra(EXTRA_SUSPEND_MILLIS, 0L);
            applyRedirectSuspension(duration);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        removeView(homeKeyView);
        homeKeyView = null;
        hideBootCoverNow();
        handler.removeCallbacksAndMessages(null);
        if (systemDialogsReceiverRegistered) {
            try { unregisterReceiver(systemDialogsReceiver); }
            catch (IllegalArgumentException ignored) { }
            systemDialogsReceiverRegistered = false;
        }
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void attachHomeKeyView() {
        if (homeKeyView != null || windowManager == null || !hasOverlayPermission(this)) return;
        HomeKeyView candidate = new HomeKeyView(this);
        candidate.setFocusable(false);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                0,
                0,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            windowManager.addView(candidate, params);
            homeKeyView = candidate;
        } catch (RuntimeException ignored) { }
    }

    private void showBootCover() {
        if (bootCover != null || windowManager == null || !hasOverlayPermission(this)) return;
        View candidate = new View(this);
        candidate.setBackgroundColor(Color.BLACK);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                // An opaque Surface can stop Fire OS from drawing MainActivity
                // underneath it, which prevents the first-frame callback from
                // ever removing the cover. Keep the visual black but force
                // composition of the activity below.
                PixelFormat.TRANSLUCENT);
        try {
            windowManager.addView(candidate, params);
            bootCover = candidate;
            handler.removeCallbacks(coverFailsafe);
            handler.postDelayed(coverFailsafe, COVER_FAILSAFE_MILLIS);
        } catch (RuntimeException ignored) { }
    }

    private void hideBootCoverNow() {
        handler.removeCallbacks(coverFailsafe);
        removeView(bootCover);
        bootCover = null;
    }

    private void applyRedirectSuspension(long durationMillis) {
        redirectSuppressedUntil = SystemClock.elapsedRealtime() + Math.max(0L, durationMillis);
        hideBootCoverNow();
    }

    private void removeView(View view) {
        if (view == null || windowManager == null) return;
        try {
            windowManager.removeViewImmediate(view);
        } catch (RuntimeException ignored) { }
    }

    private int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
    }

    private boolean redirectEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager users = (UserManager) getSystemService(USER_SERVICE);
            if (users != null && !users.isUserUnlocked()) return true;
        }
        return getSharedPreferences("home_settings", MODE_PRIVATE)
                .getBoolean("fire_tv_home_redirect", false);
    }

    private void redirectHome() {
        if (!redirectEnabled()) return;
        // TiviMate owns its own playback/navigation task. Do not replace it
        // with Jojo Home when the user exits a channel, film or series.
        if (isTiviMateForeground()) return;
        long now = SystemClock.elapsedRealtime();
        if (now < redirectSuppressedUntil || now - lastRedirectAt < 500L) return;
        lastRedirectAt = now;
        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(launch);
        } catch (RuntimeException directLaunchError) {
            try {
                PendingIntent pending = PendingIntent.getActivity(
                        this,
                        4602,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                pending.send();
            } catch (PendingIntent.CanceledException ignored) {
                hideBootCoverNow();
            }
        }
    }

    private boolean isTiviMateForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        try {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks.isEmpty() || tasks.get(0).topActivity == null) return false;
            String packageName = tasks.get(0).topActivity.getPackageName();
            return "ar.tvplayer.tv".equals(packageName)
                    || (packageName != null && packageName.toLowerCase(java.util.Locale.ROOT).contains("tivimate"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private final class HomeKeyView extends View {
        HomeKeyView(Context context) {
            super(context);
        }

        // Hidden framework callback still dispatched to system-overlay windows on Fire OS 6.
        @SuppressWarnings("unused")
        public void onCloseSystemDialogs(String reason) {
            if (reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("homekey")) {
                redirectHome();
            }
        }
    }
}
