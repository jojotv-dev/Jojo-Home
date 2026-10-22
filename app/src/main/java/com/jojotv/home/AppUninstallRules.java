package com.jojotv.home;

final class AppUninstallRules {
    private AppUninstallRules() { }

    static String menuLabel(boolean systemApp) {
        return systemApp ? "Désinstaller les mises à jour" : "Désinstaller";
    }

    static boolean usesPackageUninstaller(boolean systemApp) {
        return true;
    }

    static String androidUninstallAction() {
        return "android.intent.action.DELETE";
    }

    static boolean opensContextMenuOnDpadLongPress(int action, int keyCode, int repeatCount) {
        return action == 0 && repeatCount == 1 && (keyCode == 23 || keyCode == 66);
    }
}
