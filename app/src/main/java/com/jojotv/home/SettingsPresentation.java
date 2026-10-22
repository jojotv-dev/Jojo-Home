package com.jojotv.home;

/** Shared constants for the Jojo Home settings chrome. */
final class SettingsPresentation {
    private SettingsPresentation() { }

    static boolean showsDateTime() {
        return true;
    }

    static int cardCornerRadiusDp() {
        return 14;
    }

    static int panelCornerRadiusDp() { return 32; }
    static int rowCornerRadiusDp() { return 8; }
    static int pageInsetDp() { return 24; }
    static int panelGapDp() { return 24; }
    static int rowMinHeightDp() { return 72; }
    static float sidebarWeight() { return .27f; }

    static String cleanValue(String value) {
        return value == null ? "" : value.replaceFirst("^\\s*\u2713\\s*", "").trim();
    }

    static boolean isToggleValue(String value) {
        String clean = cleanValue(value);
        return "Activ\u00e9".equals(clean) || "D\u00e9sactiv\u00e9".equals(clean);
    }

    static boolean toggleChecked(String value) { return "Activ\u00e9".equals(cleanValue(value)); }

    static int toggledChoice(String[] values, int selected) {
        return values != null && values.length == 2 && selected >= 0 && selected < 2
                && isToggleValue(values[0]) && isToggleValue(values[1])
                && toggleChecked(values[0]) != toggleChecked(values[1]) ? 1 - selected : -1;
    }

    static int backgroundColor() { return 0xff000000; }
    static int surfaceColor() { return 0xff1b1f23; }
    static int borderColor() { return 0xff363c42; }
    static int textColor() { return 0xfff3f4f6; }
    static int secondaryTextColor() { return 0xffbec4ca; }
    static int errorTextColor() { return 0xffffb4ab; }
}
