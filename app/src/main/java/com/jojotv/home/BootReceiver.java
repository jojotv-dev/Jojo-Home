package com.jojotv.home;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

/** Restores Jojo Home after boot and, when selected, after TV standby. */
public final class BootReceiver extends BroadcastReceiver {
    private static boolean bootLaunchScheduled;
    private static long lastStandbyLaunchAt;

    /** Enabled only on supported Fire TVs; the ordinary receiver keeps its priority. */
    public static final class FireTvBootReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (FireOsHomeButtonService.isSupportedDevice()) {
                new BootReceiver().onReceive(context, intent);
            }
        }
    }

    static void enableFireTvBootPriority(Context context) {
        if (!FireOsHomeButtonService.isSupportedDevice()) return;
        ComponentName receiver = new ComponentName(context, FireTvBootReceiver.class);
        PackageManager manager = context.getPackageManager();
        try {
            if (manager.getComponentEnabledSetting(receiver) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                manager.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException error) {
            android.util.Log.w("JojoFireBoot", "Priority receiver unavailable; ordinary boot receiver retained", error);
        }
    }

    static void requestHomeBeforeService(Runnable requestHome, Runnable startService) {
        try { requestHome.run(); }
        finally { startService.run(); }
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        String event = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ? "locked_boot"
                : Intent.ACTION_BOOT_COMPLETED.equals(action) ? "boot"
                : Intent.ACTION_USER_UNLOCKED.equals(action) ? "user_unlocked"
                : Intent.ACTION_SCREEN_ON.equals(action) ? "screen_on"
                : Intent.ACTION_USER_PRESENT.equals(action) ? "user_present"
                : FireOsHomeButtonService.isSupportedDevice() && Intent.ACTION_DREAMING_STOPPED.equals(action) ? "dreaming_stopped"
                : null;
        if (event == null) return;
        boolean directBoot = "locked_boot".equals(event);
        android.content.SharedPreferences settings = directBoot
                ? context.createDeviceProtectedStorageContext().getSharedPreferences("home_settings", Context.MODE_PRIVATE)
                : context.getSharedPreferences("home_settings", Context.MODE_PRIVATE);
        boolean fireTvBoot = FireOsHomeButtonService.isSupportedDevice()
                && ("locked_boot".equals(event) || "boot".equals(event) || "user_unlocked".equals(event));
        Runnable startHomeService = () -> {
            if (FireOsHomeButtonService.isSupportedDevice()) {
                FireOsHomeButtonService.startIfEnabled(context);
            }
        };
        if (!fireTvBoot) startHomeService.run();
        boolean startAtBoot = directBoot || (settings.contains("start_at_boot") ? settings.getBoolean("start_at_boot", false) : true);
        boolean autostartAfterStandby = settings.contains("autostart_after_standby") ? settings.getBoolean("autostart_after_standby", false) : true;
        if (!AutostartRules.shouldLaunch(event, startAtBoot, autostartAfterStandby,
                FireOsHomeButtonService.isSupportedDevice())) {
            if (fireTvBoot) startHomeService.run();
            return;
        }
        if (!fireTvBoot && FireOsHomeButtonService.isSupportedDevice()) {
            // Fire wake-ups are verified by the service, not locked out for 15s
            // just because its first asynchronous request was submitted.
            FireOsHomeButtonService.recoverAfterStandby(context);
            return;
        }
        if (fireTvBoot) {
            // Fire OS keeps Amazon as its resolver winner even when Jojo Home
            // declares MAIN_HOME. Launch Jojo explicitly at the earliest boot
            // broadcast instead of waiting for (or briefly showing) Amazon Home.
            synchronized (BootReceiver.class) {
                if (bootLaunchScheduled) return;
                bootLaunchScheduled = true;
            }
            try {
                requestHomeBeforeService(() -> {
                    Intent home = new Intent(context.getApplicationContext(), MainActivity.class)
                            .setAction(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addCategory(Intent.CATEGORY_DEFAULT)
                            // Same task contract as Projectivy, without CLEAR_TASK:
                            // clearing the task was responsible for a black flash.
                            .setFlags(0x10220000 | Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("android.intent.extra.FROM_BOOT", true);
                    android.util.Log.i("JojoFireBoot", "Home request (" + event + ") at "
                            + android.os.SystemClock.elapsedRealtime() + "ms after boot");
                    context.getApplicationContext().startActivity(home);
                }, startHomeService);
            } catch (RuntimeException error) {
                android.util.Log.w("JojoFireBoot", "Boot Home request failed", error);
                synchronized (BootReceiver.class) { bootLaunchScheduled = false; }
            }
            return;
        }
        // Fire OS 6 accepts the launcher request during locked boot on this
        // device. Keep that earliest route; a failed start resets the guard so
        // BOOT_COMPLETED or USER_UNLOCKED can retry safely.
        boolean bootEvent = "locked_boot".equals(event) || "boot".equals(event) || "user_unlocked".equals(event);
        boolean standbyEvent = "screen_on".equals(event) || "user_present".equals(event) || "dreaming_stopped".equals(event);
        if (standbyEvent) {
            synchronized (BootReceiver.class) {
                long now = android.os.SystemClock.elapsedRealtime();
                // Fire OS normally sends SCREEN_ON and USER_PRESENT together.
                // They represent one wake-up, not two Home launches.
                if (now - lastStandbyLaunchAt < 15_000L) return;
                lastStandbyLaunchAt = now;
            }
        }
        if (bootEvent) {
            synchronized (BootReceiver.class) {
                if (bootLaunchScheduled) return;
                bootLaunchScheduled = true;
            }
        }
        // Philips can restore its Sources page after BOOT_COMPLETED. USER_UNLOCKED
        // is delivered once the TV is genuinely ready, so reassert Home promptly
        // there instead of leaving the user on the empty HDMI screen.
        long delay = directBoot || "user_unlocked".equals(event) || standbyEvent
                ? 0L
                : AutostartRules.delayMillis(settings.getInt("autostart_delay_seconds", 8));
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable launchHome = () -> {
            try {
                Runnable fallback = () -> {
                    Intent home = new Intent(appContext, MainActivity.class);
                    AutostartRules.prepareHomeLaunchIntent(home);
                    appContext.startActivity(home);
                };
                fallback.run();
            } catch (RuntimeException error) {
                FireOsHomeOverlayService.hideBootCover(appContext);
                synchronized (BootReceiver.class) {
                    bootLaunchScheduled = false;
                }
            } finally {
                if (pending != null) pending.finish();
            }
        };
        handler.postDelayed(launchHome, delay);
    }
}
