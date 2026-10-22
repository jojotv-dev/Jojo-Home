package com.jojotv.home;

import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class AutostartRulesTest {
    @Test
    public void fireTvRequestsHomeBeforeStartingItsService() {
        StringBuilder calls = new StringBuilder();
        BootReceiver.requestHomeBeforeService(() -> calls.append("home"), () -> calls.append(" service"));
        assertEquals("home service", calls.toString());
    }

    @Test
    public void fireTvStillStartsItsServiceWhenHomeRequestFails() {
        StringBuilder calls = new StringBuilder();
        RuntimeException failure = new IllegalStateException("Home request rejected");
        try {
            BootReceiver.requestHomeBeforeService(() -> {
                calls.append("home");
                throw failure;
            }, () -> calls.append(" service"));
            fail("Home request failure must reach the boot retry guard");
        } catch (RuntimeException error) {
            assertSame(failure, error);
        }
        assertEquals("home service", calls.toString());
    }

    @Test
    public void homeLaunchFlagsMatchLauncherRecoveryRequirements() {
        int expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME;

        assertEquals(expectedFlags, AutostartRules.homeLaunchFlags());
    }

    @Test
    public void shouldLaunchOnUserPresentWhenStandbyRecoveryEnabled() {
        assertEquals(true, AutostartRules.shouldLaunch("user_present", true, true));
    }

    @Test
    public void shouldLaunchAsSoonAsDirectBootCompletes() {
        assertEquals(true, AutostartRules.shouldLaunch("locked_boot", true, false));
    }

    @Test
    public void shouldNotLaunchOnUserPresentWhenStandbyRecoveryDisabled() {
        assertEquals(false, AutostartRules.shouldLaunch("user_present", true, false));
    }

    @Test
    public void shouldLaunchWhenFireTvDreamStopsAndStandbyRecoveryEnabled() {
        assertEquals(true, AutostartRules.shouldLaunch("dreaming_stopped", true, true));
    }

    @Test
    public void shouldNotLaunchWhenFireTvDreamStopsAndStandbyRecoveryDisabled() {
        assertEquals(false, AutostartRules.shouldLaunch("dreaming_stopped", true, false));
    }

    @Test
    public void shouldNotLaunchWhenFireTvDreamStopsAndAutostartDisabled() {
        assertEquals(false, AutostartRules.shouldLaunch("dreaming_stopped", false, true));
    }

    @Test public void fireTvStandbyIsIndependentOfBootOption() {
        for (String event : new String[] { "screen_on", "user_present", "dreaming_stopped" }) {
            assertEquals(true, AutostartRules.shouldLaunch(event, false, true, true));
            assertEquals(false, AutostartRules.shouldLaunch(event, true, false, true));
        }
    }

    @Test public void androidTvAndColdBootKeepTheirExistingRules() {
        assertEquals(false, AutostartRules.shouldLaunch("screen_on", false, true, false));
        assertEquals(false, AutostartRules.shouldLaunch("boot", false, true, true));
        assertEquals(true, AutostartRules.shouldLaunch("boot", true, false, true));
    }
}
