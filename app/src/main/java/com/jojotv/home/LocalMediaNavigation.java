package com.jojotv.home;

/**
 * Android TV firmwares often omit DocumentsUI. A resolver can still be exposed
 * even when it has no target, which results in the system "no application"
 * toast. Use Jojo Home's own storage navigator for this TV flow.
 */
final class LocalMediaNavigation {
    private LocalMediaNavigation() { }

    static boolean usesBuiltInBrowser() {
        return true;
    }

    static boolean supportsWallpaperFile(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".png")
                || value.endsWith(".webp") || value.endsWith(".gif") || value.endsWith(".bmp")
                || value.endsWith(".mp4") || value.endsWith(".mkv") || value.endsWith(".webm")
                || value.endsWith(".avi") || value.endsWith(".mov");
    }
}
