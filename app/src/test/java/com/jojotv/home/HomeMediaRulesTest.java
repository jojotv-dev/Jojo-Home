package com.jojotv.home;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class HomeMediaRulesTest {
    @Test public void genericOrMissingEpisodeArtworkUsesTheOfficialStill() {
        String still = "https://image.tmdb.org/t/p/w780/episode.jpg";
        assertEquals(still, HomeMediaRules.seriesEpisodeArtwork("https://provider.test/series.jpg", true, "/episode.jpg"));
        assertEquals(still, HomeMediaRules.seriesEpisodeArtwork("", false, "/episode.jpg"));
        assertEquals(still, HomeMediaRules.seriesEpisodeArtwork(null, false, "/episode.jpg"));
    }

    @Test public void distinctProviderEpisodeImagesAndMissingOfficialStillsKeepTheirArtwork() {
        String artwork = "https://provider.test/episode-2.jpg";
        assertEquals(artwork, HomeMediaRules.seriesEpisodeArtwork(artwork, false, "/official.jpg"));
        for (String missing : Arrays.asList(null, "", "null", "https://example.test/image.jpg", "//example.test/image.jpg"))
            assertEquals(artwork, HomeMediaRules.seriesEpisodeArtwork(artwork, true, missing));
        assertEquals("", HomeMediaRules.seriesEpisodeArtwork("", true, ""));
    }

    @Test public void completedVideoDoesNotKeepItsInProgressBadgeWhenTheCachedDurationIsUnknown() {
        assertFalse(HomeMediaRules.thumbnailInProgress(4_500_000L, -1L, true));
        assertFalse(HomeMediaRules.thumbnailInProgress(4_500_000L, 5_000_000L, true));
        assertTrue(HomeMediaRules.thumbnailInProgress(120_000L, -1L, false));
        assertTrue(HomeMediaRules.thumbnailInProgress(120_000L, 5_000_000L, false));
        assertFalse(HomeMediaRules.thumbnailInProgress(5_000_000L, 5_000_000L, false));
        assertFalse(HomeMediaRules.thumbnailInProgress(0L, -1L, false));
    }

    @Test public void onlyAnExplicitTmdbPresentationJobSelectsPresenters() {
        assertTrue(HomeMediaRules.isTmdbPresenter("Presenter"));
        assertTrue(HomeMediaRules.isTmdbPresenter(" presenter "));
        for (String job : Arrays.asList(null, "", "Director", "Producer", "Self", "Guest", "Presentation Assistant"))
            assertFalse(String.valueOf(job), HomeMediaRules.isTmdbPresenter(job));
    }

    @Test public void tvPresentersReplaceEpisodeGuestsButNeverMovieActors() {
        List<String> guests = Arrays.asList("Heidi Sevestre", "Linda Bortoletto");
        List<String> hosts = Arrays.asList("Sophie Jovillard", "Jerome Pitorin", "Ismael Khelifa");
        assertSame(hosts, HomeMediaRules.preferredTmdbPeople("tv", guests, hosts));
        assertSame(guests, HomeMediaRules.preferredTmdbPeople("movie", guests, hosts));
    }

    @Test public void fictionAndMissingPresenterCreditsRetainTheirOriginalCast() {
        List<String> actors = Arrays.asList("Actor One", "Actor Two");
        assertSame(actors, HomeMediaRules.preferredTmdbPeople("tv", actors, Collections.emptyList()));
        assertSame(actors, HomeMediaRules.preferredTmdbPeople(null, actors, Collections.emptyList()));
    }

    @Test public void oldTvPeopleCachesNeedRefreshingWithoutInvalidatingMovieCredits() {
        assertFalse(HomeMediaRules.currentTmdbPeoplePolicy("tv", 0));
        assertTrue(HomeMediaRules.currentTmdbPeoplePolicy("tv", HomeMediaRules.TMDB_PEOPLE_POLICY_VERSION));
        assertTrue(HomeMediaRules.currentTmdbPeoplePolicy("movie", 0));
    }

    @Test public void unavailableMetadataDoesNotBlockEnrichment() {
        for (String value : new String[]{null, "", "  ", "N/A", "n / a", "N, A", "N.A.",
                "null", "None", "unknown", "-", "Non renseigné", "non disponible", "[]", "{}"}) {
            assertEquals(String.valueOf(value), "", HomeMediaRules.mediaMetadataText(value));
        }
    }

    @Test public void realMetadataIsNotRewrittenOrUrlDecoded() {
        assertEquals("CANAL+ : 100% vrai", HomeMediaRules.mediaMetadataText(" CANAL+ : 100% vrai "));
        assertEquals("Un documentaire sur N/A et ses usages.",
                HomeMediaRules.mediaMetadataText("Un documentaire sur N/A et ses usages."));
        assertEquals("Na", HomeMediaRules.mediaMetadataText("Na"));
    }

    @Test public void placeholderCreditsNeverBecomeTwoPeopleNamedNAndA() {
        assertTrue(HomeMediaRules.mediaMetadataNames("N/A").isEmpty());
        assertTrue(HomeMediaRules.mediaMetadataNames("N, A").isEmpty());
        assertEquals(Arrays.asList("Mike Tyson", "Madonna"),
                HomeMediaRules.mediaMetadataNames("Mike Tyson; Madonna | Mike Tyson"));
    }

    @Test public void heroAlwaysIncludesOneSuggestionsPageAfterTheResumes() {
        assertEquals(1, HomeMediaRules.homeHeroPageCount(0));
        assertEquals(2, HomeMediaRules.homeHeroPageCount(1));
        assertEquals(11, HomeMediaRules.homeHeroPageCount(10));
        assertEquals(0, HomeMediaRules.homeHeroNextPage(0, 0, 1));
        assertEquals(0, HomeMediaRules.homeHeroNextPage(0, 0, -1));
    }

    @Test public void heroNavigationWrapsThroughSuggestionsEvenWithASingleResume() {
        assertEquals(1, HomeMediaRules.homeHeroNextPage(0, 1, 1));
        assertEquals(1, HomeMediaRules.homeHeroNextPage(0, 1, -1));
        assertEquals(0, HomeMediaRules.homeHeroNextPage(1, 1, 1));
        assertEquals(0, HomeMediaRules.homeHeroNextPage(1, 1, -1));
        for (int resumes = 2; resumes <= 10; resumes++) {
            assertEquals(resumes, HomeMediaRules.homeHeroNextPage(resumes - 1, resumes, 1));
            assertEquals(resumes, HomeMediaRules.homeHeroNextPage(0, resumes, -1));
            assertEquals(0, HomeMediaRules.homeHeroNextPage(resumes, resumes, 1));
            assertEquals(resumes - 1, HomeMediaRules.homeHeroNextPage(resumes, resumes, -1));
        }
    }

    @Test public void suggestionNavigationReachesAllRowsAndSkipsMissingCategories() {
        int[] counts = {7, 11, 5};
        assertEquals(2, HomeMediaRules.homeSuggestionTargetColumn(counts, 4, 2));
        assertEquals(1, HomeMediaRules.homeSuggestionTargetColumn(counts, 5, 2));
        assertEquals(0, HomeMediaRules.homeSuggestionTargetColumn(counts, 6, 0));
        assertEquals(1, HomeMediaRules.homeSuggestionTargetColumn(counts, 8, 0));
        assertEquals(1, HomeMediaRules.homeSuggestionTargetColumn(counts, 10, 2));
        assertEquals(-1, HomeMediaRules.homeSuggestionTargetColumn(counts, 11, 1));
        assertEquals(2, HomeMediaRules.homeSuggestionTargetColumn(new int[]{0, 0, 0}, 0, 2));
        assertEquals(2, HomeMediaRules.homeSuggestionTargetColumn(new int[]{10, 20, 1000}, 999, 1));
        assertEquals(-1, HomeMediaRules.homeSuggestionTargetColumn(new int[]{10, 20, 1000}, 1000, 2));
    }

    @Test public void suggestionRowsKeepReadableHeightsAcrossHeroSizesAndFontScales() {
        assertEquals(1, HomeMediaRules.homeSuggestionRowCount(80, 1f));
        assertEquals(2, HomeMediaRules.homeSuggestionRowCount(168, 1f));
        assertEquals(3, HomeMediaRules.homeSuggestionRowCount(256, 1f));
        assertEquals(3, HomeMediaRules.homeSuggestionRowCount(500, 1f));
        assertEquals(2, HomeMediaRules.homeSuggestionRowCount(256, 1.4f));
        assertEquals(1, HomeMediaRules.homeSuggestionRowCount(256, 2f));
        assertEquals(1, HomeMediaRules.homeSuggestionRowCount(0, 1f));
    }

    @Test public void suggestionLandscapesFitTheirCardsAndLeaveRoomForTwoLineTitles() {
        for (int cardWidth : new int[]{220, 280, 360, 500}) {
            for (int cardHeight : new int[]{64, 80, 96, 112}) {
                int width = HomeMediaRules.homeSuggestionArtworkWidthDp(cardWidth, cardHeight);
                assertTrue(width <= 128);
                assertTrue(width <= cardWidth * .43f);
                assertTrue(width * 9 / 16 <= cardHeight - 12);
                assertTrue(cardWidth - width - 24 >= cardWidth * .45f);
            }
        }
    }

    @Test public void existingTitleLogoSurvivesEnrichmentAndOnlyMissingLogosUseFallback() {
        String existing = "https://provider.example/war-gold.png";
        String replacement = "https://image.tmdb.org/t/p/original/war-hbo.png";
        assertEquals(existing, HomeMediaRules.preferredTitleLogoUrl(existing, replacement));
        assertEquals(existing, HomeMediaRules.preferredTitleLogoUrl(existing, null));
        assertEquals(replacement, HomeMediaRules.preferredTitleLogoUrl("", replacement));
        assertEquals(replacement, HomeMediaRules.preferredTitleLogoUrl("  ", replacement));
        assertEquals("", HomeMediaRules.preferredTitleLogoUrl(null, null));
    }

    @Test public void titleLogoUsesTheProductionLanguageInsteadOfTheInterfaceLanguage() {
        Map<String, String> logos = new LinkedHashMap<>();
        logos.put("/english.png", "en"); logos.put("/neutral.png", ""); logos.put("/french.png", "fr-FR");
        assertEquals("https://image.tmdb.org/t/p/original/french.png", HomeMediaRules.originalTitleLogoUrl(logos, "fr"));
        assertEquals("https://image.tmdb.org/t/p/original/english.png", HomeMediaRules.originalTitleLogoUrl(logos, "en"));
        assertEquals("https://image.tmdb.org/t/p/original/neutral.png", HomeMediaRules.originalTitleLogoUrl(logos, "ja"));
        logos.put("/japanese.svg", "ja");
        assertEquals("https://image.tmdb.org/t/p/original/japanese.svg", HomeMediaRules.originalTitleLogoUrl(logos, "ja-JP"));
        assertEquals("ja,null,en", HomeMediaRules.titleLogoLanguages("JA_jp"));
    }

    @Test public void titleLogoIsTheSameRegardlessOfImageOrderAndDisplaySize() {
        Map<String, String> first = new LinkedHashMap<>(), reversed = new LinkedHashMap<>();
        first.put("/b.png", "fr"); first.put("/a.png", "fr"); first.put("/french.svg", "fr");
        first.put("/english.svg", "en");
        List<String> paths = new ArrayList<>(first.keySet()); Collections.reverse(paths);
        for (String path : paths) reversed.put(path, first.get(path));
        assertEquals("https://image.tmdb.org/t/p/original/french.svg", HomeMediaRules.originalTitleLogoUrl(first, "fr"));
        assertEquals(HomeMediaRules.originalTitleLogoUrl(first, "fr"), HomeMediaRules.originalTitleLogoUrl(reversed, "fr"));
        first.remove("/french.svg");
        assertEquals("https://image.tmdb.org/t/p/original/a.png", HomeMediaRules.originalTitleLogoUrl(first, "fr"));
        assertEquals("", HomeMediaRules.originalTitleLogoUrl(Collections.emptyMap(), "fr"));
    }

    @Test public void titleLogoCacheSharesOneSeriesIdentityAndExcludesLegacyTranslations() {
        String key = HomeMediaRules.titleLogoCacheKey("tv", 331669);
        assertEquals(key, HomeMediaRules.titleLogoCacheKey("series", 331669));
        assertNotEquals(key, HomeMediaRules.titleLogoCacheKey("movie", 331669));
        assertNotEquals("tmdb_title_logo_original_v2_tv_331669", key);
        assertEquals("", HomeMediaRules.titleLogoCacheKey("live", 331669));
        assertEquals("", HomeMediaRules.titleLogoCacheKey("tv", 0));
    }

    @Test public void echappeesBellesUsesTheProgrammeLogoInsteadOfTheCantalEdition() {
        Map<String, String> logos = new LinkedHashMap<>();
        logos.put("/1xEwnnPnJmbagALIKZQ0S8asYJt.png", "fr");
        logos.put("/27Wr1MynP4OPe7oWG6wkOyYHnrn.png", "fr");
        logos.put("/9wBckds6BaGBgjZCVtn5b2URgD5.png", "fr");
        logos.put("/cEOIzbsAD6zM96P22Q1gUKHk6Z4.png", "fr");
        String generic = "https://image.tmdb.org/t/p/original/cEOIzbsAD6zM96P22Q1gUKHk6Z4.png";
        assertEquals(generic, HomeMediaRules.originalTitleLogoUrl(logos, "fr"));
        logos.remove("/1xEwnnPnJmbagALIKZQ0S8asYJt.png");
        assertEquals(generic, HomeMediaRules.originalTitleLogoUrl(logos, "fr"));
        logos.remove("/27Wr1MynP4OPe7oWG6wkOyYHnrn.png");
        assertEquals(generic, HomeMediaRules.originalTitleLogoUrl(logos, "fr"));
    }

    @Test public void cachedProgrammeLogosUseTheSameGenericImageAtEveryDisplaySize() {
        String generic = "https://image.tmdb.org/t/p/original/cEOIzbsAD6zM96P22Q1gUKHk6Z4.png";
        for (String file : Arrays.asList("1xEwnnPnJmbagALIKZQ0S8asYJt.png",
                "27Wr1MynP4OPe7oWG6wkOyYHnrn.png", "9wBckds6BaGBgjZCVtn5b2URgD5.png")) {
            assertEquals(generic, HomeMediaRules.canonicalTitleLogoUrl("https://image.tmdb.org/t/p/original/" + file));
            assertEquals(generic, HomeMediaRules.canonicalTitleLogoUrl("https://media.themoviedb.org/t/p/w500/" + file));
        }
        assertEquals(generic, HomeMediaRules.canonicalTitleLogoUrl(generic));
        assertEquals("", HomeMediaRules.canonicalTitleLogoUrl(null));
        assertEquals("", HomeMediaRules.canonicalTitleLogoUrl(""));
        for (String other : Arrays.asList("https://image.tmdb.org/t/p/original/another-programme.png",
                "https://nas.example/1xEwnnPnJmbagALIKZQ0S8asYJt.png")) {
            assertEquals(other, HomeMediaRules.canonicalTitleLogoUrl(other));
        }
    }

    @Test public void programmeLogoUsesTheOfficialTransparentImageWhenTmdbHasNone() {
        String logo = "https://images-fio.6play.fr/v2/images/4697204/raw";
        assertEquals(logo, HomeMediaRules.programmeTitleLogoFallback("Y’a que la vérité qui compte", 0, ""));
        assertEquals(logo, HomeMediaRules.programmeTitleLogoFallback(
                "Y'a que la vérité qui compte - 14-10-2026 - 23h18.ts", 0, "unknown"));
        assertEquals(logo, HomeMediaRules.programmeTitleLogoFallback("Nom local", 114323, "tv"));
        assertEquals(logo, HomeMediaRules.programmeTitleLogoFallback("", 114323, "series"));
    }

    @Test public void programmeLogoCannotReplaceAnotherProgrammeOrAnExplicitMovieIdentity() {
        for (String title : Arrays.asList("Y'a que la vérité qui compte : le film", "Y'a que la vérité",
                "La vérité", "Autre émission", "Y'a que la vérité qui compte 2")) {
            assertEquals("", HomeMediaRules.programmeTitleLogoFallback(title, 0, ""));
        }
        assertEquals("", HomeMediaRules.programmeTitleLogoFallback("Y'a que la vérité qui compte", 123, "tv"));
        assertEquals("", HomeMediaRules.programmeTitleLogoFallback("Y'a que la vérité qui compte", 0, "movie"));
        assertEquals("", HomeMediaRules.programmeTitleLogoFallback("Y'a que la vérité qui compte", 114323, "live"));
        assertEquals("", HomeMediaRules.programmeTitleLogoFallback("Nom local", 114323, "unknown"));
        assertEquals("", HomeMediaRules.programmeTitleLogoFallback(null, 0, null));
    }

    @Test public void providerArtworkPreservesTitleNumbersAcrossHeroAndEpisodeLabels() {
        assertEquals(Collections.singletonList("Route 187"), HomeMediaRules.providerArtworkSearchTitles("Route 187"));
        assertEquals(Collections.singletonList("Route 187"), HomeMediaRules.providerArtworkSearchTitles("Route 187 (2026) · S1 E1"));
        assertEquals(Collections.singletonList("Route 187"), HomeMediaRules.providerArtworkSearchTitles("Route 187 (2026) (4K SDR) S01E01"));
        assertEquals(Collections.singletonList("Apollo 13"), HomeMediaRules.providerArtworkSearchTitles("Apollo 13 (1995)"));
        assertEquals(Collections.singletonList("Blade Runner 2049"), HomeMediaRules.providerArtworkSearchTitles("Blade Runner 2049 (2017)"));
        assertEquals(Collections.singletonList("Class 19"), HomeMediaRules.providerArtworkSearchTitles("Class 19"));
        assertEquals(Arrays.asList("Sur écoute", "The Wire"), HomeMediaRules.providerArtworkSearchTitles("Sur écoute | The Wire (2002)"));
        assertTrue(HomeMediaRules.providerArtworkSearchTitles(null).isEmpty());
    }

    @Test public void providerArtworkYearComesFromTheProgrammeRatherThanItsDisplayLabel() {
        assertEquals("2026", HomeMediaRules.providerArtworkYear("2026-10-01", "Route 187"));
        assertEquals("2026", HomeMediaRules.providerArtworkYear("", "Route 187 (2026) · S1 E1"));
        assertEquals("2017", HomeMediaRules.providerArtworkYear("", "Blade Runner 2049 (2017)"));
        assertEquals("", HomeMediaRules.providerArtworkYear("", "Route 187"));
    }

    @Test public void providerHeroArtworkRejectsHomonymsFromAnotherYearOrContentType() {
        assertTrue(HomeMediaRules.providerArtworkIdentityMatches("tv", 0, "2026", "tv", 42, "2026"));
        assertFalse(HomeMediaRules.providerArtworkIdentityMatches("tv", 0, "2026", "tv", 43, "2023"));
        assertFalse(HomeMediaRules.providerArtworkIdentityMatches("tv", 0, "2026", "movie", 42, "2026"));
        assertTrue(HomeMediaRules.providerArtworkIdentityMatches("tv", 42, "2026", "tv", 42, "2025"));
        assertFalse(HomeMediaRules.providerArtworkIdentityMatches("tv", 42, "2026", "tv", 43, "2026"));
        assertFalse(HomeMediaRules.providerArtworkIdentityMatches("tv", 42, "2026", "movie", 42, "2026"));
    }

    @Test public void continueWatchingCleanupRemovesGeneratedEnrichmentOnly() {
        for (String key : Arrays.asList("continue_watching_home_cache", "resume_synopsis_a12",
                "freebox_video_hero_metadata_a12", "video_player_metadata_a12", "video_artwork_tmdb_a12")) {
            assertTrue(key, HomeMediaRules.continueWatchingCachePreference(key, true));
        }
        assertTrue(HomeMediaRules.continueWatchingCachePreference("resume_artwork_12ab", false));
        assertFalse(HomeMediaRules.continueWatchingCachePreference("resume_artwork_12ab", true));
    }

    @Test public void continueWatchingCleanupPreservesPlaybackAndExplicitChoices() {
        for (String key : Arrays.asList("file_video_resumes", "video_resume_smb://nas/video.ts",
                "video_resume_signature_smb://nas/video.ts", "continue_watching_insertion_order",
                "dismissed_resume_keys", "home_resume_removed_p1_video", "home_resume_restored_p1_video",
                "jojotv_home_hidden_resume_keys", "resume_artwork_metadata_12ab",
                "resume_artwork_external_app_resume_netflix", "home_videos_file_artwork_video.ts",
                "home_videos_programme_hero_artwork_12ab", "external_thumbnail_artwork_12ab")) {
            assertFalse(key, HomeMediaRules.continueWatchingCachePreference(key, false));
        }
        assertFalse(HomeMediaRules.continueWatchingCachePreference(null, false));
    }

    @Test public void continueWatchingCleanupDeletesOnlyGeneratedImageFiles() {
        assertTrue(HomeMediaRules.continueWatchingCacheFile("resume_artwork_12ab.png"));
        assertTrue(HomeMediaRules.continueWatchingCacheFile("resume_artwork_12ab_landscape.png"));
        assertTrue(HomeMediaRules.continueWatchingCacheFile("home_hero_preview_12ab.png"));
        assertTrue(HomeMediaRules.continueWatchingCacheFile("home_hero_preview_12ab.png.tmp"));
        for (String name : Arrays.asList("selected_hero_12ab.jpg", "selected_video_12ab.jpg",
                "resume_artwork_12ab.jpg", "library.bin", "inprogress_1.json", "video_frames",
                "../resume_artwork_12ab.png", "resume_artwork_12ab.png.backup")) {
            assertFalse(name, HomeMediaRules.continueWatchingCacheFile(name));
        }
    }

    @Test public void removedVideoMatchesOnlyItsOwnFileAcrossPlaybackRoutes() {
        String key = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Freebox/Vidéos/Film.mkv");
        assertTrue(HomeMediaRules.homeVideoListKeyMatches(key, "smb://NAS/Freebox/Vid%C3%A9os/film.mkv"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches(key, "smb://nas/Freebox/Vidéos/Film.mkv.bak"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches(key, "smb://other/Freebox/Vidéos/Film.mkv"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches("external-playback|film", "smb://nas/film"));
        String local = HomeMediaRules.homeVideoListDismissalKey("/storage/Vidéos/Film.mkv");
        assertTrue(HomeMediaRules.homeVideoListKeyMatches(local, "file:///storage/Vidéos/Film.mkv"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches(local, "/storage/Vidéos/film.mkv"));
    }

    @Test public void removedVideoSeriesFolderHidesEpisodesWithoutHidingItsNeighbours() {
        String key = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Freebox/Vidéos/Serie/");
        assertTrue(HomeMediaRules.homeVideoListKeyMatches(key, "smb://nas/Freebox/Vidéos/Serie/"));
        assertTrue(HomeMediaRules.homeVideoListKeyMatches(key, "smb://nas/Freebox/Vidéos/Serie/Saison 2/Episode.mkv"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches(key, "smb://nas/Freebox/Vidéos/Serie 2/Episode.mkv"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches(key, "smb://nas/Freebox/Vidéos/Autre/Episode.mkv"));
        String plus = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Freebox/Vidéos/A+B.mkv");
        assertTrue(HomeMediaRules.homeVideoListKeyMatches(plus, "smb://nas/Freebox/Vidéos/A%2BB.mkv"));
        assertFalse(HomeMediaRules.homeVideoListKeyMatches(plus, "smb://nas/Freebox/Vidéos/A B.mkv"));
    }

    @Test public void removedVideoSurvivesStaleNasSnapshotsUntilAnExplicitReplay() {
        String path = "smb://nas/Freebox/Vidéos/Film.mkv";
        String key = HomeMediaRules.homeVideoListDismissalKey(path);
        Set<String> hidden = new HashSet<>(Arrays.asList(key, "external-playback|other"));
        assertTrue(HomeMediaRules.hiddenHomeVideo(path, HomeMediaRules.mergeResumeDismissals(
                hidden, Collections.emptySet(), Collections.singletonMap(key, 200L), Collections.singletonMap(key, 100L))));
        Set<String> replayed = HomeMediaRules.mergeResumeDismissals(Collections.emptySet(), hidden,
                Collections.singletonMap(key, 200L), Collections.singletonMap(key, 201L));
        assertFalse(HomeMediaRules.hiddenHomeVideo(path, replayed));
        assertTrue(replayed.contains("external-playback|other"));
    }

    @Test public void restoringOneHiddenVideoIncludesAliasesAndPreservesOtherRemovals() {
        String key = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Freebox/Vidéos/Film.mkv");
        String alias = HomeMediaRules.homeVideoListDismissalKey("smb://NAS/Freebox/Vid%C3%A9os/film.mkv");
        String neighbour = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Freebox/Vidéos/Film 2.mkv");
        Set<String> hidden = new HashSet<>(Arrays.asList(key, alias, neighbour, "external-playback|other"));
        Set<String> restored = HomeMediaRules.homeVideoListRestorationKeys(key, hidden);
        assertEquals(new HashSet<>(Arrays.asList(key, alias)), restored);
        hidden.removeAll(restored);
        Map<String, Long> removals = new HashMap<>(), restorations = new HashMap<>();
        removals.put(key, 200L); removals.put(alias, 300L);
        restorations.put(key, 301L); restorations.put(alias, 301L);
        Set<String> merged = HomeMediaRules.mergeResumeDismissals(hidden,
                new HashSet<>(Arrays.asList(key, alias)), removals, restorations);
        assertFalse(HomeMediaRules.hiddenHomeVideo("smb://nas/Freebox/Vidéos/Film.mkv", merged));
        assertTrue(merged.contains(neighbour));
        assertTrue(merged.contains("external-playback|other"));
    }

    @Test public void restoringHiddenFolderKeepsIndividuallyHiddenEpisodesAndOtherMasks() {
        String folder = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Vidéos/Série/");
        String episode = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Vidéos/Série/Episode.mkv");
        Set<String> hidden = new HashSet<>(Arrays.asList(folder, episode, "external-playback|other"));
        assertEquals(Collections.singleton(folder), HomeMediaRules.homeVideoListRestorationKeys(folder, hidden));
        assertEquals(Collections.singleton(episode), HomeMediaRules.homeVideoListRestorationKeys(episode, hidden));
        assertTrue(HomeMediaRules.homeVideoListRestorationKeys("external-playback|other", hidden).isEmpty());
        assertEquals("", HomeMediaRules.homeVideoListPath(null));
        assertEquals("", HomeMediaRules.homeVideoListPath("external-playback|other"));
    }

    @Test public void restoringHiddenVideoPreservesLiteralPlusAndLocalPathCase() {
        String plus = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Vidéos/A+B.mkv");
        String encoded = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Vidéos/A%2BB.mkv");
        String space = HomeMediaRules.homeVideoListDismissalKey("smb://nas/Vidéos/A B.mkv");
        assertEquals(new HashSet<>(Arrays.asList(plus, encoded)), HomeMediaRules.homeVideoListRestorationKeys(
                plus, Arrays.asList(plus, encoded, space)));
        String local = HomeMediaRules.homeVideoListDismissalKey("/storage/Vidéos/Film.mkv");
        String lower = HomeMediaRules.homeVideoListDismissalKey("/storage/Vidéos/film.mkv");
        assertEquals(Collections.singleton(local), HomeMediaRules.homeVideoListRestorationKeys(
                local, Arrays.asList(local, lower)));
    }

    @Test public void recurringProgrammeArtworkIgnoresTheBroadcastWeek() {
        assertEquals("90' Enquêtes", HomeMediaRules.recurringProgrammeName("90' Enquêtes - 19-09-2026 - 21h10.mkv"));
        assertEquals("90' Enquêtes", HomeMediaRules.recurringProgrammeName("90' Enquêtes - 26-09-2026 - 21h10.mkv"));
        assertEquals("90' Enquêtes", HomeMediaRules.recurringProgrammeName("90' Enquêtes (2026-10-03) 21:10.ts"));
    }

    @Test public void recurringProgrammeArtworkAcceptsEncodedNameSeparatorsAndShortYears() {
        assertEquals("Grands reportages", HomeMediaRules.recurringProgrammeName("Grands_reportages_11.10.26_13.40.ts"));
        assertEquals("Grands reportages", HomeMediaRules.recurringProgrammeName("Grands reportages - 20261018 - 13h40.ts"));
    }

    @Test public void recurringProgrammeArtworkPreservesDistinctUndatedTitles() {
        assertEquals("Madame Bodchon part à la retraite.mkv", HomeMediaRules.recurringProgrammeName("Madame Bodchon part à la retraite.mkv"));
        assertEquals("1917.mkv", HomeMediaRules.recurringProgrammeName("1917.mkv"));
        assertEquals("Apollo 13.mkv", HomeMediaRules.recurringProgrammeName("Apollo 13.mkv"));
        assertEquals("", HomeMediaRules.recurringProgrammeName(null));
    }

    @Test public void recurringProgrammeArtworkKeepsTheSameChoiceForRecreatedRecordings() {
        String identity = HomeMediaRules.recurringProgrammeArtworkIdentity("Envoyé spécial.mkv");
        assertEquals("envoye special", identity);
        for (String file : Arrays.asList("Envoyé spécial.ts", "Envoye_special.1.mkv",
                "ENVOYE SPECIAL.mkv.2", "Envoyé spécial (2).mkv",
                "Envoyé spécial - 09-10-2026 - 21h10.mkv",
                "Envoyé spécial - 16-10-2026 - 21h10.mkv")) {
            assertEquals(file, identity, HomeMediaRules.recurringProgrammeArtworkIdentity(file));
        }
    }

    @Test public void recurringProgrammeArtworkDoesNotMergeDifferentWorks() {
        assertNotEquals(HomeMediaRules.recurringProgrammeArtworkIdentity("Envoyé spécial.mkv"),
                HomeMediaRules.recurringProgrammeArtworkIdentity("Grands Reportages.mkv"));
        assertNotEquals(HomeMediaRules.recurringProgrammeArtworkIdentity("Apollo.mkv"),
                HomeMediaRules.recurringProgrammeArtworkIdentity("Apollo 13.mkv"));
        assertNotEquals(HomeMediaRules.recurringProgrammeArtworkIdentity("Top Gun.mkv"),
                HomeMediaRules.recurringProgrammeArtworkIdentity("Top Gun - Maverick.mkv"));
        assertNotEquals(HomeMediaRules.recurringProgrammeArtworkIdentity("Film (2021).mkv"),
                HomeMediaRules.recurringProgrammeArtworkIdentity("Film (2026).mkv"));
        assertEquals("1917", HomeMediaRules.recurringProgrammeArtworkIdentity("1917.mkv"));
        assertEquals("", HomeMediaRules.recurringProgrammeArtworkIdentity(null));
    }

    @Test public void recordingArtworkSearchFindsTheProgrammeBehindAnEpisodeTitle() {
        for (String episode : Arrays.asList("Le combat des irréductibles", "Héritiers")) {
            String title = "Grands Reportages (" + episode + ")";
            assertEquals(Arrays.asList(title, "Grands Reportages"), HomeMediaRules.recordingArtworkSearchTitles(title));
        }
    }

    @Test public void recordingArtworkSearchAcceptsEpisodeSubtitleSeparators() {
        for (String separator : Arrays.asList(" - ", " – ", " — ", " | ", ": ", ":")) {
            String title = "Grands Reportages" + separator + "Héritiers";
            assertEquals(Arrays.asList(title, "Grands Reportages"), HomeMediaRules.recordingArtworkSearchTitles(title));
        }
    }

    @Test public void recordingArtworkSearchKeepsTheFullTitleBeforeDroppingTheBroadcastDate() {
        String title = "Grands Reportages (Héritiers) - 11-10-2026 - 13h40";
        assertEquals(Arrays.asList(title, "Grands Reportages (Héritiers)", "Grands Reportages"),
                HomeMediaRules.recordingArtworkSearchTitles(title));
    }

    @Test public void recordingArtworkSearchStillHandlesGuestsAndLocations() {
        for (String suffix : Arrays.asList("avec Marie", "chez Paul", "en compagnie de Julie")) {
            String title = "Un dimanche à la campagne - " + suffix;
            assertEquals(Arrays.asList(title, "Un dimanche à la campagne"), HomeMediaRules.recordingArtworkSearchTitles(title));
        }
    }

    @Test public void recordingArtworkSearchPreservesOrdinaryTitlesAndTheirNumbers() {
        for (String title : Arrays.asList("1917", "Apollo 13", "Madame Bodchon part à la retraite", "Spider-Man", "Prisoner")) {
            assertEquals(Collections.singletonList(title), HomeMediaRules.recordingArtworkSearchTitles(title));
        }
    }

    @Test public void recordingArtworkSearchHandlesTruncatedAndNestedEpisodeSubtitles() {
        for (String title : Arrays.asList("Grands Reportages (Héritiers", "Grands Reportages (Héritiers (2/2))", "Grands Reportages(Héritiers)")) {
            assertEquals(Arrays.asList(title, "Grands Reportages"), HomeMediaRules.recordingArtworkSearchTitles(title));
        }
    }

    @Test public void recordingArtworkSearchAvoidsEmptyDuplicateOrOverlyShortQueries() {
        assertTrue(HomeMediaRules.recordingArtworkSearchTitles(null).isEmpty());
        assertTrue(HomeMediaRules.recordingArtworkSearchTitles("  ").isEmpty());
        assertEquals(Collections.singletonList("Grands Reportages"), HomeMediaRules.recordingArtworkSearchTitles("  Grands Reportages  "));
        assertEquals(Collections.singletonList("Le (meilleur épisode)"), HomeMediaRules.recordingArtworkSearchTitles("Le (meilleur épisode)"));
    }

    @Test public void landscapeFrameKeepsSeparatePortraitAndHeroCrops() {
        assertArrayEquals(new int[] {600, 0, 720, 1080}, HomeMediaRules.videoArtworkCrop(1920, 1080, true));
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, HomeMediaRules.videoArtworkCrop(1920, 1080, false));
    }

    @Test public void portraitFrameCanAlsoProduceALandscapeHero() {
        assertArrayEquals(new int[] {0, 150, 1080, 1620}, HomeMediaRules.videoArtworkCrop(1080, 1920, true));
        assertArrayEquals(new int[] {0, 656, 1080, 608}, HomeMediaRules.videoArtworkCrop(1080, 1920, false));
    }

    @Test public void providerRuntimeHandlesClockAndMinuteFormats() {
        assertEquals(105, HomeMediaRules.providerRuntimeMinutes("01:45:00"));
        assertEquals(106, HomeMediaRules.providerRuntimeMinutes("01:45:30"));
        assertEquals(105, HomeMediaRules.providerRuntimeMinutes("01:45"));
        assertEquals(105, HomeMediaRules.providerRuntimeMinutes("1h45"));
        assertEquals(105, HomeMediaRules.providerRuntimeMinutes("1 h 45 min"));
        assertEquals(60, HomeMediaRules.providerRuntimeMinutes("1h"));
        assertEquals(90, HomeMediaRules.providerRuntimeMinutes("1,5h"));
        assertEquals(105, HomeMediaRules.providerRuntimeMinutes("105"));
        assertEquals(105, HomeMediaRules.providerRuntimeMinutes("105 minutes"));
        assertEquals(42, HomeMediaRules.providerRuntimeMinutes("00:42:00"));
        assertEquals(65, HomeMediaRules.providerRuntimeMinutes("1h05"));
        for (String unknown : Arrays.asList(null, "", "0", "00:00:00", "01:90:00", "01:45:90",
                "N/A", "null", "NaN", "Infinity", "-10", "999999999999999999999")) {
            assertEquals(0, HomeMediaRules.providerRuntimeMinutes(unknown));
        }
    }

    @Test public void heroRuntimeKeepsWholeMovieAndEpisodeTimes() {
        assertEquals(105, HomeMediaRules.heroRuntimeMinutes("01:45:00"));
        assertEquals(42, HomeMediaRules.heroRuntimeMinutes("00:42:00"));
        assertEquals(65, HomeMediaRules.heroRuntimeMinutes("1h05"));
        assertEquals(45, HomeMediaRules.heroRuntimeMinutes("45 min"));
        assertEquals(0, HomeMediaRules.heroRuntimeMinutes("N/A"));
    }

    @Test public void playbackRuntimeUsesMillisecondsAndDoesNotInventOneMinute() {
        assertEquals(0, HomeMediaRules.playbackRuntimeMinutes(0L));
        assertEquals(0, HomeMediaRules.playbackRuntimeMinutes(1L));
        assertEquals(0, HomeMediaRules.playbackRuntimeMinutes(-1L));
        assertEquals(0, HomeMediaRules.playbackRuntimeMinutes(Long.MAX_VALUE));
        assertEquals(1, HomeMediaRules.playbackRuntimeMinutes(60_000L));
        assertEquals(42, HomeMediaRules.playbackRuntimeMinutes(2_520_000L));
        assertEquals(105, HomeMediaRules.playbackRuntimeMinutes(6_300_000L));
        assertEquals(105, HomeMediaRules.playbackRuntimeMinutes(6_330_000L));
        assertEquals(52, HomeMediaRules.playbackRuntimeMinutes(3_174_900L));
    }

    @Test public void externalRuntimePrefersMeasuredThenProviderThenCachedMinutes() {
        assertEquals(105, HomeMediaRules.externalRuntimeMinutes(6_300_000L, 1, "90 min"));
        assertEquals(105, HomeMediaRules.externalRuntimeMinutes(0L, 1, "01:45:00"));
        assertEquals(42, HomeMediaRules.externalRuntimeMinutes(1L, 1, "00:42:00"));
        assertEquals(65, HomeMediaRules.externalRuntimeMinutes(0L, 0, "1h05"));
        assertEquals(42, HomeMediaRules.externalRuntimeMinutes(0L, 42, "N/A"));
        assertEquals(0, HomeMediaRules.externalRuntimeMinutes(1L, 0, ""));
        assertEquals(0, HomeMediaRules.externalRuntimeMinutes(-1L, -1, null));
        assertEquals(52, HomeMediaRules.externalRuntimeMinutes(0L, 0, "00:52:54"));
        assertEquals(52, HomeMediaRules.externalRuntimeMinutes(0L, 0, "3174"));
    }

    @Test public void nativeMovieResumeKeepsTheProviderIdentityAndExcludesOtherRoutes() {
        assertTrue(HomeMediaRules.nativeIptvMovieResume("iptv:source:movie:42", "movie"));
        assertTrue(HomeMediaRules.nativeIptvMovieResume("iptv:other:movie:42", "MOVIE"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume("home-external:42", "movie"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume("freebox:/film.mkv", "movie"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume("tt123", "movie"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume("iptv:source:series:42", "series"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume("iptv:source:live:42", "iptv"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume(null, "movie"));
        assertFalse(HomeMediaRules.nativeIptvMovieResume("iptv:source:movie:42", null));
    }

    @Test public void nativeSeriesResumeUsesItsProviderIdentity() {
        assertTrue(HomeMediaRules.nativeIptvVodResume("iptv:source:series:42", "series"));
        assertTrue(HomeMediaRules.nativeIptvVodResume("iptv:other:series:42", "SERIES"));
        assertTrue(HomeMediaRules.nativeIptvVodResume("iptv:source:movie:42", "movie"));
        assertFalse(HomeMediaRules.nativeIptvVodResume("iptv:source:live:42", "iptv"));
        assertFalse(HomeMediaRules.nativeIptvVodResume("home-external:42", "series"));
        assertFalse(HomeMediaRules.nativeIptvVodResume(null, "series"));
    }

    @Test public void heroExcludesWatchedEpisodesAtTheSameThresholdAsTheirFolder() {
        assertFalse(HomeMediaRules.completedEpisodeResume(true, false, 0.92999f));
        assertTrue(HomeMediaRules.completedEpisodeResume(true, false, 0.93f));
        assertTrue(HomeMediaRules.completedEpisodeResume(true, false, 42f / 44f));
        assertFalse(HomeMediaRules.completedEpisodeResume(true, false, 0.25f));
        assertTrue(HomeMediaRules.completedEpisodeResume(true, true, 0f));
        assertFalse(HomeMediaRules.completedEpisodeResume(false, false, 0.95f));
    }

    @Test public void liveGridOrderSurvivesRefreshAndAppendsNewFavorites() {
        List<String> saved = Arrays.asList("canal", "removed", "tf1", "canal");
        assertEquals(Arrays.asList("canal", "tf1", "m6", "arte"),
                HomeMediaRules.liveChannelOrder(Arrays.asList("tf1", "m6", "canal", "arte"), saved));
        assertEquals(Arrays.asList("canal", "tf1", "m6", "arte"),
                HomeMediaRules.liveChannelOrder(Arrays.asList("arte", "tf1", "canal", "m6"),
                        Arrays.asList("canal", "tf1", "m6", "arte")));
        assertEquals(Arrays.asList("tf1", "m6"),
                HomeMediaRules.liveChannelOrder(Arrays.asList("tf1", "m6"), Collections.emptyList()));
        assertTrue(HomeMediaRules.liveChannelOrder(Collections.emptyList(), saved).isEmpty());
    }

    @Test public void liveGridNavigationKeepsRowsAndHandlesPartialLastRow() {
        assertEquals(0, HomeMediaRules.liveGridNeighbor(0, 12, 5, -1, 0));
        assertEquals(0, HomeMediaRules.liveGridNeighbor(0, 12, 5, 0, -1));
        assertEquals(1, HomeMediaRules.liveGridNeighbor(0, 12, 5, 1, 0));
        assertEquals(5, HomeMediaRules.liveGridNeighbor(0, 12, 5, 0, 1));
        assertEquals(4, HomeMediaRules.liveGridNeighbor(4, 12, 5, 1, 0));
        assertEquals(9, HomeMediaRules.liveGridNeighbor(4, 12, 5, 0, 1));
        assertEquals(11, HomeMediaRules.liveGridNeighbor(9, 12, 5, 0, 1));
        assertEquals(6, HomeMediaRules.liveGridNeighbor(11, 12, 5, 0, -1));
        assertEquals(11, HomeMediaRules.liveGridNeighbor(11, 12, 5, 1, 0));
        assertEquals(10, HomeMediaRules.liveGridNeighbor(10, 12, 5, 0, 1));
        assertEquals(0, HomeMediaRules.liveGridNeighbor(0, 1, 5, 0, 1));
    }

    @Test public void liveGridLabelsKeepQualityWithoutCountryOrHdr() {
        assertEquals("LIGUE 1+ UHD", HomeMediaRules.liveChannelDisplayName("FR | LIGUE 1+ UHD"));
        assertEquals("LIGUE 1+ FHD", HomeMediaRules.liveChannelDisplayName("FR | LIGUE 1+ FHD"));
        assertEquals("CANAL+ FOOT UHD", HomeMediaRules.liveChannelDisplayName("FR | CANAL+ FOOT UHD HDR"));
        assertEquals("CANAL+ SPORT FHD", HomeMediaRules.liveChannelDisplayName("fr: Canal+ Sport fhd hevc"));
        assertEquals("BEIN SPORTS 1", HomeMediaRules.liveChannelDisplayName("FR | BEIN SPORTS 1 HD"));
        assertEquals("CANAL+ UHD", HomeMediaRules.liveChannelDisplayName("FR | CANAL+ 4K HDR"));
        assertEquals("", HomeMediaRules.liveChannelDisplayName(null));
        assertEquals("CANAL+ FOOT", HomeMediaRules.channelName("FR | CANAL+ FOOT UHD HDR"));
        assertEquals(285, HomeMediaRules.networkId("FR | CANAL+ UHD HDR"));
    }

    @Test public void seriesStatusBadgeUsesKnownLifecycleStates() {
        for (String status : Arrays.asList("Returning Series", "In Production", "Planned", "Pilot", "En cours")) {
            assertEquals("EN COURS", HomeMediaRules.seriesStatusLabel(status, false));
        }
        for (String status : Arrays.asList("Ended", "Canceled", "Cancelled", "Terminé", " ended ")) {
            assertEquals("TERMINÉ", HomeMediaRules.seriesStatusLabel(status, true));
        }
        assertEquals("EN COURS", HomeMediaRules.seriesStatusLabel("", true));
        for (String status : Arrays.asList(null, "", "unknown", "Released", "0")) {
            assertEquals("", HomeMediaRules.seriesStatusLabel(status, false));
        }
    }

    @Test public void seriesStatusBadgeShowsTotalSeasonsAndOneOngoingSeason() {
        assertEquals("2 SAISONS  │  1 EN COURS", HomeMediaRules.seriesStatusBadgeText("EN COURS", 2));
        assertEquals("4 SAISONS  │  TERMINÉ", HomeMediaRules.seriesStatusBadgeText("TERMINÉ", 4));
        assertEquals("1 SAISON  │  TERMINÉ", HomeMediaRules.seriesStatusBadgeText("TERMINÉ", 1));
        assertEquals("1 SAISON  │  1 EN COURS", HomeMediaRules.seriesStatusBadgeText("EN COURS", 1));
        assertEquals("│  1 EN COURS", HomeMediaRules.seriesStatusBadgeText("EN COURS", 0));
        assertEquals("│  TERMINÉ", HomeMediaRules.seriesStatusBadgeText("TERMINÉ", -1));
        assertEquals("", HomeMediaRules.seriesStatusBadgeText("", 2));
        assertEquals("", HomeMediaRules.seriesStatusBadgeText(null, 2));
    }

    @Test public void seasonStatusUsesTheCompleteBroadcastCalendar() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 6);
        assertEquals("TERMINÉ", HomeMediaRules.seasonStatusLabel(2,
                Arrays.asList("2026-09-22", "2026-09-29"), today));
        assertEquals("EN COURS", HomeMediaRules.seasonStatusLabel(2,
                Arrays.asList("2026-09-29", "2026-10-13"), today));
        assertEquals("À VENIR", HomeMediaRules.seasonStatusLabel(2,
                Arrays.asList("2026-10-13", "2026-10-20"), today));
        assertEquals("TERMINÉ", HomeMediaRules.seasonStatusLabel(2,
                Arrays.asList("2026-09-29", "2026-10-06"), today));
        assertEquals("EN COURS", HomeMediaRules.seasonStatusLabel(2,
                Arrays.asList("2026-09-29", "2026-10-06"), today.minusDays(1)));
    }

    @Test public void incompleteSeasonCalendarDoesNotInventAStatus() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 6);
        for (String missing : Arrays.asList(null, "", "null", "2026-02-30")) {
            assertEquals("", HomeMediaRules.seasonStatusLabel(2,
                    Arrays.asList("2026-09-29", missing), today));
        }
        assertEquals("", HomeMediaRules.seasonStatusLabel(3,
                Arrays.asList("2026-09-22", "2026-09-29"), today));
        assertEquals("", HomeMediaRules.seasonStatusLabel(1,
                Arrays.asList("2026-09-22", "2026-09-29"), today));
        assertEquals("", HomeMediaRules.seasonStatusLabel(0, java.util.Collections.emptyList(), today));
        assertEquals("", HomeMediaRules.seasonStatusLabel(2, null, today));
    }

    @Test public void announcedEpisodeUsesItsDateUntilLocalBroadcastDay() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 4);
        assertEquals("Diffusion le 05/10/2026", HomeMediaRules.seriesEpisodeLabel(2, 3, "2026-10-05", today));
        assertEquals("S02E03", HomeMediaRules.seriesEpisodeLabel(2, 3, "2026-10-05", today.plusDays(1)));
        assertEquals("S02E03", HomeMediaRules.seriesEpisodeLabel(2, 3, "2026-10-05", today.plusDays(2)));
        assertEquals("Diffusion le 01/01/2027", HomeMediaRules.seriesEpisodeLabel(3, 1, "2027-01-01", today));
    }

    @Test public void unknownOrInvalidEpisodeDatesKeepSeasonAndEpisode() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 4);
        for (String date : Arrays.asList(null, "", "null", "2026-02-30", "2026-99-01")) {
            assertEquals("S01E04", HomeMediaRules.seriesEpisodeLabel(1, 4, date, today));
        }
        assertEquals("Diffusion le 05/10/2026", HomeMediaRules.seriesEpisodeLabel(1, 4, "2026-10-05T20:00:00Z", today));
    }

    @Test public void upcomingEpisodeDateBadgeExpiresOnItsLocalBroadcastDay() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 20);
        assertEquals("26/10/2026", HomeMediaRules.seriesEpisodeDateBadge("2026-10-26", today));
        assertEquals("01/01/2027", HomeMediaRules.seriesEpisodeDateBadge("2027-01-01T20:00:00Z", today));
        assertEquals("", HomeMediaRules.seriesEpisodeDateBadge("2026-10-20", today));
        assertEquals("", HomeMediaRules.seriesEpisodeDateBadge("2026-10-19", today));
        for (String date : Arrays.asList(null, "", "null", "2026-02-30", "2026-99-01")) {
            assertEquals("", HomeMediaRules.seriesEpisodeDateBadge(date, today));
        }
    }

    @Test public void onlyOngoingSeriesCanAddAnnouncedEpisodes() {
        org.junit.Assert.assertTrue(HomeMediaRules.ongoingSeries("Returning Series", false));
        org.junit.Assert.assertTrue(HomeMediaRules.ongoingSeries("In Production", true));
        org.junit.Assert.assertFalse(HomeMediaRules.ongoingSeries("Ended", true));
        org.junit.Assert.assertFalse(HomeMediaRules.ongoingSeries("Canceled", false));
        org.junit.Assert.assertFalse(HomeMediaRules.ongoingSeries("", false));
    }

    @Test public void announcedEpisodeRemainsVisibleWhileWaitingForProvider() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 4);
        org.junit.Assert.assertTrue(HomeMediaRules.announcedSeriesEpisode(1, 5, "2026-10-05", 1, 4, today));
        org.junit.Assert.assertTrue(HomeMediaRules.announcedSeriesEpisode(1, 5, "2026-10-05", 1, 4, today.plusDays(2)));
        org.junit.Assert.assertTrue(HomeMediaRules.announcedSeriesEpisode(2, 1, "2026-10-03", 1, 8, today));
        org.junit.Assert.assertFalse(HomeMediaRules.announcedSeriesEpisode(1, 2, "2026-10-01", 1, 4, today));
        org.junit.Assert.assertFalse(HomeMediaRules.announcedSeriesEpisode(1, 5, "", 1, 4, today));
        org.junit.Assert.assertFalse(HomeMediaRules.announcedSeriesEpisode(0, 1, "2026-10-05", 1, 4, today));
        org.junit.Assert.assertFalse(HomeMediaRules.announcedSeriesEpisode(1, 2, "2026-10-01", 0, 0, today));
    }

    @Test public void aNewerEmptySnapshotCannotUndoIndividualVideoDismissals() {
        Set<String> hidden = HomeMediaRules.mergeResumeDismissals(
                new HashSet<>(Arrays.asList("video-a", "video-b")), Collections.emptySet(),
                Collections.singletonMap("video-a", 200L), Collections.emptyMap());
        assertEquals(new HashSet<>(Arrays.asList("video-a", "video-b")), hidden);
    }

    @Test public void twoOfflineDevicesRetainBothDismissalsInEitherMergeDirection() {
        Set<String> first = Collections.singleton("video-a"), second = Collections.singleton("video-b");
        Set<String> expected = new HashSet<>(Arrays.asList("video-a", "video-b"));
        assertEquals(expected, HomeMediaRules.mergeResumeDismissals(first, second, Collections.emptyMap(), Collections.emptyMap()));
        assertEquals(expected, HomeMediaRules.mergeResumeDismissals(second, first, Collections.emptyMap(), Collections.emptyMap()));
    }

    @Test public void onlyPlaybackAfterRemovalCanReinstateThatVideo() {
        Map<String, Long> removals = new HashMap<>(), replays = new HashMap<>();
        removals.put("video-a", 200L); removals.put("video-b", 300L);
        replays.put("video-a", 201L); replays.put("video-b", 299L);
        assertEquals(Collections.singleton("video-b"), HomeMediaRules.mergeResumeDismissals(
                new HashSet<>(Arrays.asList("video-a", "video-b")), Collections.emptySet(), removals, replays));
    }

    @Test public void restoringDeviceOrdersKeepsTheLocalOrderAndTheOtherTvsBackup() {
        Map<String, String> local = Collections.singletonMap("device-firestick", "app-b|app-a");
        Map<String, String> remote = new HashMap<>();
        remote.put("device-firestick", "app-a|app-b"); remote.put("device-android", "app-c|app-a");
        Map<String, String> merged = HomeMediaRules.mergeDeviceAppOrders(local, remote);
        assertEquals("app-b|app-a", merged.get("device-firestick"));
        assertEquals("app-c|app-a", merged.get("device-android"));
        assertEquals(remote, HomeMediaRules.mergeDeviceAppOrders(Collections.emptyMap(), remote));
    }

    @Test public void downDoesNotMistakeAScaledCardsNeighbourForALowerRow() {
        assertFalse(HomeMediaRules.isLowerFocusRow(398, 244, 400, 244));
        assertFalse(HomeMediaRules.isLowerFocusRow(400, 244, 398, 244));
        assertFalse(HomeMediaRules.isLowerFocusRow(400, 84, 402, 244));
        assertFalse(HomeMediaRules.isLowerFocusRow(400, 244, 402, 84));
        assertTrue(HomeMediaRules.isLowerFocusRow(400, 244, 660, 244));
        assertTrue(HomeMediaRules.isLowerFocusRow(400, 84, 490, 84));
        assertFalse(HomeMediaRules.isLowerFocusRow(660, 244, 400, 244));
    }

    @Test public void grandConcoursHomonymousShortFilmCannotDescribeALongRecording() {
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "2025",
                105, 2, false, false, false));
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "2025",
                105, 0, false, false, false));
    }

    @Test public void chosenProgrammeImageCannotBeOverruledByAnExactTitleAndYear() {
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "2025",
                105, 105, true, false, false));
        assertFalse(HomeMediaRules.freeboxMetadataMatches("tv", "2025", "2003",
                105, 105, true, false, false));
    }

    @Test public void aSelectedShowKeepsItsIdentityAcrossAiringYearsAndVariableEpisodeLengths() {
        assertTrue(HomeMediaRules.freeboxMetadataMatches("tv", "2025", "2003",
                105, 120, true, true, true));
        assertTrue(HomeMediaRules.freeboxMetadataMatches("tv", "2025", "2002",
                98, 120, true, true, false));
    }

    @Test public void aSavedShowIdCannotOverrideAConflictingChosenImage() {
        assertFalse(HomeMediaRules.freeboxMetadataMatches("tv", "2025", "2025",
                105, 105, true, false, true));
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "2025",
                105, 105, true, false, true));
    }

    @Test public void grandConcoursDoesNotAutomaticallyMatchGrandConcoursDeMiniatures() {
        assertFalse(HomeMediaRules.freeboxMetadataTitleMatches(
                "le grand concours", "le grand concours de miniatures"));
        assertFalse(HomeMediaRules.freeboxMetadataTitleMatches(
                "le grand concours de miniatures", "le grand concours"));
        assertTrue(HomeMediaRules.freeboxMetadataTitleMatches("le grand concours", "le grand concours"));
        assertFalse(HomeMediaRules.freeboxMetadataTitleMatches("", ""));
    }

    @Test public void movieYearConflictsAndFutureShowsAreRejected() {
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "2024",
                105, 105, true, true, true));
        assertFalse(HomeMediaRules.freeboxMetadataMatches("tv", "2025", "2026",
                105, 105, true, true, false));
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "",
                105, 105, false, false, false));
    }

    @Test public void alternatePostersMatchAcrossTmdbSizesAndUrlParameters() {
        List<String> paths = Arrays.asList("/main.jpg", "/alternate.jpg", "");
        assertTrue(HomeMediaRules.artworkMatches(
                "https://image.tmdb.org/t/p/w500/alternate.jpg?width=300", paths));
        assertFalse(HomeMediaRules.artworkMatches(
                "https://image.tmdb.org/t/p/original/unrelated.jpg", paths));
        assertFalse(HomeMediaRules.artworkMatches("", paths));
    }

    @Test public void titleOnlyMovieMatchesWithoutYearDurationOrChosenIdentityAreRejected() {
        assertFalse(HomeMediaRules.freeboxMetadataMatches("movie", "", "2025",
                0, 2, false, false, false));
        assertTrue(HomeMediaRules.freeboxMetadataMatches("movie", "2025", "2025",
                105, 106, false, false, false));
        assertEquals("2025", HomeMediaRules.titleYear("Le Grand Concours - 2025-09-26.ts"));
        assertEquals("", HomeMediaRules.titleYear("Enregistrement 120250.ts"));
        assertEquals(105, HomeMediaRules.heroRuntimeMinutes("1h45"));
    }

    @Test public void rapidThumbnailFocusRendersOnlyTheLatestSelectionInTheNextFrame() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        List<Integer> rendered = new ArrayList<>();
        int[] selection = { 1 };
        HomeMediaRules.FocusUpdate update = new HomeMediaRules.FocusUpdate(
                () -> true, () -> rendered.add(selection[0]), frames::add);
        update.run();
        selection[0] = 2;
        update.run();
        selection[0] = 3;
        update.run();
        assertTrue(rendered.isEmpty());
        assertEquals(1, frames.size());
        frames.remove().run();
        assertEquals(Collections.singletonList(3), rendered);
    }

    @Test public void leavingTheFolderDropsItsQueuedHeroUpdate() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        boolean[] visible = { true };
        int[] rendered = { 0 };
        HomeMediaRules.FocusUpdate update = new HomeMediaRules.FocusUpdate(
                () -> visible[0], () -> rendered[0]++, frames::add);
        update.run();
        visible[0] = false;
        frames.remove().run();
        assertEquals(0, rendered[0]);
        visible[0] = true;
        update.run();
        frames.remove().run();
        assertEquals(1, rendered[0]);
    }

    @Test public void completedFocusUpdateAcceptsANewSelectionOnTheNextFrame() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        List<Integer> rendered = new ArrayList<>();
        int[] selection = { 1 };
        HomeMediaRules.FocusUpdate update = new HomeMediaRules.FocusUpdate(
                () -> true, () -> rendered.add(selection[0]), frames::add);
        update.run();
        frames.remove().run();
        selection[0] = 2;
        update.run();
        frames.remove().run();
        assertEquals(Arrays.asList(1, 2), rendered);
    }

    @Test public void flushingFocusBeforeAnActionDoesNotRenderTwiceWhenTheFrameArrives() {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        int[] rendered = { 0 };
        HomeMediaRules.FocusUpdate update = new HomeMediaRules.FocusUpdate(
                () -> true, () -> rendered[0]++, frames::add);
        update.run();
        update.flush();
        frames.remove().run();
        assertEquals(1, rendered[0]);
    }

    @Test public void largeFoldersShowFirstCardsBeforeSchedulingTheRestWithoutLosingFiles() {
        List<Integer> rendered = new ArrayList<>();
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        HomeMediaRules.renderInBatches(101, () -> true, rendered::add, frames::add, () -> {}, () -> 0L);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5), rendered);
        assertEquals(1, frames.size());
        while (!frames.isEmpty()) frames.remove().run();
        assertEquals(101, rendered.size());
        for (int index = 0; index < 101; index++) assertEquals(index, rendered.get(index).intValue());
    }

    @Test public void expensiveCardsYieldAtTheFrameBudgetInsteadOfBlockingTheWholeFolder() {
        List<Integer> rendered = new ArrayList<>();
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        long[] clock = { 0L };
        HomeMediaRules.renderInBatches(20, () -> true, index -> {
            rendered.add(index);
            clock[0] += 4_000_000L;
        }, frames::add, () -> {}, () -> clock[0]);
        assertEquals(Arrays.asList(0, 1), rendered);
        assertEquals(1, frames.size());
        while (!frames.isEmpty()) frames.remove().run();
        assertEquals(20, rendered.size());
    }

    @Test public void leavingOrRefreshingAFolderCancelsItsPendingBatches() {
        List<Integer> rendered = new ArrayList<>();
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        boolean[] current = { true };
        int[] applied = { 0 };
        HomeMediaRules.renderInBatches(20, () -> current[0], rendered::add, frames::add,
                () -> applied[0]++, () -> 0L);
        current[0] = false;
        frames.remove().run();
        assertEquals(6, rendered.size());
        assertEquals(1, applied[0]);
        assertTrue(frames.isEmpty());
    }

    @Test public void anAuthoritativeEmptyFolderStillPublishesItsEmptyState() {
        int[] applied = { 0 };
        HomeMediaRules.renderInBatches(0, () -> true, index -> fail("Unexpected file"),
                task -> fail("Unexpected frame"), () -> applied[0]++, () -> 0L);
        assertEquals(1, applied[0]);
    }

    @Test public void recordingsAreNumberedNaturallyIncludingFirstUnnumberedFile() {
        List<String> titles = new ArrayList<>(Arrays.asList("Grands reportages 2.ts", "Grands reportages 4.ts", "Grands reportages 3.ts", "Grands reportages.ts", "Grands reportages 10.ts"));
        titles.sort(HomeMediaRules::compareEpisodes);
        assertEquals(Arrays.asList("Grands reportages.ts", "Grands reportages 2.ts", "Grands reportages 3.ts", "Grands reportages 4.ts", "Grands reportages 10.ts"), titles);
    }
    @Test public void seasonAndEpisodeAreNumericAcrossNamingFormats() {
        assertTrue(HomeMediaRules.compareEpisodes("Show.S01E02.mkv", "Show.S01E10.mkv") < 0);
        assertTrue(HomeMediaRules.compareEpisodes("Show_1x10.mkv", "Show_S02E01.mkv") < 0);
        assertTrue(HomeMediaRules.compareEpisodes("Épisode 2.ts", "Épisode 10.ts") < 0);
        assertTrue(HomeMediaRules.compareEpisodes("Show S01E01.ts", "Bonus.ts") < 0);
    }
    @Test public void comparatorIsTransitiveForMixedNames() {
        List<String> values = Arrays.asList("Bonus", "A S02E01", "Z S01E01", "Grands reportages 2", "Grands reportages", "3", "10");
        for (String a : values) for (String b : values) for (String c : values) {
            if (HomeMediaRules.compareEpisodes(a,b) <= 0 && HomeMediaRules.compareEpisodes(b,c) <= 0)
                assertTrue(HomeMediaRules.compareEpisodes(a,c) <= 0);
        }
    }
    @Test public void exactChannelNamesDoNotMatchRelatedProgrammesOrChannels() {
        assertEquals(290, HomeMediaRules.networkId("FR | TF1 FHD"));
        assertEquals(361, HomeMediaRules.networkId("FR | FRANCE 2 UHD"));
        assertEquals(712, HomeMediaRules.networkId("FR | M6 HD"));
        assertEquals(285, HomeMediaRules.networkId("FR | CANAL+ 4K"));
        assertEquals(0, HomeMediaRules.networkId("TF1 Séries Films"));
        assertEquals(0, HomeMediaRules.networkId("CANAL+ SPORT"));
        assertEquals("LIGUE 1+", HomeMediaRules.channelName("FR | LIGUE 1+ FHD"));
        assertEquals("LIGUE 1+", HomeMediaRules.channelName("FR | LIGUE 1+ UHD"));
        assertFalse(HomeMediaRules.liveTitle("Bonjour ! La matinale TF1"));
    }
}
