package com.jojotv.home;

/** Small, platform-independent policy for the Watch Next observer. */
final class WatchNextRules {
    static final long RESUME_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private WatchNextRules() { }

    static boolean shouldObserve(int sdkInt, boolean permissionGranted) {
        return sdkInt >= 26 && permissionGranted;
    }

    static long refreshDelayMillis() {
        return 180L;
    }

    static boolean isFreshResume(long engagementTimeMillis, long nowMillis) {
        return engagementTimeMillis > 0L
                && engagementTimeMillis <= nowMillis
                && nowMillis - engagementTimeMillis <= RESUME_RETENTION_MS;
    }
}
