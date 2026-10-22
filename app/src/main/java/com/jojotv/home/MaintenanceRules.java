package com.jojotv.home;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Defines the intentionally narrow scope of each maintenance reset. */
final class MaintenanceRules {
    private MaintenanceRules() { }

    static List<String> keysFor(String family) {
        if ("appearance".equals(family)) return Arrays.asList(
                "icon_size", "home_rows", "background_color", "dynamic_wallpaper",
                "dynamic_wallpaper_mode", "weather_widget", "show_app_labels", "wallpaper_uri", "wallpaper_video");
        if ("organization".equals(family)) return Arrays.asList(
                "app_order", "hidden_apps", "content_apps", "app_sort_mode",
                "continue_watching_position", "continue_watching_layout", "continue_watching_enabled", "continue_watching_card_limit");
        return Collections.emptyList();
    }
}
