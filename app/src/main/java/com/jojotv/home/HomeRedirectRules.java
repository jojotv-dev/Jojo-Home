package com.jojotv.home;

/** Restricts the Fire TV redirect to the Amazon launcher window only. */
final class HomeRedirectRules {
    static final int VIEW_FOCUSED = 8;
    static final int WINDOW_STATE_CHANGED = 32;
    static final int WINDOWS_CHANGED = 4194304;

    private HomeRedirectRules() { }

    static boolean shouldRedirect(boolean enabled, int eventType, String packageName) {
        return enabled
                && (eventType == VIEW_FOCUSED
                || eventType == WINDOW_STATE_CHANGED
                || eventType == WINDOWS_CHANGED)
                && isAmazonLauncher(packageName);
    }

    static long redirectDelayMillis() {
        return 180L;
    }

    private static boolean isAmazonLauncher(String packageName) {
        return "com.amazon.tv.launcher".equals(packageName)
                || "com.amazon.firetv.launcher".equals(packageName)
                || "com.amazon.device.launcher".equals(packageName);
    }
}
