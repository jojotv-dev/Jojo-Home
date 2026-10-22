package com.jojotv.home;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NotificationResumeRulesTest {
    @Test public void netflixProfileComesOnlyFromThePublishedResumeLabel() {
        assertEquals("STÉPHANIE", NotificationResumeRules.netflixProfile(
                "com.netflix.ninja", "recommendation", "continueWatching", "Reprendre avec le profil de STÉPHANIE"));
        assertEquals("", NotificationResumeRules.netflixProfile(
                "com.netflix.ninja", "recommendation", "recommendations", "Reprendre avec le profil de ARNAUD"));
        assertEquals("", NotificationResumeRules.netflixProfile(
                "com.netflix.ninja", "transport", "continueWatching", "Reprendre avec le profil de ARNAUD"));
        assertEquals("", NotificationResumeRules.netflixProfile(
                "com.netflix.ninja", "recommendation", "continueWatching", "Netflix"));
        org.junit.Assert.assertNotEquals(NotificationResumeRules.resumeKey("com.netflix.ninja", "Série", "Stéphanie"),
                NotificationResumeRules.resumeKey("com.netflix.ninja", "Série", "Arnaud"));
    }
    @Test
    public void netflixResumeDurationUsesSecondsAndMatchesTheObservedRemainingTime() {
        long duration = NotificationResumeRules.netflixRecommendationDuration(
                "com.netflix.ninja", "recommendation", "continueWatching", 0, 3758);
        assertEquals(3_758_000L, duration);
        assertEquals("52min restantes", SystemContinueWatching.remainingTimeLabel(duration, 634_607L));
        assertEquals(634_000L, NotificationResumeRules.notificationPosition(duration, 634, 3758));
    }

    @Test
    public void netflixRecommendationsDoNotTurnTrendingTitlesIntoResumes() {
        assertTrue(NotificationResumeRules.acceptsPlaybackNotification(
                "com.netflix.ninja", "recommendation", "continueWatching", false, 3758));
        assertFalse(NotificationResumeRules.acceptsPlaybackNotification(
                "com.netflix.ninja", "recommendation", "trendingNow", false, 5556));
        assertFalse(NotificationResumeRules.acceptsPlaybackNotification(
                "com.netflix.ninja", "recommendation", null, false, 3758));
        assertTrue(NotificationResumeRules.acceptsPlaybackNotification(
                "com.netflix.ninja", "transport", null, true, -1));
        assertTrue(NotificationResumeRules.acceptsPlaybackNotification(
                "io.gh.reisxd.tizentube.cobalt", "transport", null, true, -1));
    }

    @Test
    public void netflixRuntimeRejectsPlaceholdersAndOtherNotificationScales() {
        assertEquals(-1L, NotificationResumeRules.netflixRecommendationDuration(
                "com.netflix.ninja", "recommendation", "continueWatching", 10, 100));
        assertEquals(-1L, NotificationResumeRules.netflixRecommendationDuration(
                "com.netflix.ninja", "recommendation", "continueWatching", 3759, 3758));
        assertEquals(-1L, NotificationResumeRules.netflixRecommendationDuration(
                "com.netflix.ninja", "recommendation", "trendingNow", 0, 5556));
        assertEquals(-1L, NotificationResumeRules.netflixRecommendationDuration(
                "com.netflix.ninja", "transport", null, 20, 1000));
        assertEquals(-1L, NotificationResumeRules.netflixRecommendationDuration(
                "com.canal.fai", "recommendation", "continueWatching", 20, 1000));
    }

    @Test
    public void netflixMetadataFreeSessionCannotUseAnOldProfileRecommendation() {
        assertFalse(NotificationResumeRules.netflixSessionMatchesRecommendation(
                null, "https://www.netflix.com/watch/81437051"));
        assertFalse(NotificationResumeRules.netflixSessionMatchesRecommendation(
                "", "https://www.netflix.com/watch/81437051"));
    }

    @Test
    public void netflixRecommendationTimingRequiresTheSamePublishedContentId() {
        assertFalse(NotificationResumeRules.netflixSessionMatchesRecommendation(
                "netflix:80000003", "https://www.netflix.com/watch/81437051"));
        assertTrue(NotificationResumeRules.netflixSessionMatchesRecommendation(
                "netflix:81437051", "https://www.netflix.com/watch/81437051?trkid=1"));
        assertFalse(NotificationResumeRules.netflixSessionMatchesRecommendation(
                "netflix:81437051", null));
    }

    @Test
    public void netflixZeroBookmarkDoesNotRetainAnUnverifiedPositionFromThePreviousFix() {
        String recommendation = "https://www.netflix.com/watch/81437051";
        assertEquals(0L, NotificationResumeRules.netflixRecommendationPosition(
                3_758_000L, 0, 3758, 811_939L, "", recommendation));
        assertEquals(0L, NotificationResumeRules.netflixRecommendationPosition(
                3_758_000L, 0, 3758, 811_939L, "netflix:80000003", recommendation));
        assertEquals(811_939L, NotificationResumeRules.netflixRecommendationPosition(
                3_758_000L, 0, 3758, 811_939L, "netflix:81437051", recommendation));
        assertEquals(634_000L, NotificationResumeRules.netflixRecommendationPosition(
                3_758_000L, 634, 3758, 811_939L, "netflix:81437051", recommendation));
    }

    @Test
    public void netflixNewEpisodeClearsInheritedTimingButSeekAndPackageChangesDoNot() {
        assertTrue(NotificationResumeRules.playbackVideoChanged("com.netflix.ninja",
                "https://www.netflix.com/watch/80000001", "https://www.netflix.com/watch/80000003"));
        assertFalse(NotificationResumeRules.playbackVideoChanged("com.netflix.ninja",
                "https://www.netflix.com/watch/80000001?trkid=1", "netflix:80000001"));
        assertFalse(NotificationResumeRules.playbackVideoChanged("com.netflix.ninja",
                "https://www.netflix.com/watch/80000001", null));
    }

    @Test
    public void tizenTubeVideoIdentityChangesWithoutNeedingAnotherTitleOrDuration() {
        String app = "io.gh.reisxd.tizentube.cobalt";
        assertTrue(NotificationResumeRules.playbackVideoChanged(app, "dQw4w9WgXcQ",
                "intent://www.youtube.com/watch?v=9bZkp7q19f0#Intent;scheme=https;package=" + app + ";end"));
        assertFalse(NotificationResumeRules.playbackVideoChanged(app, "https://youtu.be/dQw4w9WgXcQ?t=10",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=60"));
        assertFalse(NotificationResumeRules.playbackVideoChanged(app, "", "https://youtu.be/dQw4w9WgXcQ"));
        assertFalse(NotificationResumeRules.playbackVideoChanged(app, "dQw4w9WgXcQ", null));
    }

    @Test
    public void acceptsSmartTubeAliases() {
        Set<String> selected = new HashSet<>();
        selected.add("org.smarttube");

        assertTrue(NotificationResumeRules.acceptsPackage(selected, "org.smarttube.beta"));
        assertTrue(NotificationResumeRules.acceptsPackage(selected, "org.smarttube"));
    }

    @Test
    public void acceptsNetflixAliases() {
        Set<String> selected = new HashSet<>();
        selected.add("com.netflix.ninja");

        assertTrue(NotificationResumeRules.acceptsPackage(selected, "com.netflix.ninja"));
        assertTrue(NotificationResumeRules.acceptsPackage(selected, "com.netflix.mediaclient"));
    }

    @Test
    public void freeTvSelectionAcceptsOqeeVariantsOnly() {
        Set<String> selected = java.util.Collections.singleton("net.oqee.androidtv");
        assertTrue(NotificationResumeRules.acceptsPackage(selected, "net.oqee.androidtv.mod"));
        assertTrue(NotificationResumeRules.acceptsPackage(java.util.Collections.singleton("free.tv"), "net.oqee.androidtv.mod"));
        assertFalse(NotificationResumeRules.acceptsPackage(selected, "com.netflix.ninja"));
        assertFalse(NotificationResumeRules.acceptsPackage(java.util.Collections.singleton("com.netflix.ninja"), "net.oqee.androidtv.mod"));
    }

    @Test
    public void artworkBelongsToItsContentAndChangesWithItsImage() throws Exception {
        String packageName = "io.gh.reisxd.tizentube.cobalt";
        byte[] firstImage = new byte[] { 1, 2, 3 };
        String first = NotificationResumeRules.artworkFileName(packageName, "FAST LIFE", firstImage);
        assertEquals(first, NotificationResumeRules.artworkFileName(packageName, "FAST LIFE", firstImage));
        assertFalse(first.equals(NotificationResumeRules.artworkFileName(packageName, "Eva", firstImage)));
        assertFalse(first.equals(NotificationResumeRules.artworkFileName(packageName, "FAST LIFE", new byte[] { 4, 5, 6 })));
    }

    @Test
    public void discardsAmbiguousOldCobaltArtworkButKeepsCanalArtwork() throws Exception {
        String cobalt = "io.gh.reisxd.tizentube.cobalt";
        String oldCobalt = "file:///data/notification_resume_" + Integer.toHexString(cobalt.hashCode()) + ".png";
        assertEquals("", NotificationResumeRules.retainedArtworkUri(cobalt, oldCobalt));
        String current = "file:///data/" + NotificationResumeRules.artworkFileName(cobalt, "FAST LIFE", new byte[] { 1 });
        assertEquals(current, NotificationResumeRules.retainedArtworkUri(cobalt, current));
        String canal = "com.canal.fai";
        String oldCanal = "file:///data/notification_resume_" + Integer.toHexString(canal.hashCode()) + ".png";
        assertEquals(oldCanal, NotificationResumeRules.retainedArtworkUri(canal, oldCanal));
    }

    @Test
    public void acceptsTivimateAliases() {
        Set<String> selected = new HashSet<>();
        selected.add("tivimate");

        assertTrue(NotificationResumeRules.acceptsPackage(selected, "tivimate.android.tv"));
    }

    @Test
    public void acceptsAmazonPrimeAliases() {
        Set<String> selected = new HashSet<>();
        selected.add("com.amazon.firetv");

        assertTrue(NotificationResumeRules.acceptsPackage(selected, "com.amazon.venezia"));
        assertTrue(NotificationResumeRules.acceptsPackage(selected, "com.amazon.firebat"));
    }

    @Test
    public void acceptsAnyWhenSelectionIsNull() {
        assertTrue(NotificationResumeRules.acceptsPackage(null, "com.netflix.mediaclient"));
        assertTrue(NotificationResumeRules.acceptsPackage(null, "org.smarttube.beta"));
    }

    @Test
    public void ignoresUnrelatedPackages() {
        Set<String> selected = new HashSet<>();
        selected.add("com.netflix.ninja");

        assertFalse(NotificationResumeRules.acceptsPackage(selected, "org.smarttube.beta"));
    }

    @Test
    public void onlySelectedVideoAppsAreAccepted() {
        Set<String> selected = new HashSet<>();
        selected.add("io.gh.reisxd.tizentube.cobalt");

        assertTrue(NotificationResumeRules.acceptsPackage(selected, "io.gh.reisxd.tizentube.cobalt"));
        assertFalse(NotificationResumeRules.acceptsPackage(selected, "com.google.android.youtube.tv"));
        assertFalse(NotificationResumeRules.acceptsPackage(selected, "com.netflix.ninja"));
        assertFalse(NotificationResumeRules.acceptsPackage(new HashSet<>(), "org.smarttube.beta"));
    }

    @Test
    public void recognizesCobaltAsYoutubeVideoButKeepsItsOwnSelection() {
        assertTrue(NotificationResumeRules.isYoutubeVideoPackage("io.gh.reisxd.tizentube.cobalt"));
        assertTrue(NotificationResumeRules.isYoutubeVideoPackage("org.smarttube.beta"));
        assertFalse(NotificationResumeRules.isYoutubeVideoPackage("com.google.android.apps.youtube.music"));
        assertFalse(NotificationResumeRules.acceptsPackage(
                java.util.Collections.singleton("com.google.android.youtube.tv"), "io.gh.reisxd.tizentube.cobalt"));
        assertFalse(NotificationResumeRules.acceptsPackage(
                java.util.Collections.singleton("com.google.android.youtube.tv"), "com.google.android.apps.youtube.music"));
    }

    @Test
    public void youtubeResolutionRequiresFullTitleAuthorAndDuration() {
        String title = "FAST LIFE - Dans la vie de Laurent Dupin au Grand Prix de Silverstone - F1";
        long duration = (36L * 60L + 12L) * 1_000L;
        assertTrue(NotificationResumeRules.matchesYoutubeVideo(title, "CANAL+ Sport", duration, title, "CANAL+ Sport", duration + 900L));
        assertFalse(NotificationResumeRules.matchesYoutubeVideo(title, "CANAL+ Sport", duration, "Eva interprète Sur la piste", "C à vous", 165_000L));
        assertFalse(NotificationResumeRules.matchesYoutubeVideo(title, "CANAL+ Sport", duration, title, "Autre chaîne", duration));
        assertFalse(NotificationResumeRules.matchesYoutubeVideo(title, "CANAL+ Sport", duration, title, "CANAL+ Sport", 165_000L));
        assertFalse(NotificationResumeRules.matchesYoutubeVideo(title, "", duration, title, "CANAL+ Sport", duration));
        assertFalse(NotificationResumeRules.matchesYoutubeVideo(title, "CANAL+ Sport", -1L, title, "CANAL+ Sport", duration));
    }

    @Test
    public void youtubeIdsComeFromVideoLinksAndProviderThumbnails() {
        String id = "iC3TId4OFx4";
        assertEquals(id, NotificationResumeRules.youtubeVideoId(id));
        assertEquals(id, NotificationResumeRules.youtubeVideoId("https://www.youtube.com/watch?v=" + id + "&t=60s"));
        assertEquals(id, NotificationResumeRules.youtubeVideoId("https://youtu.be/" + id));
        assertEquals(id, NotificationResumeRules.youtubeVideoId("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"));
        assertEquals(null, NotificationResumeRules.youtubeVideoId("https://www.youtube.com/"));
        assertEquals(null, NotificationResumeRules.youtubeVideoId("https://example.com/watch?v=" + id));
        assertEquals(null, NotificationResumeRules.youtubeVideoId("https://youtube.com.example.com/watch?v=" + id));
        assertEquals(null, NotificationResumeRules.youtubeVideoId("FAST LIFE"));
    }

    @Test
    public void findsContentAfterGenericNetflixNotificationTitle() {
        assertEquals("Slow Horses", NotificationResumeRules.firstContentTitle("Netflix", " Netflix ", "Slow Horses", "Saison 6"));
        assertEquals("L'Arène", NotificationResumeRules.firstContentTitle("CANAL+", "CANAL+", null, "L'Arène"));
        assertEquals(null, NotificationResumeRules.firstContentTitle("Netflix", "Netflix", " ", null));
    }

    @Test
    public void unknownPlaybackStateRetainsKnownPositionAndSeekingToStartIsAllowed() {
        assertEquals(120_000L, NotificationResumeRules.playbackPosition("com.netflix.ninja", 600_000L, -1L, 120_000L, false));
        assertEquals(0L, NotificationResumeRules.playbackPosition("com.netflix.ninja", 600_000L, 0L, 120_000L, true));
    }

    @Test
    public void cobaltReportedEndDoesNotFinishAnActiveVideo() {
        String packageName = "io.gh.reisxd.tizentube.cobalt";
        assertEquals(-1L, NotificationResumeRules.playbackPosition(packageName, 600_000L, 600_000L, -1L, true));
        assertEquals(120_000L, NotificationResumeRules.playbackPosition(packageName, 600_000L, 600_001L, 120_000L, true));
        assertEquals(600_000L, NotificationResumeRules.playbackPosition(packageName, 600_000L, 600_000L, 120_000L, false));
        assertEquals(600_000L, NotificationResumeRules.playbackPosition("com.netflix.ninja", 600_000L, 600_000L, 120_000L, true));
    }

    @Test
    public void notificationProgressNeedsARealDurationAndItsOwnScale() {
        assertEquals(120_000L, NotificationResumeRules.notificationPosition(600_000L, 20, 100));
        assertEquals(120_000L, NotificationResumeRules.notificationPosition(600_000L, 200, 1_000));
        assertEquals(-1L, NotificationResumeRules.notificationPosition(-1L, 20, 100));
        assertEquals(-1L, NotificationResumeRules.notificationPosition(600_000L, 101, 100));
    }

    @Test
    public void keepsItemsForThirtyDaysOnly() {
        long now = 2_000_000_000_000L;
        assertTrue(NotificationResumeRules.isFresh(now - NotificationResumeRules.RETENTION_MS, now));
        assertFalse(NotificationResumeRules.isFresh(now - NotificationResumeRules.RETENTION_MS - 1L, now));
        assertFalse(NotificationResumeRules.isFresh(0L, now));
    }

    @Test
    public void normalizesResumeKeys() {
        assertEquals("com.netflix.mediaclient|episode 1", NotificationResumeRules.resumeKey(" COM.NETFLIX.MEDIACLIENT ", " Episode 1 "));
    }
}
