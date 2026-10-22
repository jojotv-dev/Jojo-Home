# Monet Continue Watching Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render Android TV resume items as Monet-style landscape cards and let the user choose whether their row is above or below the app grid.

**Architecture:** Keep `MainActivity` as the renderer. `SystemContinueWatching` supplies small pure helpers for the persisted row-position choice and progress calculation; `MainActivity` uses those helpers while converting `WatchNextPrograms` to cards and syncing the preference through the existing NAS settings map.

**Tech Stack:** Java, Android Views, `TvContract.WatchNextPrograms`, JUnit 4.

## Global Constraints

- Use the public Watch Next/Jojo bridge data only.
- Default row position is after applications.
- The landscape thumbnail is preferred over poster art.
- Existing resume intents remain the activation path.

---

### Task 1: Test and add pure presentation helpers

**Files:**
- Modify: `app/src/main/java/com/jojotv/home/SystemContinueWatching.java`
- Modify: `app/src/test/java/com/jojotv/home/SystemContinueWatchingTest.java`

- [ ] Add a failing test for `resumeRowBeforeApps(String)` with `"before"`, `"after"`, and null values.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest`; expect a missing helper failure.
- [ ] Add `resumeRowBeforeApps(String)` returning true only for `"before"`.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest`; expect success.

### Task 2: Add position setting and persist it

**Files:**
- Modify: `app/src/main/java/com/jojotv/home/MainActivity.java`

- [ ] Add `continue_watching_position` preference accessors with default `"after"`.
- [ ] Add `Position des reprises` to the existing settings list with choices `Après les applications` and `Avant les applications`.
- [ ] Include the value in `pushHomeSettingsToNas()` and `applyHomeSettings()`.
- [ ] Place `contentRows()` before or after `appRows()` in `createHome()` from the helper value.

### Task 3: Replace portrait cards with landscape overlay cards

**Files:**
- Modify: `app/src/main/java/com/jojotv/home/MainActivity.java`

- [ ] Query `thumbnail_uri` before `poster_art_uri`; retain both when a thumbnail is absent.
- [ ] Replace the 160 by 220 dp portrait `jojoProgramTile()` with a 300 by 170 dp `FrameLayout`: full-bleed image, dark bottom gradient, title, episode/source labels, and progress bar when duration/position are valid.
- [ ] Keep current intent launch and focus behavior.
- [ ] Update the row height for the new card.

### Task 4: Verify device rendering

**Files:**
- Modify only if the test exposes a defect.

- [ ] Run `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Install the APK on `192.168.1.150:5555`.
- [ ] Verify artwork, text overlay, focus, progress and both position choices using the TV UI dump/screenshot.
