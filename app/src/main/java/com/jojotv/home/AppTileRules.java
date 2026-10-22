package com.jojotv.home;

/** Presentation policy that separates wide TV banners from square adaptive icons. */
final class AppTileRules {
    private AppTileRules() { }

    // AndroidX TV CardDefaults uses RoundedCornerShape(8.dp):
    // https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/Card.kt
    // Scale that card shape down for previews smaller than a 160x90 TV banner.
    // Google publishes no universal launcher-icon radius in its icon guidelines.
    static float appIconCornerRadius(float width, float height, float density) {
        return Math.min(8f * density, Math.min(width, height) * 8f / 90f);
    }

    static boolean usesCenteredFallbackIcon(boolean hasBanner, boolean hasCustomIcon) {
        return !hasBanner && !hasCustomIcon;
    }

    static boolean usesApplicationBannerBeforeActivityBanner() { return true; }

    /** Use the official black CANAL+ TV banner rather than the generic blue app banner. */
    static boolean usesOfficialCanalPlusBanner(String packageName) {
        return "com.canal.fai".equals(packageName);
    }

    static boolean scalesClippedStageOnFocus() {
        return false;
    }

    static boolean elevatesTransparentStageOnFocus() { return false; }

    static boolean usesTransparentStageForStandardApps() {
        return true;
    }

    static int fallbackTileColor(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("netflix")) return 0xFFFFFFFF;
        if (value.contains("amazon.firebat")) return 0xFF00A9E0;
        return 0x00000000;
    }

    static boolean requiresRecenteredBanner(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase(java.util.Locale.ROOT);
        return value.contains("netflix") || value.contains("amazon.firebat");
    }

    static boolean normalizesFireOsBanner(String packageName, boolean fireOs, boolean cinemaGradient) {
        return fireOs && cinemaGradient && requiresRecenteredBanner(packageName);
    }

    static float fallbackLogoOffsetFraction() { return 0.125f; }
}
