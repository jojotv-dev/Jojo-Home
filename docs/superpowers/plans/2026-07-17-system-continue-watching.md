# System Continue Watching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display public Android TV Watch Next items in Jojo Home after a single persistent runtime permission grant.

**Architecture:** `MainActivity` retains its private Jojo bridge, adds a guarded `TvContract.WatchNextPrograms` query, and renders all usable published resume intents in the existing content area. The manifest declares the permission; the activity asks only while it is ungranted.

**Tech Stack:** Java, Android framework `TvContract`, Android runtime permissions, Gradle unit tests.

## Global Constraints

- Do not access proprietary application storage or private providers.
- Do not render items that lack a resume intent.
- Preserve JojoTV bridge fallback behavior when no system item exists.

---

### Task 1: Add the permission and a tested item filter

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/jojotv/home/SystemContinueWatching.java`
- Create: `app/src/test/java/com/jojotv/home/SystemContinueWatchingTest.java`

- [ ] Write a failing unit test proving that items without an intent are excluded and items with a title and intent remain.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest` and confirm it fails because the helper does not exist.
- [ ] Add the minimal immutable item/filter helper and the JUnit dependency.
- [ ] Run the same test and confirm it passes.

### Task 2: Read and render Watch Next data

**Files:**
- Modify: `app/src/main/java/com/jojotv/home/MainActivity.java`

- [ ] Add a failing test for the helper’s Jojo package identity detection if the implementation needs it.
- [ ] Query `TvContract.WatchNextPrograms.CONTENT_URI` after permission validation, map poster/thumbnail and intent, then render the row through the existing card system.
- [ ] Keep the existing bridge as a fallback and avoid duplicate JojoTV cards.
- [ ] Build with `./gradlew.bat :app:assembleDebug`.

### Task 3: Device verification

**Files:**
- Modify: `app/src/main/java/com/jojotv/home/MainActivity.java` only if verification exposes an implementation defect.

- [ ] Install the debug APK on the TV.
- [ ] Grant the one-time Android permission.
- [ ] Verify the row contains only publicly published Watch Next items and that activating an item sends its published intent.
