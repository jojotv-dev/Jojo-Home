package com.jojotv.home;

/** Pure visibility and sizing rules for continue-watching rows. */
final class ContentPresentationRules {
    private ContentPresentationRules() { }

    static boolean showsContinueWatching(boolean enabled) {
        return enabled;
    }

    static int visibleCardCount(int configuredLimit, int availableCount) {
        return Math.min(Math.max(1, configuredLimit), Math.max(0, availableCount));
    }
}
