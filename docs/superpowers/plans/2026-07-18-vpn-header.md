# VPN Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a VPN command between Network and Sources only while the active network uses a VPN transport, without truncating the full French date or clock.

**Architecture:** `VpnHeaderRules` owns deterministic visibility, compact-gap and fallback decisions. `MainActivity` reads active `NetworkCapabilities`, creates the conditional header command, and resolves a launchable VPN provider from declared `VpnService` implementations before opening Android VPN settings as a fallback.

**Tech Stack:** Java, Android `ConnectivityManager`, `NetworkCapabilities`, `PackageManager`, JUnit 4.

## Global Constraints

- Insert VPN after Réseau and before Sources only when `TRANSPORT_VPN` is active.
- Preserve the existing full French date and 24-hour clock.
- Use compact header gaps only while VPN is shown.
- Launch the VPN provider when uniquely launchable; otherwise open Android VPN settings.
- Compile only; do not install the APK.

---

### Task 1: VPN header rules

**Files:**
- Create: `app/src/main/java/com/jojotv/home/VpnHeaderRules.java`
- Modify: `app/src/test/java/com/jojotv/home/SystemContinueWatchingTest.java`

**Interfaces:**
- Produces `VpnHeaderRules.showsVpn(boolean)`, `VpnHeaderRules.headerGapDp(boolean)`, and `VpnHeaderRules.shouldLaunchProvider(int)`.

- [ ] **Step 1: Write the failing test**

```java
@Test public void vpnHeaderAppearsOnlyForAnActiveVpnAndCompactsItsGaps() {
    assertEquals(false, VpnHeaderRules.showsVpn(false));
    assertEquals(true, VpnHeaderRules.showsVpn(true));
    assertEquals(12, VpnHeaderRules.headerGapDp(false));
    assertEquals(4, VpnHeaderRules.headerGapDp(true));
    assertEquals(true, VpnHeaderRules.shouldLaunchProvider(1));
    assertEquals(false, VpnHeaderRules.shouldLaunchProvider(0));
    assertEquals(false, VpnHeaderRules.shouldLaunchProvider(2));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.jojotv.home.SystemContinueWatchingTest`

Expected: compilation failure because `VpnHeaderRules` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
final class VpnHeaderRules {
    static boolean showsVpn(boolean activeVpn) { return activeVpn; }
    static int headerGapDp(boolean activeVpn) { return activeVpn ? 4 : 12; }
    static boolean shouldLaunchProvider(int launchableProviders) { return launchableProviders == 1; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.jojotv.home.SystemContinueWatchingTest`

Expected: `BUILD SUCCESSFUL`.

### Task 2: Dynamic header command and provider fallback

**Files:**
- Modify: `app/src/main/java/com/jojotv/home/MainActivity.java:242-264, 328-335, 892-904`
- Create: `app/src/main/res/drawable/ic_vpn.xml`

**Interfaces:**
- Consumes `VpnHeaderRules` from Task 1.
- Produces `isVpnActive()`, `vpnCommand()`, `openActiveVpnProvider()`, and `openVpnSettings()`.

- [ ] **Step 1: Write the minimal behavior**

```java
boolean vpnActive = isVpnActive();
bar.addView(networkIcon()); addHeaderGap(bar, vpnActive);
if (VpnHeaderRules.showsVpn(vpnActive)) {
    bar.addView(vpnCommand()); addHeaderGap(bar, vpnActive);
}
bar.addView(headerCommand(R.drawable.ic_sources, "Sources", this::showSourcesOverlay));
```

- [ ] **Step 2: Implement provider launch and safe fallback**

```java
private void openActiveVpnProvider() {
    List<ResolveInfo> candidates = getPackageManager().queryIntentServices(
        new Intent("android.net.VpnService"), PackageManager.MATCH_ALL);
    List<Intent> launchable = new ArrayList<>();
    for (ResolveInfo candidate : candidates) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(candidate.serviceInfo.packageName);
        if (launch != null) launchable.add(launch);
    }
    if (VpnHeaderRules.shouldLaunchProvider(launchable.size())) startActivity(launchable.get(0));
    else openVpnSettings();
}
```

- [ ] **Step 3: Keep full date and time visible**

```java
private void addHeaderGap(LinearLayout bar, boolean vpnActive) {
    int width = dp(VpnHeaderRules.headerGapDp(vpnActive));
    bar.addView(spaceWidth(width), new LinearLayout.LayoutParams(width, 1));
}
```

Reserve the existing `220dp` date/time frame and use the compact `4dp` gaps only for a VPN-active bar.

- [ ] **Step 4: Verify build and APK output**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.jojotv.home.SystemContinueWatchingTest; ./gradlew.bat :app:assembleDebug`

Expected: both commands report `BUILD SUCCESSFUL`; copy `app/build/outputs/apk/debug/app-debug.apk` to `Jojo Home.apk`.
