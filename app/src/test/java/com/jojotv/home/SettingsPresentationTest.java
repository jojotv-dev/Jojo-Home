package com.jojotv.home;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsPresentationTest {
    @Test public void referencePanelsKeepTheirProportionsAcrossTvViewports() {
        for (int width : new int[]{720, 960, 1280, 1920}) {
            float available = width - 2 * SettingsPresentation.pageInsetDp() - SettingsPresentation.panelGapDp();
            float sidebar = available * SettingsPresentation.sidebarWeight();
            float content = available - sidebar;
            assertTrue(sidebar >= 170);
            assertTrue(content > 470);
            assertEquals(width, 2 * SettingsPresentation.pageInsetDp() + SettingsPresentation.panelGapDp() + sidebar + content, .01f);
        }
    }

    @Test public void onlyExplicitOnOffValuesUseSwitches() {
        assertTrue(SettingsPresentation.isToggleValue("Activ\u00e9"));
        assertTrue(SettingsPresentation.isToggleValue("D\u00e9sactiv\u00e9"));
        assertTrue(SettingsPresentation.isToggleValue("\u2713  Activ\u00e9"));
        for (String value : new String[]{null, "", "3 secondes", "Automatique", "Autoris\u00e9", "\u00c0 autoriser", "ADB requis"})
            assertFalse(String.valueOf(value), SettingsPresentation.isToggleValue(value));
    }

    @Test public void switchingReusesTheExistingChoiceIndexInEitherOrder() {
        String[] values = {"Activ\u00e9", "D\u00e9sactiv\u00e9"};
        assertEquals(1, SettingsPresentation.toggledChoice(values, 0));
        assertEquals(0, SettingsPresentation.toggledChoice(values, 1));
        assertEquals(1, SettingsPresentation.toggledChoice(new String[]{values[1], values[0]}, 0));
        assertEquals(-1, SettingsPresentation.toggledChoice(new String[]{"Liste", "Grille"}, 0));
        assertEquals(-1, SettingsPresentation.toggledChoice(new String[]{values[0], values[0]}, 0));
        assertEquals(-1, SettingsPresentation.toggledChoice(values, -1));
        assertEquals(-1, SettingsPresentation.toggledChoice(null, 0));
    }

    @Test public void persistedBooleanAndValueLabelsRemainUnambiguous() {
        assertTrue(SettingsPresentation.toggleChecked("\u2713  Activ\u00e9"));
        assertFalse(SettingsPresentation.toggleChecked("D\u00e9sactiv\u00e9"));
        assertEquals("3 secondes", SettingsPresentation.cleanValue("\u2713  3 secondes"));
        assertEquals("", SettingsPresentation.cleanValue(null));
    }

    @Test public void referenceCornersDoNotChangeOtherExistingCardSurfaces() {
        assertEquals(32, SettingsPresentation.panelCornerRadiusDp());
        assertEquals(8, SettingsPresentation.rowCornerRadiusDp());
        assertEquals(14, SettingsPresentation.cardCornerRadiusDp());
        assertTrue(SettingsPresentation.rowMinHeightDp() >= 48);
    }
}
