package com.jojotv.home;

/** Stable 16:9-ish dimensions for the selectable continue-watching cards. */
final class ContinueWatchingTileRules {
    private ContinueWatchingTileRules() { }

    static int widthDp(String size) {
        if ("very_small".equals(size)) return 160;
        if ("small".equals(size)) return 200;
        if ("medium".equals(size)) return 250;
        if ("very_large".equals(size)) return 360;
        return 300;
    }

    static int heightDp(String size) {
        if ("very_small".equals(size)) return 91;
        if ("small".equals(size)) return 114;
        if ("medium".equals(size)) return 142;
        if ("very_large".equals(size)) return 203;
        return 170;
    }

    static String label(String size) {
        if ("very_small".equals(size)) return "Très petites";
        if ("small".equals(size)) return "Petite";
        if ("medium".equals(size)) return "Moyenne";
        if ("very_large".equals(size)) return "Très grandes";
        return "Grande";
    }

    static int progressWidthDp(int cardWidthDp, int horizontalInsetDp, float ratio) {
        float clampedRatio = Math.max(0f, Math.min(1f, ratio));
        return Math.round(Math.max(0, cardWidthDp - horizontalInsetDp * 2) * clampedRatio);
    }

    static int metadataHeightDp() { return 64; }
}
