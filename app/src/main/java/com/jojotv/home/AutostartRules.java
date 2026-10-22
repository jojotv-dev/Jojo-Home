package com.jojotv.home;

import android.content.Intent;

/** Platform-independent rules for Jojo Home's automatic launcher recovery. */
final class AutostartRules {
    private AutostartRules() { }

    static int homeLaunchFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME;
    }

    static boolean shouldLaunch(String event, boolean enabledAtBoot, boolean afterStandby) {
        if (!enabledAtBoot) return false;
        return "locked_boot".equals(event)
                || "boot".equals(event)
                || "user_unlocked".equals(event)
                || ("screen_on".equals(event) && afterStandby)
                || ("user_present".equals(event) && afterStandby)
                || ("dreaming_stopped".equals(event) && afterStandby);
    }

    static boolean shouldLaunch(String event, boolean enabledAtBoot, boolean afterStandby, boolean fireTv) {
        if (fireTv && ("screen_on".equals(event) || "user_present".equals(event)
                || "dreaming_stopped".equals(event))) return afterStandby;
        return shouldLaunch(event, enabledAtBoot, afterStandby);
    }

    static long delayMillis(int seconds) {
        return Math.max(0, Math.min(60, seconds)) * 1_000L;
    }

    static void prepareHomeLaunchIntent(Intent intent) {
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(intent.getFlags() | homeLaunchFlags());
    }
}
