package com.jojotv.home;

/** Pure presentation rules for the application row. */
final class AppPresentationRules {
    private AppPresentationRules() { }

    static boolean isAlphabeticalOrder(String mode) {
        return "alphabetical".equals(mode);
    }

    static float labelAlpha(boolean alwaysVisible, boolean focused) {
        return alwaysVisible || focused ? 1f : 0f;
    }
}
