package com.jojotv.home;

final class SettingsGridRules {
    private SettingsGridRules() { }

    static boolean lastRowNeedsPlaceholder(int cellsInRow) { return cellsInRow == 1; }
    static boolean linksColumnsForDpad() { return true; }
}
