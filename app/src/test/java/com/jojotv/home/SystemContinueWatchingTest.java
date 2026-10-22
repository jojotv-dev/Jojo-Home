package com.jojotv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public final class SystemContinueWatchingTest {
    @Test public void netflixFirstFrameAndFinalMergeCannotReintroduceCachedEpisodes() {
        java.util.function.Function<String, String> source = value -> value.split("\\|", -1)[0];
        java.util.function.Function<String, String> profile = value -> value.split("\\|", -1)[1];
        String old = "com.netflix.ninja|Stéphanie|episode-3";
        String shared = "com.netflix.ninja|Stéphanie|episode-6";
        String anonymous = "com.netflix.mediaclient||episode-6";
        String other = "com.netflix.ninja|Arnaud|episode-2";
        String freebox = "file-video|Stéphanie|film";
        java.util.Map<String, List<String>> published = java.util.Collections.singletonMap("Stéphanie", Arrays.asList(shared));
        List<String> firstFrame = SystemContinueWatching.replaceNetflixProfileResumes(
                Arrays.asList(old, anonymous, other, freebox), published, source, profile);
        assertEquals(Arrays.asList(shared, other, freebox), firstFrame);
        java.util.ArrayList<String> finalSources = new java.util.ArrayList<>(firstFrame);
        finalSources.add(old); finalSources.add(anonymous); finalSources.add(shared);
        assertEquals(firstFrame, SystemContinueWatching.replaceNetflixProfileResumes(finalSources, published, source, profile));
    }

    @Test public void netflixAbsentPublicationKeepsKnownCardsAndProfilesRemainSeparate() {
        java.util.function.Function<String, String> source = value -> "com.netflix.ninja";
        java.util.function.Function<String, String> profile = value -> value.split("\\|", -1)[0];
        List<String> local = Arrays.asList("Stéphanie|episode-3", "Arnaud|episode-2");
        assertEquals(local, SystemContinueWatching.replaceNetflixProfileResumes(local,
                java.util.Collections.emptyMap(), source, profile));
        java.util.Map<String, List<String>> published = new java.util.LinkedHashMap<>();
        published.put("Stéphanie", Arrays.asList("Stéphanie|episode-6"));
        published.put("Arnaud", Arrays.asList("Arnaud|episode-5"));
        assertEquals(Arrays.asList("Stéphanie|episode-6", "Arnaud|episode-5"),
                SystemContinueWatching.replaceNetflixProfileResumes(local, published, source, profile));
    }

    @Test public void netflixCloudCardsKeepDistinctContentAndProfileIdentities() {
        String profile = String.join("", java.util.Collections.nCopies(64, "a"));
        String other = String.join("", java.util.Collections.nCopies(64, "b"));
        String first = SystemContinueWatching.netflixProfileContentKey(profile, "80000103");
        assertTrue(first.matches("netflix_[0-9a-f]{64}"));
        assertNotEquals(first, SystemContinueWatching.netflixProfileContentKey(profile, "80000106"));
        assertNotEquals(first, SystemContinueWatching.netflixProfileContentKey(other, "80000103"));
        assertTrue(SystemContinueWatching.sameNetflixProfile(" STÉPHANIE ", "Stéphanie"));
        assertFalse(SystemContinueWatching.sameNetflixProfile("Stéphanie", "Arnaud"));
        assertTrue(SystemContinueWatching.netflixSnapshotReplacesLocal("STÉPHANIE", "Stéphanie"));
        assertTrue(SystemContinueWatching.netflixSnapshotReplacesLocal("", "Stéphanie"));
        assertFalse(SystemContinueWatching.netflixSnapshotReplacesLocal("Arnaud", "Stéphanie"));
        assertFalse(SystemContinueWatching.netflixSnapshotReplacesLocal("Stéphanie", ""));
        assertEquals("", SystemContinueWatching.netflixProfileContentKey(profile, "intent:private"));
    }
    @Test
    public void netflixResumeLabelsAreEpisodeCaptionsRatherThanSynopses() {
        assertEquals("", SystemContinueWatching.publishedResumeSynopsis("com.netflix.ninja", "Reprendre la lecture Frères"));
        assertEquals("Frères", SystemContinueWatching.netflixResumeEpisodeTitle("À l’est d’Éden", "Reprendre la lecture Frères"));
        assertEquals("Bain de sang", SystemContinueWatching.netflixResumeEpisodeTitle("Monster", "Continue watching: Bain de sang"));
        assertEquals("", SystemContinueWatching.netflixResumeEpisodeTitle("Film", "Reprendre la lecture Film"));
        assertEquals("", SystemContinueWatching.netflixResumeEpisodeTitle("Film", "Continue watching"));
        assertEquals("Un véritable synopsis.", SystemContinueWatching.publishedResumeSynopsis("com.netflix.ninja", "Un véritable synopsis."));
        assertEquals("Reprendre la lecture Frères", SystemContinueWatching.publishedResumeSynopsis("com.jojo.tv", "Reprendre la lecture Frères"));
    }

    @Test
    public void netflixEpisodeLabelDoesNotDecideWhetherResumeIsMovieOrSeries() {
        assertEquals("unknown", SystemContinueWatching.netflixResumeMediaType("À l’est d’Éden", "Reprendre la lecture Frères", "", ""));
        assertEquals("tv", SystemContinueWatching.netflixResumeMediaType("Série", "", "1", "2"));
        assertEquals("tv", SystemContinueWatching.netflixResumeMediaType("Série S01E02", "", null, null));
        assertEquals("unknown", SystemContinueWatching.netflixResumeMediaType("Film", "Reprendre la lecture Film", "0", "0"));
        assertEquals("unknown", SystemContinueWatching.netflixResumeMediaType("Film", "Un synopsis.", null, null));
    }

    @Test
    public void netflixCombinedTitleOffersSeriesAndEpisodeForMetadataLookup() {
        java.util.Map<String, String> candidates = SystemContinueWatching.netflixSeriesEpisodeCandidates(
                "The Gentlemen, La Reconquête du royaume");
        assertEquals(1, candidates.size());
        assertEquals("La Reconquête du royaume", candidates.get("The Gentlemen"));
        assertEquals("unknown", SystemContinueWatching.netflixResumeMediaType(
                "The Gentlemen, La Reconquête du royaume", "Reprendre la lecture", null, null));
    }

    @Test
    public void netflixCommaCandidatesKeepEveryPossibleTitleBoundary() {
        java.util.Map<String, String> candidates = SystemContinueWatching.netflixSeriesEpisodeCandidates(
                "Série, deuxième partie, Un épisode, deux histoires");
        assertEquals(3, candidates.size());
        assertEquals("Un épisode, deux histoires", candidates.get("Série, deuxième partie"));
        assertEquals("deux histoires", candidates.get("Série, deuxième partie, Un épisode"));
        // Candidate generation must not reinterpret a comma in a movie title as TV evidence.
        assertEquals("unknown", SystemContinueWatching.netflixResumeMediaType(
                "Veni, Vidi, Vici", "", null, null));
        assertTrue(SystemContinueWatching.netflixMovieMetadataMatches("unknown", "", "2024", 86, "2024", 86));
    }

    @Test
    public void netflixSeparateEpisodeCaptionAndEmptyTitlesNeedNoCommaFallback() {
        assertTrue(SystemContinueWatching.netflixSeriesEpisodeCandidates("À l’est d’Éden").isEmpty());
        assertEquals("Frères", SystemContinueWatching.netflixResumeEpisodeTitle(
                "À l’est d’Éden", "Reprendre la lecture Frères"));
        assertTrue(SystemContinueWatching.netflixSeriesEpisodeCandidates(null).isEmpty());
        assertTrue(SystemContinueWatching.netflixSeriesEpisodeCandidates("  ").isEmpty());
        assertTrue(SystemContinueWatching.netflixSeriesEpisodeCandidates(", Épisode").isEmpty());
        assertTrue(SystemContinueWatching.netflixSeriesEpisodeCandidates("Série, ").isEmpty());
    }

    @Test
    public void netflixMetadataDoesNotChooseBetweenAmbiguousMoviesAndSeries() {
        assertEquals("tv", SystemContinueWatching.resolvedResumeMediaType("tv", 12, 34));
        assertEquals("", SystemContinueWatching.resolvedResumeMediaType("tv", 12, 0));
        assertEquals("movie", SystemContinueWatching.resolvedResumeMediaType("unknown", 12, 0));
        assertEquals("tv", SystemContinueWatching.resolvedResumeMediaType("unknown", 0, 34));
        assertEquals("", SystemContinueWatching.resolvedResumeMediaType("unknown", 12, 34));
        assertEquals("", SystemContinueWatching.resolvedResumeMediaType("unknown", 0, 0));
    }

    @Test
    public void netflixEpisodeResumeRejectsTheSameName1955Movie() {
        String episodeTitle = SystemContinueWatching.netflixResumeEpisodeTitle(
                "À l’est d’Éden", "Reprendre la lecture Frères");
        assertFalse(SystemContinueWatching.netflixMovieMetadataMatches("unknown", episodeTitle, "", 66, "1955", 117));
        assertFalse(SystemContinueWatching.netflixMovieMetadataMatches("unknown", episodeTitle, "", 0, "1955", 117));
        assertFalse(SystemContinueWatching.netflixMovieMetadataMatches("unknown", episodeTitle, "2026", 0, "1955", 117));
        assertEquals("tv", SystemContinueWatching.resolvedResumeMediaType("unknown", 0, 34));
    }

    @Test
    public void netflixActualMoviesRemainEligibleWithConsistentTimingAndYear() {
        assertTrue(SystemContinueWatching.netflixMovieMetadataMatches("unknown", "", "1955", 117, "1955", 117));
        assertTrue(SystemContinueWatching.netflixMovieMetadataMatches("unknown", "", "", 116, "1955", 117));
        assertFalse(SystemContinueWatching.netflixMovieMetadataMatches("unknown", "", "2026", 117, "1955", 117));
        assertFalse(SystemContinueWatching.netflixMovieMetadataMatches("unknown", "", "", 117, "1955", 0));
        assertFalse(SystemContinueWatching.netflixMovieMetadataMatches("tv", "", "1955", 117, "1955", 117));
    }

    @Test
    public void netflixTitleLogosUseOriginalResolutionIncludingCachedTmdbUrls() {
        assertEquals("https://image.tmdb.org/t/p/original/logo.png",
                SystemContinueWatching.netflixTitleLogoUrl("https://image.tmdb.org/t/p/w500/logo.png"));
        assertEquals("https://image.tmdb.org/t/p/original/logo.svg",
                SystemContinueWatching.netflixTitleLogoUrl("https://image.tmdb.org/t/p/original/logo.svg"));
        assertEquals("https://provider.example/w500/logo.png",
                SystemContinueWatching.netflixTitleLogoUrl("https://provider.example/w500/logo.png"));
        assertEquals("", SystemContinueWatching.netflixTitleLogoUrl(null));
    }

    @Test
    public void sharedYoutubeIdentitySurvivesPackageAndSeekChanges() {
        String first = SystemContinueWatching.sharedResumeKey("io.gh.reisxd.tizentube.cobalt", "Vidéo", "Chaîne",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s", null, null);
        assertEquals(first, SystemContinueWatching.sharedResumeKey("dev.tizentube.tv", "Vidéo", "Chaîne",
                "https://youtu.be/dQw4w9WgXcQ?t=60", null, null));
        assertNotEquals(first, SystemContinueWatching.sharedResumeKey("dev.tizentube.tv", "Vidéo", "Chaîne",
                "https://www.youtube.com/watch?v=9bZkp7q19f0", null, null));
    }

    @Test
    public void sharedNetflixEpisodesKeepTheirOwnIdentityDespiteIdenticalTitles() {
        String first = SystemContinueWatching.sharedResumeKey("com.netflix.ninja", "Même série", "Reprendre la lecture",
                "https://www.netflix.com/watch/80000001?trkid=123", "1", "1");
        assertEquals(first, SystemContinueWatching.sharedResumeKey("com.netflix.mediaclient", "Même série", "",
                "https://www.netflix.com/watch/80000001?trkid=456&t=100", "1", "1"));
        assertEquals(first, SystemContinueWatching.sharedResumeKey("com.netflix.ninja", "Même série", "",
                "netflix:80000001", "1", "1"));
        assertNotEquals(first, SystemContinueWatching.sharedResumeKey("com.netflix.ninja", "Même série", "",
                "https://www.netflix.com/watch/80000002", "1", "2"));
    }

    @Test
    public void sharedFreeTvIdentityUsesProgramIdAcrossPackageVariants() {
        String first = SystemContinueWatching.sharedResumeKey("net.oqee.androidtv.mod", "Replay", "",
                "https://oq.ee/playback/replay?id=42&source=tv", null, null);
        assertEquals(first, SystemContinueWatching.sharedResumeKey("net.oqee.androidtv", "Replay", "",
                "https://oq.ee/playback/replay?source=home&id=42", null, null));
        assertNotEquals(first, SystemContinueWatching.sharedResumeKey("net.oqee.androidtv", "Replay", "",
                "https://oq.ee/playback/replay?id=43", null, null));
    }

    @Test
    public void unresolvedYoutubeTitlesDoNotCombineDifferentChannels() {
        assertNotEquals(SystemContinueWatching.sharedResumeKey("dev.tizentube.tv", "Interview", "Chaîne A", "", null, null),
                SystemContinueWatching.sharedResumeKey("dev.tizentube.tv", "Interview", "Chaîne B", "", null, null));
    }

    @Test
    public void netflixRemovalKeepsTitlesStillPublishedByEitherNativeSource() {
        java.util.Set<String> previous = new HashSet<>(Arrays.asList("netflix_movie", "netflix_series", "netflix_removed"));
        java.util.Set<String> watchNextAndPreview = new HashSet<>(Arrays.asList("netflix_movie", "netflix_series"));
        assertEquals(new HashSet<>(Arrays.asList("netflix_removed")),
                SystemContinueWatching.removedNetflixResumeKeys(previous, watchNextAndPreview, true, true));
    }

    @Test
    public void validEmptyNetflixSnapshotRemovesTheLastPublishedTitles() {
        java.util.Set<String> previous = new HashSet<>(Arrays.asList("netflix_movie", "netflix_series", "free-tv_other"));
        assertEquals(new HashSet<>(Arrays.asList("netflix_movie", "netflix_series")),
                SystemContinueWatching.removedNetflixResumeKeys(previous, java.util.Collections.emptySet(), true, true));
    }

    @Test
    public void failedOrPartialNativeQueriesNeverDeleteNetflixHistory() {
        java.util.Set<String> previous = new HashSet<>(Arrays.asList("netflix_movie"));
        assertTrue(SystemContinueWatching.removedNetflixResumeKeys(previous, java.util.Collections.emptySet(), false, true).isEmpty());
        assertTrue(SystemContinueWatching.removedNetflixResumeKeys(previous, java.util.Collections.emptySet(), true, false).isEmpty());
        assertTrue(SystemContinueWatching.removedNetflixResumeKeys(previous, java.util.Collections.emptySet(), false, false).isEmpty());
    }

    @Test
    public void remoteOnlyNetflixHistoryHasNoLocalRemovalSignal() {
        assertTrue(SystemContinueWatching.removedNetflixResumeKeys(java.util.Collections.emptySet(),
                java.util.Collections.emptySet(), true, true).isEmpty());
    }

    @Test
    public void staleNotificationsCannotRestoreTitlesRemovedInNetflix() {
        assertFalse(SystemContinueWatching.mayRestoreSharedResume(true, true, true, "notification"));
        assertFalse(SystemContinueWatching.mayRestoreSharedResume(true, true, false, "preview-direct"));
        assertTrue(SystemContinueWatching.mayRestoreSharedResume(true, true, true, "watch-next"));
        assertTrue(SystemContinueWatching.mayRestoreSharedResume(true, true, true, "preview-42"));
        assertTrue(SystemContinueWatching.mayRestoreSharedResume(false, false, false, "notification"));
        assertTrue(SystemContinueWatching.mayRestoreSharedResume(true, false, true, "notification"));
    }

    @Test
    public void firstOrUnchangedNativeHistoryDoesNotOverwriteARemoteResume() {
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(false, -1L, 10_000L, -1, -1));
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(true, 10_000L, 10_000L, -1, -1));
        assertEquals(60_000L, SystemContinueWatching.sharedResumeTiming(60_000L, 10_000L, false));
        assertEquals(60_000L, SystemContinueWatching.sharedResumeTiming(60_000L, -1L, true));
    }

    @Test
    public void aRealRewindOrFreeTvProgressChangeCanReplaceTheRememberedPosition() {
        assertTrue(SystemContinueWatching.sharedResumeProgressChanged(true, 60_000L, 10_000L, -1, -1));
        assertEquals(10_000L, SystemContinueWatching.sharedResumeTiming(60_000L, 10_000L, true));
        assertTrue(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, -1L, 20, 100));
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, -1L, 20, 20));
        assertEquals(-1L, SystemContinueWatching.sharedResumeTiming(-1L, -1L, true));
    }

    @Test
    public void publishedNetflixEpisodeChangesEvenWhenBothPositionsAreUnknown() {
        SystemContinueWatching.Item first = new SystemContinueWatching.Item("com.netflix.ninja", "East of Eden",
                "Reprendre la lecture Pilote", "", "https://www.netflix.com/title/42", -1L, -1L, "1", "1");
        SystemContinueWatching.Item third = new SystemContinueWatching.Item("com.netflix.ninja", "East of Eden",
                "Reprendre la lecture Un nouveau départ", "", "https://www.netflix.com/title/42", -1L, -1L, "1", "3");
        assertTrue(SystemContinueWatching.sharedResumeEpisodeChanged(true, first, third));
        assertFalse(SystemContinueWatching.sharedResumeEpisodeChanged(false, first, third));
        // A real subsequent change back to episode 1 is a rewatch, not a reason to keep episode 3.
        assertTrue(SystemContinueWatching.sharedResumeEpisodeChanged(true, third, first));
    }

    @Test
    public void netflixEpisodeCaptionsCanAdvanceWithoutPublishedCounters() {
        SystemContinueWatching.Item first = new SystemContinueWatching.Item("com.netflix.ninja", "East of Eden",
                "Reprendre la lecture Pilote", "", "", -1L, -1L);
        SystemContinueWatching.Item third = new SystemContinueWatching.Item("com.netflix.ninja", "East of Eden",
                "Reprendre la lecture Un nouveau départ", "", "", -1L, -1L);
        assertTrue(SystemContinueWatching.sharedResumeEpisodeChanged(true, first, third));
        assertFalse(SystemContinueWatching.sharedResumeEpisodeChanged(false, first, third));
        assertFalse(SystemContinueWatching.sharedResumeEpisodeChanged(true, first,
                new SystemContinueWatching.Item("com.netflix.ninja", "East of Eden", "", "", "", -1L, -1L)));
    }

    @Test
    public void missingCountersAndFormattingChangesDoNotInventAnotherNetflixEpisode() {
        SystemContinueWatching.Item first = new SystemContinueWatching.Item("com.netflix.ninja", "Série",
                "Reprendre la lecture Pilote", "", "", -1L, -1L, "01", "01");
        assertFalse(SystemContinueWatching.sharedResumeEpisodeChanged(true, first,
                new SystemContinueWatching.Item("com.netflix.ninja", "Série", "Resume watching Pilote",
                        "", "", -1L, -1L, "1", "1")));
        assertFalse(SystemContinueWatching.sharedResumeEpisodeChanged(true, first,
                new SystemContinueWatching.Item("com.netflix.ninja", "Série", "", "", "", -1L, -1L, null, null)));
    }

    @Test
    public void laterPublishedTizenTubeProgressIsAcceptedButAFirstSnapshotStillCannotOverwriteTheNas() {
        assertTrue(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, 120_000L, -1, -1));
        assertTrue(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, -1L, -1, 35));
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, 0L, -1, 0));
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(false, -1L, 120_000L, -1, 35));
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, 10_000L, -1, -1, 60_000L, -1));
        assertFalse(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, -1L, -1, 20, -1L, 60));
        assertTrue(SystemContinueWatching.sharedResumeProgressChanged(true, -1L, 120_000L, -1, -1, 60_000L, -1));
        assertTrue(SystemContinueWatching.sharedResumeProgressChanged(true, 10_000L, 20_000L, -1, -1, 60_000L, -1));
    }

    @Test
    public void usesBuiltInBrowserOnAndroidTvInsteadOfAnUnreliableDocumentPicker() {
        assertEquals(true, LocalMediaNavigation.usesBuiltInBrowser());
    }

    @Test
    public void recognizesBmpPhotosAsValidWallpapers() {
        assertEquals(true, LocalMediaNavigation.supportsWallpaperFile("vacances.bmp"));
        assertEquals(false, LocalMediaNavigation.supportsWallpaperFile("notes.pdf"));
    }

    @Test
    public void settingsChromeKeepsDateTimeAndRoundedCardsVisible() {
        assertEquals(true, SettingsPresentation.showsDateTime());
        assertEquals(14, SettingsPresentation.cardCornerRadiusDp());
    }

    @Test
    public void autostartRunsAtBootAndCanAlsoResumeAfterStandby() {
        assertEquals(true, AutostartRules.shouldLaunch("boot", true, false));
        assertEquals(false, AutostartRules.shouldLaunch("boot", false, true));
        assertEquals(true, AutostartRules.shouldLaunch("screen_on", true, true));
        assertEquals(false, AutostartRules.shouldLaunch("screen_on", true, false));
        assertEquals(8_000L, AutostartRules.delayMillis(8));
        assertEquals(60_000L, AutostartRules.delayMillis(120));
    }

    @Test
    public void appPresentationSupportsAlphabeticalOrderAndPersistentLabels() {
        assertEquals(true, AppPresentationRules.isAlphabeticalOrder("alphabetical"));
        assertEquals(false, AppPresentationRules.isAlphabeticalOrder("manual"));
        assertEquals(1f, AppPresentationRules.labelAlpha(true, false), 0f);
        assertEquals(1f, AppPresentationRules.labelAlpha(false, true), 0f);
        assertEquals(0f, AppPresentationRules.labelAlpha(false, false), 0f);
    }

    @Test
    public void continueWatchingCanBeHiddenAndItsCardCountIsBounded() {
        assertEquals(false, ContentPresentationRules.showsContinueWatching(false));
        assertEquals(true, ContentPresentationRules.showsContinueWatching(true));
        assertEquals(5, ContentPresentationRules.visibleCardCount(5, 12));
        assertEquals(3, ContentPresentationRules.visibleCardCount(10, 3));
    }

    @Test
    public void maintenanceResetsOnlyTheRequestedSettingsFamily() {
        assertEquals(true, MaintenanceRules.keysFor("appearance").contains("background_color"));
        assertEquals(false, MaintenanceRules.keysFor("appearance").contains("nas_token"));
        assertEquals(true, MaintenanceRules.keysFor("organization").contains("app_order"));
        assertEquals(false, MaintenanceRules.keysFor("organization").contains("wallpaper_uri"));
    }

    @Test
    public void backNavigationPrioritizesModalStatesBeforeChangingScreens() {
        assertEquals(BackNavigationRules.Action.CANCEL_MOVE, BackNavigationRules.nextAction(true, true, true, true));
        assertEquals(BackNavigationRules.Action.DISMISS_POPUP, BackNavigationRules.nextAction(false, true, true, true));
        assertEquals(BackNavigationRules.Action.POP_SCREEN, BackNavigationRules.nextAction(false, false, true, true));
        assertEquals(BackNavigationRules.Action.GO_HOME, BackNavigationRules.nextAction(false, false, false, true));
        assertEquals(BackNavigationRules.Action.STAY_HOME, BackNavigationRules.nextAction(false, false, false, false));
    }

    @Test
    public void netflixGeneralChannelRetainsExplicitResumeWithMissingTiming() {
        assertEquals(true, SystemContinueWatching.isPublishedResumePreview(
                "Reprendre la lecture La Reconquête du royaume", -1L, -1L));
        assertEquals(true, SystemContinueWatching.isPublishedResumePreview(
                "Resume watching Episode 2", 120_000L, 0L));
        assertEquals(true, SystemContinueWatching.isPublishedResumePreview("", 120_000L, 30_000L));
        assertEquals(false, SystemContinueWatching.isPublishedResumePreview(
                "Nouvel épisode : regarder maintenant", -1L, -1L));
        assertEquals(false, SystemContinueWatching.isPublishedResumePreview(
                "Une nouvelle série à découvrir", 120_000L, 0L));
        assertEquals(false, SystemContinueWatching.isPublishedResumePreview(
                "Reprendre la lecture", 120_000L, 120_000L));
    }

    @Test
    public void freeTvProviderRetainsContentWithoutImportingProfileOrCompletedPlayback() {
        String replay = "https://oq.ee/playback/replay?id=123";
        assertTrue(SystemContinueWatching.isFreeTvResume("replay", "Telefoot", replay, 0));
        assertTrue(SystemContinueWatching.isFreeTvResume("vod", "Un film", replay, 45));
        assertFalse(SystemContinueWatching.isFreeTvResume("profile", "Arnaud", replay, -1));
        assertFalse(SystemContinueWatching.isFreeTvResume("replay", "Telefoot", replay, 100));
        assertFalse(SystemContinueWatching.isFreeTvResume("replay", "", replay, 20));
        assertFalse(SystemContinueWatching.isFreeTvResume("replay", "Telefoot", "", 20));
    }

    @Test
    public void keepsOnlyPublishedItemsThatCanBeResumed() {
        List<SystemContinueWatching.Item> items = SystemContinueWatching.resumableItems(Arrays.asList(
                new SystemContinueWatching.Item("Netflix", "Episode 2", "poster", "intent:#Intent;end"),
                new SystemContinueWatching.Item("SmartTube", "Video", "thumbnail", ""),
                new SystemContinueWatching.Item("", "Video", "thumbnail", "intent:#Intent;end")
        ));

        assertEquals(1, items.size());
        assertEquals("Netflix", items.get(0).title);
    }

    @Test
    public void requestsTvListingsOnlyUntilAndroidHasSeenTheChoice() {
        assertEquals(true, SystemContinueWatching.shouldRequestPermission(false, false));
        assertEquals(false, SystemContinueWatching.shouldRequestPermission(false, true));
        assertEquals(false, SystemContinueWatching.shouldRequestPermission(true, false));
    }

    @Test
    public void recognizesOnlyTheBeforeAppsResumePosition() {
        assertEquals(true, SystemContinueWatching.resumeRowBeforeApps("before"));
        assertEquals(false, SystemContinueWatching.resumeRowBeforeApps("after"));
        assertEquals(false, SystemContinueWatching.resumeRowBeforeApps(null));
    }

    @Test
    public void formatsPublishedSeasonAndEpisodeWithoutUsingSynopsis() {
        assertEquals("Saison 2 · Épisode 7", SystemContinueWatching.episodeLabel("2", "7"));
        assertEquals("Saison 3", SystemContinueWatching.episodeLabel("3", null));
        assertEquals("Épisode 4", SystemContinueWatching.episodeLabel(null, "4"));
        assertEquals("", SystemContinueWatching.episodeLabel(null, null));
    }

    @Test
    public void formatsResumePositionsInHoursAndMinutes() {
        assertEquals("7 min", SystemContinueWatching.playbackTimeLabel(7 * 60_000L));
        assertEquals("1 h 05", SystemContinueWatching.playbackTimeLabel(65 * 60_000L));
    }

    @Test
    public void watchProgressIdentityUsesProviderContentAndEpisodeIdsInsteadOfTitles() {
        String movieA = com.nuvio.tv.domain.model.WatchProgress.stableIdentity(
                "movie", "tmdb:101", "https://catalog.example", null, null);
        String movieASameId = com.nuvio.tv.domain.model.WatchProgress.stableIdentity(
                "movie", "tmdb:101", "https://catalog.example", null, null);
        String movieB = com.nuvio.tv.domain.model.WatchProgress.stableIdentity(
                "movie", "tmdb:202", "https://catalog.example", null, null);
        String otherProvider = com.nuvio.tv.domain.model.WatchProgress.stableIdentity(
                "movie", "tmdb:101", "https://other.example", null, null);
        String episodeOne = com.nuvio.tv.domain.model.WatchProgress.stableIdentity(
                "series", "tvdb:42", "https://catalog.example", 1, 1);
        String episodeTwo = com.nuvio.tv.domain.model.WatchProgress.stableIdentity(
                "series", "tvdb:42", "https://catalog.example", 1, 2);

        assertEquals(movieA, movieASameId);
        assertNotEquals(movieA, movieB);
        assertNotEquals(movieA, otherProvider);
        assertNotEquals(episodeOne, episodeTwo);
        assertNotEquals(movieA, episodeOne);
    }

    @Test
    public void conflictResolutionKeepsNewerRemoteOrUnpushedLocalProgress() {
        assertTrue(com.nuvio.tv.domain.model.WatchProgress.shouldApplyRemoteProgress(100L, 101L));
        assertFalse(com.nuvio.tv.domain.model.WatchProgress.shouldApplyRemoteProgress(101L, 100L));
        assertTrue(com.nuvio.tv.domain.model.WatchProgress.hasNewerUnpushedLocalProgress(101L, 100L, 100L));
        assertFalse(com.nuvio.tv.domain.model.WatchProgress.hasNewerUnpushedLocalProgress(99L, 100L, 100L));
    }

    @Test
    public void syncPullsInitialSnapshotsGapsAndForeignProgressChangesOnly() {
        assertTrue(SystemContinueWatching.shouldPullRemoteProgressSnapshot(0L, 1L, false));
        assertFalse(SystemContinueWatching.shouldPullRemoteProgressSnapshot(10L, 8L, false));
        assertTrue(SystemContinueWatching.shouldPullRemoteProgressSnapshot(7L, 9L, false));
        assertTrue(SystemContinueWatching.shouldPullRemoteProgressSnapshot(10L, 8L, true));
    }

    @Test
    public void remainingTimeIsBlankForUnknownCompletedAndLiveDurations() {
        assertEquals("50min restantes", SystemContinueWatching.remainingTimeLabel(60 * 60_000L, 10 * 60_000L));
        assertEquals("", SystemContinueWatching.remainingTimeLabel(0L, 50_000L));
        assertEquals("", SystemContinueWatching.remainingTimeLabel(-1L, 50_000L));
        assertEquals("", SystemContinueWatching.remainingTimeLabel(60_000L, 60_000L));
    }

    @Test
    public void mergesDistinctJojoProfileSnapshotsWithoutReplacingTheFirstProfile() {
        SystemContinueWatching.Item profileOne = new SystemContinueWatching.Item("com.jojo.tv", "Profil 1", "", "", "intent:#Intent;i.profile=1;end", 6_240_000L, 120_000L);
        SystemContinueWatching.Item profileTwo = new SystemContinueWatching.Item("com.jojo.tv", "Profil 2", "", "", "intent:#Intent;i.profile=2;end", 6_240_000L, 180_000L);

        java.util.List<SystemContinueWatching.Item> merged = SystemContinueWatching.mergeJojoProfileSnapshots(
                java.util.Collections.singletonList(profileOne),
                java.util.Collections.singletonList(profileTwo));

        assertEquals(2, merged.size());
        assertEquals("Profil 1", merged.get(0).title);
        assertEquals("Profil 2", merged.get(1).title);
    }

    @Test
    public void replacesAnOlderPositionForTheSameProfileVideo() {
        SystemContinueWatching.Item older = new SystemContinueWatching.Item("com.jojo.tv", "Même vidéo", "", "", "intent:#Intent;i.androidTvPosition=120000;end", 6_240_000L, 120_000L, "1", "2");
        SystemContinueWatching.Item newer = new SystemContinueWatching.Item("com.jojo.tv", "Même vidéo", "", "", "intent:#Intent;i.androidTvPosition=180000;end", 6_240_000L, 180_000L, "1", "2");

        java.util.List<SystemContinueWatching.Item> merged = SystemContinueWatching.mergeJojoProfileSnapshots(
                java.util.Collections.singletonList(older),
                java.util.Collections.singletonList(newer));

        assertEquals(1, merged.size());
        assertEquals(180_000L, merged.get(0).position);
    }

    @Test
    public void keepsTheSameVideoForTwoDifferentJojoProfiles() {
        SystemContinueWatching.Item arnaud = new SystemContinueWatching.Item("com.jojo.tv", "Enquête d'action", "Synopsis", "", "intent:#Intent;i.androidTvProfileId=1;end", 6_240_000L, 120_000L, null, null);
        SystemContinueWatching.Item stephanie = new SystemContinueWatching.Item("com.jojo.tv", "Enquête d'action", "Synopsis", "", "intent:#Intent;i.androidTvProfileId=2;end", 6_240_000L, 180_000L, null, null);

        java.util.List<SystemContinueWatching.Item> merged = SystemContinueWatching.mergeJojoProfileSnapshots(
                java.util.Collections.singletonList(arnaud),
                java.util.Collections.singletonList(stephanie));

        assertEquals(2, merged.size());
    }

    @Test
    public void replacesAProfileSnapshotWithAnEmptyValidResponse() {
        SystemContinueWatching.Item profileOne = new SystemContinueWatching.Item("com.jojo.tv", "Profil 1", "", "", "intent:#Intent;i.androidTvProfileId=1;end");
        SystemContinueWatching.Item profileTwo = new SystemContinueWatching.Item("com.jojo.tv", "Profil 2", "", "", "intent:#Intent;i.androidTvProfileId=2;end");

        java.util.List<SystemContinueWatching.Item> replaced = SystemContinueWatching.replaceJojoProfileSnapshot(
                java.util.Arrays.asList(profileOne, profileTwo), java.util.Collections.emptyList(), "1");

        assertEquals(1, replaced.size());
        assertEquals("Profil 2", replaced.get(0).title);
    }

    @Test
    public void usesProgrammeTitleInsteadOfSynopsisForResumeCard() {
        assertEquals("Enquête d'action", SystemContinueWatching.displayTitle("Enquête d'action", "Un synopsis qui ne doit pas devenir le titre"));
    }

    @Test
    public void replacesLegacyGenericTitleWhenTheSameProfilePublishesTheRealTitle() {
        SystemContinueWatching.Item legacy = new SystemContinueWatching.Item("com.jojo.tv", "Vidéos", "Synopsis", "", "intent:#Intent;S.contentId=freebox%3A%2FVideos%2FEnquete.mkv;i.androidTvProfileId=1;end", 6_240_000L, 120_000L);
        SystemContinueWatching.Item corrected = new SystemContinueWatching.Item("com.jojo.tv", "Enquête d'action", "Synopsis", "", "intent:#Intent;S.contentId=freebox%3A%2FVideos%2FEnquete.mkv;i.androidTvProfileId=1;end", 6_240_000L, 180_000L);

        java.util.List<SystemContinueWatching.Item> merged = SystemContinueWatching.mergeJojoProfileSnapshots(
                java.util.Collections.singletonList(legacy),
                java.util.Collections.singletonList(corrected));

        assertEquals(1, merged.size());
        assertEquals("Enquête d'action", merged.get(0).title);
    }

    @Test
    public void formatsHourDurationsWithoutInternalSpaces() {
        assertEquals("1h44", SystemContinueWatching.compactPlaybackTimeLabel(104 * 60_000L));
        assertEquals("2 min", SystemContinueWatching.compactPlaybackTimeLabel(2 * 60_000L));
        assertEquals("54 min", SystemContinueWatching.compactPlaybackTimeLabel(54 * 60_000L + 29_000L));
        assertEquals("55 min", SystemContinueWatching.compactPlaybackTimeLabel(54 * 60_000L + 30_000L));
        assertEquals("55 min", SystemContinueWatching.compactPlaybackTimeLabel(54 * 60_000L + 31_000L));
        assertEquals("1h00", SystemContinueWatching.compactPlaybackTimeLabel(59 * 60_000L + 30_000L));
    }

    @Test
    public void bridgeRequestsResumeMetadataAndKeepsARealProgressValue() {
        assertEquals(true, SystemContinueWatching.bridgeColumns().contains("season_number"));
        assertEquals(true, SystemContinueWatching.bridgeColumns().contains("episode_number"));
        assertEquals(false, SystemContinueWatching.legacyBridgeColumns().contains("season_number"));
        assertEquals(true, SystemContinueWatching.legacyBridgeColumns().contains("duration"));
        assertEquals(true, SystemContinueWatching.hasProgress(120_000L, 30_000L));
        assertEquals(false, SystemContinueWatching.hasProgress(120_000L, 0L));
    }

    @Test
    public void readsSeasonAndEpisodeFromTheJojoLaunchIntentWhenBridgeColumnsAreBlank() {
        String intent = "intent:#Intent;component=com.jojo.tv/.MainActivity;i.androidTvSeason=1;i.androidTvEpisode=10;end";
        assertEquals("1", SystemContinueWatching.jojoIntentExtra(intent, "androidTvSeason"));
        assertEquals("10", SystemContinueWatching.jojoIntentExtra(intent, "androidTvEpisode"));
    }

    @Test
    public void sharedHeaderUsesFrench24HourClockAndAppTilesClipFocusOverflow() {
        assertEquals("HH:mm", HomeChromeRules.clockPattern());
        assertEquals(true, HomeChromeRules.showsDateTimeInEveryHeader());
        assertEquals(true, HomeChromeRules.clipsFocusedAppIcon());
        assertEquals(true, HomeChromeRules.clipsManagedAppIcon());
    }

    @Test
    public void homeHeroChoicesKeepExistingPresentationForMissingAndUnknownSettings() {
        assertEquals(0, HomeChromeRules.homeHeroChoice(null));
        assertEquals(0, HomeChromeRules.homeHeroChoice(""));
        assertEquals(0, HomeChromeRules.homeHeroChoice("modern"));
        assertEquals(0, HomeChromeRules.homeHeroChoice("future-style"));
        assertEquals(0, HomeChromeRules.homeHeroChoice("current"));
    }

    @Test
    public void homeHeroChoicesPersistTheThreeApprovedDesignsIndependentlyOfBars() {
        assertEquals(1, HomeChromeRules.homeHeroChoice("muted"));
        assertEquals(2, HomeChromeRules.homeHeroChoice("airy"));
        assertEquals(3, HomeChromeRules.homeHeroChoice("editorial"));
        assertEquals(4, HomeChromeRules.HOME_HERO_LABELS.length);
        assertFalse(HomeChromeRules.HOME_HERO_STYLE.equals(HomeChromeRules.HOME_BARS_STYLE));
        assertEquals(1, HomeChromeRules.homeBarsChoice("black"));
        assertEquals(2, HomeChromeRules.homeBarsChoice("frosted"));
    }

    @Test
    public void homeHeroEpisodeLabelsPreserveEpisodeTitlesAndUnknownProviderLabels() {
        assertEquals("Saison 2 · Épisode 3", HomeChromeRules.homeHeroEpisodeLabel("S02E03"));
        assertEquals("Saison 1 · Épisode 104 · Le retour",
                HomeChromeRules.homeHeroEpisodeLabel("S01E104 · Le retour"));
        assertEquals("Épisode spécial", HomeChromeRules.homeHeroEpisodeLabel("Épisode spécial"));
        assertEquals("", HomeChromeRules.homeHeroEpisodeLabel(null));
    }

    @Test
    public void settingsGridKeepsTheLastTileAtHalfWidthAndLinksBothColumns() {
        assertEquals(true, SettingsGridRules.lastRowNeedsPlaceholder(1));
        assertEquals(false, SettingsGridRules.lastRowNeedsPlaceholder(2));
        assertEquals(true, SettingsGridRules.linksColumnsForDpad());
    }

    @Test
    public void watchNextRefreshesAfterPermissionAndDebouncesProviderChanges() {
        assertEquals(true, WatchNextRules.shouldObserve(26, true));
        assertEquals(false, WatchNextRules.shouldObserve(25, true));
        assertEquals(false, WatchNextRules.shouldObserve(26, false));
        assertEquals(180L, WatchNextRules.refreshDelayMillis());
    }

    @Test
    public void appTilesCenterAdaptiveIconsAndScaleOnlyTheirClippedStage() {
        assertEquals(true, AppTileRules.usesApplicationBannerBeforeActivityBanner());
        assertEquals(true, AppTileRules.usesOfficialCanalPlusBanner("com.canal.fai"));
        assertEquals(false, AppTileRules.usesOfficialCanalPlusBanner("com.netflix.ninja"));
        assertEquals(true, AppTileRules.usesCenteredFallbackIcon(false, false));
        assertEquals(false, AppTileRules.usesCenteredFallbackIcon(true, false));
        assertEquals(false, AppTileRules.usesCenteredFallbackIcon(false, true));
        assertEquals(false, AppTileRules.scalesClippedStageOnFocus());
        assertEquals(false, AppTileRules.elevatesTransparentStageOnFocus());
        assertEquals(true, AppTileRules.usesTransparentStageForStandardApps());
        assertEquals(0xFFFFFFFF, AppTileRules.fallbackTileColor("com.netflix.ninja"));
        assertEquals(0xFF00A9E0, AppTileRules.fallbackTileColor("com.amazon.firebat"));
        assertEquals(true, AppTileRules.requiresRecenteredBanner("com.netflix.ninja"));
        assertEquals(true, AppTileRules.requiresRecenteredBanner("com.amazon.firebat"));
        assertEquals(false, AppTileRules.requiresRecenteredBanner("fr.free.android.tv"));
        assertEquals(0x00000000, AppTileRules.fallbackTileColor("fr.free.android.tv"));
        assertEquals(0.125f, AppTileRules.fallbackLogoOffsetFraction(), 0.001f);
    }

    @Test
    public void appIconMaskScalesDownInSmallMenusAndWithDisplayDensity() {
        float standard = AppTileRules.appIconCornerRadius(160, 90, 1);
        float small = AppTileRules.appIconCornerRadius(80, 45, 1);
        assertEquals(8f, standard, 0.001f);
        assertEquals(standard / 2, small, 0.001f);
        assertEquals(standard * 2, AppTileRules.appIconCornerRadius(320, 180, 2), 0.001f);
        assertEquals(8f, AppTileRules.appIconCornerRadius(320, 180, 1), 0.001f);
    }

    @Test
    public void continueWatchingTileSizesKeepLargeAsTheCurrentDefault() {
        assertEquals(200, ContinueWatchingTileRules.widthDp("small"));
        assertEquals(250, ContinueWatchingTileRules.widthDp("medium"));
        assertEquals(300, ContinueWatchingTileRules.widthDp("large"));
        assertEquals(170, ContinueWatchingTileRules.heightDp("large"));
        assertEquals("Grande", ContinueWatchingTileRules.label("large"));
        assertEquals(272, ContinueWatchingTileRules.progressWidthDp(300, 14, 1f));
    }

    @Test
    public void notificationResumesRespectTheSelectedContentApplications() {
        assertEquals(true, NotificationResumeRules.acceptsPackage(new HashSet<>(Arrays.asList("com.netflix.ninja", "org.smarttube.beta")), "com.netflix.ninja"));
        assertEquals(true, NotificationResumeRules.acceptsPackage(new HashSet<>(Arrays.asList("com.netflix.ninja", "org.smarttube.beta")), "org.smarttube.beta"));
        assertEquals(false, NotificationResumeRules.acceptsPackage(new HashSet<>(Arrays.asList("com.netflix.ninja")), "com.amazon.firetv"));
    }

    @Test
    public void fireTvHomeRedirectOnlyHandlesAmazonLauncherWindowEvents() {
        assertEquals(true, HomeRedirectRules.shouldRedirect(true, HomeRedirectRules.VIEW_FOCUSED, "com.amazon.tv.launcher"));
        assertEquals(true, HomeRedirectRules.shouldRedirect(true, HomeRedirectRules.WINDOW_STATE_CHANGED, "com.amazon.tv.launcher"));
        assertEquals(false, HomeRedirectRules.shouldRedirect(false, HomeRedirectRules.WINDOW_STATE_CHANGED, "com.amazon.tv.launcher"));
        assertEquals(false, HomeRedirectRules.shouldRedirect(true, 0, "com.amazon.tv.launcher"));
        assertEquals(false, HomeRedirectRules.shouldRedirect(true, HomeRedirectRules.WINDOW_STATE_CHANGED, "com.netflix.ninja"));
        assertEquals(180L, HomeRedirectRules.redirectDelayMillis());
    }

    @Test
    public void appMenuUsesTheSystemAppropriateRemovalAction() {
        assertEquals("Désinstaller", AppUninstallRules.menuLabel(false));
        assertEquals("Désinstaller les mises à jour", AppUninstallRules.menuLabel(true));
        assertEquals("android.intent.action.DELETE", AppUninstallRules.androidUninstallAction());
        assertEquals(true, AppUninstallRules.usesPackageUninstaller(false));
        assertEquals(true, AppUninstallRules.usesPackageUninstaller(true));
        assertEquals(true, AppUninstallRules.opensContextMenuOnDpadLongPress(0, 23, 1));
        assertEquals(true, AppUninstallRules.opensContextMenuOnDpadLongPress(0, 66, 1));
        assertEquals(false, AppUninstallRules.opensContextMenuOnDpadLongPress(1, 23, 1));
        assertEquals(false, AppUninstallRules.opensContextMenuOnDpadLongPress(0, 22, 1));
    }

    @Test
    public void continueWatchingTextKeepsMarqueeRunningOutsideFocus() {
        assertEquals(-1, ResumeTextRules.marqueeRepeatLimit());
        assertEquals(true, ResumeTextRules.keepsMarqueeActiveWhenUnfocused());
    }

    @Test
    public void vpnHeaderAppearsOnlyForAnActiveVpnAndCompactsItsGaps() {
        assertEquals(false, VpnHeaderRules.showsVpn(false));
        assertEquals(true, VpnHeaderRules.showsVpn(true));
        assertEquals(12, VpnHeaderRules.headerGapDp(false));
        assertEquals(4, VpnHeaderRules.headerGapDp(true));
        assertEquals(true, VpnHeaderRules.shouldLaunchProvider(1));
        assertEquals(false, VpnHeaderRules.shouldLaunchProvider(0));
        assertEquals(false, VpnHeaderRules.shouldLaunchProvider(2));
    }
}
