package com.jojotv.home;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AppExportHelperTest {
    @Test
    public void archiveNameSanitizesLabelAndPackage() {
        assertEquals("my-app_com.example.app.zip", AppExportHelper.archiveName("com.example.app", "My App"));
    }

    @Test
    public void archiveNameFallsBackToPackageWhenLabelIsBlank() {
        assertEquals("com.example.app.zip", AppExportHelper.archiveName("com.example.app", "   "));
    }
}
