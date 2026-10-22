package com.jojotv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TvPressFeedbackRulesTest {
    @Test public void selectKeysOnly() {
        assertTrue(TvPressFeedbackRules.isSelectKey(23));
        assertTrue(TvPressFeedbackRules.isSelectKey(66));
        assertTrue(TvPressFeedbackRules.isSelectKey(96));
        assertTrue(TvPressFeedbackRules.isSelectKey(160));
        assertFalse(TvPressFeedbackRules.isSelectKey(19)); // D-pad up
        assertFalse(TvPressFeedbackRules.isSelectKey(4));  // Back
    }

    @Test public void rapidPressesAreDebouncedPerControl() {
        assertTrue(TvPressFeedbackRules.acceptPress(-1L, 1000L));
        assertFalse(TvPressFeedbackRules.acceptPress(1000L, 1100L));
        assertFalse(TvPressFeedbackRules.acceptPress(1000L, 1249L));
        assertTrue(TvPressFeedbackRules.acceptPress(1000L, 1250L));
    }

    @Test public void pressAndReleaseRemainBrief() {
        assertEquals(.97f, TvPressFeedbackRules.PRESS_SCALE, 0f);
        assertEquals(.90f, TvPressFeedbackRules.PRESS_ALPHA, 0f);
        assertTrue(TvPressFeedbackRules.PRESS_MS >= 100L && TvPressFeedbackRules.PRESS_MS <= 150L);
        assertTrue(TvPressFeedbackRules.RELEASE_MS >= 150L && TvPressFeedbackRules.RELEASE_MS <= 200L);
    }
}
