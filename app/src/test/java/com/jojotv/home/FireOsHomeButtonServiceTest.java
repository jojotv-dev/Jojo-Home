package com.jojotv.home;

import org.junit.Test;
import static org.junit.Assert.*;

public class FireOsHomeButtonServiceTest {
    private static final long STARTED_AT = 1_791_051_590_000L;

    @Test public void supportedDeviceExcludesAndroidTvAndFireTablets() {
        assertTrue(FireOsHomeButtonService.isSupportedDevice("Amazon", "AFTMM", 25));
        assertFalse(FireOsHomeButtonService.isSupportedDevice("NVIDIA", "SHIELD Android TV", 25));
        assertFalse(FireOsHomeButtonService.isSupportedDevice("Amazon", "KFMAWI", 25));
        assertFalse(FireOsHomeButtonService.isSupportedDevice("Amazon", null, 25));
        assertFalse(FireOsHomeButtonService.isSupportedDevice("Amazon", "AFTMM", 26));
    }

    @Test public void standbyListenerDoesNotDependOnHomeButtonOption() {
        assertTrue(FireOsHomeButtonService.shouldKeepRunning(false, true));
        assertTrue(FireOsHomeButtonService.shouldKeepRunning(true, false));
        assertFalse(FireOsHomeButtonService.shouldKeepRunning(false, false));
    }

    @Test public void readsPowerAndDreamWakeIndependentlyOfHomeMethod() {
        assertEquals("wake", FireOsHomeButtonService.parseStandbyLog(entry("PowerManagerService", "Waking up from sleep..."), STARTED_AT));
        assertEquals("sleep", FireOsHomeButtonService.parseStandbyLog(entry("PowerManagerService", "Going to sleep due to timeout..."), STARTED_AT));
        assertEquals("wake", FireOsHomeButtonService.parseStandbyLog(entry("DreamManagerService", "Stopping dream."), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseStandbyLog(entry("JojoFireWake", "Waking up from sleep..."), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseStandbyLog(entry("PowerManagerService", "Waking up from sleep..."), STARTED_AT + 10_000L));
    }

    @Test public void readsActualResumedActivityWithoutConfusingAmazonSettings() {
        assertEquals("com.amazon.tv.launcher/.ui.HomeActivity_vNext", FireOsHomeButtonService.parseStandbyLog(
                entry("am_resume_activity", "[0,42,10,com.amazon.tv.launcher/.ui.HomeActivity_vNext]"), STARTED_AT));
        assertTrue(FireOsHomeButtonService.isAmazonHomeComponent("com.amazon.tv.launcher/.ui.HomeActivity_vNext"));
        assertFalse(FireOsHomeButtonService.isAmazonHomeComponent("com.amazon.tv.launcher/.ui.SettingsActivity"));
        assertNull(FireOsHomeButtonService.parseStandbyLog(entry("am_resume_activity", "[malformed]"), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseStandbyLog(entry("OtherApp", "[0,42,10,com.amazon.tv.launcher/.ui.HomeActivity_vNext]"), STARTED_AT));
    }

    @Test public void wakeEventsMergeButApiAcceptanceDoesNotConfirmVisibleHome() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        assertTrue(tracker.start(0L));
        assertFalse(tracker.start(100L));
        assertEquals(tracker.LAUNCH, tracker.nextAction(0L, true, false, false));
        assertEquals(tracker.WAIT, tracker.nextAction(500L, true, false, false));
        tracker.completed(tracker.generation, 500L);
        assertEquals(tracker.WAIT, tracker.nextAction(1_000L, true, false, false));
        assertEquals(tracker.LAUNCH, tracker.nextAction(1_250L, true, false, false));
        assertTrue(tracker.active);
    }

    @Test public void earlyWakeWaitsForInteractiveDisplay() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        assertEquals(tracker.WAIT, tracker.nextAction(0L, false, false, false));
        assertEquals(0, tracker.attempts);
        assertEquals(tracker.LAUNCH, tracker.nextAction(500L, true, false, false));
    }

    @Test public void amazonReclaimDuringWakeTriggersAnotherBoundedAttempt() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        assertEquals(tracker.LAUNCH, tracker.nextAction(0L, true, false, false));
        tracker.completed(tracker.generation, 100L);
        assertEquals(tracker.WAIT, tracker.nextAction(200L, true, true, false));
        assertEquals(tracker.LAUNCH, tracker.nextAction(2_000L, true, false, false));
        tracker.completed(tracker.generation, 2_100L);
        assertEquals(tracker.WAIT, tracker.nextAction(2_200L, true, true, false));
        assertEquals(tracker.WAIT, tracker.nextAction(4_000L, true, true, false));
        assertEquals(tracker.LAUNCH, tracker.nextAction(6_000L, true, false, false));
        tracker.completed(tracker.generation, 6_100L);
        assertEquals(tracker.DONE, tracker.nextAction(10_000L, true, true, false));
        assertFalse(tracker.active);
    }

