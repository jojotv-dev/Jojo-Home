package com.nuvio.tv.data.freebox;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FreeboxProgrammeOverrideKeyTest {
    @Test
    public void weeklyRecordingsShareOneArtworkKey() {
        String previousWeek = FreeboxMediaDisplayKt.freeboxProgrammeOverrideKey(
                "90' Enquêtes - 19-09-2026 - 21h10.mkv", false);
        String currentWeek = FreeboxMediaDisplayKt.freeboxProgrammeOverrideKey(
                "90' Enquêtes - 26-09-2026 - 21h10.mkv", false);
        assertEquals("freebox-programme:90 enquetes", previousWeek);
        assertEquals(previousWeek, currentWeek);
    }

    @Test
    public void datedProgrammeFolderNamesShareOneArtworkKey() {
        String previousWeek = FreeboxMediaDisplayKt.freeboxProgrammeOverrideKey(
                "90' Enquêtes 19.09.2026 21.10", true);
        String currentWeek = FreeboxMediaDisplayKt.freeboxProgrammeOverrideKey(
                "90' Enquêtes 26.09.2026 21.10", true);
        assertEquals(previousWeek, currentWeek);
    }

    @Test
    public void oldDateSpecificArtworkKeysResolveToTheStableProgrammeKey() {
        assertEquals(
                "freebox-programme:90 enquetes",
                FreeboxMediaDisplayKt.freeboxCanonicalProgrammeOverrideKey(
                        "freebox-programme:90 enquetes 19 09 2026 21 10"));
        assertEquals(
                "freebox-programme:90 enquetes",
                FreeboxMediaDisplayKt.freeboxCanonicalProgrammeOverrideKey(
                        "freebox-programme:90 enquetes 19 09 26"));
    }
}
