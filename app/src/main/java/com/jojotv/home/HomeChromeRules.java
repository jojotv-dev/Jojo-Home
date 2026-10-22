package com.jojotv.home;

final class HomeChromeRules {
    private HomeChromeRules() { }

    static final String HOME_BARS_STYLE = "home_bars_style";
    static final String[] HOME_BARS_VALUES = { "transparent", "black", "frosted" };
    static final String[] HOME_BARS_LABELS = { "Transparent", "Bandes noires", "Verre dépoli" };
    static final String HOME_HERO_STYLE = "home_hero_style";
    static final String[] HOME_HERO_VALUES = { "current", "muted", "airy", "editorial" };
    static final String[] HOME_HERO_LABELS = {
            "Cinéma immersif", "Fiche feutrée", "Composition aérée", "Éditorial compact"
    };

    static int homeBarsChoice(String value) {
        for (int index = 0; index < HOME_BARS_VALUES.length; index++) {
            if (HOME_BARS_VALUES[index].equals(value)) return index;
        }
        return 0;
    }

    static int homeHeroChoice(String value) {
        for (int index = 0; index < HOME_HERO_VALUES.length; index++) {
            if (HOME_HERO_VALUES[index].equals(value)) return index;
        }
        return 0;
    }

    static String homeHeroEpisodeLabel(String value) {
        if (value == null) return "";
        java.util.regex.Matcher episode = java.util.regex.Pattern
                .compile("(?i)^S(\\d{1,2})E(\\d{1,3})(.*)$").matcher(value.trim());
        if (!episode.matches()) return value;
        return "Saison " + Integer.parseInt(episode.group(1)) + " · Épisode "
                + Integer.parseInt(episode.group(2)) + episode.group(3);
    }

    static String clockPattern() { return "HH:mm"; }
    static boolean showsDateTimeInEveryHeader() { return true; }
    // Focused app banners are intentionally scaled beyond their resting tile.
    // Let the surrounding Home rail expose that enlarged area instead of
    // cutting the left/right edges (and the first banner's top edge).
    static boolean clipsFocusedAppIcon() { return false; }
    static boolean clipsManagedAppIcon() { return true; }
}