    @Test public void nextSleepRearmsImmediatelyEvenAfterSuccessfulWake() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        assertEquals(tracker.WAIT, tracker.nextAction(0L, true, true, false));
        assertEquals(tracker.DONE, tracker.nextAction(10_000L, true, true, false));
        assertFalse(tracker.start(10_050L));
        tracker.sleep();
        assertTrue(tracker.start(10_100L));
        assertEquals(tracker.LAUNCH, tracker.nextAction(10_100L, true, false, false));
    }

    @Test public void staleCompletionCannotUnlockNewWakeRequest() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        int oldCycle = tracker.generation;
        assertEquals(tracker.LAUNCH, tracker.nextAction(0L, true, false, false));
        tracker.sleep();
        assertFalse(tracker.isCurrent(oldCycle));
        tracker.start(1_000L);
        assertEquals(tracker.LAUNCH, tracker.nextAction(1_000L, true, false, false));
        tracker.completed(oldCycle, 1_100L);
        assertEquals(tracker.WAIT, tracker.nextAction(2_000L, true, false, false));
    }

    @Test public void wakeRecoveryCannotStealAnAppOpenedAfterHome() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        assertEquals(tracker.WAIT, tracker.nextAction(0L, true, true, false));
        assertEquals(tracker.DONE, tracker.nextAction(500L, true, false, true));
        assertFalse(tracker.active);
    }

    @Test public void fastHomeResumeThenAppLaunchIsNotMissedBetweenChecks() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        tracker.observeHome();
        assertEquals(tracker.DONE, tracker.nextAction(100L, true, false, true));
    }

    @Test public void retriesAndObservationAreStrictlyBoundedAndFailureCanRetry() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        for (long now : new long[] { 0L, 1_000L, 2_000L }) {
            assertEquals(tracker.LAUNCH, tracker.nextAction(now, true, false, false));
            tracker.completed(tracker.generation, now);
        }
        assertEquals(tracker.WAIT, tracker.nextAction(3_000L, true, false, false));
        assertEquals(3, tracker.attempts);
        assertEquals(tracker.DONE, tracker.nextAction(10_000L, true, false, false));
        assertTrue(tracker.start(10_100L));
    }

    @Test public void cancelledWakeCannotLaunchOrConsumeAnotherCycle() {
        FireOsHomeButtonService.WakeRecoveryTracker tracker = new FireOsHomeButtonService.WakeRecoveryTracker();
        tracker.start(0L);
        int cycle = tracker.generation;
        tracker.cancel();
        assertFalse(tracker.isCurrent(cycle));
        assertEquals(tracker.DONE, tracker.nextAction(500L, true, false, false));
    }

    @Test public void selectedOzDcsDoesNotConsumeOtherHomeSources() {
        assertTrue(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "OZ-DCS:HomePressReceiver"),
                FireOsHomeButtonService.METHOD_OZ_DCS));
        assertFalse(FireOsHomeButtonService.matchesTriggerMethod(event(4L, "ActivityManager"),
                FireOsHomeButtonService.METHOD_OZ_DCS));
        assertFalse(FireOsHomeButtonService.matchesTriggerMethod(event(600L, "Vizzini_3.0.16038.0:HomeListener"),
                FireOsHomeButtonService.METHOD_OZ_DCS));
    }

    @Test public void selectedHomeListenerAcceptsVersionedTagsOnly() {
        assertTrue(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "Vizzini_3.0.16038.0:HomeListener"),
                FireOsHomeButtonService.METHOD_HOME_LISTENER));
        assertFalse(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "OZ-DCS:HomePressReceiver"),
                FireOsHomeButtonService.METHOD_HOME_LISTENER));
    }

    @Test public void selectedActivityManagerIncludesCachedHomeIntents() {
        assertTrue(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "am_new_intent"),
                FireOsHomeButtonService.METHOD_ACTIVITY_MANAGER));
        assertTrue(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "am_create_activity"),
                FireOsHomeButtonService.METHOD_ACTIVITY_MANAGER));
        assertFalse(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "HomeListener"),
                FireOsHomeButtonService.METHOD_ACTIVITY_MANAGER));
    }

    @Test public void automaticMethodKeepsAllDetectionSources() {
        assertTrue(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "OZ-DCS:HomePressReceiver"),
                FireOsHomeButtonService.METHOD_AUTO));
        assertTrue(FireOsHomeButtonService.matchesTriggerMethod(event(0L, "ActivityManager"),
                FireOsHomeButtonService.METHOD_AUTO));
    }

    @Test public void selectedOzDcsKeepsNextPressAfterLateOtherSources() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        int launches = 0;
        for (FireOsHomeButtonService.HomeLogEvent event : new FireOsHomeButtonService.HomeLogEvent[] {
                event(0L, "OZ-DCS:HomePressReceiver"), event(4L, "ActivityManager"),
                event(600L, "Vizzini_3.0.16038.0:HomeListener"), event(1_000L, "OZ-DCS:HomePressReceiver") }) {
            if (FireOsHomeButtonService.matchesTriggerMethod(event, FireOsHomeButtonService.METHOD_OZ_DCS)
                    && tracker.shouldLaunch(event)) launches++;
        }
        assertEquals(2, launches);
    }

    private String entry(String source, String message) {
        return "         1791051594.798  1408  1408 I " + source + ": " + message;
    }

    @Test public void readsVersionedHomeListener() {
        FireOsHomeButtonService.HomeLogEvent event = FireOsHomeButtonService.parseHomeLogEvent(
                entry("Vizzini_3.0.16038.0:HomeListener", "Received: amazon.intent.action.HOME_PRESSED"), STARTED_AT);
        assertNotNull(event);
        assertEquals(1_791_051_594_798L, event.timestamp);
        assertEquals("Vizzini_3.0.16038.0:HomeListener", event.source);
    }

    @Test public void readsOzDcsWithoutHomeListener() {
        assertNotNull(FireOsHomeButtonService.parseHomeLogEvent(entry("OZ-DCS:HomePressReceiver",
                "DCS amazon.intent.action.HOME_PRESSED received, DCS started"), STARTED_AT));
    }

    @Test public void readsActivityManagerWithoutVendorBroadcast() {
        assertNotNull(FireOsHomeButtonService.parseHomeLogEvent(entry("ActivityManager",
                "START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] "
                        + "cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext (has extras)}"), STARTED_AT));
    }

    @Test public void readsCustomHomeHandler() {
        assertNotNull(FireOsHomeButtonService.parseHomeLogEvent(entry("CustomHomeButtonHandler",
                "getCustomHomeReceiver: package: com.jojotv.home"), STARTED_AT));
    }

    @Test public void readsCachedAmazonHomeIntentWithoutMainBufferSignatures() {
        FireOsHomeButtonService.HomeLogEvent event = FireOsHomeButtonService.parseHomeLogEvent(
                "         1791109361.330   622   666 I am_new_intent: [0,223938164,20808,"
                        + "com.amazon.tv.launcher/.ui.HomeActivity_vNext,android.intent.action.MAIN,NULL,NULL,270532608]",
                STARTED_AT);
        assertNotNull(event);
        assertEquals("am_new_intent", event.source);
        assertEquals(1_791_109_361_330L, event.timestamp);
    }

    @Test public void readsCreatedAmazonHomeActivity() {
        assertNotNull(FireOsHomeButtonService.parseHomeLogEvent(entry("am_create_activity",
                "[0,42,10,com.amazon.tv.launcher/com.amazon.tv.launcher.ui.HomeActivity_vNext,"
                        + "android.intent.action.MAIN,NULL,NULL,270532608]"), STARTED_AT));
    }

    @Test public void ignoresOtherActivityEventsAndActions() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("am_new_intent",
                "[0,42,10,com.amazon.tv.launcher/.ui.MainSettingsActivity,"
                        + "android.intent.action.MAIN,NULL,NULL,270532608]"), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("am_new_intent",
                "[0,42,10,com.jojotv.home/.MainActivity,android.intent.action.MAIN,NULL,NULL,270532608]"), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("am_new_intent",
                "[0,42,10,com.amazon.tv.launcher/.ui.HomeActivity_vNext,"
                        + "android.intent.action.VIEW,NULL,NULL,270532608]"), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("am_resume_activity",
                "[0,42,10,com.amazon.tv.launcher/.ui.HomeActivity_vNext]"), STARTED_AT));
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("am_new_intent", "[malformed]"), STARTED_AT));
    }

    @Test public void ignoresJojoEchoOfVendorEvent() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("JojoFireHome",
                "Home detected: [ 10-03 20:17:26.798 1408:1408 I/Vizzini_3.0.16038.0:HomeListener ] "
                        + "Received: amazon.intent.action.HOME_PRESSED"), STARTED_AT));
    }

    @Test public void ignoresJojoEchoOfActivityManagerEvent() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("JojoFireHome",
                "Home detected: [ 10-03 20:22:22.022 607:658 I/ActivityManager ] START u0 "
                        + "{cat=[android.intent.category.HOME] cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext}"), STARTED_AT));
    }

    @Test public void ignoresSignaturesCopiedByOtherApps() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("OtherApp",
                "Received: amazon.intent.action.HOME_PRESSED"), STARTED_AT));
    }

    @Test public void ignoresAmazonSettingsActivity() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("ActivityManager",
                "START u0 {cmp=com.amazon.tv.launcher/.ui.SettingsActivity (has extras)}"), STARTED_AT));
    }

    @Test public void ignoresBufferedHistoryOnReaderRestart() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent(entry("HomeListener",
                "Received: amazon.intent.action.HOME_PRESSED"), 1_791_051_600_000L));
    }

    @Test public void lateReadingDoesNotDropOrMergeTwoRealPresses() {
        String line = entry("HomeListener", "Received: amazon.intent.action.HOME_PRESSED");
        FireOsHomeButtonService.HomeLogEvent first = FireOsHomeButtonService.parseHomeLogEvent(line, STARTED_AT);
        FireOsHomeButtonService.HomeLogEvent second = FireOsHomeButtonService.parseHomeLogEvent(
                line.replace("1791051594.798", "1791051595.798"), STARTED_AT);
        assertNotNull(first);
        assertNotNull(second);
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(first));
        assertTrue(tracker.shouldLaunch(second));
    }

    @Test public void ignoresDividersAndMalformedLines() {
        assertNull(FireOsHomeButtonService.parseHomeLogEvent("--------- beginning of main", STARTED_AT));
        assertNull(FireOsHomeButtonService.parseHomeLogEvent("amazon.intent.action.HOME_PRESSED", STARTED_AT));
    }

    private FireOsHomeButtonService.HomeLogEvent event(long offset, String source) {
        return new FireOsHomeButtonService.HomeLogEvent(STARTED_AT + offset, source);
    }

    @Test public void lateVendorDuplicateCannotHideNextHomePress() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "OZ-DCS:HomePressReceiver")));
        assertFalse(tracker.shouldLaunch(event(4L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(599L, "Vizzini_3.0.16038.0:HomeListener")));
        assertFalse(tracker.shouldLaunch(event(1_000L, "OZ-DCS:HomePressReceiver")));
        assertTrue(tracker.shouldLaunch(event(1_004L, "ActivityManager")));
    }

    @Test public void activityManagerFirstAlsoCoalescesLateVendorSignals() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(4L, "OZ-DCS:HomePressReceiver")));
        assertFalse(tracker.shouldLaunch(event(600L, "Vizzini_3.0.16038.0:HomeListener")));
        assertTrue(tracker.shouldLaunch(event(1_000L, "ActivityManager")));
    }

    @Test public void separateActivityManagerPressesAreNotBlockedByCooldown() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "ActivityManager")));
        assertTrue(tracker.shouldLaunch(event(250L, "ActivityManager")));
        assertTrue(tracker.shouldLaunch(event(500L, "ActivityManager")));
    }

    @Test public void repeatedVendorStillWorksWhenActivityManagerIsMissing() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "OZ-DCS:HomePressReceiver")));
        assertFalse(tracker.shouldLaunch(event(20L, "HomeListener")));
        assertTrue(tracker.shouldLaunch(event(1_000L, "OZ-DCS:HomePressReceiver")));
    }

    @Test public void ignoresIdenticalActivityManagerEntry() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(0L, "ActivityManager")));
    }

    @Test public void missingPreviousActivityManagerDoesNotHideNextPress() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "OZ-DCS:HomePressReceiver")));
        assertFalse(tracker.shouldLaunch(event(1_000L, "HomeListener")));
        assertTrue(tracker.shouldLaunch(event(1_004L, "ActivityManager")));
    }

    @Test public void cachedHomeIntentCannotBeHiddenByPreviousVendorCooldown() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "HomeListener")));
        assertFalse(tracker.shouldLaunch(event(4L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(6L, "am_new_intent")));
        assertTrue(tracker.shouldLaunch(event(1_000L, "am_new_intent")));
        assertTrue(tracker.shouldLaunch(event(1_250L, "am_new_intent")));
    }

    @Test public void primaryPairsDoNotMergeSeparateQuickPresses() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(2L, "am_new_intent")));
        assertTrue(tracker.shouldLaunch(event(100L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(102L, "am_new_intent")));
    }

    @Test public void eventsBufferFirstStillCoalescesStartAndVendor() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "am_new_intent")));
        assertFalse(tracker.shouldLaunch(event(2L, "ActivityManager")));
        assertFalse(tracker.shouldLaunch(event(100L, "HomeListener")));
        assertTrue(tracker.shouldLaunch(event(1_000L, "am_new_intent")));
    }

    @Test public void ignoresReplayedActivityIntent() {
        FireOsHomeButtonService.HomePressTracker tracker = new FireOsHomeButtonService.HomePressTracker();
        assertTrue(tracker.shouldLaunch(event(0L, "am_new_intent")));
        assertFalse(tracker.shouldLaunch(event(0L, "am_new_intent")));
    }
}
