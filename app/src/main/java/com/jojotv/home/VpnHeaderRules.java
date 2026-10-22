package com.jojotv.home;

final class VpnHeaderRules {
    private VpnHeaderRules() { }

    static boolean showsVpn(boolean activeVpn) { return activeVpn; }

    static int headerGapDp(boolean activeVpn) { return activeVpn ? 4 : 12; }

    static boolean shouldLaunchProvider(int launchableProviders) { return launchableProviders == 1; }
}
