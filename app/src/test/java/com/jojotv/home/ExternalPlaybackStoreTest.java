package com.jojotv.home;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExternalPlaybackStoreTest {
    @Test public void failedStartDurationRemainsMillisecondsInPlayerMetadata() throws Exception {
        org.json.JSONObject record = new org.json.JSONObject().put("duration", 3_174_900L)
                .put("position", 0L).put("started", false).put("runtimeMinutes", 52_915);
        assertEquals(Integer.valueOf(52),
                com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt.providerRuntimeMinutes(record));
    }

    @Test public void actualPlaybackDurationOverridesAnEstimatedProviderRuntime() throws Exception {
        org.json.JSONObject record = new org.json.JSONObject().put("duration", 3_174_900L)
                .put("runtimeMinutes", 60);
        assertEquals(Integer.valueOf(52),
                com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt.providerRuntimeMinutes(record));
    }

    @Test public void providerRuntimeFallbackKeepsItsOriginalUnits() throws Exception {
        assertEquals(Integer.valueOf(53),
                com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt.providerRuntimeMinutes(
                        new org.json.JSONObject().put("runtimeMinutes", 53)));
        assertEquals(Integer.valueOf(52),
                com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt.providerRuntimeMinutes(
                        new org.json.JSONObject().put("providerRuntime", "00:52:54")));
        assertEquals(Integer.valueOf(52),
                com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt.providerRuntimeMinutes(
                        new org.json.JSONObject().put("providerRuntime", 3174)));
        assertNull(com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMetadataKt.providerRuntimeMinutes(
                new org.json.JSONObject().put("duration", 0L)));
    }

    @Test public void providerEnrichmentDoesNotOverwriteThePlaybackDuration() throws Exception {
        org.json.JSONObject record = new org.json.JSONObject().put("duration", 3_174_900L);
        org.json.JSONObject metadata = new org.json.JSONObject().put("duration", "00:52:54");
        java.lang.reflect.Method copyMetadata = TiviMateFavoriteImporter.class.getDeclaredMethod(
                "copyProviderMetadata", org.json.JSONObject.class, org.json.JSONObject.class,
                org.json.JSONObject.class, org.json.JSONObject.class, String.class);
        copyMetadata.setAccessible(true);
        copyMetadata.invoke(null, record, metadata, null, null, "Furious");
        assertEquals(3_174_900L, record.getLong("duration"));
        assertEquals("00:52:54", record.getString("providerRuntime"));
        assertEquals(52, record.getInt("runtimeMinutes"));
    }

    @Test public void newerDeletionWinsOverAnOlderPlaybackSnapshot() {
        assertTrue(ExternalPlaybackStore.compareRevisions(200, "tv-a", 100, "tv-b") > 0);
        assertTrue(ExternalPlaybackStore.compareRevisions(100, "tv-b", 200, "tv-a") < 0);
    }

    @Test public void laterExplicitPlaybackCanSupersedeDeletionOnAnotherDevice() {
        assertTrue(ExternalPlaybackStore.compareRevisions(201, "tv-b", 200, "tv-a") > 0);
    }

    @Test public void concurrentRevisionsConvergeRegardlessOfMergeDirection() {
        int forward = ExternalPlaybackStore.compareRevisions(200, "tv-a", 200, "tv-b");
        int reverse = ExternalPlaybackStore.compareRevisions(200, "tv-b", 200, "tv-a");
        assertTrue(forward < 0);
        assertTrue(reverse > 0);
        assertEquals(0, ExternalPlaybackStore.compareRevisions(200, "tv-a", 200, "tv-a"));
        assertEquals(0, ExternalPlaybackStore.compareRevisions(0, null, 0, ""));
    }
    @Test public void exactProviderMatchPrefersTheVisibleQualityVariant() throws Exception {
        org.json.JSONArray variants = new org.json.JSONArray()
                .put(new org.json.JSONObject().put("id", "standard")
                        .put("name", "Slow Horses (2022)").put("year", "2022-04-01"))
                .put(new org.json.JSONObject().put("id", "dolby-vision")
                        .put("name", "Slow Horses (2022) (4K DV)").put("year", "2022-04-01"));
        assertEquals("dolby-vision",
                TiviMateFavoriteImporter.exact(variants, "Slow Horses (2022) (4K DV)").getString("id"));
        assertNull(TiviMateFavoriteImporter.exact(variants, "Slow Horses"));
    }

    @Test public void stalkerStreamCommandDoesNotExecuteShellSyntax() {
        assertEquals("https://example.invalid/live", TiviMateFavoriteImporter.streamCommand("ffmpeg https://example.invalid/live"));
        assertEquals("", TiviMateFavoriteImporter.streamCommand(null));
        assertFalse(ExternalPlaybackStore.supportedUrl(TiviMateFavoriteImporter.streamCommand("ffmpeg https://example.invalid/live -flag")));
        assertFalse(ExternalPlaybackStore.supportedUrl(TiviMateFavoriteImporter.streamCommand("ffmpeg file:///private")));
    }

    @Test public void stalkerPlaybackUrlProvidesConnectionForMetadataLookup() {
        String url = "https://portal.example/play/movie.php?mac=00%3A1A%3A79%3A12%3A34%3A56&stream=42&play_token=x&type=movie";

        assertEquals("00:1A:79:12:34:56", TiviMateFavoriteImporter.stalkerMacFromPlaybackUrl(url));
        assertEquals("https://portal.example/", TiviMateFavoriteImporter.stalkerPortalFromPlaybackUrl(url));
        assertEquals("https://portal.example/server/load.php",
                TiviMateFavoriteImporter.stalkerEndpoints("", url).get(0));
    }

    @Test public void onlyLiveUsesTheLivePlayerContract() {
        assertEquals("iptv", ExternalPlaybackStore.playerContentType("live"));
        assertEquals("home-external", ExternalPlaybackStore.playerContentType("movies"));
        assertEquals("home-external", ExternalPlaybackStore.playerContentType("series"));
        assertEquals("home-external", ExternalPlaybackStore.playerContentType(null));
    }

    @Test public void playerTitleRemovesProviderNoiseAndFormatsEpisodeCode() {
        assertEquals("Prisoner S01E05",
                ExternalPlaybackStore.playerDisplayTitle("Prisoner (2026) (4K HDR10) S1 E5", 0, 0));
        assertEquals("Prisoner S01E06",
                ExternalPlaybackStore.playerDisplayTitle("Prisoner (2026) (4K HDR10)", 1, 6));
        assertEquals("Prisoner",
                ExternalPlaybackStore.playerDisplayTitle("Prisoner (2026) (4K HDR10)", 0, 0));
    }

    @Test public void markingUnwatchedKeepsFavoriteButRemovesResume() {
        assertTrue(ExternalPlaybackStore.visibleInRow(false, true, false, true));
        assertFalse(ExternalPlaybackStore.visibleInRow(false, true, false, false));
        assertTrue(ExternalPlaybackStore.visibleInRow(true, false, false, false));
        assertFalse(ExternalPlaybackStore.visibleInRow(true, false, false, true));
    }

    @Test public void deletedExternalLiveIsHiddenFromFavoritesAndContinueWatching() {
        // The persisted tombstone is reduced to this public row predicate by
        // listAll(): deletion must win over both favorite and started flags.
        assertFalse(ExternalPlaybackStore.visibleInRow(true, true, true, true));
        assertFalse(ExternalPlaybackStore.visibleInRow(true, true, true, false));
    }
    @Test public void onlyBoundedNetworkUrlsWithHostsAreAccepted() {
        assertTrue(ExternalPlaybackStore.supportedUrl("https://example.invalid/video.mp4?token=opaque"));
        assertTrue(ExternalPlaybackStore.supportedUrl("http://192.168.1.10:8080/live/42"));
        assertFalse(ExternalPlaybackStore.supportedUrl("https:///missing-host"));
        assertFalse(ExternalPlaybackStore.supportedUrl("file:///sdcard/video.mp4"));
        assertFalse(ExternalPlaybackStore.supportedUrl("javascript:alert(1)"));
        assertFalse(ExternalPlaybackStore.supportedUrl(null));
        assertFalse(ExternalPlaybackStore.supportedUrl("https://example.invalid/" + new String(new char[16384]).replace('\0', 'a')));
    }
    @Test public void identityIsStableAndDoesNotExposeTheUrl() {
        String url = "https://example.invalid/movie/account/secret/123.mp4?token=private";
        assertEquals(ExternalPlaybackStore.id(url), ExternalPlaybackStore.id(url));
        assertTrue(ExternalPlaybackStore.id(url).matches("[a-f0-9]{64}"));
        assertFalse(ExternalPlaybackStore.id(url).contains("secret"));
        assertNotEquals(ExternalPlaybackStore.id(url), ExternalPlaybackStore.id(url.replace("123", "124")));
    }

    @Test public void identityPreservesSignificantEncodingAndAccountDifferences() {
        assertNotEquals(ExternalPlaybackStore.id("https://example.invalid/a%2Bb"),
                ExternalPlaybackStore.id("https://example.invalid/a+b"));
        assertNotEquals(ExternalPlaybackStore.id("https://example.invalid/user1/42"),
                ExternalPlaybackStore.id("https://example.invalid/user2/42"));
    }

    @Test public void sameTitleWithDifferentExternalUrlsKeepsSeparatePlaybackIds() {
        String existingTitle = "Même titre";
        String incomingTitle = "Même titre";
        String existingUrl = "https://provider.invalid/create_link?token=old";
        String incomingUrl = "https://provider.invalid/create_link?token=new";

        assertEquals(existingTitle, incomingTitle);
        assertNotEquals(ExternalPlaybackStore.id(existingUrl), ExternalPlaybackStore.id(incomingUrl));
    }

    @Test public void liveFavoriteIdentityRemainsStableAcrossUrlRotation() {
        String firstUrlId = ExternalPlaybackStore.id("https://provider.invalid/create_link?token=old");
        String rotatedUrlId = ExternalPlaybackStore.id("https://provider.invalid/create_link?token=new");
        String channelId = ExternalPlaybackStore.favoriteId("provider-a", "live", "channel-42");

        assertNotEquals(firstUrlId, rotatedUrlId);
        assertEquals(channelId, ExternalPlaybackStore.favoriteId("provider-a", "live", "channel-42"));
        assertNotEquals(channelId, ExternalPlaybackStore.favoriteId("provider-a", "live", "channel-43"));
    }

    @Test public void favoriteIdentityIgnoresRotatingPlaybackUrl() throws Exception {
        org.json.JSONObject first = new org.json.JSONObject()
                .put("favoriteSource", "tivimate-stalker").put("stalkerSource", "provider-a")
                .put("category", "movies").put("stalkerId", "42")
                .put("url", "https://provider.invalid/first-token");
        org.json.JSONObject second = new org.json.JSONObject(first.toString())
                .put("url", "https://provider.invalid/second-token");
        String firstId = ExternalPlaybackStore.expectedStorageId(first);
        assertEquals(firstId, ExternalPlaybackStore.expectedStorageId(second));
        assertTrue(firstId.matches("[a-f0-9]{64}"));
        assertNotEquals(firstId, ExternalPlaybackStore.favoriteId("provider-a", "series", "42"));
        assertNotEquals(firstId, ExternalPlaybackStore.favoriteId("provider-b", "movies", "42"));
    }

    @Test public void stalkerEpisodesHaveIndependentStableResumeIds() throws Exception {
        String first = ExternalPlaybackStore.stalkerEpisodeId("provider-a", "series-7", "episode-1");
        String second = ExternalPlaybackStore.stalkerEpisodeId("provider-a", "series-7", "episode-2");
        assertNotEquals(first, second);
        org.json.JSONObject rotated = new org.json.JSONObject()
                .put("stalkerSource", "provider-a").put("stalkerSeriesId", "series-7")
                .put("stalkerEpisodeId", "episode-1").put("url", "https://provider.invalid/new-token");
        assertEquals(first, ExternalPlaybackStore.expectedStorageId(rotated));
    }

    @Test public void stalkerEpisodeIdentityIncludesSeasonWhenProviderNumbersRestart() throws Exception {
        String first = ExternalPlaybackStore.stalkerEpisodeId("provider-a", "series-7", 1, "1");
        String second = ExternalPlaybackStore.stalkerEpisodeId("provider-a", "series-7", 2, "1");
        assertNotEquals(first, second);
    }

    @Test public void stalkerProviderIdentityIgnoresLocalMacAndKnownEndpointSuffixes() {
        String root = TiviMateFavoriteImporter.providerSourceId("http://provider.invalid:8080/");
        assertEquals(root, TiviMateFavoriteImporter.providerSourceId("http://provider.invalid:8080/c/"));
        assertEquals(root, TiviMateFavoriteImporter.providerSourceId("http://provider.invalid:8080/portal.php"));
        assertEquals(root, TiviMateFavoriteImporter.providerSourceId("http://provider.invalid:8080/server/load.php"));
    }

    @Test public void categoryMustBeExplicitNotGuessedFromDuration() {
        assertTrue(ExternalPlaybackStore.categoryValid("movies"));
        assertTrue(ExternalPlaybackStore.categoryValid("series"));
        assertTrue(ExternalPlaybackStore.categoryValid("live"));
        assertFalse(ExternalPlaybackStore.categoryValid("external"));
        assertFalse(ExternalPlaybackStore.categoryValid(""));
        assertFalse(ExternalPlaybackStore.categoryValid(null));
    }

    @Test public void continueWatchingIdentityIgnoresRotatingVodUrls() {
        assertEquals(
                ExternalPlaybackStore.continueWatchingKey("movies", false, "url-hash-a", "Prisoner", "2026", "", "", 0, 0),
                ExternalPlaybackStore.continueWatchingKey("movies", false, "url-hash-b", "PRISONER", "2026", "", "", 0, 0));
    }

    @Test public void continueWatchingIdentityKeepsDistinctYearsAndLiveChannels() {
        assertNotEquals(
                ExternalPlaybackStore.continueWatchingKey("movies", false, "movie-a", "Le Prisonnier", "1967", "", "", 0, 0),
                ExternalPlaybackStore.continueWatchingKey("movies", false, "movie-b", "Le Prisonnier", "2009", "", "", 0, 0));
        assertNotEquals(
                ExternalPlaybackStore.continueWatchingKey("live", true, "live-a", "France 2", "", "", "", 0, 0),
                ExternalPlaybackStore.continueWatchingKey("live", true, "live-b", "France 2", "", "", "", 0, 0));
    }

    @Test public void continueWatchingIdentitySeparatesProvidersWithTheSameCatalogueId() {
        assertNotEquals(
                ExternalPlaybackStore.continueWatchingKey("movies", false, "url-a", "Prisoner", "2026", "provider-a", "42", 0, 0),
                ExternalPlaybackStore.continueWatchingKey("movies", false, "url-b", "Prisoner", "2026", "provider-b", "42", 0, 0));
        assertEquals(
                ExternalPlaybackStore.continueWatchingKey("series", false, "url-a", "Prisoner", "2026", "provider-a", "42", 1, 2),
                ExternalPlaybackStore.continueWatchingKey("series", false, "url-b", "Prisoner", "2026", "provider-a", "42", 1, 2));
    }

    @Test public void continueWatchingIdentityGroupsEpisodesUnderTheirProviderSeries() throws Exception {
        org.json.JSONObject first = new org.json.JSONObject().put("category", "series")
                .put("id", "first").put("title", "Série").put("stalkerSource", "provider-a")
                .put("stalkerId", "series-7").put("stalkerEpisodeId", "episode-a")
                .put("season", 1).put("episode", 2);
        org.json.JSONObject second = new org.json.JSONObject(first.toString())
                .put("id", "second").put("stalkerEpisodeId", "episode-b");
        assertEquals(ExternalPlaybackStore.continueWatchingKey(first),
                ExternalPlaybackStore.continueWatchingKey(second));
    }

    @Test public void nativeSeriesResumeUpdatesItsEpisodeWithoutMergingOtherProviders() throws Exception {
        org.json.JSONObject first = new org.json.JSONObject().put("category", "series")
                .put("favoriteSource", "jojo-iptv").put("id", "iptv:source:series:7")
                .put("title", "L’arène").put("season", 1).put("episode", 1);
        org.json.JSONObject second = new org.json.JSONObject(first.toString()).put("episode", 2)
                .put("title", "L’arène S01E02");
        assertEquals(ExternalPlaybackStore.continueWatchingKey(first), ExternalPlaybackStore.continueWatchingKey(second));
        assertTrue(ExternalPlaybackStore.sameContinueWatchingItem(first, second));
        org.json.JSONObject other = new org.json.JSONObject(first.toString()).put("id", "iptv:other:series:7");
        assertNotEquals(ExternalPlaybackStore.continueWatchingKey(first), ExternalPlaybackStore.continueWatchingKey(other));
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(first, other));
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(other, first));
    }

    @Test public void replayingFuriousEpisodeGroupsRawAndPlayerTitles() throws Exception {
        org.json.JSONObject original = new org.json.JSONObject().put("category", "series")
                .put("id", "old-route").put("title", "Furious").put("season", 1).put("episode", 1)
                .put("lastPlayedAt", 100).put("year", "2025");
        org.json.JSONObject replay = new org.json.JSONObject(original.toString())
                .put("id", "new-route").put("title", "Furious S01E01").put("lastPlayedAt", 200);
        assertTrue(ExternalPlaybackStore.sameContinueWatchingItem(original, replay));
        assertTrue(ExternalPlaybackStore.sameContinueWatchingItem(replay, original));
        assertTrue(ExternalPlaybackStore.preferForContinueWatching(replay, original));
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(original,
                new org.json.JSONObject(replay.toString()).put("year", "2000")));
    }

    @Test public void seriesIdentityFallsBackFromEmptyParentId() throws Exception {
        org.json.JSONObject original = new org.json.JSONObject().put("category", "series")
                .put("stalkerSource", "provider-a").put("stalkerId", "series-7");
        org.json.JSONObject replay = new org.json.JSONObject(original.toString()).put("stalkerSeriesId", "");
        assertEquals(ExternalPlaybackStore.continueWatchingKey(original),
                ExternalPlaybackStore.continueWatchingKey(replay));
    }

    @Test public void seriesResumesRecognizeSourceAndAccountAliases() throws Exception {
        org.json.JSONObject original = new org.json.JSONObject().put("category", "series")
                .put("stalkerSource", "portal-a").put("stalkerProviderAccount", "account-a")
                .put("stalkerId", "series-7");
        org.json.JSONObject replay = new org.json.JSONObject(original.toString())
                .put("stalkerSource", "account-a").put("stalkerSeriesId", "series-7");
        assertTrue(ExternalPlaybackStore.sameContinueWatchingItem(original, replay));
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(original,
                new org.json.JSONObject(replay.toString()).put("stalkerProviderAccount", "account-b")));
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(original,
                new org.json.JSONObject(replay.toString()).put("stalkerSeriesId", "series-8")));
    }

    @Test public void knownCatalogueIdsAreNotMergedByTitle() throws Exception {
        org.json.JSONObject original = new org.json.JSONObject().put("category", "series")
                .put("title", "Furious").put("stalkerSource", "provider-a").put("stalkerId", "series-7");
        org.json.JSONObject other = new org.json.JSONObject(original.toString()).put("stalkerId", "series-8");
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(original, other));
        other.put("stalkerId", "series-7").put("stalkerSource", "provider-b");
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(original, other));
    }

    @Test public void sameEpisodeLinkGroupsAResumeWithMissingProviderMetadata() throws Exception {
        org.json.JSONObject original = new org.json.JSONObject().put("category", "series")
                .put("id", "old-route").put("title", "Furious S01E01")
                .put("url", "http://example.invalid/episode-1").put("season", 1).put("episode", 1);
        org.json.JSONObject replay = new org.json.JSONObject(original.toString())
                .put("id", "new-route").put("stalkerSource", "provider-a").put("stalkerSeriesId", "series-7");
        assertTrue(ExternalPlaybackStore.sameContinueWatchingItem(original, replay));
        assertFalse(ExternalPlaybackStore.sameContinueWatchingItem(original,
                new org.json.JSONObject(replay.toString()).put("episode", 2)));
    }

    @Test public void continueWatchingPrefersLatestThenRichestProviderRecord() {
        assertTrue(ExternalPlaybackStore.preferForContinueWatching(200L, 0, "latest", 100L, 0, "old"));
        assertFalse(ExternalPlaybackStore.preferForContinueWatching(100L, 0, "old", 200L, 0, "latest"));
        assertTrue(ExternalPlaybackStore.preferForContinueWatching(200L, 2, "rich", 200L, 0, "sparse"));
    }

}
