package com.jojotv.home.iptv

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import com.jojotv.home.SmbConnectionConfig
import com.jojotv.home.OfficialProgrammeLogoResolver
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class IptvParsingTest {
    @Test fun vodSortUsesRatingsAndProviderAdditionDatesWithoutChangingPlaylistOrder() {
        val older = IptvItem("old", "a", "20", ContentType.MOVIE, "Zulu", rating = "9,1", year = "2026", addedAt = 1000)
        val newer = older.copy(id = "new", title = "Alpha", rating = "7.3", year = "1990", addedAt = 2000)
        val unknown = older.copy(id = "unknown", title = "Unknown", rating = "NaN", addedAt = 0)
        val values = listOf(unknown, older, newer)
        val key = IptvOrganization.key("a", ContentType.MOVIE, "group", "channelSort")
        fun sorted(mode: String) = IptvOrganization.sortedItems(values, mapOf(key to mode), emptySet(), "a", ContentType.MOVIE, "group").map { it.id }
        assertEquals(listOf("unknown", "old", "new"), sorted("playlist"))
        assertEquals(listOf("new", "unknown", "old"), sorted("name"))
        assertEquals(listOf("old", "new", "unknown"), sorted("rating"))
        assertEquals(listOf("new", "old", "unknown"), sorted("added"))
    }

    @Test fun favoriteSortIsIndependentForMoviesSeriesAndProviderCatalogues() {
        val movie = IptvOrganization.key("", ContentType.MOVIE, "favorites", "channelSort")
        val series = IptvOrganization.key("", ContentType.SERIES, "favorites", "channelSort")
        val catalogue = IptvOrganization.key("a", ContentType.MOVIE, null, "channelSort")
        val settings = mapOf(movie to "name", series to "rating", catalogue to "added")
        assertEquals("name", IptvOrganization.value(settings, "", ContentType.MOVIE, "favorites", "channelSort", "playlist"))
        assertEquals("rating", IptvOrganization.value(settings, "", ContentType.SERIES, "favorites", "channelSort", "playlist"))
        assertEquals("added", IptvOrganization.value(settings, "a", ContentType.MOVIE, "", "channelSort", "playlist"))
        assertEquals("playlist", IptvOrganization.value(settings, "b", ContentType.MOVIE, "", "channelSort", "playlist"))
    }

    @Test fun providerAdditionDatesSupportSecondsMillisecondsAndTextAndRemainOptionalInOldCaches() {
        assertEquals(1_700_000_000_000L, iptvAddedAt("1700000000"))
        assertEquals(1_700_000_000_000L, iptvAddedAt("1700000000000"))
        assertTrue(iptvAddedAt("2026-10-20 12:00:00") > iptvAddedAt("2026-10-19 12:00:00"))
        assertEquals(0L, iptvAddedAt("")); assertEquals(0L, iptvAddedAt("unknown"))
        val old = """{"id":"old","sourceId":"a","providerId":"1","type":"MOVIE","title":"Old cache"}"""
        assertEquals(0L, iptvJson.decodeFromString<IptvItem>(old).addedAt)
    }

    @Test fun vodGridKeepsDirectionalFocusInsideRowsAndHandlesTheLastPartialRow() {
        assertEquals(4, IptvOrganization.vodGridColumns(600, 138))
        assertEquals(1, IptvOrganization.vodGridColumns(0, 138))
        assertEquals(8, IptvOrganization.vodGridColumns(4000, 138))
        assertEquals(-1, IptvOrganization.vodGridTarget(0, 0, 4, "down"))
        assertEquals(4, IptvOrganization.vodGridTarget(4, 10, 4, "left"))
        assertEquals(3, IptvOrganization.vodGridTarget(3, 10, 4, "right"))
        assertEquals(9, IptvOrganization.vodGridTarget(7, 10, 4, "down"))
        assertEquals(5, IptvOrganization.vodGridTarget(9, 10, 4, "up"))
        assertEquals(9, IptvOrganization.vodGridTarget(9, 10, 4, "right"))
        assertEquals(9, IptvOrganization.vodGridTarget(9, 10, 4, "down"))
    }

    @Test fun vodGridTargetsStayValidAcrossDensitiesSizesAndDirections() {
        for (count in 1..65) for (columns in 1..8) for (index in 0 until count) {
            for (direction in listOf("left", "right", "up", "down")) {
                val target = IptvOrganization.vodGridTarget(index, count, columns, direction)
                assertTrue(target in 0 until count)
                if (direction in listOf("left", "right")) assertEquals(index / columns, target / columns)
                if (direction == "up") assertTrue(target <= index)
                if (direction == "down") assertTrue(target >= index)
            }
        }
    }

    @Test fun officialProgrammeCatalogsRequireAnExactNameAndKeepTheRealTf1Slug() {
        val source = OfficialProgrammeLogoResolver.Source.TF1
        val html = """<a href="/tf1/les-douze-coups-de-midi" title="Les 12 coups de midi"></a>
            <a href="/tf1/les-douze-coups-de-midi-les-meilleurs-moments" title="Les 12 coups de midi : les meilleurs moments"></a>
            <a href="//unrelated.example/les-douze-coups-de-midi" title="Les 12 coups de midi"></a>"""
        val pages = OfficialProgrammeLogoResolver.catalogPages(source, html)
        assertEquals(listOf("https://www.tf1.fr/tf1/les-douze-coups-de-midi"),
            OfficialProgrammeLogoResolver.matchingPages(source, pages, "Les 12 coups de midi"))
        assertTrue(OfficialProgrammeLogoResolver.matchingPages(source, pages, "Les 12 coups").isEmpty())
    }

    @Test fun franceTvProgramSitemapsDoNotConfuseSeasonsOrSimilarProgrammes() {
        val source = OfficialProgrammeLogoResolver.Source.FRANCE_TV
        val xml = """<loc><![CDATA[ https://www.france.tv/sitemaps/program/france-2_fort-boyard.xml ]]></loc>
            <loc>https://www.france.tv/sitemaps/program/france-2_fort-boyard_saison-37.xml</loc>
            <loc>https://www.france.tv/sitemaps/program/france-2_fort-boyard-par-domingo.xml</loc>"""
        assertEquals(listOf("https://www.france.tv/france-2/fort-boyard/"),
            OfficialProgrammeLogoResolver.matchingPages(source,
                OfficialProgrammeLogoResolver.catalogPages(source, xml), "Fort Boyard"))
    }

    @Test fun m6LogoIsTakenFromTheMatchedProgrammeHeroRatherThanTheBroadcaster() {
        val source = OfficialProgrammeLogoResolver.Source.M6
        val page = "https://www.m6.fr/ya-que-la-verite-qui-compte-p_27340"
        val redux = """{"layout":{"layouts":{"main":{"program":{"27340":{"blocks":[{"content":{"items":[
            {"itemContent":{"logo":{"id":"4697204","caption":"logo_YAQLAVQC.png"}}}]}}]}}}}},
            "brand":{"logo":{"id":"4270343","caption":"M6+"}}}"""
        val state = buildJsonObject { put("__reduxState", redux) }
        val html = """<script type="application/ld+json">{"item":{"@id":"$page","name":"Y'a que la vérité qui compte"}}</script>
            <script>(function(root){root.__dehydratedState = $state;})(window);</script>"""
        assertEquals("https://images-fio.6play.fr/v2/images/4697204/raw",
            OfficialProgrammeLogoResolver.logoFromPage(source, page, html, "Y’a que la vérité qui compte"))
        assertEquals("", OfficialProgrammeLogoResolver.logoFromPage(source, page, html, "Un autre programme"))
        assertEquals("", OfficialProgrammeLogoResolver.logoFromPage(source,
            "https://www.m6.fr/autre-p_42", html, "Y’a que la vérité qui compte"))
    }

    @Test fun tf1TitleLogoIgnoresTheNavigationPromotionAndUsesTheLargestTransparentFormat() {
        val source = OfficialProgrammeLogoResolver.Source.TF1
        val program = """{"name":"Koh-Lanta","slug":"koh-lanta","decoration":{"logo":{"id":"logo-programme-test",
            "sourcesWithScales":[{"width":"220","scale":1,"type":"png","url":"https://photos.tf1.fr/logo-small.png"},
            {"width":"220","scale":3,"type":"png","url":"https://photos.tf1.fr/logo-large.png"},
            {"width":"220","scale":3,"type":"avif","url":"https://photos.tf1.fr/logo.avif"}]}}}"""
        val data = "20:[{\"name\":\"Star Academy\",\"slug\":\"star-academy\"},$program]\n".replace("\n", " ") + "\n"
        val chunk = buildJsonArray { add(1); add(data) }
        val html = "<script>self.__next_f.push($chunk)</script>"
        assertEquals("https://photos.tf1.fr/logo-large.png", OfficialProgrammeLogoResolver.logoFromPage(
            source, "https://www.tf1.fr/tf1/koh-lanta", html, "Koh-Lanta"))
        assertEquals("", OfficialProgrammeLogoResolver.logoFromPage(source,
            "https://www.tf1.fr/tf1/autre", html, "Koh-Lanta"))
    }

    @Test fun franceTvTitleImageMustBelongToTheProgrammeHeader() {
        val source = OfficialProgrammeLogoResolver.Source.FRANCE_TV
        val page = "https://www.france.tv/france-2/fort-boyard/"
        val html = """<img alt="France TV" src="https://www.france.tv/logos/france-tv.svg"/>
            <img alt="Fort Boyard" src="https://medias.france.tv/backdrop.jpg"/>
            <img aria-hidden="true" alt="Fort Boyard " src="https://medias.france.tv/logo.png"
                srcSet="https://medias.france.tv/logo-large.png 2x"/><h1>Fort Boyard</h1>
            <img aria-hidden="true" alt="Fort Boyard" src="https://medias.france.tv/episode.png"/>"""
        assertEquals("https://medias.france.tv/logo-large.png",
            OfficialProgrammeLogoResolver.logoFromPage(source, page, html, "Fort Boyard"))
        assertEquals("", OfficialProgrammeLogoResolver.logoFromPage(source, page, html, "Fort Boyard Junior"))
    }

    @Test fun officialFallbackPreservesProductionLanguageAndRejectsNonOfficialUrls() {
        assertTrue(OfficialProgrammeLogoResolver.supportsLanguage("fr-FR"))
        assertFalse(OfficialProgrammeLogoResolver.supportsLanguage("en"))
        assertFalse(OfficialProgrammeLogoResolver.supportsLanguage("ja"))
        assertEquals(OfficialProgrammeLogoResolver.titleKey("Y'a que la vérité qui compte"),
            OfficialProgrammeLogoResolver.titleKey(OfficialProgrammeLogoResolver.programmeTitle(
                "Y’a que la vérité qui compte - 2026-10-14 21h10.mkv")))
        val host = "photos.tf1.fr"
        assertTrue(OfficialProgrammeLogoResolver.officialUrl("https://photos.tf1.fr/logo.png", host))
        for (url in listOf("http://photos.tf1.fr/logo.png", "https://photos.tf1.fr.other.example/logo.png",
            "https://photos.tf1.fr@other.example/logo.png", "https://photos.tf1.fr:8095/logo.png"))
            assertFalse(url, OfficialProgrammeLogoResolver.officialUrl(url, host))
    }

    @Test fun homeAndPlayerChooseTheSameOriginalFrenchLogoForArena() {
        val logos = listOf(
            com.nuvio.tv.data.remote.api.TmdbImage("/english.png", "en"),
            com.nuvio.tv.data.remote.api.TmdbImage("/jrmyqbN4TGJ7q0lWBMzBavjIGxB.png", "fr", "FR")
        )
        val expected = "https://image.tmdb.org/t/p/original/jrmyqbN4TGJ7q0lWBMzBavjIGxB.png"
        assertEquals(expected, com.nuvio.tv.core.tmdb.tmdbLocalizedLogoUrl(logos, "fr"))
        assertEquals(expected, com.jojotv.home.HomeMediaRules.originalTitleLogoUrl(
            logos.reversed().associate { it.filePath!! to it.iso6391.orEmpty() }, "fr"))
        assertEquals("https://image.tmdb.org/t/p/original/english.png",
            com.nuvio.tv.core.tmdb.tmdbLocalizedLogoUrl(logos, "en"))
    }

    @Test fun vodKeepsProviderTitleLogoBeforeCachedAndNewTmdbLogos() {
        val provider = IptvItem("series", "provider", "42", ContentType.SERIES, "WAR",
            titleLogo = "https://provider.example/war-gold.png")
        val replacement = IptvMediaPresentation.Details(42, "tv", logo = "https://tmdb.example/war-hbo.png")
        assertEquals(provider.titleLogo, IptvMediaPresentation.titleLogo(provider, replacement,
            "https://cache.example/other-logo.png"))
        assertEquals(provider.titleLogo, IptvMediaPresentation.titleLogo(provider, null))
    }

    @Test fun vodKeepsCachedTitleLogoAcrossMetadataRefreshes() {
        val item = IptvItem("series", "provider", "42", ContentType.SERIES, "WAR")
        val replacement = IptvMediaPresentation.Details(42, "tv", logo = "https://tmdb.example/war-hbo.png")
        val cached = "https://cache.example/war-gold.png"
        assertEquals(cached, IptvMediaPresentation.titleLogo(item, replacement, cached))
        assertEquals(replacement.logo, IptvMediaPresentation.titleLogo(item, replacement))
        assertEquals("", IptvMediaPresentation.titleLogo(item, null))
    }

    @Test fun vodEditorialOnlyCompletesMissingProviderInformation() {
        val provider = IptvItem("film", "provider", "42", ContentType.MOVIE, "Titre fournisseur",
            command = "provider-command", streamUrl = "https://provider/film", genre = "Genre fournisseur", year = "2026",
            duration = "1:24:00", description = "Synopsis fournisseur", cast = "Acteur fournisseur", director = "Realisateur fournisseur")
        val supplement = IptvMediaPresentation.Details(36, "movie", genre = "Autre genre", year = "2025", runtime = 90,
            description = "Autre synopsis", director = "Autre realisateur",
            cast = listOf(IptvMediaPresentation.Cast(1, "Autre acteur", "Role", "")))
        assertEquals(provider, IptvMediaPresentation.editorial(provider, supplement))
        val sparse = provider.copy(genre = "", year = "", duration = "0", description = "", cast = "", director = "")
        assertEquals(sparse.copy(genre = "Autre genre", year = "2025", duration = "90 min", description = "Autre synopsis",
            cast = "Autre acteur", director = "Autre realisateur"), IptvMediaPresentation.editorial(sparse, supplement))
        assertEquals(sparse, IptvMediaPresentation.editorial(sparse, null))
    }

    @Test fun oldVodPresentationCacheRemainsReadableWithEmptyEditorialFields() {
        val old = iptvJson.decodeFromString<IptvMediaPresentation.Details>("""{"tmdbId":36,"mediaType":"tv","logo":"https://image/logo.png"}""")
        assertEquals(0, old.runtime); assertTrue(old.genre.isEmpty()); assertTrue(old.description.isEmpty())
        assertTrue(old.year.isEmpty()); assertTrue(old.director.isEmpty())
        assertNull(old.broadcastSeason); assertFalse(old.broadcastStatusLoaded)
    }

    @Test fun episodeFocusRetainsTheSeriesFicheButUsesOnlyTheSelectedEpisodeRuntime() {
        val series = IptvItem("series", "provider", "36", ContentType.SERIES, "Serie", duration = "50 min", description = "Synopsis serie")
        val episode = series.copy(id = "episode", type = ContentType.EPISODE, seriesId = "36", season = 1, episode = 2,
            duration = "52 min", description = "Synopsis episode")
        val details = IptvMediaPresentation.Details(42, "tv", runtime = 48,
            episodes = listOf(IptvMediaPresentation.Episode(1, 2, "Episode TMDB", "2026-10-13", "", "", 44)))
        assertEquals(series.copy(duration = "52 min"), IptvMediaPresentation.editorial(series, details, episode))
        assertEquals(series.copy(duration = "44 min"), IptvMediaPresentation.editorial(series, details, episode.copy(duration = "")))
        assertEquals(series.copy(duration = ""), IptvMediaPresentation.editorial(series, null, episode.copy(duration = "")))
        assertEquals(series, IptvMediaPresentation.editorial(series, details, episode.copy(sourceId = "other-provider")))
        assertEquals(series, IptvMediaPresentation.editorial(series, details, episode.copy(seriesId = "another-series")))
    }

    @Test fun vodHeroCreditsUseTheNativeThreeActorSummary() {
        val movie = IptvItem("film", "provider", "42", ContentType.MOVIE, "Film", cast = "A, B, C, D", director = "R")
        assertEquals("Acteurs : A, B, C\u2026\nR\u00e9alisateur : R", IptvMediaPresentation.credits(movie))
        assertEquals("", IptvMediaPresentation.credits(movie.copy(cast = "", director = "")))
    }

    @Test fun vodTitleArtworkMatchesTheExactTitleAndYear() {
        val item = IptvItem("film", "provider", "42", ContentType.MOVIE, "Été (2026) (4K DV)", year = "2026")
        val candidates = iptvJson.parseToJsonElement("""[{"id":1,"title":"Été","release_date":"2021-01-01"},{"id":2,"title":"Ete","release_date":"2026-01-01"}]""").jsonArray
        assertEquals(2, IptvMediaPresentation.match(item, candidates))
        assertNull(IptvMediaPresentation.match(item.copy(title = "Été", year = ""), candidates))
    }

    @Test fun vodTitleArtworkRejectsSimilarTitlesAndAmbiguousMatches() {
        val item = IptvItem("film", "provider", "42", ContentType.MOVIE, "Platini (2026)")
        assertNull(IptvMediaPresentation.match(item, iptvJson.parseToJsonElement("""[{"id":1,"title":"Platini","release_date":"2021-01-01"},{"id":2,"title":"Michel Platini","release_date":"2026-01-01"}]""").jsonArray))
        assertNull(IptvMediaPresentation.match(item, iptvJson.parseToJsonElement("""[{"id":1,"title":"Platini","release_date":"2026-01-01"},{"id":2,"title":"Platini","release_date":"2026-02-01"}]""").jsonArray))
    }

    @Test fun vodHeroRecognizesProviderQualityLabelsAndLocalizedOriginalAliases() {
        assertEquals(listOf("WAR"), IptvMediaPresentation.searchTitles("WAR (2026) (4K SDR)"))
        assertEquals(listOf("American Hostage"), IptvMediaPresentation.searchTitles("American Hostage (2026) (4K SDR)"))
        assertEquals(listOf("Sur écoute", "The Wire"), IptvMediaPresentation.searchTitles("Sur écoute | The Wire (2002)"))
        val candidates = iptvJson.parseToJsonElement("""[
            {"id":1,"name":"War","first_air_date":"2026-04-01"},
            {"id":2,"name":"American Hostage","first_air_date":"2026-05-01"},
            {"id":3,"name":"Sur écoute","original_name":"The Wire","first_air_date":"2002-06-02"}
        ]""").jsonArray
        val item = IptvItem("series", "provider", "42", ContentType.SERIES, "WAR (2026) (4K SDR)")
        assertEquals(1, IptvMediaPresentation.match(item, candidates))
        assertEquals(2, IptvMediaPresentation.match(item.copy(title = "American Hostage (2026) (4K SDR)"), candidates))
        assertEquals(3, IptvMediaPresentation.match(item.copy(title = "Sur écoute | The Wire (2002)"), candidates))
        assertNull(IptvMediaPresentation.match(item.copy(year = "2025"), candidates))
        val ambiguous = iptvJson.parseToJsonElement("""[
            {"id":3,"name":"Sur écoute","first_air_date":"2002-06-02"},
            {"id":4,"name":"The Wire","first_air_date":"2002-01-01"}
        ]""").jsonArray
        assertNull(IptvMediaPresentation.match(item.copy(title = "Sur écoute | The Wire (2002)"), ambiguous))
    }

    @Test fun heroBroadcastSeasonMatchesHomeAndIgnoresSpecialsAndFutureRenewals() {
        val series = iptvJson.parseToJsonElement("""{"last_episode_to_air":{"season_number":2},"seasons":[
            {"season_number":0,"air_date":"2026-10-14","episode_count":1},
            {"season_number":1,"air_date":"2024-01-01","episode_count":8},
            {"season_number":2,"air_date":"2025-01-01","episode_count":8},
            {"season_number":3,"air_date":"2027-01-01","episode_count":8}
        ]}""").jsonObject
        val today = java.time.LocalDate.of(2026, 10, 15)
        assertEquals(series["seasons"]!!.jsonArray[2].jsonObject, IptvMediaPresentation.broadcastSeason(series, today))
        val upcoming = iptvJson.parseToJsonElement("""{"seasons":[
            {"season_number":2,"air_date":"2028-01-01","episode_count":8},
            {"season_number":1,"air_date":"2027-01-01","episode_count":8}
        ]}""").jsonObject
        assertEquals(upcoming["seasons"]!!.jsonArray[1].jsonObject, IptvMediaPresentation.broadcastSeason(upcoming, today))
    }

    @Test fun heroStatusFollowsSeasonAirDatesInsteadOfTheSeriesProductionStatus() {
        val today = java.time.LocalDate.of(2026, 10, 15)
        val details = IptvMediaPresentation.Details(42, "tv", status = "EN COURS", seasonCount = 3,
            broadcastSeason = IptvMediaPresentation.Season(2, 2, listOf("2026-10-01", "2026-10-14")),
            broadcastStatusLoaded = true)
        assertEquals("TERMINÉ", IptvMediaPresentation.seriesBroadcastStatus(details, today))
        assertEquals("3 SAISONS  │  TERMINÉ", com.jojotv.home.HomeMediaRules.seriesStatusBadgeText(
            IptvMediaPresentation.seriesBroadcastStatus(details, today), details.seasonCount))
        val ongoing = details.copy(broadcastSeason = IptvMediaPresentation.Season(2, 2, listOf("2026-10-01", "2026-10-20")))
        assertEquals("EN COURS", IptvMediaPresentation.seriesBroadcastStatus(ongoing, today))
        val future = details.copy(broadcastSeason = IptvMediaPresentation.Season(3, 2, listOf("2027-01-01", "2027-01-08")))
        assertEquals("À VENIR", IptvMediaPresentation.seriesBroadcastStatus(future, today))
        assertEquals("", IptvMediaPresentation.seriesBroadcastStatus(details.copy(
            broadcastSeason = IptvMediaPresentation.Season(2, 2, listOf("2026-10-01"))), today))
        assertEquals("", IptvMediaPresentation.seriesBroadcastStatus(details.copy(broadcastSeason = null), today))
        assertEquals("TERMINÉ", IptvMediaPresentation.seriesBroadcastStatus(details.copy(status = "TERMINÉ", broadcastSeason = null), today))
        val saved = iptvJson.decodeFromString<IptvMediaPresentation.Details>(iptvJson.encodeToString(ongoing))
        assertTrue(saved.broadcastStatusLoaded)
        assertEquals("EN COURS", IptvMediaPresentation.seriesBroadcastStatus(saved, today))
    }

    @Test fun announcedEpisodesPreserveAvailableProviderCommandsAndMetadata() {
        val series = IptvItem("series", "provider", "36", ContentType.SERIES, "Série")
        val available = series.copy(id = "episode", providerId = "91", type = ContentType.EPISODE, title = "Titre fournisseur",
            description = "Synopsis fournisseur", season = 1, episode = 1, seriesId = "36", command = "provider-command", streamUrl = "https://provider/episode")
        val announcements = listOf(
            IptvMediaPresentation.Episode(1, 1, "Titre TMDB", "2026-10-01", "", "Autre synopsis", 40),
            IptvMediaPresentation.Episode(1, 2, "Suivant", "2026-10-20", "", "Synopsis", 42))
        val result = IptvMediaPresentation.mergeEpisodes(series, listOf(available), announcements, java.time.LocalDate.of(2026, 10, 13))
        assertEquals(available.copy(airDate = "2026-10-01"), result[0])
        assertTrue(result[1].scheduledEpisode)
        assertTrue(result[1].command.isBlank()); assertTrue(result[1].streamUrl.isBlank())
        assertNull(IptvStore.nextEpisode(available, result))
    }

    @Test fun providerEpisodesReplaceAnnouncementsWithoutDuplicateCards() {
        val series = IptvItem("series", "provider", "36", ContentType.SERIES, "Série")
        val first = series.copy(id = "first", providerId = "1", type = ContentType.EPISODE, season = 1, episode = 1, seriesId = "36")
        val second = first.copy(id = "second", providerId = "2", episode = 2, streamUrl = "https://provider/second")
        val announcement = IptvMediaPresentation.Episode(1, 2, "Suivant", "2026-10-14", "", "", 40)
        val before = IptvMediaPresentation.mergeEpisodes(series, listOf(first), listOf(announcement), java.time.LocalDate.of(2026, 10, 15))
        assertTrue(before.last().scheduledEpisode)
        val after = IptvMediaPresentation.mergeEpisodes(series, listOf(first, second), listOf(announcement), java.time.LocalDate.of(2026, 10, 15))
        assertEquals(2, after.size); assertFalse(after.last().scheduledEpisode)
        assertEquals(second.copy(airDate = announcement.airDate), IptvStore.nextEpisode(first, after))
    }

    @Test fun announcedEpisodesExcludeInvalidDatesAndOtherProviders() {
        val series = IptvItem("series", "provider", "36", ContentType.SERIES, "Série")
        val other = series.copy(id = "other", sourceId = "another", type = ContentType.EPISODE, season = 1, episode = 1, seriesId = "36")
        val invalid = IptvMediaPresentation.Episode(1, 2, "Suivant", "inconnue", "", "", 40)
        assertTrue(IptvMediaPresentation.mergeEpisodes(series, listOf(other), listOf(invalid)).isEmpty())
    }

    @Test fun oldIptvItemsRemainReadableWithoutPresentationFields() {
        val item = iptvJson.decodeFromString<IptvItem>("""{"id":"film","sourceId":"provider","providerId":"42","type":"MOVIE","title":"Film"}""")
        assertEquals(0, item.tmdbId); assertTrue(item.titleLogo.isEmpty())
        assertFalse(item.scheduledEpisode); assertTrue(item.airDate.isEmpty())
    }

    @Test fun futureEpisodePlaybackIsBlockedUntilTheProviderSuppliesIt() {
        val today = java.time.LocalDate.of(2026, 10, 13)
        val episode = IptvItem("episode", "provider", "42", ContentType.EPISODE, "Épisode", airDate = "2026-10-20")
        assertFalse(IptvMediaPresentation.playable(episode, today))
        assertTrue(IptvMediaPresentation.playable(episode.copy(airDate = "2026-10-13"), today))
        assertFalse(IptvMediaPresentation.playable(episode.copy(airDate = "2026-10-01", scheduledEpisode = true), today))
        assertTrue(IptvMediaPresentation.playable(episode.copy(type = ContentType.MOVIE), today))
    }

    @Test fun nasSyncStreamKeepsTheExistingEnvelopeAndEscapedRecords() {
        val record = IptvSyncRecord(42L, "tv-é", "{\"title\":\"Été 🌍\",\"description\":\"ligne\\n\\\"suite\\\"\"}")
        val snapshot = IptvSyncSnapshot(records = mapOf("profile:a" to record))
        val output = java.io.ByteArrayOutputStream()
        writeIptvNasSyncSnapshot(snapshot, output)
        val envelope = iptvJson.parseToJsonElement(output.toString("UTF-8")).jsonObject
        assertEquals(setOf("value"), envelope.keys)
        assertEquals(iptvJson.parseToJsonElement(iptvJson.encodeToString(snapshot)), envelope["value"])
        val response = "{\"key\":\"jojo-home-iptv\",\"updatedAt\":42,\"value\":${envelope.getValue("value")}}"
        val fragmented = object : java.io.ByteArrayInputStream(response.toByteArray(Charsets.UTF_8)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 7))
        }
        assertEquals(snapshot, readIptvNasSyncSnapshot(fragmented))
    }

    @Test fun nasSyncBodyKeepsAnExactUtf8ContentLength() {
        val snapshot = IptvSyncSnapshot(records = mapOf("profile:a" to
            IptvSyncRecord(42L, "tv-é", "Été 🌍\n\"suite\"")))
        val body = iptvNasSyncBody(snapshot)
        val output = okio.Buffer()
        body.writeTo(output)
        assertEquals("application/json; charset=utf-8", body.contentType().toString())
        assertEquals(output.size, body.contentLength())
        assertEquals(body.contentLength(), body.contentLength())
        assertEquals(snapshot, readIptvNasSyncSnapshot(output.inputStream()))
    }

    @Test fun nasSyncLargeRecordIsWrittenInBoundedChunks() {
        val snapshot = IptvSyncSnapshot(records = mapOf("setting:guide" to
            IptvSyncRecord(42L, "tv", "Émission\\n".repeat(250_000))))
        var written = 0L
        var largestChunk = 0
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { written++; largestChunk = maxOf(largestChunk, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                written += length
                largestChunk = maxOf(largestChunk, length)
            }
        }
        writeIptvNasSyncSnapshot(snapshot, output)
        assertTrue(written > 2_000_000L)
        assertTrue("A large NAS record must not become one output buffer", largestChunk <= 65_536)
    }

    @Test fun nasSyncEmptyAndMalformedResponsesKeepDistinctOutcomes() {
        assertNull(readIptvNasSyncSnapshot("{}".byteInputStream()))
        assertNull(readIptvNasSyncSnapshot("{\"value\":null}".byteInputStream()))
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            readIptvNasSyncSnapshot("{\"value\":{\"records\":".byteInputStream())
        }
    }

    @Test fun remoteBindingsKeepGuideAndPlayerIndependentAndRejectUnavailableActions() {
        val guide = IptvRemote.guide.first { it.id == "ok" }
        val player = IptvRemote.player.first { it.id == "ok" }
        assertNotEquals(guide.setting(false), player.setting(true))
        assertEquals(IptvRemoteAction.WATCH, IptvRemote.action(guide, false, "UNKNOWN"))
        assertEquals(IptvRemoteAction.WATCH, IptvRemote.action(guide, false, "BEGINNING"))
        assertEquals(IptvRemoteAction.INFO_CONTROLS, IptvRemote.action(player, true, "WATCH"))
        assertEquals(IptvRemoteAction.SEARCH, IptvRemote.action(guide, false, "SEARCH"))
        assertTrue(IptvRemote.guide.none { it.keyCode in listOf(19, 20) })
        assertTrue(IptvRemote.defaults(false).keys.all { it.startsWith("remote.guide.") })
        assertTrue(IptvRemote.defaults(false).keys.intersect(IptvRemote.defaults(true).keys).isEmpty())
    }

    @Test fun playerMenuVisibilityKeepsConfiguredOrderAndHiddenDefaults() {
        val values = IptvRemote.visiblePlayerMenu(mapOf("playerMenuOrder" to "[\"history\",\"audio\",\"unknown\",\"audio\"]",
            "playerMenu:history" to "true", "playerMenu:search" to "false"))
        assertEquals(listOf("history", "audio"), values.take(2))
        assertFalse("search" in values)
        assertFalse("movies" in values)
        assertFalse("unknown" in values)
        assertEquals(1, values.count { it == "audio" })
    }

    @Test fun playerMenuUsesDefaultsWhenTheSavedOrderIsMalformed() {
        val values = IptvRemote.visiblePlayerMenu(mapOf("playerMenuOrder" to "invalid"))
        assertEquals(listOf("search", "channels", "record"), values.take(3))
        assertTrue("settings" in values)
        assertFalse("history" in values)
    }

    @Test fun remoteShortPressWaitsForReleaseWhenLongPressIsAssigned() {
        val press = IptvRemotePress()
        assertNull(press.handle(23, true, 100, IptvRemoteAction.WATCH, IptvRemoteAction.MENU).action)
        assertEquals(IptvRemoteAction.WATCH, press.handle(23, false, 200, IptvRemoteAction.WATCH).action)
        assertFalse(press.owns(23))
        assertNull(press.handle(23, false, 201, IptvRemoteAction.WATCH).action)
    }

    @Test fun remoteLongPressFiresOnceAndNeverAlsoRunsItsShortAction() {
        val press = IptvRemotePress()
        press.handle(4, true, 100, IptvRemoteAction.BACK, IptvRemoteAction.STOP)
        assertNull(press.handle(4, true, 400, IptvRemoteAction.BACK, repeat = 1).action)
        assertEquals(IptvRemoteAction.STOP, press.handle(4, true, 600, IptvRemoteAction.BACK, repeat = 2).action)
        assertNull(press.handle(4, true, 900, IptvRemoteAction.BACK, repeat = 3).action)
        assertNull(press.handle(4, false, 1000, IptvRemoteAction.BACK).action)
    }

    @Test fun remoteLongPressWorksWithoutRepeatEventsAndCancelledPressDoesNothing() {
        val press = IptvRemotePress()
        press.handle(23, true, 100, IptvRemoteAction.WATCH, IptvRemoteAction.MENU)
        assertEquals(IptvRemoteAction.MENU, press.handle(23, false, 700, IptvRemoteAction.WATCH).action)
        press.handle(23, true, 1000, IptvRemoteAction.WATCH, IptvRemoteAction.MENU)
        assertNull(press.handle(23, false, 1800, IptvRemoteAction.WATCH, cancelled = true).action)
        assertFalse(press.owns(23))
    }

    @Test fun remoteHoldTimerFiresBeforeReleaseAndCannotAffectANewerPress() {
        val press = IptvRemotePress(600)
        press.handle(23, true, 100, IptvRemoteAction.WATCH, IptvRemoteAction.MENU)
        assertNull(press.hold(23, 100, 650).action)
        assertEquals(IptvRemoteAction.MENU, press.hold(23, 100, 700).action)
        assertNull(press.handle(23, false, 800, IptvRemoteAction.WATCH).action)
        press.handle(23, true, 1000, IptvRemoteAction.WATCH, IptvRemoteAction.MENU)
        assertNull(press.hold(23, 100, 1700).action)
        press.cancel(23, 100)
        assertTrue(press.owns(23))
        press.cancel(23, 1000)
        assertNull(press.handle(23, false, 1800, IptvRemoteAction.WATCH).action)
    }

    @Test fun remotePagingRepeatsButToggleActionsAndKeyUpDoNot() {
        val press = IptvRemotePress()
        assertEquals(IptvRemoteAction.PAGE_DOWN, press.handle(167, true, 100, IptvRemoteAction.PAGE_DOWN).action)
        assertEquals(IptvRemoteAction.PAGE_DOWN, press.handle(167, true, 600, IptvRemoteAction.PAGE_DOWN, repeat = 1).action)
        assertNull(press.handle(167, false, 700, IptvRemoteAction.PAGE_DOWN).action)
        assertEquals(IptvRemoteAction.FAVORITE, press.handle(183, true, 800, IptvRemoteAction.FAVORITE).action)
        assertNull(press.handle(183, true, 1400, IptvRemoteAction.FAVORITE, repeat = 1).action)
        assertNull(press.handle(183, false, 1500, IptvRemoteAction.FAVORITE).action)
    }

    @Test fun remoteEnterAndSeparateMediaButtonsUseTheSameBindingAndConsumeTheirRelease() {
        assertEquals(23, IptvRemote.key(66))
        assertEquals(166, IptvRemote.key(92))
        val press = IptvRemotePress()
        press.handle(126, true, 100, IptvRemoteAction.PLAY_PAUSE, IptvRemoteAction.IGNORE)
        assertTrue(press.owns(85))
        assertEquals(IptvRemoteAction.PLAY_PAUSE, press.handle(126, false, 200, IptvRemoteAction.PLAY_PAUSE).action)
        assertFalse(press.owns(127))
    }

    @Test fun posterDecodeKeepsEnoughPixelsForTheDisplayedCard() {
        val size = IptvArtworkSizing.size(300, 450, 264, 368, true)
        assertEquals(1, size.sample)
        assertEquals(264, size.width)
        assertEquals(396, size.height)
    }

    @Test fun largeArtworkAndLogosKeepBoundedDecodedDimensions() {
        val poster = IptvArtworkSizing.size(4000, 6000, 264, 368, true)
        assertEquals(8, poster.sample)
        assertEquals(264, poster.width)
        assertEquals(396, poster.height)
        val logo = IptvArtworkSizing.size(4000, 3000, 96, 72, false)
        assertEquals(32, logo.sample)
        assertEquals(96, logo.width)
        assertEquals(72, logo.height)
    }

    @Test fun smallProviderArtworkIsNotEnlargedInMemory() {
        val size = IptvArtworkSizing.size(80, 120, 264, 368, true)
        assertEquals(IptvArtworkSizing.Size(80, 120, 1), size)
    }

    @Test fun heroPrefersTmdbBackdropsToProviderImagesButKeepsTheExplicitChoiceFirst() {
        val item = IptvItem("movie", "source", "42", ContentType.MOVIE, "Film",
            artwork = "provider-poster", backdrop = "provider-backdrop")
        val details = IptvMediaPresentation.Details(42, "movie",
            backdrops = listOf("tmdb-landscape", "second-landscape"), posters = listOf("tmdb-poster"))
        assertEquals(listOf("tmdb-landscape", "second-landscape", "provider-backdrop", "provider-poster"),
            IptvMediaPresentation.heroArtworkCandidates(item, details))
        assertEquals(listOf("selected-landscape", "tmdb-landscape", "second-landscape", "provider-backdrop", "provider-poster"),
            IptvMediaPresentation.heroArtworkCandidates(item, details, "selected-landscape"))
        assertEquals(listOf("tmdb-landscape", "second-landscape", "provider-backdrop", "provider-poster"),
            IptvMediaPresentation.heroArtworkCandidates(item.copy(type = ContentType.SERIES), details.copy(mediaType = "tv"), "tmdb-landscape"))
        assertEquals(listOf("provider-poster"),
            IptvMediaPresentation.heroArtworkCandidates(item.copy(backdrop = "provider-poster"), null))
    }

    @Test fun heroRejectsPortraitAndSquareImagesEvenWhenTheProviderLabelsThemAsBackdrops() {
        assertTrue(IptvArtworkSizing.isLandscape(1920, 1080))
        assertTrue(IptvArtworkSizing.isLandscape(1440, 1080))
        assertTrue(IptvArtworkSizing.isLandscape(2560, 1080))
        assertFalse(IptvArtworkSizing.isLandscape(1000, 1500))
        assertFalse(IptvArtworkSizing.isLandscape(1080, 1080))
        assertFalse(IptvArtworkSizing.isLandscape(1920, 0))
        assertFalse(IptvArtworkSizing.isLandscape(-1, -1))
    }

    @Test fun heroKeepsTheWholeLandscapeWhenTheArtworkAreaIsWiderThanSixteenNine() {
        val size = IptvArtworkSizing.size(1920, 1080, 720, 330, false)
        assertEquals(IptvArtworkSizing.Size(587, 330, 2), size)
        assertTrue(size.width <= 720 && size.height <= 330)
    }

    @Test fun guideViewportIncludesNeighboursWithoutCreatingTheWholeDay() {
        val hour = 3_600_000L
        val range = IptvGuideViewport.range(0, 24 * hour, 300 * 12, 600, 300)
        assertEquals(11 * hour, range.first)
        assertEquals(15 * hour - 1, range.last)
        assertTrue(12 * hour in range)
        assertTrue(14 * hour - 1 in range)
        assertFalse(0L in range)
    }

    @Test fun guideViewportClampsAtBothEdgesAndAcceptsLongerDstDays() {
        val hour = 3_600_000L
        assertEquals(0L, IptvGuideViewport.range(0, 24 * hour, 0, 600, 300).first)
        val range = IptvGuideViewport.range(0, 25 * hour, 300 * 24, 600, 300)
        assertEquals(25 * hour - 1, range.last)
    }

    @Test fun guideViewportStaysStableWithinAHalfHourDuringSmoothScrolling() {
        val hour = 3_600_000L
        assertEquals(IptvGuideViewport.range(0, 24 * hour, 3601, 600, 300),
            IptvGuideViewport.range(0, 24 * hour, 3620, 600, 300))
    }

    @Test fun catalogueArraysDecodeIncrementallyWithoutReadingTheWholeResponse() {
        val payload = (1..12_000).joinToString(",", "[", "]") {
            """{"stream_id":$it,"name":"Chaîne $it","plot":"${"synopsis ".repeat(20)}"}"""
        }.toByteArray(Charsets.UTF_8)
        val input = object : java.io.ByteArrayInputStream(payload) { fun consumed() = pos }
        val first = iptvCatalogueObjects(input).take(3).toList()
        assertEquals(listOf("Chaîne 1", "Chaîne 2", "Chaîne 3"), first.map { it.string("name") })
        assertTrue("A few entries must not retain the entire provider response", input.consumed() < payload.size / 4)
    }

    @Test fun streamedCataloguesPreserveWrappedResponsesAndCachedItemTypes() {
        val items = listOf(
            IptvItem("live", "source", "1", ContentType.LIVE, "Chaîne", epgId = "guide", catchupDays = 7),
            IptvItem("film", "source", "2", ContentType.MOVIE, "Film")
        )
        val array = items.joinToString(",", "[", "]") { iptvJson.encodeToString(IptvItem.serializer(), it) }
        listOf(array, """{"data":$array,"total":2}""").forEach { payload ->
            val decoded = iptvCatalogueObjects(payload.byteInputStream()).filter { it.string("type") == "LIVE" }
                .map { iptvJson.decodeFromJsonElement(IptvItem.serializer(), it) }.toList()
            assertEquals(listOf(items.first()), decoded)
        }
        assertTrue(iptvCatalogueObjects("[]".byteInputStream()).none())
    }

    @Test fun chunkedGcmRemainsCompatibleWithExistingCiphertextAtBufferBoundaries() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        val iv = ByteArray(12) { (it + 1).toByte() }
        fun cipher(mode: Int) = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        listOf(0, 1, 4095, 4096, 4097, 65535, 65536, 65537, 131072, 1048576).forEach { size ->
            val plain = ByteArray(size) { (it % 251).toByte() }
            val legacy = cipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(plain)
            assertArrayEquals(legacy, iptvCipherFinal(cipher(javax.crypto.Cipher.ENCRYPT_MODE), plain))
            assertArrayEquals(plain, iptvCipherFinal(cipher(javax.crypto.Cipher.DECRYPT_MODE), legacy))
        }
    }

    @Test fun chunkedGcmRejectsTamperedDataWithoutReturningPlaintext() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        val iv = ByteArray(12)
        val encryptor = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        val encrypted = encryptor.doFinal(ByteArray(65537) { 42 })
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        val decryptor = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        assertTrue(runCatching { iptvCipherFinal(decryptor, encrypted) }.exceptionOrNull() is javax.crypto.AEADBadTagException)
    }

    @Test fun chunkedGcmDecryptsAnEnvelopeRangeWithoutCopyingItsPayload() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        val iv = ByteArray(12)
        fun cipher(mode: Int) = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        val plain = ByteArray(65537) { (it % 251).toByte() }
        val encrypted = cipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(plain)
        val envelope = ByteArray(29) { 42 } + encrypted + ByteArray(17) { 9 }
        assertArrayEquals(plain, iptvCipherFinal(cipher(javax.crypto.Cipher.DECRYPT_MODE), envelope, 29, encrypted.size))
        envelope[29 + encrypted.lastIndex] = (envelope[29 + encrypted.lastIndex].toInt() xor 1).toByte()
        assertTrue(runCatching {
            iptvCipherFinal(cipher(javax.crypto.Cipher.DECRYPT_MODE), envelope, 29, encrypted.size)
        }.exceptionOrNull() is javax.crypto.AEADBadTagException)
    }

    @Test fun legacyStorageCipherStreamsLargePayloadAndKeepsPlatformCompatibility() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val iv = ByteArray(12) { (it + 1).toByte() }
        val aad = byteArrayOf(0, 1, 61)
        fun cipher(sdkInt: Int, mode: Int) = iptvStorageCipher(sdkInt).apply {
            init(mode, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            updateAAD(aad)
        }
        listOf(0, 4095, 4096, 4097, 65537, 10 * 1024 * 1024 + 1).forEach { size ->
            val plain = ByteArray(size) { (it % 251).toByte() }
            val platformEncrypted = cipher(31, javax.crypto.Cipher.ENCRYPT_MODE).doFinal(plain)
            val compatibleEncrypted = iptvCipherFinal(cipher(25, javax.crypto.Cipher.ENCRYPT_MODE), plain)
            assertArrayEquals(platformEncrypted, compatibleEncrypted)
            assertArrayEquals(plain, cipher(31, javax.crypto.Cipher.DECRYPT_MODE).doFinal(compatibleEncrypted))
            val decryptor = cipher(25, javax.crypto.Cipher.DECRYPT_MODE)
            if (size > 4096) {
                val output = ByteArray(decryptor.getOutputSize(platformEncrypted.size))
                val written = decryptor.update(platformEncrypted, 0, 4096, output, 0)
                assertTrue("Legacy GCM must emit data before doFinal instead of buffering the entire library", written > 0)
                val finalSize = written + decryptor.doFinal(platformEncrypted, 4096,
                    platformEncrypted.size - 4096, output, written)
                assertEquals(plain.size, finalSize)
                assertArrayEquals(plain, output)
            } else {
                assertArrayEquals(plain, iptvCipherFinal(decryptor, platformEncrypted))
            }
        }
    }

    @Test fun legacyStorageCipherRejectsTamperedTagAndAadWithoutReturningPlaintext() {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val iv = ByteArray(12)
        val aad = byteArrayOf(0, 1, 61)
        val encrypted = iptvStorageCipher(31).apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            updateAAD(aad)
        }.doFinal(ByteArray(65537) { 42 })
        listOf(true, false).forEach { damageTag ->
            val input = encrypted.copyOf()
            val header = aad.copyOf()
            if (damageTag) input[input.lastIndex] = (input.last().toInt() xor 1).toByte()
            else header[1] = 2
            val decryptor = iptvStorageCipher(25).apply {
                init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
                updateAAD(header)
            }
            assertTrue(runCatching { iptvCipherFinal(decryptor, input) }.exceptionOrNull() is javax.crypto.AEADBadTagException)
        }
    }

    private fun storageTestWrap(input: ByteArray): ByteArray {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(input)
        return byteArrayOf(12) + cipher.iv + encrypted
    }

    private fun storageTestUnwrap(input: ByteArray): ByteArray {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, input.copyOfRange(1, 13)))
        }.doFinal(input.copyOfRange(13, input.size))
    }

    @Test fun storageEnvelopeKeepsLargeLibrariesOutOfKeystoreOperations() {
        listOf(0, 1, 4096, 4097, 65537, 1048576).forEach { size ->
            val plain = ByteArray(size) { (it % 251).toByte() }
            var wrapped = 0
            var unwrapped = 0
            val encrypted = iptvEncryptStorage(plain) { key ->
                assertEquals(32, key.size)
                wrapped++
                storageTestWrap(key)
            }
            val decoded = iptvDecryptStorage(encrypted) { key ->
                assertEquals(61, key.size) // Only IV + small key + authentication tag reach Keystore.
                unwrapped++
                storageTestUnwrap(key)
            }
            assertEquals(1, wrapped)
            assertEquals(1, unwrapped)
            assertArrayEquals(plain, decoded)
        }
    }

    @Test fun storageReadsLegacyLibrariesAndBackups() {
        val plain = ByteArray(65537) { (it % 251).toByte() }
        assertArrayEquals(plain, iptvDecryptStorage(storageTestWrap(plain), ::storageTestUnwrap))
    }

    @Test fun storageEnvelopeRejectsTamperedWrappedKeyIvAndPayload() {
        val encrypted = iptvEncryptStorage(ByteArray(65537) { 42 }, ::storageTestWrap)
        val headerSize = 3 + (encrypted[2].toInt() and 0xff)
        listOf(3, headerSize, headerSize + 12, encrypted.lastIndex).forEach { position ->
            val damaged = encrypted.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertTrue(runCatching { iptvDecryptStorage(damaged, ::storageTestUnwrap) }.exceptionOrNull() is javax.crypto.AEADBadTagException)
        }
        assertTrue(runCatching { iptvDecryptStorage(encrypted.copyOf(encrypted.size - 1), ::storageTestUnwrap) }.exceptionOrNull() is javax.crypto.AEADBadTagException)
    }

    @Test fun storageEnvelopeRejectsUnsupportedAndTruncatedHeaders() {
        listOf(byteArrayOf(0), byteArrayOf(0, 2, 1), byteArrayOf(0, 1, 0), byteArrayOf(0, 1, 61)).forEach { damaged ->
            assertTrue(runCatching { iptvDecryptStorage(damaged) { error("Must not unwrap an invalid header") } }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun streamedLibraryStorageKeepsExistingReadersAndExactUtf8Content() {
        val library = IptvLibrary(settings = mapOf("title" to "Café \"VO\"\n日本語"),
            syncRecords = mapOf("deleted" to IptvSyncRecord(42, "tv", null)))
        val output = java.io.ByteArrayOutputStream()
        val expected = iptvWriteEncryptedStorage(output, ::storageTestWrap) { iptvJson.encodeToStream(library, it) }
        val encrypted = output.toByteArray()
        val plaintext = iptvDecryptStorage(encrypted, ::storageTestUnwrap)
        assertEquals(iptvJson.encodeToString(library), plaintext.toString(Charsets.UTF_8))
        assertEquals(library, iptvJson.decodeFromString<IptvLibrary>(plaintext.toString(Charsets.UTF_8)))
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(plaintext), expected)
        assertArrayEquals(expected, iptvEncryptedStorageDigest(encrypted.inputStream(), ::storageTestUnwrap))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun largeLibraryStorageWritesAndVerifiesInBoundedChunks() {
        val description = "Café \"VO\"\n日本語 ".repeat(32)
        val library = IptvLibrary(settings = (0 until 32_000).associate { "setting:$it" to description })
        val file = java.io.File.createTempFile("iptv-stream-test", ".bin")
        try {
            var bytesWritten = 0L
            val expected = file.outputStream().use { fileOutput ->
                val bounded = object : java.io.OutputStream() {
                    override fun write(value: Int) { fileOutput.write(value); bytesWritten++ }
                    override fun write(value: ByteArray, offset: Int, count: Int) {
                        assertTrue("Do not allocate or write the complete library", count <= 4112)
                        bytesWritten += count
                        fileOutput.write(value, offset, count)
                    }
                }
                iptvWriteEncryptedStorage(bounded, ::storageTestWrap) { iptvJson.encodeToStream(library, it) }
            }
            assertTrue("Exercise a library larger than the failed JSON allocation", bytesWritten > 21_492_400)
            val actual = file.inputStream().use { fileInput ->
                val bounded = object : java.io.FilterInputStream(fileInput) {
                    override fun read(value: ByteArray, offset: Int, count: Int): Int {
                        assertTrue("Verification must not read the complete library", count <= 4096)
                        return super.read(value, offset, count)
                    }
                }
                iptvEncryptedStorageDigest(bounded, ::storageTestUnwrap)
            }
            assertArrayEquals(expected, actual)
        } finally { file.delete() }
    }

    @Test fun streamedStorageHandlesEmptyPayloadAndChunkBoundaries() {
        listOf(0, 1, 4095, 4096, 4097, 65537).forEach { size ->
            val plain = ByteArray(size) { (it % 251).toByte() }
            val output = java.io.ByteArrayOutputStream()
            val expected = iptvWriteEncryptedStorage(output, ::storageTestWrap) { it.write(plain) }
            val encrypted = output.toByteArray()
            assertArrayEquals(plain, iptvDecryptStorage(encrypted, ::storageTestUnwrap))
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(plain), expected)
            assertArrayEquals(expected, iptvEncryptedStorageDigest(encrypted.inputStream(), ::storageTestUnwrap))
        }
    }

    @Test fun streamedStorageAuthenticationRejectsTamperingAndTruncation() {
        val output = java.io.ByteArrayOutputStream()
        iptvWriteEncryptedStorage(output, ::storageTestWrap) { it.write(ByteArray(65537) { 42 }) }
        val encrypted = output.toByteArray()
        val headerSize = 3 + (encrypted[2].toInt() and 0xff)
        listOf(3, headerSize, headerSize + 12, encrypted.lastIndex).forEach { position ->
            val damaged = encrypted.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertTrue(runCatching {
                iptvEncryptedStorageDigest(damaged.inputStream(), ::storageTestUnwrap)
            }.exceptionOrNull() is javax.crypto.AEADBadTagException)
        }
        assertTrue(runCatching {
            iptvEncryptedStorageDigest(encrypted.copyOf(encrypted.size - 1).inputStream(), ::storageTestUnwrap)
        }.exceptionOrNull() is javax.crypto.AEADBadTagException)
    }

    @Test fun streamedStoragePropagatesSerializationAndDiskFailuresBeforeCommit() {
        val failure = java.io.IOException("simulated failure")
        assertSame(failure, runCatching {
            iptvWriteEncryptedStorage(java.io.ByteArrayOutputStream(), ::storageTestWrap) {
                it.write(ByteArray(4097))
                throw failure
            }
        }.exceptionOrNull())
        val fullDisk = object : java.io.OutputStream() {
            override fun write(value: Int) { throw failure }
        }
        assertSame(failure, runCatching {
            iptvWriteEncryptedStorage(fullDisk, ::storageTestWrap) { it.write(ByteArray(4097)) }
        }.exceptionOrNull())
    }

    @Test fun homeFavoriteMovieProgressUsesCanonicalIdentityAndLatestRecord() {
        val first = com.nuvio.tv.domain.model.WatchProgress("iptv:movie", "movie", "Même titre", null, null, null,
            "video", null, null, null, 1, 1000, 10)
        val finished = first.copy(position = 950, lastWatched = 20)
        val homonym = first.copy(contentId = "iptv:other", lastWatched = 30)
        val series = first.copy(contentType = "series", lastWatched = 40)
        val selected = IptvStore.homeFavoritePlaybackProgress(first.contentId, "movies", listOf(first, homonym, series, finished))
        assertEquals(finished, selected)
        assertTrue(selected!!.isCompleted())
        assertNull(IptvStore.homeFavoritePlaybackProgress("iptv:missing", "movies", listOf(first)))
        assertNull(IptvStore.homeFavoritePlaybackProgress(first.contentId, "live", listOf(first)))
    }

    @Test fun homeFavoriteSeriesKeepsUnfinishedEpisodesAfterAnotherEpisodeFinishes() {
        val first = com.nuvio.tv.domain.model.WatchProgress("iptv:series", "series", "Série", null, null, null,
            "episode1", 1, 1, "Épisode", 1, 1000, 10)
        val second = first.copy(videoId = "episode2", episode = 2, position = 950, lastWatched = 20)
        assertEquals(first, IptvStore.homeFavoritePlaybackProgress(first.contentId, "series", listOf(first, second)))
        val firstFinished = first.copy(position = 950, lastWatched = 30)
        assertNull(IptvStore.homeFavoritePlaybackProgress(first.contentId, "series", listOf(first, second, firstFinished)))
        val firstReset = first.copy(position = 0, lastWatched = 40)
        assertNull(IptvStore.homeFavoritePlaybackProgress(first.contentId, "series", listOf(first, firstReset)))
    }

    @Test fun homeFavoriteSeriesRecognizesStartsWithUnknownDuration() {
        val started = com.nuvio.tv.domain.model.WatchProgress("iptv:series", "series", "Série", null, null, null,
            "episode", 1, 1, "Épisode", 1, 0, 10)
        assertEquals(started, IptvStore.homeFavoritePlaybackProgress(started.contentId, "series", listOf(started)))
        assertNull(IptvStore.homeFavoritePlaybackProgress(started.contentId, "series", emptyList()))
        assertNull(IptvStore.homeFavoritePlaybackProgress(started.contentId, "series", listOf(started.copy(progressPercent = 100f))))
    }

    @Test fun homeSeriesResumeKeepsEpisodeTwoWhenEpisodeOneIsWatched() {
        val first = com.nuvio.tv.domain.model.WatchProgress("iptv:source:series:7", "series", "L’arène", null, null, null,
            "episode1", 1, 1, "Épisode 1", 42 * 60_000L, 44 * 60_000L, 10)
        val second = first.copy(videoId = "episode2", episode = 2, position = 5 * 60_000L, lastWatched = 20)
        assertEquals(second, IptvStore.homeFavoritePlaybackProgress(first.contentId, "series", listOf(first, second)))
        assertEquals(second, IptvStore.homeFavoritePlaybackProgress(first.contentId, "series",
            listOf(first.copy(lastWatched = 30, progressPercent = 100f), second)))
    }

    @Test fun homeSeriesBadgeRemainsWhileWaitingAfterAllAvailableEpisodesAreWatched() {
        val first = com.nuvio.tv.domain.model.WatchProgress("iptv:provider:SERIES:42", "series", "L’arène", null, null, null,
            "episode1", 1, 1, "Épisode 1", 42 * 60_000L, 44 * 60_000L, 10)
        val second = first.copy(videoId = "episode2", episode = 2, position = first.duration, lastWatched = 20)
        val progress = listOf(first, second)
        assertTrue(IptvStore.homeFavoriteSeriesStarted(first.contentId, progress))
        assertNull(IptvStore.homeFavoritePlaybackProgress(first.contentId, "series", progress))
    }

    @Test fun homeSeriesBadgeRemainsForAPartlyWatchedEpisode() {
        val progress = com.nuvio.tv.domain.model.WatchProgress("iptv:provider:SERIES:43", "series", "Les fous du bus", null, null, null,
            "episode1", 1, 1, "Épisode 1", 5 * 60_000L, 44 * 60_000L, 10)
        assertTrue(IptvStore.homeFavoriteSeriesStarted(progress.contentId, listOf(progress)))
        assertEquals(progress, IptvStore.homeFavoritePlaybackProgress(progress.contentId, "series", listOf(progress)))
    }

    @Test fun homeSeriesBadgeUsesLatestEpisodeStateAfterAnExplicitReset() {
        val completed = com.nuvio.tv.domain.model.WatchProgress("iptv:provider:SERIES:42", "series", "Série", null, null, null,
            "episode1", 1, 1, "Épisode 1", 100, 100, 10, progressPercent = 100f)
        val reset = completed.copy(position = 0, progressPercent = 0f, lastWatched = 20)
        assertFalse(IptvStore.homeFavoriteSeriesStarted(completed.contentId, listOf(reset, completed)))
        val anotherWatchedEpisode = completed.copy(videoId = "episode2", episode = 2, lastWatched = 30)
        assertTrue(IptvStore.homeFavoriteSeriesStarted(completed.contentId, listOf(reset, completed, anotherWatchedEpisode)))
    }

    @Test fun homeSeriesBadgeRecognizesSyncedCompletionWithoutAPosition() {
        val progress = com.nuvio.tv.domain.model.WatchProgress("iptv:provider:SERIES:42", "series", "Série", null, null, null,
            "episode1", 1, 1, "Épisode 1", 0, 0, 10, progressPercent = 100f)
        assertTrue(IptvStore.homeFavoriteSeriesStarted(progress.contentId, listOf(progress)))
        assertNull(IptvStore.homeFavoritePlaybackProgress(progress.contentId, "series", listOf(progress)))
    }

    @Test fun homeSeriesBadgeKeepsAccountsProvidersAndContentTypesSeparate() {
        val id = "iptv:account:first:provider:SERIES:42"
        val progress = com.nuvio.tv.domain.model.WatchProgress(id, "series", "Même titre", null, null, null,
            "episode1", 1, 1, "Épisode 1", 100, 100, 10)
        val unrelated = listOf(progress.copy(contentId = "iptv:account:second:provider:SERIES:42"),
            progress.copy(contentId = "iptv:account:first:other:SERIES:42"), progress.copy(contentType = "movie"))
        assertFalse(IptvStore.homeFavoriteSeriesStarted(id, unrelated))
        assertTrue(IptvStore.homeFavoriteSeriesStarted(id, unrelated + progress))
    }

    @Test fun homeSeriesBadgeIsAbsentForANeverStartedSeries() {
        val progress = com.nuvio.tv.domain.model.WatchProgress("iptv:provider:SERIES:42", "series", "Série", null, null, null,
            "episode1", 1, 1, "Épisode 1", 0, 0, 10)
        assertFalse(IptvStore.homeFavoriteSeriesStarted(progress.contentId, emptyList()))
        assertFalse(IptvStore.homeFavoriteSeriesStarted(progress.contentId, listOf(progress)))
    }

    @Test fun homeUnwatchedMovieKeepsDurationAndMetadataWithoutAPlaybackUrl() {
        val movie = IptvItem(itemId("provider", ContentType.MOVIE, "42"), "provider", "42", ContentType.MOVIE, "Animals")
        val previous = com.nuvio.tv.domain.model.WatchProgress(movie.id, "movie", movie.title, "poster", "backdrop", "logo",
            "video", null, null, null, 500_000L, 6_480_000L, 10L, description = "Synopsis")
        assertTrue(movie.streamUrl.isBlank())
        val reset = IptvStore.homeUnwatchedProgress(movie, movie.id, listOf(previous), 20L).single()
        assertEquals(previous.copy(position = 0L, progressPercent = 0f, lastWatched = 20L), reset)
        assertFalse(reset.isInProgress())
        assertFalse(reset.isCompleted())
    }

    @Test fun homeUnwatchedMovieOnlyResetsItsAccountProviderAndContentType() {
        val movie = IptvItem(itemId("provider", ContentType.MOVIE, "42"), "provider", "42", ContentType.MOVIE, "Même titre")
        val owner = iptvPlaybackContentId(movie.id, "second", "first")
        val previous = com.nuvio.tv.domain.model.WatchProgress(owner, "movie", movie.title, null, null, null,
            "video", null, null, null, 50L, 100L, 10L)
        val otherAccount = previous.copy(contentId = movie.id, lastWatched = 30L)
        val otherProvider = previous.copy(contentId = iptvPlaybackContentId(itemId("other", ContentType.MOVIE, "42"), "second", "first"))
        val otherType = previous.copy(contentType = "series", season = 1, episode = 1)
        val reset = IptvStore.homeUnwatchedProgress(movie, owner, listOf(previous, otherAccount, otherProvider, otherType), 20L)
        assertEquals(listOf(previous.copy(position = 0L, progressPercent = 0f, lastWatched = 20L)), reset)
    }

    @Test fun homeUnwatchedCompletedMovieWinsOverDuplicateAndOldSyncedProgress() {
        val movie = IptvItem(itemId("provider", ContentType.MOVIE, "42"), "provider", "42", ContentType.MOVIE, "Film")
        val old = com.nuvio.tv.domain.model.WatchProgress(movie.id, "movie", movie.title, null, null, null,
            "video", null, null, null, 50L, 100L, 10L)
        val completed = old.copy(position = 100L, progressPercent = 100f, lastWatched = 100L, description = "Fiche récente")
        val reset = IptvStore.homeUnwatchedProgress(movie, movie.id, listOf(old, completed), 20L).single()
        assertEquals(completed.copy(position = 0L, progressPercent = 0f, lastWatched = 101L), reset)
        assertFalse(com.nuvio.tv.domain.model.WatchProgress.shouldApplyRemoteProgress(reset.lastWatched, completed.lastWatched))
        assertEquals(reset, IptvStore.homeFavoritePlaybackProgress(movie.id, "movies", listOf(old, completed, reset)))
    }

    @Test fun homeUnwatchedSeriesResetsCompletedAndStartedEpisodesAcrossSeasons() {
        val series = IptvItem(itemId("provider", ContentType.SERIES, "42"), "provider", "42", ContentType.SERIES, "Série")
        val first = com.nuvio.tv.domain.model.WatchProgress(series.id, "series", series.title, null, null, null,
            "episode1", 1, 1, "Épisode 1", 100L, 100L, 10L, progressPercent = 100f)
        val second = first.copy(videoId = "episode2", episode = 2, position = 25L, progressPercent = null, lastWatched = 20L)
        val nextSeason = second.copy(videoId = "episode3", season = 2, episode = 1, lastWatched = 30L)
        val previous = listOf(first, second, nextSeason)
        val reset = IptvStore.homeUnwatchedProgress(series, series.id, previous, 40L)
        assertEquals(3, reset.size)
        assertTrue(reset.all { it.position == 0L && it.progressPercent == 0f && !it.isCompleted() })
        assertEquals(previous.map { it.videoId }, reset.map { it.videoId })
        assertNull(IptvStore.homeFavoritePlaybackProgress(series.id, "series", previous + reset))
        assertFalse(IptvStore.homeFavoriteSeriesStarted(series.id, previous + reset))
    }

    @Test fun homeUnwatchedEpisodeLeavesOtherEpisodesAndSeasonsInProgress() {
        val seriesId = itemId("provider", ContentType.SERIES, "42")
        val episode = IptvItem(itemId("provider", ContentType.EPISODE, "7"), "provider", "7", ContentType.EPISODE,
            "Épisode 1", seriesId = "42", season = 1, episode = 1)
        val first = com.nuvio.tv.domain.model.WatchProgress(seriesId, "series", "Série", null, null, null,
            episode.id, 1, 1, episode.title, 100L, 100L, 10L, progressPercent = 100f)
        val second = first.copy(videoId = "episode2", episode = 2, position = 25L, progressPercent = null, lastWatched = 20L)
        val nextSeason = second.copy(videoId = "episode3", season = 2, episode = 1, lastWatched = 30L)
        val reset = IptvStore.homeUnwatchedProgress(episode, IptvStore.homeContentId(episode), listOf(first, second, nextSeason), 40L)
        assertEquals(listOf(first.copy(position = 0L, progressPercent = 0f, lastWatched = 40L)), reset)
        assertEquals(nextSeason, IptvStore.homeFavoritePlaybackProgress(seriesId, "series", listOf(first, second, nextSeason) + reset))
    }

    @Test fun homeUnwatchedUnknownDurationClearsPercentageAndAcceptsMissingProgress() {
        val movie = IptvItem(itemId("provider", ContentType.MOVIE, "42"), "provider", "42", ContentType.MOVIE, "Film")
        val previous = com.nuvio.tv.domain.model.WatchProgress(movie.id, "movie", movie.title, null, null, null,
            "video", null, null, null, 0L, 0L, 10L, progressPercent = 100f)
        val reset = IptvStore.homeUnwatchedProgress(movie, movie.id, listOf(previous), 20L).single()
        assertEquals(0L, reset.duration)
        assertEquals(0f, reset.progressPercent!!, 0f)
        assertFalse(reset.isCompleted())
        assertTrue(IptvStore.homeUnwatchedProgress(movie, movie.id, emptyList(), 20L).isEmpty())
    }

    @Test fun liveNavigationKeepsCatalogOrderWrapsAndSkipsVodAndDuplicates() {
        val first = IptvItem("first", "source", "1", ContentType.LIVE, "Zeta")
        val second = first.copy(id = "second", providerId = "2", title = "Alpha")
        val third = first.copy(id = "third", sourceId = "other", providerId = "3", title = "Beta")
        val movie = first.copy(id = "movie", type = ContentType.MOVIE)
        val catalog = listOf(first, movie, second, first, third)
        assertEquals(second, IptvOrganization.adjacentChannel(catalog, first.id, true))
        assertEquals(third, IptvOrganization.adjacentChannel(catalog, first.id, false))
        assertEquals(third, IptvOrganization.adjacentChannel(catalog, second.id, true))
        assertEquals(first, IptvOrganization.adjacentChannel(catalog, second.id, false))
        assertEquals(first, IptvOrganization.adjacentChannel(catalog, third.id, true))
        assertEquals(first, IptvOrganization.adjacentChannel(listOf(first), first.id, false))
        assertNull(IptvOrganization.adjacentChannel(catalog, movie.id, true))
        assertNull(IptvOrganization.adjacentChannel(emptyList(), first.id, true))
        assertNull(IptvOrganization.adjacentChannel(catalog, "removed", true))
    }

    @Test fun recordingsKeepTheirOriginalFolderAndRejectInvalidIds() {
        val root = java.io.File(System.getProperty("java.io.tmpdir"), "original-recordings").absoluteFile
        val selected = java.io.File(root.parentFile, "selected-recordings").absoluteFile
        val id = "12345678-1234-1234-1234-123456789abc"
        val item = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val legacy = IptvRecording(id, item, "Programme", 1, 2)
        assertEquals(java.io.File(root, id), IptvRecordingScheduler.recordingFolder(root, legacy))
        val configured = legacy.copy(directory = selected.absolutePath)
        assertEquals(java.io.File(selected, id), IptvRecordingScheduler.recordingFolder(root, configured))
        val serialized = iptvJson.encodeToString(IptvRecording.serializer(), configured)
        assertEquals(configured, iptvJson.decodeFromString(IptvRecording.serializer(), serialized))
        val oldJson = kotlinx.serialization.json.JsonObject(iptvJson.parseToJsonElement(serialized).jsonObject - "directory").toString()
        val restored = iptvJson.decodeFromString(IptvRecording.serializer(), oldJson)
        assertEquals("", restored.directory)
        assertEquals(java.io.File(root, id), IptvRecordingScheduler.recordingFolder(root, restored))
        assertEquals(java.io.File(selected, id), IptvRecordingScheduler.recordingFolder(root,
            legacy.copy(file = java.io.File(selected, "$id/recording.ts").absolutePath)))
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.recordingFolder(root, configured.copy(id = "../other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.recordingFolder(root, configured.copy(directory = "relative"))
        }
    }

    @Test fun movingRecordingStartKeepsDurationAndAllowsPlanningOnAnotherDay() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val start = time("2026-10-15T20:00:00")
        val end = time("2026-10-15T21:30:00")
        val moved = IptvRecordingScheduler.moveStart(start, end, time("2026-10-16T23:30:00"))
        assertEquals(time("2026-10-17T01:00:00"), moved.end)
        val plan = IptvRecordingScheduler.plan(moved.start, moved.end, 5, 10, now = start, zone = zone)
        assertEquals(moved, plan.program)
        assertEquals(moved.start - 5 * 60_000L, plan.capture.start)
        assertEquals(moved.end + 10 * 60_000L, plan.capture.end)
    }

    @Test fun movingRecordingStartRepairsAnInvalidEndAndKeepsAValidShortDuration() {
        assertEquals(IptvRecordingScheduler.Window(180_000L, 240_000L),
            IptvRecordingScheduler.moveStart(120_000L, 60_000L, 180_000L))
        assertEquals(IptvRecordingScheduler.Window(180_000L, 210_000L),
            IptvRecordingScheduler.moveStart(60_000L, 90_000L, 180_000L))
    }

    @Test fun recordingMarginsPreserveProgramHoursAndOldJsonDefaults() {
        val minute = 60_000L
        val channel = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val recording = IptvRecording("12345678-1234-1234-1234-123456789abc", channel, "Programme",
            18 * minute, 33 * minute, directory = "smb://nas.test/TV", accountId = "first",
            programStart = 20 * minute, programEnd = 30 * minute, beforeMinutes = 2, afterMinutes = 3,
            repeatDays = listOf(1, 3), recurrenceId = "recurrence")
        val serialized = iptvJson.encodeToString(IptvRecording.serializer(), recording)
        assertEquals(recording, iptvJson.decodeFromString(IptvRecording.serializer(), serialized))
        val legacyJson = kotlinx.serialization.json.JsonObject(iptvJson.parseToJsonElement(serialized).jsonObject -
            setOf("programStart", "programEnd", "beforeMinutes", "afterMinutes", "repeatDays", "recurrenceId")).toString()
        val legacy = iptvJson.decodeFromString(IptvRecording.serializer(), legacyJson)
        assertEquals(recording.start, legacy.programStart)
        assertEquals(recording.end, legacy.programEnd)
        assertEquals(0, legacy.beforeMinutes)
        assertEquals(0, legacy.afterMinutes)
        assertTrue(legacy.repeatDays.isEmpty())
        assertEquals("", legacy.recurrenceId)
        assertEquals(recording.directory, legacy.directory)
        assertEquals(recording.accountId, legacy.accountId)
    }

    @Test fun recordingMarginsWindowAddsBeforeAndAfterWithoutChangingZeroMarginHours() {
        val minute = 60_000L
        assertEquals(IptvRecordingScheduler.Window(20 * minute, 30 * minute),
            IptvRecordingScheduler.window(20 * minute, 30 * minute, now = 5 * minute))
        assertEquals(IptvRecordingScheduler.Window(18 * minute, 33 * minute),
            IptvRecordingScheduler.window(20 * minute, 30 * minute, 2, 3, now = 5 * minute))
        assertEquals(IptvRecordingScheduler.Window(5 * minute, 10 * minute),
            IptvRecordingScheduler.window(6 * minute, 9 * minute, 2, 1, now = 5 * minute))
        assertEquals(IptvRecordingScheduler.Window(5 * minute, 8 * minute),
            IptvRecordingScheduler.window(2 * minute, 8 * minute, now = 5 * minute))
    }

    @Test fun recordingMarginsWindowRejectsInvalidTimesAndMarginOverflow() {
        val minute = 60_000L
        listOf(-1 to 0, 61 to 0, 0 to -1, 0 to 61).forEach { (before, after) ->
            assertThrows(IllegalArgumentException::class.java) {
                IptvRecordingScheduler.window(20 * minute, 30 * minute, before, after, now = 5 * minute)
            }
        }
        listOf(0L to 30 * minute, 20 * minute to 20 * minute, 20 * minute to 10 * minute,
            2 * minute to 5 * minute).forEach { (start, end) ->
            assertThrows(IllegalArgumentException::class.java) {
                IptvRecordingScheduler.window(start, end, now = 5 * minute)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.window(20 * minute, Long.MAX_VALUE, afterMinutes = 1, now = 5 * minute)
        }
    }

    @Test fun recordingConflictsExcludeTheEditedIdAndTreatAdjacentWindowsAsSeparate() {
        val channel = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val planned = IptvRecording("first", channel, "Programme", 10, 20, accountId = "first")
        val running = planned.copy(id = "second", start = 30, end = 40, state = RecordingState.RUNNING, accountId = "second")
        val completed = planned.copy(id = "done", start = 50, end = 60, state = RecordingState.COMPLETE)
        val cancelled = planned.copy(id = "cancelled", start = 50, end = 60, state = RecordingState.CANCELLED)
        val values = listOf(planned, running, completed, cancelled)
        assertEquals(planned, IptvRecordingScheduler.conflict(values, 15, 25))
        assertEquals(running, IptvRecordingScheduler.conflict(values, 35, 45))
        assertNull(IptvRecordingScheduler.conflict(values, 20, 30))
        assertNull(IptvRecordingScheduler.conflict(values, 50, 60))
        assertNull(IptvRecordingScheduler.conflict(values, 10, 20, excludedId = planned.id))
        assertEquals(running, IptvRecordingScheduler.conflict(values, 15, 35, excludedId = planned.id))
        val margin = IptvRecordingScheduler.window(60_000, 120_000, 1, 0, now = 1)
        assertEquals(planned, IptvRecordingScheduler.conflict(values, margin.start, margin.end))
    }

    @Test fun recordingRecurrenceSelectsTheNextWeekdayAndSkipsElapsedDays() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val start = time("2026-10-12T22:00:00")
        val end = time("2026-10-12T23:30:00")
        assertEquals(IptvRecordingScheduler.Window(time("2026-10-14T22:00:00"), time("2026-10-14T23:30:00")),
            IptvRecordingScheduler.recurringProgram(start, end, listOf(1, 3), start, zone))
        assertEquals(IptvRecordingScheduler.Window(time("2026-10-19T22:00:00"), time("2026-10-19T23:30:00")),
            IptvRecordingScheduler.recurringProgram(start, end, listOf(1, 3), time("2026-10-15T10:00:00"), zone))
        assertEquals(IptvRecordingScheduler.Window(start, end),
            IptvRecordingScheduler.recurringProgram(start, end, listOf(1), start - 1, zone))
    }

    @Test fun recordingRecurrencePreservesOvernightAndLocalDstEndHours() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val overnight = IptvRecordingScheduler.recurringProgram(time("2026-10-12T22:00:00"), time("2026-10-13T01:15:00"),
            listOf(1, 3), time("2026-10-12T22:00:00"), zone)
        assertEquals(time("2026-10-14T22:00:00"), overnight.start)
        assertEquals(time("2026-10-15T01:15:00"), overnight.end)
        val autumn = IptvRecordingScheduler.recurringProgram(time("2026-10-18T01:30:00"), time("2026-10-18T03:30:00"),
            listOf(7), time("2026-10-18T01:30:00"), zone)
        assertEquals(time("2026-10-25T01:30:00"), autumn.start)
        assertEquals(time("2026-10-25T03:30:00"), autumn.end)
        assertEquals(3 * 60 * 60_000L, autumn.end - autumn.start)
    }

    @Test fun recordingRecurrenceRejectsInvalidDaysAndOverlappingMargins() {
        val zone = java.time.ZoneId.of("UTC")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val start = time("2026-10-12T12:00:00")
        val end = time("2026-10-12T13:00:00")
        listOf(emptyList(), listOf(0), listOf(8)).forEach { days ->
            assertThrows(IllegalArgumentException::class.java) { IptvRecordingScheduler.recurringProgram(start, end, days, start, zone) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.plan(start, time("2026-10-13T11:30:00"), afterMinutes = 60,
                repeatDays = (1..7).toList(), now = start - 60_000, zone = zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.plan(start, time("2026-10-14T11:00:00"),
                repeatDays = listOf(1, 3, 4), now = start - 60_000, zone = zone)
        }
    }

    @Test fun recordingRecurringConflictChecksFutureOccurrencesAndAdjacentWindows() {
        val zone = java.time.ZoneId.of("UTC")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val channel = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val weekly = IptvRecording("first", channel, "Weekly", time("2026-10-12T12:00:00"), time("2026-10-12T13:00:00"), repeatDays = listOf(1))
        val future = weekly.copy(id = "second", start = time("2026-10-26T12:30:00"), end = time("2026-10-26T14:00:00"),
            programStart = time("2026-10-26T12:30:00"), programEnd = time("2026-10-26T14:00:00"), repeatDays = emptyList())
        assertEquals(weekly, IptvRecordingScheduler.planningConflict(listOf(weekly), future, zone))
        assertEquals(future, IptvRecordingScheduler.planningConflict(listOf(future), weekly, zone))
        assertEquals(weekly, IptvRecordingScheduler.planningConflict(listOf(weekly), future.copy(repeatDays = listOf(1)), zone))
        assertNull(IptvRecordingScheduler.planningConflict(listOf(weekly), future.copy(start = time("2026-10-26T13:00:00")), zone))
        assertNull(IptvRecordingScheduler.planningConflict(listOf(weekly), weekly.copy(title = "Edited"), zone))
    }

    @Test fun recordingRepeatingEditionKeepsSeriesOwnerAndFolder() {
        val zone = java.time.ZoneId.of("UTC")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val channel = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val start = time("2026-10-12T12:00:00")
        val original = IptvRecording("12345678-1234-1234-1234-123456789abc", channel, "Programme", start, start + 60 * 60_000L,
            accountId = "first", directory = "smb://nas.test/TV")
        val edited = IptvRecordingScheduler.replanned(original, channel, "Programme", original.start, original.end,
            2, 3, now = start - 60 * 60_000L, repeatDays = listOf(3, 3), zone = zone)
        assertEquals(original.id, edited.id)
        assertEquals(original.accountId, edited.accountId)
        assertEquals(original.directory, edited.directory)
        assertEquals(original.id, edited.recurrenceId)
        assertEquals(listOf(3), edited.repeatDays)
        assertEquals(time("2026-10-14T12:00:00"), edited.programStart)
        assertEquals(edited.programStart - 2 * 60_000L, edited.start)
        assertEquals(edited.programEnd + 3 * 60_000L, edited.end)
    }

    @Test fun recordingRecurrenceRecoversOngoingOccurrencesWithoutRestartingThePreviousOne() {
        val zone = java.time.ZoneId.of("UTC")
        fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        val channel = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val daily = IptvRecording("first", channel, "Programme", time("2026-10-12T12:00:00"), time("2026-10-12T13:00:00"),
            repeatDays = (1..7).toList())
        val ongoing = IptvRecordingScheduler.nextRecurringProgram(daily, time("2026-10-14T12:30:00"), zone)
        assertEquals(time("2026-10-14T12:00:00"), ongoing.start)
        assertEquals(time("2026-10-14T13:00:00"), ongoing.end)
        val future = IptvRecordingScheduler.nextRecurringProgram(daily, time("2026-10-14T13:30:00"), zone)
        assertEquals(time("2026-10-15T12:00:00"), future.start)
        assertEquals(time("2026-10-15T13:00:00"), future.end)
        val earlyStop = IptvRecordingScheduler.nextRecurringProgram(daily, time("2026-10-12T12:05:00"), zone)
        assertEquals(time("2026-10-13T12:00:00"), earlyStop.start)
    }

    @Test fun recordingEditionPreservesIdentityOwnerAndDestinationWhileUpdatingHours() {
        val minute = 60_000L
        val originalChannel = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val nextChannel = originalChannel.copy(id = "other-channel", providerId = "other-provider", title = "Other TV")
        val original = IptvRecording("12345678-1234-1234-1234-123456789abc", originalChannel,
            "Programme", 20 * minute, 30 * minute, directory = "smb://nas.test/TV", accountId = "first")
        val edited = IptvRecordingScheduler.replanned(original, nextChannel, "Nouveau programme",
            6 * minute, 9 * minute, 2, 1, now = 5 * minute)
        assertEquals(original.id, edited.id)
        assertEquals(original.accountId, edited.accountId)
        assertEquals(original.directory, edited.directory)
        assertEquals(RecordingState.PLANNED, edited.state)
        assertEquals(nextChannel, edited.channel)
        assertEquals("Nouveau programme", edited.title)
        assertEquals(6 * minute, edited.programStart)
        assertEquals(9 * minute, edited.programEnd)
        assertEquals(5 * minute, edited.start)
        assertEquals(10 * minute, edited.end)
        assertEquals(2, edited.beforeMinutes)
        assertEquals(1, edited.afterMinutes)
        RecordingState.entries.filter { it != RecordingState.PLANNED }.forEach { state ->
            assertThrows(IllegalStateException::class.java) {
                IptvRecordingScheduler.replanned(original.copy(state = state), nextChannel, "Programme",
                    20 * minute, 30 * minute, now = 5 * minute)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.replanned(original, nextChannel.copy(type = ContentType.MOVIE),
                "Programme", 20 * minute, 30 * minute, now = 5 * minute)
        }
    }

    @Test fun smbRecordingsKeepTheirShareWhenTheDefaultFolderChanges() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val recording = IptvRecording(id, IptvItem("channel", "source", "provider", ContentType.LIVE, "TV"),
            "Programme", 1, 2, directory = "smb://nas.test/TV/Enregistrements")
        assertEquals("smb://nas.test/TV/Enregistrements/$id", IptvRecordingScheduler.recordingPath("smb://other.test/new", recording))
        assertEquals("smb://nas.test/TV/Enregistrements/$id", IptvRecordingScheduler.recordingPath("smb://other.test/new",
            recording.copy(directory = "", file = "smb://nas.test/TV/Enregistrements/$id/recording.m3u8")))
        assertEquals(recording, iptvJson.decodeFromString(IptvRecording.serializer(), iptvJson.encodeToString(IptvRecording.serializer(), recording)))
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.recordingPath("smb://nas.test/TV", recording.copy(id = "../other"))
        }
    }

    @Test fun systemRecordingDirectoriesKeepOpaqueDocumentIds() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ATV%2FJojo%20Home"
        assertTrue(IptvRecordingScheduler.isDocument(tree))
        assertEquals(tree, IptvRecordingScheduler.documentTreePath(" $tree "))
        assertEquals(tree, IptvRecordingScheduler.recordingDirectory(tree))
        assertEquals("content://provider.test/tree/opaque%2Bid%3A42", IptvRecordingScheduler.documentTreePath(
            "content://provider.test/tree/opaque%2Bid%3A42"))
        assertFalse(IptvRecordingScheduler.isNetwork(tree))
        assertFalse(IptvRecordingScheduler.isDocument("/storage/emulated/0/TV"))
    }

    @Test fun systemRecordingDirectoriesRejectFilesAndMalformedTreeUris() {
        listOf("content://provider.test/document/file", "content://provider.test/tree", "content:///tree/id",
            "content://provider.test/tree/id/document/child", "content://provider.test/tree/id?key=value",
            "content://provider.test/tree/id#fragment", "content://user@provider.test/tree/id", "https://provider.test/tree/id").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { IptvRecordingScheduler.documentTreePath(path) }
        }
    }

    @Test fun systemRecordingsPersistTheirDirectoryAndRealFileUri() {
        val recording = IptvRecording("12345678-1234-1234-1234-123456789abc",
            IptvItem("channel", "source", "provider", ContentType.LIVE, "TV"), "Programme", 1, 2,
            state = RecordingState.COMPLETE, directory = "content://provider.test/tree/selected-folder",
            file = "content://provider.test/tree/selected-folder/document/opaque-video-id")
        assertEquals(recording, iptvJson.decodeFromString(IptvRecording.serializer(), iptvJson.encodeToString(IptvRecording.serializer(), recording)))
        assertThrows(IllegalArgumentException::class.java) {
            IptvRecordingScheduler.recordingFolder(java.io.File("/tmp/other"), recording)
        }
    }

    @Test fun editingSystemRecordingRetainsItsOriginalTree() {
        val original = IptvRecording("12345678-1234-1234-1234-123456789abc",
            IptvItem("channel", "source", "provider", ContentType.LIVE, "TV"), "Programme", 60_000, 120_000,
            directory = "content://provider.test/tree/selected-folder")
        val edited = IptvRecordingScheduler.replanned(original, original.channel, "Autre programme", 180_000, 240_000, now = 0)
        assertEquals(original.id, edited.id)
        assertEquals(original.directory, edited.directory)
        assertEquals("", edited.file)
    }

    @Test fun recordingDirectoryNormalizationPreservesLocalAndSmbStorage() {
        assertEquals(java.io.File("/tmp/TV").canonicalPath, IptvRecordingScheduler.recordingDirectory("/tmp/TV"))
        assertEquals("smb://nas.test/TV/Jojo%20Home", IptvRecordingScheduler.recordingDirectory("smb://nas.test/TV/Jojo Home/"))
    }

    @Test fun smbFoldersRejectTraversalCredentialsAndNonStorageUrls() {
        assertEquals("smb://nas.test/TV/Jojo%20Home", IptvRecordingScheduler.smbPath("smb://nas.test/TV/Jojo Home/"))
        assertEquals("smb://nas.test", IptvRecordingScheduler.smbPath("smb://nas.test/", false))
        listOf("smb://nas.test", "smb://user:password@nas.test/TV", "https://nas.test/TV", "smb://nas.test/TV/../other",
            "smb://nas.test/TV/%2e%2e/other", "smb://nas.test/TV/a%2fb", "smb://nas.test/TV/a%5cb", "smb://nas.test/TV?key=value").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { IptvRecordingScheduler.smbPath(path) }
        }
    }

    @Test fun recordingConnectionsReuseHomeCredentialsUntilAnIptvOverrideIsSaved() {
        val home = SmbConnectionConfig("NAS", "nas.test/Disque dur", "home-user", "home-password")
        val cached = SmbConnectionConfig("SMB", "smb://NAS.test/Disque%20dur/", "old-user", "old-password")
        val other = SmbConnectionConfig("PC", "pc.test/TV", "pc-user", "pc-password")
        assertEquals(listOf(home, other), IptvRecordingScheduler.recordingConnections(listOf(home), listOf(cached, other)))
        assertEquals(listOf(cached, other), IptvRecordingScheduler.recordingConnections(listOf(home), listOf(cached, other), cached.browserUrl()))
    }

    @Test fun recordingCredentialsStayOnTheirShareWhenBrowsingParentAndSiblingFolders() {
        val server = SmbConnectionConfig("NAS", "nas.test", "server-user", "server-password")
        val share = SmbConnectionConfig("TV", "nas.test/Disque dur/Vidéos", "share-user", "share-password")
        val other = SmbConnectionConfig("Autre", "other.test/Disque dur", "other-user", "other-password")
        val connections = listOf(server, share, other)
        assertSame(share, IptvRecordingScheduler.connectionFor("smb://NAS.test/Disque%20dur", connections))
        assertSame(share, IptvRecordingScheduler.connectionFor("smb://nas.test/Disque dur/Enregistrements", connections))
        assertSame(share, IptvRecordingScheduler.connectionFor("smb://nas.test/Disque dur/Vidéos/Film", connections))
        assertSame(server, IptvRecordingScheduler.connectionFor("smb://nas.test", connections))
        assertSame(server, IptvRecordingScheduler.connectionFor("smb://nas.test/Disque dur bis/Film", connections))
        assertNull(IptvRecordingScheduler.connectionFor("smb://nas.test:1445/Disque dur/Film", connections))
        assertNull(IptvRecordingScheduler.connectionFor("smb://unknown.test/Disque dur/Film", connections))
        val plus = SmbConnectionConfig("NAS", "nas.test/TV+", "user", "password")
        assertSame(plus, IptvRecordingScheduler.connectionFor("smb://nas.test/TV%2B/Film", listOf(plus)))
        assertNull(IptvRecordingScheduler.connectionFor("smb://nas.test/TV /Film", listOf(plus)))
    }

    @Test fun savedProgramsKeepProviderAndScheduleIdentity() {
        val saved = IptvSavedProgram(IptvItem("channel", "source", "provider", ContentType.LIVE, "TV"), EpgProgram("Programme", "Description", 1000, 2000))
        assertEquals(saved, iptvJson.decodeFromString(IptvSavedProgram.serializer(), iptvJson.encodeToString(IptvSavedProgram.serializer(), saved)))
    }

    @Test fun programSearchHonorsReplayWindowAndPastVisibilityWithoutLosingCurrentPrograms() {
        val now = 10 * 86_400_000L
        val item = IptvItem("channel", "source", "provider", ContentType.LIVE, "TV")
        val current = EpgProgram("News", "", now - 1000, now + 1000)
        val yesterday = EpgProgram("NEWS", "", now - 86_400_000, now - 82_800_000)
        val expired = EpgProgram("News", "", now - 3 * 86_400_000, now - 3 * 86_400_000 + 1000)
        val other = EpgProgram("Film", "News", now, now + 1000)
        val programs = listOf(current, yesterday, expired, other, current)
        assertEquals(listOf(current), IptvStore.searchPrograms(item, programs, "news", now, 7, false))
        assertEquals(listOf(yesterday, current), IptvStore.searchPrograms(item.copy(catchupDays = 2), programs, "news", now, 7, false))
        assertEquals(listOf(expired, yesterday, current), IptvStore.searchPrograms(item, programs, "news", now, 7, true))
        assertTrue(IptvStore.searchPrograms(item, programs, " ", now, 7, true).isEmpty())
    }

    @Test fun channelNameEditorUsesOnlyLiteralEdgesAndPreservesProviderIdentity() {
        val settings = mapOf("channelNameEditor" to "true", "channelNamePrefixes" to "US:, UK:, ,",
            "channelNameSuffixes" to "| HD, | FHD")
        val item = IptvItem("id", "source", "provider", ContentType.LIVE, "uk: News | FHD")
        assertEquals("News", IptvOrganization.channelTitle(item.title, settings))
        assertEquals("uk: News | FHD", item.title)
        assertEquals("News UK: Special | HD edition", IptvOrganization.channelTitle("News UK: Special | HD edition", settings))
        assertEquals("UK:", IptvOrganization.channelTitle("UK:", settings))
        assertEquals(item.title, IptvOrganization.channelTitle(item.title, settings + ("channelNameEditor" to "false")))
        assertEquals(item.title, IptvOrganization.channelTitle(item.title, emptyMap()))
    }

    @Test fun guideSortsRespectGroupOverridesAndStableFavoritesPriority() {
        val a = IptvItem("a", "s", "1", ContentType.LIVE, "UK: Zeta")
        val b = a.copy(id = "b", title = "Alpha")
        val c = a.copy(id = "c", title = "US: Bravo")
        val values = listOf(a, b, c)
        val settings = mapOf("channelSort" to "added", "channelAdded:a" to "200", "channelAdded:b" to "100",
            "channelWatchTime:a" to "10", "channelWatchTime:c" to "20",
            "channelNameEditor" to "true", "channelNamePrefixes" to "UK:, US:")
        fun sorted(overrides: Map<String, String>) = IptvOrganization.items(values, settings + overrides, setOf("b"), "s", ContentType.LIVE, "g").map { it.id }
        assertEquals(listOf("a", "b", "c"), sorted(emptyMap()))
        assertEquals(listOf("c", "a", "b"), sorted(mapOf("channelSort" to "watchTime")))
        assertEquals(listOf("b", "c", "a"), sorted(mapOf("channelSort" to "name")))
        assertEquals(listOf("b", "c", "a"), sorted(mapOf("channelSort" to "watchTime", "favoritesFirst" to "true")))
        assertEquals(listOf("a", "b", "c"), sorted(mapOf("channelSort" to "watchTime",
            IptvOrganization.key("s", ContentType.LIVE, "g", "channelSort") to "playlist")))
        assertEquals(listOf("a", "b", "c"), sorted(mapOf("channelSort" to "watchTime",
            "channelWatchTime:a" to "0", "channelWatchTime:c" to "0")))
    }

    @Test fun watchClockExcludesPausedBufferedAndRepeatedPlayingEvents() {
        val clock = IptvWatchClock()
        assertEquals(0L, clock.update(false, 0))
        assertEquals(0L, clock.update(true, 100))
        assertEquals(0L, clock.update(true, 300))
        assertEquals(400L, clock.update(false, 500))
        assertEquals(0L, clock.update(false, 900))
        assertEquals(0L, clock.update(true, 1000))
        assertEquals(200L, clock.update(false, 1200))
        assertEquals(0L, clock.update(false, 1500))
    }

    @Test fun replayMarkerUsesProviderMetadataAndOldItemsRemainReadable() {
        val source = IptvSource("s", "List", SourceType.M3U, "https://example.test/list.m3u")
        val item = M3uParser.parse(java.io.StringReader("""#EXTM3U
            #EXTINF:-1 catchup-days="7",Replay
            https://media.example.test/channel.ts
        """.trimIndent()).buffered(), source).single()
        assertEquals(7, item.catchupDays)
        val legacy = iptvJson.decodeFromString(IptvItem.serializer(), """{"id":"x","sourceId":"s","providerId":"1","type":"LIVE","title":"Old"}""")
        assertEquals(0, legacy.catchupDays)
    }

    @Test fun groupSortUsesPlaylistOrderNamesAndScopedManualOverrides() {
        val groups = listOf(IptvCategory("b", "Beta"), IptvCategory("a", "alpha"),
            IptvCategory("c", "Beta"), IptvCategory("new", "Zeta"))
        val order = listOf("removed", "c", "b")
        val settings = mapOf("groupSort" to "name", IptvOrganization.key("manual", ContentType.LIVE, null, "groupSort") to "manual",
            IptvOrganization.key("playlist", ContentType.LIVE, null, "groupSort") to "playlist")
        fun sorted(source: String) = IptvOrganization.groups(groups,
            IptvOrganization.value(settings, source, ContentType.LIVE, null, "groupSort", "playlist"), order).map { it.id }
        assertEquals(listOf("a", "b", "c", "new"), sorted("default"))
        assertEquals(listOf("c", "b", "a", "new"), sorted("manual"))
        assertEquals(listOf("b", "a", "c", "new"), sorted("playlist"))
        assertEquals(listOf("new", "a", "b", "c"), IptvOrganization.groups(groups, "name", order) {
            if (it.id == "new") "Aaron" else it.name
        }.map { it.id })
    }

    @Test fun playlistSortUsesStableIdsAndKeepsNewSourcesInManualOrder() {
        fun source(id: String, name: String) = IptvSource(id, name, SourceType.M3U, "https://example.test/$id.m3u")
        val values = listOf(source("b", "Beta"), source("a", "alpha"), source("c", "Beta"), source("new", "Zeta"))
        assertEquals(listOf("a", "b", "c", "new"), IptvOrganization.sources(values, "name", emptyList()).map { it.id })
        assertEquals(listOf("c", "b", "a", "new"), IptvOrganization.sources(values, "manual", listOf("removed", "c", "b")).map { it.id })
        assertEquals(listOf("b", "a", "c", "new"), IptvOrganization.sources(values, "manual", emptyList()).map { it.id })
    }

    @Test fun oldPlaylistsIncludeAllContentAndNewChoicesSurviveSerialization() {
        val legacy = iptvJson.decodeFromString(IptvSource.serializer(),
            """{"id":"x","name":"Test","type":"M3U","url":"https://example.test/list.m3u"}""")
        assertTrue(legacy.includeLive); assertTrue(legacy.includeVod)
        val selected = legacy.copy(includeLive = false)
        val restored = iptvJson.decodeFromString(IptvSource.serializer(), iptvJson.encodeToString(IptvSource.serializer(), selected))
        assertEquals(selected, restored)
        assertFalse(restored.includes(ContentType.LIVE))
        assertTrue(restored.includes(ContentType.MOVIE)); assertTrue(restored.includes(ContentType.SERIES))
        assertTrue(restored.includes(ContentType.EPISODE))
        assertTrue(restored.copy(includeLive = true, includeVod = false).includes(ContentType.LIVE))
        assertFalse(restored.copy(includeVod = false).includes(ContentType.EPISODE))
    }

    @Test fun playlistActivationAndAutomaticUpdatesStayScopedToTheirSource() {
        val now = 100 * 3_600_000L
        val lastUpdate = now - 2 * 3_600_000L
        val settings = mapOf(IptvPlaylistSettings.key("a", "updateHours") to "2",
            IptvPlaylistSettings.key("b", "enabled") to "false",
            IptvPlaylistSettings.key("b", "updateOnStart") to "true")
        assertTrue(IptvPlaylistSettings.enabled(emptyMap(), "a"))
        assertFalse(IptvPlaylistSettings.enabled(settings, "b"))
        assertTrue(IptvPlaylistSettings.updateDue(settings, "a", lastUpdate, now, false))
        assertFalse(IptvPlaylistSettings.updateDue(settings, "a", lastUpdate + 1, now, false))
        assertFalse(IptvPlaylistSettings.updateDue(settings, "b", lastUpdate, now, true))
        assertFalse(IptvPlaylistSettings.updateDue(settings, "other", lastUpdate, now, true))
        assertFalse(IptvPlaylistSettings.updateDue(settings, "a", 0L, now, false))
        val manual = settings + (IptvPlaylistSettings.key("a", "updateHours") to "0")
        assertFalse(IptvPlaylistSettings.updateDue(manual, "a", 1L, now, false))
        val startup = manual + (IptvPlaylistSettings.key("a", "updateOnStart") to "true")
        assertTrue(IptvPlaylistSettings.updateDue(startup, "a", 0L, now, true))
        assertFalse(IptvPlaylistSettings.updateDue(startup, "a", lastUpdate, now, false))
        assertFalse(IptvPlaylistSettings.updateDue(settings, "a", now + 1, now, false))
    }

    @Test fun organizationOverridesStayWithinTheirProviderAndContentType() {
        val values = mapOf("channelSort" to "playlist",
            IptvOrganization.key("a", ContentType.LIVE, null, "channelSort") to "name",
            IptvOrganization.key("a", ContentType.LIVE, "group", "channelSort") to "manual")
        assertEquals("manual", IptvOrganization.value(values, "a", ContentType.LIVE, "group", "channelSort", "playlist"))
        assertEquals("name", IptvOrganization.value(values, "a", ContentType.MOVIE, "group", "channelSort", "playlist"))
        assertEquals("playlist", IptvOrganization.value(values, "b", ContentType.LIVE, "group", "channelSort", "playlist"))
        assertEquals(IptvOrganization.key("a", ContentType.SERIES, "group", "blocked"),
            IptvOrganization.key("a", ContentType.EPISODE, "group", "blocked"))
    }

    @Test fun newlyAddedGroupsFollowVisibilityPolicyWithoutOverridingExistingChoices() {
        assertFalse(IptvOrganization.groupHidden("known", "", "false", "[\"known\"]"))
        assertTrue(IptvOrganization.groupHidden("new", "", "false", "[\"known\"]"))
        assertFalse(IptvOrganization.groupHidden("new", "false", "false", "[\"known\"]"))
        assertTrue(IptvOrganization.groupHidden("known", "true", "true", "[\"known\"]"))
        assertFalse(IptvOrganization.groupHidden("new", "", "true", "[]"))
        val settings = mapOf(IptvOrganization.key("a", ContentType.LIVE, null, "newGroupsVisible:LIVE") to "false",
            IptvOrganization.key("a", ContentType.LIVE, null, "knownGroups:LIVE") to "[\"known\"]")
        val known = IptvItem("known", "a", "1", ContentType.LIVE, "TV", categoryId = "known")
        val hidden = known.copy(id = "new", categoryId = "new")
        val movie = hidden.copy(id = "movie", type = ContentType.MOVIE)
        val other = hidden.copy(id = "other", sourceId = "b")
        assertEquals(listOf(known, movie, other), IptvOrganization.items(listOf(known, hidden, movie, other), settings,
            emptySet(), "", ContentType.LIVE, ""))
    }

    @Test fun providerChannelOverridesHideAndRenameOnlyTheSelectedChannel() {
        val renamed = IptvItem("renamed", "a", "1", ContentType.LIVE, "Original", categoryId = "group")
        val hidden = renamed.copy(id = "hidden", title = "Hidden")
        val other = renamed.copy(id = "other", title = "Other")
        val settings = mapOf(
            IptvOrganization.channelHiddenKey(hidden) to "true",
            IptvOrganization.channelNameKey(renamed) to "Canal renommé",
        )

        val result = IptvOrganization.items(listOf(renamed, hidden, other), settings, emptySet(), "a", ContentType.LIVE, "group")

        assertEquals(listOf("renamed", "other"), result.map { it.id })
        assertEquals("Canal renommé", result.first { it.id == "renamed" }.title)
    }

    @Test fun individualChannelBlockingKeepsProviderAndGroupScopesIndependent() {
        val channel = IptvItem("channel", "a", "1", ContentType.LIVE, "TV", categoryId = "group")
        val blocked = mapOf(IptvOrganization.channelBlockedKey(channel) to "true")
        assertTrue(IptvOrganization.blocked(blocked, channel))
        assertTrue(IptvOrganization.playbackLocked(blocked, channel))
        assertFalse(IptvOrganization.blocked(blocked, channel.copy(id = "sibling")))
        assertFalse(IptvOrganization.blocked(blocked, channel.copy(sourceId = "b")))
        assertFalse(IptvOrganization.blocked(blocked, channel.copy(categoryId = "other")))
        val groupKey = IptvOrganization.key("a", ContentType.LIVE, "group", "blocked")
        assertTrue(IptvOrganization.blocked(blocked + (groupKey to "false"), channel))
        assertTrue(IptvOrganization.blocked(mapOf(groupKey to "true"), channel.copy(id = "sibling")))
        assertFalse(IptvOrganization.playbackLocked(blocked.mapValues { "false" }, channel))
    }

    @Test fun restoringChannelOverridesReturnsTheOriginalCatalogue() {
        val channel = IptvItem("channel", "a", "1", ContentType.LIVE, "Original", categoryId = "group")
        val changed = mapOf(IptvOrganization.channelHiddenKey(channel) to "true",
            IptvOrganization.channelNameKey(channel) to "Renamed")
        assertTrue(IptvOrganization.items(listOf(channel), changed, emptySet(), "a", ContentType.LIVE, "group").isEmpty())
        val restored = changed + (IptvOrganization.channelHiddenKey(channel) to "false") + (IptvOrganization.channelNameKey(channel) to "")
        assertEquals(listOf(channel), IptvOrganization.items(listOf(channel), restored, emptySet(), "a", ContentType.LIVE, "group"))
    }

    @Test fun guideMenuKeepsChannelNamesSeparateFromProgramCells() {
        assertFalse(IptvRemote.isProgramContext("channel", "channel", true))
        assertTrue(IptvRemote.isProgramContext("channel", "channel:program:123", true))
        assertFalse(IptvRemote.isProgramContext("channel", "channel:program:123", false))
        assertTrue(IptvRemoteAction.BLOCKING.available)
        assertTrue(IptvRemoteAction.VISIBILITY.available)
    }

    @Test fun channelListPagesBoundTheRenderedRowsAndKeepGlobalIndices() {
        assertEquals(IntRange.EMPTY, IptvOrganization.channelListPage(0, -1))
        assertEquals(0..0, IptvOrganization.channelListPage(1, 100))
        assertEquals(0..11, IptvOrganization.channelListPage(30, -1))
        assertEquals(0..11, IptvOrganization.channelListPage(30, 11))
        assertEquals(12..23, IptvOrganization.channelListPage(30, 12))
        assertEquals(24..29, IptvOrganization.channelListPage(30, 100))
        assertEquals(222216..222227, IptvOrganization.channelListPage(1_000_000, 222222))
        assertEquals(2147483640..2147483646, IptvOrganization.channelListPage(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun guideChannelNumbersAcceptDigitsAndSeparateTimedSequences() {
        assertEquals(0, IptvRemote.digit(7))
        assertEquals(9, IptvRemote.digit(16))
        assertNull(IptvRemote.digit(23))
        assertNull(IptvRemote.digit(6))
        val input = IptvChannelNumberInput()
        assertEquals(1, input.append(1, 100))
        assertEquals(12, input.append(2, 300))
        assertEquals(3, input.append(3, 2000))
        input.clear()
        assertEquals(4, input.append(4, 2100))
        assertEquals(5, input.append(5, 100))
        input.clear()
        repeat(10) { input.append(9, 100) }
        assertEquals(92, input.append(2, 200))
    }

    @Test fun playerNavigationKeepsOverlayAndPreviewModesDistinct() {
        listOf(IptvRemoteAction.CHANNELS_OVERLAY, IptvRemoteAction.GUIDE_OVERLAY, IptvRemoteAction.GROUPS_OVERLAY).forEach {
            assertEquals(true, IptvRemote.overlayMode(it))
            assertTrue(it.available)
        }
        listOf(IptvRemoteAction.CHANNELS_PREVIEW, IptvRemoteAction.GUIDE_PREVIEW, IptvRemoteAction.GROUPS_PREVIEW).forEach {
            assertEquals(false, IptvRemote.overlayMode(it))
            assertTrue(it.available)
        }
        assertNull(IptvRemote.overlayMode(IptvRemoteAction.INFO_NEXT))
        assertNull(IptvRemote.overlayMode(IptvRemoteAction.STOP))
        assertTrue(IptvRemoteAction.INFO_NEXT.available)
        assertTrue(IptvRemoteAction.INFO_PREVIOUS.available)
        val page = IptvRemote.player.first { it.id == "pageUp" }
        assertEquals(IptvRemoteAction.INFO_NEXT, IptvRemote.action(page, true, "INFO_NEXT"))
        assertEquals(IptvRemoteAction.NEXT, IptvRemote.action(page, true, null))
    }

    @Test fun renamedPlaybackRemembersProviderNameForFavoriteAndHistoryRestoration() {
        val channel = IptvItem("channel", "a", "1", ContentType.LIVE, "Original", categoryId = "group")
        val settings = mapOf(IptvOrganization.channelOriginalNameKey(channel) to channel.title,
            IptvOrganization.channelNameKey(channel) to "Renamed")
        val displayed = IptvOrganization.items(listOf(channel), settings, setOf(channel.id), "a", ContentType.LIVE, "group").single()
        assertEquals("Renamed", displayed.title)
        assertEquals(channel, IptvOrganization.originalItem(displayed, settings))
        assertEquals(channel.copy(title = "Updated provider name"), IptvOrganization.originalItem(channel.copy(title = "Updated provider name"), settings))
        val library = IptvLibrary().withAccount(IptvAccount("first", "First")).copy(settings = settings, favorites = setOf(channel.id))
            .withPlayback(displayed, "first", 100)
        assertEquals(listOf(channel), library.remembered)
        val restored = settings + (IptvOrganization.channelNameKey(channel) to "")
        assertEquals(listOf(channel), IptvOrganization.items(library.remembered, restored, library.favorites, "a", ContentType.LIVE, "group"))
    }

    @Test fun customOrderKeepsNewChannelsAndPreservesTheirPlaylistOrder() {
        assertEquals(listOf("c", "a", "b", "new1", "new2"),
            IptvOrganization.ordered(listOf("a", "b", "new1", "c", "new2"), listOf("removed", "c", "a")) { it })
        assertEquals(emptyList<String>(), IptvOrganization.ids("invalid"))
        assertEquals(listOf("c", "a"), IptvOrganization.ids("[\"c\",\"a\"]"))
    }

    @Test fun hiddenGroupsAndFavoriteFiltersApplyBeforeSorting() {
        fun channel(id: String, group: String, title: String) = IptvItem(id, "a", id, ContentType.LIVE, title, categoryId = group)
        val items = listOf(channel("z", "shown", "Zulu"), channel("a", "shown", "Alpha"), channel("h", "hidden", "Hidden"))
        val settings = mapOf(IptvOrganization.key("a", ContentType.LIVE, "hidden", "hidden") to "true",
            IptvOrganization.key("a", ContentType.LIVE, null, "channelSort") to "name")
        assertEquals(listOf("a", "z"), IptvOrganization.items(items, settings, emptySet(), "a", ContentType.LIVE, "").map { it.id })
        val showHidden = settings + (IptvOrganization.key("a", ContentType.LIVE, null, "showHiddenChannels") to "true")
        assertEquals(listOf("a", "h", "z"), IptvOrganization.items(items, showHidden, emptySet(), "a", ContentType.LIVE, "").map { it.id })
        val onlyFavorites = settings + (IptvOrganization.key("a", ContentType.LIVE, "shown", "favoritesOnly") to "true")
        assertEquals(listOf("z"), IptvOrganization.items(items.take(2), onlyFavorites, setOf("z"), "a", ContentType.LIVE, "shown").map { it.id })
    }

    @Test fun largeCataloguesFilterGroupsOnceWithoutSharingVisibilityAcrossProviders() {
        val items = List(12_000) { index ->
            IptvItem("$index", if (index % 4 in listOf(0, 2)) "a" else "b", "$index", ContentType.LIVE, "$index",
                categoryId = if (index % 4 < 2) "shared" else "other")
        }
        val hidden = mapOf(IptvOrganization.key("a", ContentType.LIVE, "shared", "hidden") to "true",
            IptvOrganization.key("b", ContentType.LIVE, "other", "hidden") to "true")
        var hiddenLookups = 0
        val settings = object : Map<String, String> by hidden {
            override fun get(key: String): String? {
                if (key.endsWith(":hidden")) hiddenLookups++
                return hidden[key]
            }
        }
        val visible = IptvOrganization.items(items, settings, emptySet(), "a", ContentType.LIVE, "")
        assertEquals(items.filterIndexed { index, _ -> index % 4 in listOf(1, 2) }.map { it.id }, visible.map { it.id })
        assertEquals(4, hiddenLookups)
        assertEquals(items, IptvOrganization.items(items, emptyMap(), emptySet(), "a", ContentType.LIVE, ""))
    }

    @Test fun digestKeepsExistingPersistentIdentifiers() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(""))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", digest("hello"))
        listOf("é テレビ", "provider:LIVE:group", "0123456789".repeat(100)).forEach { value ->
            val previous = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(previous, digest(value))
        }
    }

    @Test fun groupEpgOffsetAcceptsMinutesAndRejectsInvalidClockValues() {
        assertEquals(90, IptvOrganization.offsetMinutes("+1:30"))
        assertEquals(-45, IptvOrganization.offsetMinutes("−0:45"))
        assertEquals(720, IptvOrganization.offsetMinutes("12:00"))
        assertNull(IptvOrganization.offsetMinutes("12:01"))
        assertNull(IptvOrganization.offsetMinutes("0:60"))
        assertNull(IptvOrganization.offsetMinutes("1.5"))
        val values = mapOf("epgOffsetMinutes" to "60",
            IptvOrganization.key("a", ContentType.LIVE, "group", "epgOffsetMinutes") to "-45")
        assertEquals("-45", IptvOrganization.value(values, "a", ContentType.LIVE, "group", "epgOffsetMinutes", "0"))
        assertEquals("60", IptvOrganization.value(values, "a", ContentType.LIVE, "other", "epgOffsetMinutes", "0"))
    }

    @Test fun syncedLibraryRestoresGroupOrganizationOverrides() {
        val settings = mapOf(
            IptvOrganization.key("a", ContentType.LIVE, "group", "groupName") to "France",
            IptvOrganization.key("a", ContentType.LIVE, "group", "hidden") to "true",
            IptvOrganization.key("a", ContentType.LIVE, "group", "blocked") to "true",
            IptvOrganization.key("a", ContentType.LIVE, "group", "itemOrder") to "[\"b\",\"a\"]")
        val records = iptvSyncValues(IptvLibrary(settings = settings)).mapValues { IptvSyncRecord(100, "tv-a", it.value) }
        assertEquals(settings, mergeIptvLibrary(IptvLibrary(), records).settings)
    }

    @Test fun parentalSettingScopesAreOptInAndPlaybackProtectionRemainsEnabledByDefault() {
        listOf("lockSettings", "lockPlaylists", "lockEpg").forEach { assertFalse(IptvOrganization.pinScopeDefault(it)) }
        assertTrue(IptvOrganization.pinScopeDefault("lockPlayback"))
    }

    @Test fun channelsOnlyUnlockNeverUnlocksSettingsOrPinChanges() {
        assertTrue(IptvOrganization.pinUnlockApplies("lockPlayback", true))
        listOf("lockSettings", "lockPlaylists", "lockEpg", "changePin").forEach {
            assertFalse(IptvOrganization.pinUnlockApplies(it, true))
            assertTrue(IptvOrganization.pinUnlockApplies(it, false))
        }
    }

    @Test fun blockedSeriesGroupAlsoLocksEpisodesWithoutEnablingGlobalPlaybackLock() {
        val episode = IptvItem("episode", "a", "1", ContentType.EPISODE, "Épisode", categoryId = "group")
        val settings = mapOf("parentalEnabled" to "false", "lockPlayback" to "false",
            IptvOrganization.key("a", ContentType.SERIES, "group", "blocked") to "true")
        assertTrue(IptvOrganization.playbackLocked(settings, episode))
        assertFalse(IptvOrganization.playbackLocked(settings, episode.copy(categoryId = "other")))
        assertFalse(IptvOrganization.playbackLocked(settings, episode.copy(sourceId = "b")))
    }

    @Test fun syncedLibraryRestoresProvidersAndAllThreeHomeFavoriteTypes() {
        val source = IptvSource("stable-provider", "Fournisseur", SourceType.STALKER, "https://portal.example.test",
            mac = "00:00:00:00:00:01", headers = mapOf("User-Agent" to "Test"))
        val items = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE).map { type ->
            IptvItem(itemId(source.id, type, type.name), source.id, type.name, type, type.name,
                artwork = "https://portal.example.test/poster.png", description = "Description fournisseur")
        }
        val original = IptvLibrary(sources = listOf(source), remembered = items, favorites = items.map { it.id }.toSet(),
            settings = mapOf("bufferSize" to "small"), history = listOf(items.first().id), playedAt = mapOf(items.first().id to 200L))
        val records = iptvSyncValues(original).mapValues { IptvSyncRecord(100, "tv-a", it.value) }
        val restored = mergeIptvLibrary(IptvLibrary(), records)
        assertEquals(original.sources, restored.sources)
        assertEquals(original.favorites, restored.favorites)
        assertEquals(original.remembered, restored.remembered)
        assertEquals(original.settings, restored.settings)
        assertEquals(original.playedAt, restored.playedAt)
        assertEquals(restored, iptvRecoveryLibrary(IptvSyncSnapshot(records = records)))
        val projected = IptvStore.projectHomeFavorites(restored.remembered, restored.favorites, setOf(source.id))
        assertEquals(setOf("movies", "series", "live"), projected.values.map { iptvJson.parseToJsonElement(it).jsonObject.string("category") }.toSet())
    }

    @Test fun nasRecoveryRefusesEmptyDeletedOrUnsupportedSnapshots() {
        val source = IptvSource("source", "Fournisseur", SourceType.STALKER, "https://portal.example.test")
        val records = iptvSyncValues(IptvLibrary(sources = listOf(source))).mapValues { IptvSyncRecord(10, "tv-a", it.value) }
        listOf(
            IptvSyncSnapshot(),
            IptvSyncSnapshot(records = mapOf("source:${source.id}" to IptvSyncRecord(20, "tv-a"))),
            IptvSyncSnapshot(schema = 2, records = records)
        ).forEach { snapshot ->
            assertTrue(runCatching { iptvRecoveryLibrary(snapshot) }.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(records, iptvRecoveryLibrary(IptvSyncSnapshot(records = records)).syncRecords)
    }

    @Test fun automaticNasRecoveryLeavesReadableLibrariesAndUnrelatedFailuresUntouched() {
        var recoveries = 0
        assertEquals("readable", iptvReadWithRecovery({ "readable" }) { recoveries++ })
        val unrelated = IllegalStateException("Storage unavailable", java.io.IOException())
        assertSame(unrelated, runCatching {
            iptvReadWithRecovery({ throw unrelated }) { recoveries++ }
        }.exceptionOrNull())
        assertEquals(0, recoveries)
    }

    @Test fun automaticNasRecoveryRestoresEncryptedLibraryAndAllThreeHomeFolders() {
        val source = IptvSource("stable-provider", "Fournisseur", SourceType.STALKER, "https://portal.example.test")
        val items = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE).map { type ->
            IptvItem(itemId(source.id, type, type.name), source.id, type.name, type, type.name)
        }
        val original = IptvLibrary(sources = listOf(source), remembered = items, favorites = items.map { it.id }.toSet())
        val snapshot = IptvSyncSnapshot(records = iptvSyncValues(original).mapValues { IptvSyncRecord(100, "tv-a", it.value) })
        var encryptedLibrary = iptvEncryptStorage(iptvJson.encodeToString(original).toByteArray(), ::storageTestWrap).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val damaged = encryptedLibrary.copyOf()
        var archive: ByteArray? = null
        val operations = mutableListOf<String>()
        val recovered = iptvReadWithRecovery({
            operations += "read"
            try {
                iptvJson.decodeFromString<IptvLibrary>(iptvDecryptStorage(encryptedLibrary, ::storageTestUnwrap).toString(Charsets.UTF_8))
            } catch (problem: javax.crypto.AEADBadTagException) {
                throw IllegalStateException("Unreadable library", problem)
            }
        }) {
            operations += "recover"
            val restored = iptvRecoveryLibrary(snapshot)
            val replacement = iptvEncryptStorage(iptvJson.encodeToString(restored).toByteArray(), ::storageTestWrap)
            assertEquals(restored, iptvJson.decodeFromString<IptvLibrary>(
                iptvDecryptStorage(replacement, ::storageTestUnwrap).toString(Charsets.UTF_8)))
            archive = encryptedLibrary.copyOf()
            encryptedLibrary = replacement
        }
        assertEquals(listOf("read", "recover", "read"), operations)
        assertArrayEquals(damaged, archive)
        assertEquals(original.sources, recovered.sources)
        assertEquals(original.favorites, recovered.favorites)
        val projected = IptvStore.projectHomeFavorites(recovered.remembered, recovered.favorites, setOf(source.id))
        assertEquals(setOf("movies", "series", "live"), projected.values.map { iptvJson.parseToJsonElement(it).jsonObject.string("category") }.toSet())
    }

    @Test fun failedNasRecoveryPreservesStorageErrorAndNeverLoops() {
        val unreadable = IllegalStateException("Unreadable library", javax.crypto.AEADBadTagException())
        val unavailable = java.io.IOException("NAS unavailable")
        var reads = 0
        var recoveries = 0
        assertSame(unreadable, runCatching {
            iptvReadWithRecovery({ reads++; throw unreadable }) { recoveries++; throw unavailable }
        }.exceptionOrNull())
        assertEquals(1, reads)
        assertEquals(1, recoveries)
        assertArrayEquals(arrayOf<Throwable>(unavailable), unreadable.suppressed)

        reads = 0
        recoveries = 0
        assertSame(unreadable, runCatching {
            iptvReadWithRecovery({ reads++; throw unreadable }) { recoveries++ }
        }.exceptionOrNull())
        assertEquals(2, reads)
        assertEquals(1, recoveries)
    }

    @Test fun syncKeepsIndependentOfflineAdditionsAndDeletionTombstones() {
        val local = mapOf("favorite:a" to IptvSyncRecord(10, "tv-a", "true"), "favorite:b" to IptvSyncRecord(20, "tv-a", "true"))
        val remote = mapOf("favorite:a" to IptvSyncRecord(30, "tv-b"), "favorite:c" to IptvSyncRecord(25, "tv-b", "true"))
        val merged = mergeIptvSyncRecords(local, remote)
        assertNull(merged.getValue("favorite:a").value)
        assertEquals("true", merged.getValue("favorite:b").value)
        assertEquals("true", merged.getValue("favorite:c").value)
        assertEquals(merged, mergeIptvSyncRecords(merged, local))
        assertEquals(merged, mergeIptvSyncRecords(remote, local))
        assertEquals("true", mergeIptvSyncRecords(merged, mapOf("favorite:a" to IptvSyncRecord(40, "tv-a", "true"))).getValue("favorite:a").value)
    }

    @Test fun removedProviderCannotReturnWithAnOldFavoriteSnapshot() {
        val source = IptvSource("provider", "Fournisseur", SourceType.M3U, "https://example.test/list.m3u")
        val movie = IptvItem(itemId(source.id, ContentType.MOVIE, "1"), source.id, "1", ContentType.MOVIE, "Film")
        val old = iptvSyncValues(IptvLibrary(sources = listOf(source), remembered = listOf(movie), favorites = setOf(movie.id)))
            .mapValues { IptvSyncRecord(1, "legacy", it.value) }
        val current = mergeIptvLibrary(IptvLibrary(), old)
        val removed = mergeIptvLibrary(current, mapOf("source:${source.id}" to IptvSyncRecord(100, "tv-a")))
        val restored = mergeIptvLibrary(removed, old)
        assertTrue(restored.sources.isEmpty())
        assertTrue(restored.favorites.isEmpty())
        assertTrue(restored.remembered.isEmpty())
        assertNull(restored.syncRecords.getValue("source:${source.id}").value)
    }

    @Test fun equalSyncRevisionsConvergeRegardlessOfMergeOrder() {
        val a = mapOf("setting:bufferSize" to IptvSyncRecord(10, "tv-a", "small"))
        val b = mapOf("setting:bufferSize" to IptvSyncRecord(10, "tv-b", "large"))
        assertEquals(mergeIptvSyncRecords(a, b), mergeIptvSyncRecords(b, a))
        val removed = mapOf("setting:bufferSize" to IptvSyncRecord(10, "tv-b"))
        assertNull(mergeIptvSyncRecords(b, removed).getValue("setting:bufferSize").value)
        assertEquals(mergeIptvSyncRecords(b, removed), mergeIptvSyncRecords(removed, b))
    }

    @Test fun explicitRefavoriteRestoresMetadataEvenAfterAnotherTvPrunedItsHistory() {
        val source = IptvSource("provider", "Fournisseur", SourceType.XTREAM, "https://example.test")
        val movie = IptvItem(itemId(source.id, ContentType.MOVIE, "1"), source.id, "1", ContentType.MOVIE, "Film")
        val old = IptvLibrary(sources = listOf(source), remembered = listOf(movie), favorites = setOf(movie.id))
        val original = iptvSyncValues(old).mapValues { IptvSyncRecord(10, "tv-a", it.value) }
        val removed = mergeIptvLibrary(mergeIptvLibrary(IptvLibrary(), original),
            mapOf("favorite:${movie.id}" to IptvSyncRecord(20, "tv-a")))
        val restored = mergeIptvLibrary(removed, mapOf("favorite:${movie.id}" to
            IptvSyncRecord(30, "tv-b", iptvSyncValues(old).getValue("favorite:${movie.id}"))))
        assertEquals(setOf(movie.id), restored.favorites)
        assertEquals(listOf(movie), restored.remembered)
    }

    @Test fun oldEncryptedLibraryFormatRemainsReadableBeforeFirstSync() {
        val old = iptvJson.decodeFromString<IptvLibrary>("""{"sources":[],"favorites":[],"searches":["ancien"]}""")
        assertTrue(old.syncRecords.isEmpty())
        assertTrue(old.accounts.isEmpty())
        assertEquals("", old.selectedAccountId)
        assertTrue(old.accountStates.isEmpty())
        assertEquals("", old.legacyAccountId)
        assertEquals(listOf("ancien"), old.searches)
        val records = iptvSyncValues(old).mapValues { IptvSyncRecord(1, "legacy", it.value) }
        assertEquals(old.searches, mergeIptvLibrary(IptvLibrary(), records).searches)
    }

    @Test fun localAccountsValidateNamesAndKeepIdentityWhenEdited() {
        val added = IptvLibrary().withAccount(IptvAccount("first", "  Jojo  ", "😎"))
        assertEquals("Jojo", added.accounts.single().name)
        assertEquals("first", added.selectedAccountId)
        val second = added.withAccount(IptvAccount("second", "Invité"))
        val edited = second.withAccount(added.accounts.single().copy(name = "Arnau", avatar = "🐼"))
        assertEquals(listOf("first", "second"), edited.accounts.map { it.id })
        assertEquals(IptvAccount("first", "Arnau", "🐼"), edited.accounts.first())
        assertEquals("second", edited.selectedAccountId)
        assertThrows(IllegalStateException::class.java) { edited.withAccount(IptvAccount("third", "  ARNAU ")) }
        assertThrows(IllegalStateException::class.java) { edited.withAccount(IptvAccount("third", "  ")) }
        assertThrows(IllegalStateException::class.java) { edited.withAccount(IptvAccount("third", "x".repeat(41))) }
    }

    @Test fun deletingLocalAccountsKeepsIptvDataAndSelectsRemainingAccount() {
        val initial = IptvLibrary(favorites = setOf("film"), history = listOf("film"),
            settings = mapOf("themeAccent" to "home"), searches = listOf("recherche"))
            .withAccount(IptvAccount("first", "Jojo")).withAccount(IptvAccount("second", "Invité"))
        assertEquals("second", initial.withoutAccount("first").selectedAccountId)
        val deleted = initial.withoutAccount("second")
        assertEquals(listOf("first"), deleted.accounts.map { it.id })
        assertEquals("first", deleted.selectedAccountId)
        assertEquals(setOf("film"), deleted.favorites)
        assertEquals(listOf("film"), deleted.history)
        assertEquals(initial.settings, deleted.settings)
        assertEquals(listOf("recherche"), deleted.searches)
        val empty = deleted.withoutAccount("first")
        assertTrue(empty.accounts.isEmpty())
        assertEquals("", empty.selectedAccountId)
    }

    @Test fun accountsSurviveSerializationAndAdvertiseTheirNamesAndAvatarsToNas() {
        val local = IptvLibrary(settings = mapOf("themeAccent" to "home"))
            .withAccount(IptvAccount("local", "Jojo", "😎"))
        assertEquals(local, iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(local)))
        val values = iptvSyncValues(local)
        assertEquals("home", values["setting:themeAccent"])
        assertFalse(values.containsKey("account:local:setting:themeAccent"))
        assertEquals("local", values["legacyAccount"])
        assertEquals(local.accounts.single(), iptvJson.decodeFromString<IptvAccount>(values.getValue("profile:local")))
        val merged = mergeIptvLibrary(local, mapOf("setting:themeAccent" to IptvSyncRecord(10, "other-tv", "3")))
        assertEquals(local.accounts, merged.accounts)
        assertEquals(local.selectedAccountId, merged.selectedAccountId)
        assertEquals("3", merged.settings["themeAccent"])
    }

    @Test fun accountOrnamentsSurviveAvatarEditsAndNasSynchronization() {
        val legacy = iptvJson.decodeFromString<IptvAccount>("""{"id":"legacy","name":"Jojo","avatar":"first-avatar"}""")
        assertEquals(0xffd3a033.toInt(), legacy.ornamentColorValue())
        val local = IptvLibrary().withAccount(legacy.copy(ornamentColor = "#F06292"))
        val edited = local.withAccount(local.accounts.single().copy(name = "Jojo TV", avatar = "second-avatar"))
        val restored = iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(edited))
        assertEquals("#F06292", restored.accounts.single().ornamentColor)
        assertEquals(restored.accounts.single(), iptvJson.decodeFromString<IptvAccount>(iptvSyncValues(restored).getValue("profile:legacy")))
        val remote = restored.accounts.single().copy(ornamentColor = "#2196F3")
        val merged = mergeIptvLibrary(restored, mapOf("profile:legacy" to IptvSyncRecord(100, "nas", iptvJson.encodeToString(remote))))
        assertEquals("#2196F3", merged.accounts.single().ornamentColor)
        assertEquals("second-avatar", merged.accounts.single().avatar)
        assertEquals("#F06292", local.accounts.single().ornamentColor)
    }

    @Test fun editingAnotherAccountsOrnamentKeepsTheActiveAccountsPersonalData() {
        val local = accountSyncFixture().copy(history = listOf("live"), playedAt = mapOf("live" to 42L),
            searches = listOf("recherche invitée"), settings = accountSyncFixture().settings + ("savedPrograms" to "[programme]"))
        val account = local.accounts.first { it.id == "first" }
        val edited = local.withAccount(account.copy(ornamentColor = "#2196F3"))
        assertEquals("second", edited.selectedAccountId)
        assertEquals(local.favorites, edited.favorites)
        assertEquals(local.history, edited.history)
        assertEquals(local.playedAt, edited.playedAt)
        assertEquals(local.searches, edited.searches)
        assertEquals(local.settings, edited.settings)
        assertEquals("#2196F3", edited.accounts.first { it.id == "first" }.ornamentColor)
        assertEquals(local.accounts.first { it.id == "second" }, edited.accounts.first { it.id == "second" })
    }

    @Test fun successiveOrnamentAvatarAndNameEditsKeepTheSavedColorAfterReloadAndSync() {
        var local = accountSyncFixture()
        fun edit(change: (IptvAccount) -> IptvAccount) {
            local = local.withAccount(change(local.accounts.first { it.id == "first" }))
        }
        edit { it.copy(ornamentColor = "#2196F3") }
        edit { it.copy(ornamentColor = "#F06292") }
        edit { it.copy(avatar = "second-avatar") }
        edit { it.copy(name = "Arnaud TV") }
        val restored = iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(local))
        val records = accountSyncRecords(restored, 200)
        val merged = mergeIptvLibrary(mergeIptvLibrary(restored, records), accountSyncRecords(accountSyncFixture(), 100))
        assertEquals("second", merged.selectedAccountId)
        assertEquals(restored.accounts, merged.accounts)
        assertEquals("#F06292", merged.accounts.first { it.id == "first" }.ornamentColor)
        assertEquals("second-avatar", merged.accounts.first { it.id == "first" }.avatar)
        assertEquals("Arnaud TV", merged.accounts.first { it.id == "first" }.name)
    }

    private fun accountSyncFixture(): IptvLibrary {
        val source = IptvSource("provider", "Fournisseur", SourceType.XTREAM, "https://example.test")
        val movie = IptvItem("movie", source.id, "1", ContentType.MOVIE, "Film")
        val live = IptvItem("live", source.id, "2", ContentType.LIVE, "Chaîne")
        return IptvLibrary(sources = listOf(source), remembered = listOf(movie, live), favorites = setOf(movie.id),
            settings = mapOf("themeAccent" to "4")).withAccount(IptvAccount("first", "Arnaud", "😎"))
            .withAccount(IptvAccount("second", "Invité", "avatar-family"))
            .copy(favorites = setOf(live.id), settings = mapOf("themeAccent" to "8")).checkpointAccount()
    }

    private fun accountSyncRecords(value: IptvLibrary, at: Long = 100, writer: String = "tv-a") =
        iptvSyncValues(value).mapValues { IptvSyncRecord(at, writer, it.value) }

    @Test fun activityCheckpointRestoresFavoritesOfEveryTypeAfterRestart() {
        val base = accountSyncFixture().selectAccount("first")
        for (type in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)) {
            val item = IptvItem("new-${type.name}", "provider", "42", type, "Nouveau")
            val next = base.withFavorite(item, true, "first").checkpointAccount()
            val changes = iptvActivityChanges(base, next, 200, "tv")
            val checkpoint = next.activityCheckpoint(changes)
            val restored = base.restoreActivityCheckpoint(iptvJson.decodeFromString<IptvActivityCheckpoint>(iptvJson.encodeToString(checkpoint)))
            assertEquals(next.favorites, restored.favorites)
            assertEquals(next.accountStates, restored.accountStates)
            assertEquals(item, restored.remembered.first { it.id == item.id })
            assertEquals(changes, restored.syncRecords.filterKeys { it in changes })
            assertEquals(base.sources, restored.sources)
            assertEquals(base.settings, restored.settings)
        }
    }

    @Test fun activityCheckpointRestoresHistoryDeletionWithoutAffectingFavoritesOrOtherAccounts() {
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val other = movie.copy(id = "other", providerId = "2")
        val base = accountSyncFixture().withPlayback(movie, "first", 10).withPlayback(other, "first", 20)
            .withPlayback(movie, "second", 30).selectAccount("second")
        for (item in listOf(movie, null)) {
            val next = base.withoutHistory(item, "first").checkpointAccount()
            val restored = base.restoreActivityCheckpoint(next.activityCheckpoint(iptvActivityChanges(base, next, 200, "tv")))
            assertEquals(next.accountStates, restored.accountStates)
            assertEquals(base.history, restored.history)
            assertEquals(base.favorites, restored.favorites)
            assertEquals(base.playedAt, restored.playedAt)
        }
    }

    @Test fun activityJournalStaysSmallWithALargeProviderAndSettingsSnapshot() {
        val item = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val base = accountSyncFixture().copy(settings = (0..999).associate { "setting-$it" to "x".repeat(8192) },
            syncRecords = (0..999).associate { "setting:$it" to IptvSyncRecord(100, "old", "x".repeat(8192)) })
        val next = base.withFavorite(item, true, "second").checkpointAccount()
        val changes = iptvActivityChanges(base, next, 200, "tv")
        val checkpoint = next.activityCheckpoint(changes)
        val payload = iptvJson.encodeToString(checkpoint)
        assertTrue(payload.length < 10000)
        assertFalse(payload.contains("setting-999"))
        assertFalse(payload.contains("https://example.test"))
        assertTrue(checkpoint.accounts.values.all { it.settings.isEmpty() && it.searches.isEmpty() })
        assertEquals(changes, checkpoint.records)
    }

    @Test fun anOldActivityJournalCannotUndoACompletedFullSave() {
        val base = accountSyncFixture()
        val pending = base.withoutHistory(null, "second").activityCheckpoint(emptyMap())
        val saved = base.copy(activityRevision = base.activityRevision + 1)
        assertSame(saved, saved.restoreActivityCheckpoint(pending))
        val legacyJson = iptvJson.encodeToString(base)
        assertFalse(legacyJson.contains("activityRevision"))
        assertEquals(0L, iptvJson.decodeFromString<IptvLibrary>(legacyJson).activityRevision)
    }

    @Test fun activitySyncChangesExactlyMatchTheExistingNasProtocol() {
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val base = accountSyncFixture().withPlayback(movie, "first", 10).withPlayback(movie, "second", 20)
        val variants = listOf(base.withFavorite(movie, true, "second"), base.withFavorite(movie, false, "first"),
            base.withoutHistory(null, "first"), base.withoutHistory(movie, "second"), base.withPlayback(movie, "first", 30))
        for (next in variants) {
            val before = iptvSyncValues(base); val after = iptvSyncValues(next)
            val expected = (before.keys + after.keys).filter { before[it] != after[it] }
                .associateWith { IptvSyncRecord(200, "tv", after[it]) }
            assertEquals(expected, iptvActivityChanges(base, next, 200, "tv"))
        }
    }

    @Test fun activityJournalRetainsNasDeletionTombstonesOnRestartAndOldSnapshotReplay() {
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val base = accountSyncFixture().withPlayback(movie, "first", 10).selectAccount("first")
        val old = accountSyncRecords(base, 100)
        val initial = base.copy(syncRecords = old)
        val deleted = initial.withoutHistory(null, "first")
        val changes = iptvActivityChanges(initial, deleted, 200, "tv")
        assertNull(changes.getValue("play:movie").value)
        assertNull(changes.getValue("account:first:play:movie").value)
        val restored = initial.restoreActivityCheckpoint(deleted.activityCheckpoint(changes))
        val replayed = mergeIptvLibrary(restored, old)
        assertTrue(replayed.history.isEmpty())
        assertTrue(replayed.playedAt.isEmpty())
        assertEquals(initial.favorites, replayed.favorites)
    }

    @Test fun activityCheckpointDoesNotOverrideTheSelectedAccountSettingsOrSearches() {
        val base = accountSyncFixture().copy(searches = listOf("film"),
            settings = mapOf("themeAccent" to "8", "audioLanguage" to "fr")).checkpointAccount()
        val next = base.withoutHistory(null, "first")
        val restored = base.restoreActivityCheckpoint(next.activityCheckpoint(emptyMap()))
        assertEquals(base.selectedAccountId, restored.selectedAccountId)
        assertEquals(base.settings, restored.settings)
        assertEquals(base.searches, restored.searches)
        assertEquals(base.accountStates.mapValues { it.value.settings }, restored.accountStates.mapValues { it.value.settings })
    }

    @Test fun cloudActivityCheckpointDoesNotCreateNasActivityRecords() {
        val base = accountSyncFixture()
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val next = base.withFavorite(movie, true, "second")
        val restored = base.restoreActivityCheckpoint(next.activityCheckpoint(emptyMap()))
        assertEquals(next.favorites, restored.favorites)
        assertEquals(base.syncRecords, restored.syncRecords)
    }

    @Test fun activityJournalHandlesNasRepliesWithoutRewritingUnchangedProviderData() {
        val base = accountSyncFixture()
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val next = base.withFavorite(movie, true, "second").withPlayback(movie, "first", 100)
        assertTrue(base.onlyActivityChanged(next))
        assertTrue(next.onlyActivityChanged(next.copy(remembered = next.remembered.reversed())))
        val record = IptvSyncRecord(100, "tv")
        assertTrue(next.onlyActivityChanged(next.copy(syncRecords = next.syncRecords + ("play:movie" to record))))
        assertFalse(next.onlyActivityChanged(next.copy(syncRecords = next.syncRecords + ("setting:themeAccent" to record))))
        assertFalse(next.onlyActivityChanged(next.copy(settings = next.settings + ("themeAccent" to "9"))))
        assertFalse(next.onlyActivityChanged(next.copy(accounts = next.accounts.reversed())))
        assertFalse(next.onlyActivityChanged(next.copy(sources = emptyList())))
        val privateSettings = next.selectAccount("first").copy(settings = next.settings + ("audioLanguage" to "fr")).checkpointAccount()
        assertFalse(next.onlyActivityChanged(privateSettings))
    }

    @Test fun repeatedActivityCommandsCreateNoDuplicateNasRevisions() {
        val base = accountSyncFixture()
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val added = base.withFavorite(movie, true, "second")
        assertTrue(iptvActivityChanges(added, added.withFavorite(movie, true, "second"), 200, "tv").isEmpty())
        val deleted = added.withoutHistory(null, "first")
        assertTrue(iptvActivityChanges(deleted, deleted.withoutHistory(null, "first"), 200, "tv").isEmpty())
    }

    @Test fun repeatedFavoriteAdditionKeepsOneFavoriteAndItsMetadata() {
        val source = IptvSource("provider", "Fournisseur", SourceType.XTREAM, "https://example.test")
        val movie = IptvItem(itemId(source.id, ContentType.MOVIE, "1"), source.id, "1", ContentType.MOVIE, "Film")
        val initial = IptvLibrary(sources = listOf(source)).withAccount(IptvAccount("first", "Arnaud"))
        val once = initial.withFavorite(movie, true, "first").checkpointAccount()
        val twice = once.withFavorite(movie, true, "first").checkpointAccount()
        assertEquals(setOf(movie.id), twice.favorites)
        assertEquals(once, twice)
        assertEquals(listOf(movie), twice.remembered)
        assertEquals(setOf(movie.id), twice.accountStates.getValue("first").favorites)
        assertTrue(iptvSyncValues(twice).containsKey("account:first:favorite:${movie.id}"))
    }

    @Test fun seriesCanBeFavoritedBeforeItsEpisodesOrPlaybackUrlHaveLoaded() {
        val source = IptvSource("provider", "Fournisseur", SourceType.STALKER, "https://example.test/c/")
        val series = IptvItem(itemId(source.id, ContentType.SERIES, "42"), source.id, "42", ContentType.SERIES,
            "Série", artwork = "https://example.test/poster.jpg")
        val saved = IptvLibrary(sources = listOf(source)).withAccount(IptvAccount("first", "Arnaud"))
            .withFavorite(series, true, "first").checkpointAccount()
        val restored = iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(saved))
        assertEquals(setOf(series.id), restored.favorites)
        assertEquals(series, restored.remembered.single())
        assertEquals("", restored.remembered.single().streamUrl)
        assertEquals(setOf(series.id), IptvStore.projectHomeFavorites(restored.remembered,
            restored.favorites, setOf(source.id)).keys)
    }

    @Test fun queuedFavoriteAdditionKeepsTheAccountCapturedAtTheClick() {
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val switched = IptvLibrary().withAccount(IptvAccount("first", "Arnaud"))
            .withAccount(IptvAccount("second", "Invité")).selectAccount("second")
        val saved = switched.withFavorite(movie, true, "first").checkpointAccount()
        assertEquals("second", saved.selectedAccountId)
        assertTrue(saved.favorites.isEmpty())
        assertEquals(setOf(movie.id), saved.accountStates.getValue("first").favorites)
        assertTrue(saved.selectAccount("first").favorites.contains(movie.id))
    }

    @Test fun individualHistoryDeletionKeepsFavoritesAndAnIdenticalProviderIdElsewhere() {
        val movie = IptvItem(itemId("a", ContentType.MOVIE, "42"), "a", "42", ContentType.MOVIE, "Film")
        val other = movie.copy(id = itemId("b", ContentType.MOVIE, "42"), sourceId = "b")
        val initial = IptvLibrary().withAccount(IptvAccount("first", "Arnaud"))
            .withFavorite(movie, true, "first").withPlayback(movie, "first", 10).withPlayback(other, "first", 20)
        val deleted = initial.withoutHistory(movie, "first").checkpointAccount()
        assertEquals(listOf(other.id), deleted.history)
        assertEquals(mapOf(other.id to 20L), deleted.playedAt)
        assertEquals(initial.favorites, deleted.favorites)
        assertEquals(initial.remembered, deleted.remembered)
        assertEquals(deleted, deleted.withoutHistory(movie, "first").checkpointAccount())
    }

    @Test fun clearingHistoryCoversLiveMoviesAndEpisodesOnlyForItsAccount() {
        val movie = IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film")
        val live = movie.copy(id = "live", type = ContentType.LIVE)
        val episode = movie.copy(id = "episode", type = ContentType.EPISODE, seriesId = "7", season = 1, episode = 2)
        val initial = IptvLibrary().withAccount(IptvAccount("first", "Arnaud"))
            .withAccount(IptvAccount("second", "Invité")).withFavorite(movie, true, "first")
            .withPlayback(movie, "first", 10).withPlayback(live, "first", 20).withPlayback(episode, "first", 30)
            .withPlayback(movie, "second", 40).selectAccount("second")
        val deleted = initial.withoutHistory(null, "first").checkpointAccount()
        assertEquals(initial.history, deleted.history)
        assertEquals(initial.playedAt, deleted.playedAt)
        assertEquals(initial.accountStates.getValue("second"), deleted.accountStates.getValue("second"))
        val first = deleted.selectAccount("first")
        assertTrue(first.history.isEmpty())
        assertTrue(first.playedAt.isEmpty())
        assertEquals(setOf(movie.id), first.favorites)
    }

    @Test fun removingSeriesHistoryCoversItsSeasonsAndKeepsNeighbouringSeries() {
        val series = IptvItem(itemId("a", ContentType.SERIES, "42"), "a", "42", ContentType.SERIES, "Série")
        val first = series.copy(id = itemId("a", ContentType.EPISODE, "101"), type = ContentType.EPISODE,
            seriesId = "42", season = 1, episode = 1)
        val second = first.copy(id = itemId("a", ContentType.EPISODE, "202"), season = 2)
        val other = first.copy(id = itemId("a", ContentType.EPISODE, "303"), seriesId = "43")
        val initial = IptvLibrary().withAccount(IptvAccount("first", "Arnaud"))
            .withPlayback(first, "first", 10).withPlayback(second, "first", 20).withPlayback(other, "first", 30)
        val deleted = initial.withoutHistory(series, "first").checkpointAccount()
        assertEquals(listOf(other.id), deleted.history)
        assertEquals(mapOf(other.id to 30L), deleted.playedAt)
    }

    @Test fun deletedHistoryDoesNotReturnFromAnOlderNasSnapshot() {
        val original = accountSyncFixture().withPlayback(
            IptvItem("movie", "provider", "1", ContentType.MOVIE, "Film"), "first", 100).selectAccount("first")
        val records = accountSyncRecords(original, 100)
        val deleted = mergeIptvLibrary(original, mapOf(
            "play:movie" to IptvSyncRecord(200, "tv-b"),
            "account:first:play:movie" to IptvSyncRecord(200, "tv-b")
        ))
        val replayed = mergeIptvLibrary(deleted, records)
        assertTrue(replayed.accountStates.getValue("first").history.isEmpty())
        assertTrue(replayed.accountStates.getValue("first").playedAt.isEmpty())
        assertEquals(original.favorites, replayed.favorites)
        assertEquals(original.accountStates.getValue("second"), replayed.accountStates.getValue("second"))
        assertEquals("first", replayed.selectedAccountId)
    }

    @Test fun newTvImportsAllAccountsButEachTvKeepsItsOwnSelectedAccount() {
        val sender = accountSyncFixture()
        val records = accountSyncRecords(sender)
        val fresh = mergeIptvLibrary(IptvLibrary(), records)
        assertEquals(sender.accounts, fresh.accounts)
        assertEquals("first", fresh.selectedAccountId)
        assertEquals(setOf("movie"), fresh.favorites)
        assertEquals("8", fresh.settings["themeAccent"])
        val guestTv = fresh.selectAccount("second")
        val pulled = mergeIptvLibrary(guestTv, accountSyncRecords(sender.selectAccount("first"), 200))
        assertEquals("second", pulled.selectedAccountId)
        assertEquals(setOf("live"), pulled.favorites)
        assertEquals("8", pulled.settings["themeAccent"])
        assertEquals(pulled, mergeIptvLibrary(pulled, pulled.syncRecords))
    }

    @Test fun nasAccountRenameAndAvatarChangeKeepTheIdentityAndOtherTvSelection() {
        val local = mergeIptvLibrary(IptvLibrary(), accountSyncRecords(accountSyncFixture())).selectAccount("second")
        val edited = local.accounts.first { it.id == "first" }.copy(name = "Arnaud TV", avatar = "avatar-animal")
        val changed = mergeIptvLibrary(local, mapOf("profile:first" to
            IptvSyncRecord(200, "tv-b", iptvJson.encodeToString(edited))))
        assertEquals(edited, changed.accounts.first { it.id == "first" })
        assertEquals("second", changed.selectedAccountId)
        assertEquals(setOf("live"), changed.favorites)
        assertEquals(setOf("movie"), changed.selectAccount("first").favorites)
        val offline = mergeIptvLibrary(changed, accountSyncRecords(accountSyncFixture(), 100))
        assertEquals(edited, offline.accounts.first { it.id == "first" })
    }

    @Test fun deletedNasAccountDoesNotReturnFromAnOfflineTvAndOtherAccountsKeepTheirFavorites() {
        val old = accountSyncRecords(accountSyncFixture())
        val local = mergeIptvLibrary(IptvLibrary(), old)
        val deletion = mapOf("profile:first" to IptvSyncRecord(200, "tv-b"),
            "favorite:movie" to IptvSyncRecord(200, "tv-b"),
            "account:first:favorite:movie" to IptvSyncRecord(200, "tv-b"))
        val deleted = mergeIptvLibrary(local, deletion)
        assertEquals(listOf("second"), deleted.accounts.map { it.id })
        assertEquals("second", deleted.selectedAccountId)
        assertEquals(setOf("live"), deleted.favorites)
        assertEquals("first", deleted.legacyAccountId)
        val replayed = mergeIptvLibrary(deleted, old)
        assertEquals(deleted, replayed)
        val staleTv = mergeIptvLibrary(accountSyncFixture().selectAccount("first"), replayed.syncRecords)
        assertEquals(listOf("second"), staleTv.accounts.map { it.id })
        assertEquals(setOf("live"), staleTv.favorites)
        val empty = mergeIptvLibrary(replayed, mapOf("profile:second" to IptvSyncRecord(300, "tv-b")))
        assertTrue(empty.accounts.isEmpty())
        assertTrue(empty.favorites.isEmpty())
        assertEquals("", empty.selectedAccountId)
        assertTrue(empty.withAccount(IptvAccount("third", "Nouveau")).favorites.isEmpty())
    }

    @Test fun oldLibrarySeedsMissingAccountRecordsWithoutOverwritingKnownDeletionsOrFavoriteDates() {
        val old = accountSyncFixture().copy(syncRecords = mapOf(
            "favorite:movie" to IptvSyncRecord(50, "old-tv", iptvJson.encodeToString(accountSyncFixture().remembered.first())),
            "profile:second" to IptvSyncRecord(90, "tv-b")))
        val seeded = seedIptvSyncRecords(old)
        assertEquals(old.syncRecords.getValue("favorite:movie"), seeded.syncRecords["account:first:favorite:movie"])
        assertEquals(old.syncRecords.getValue("profile:second"), seeded.syncRecords["profile:second"])
        assertEquals(1L, seeded.syncRecords.getValue("profile:first").changedAt)
        assertEquals(seeded, seedIptvSyncRecords(seeded))
        val merged = mergeIptvLibrary(seeded, emptyMap())
        assertEquals(listOf("first"), merged.accounts.map { it.id })
        assertEquals(setOf("movie"), merged.favorites)
    }

    @Test fun sameNameAccountsFromOldTvsConvergeWithTheirFavoritesAndKeepAliasesAfterRename() {
        val template = accountSyncFixture()
        val first = IptvLibrary(sources = template.sources, remembered = template.remembered,
            favorites = setOf("movie"), settings = mapOf("themeAccent" to "4"))
            .withAccount(IptvAccount("a", "Arnaud", "😎"))
        val other = IptvLibrary(sources = template.sources, remembered = template.remembered,
            favorites = setOf("live"), settings = mapOf("themeAccent" to "8"))
            .withAccount(IptvAccount("b", "arnaud"))
        val merged = mergeIptvLibrary(first, seedIptvSyncRecords(other).syncRecords)
        val secondTv = mergeIptvLibrary(other, merged.syncRecords)
        assertEquals(listOf(IptvAccount("a", "Arnaud", "😎")), merged.accounts)
        assertEquals(merged.accounts, secondTv.accounts)
        assertEquals(setOf("movie", "live"), merged.favorites)
        assertEquals(merged.favorites, secondTv.favorites)
        assertEquals("a", secondTv.selectedAccountId)
        assertEquals("a", iptvAccountId(secondTv.syncRecords, "b"))
        assertEquals(merged, mergeIptvLibrary(merged, secondTv.syncRecords))
        val renamed = merged.accounts.single().copy(name = "Arnaud TV", avatar = "avatar-animal")
        val updated = mergeIptvLibrary(secondTv, mapOf("profile:a" to
            IptvSyncRecord(100, "tv-a", iptvJson.encodeToString(renamed))))
        val late = mergeIptvLibrary(updated, seedIptvSyncRecords(other).syncRecords +
            ("account:b:favorite:movie" to IptvSyncRecord(200, "tv-b")))
        assertEquals(listOf(renamed), late.accounts)
        assertEquals(setOf("live"), late.favorites)
        assertEquals("a", late.selectedAccountId)
        val deleted = mergeIptvLibrary(late, mapOf("profile:a" to IptvSyncRecord(300, "tv-a")))
        assertTrue(mergeIptvLibrary(other, deleted.syncRecords).accounts.isEmpty())
    }

    @Test fun differentFirstLocalAccountsKeepTheirDataAndCannotReplaceTheLegacyPrimaryAccount() {
        val template = accountSyncFixture()
        val first = IptvLibrary(sources = template.sources, remembered = template.remembered,
            favorites = setOf("movie"), settings = mapOf("themeAccent" to "4"))
            .withAccount(IptvAccount("a", "Arnaud"))
        val other = IptvLibrary(sources = template.sources, remembered = template.remembered,
            favorites = setOf("live"), settings = mapOf("themeAccent" to "8"))
            .withAccount(IptvAccount("b", "Invité"))
        val firstRecords = accountSyncRecords(first, 100, "tv-a")
        val secondRecords = accountSyncRecords(other, 200, "tv-b")
        val merged = mergeIptvLibrary(first.copy(syncRecords = firstRecords), secondRecords)
        assertEquals("a", merged.legacyAccountId)
        assertEquals("a", merged.selectedAccountId)
        assertEquals(setOf("movie"), merged.favorites)
        assertEquals("8", merged.settings["themeAccent"])
        val guest = merged.selectAccount("b")
        assertEquals(setOf("live"), guest.favorites)
        assertEquals("8", guest.settings["themeAccent"])
        val guestTv = mergeIptvLibrary(other.copy(syncRecords = secondRecords), firstRecords)
        assertEquals("b", guestTv.selectedAccountId)
        assertEquals("a", guestTv.legacyAccountId)
        assertEquals(guest.favorites, guestTv.favorites)
    }

    @Test fun previouslyUnknownOldTvFollowsAnAccountRenameAndCannotUndoItsDeletion() {
        val template = accountSyncFixture()
        val first = IptvLibrary(sources = template.sources, remembered = template.remembered, favorites = setOf("movie"))
            .withAccount(IptvAccount("first", "Arnaud"))
        val registered = mergeIptvLibrary(first, emptyMap())
        val renamed = IptvAccount("first", "Arnaud TV", "avatar-family")
        val updated = mergeIptvLibrary(registered, mapOf(
            "profile:first" to IptvSyncRecord(100, "tv-a", iptvJson.encodeToString(renamed)),
            "accountName:arnaud" to IptvSyncRecord(100, "tv-a", "first")))
        val oldTv = IptvLibrary(sources = template.sources, remembered = template.remembered, favorites = setOf("live"))
            .withAccount(IptvAccount("unseen", "Arnaud", "😎"))
        val joined = mergeIptvLibrary(oldTv, updated.syncRecords)
        assertEquals(listOf(renamed), joined.accounts)
        assertEquals("first", joined.selectedAccountId)
        assertEquals(setOf("movie", "live"), joined.favorites)
        val deleted = mergeIptvLibrary(joined, mapOf(
            "profile:first" to IptvSyncRecord(200, "tv-a"),
            "accountName:arnaud" to IptvSyncRecord(200, "tv-a", "first")))
        val late = mergeIptvLibrary(oldTv, deleted.syncRecords)
        assertTrue(late.accounts.isEmpty())
        assertTrue(late.favorites.isEmpty())
        val recreated = deleted.withAccount(IptvAccount("new", "Arnaud"))
        val pulled = mergeIptvLibrary(oldTv, deleted.syncRecords + accountSyncRecords(recreated, 300))
        assertEquals(listOf(IptvAccount("new", "Arnaud")), pulled.accounts)
        assertTrue(pulled.favorites.isEmpty())
    }

    @Test fun lateOldTvCanFillAnInitialAvatarButCannotReplaceAnExplicitAvatarEdit() {
        val template = accountSyncFixture()
        val first = IptvLibrary(sources = template.sources).withAccount(IptvAccount("first", "Arnaud"))
        val registered = mergeIptvLibrary(first, emptyMap())
        val other = IptvLibrary(sources = template.sources).withAccount(IptvAccount("old", "Arnaud", "😎"))
        val filled = mergeIptvLibrary(registered, seedIptvSyncRecords(other).syncRecords)
        assertEquals(listOf(IptvAccount("first", "Arnaud", "😎")), filled.accounts)
        val cleared = mergeIptvLibrary(filled, mapOf("profile:first" to
            IptvSyncRecord(100, "tv-a", iptvJson.encodeToString(IptvAccount("first", "Arnaud")))))
        assertEquals(listOf(IptvAccount("first", "Arnaud")), mergeIptvLibrary(cleared, seedIptvSyncRecords(other).syncRecords).accounts)
    }

    @Test fun nasStorageRecoveryRestoresProfilesAvatarsAndAllThreeHomeFolders() {
        val source = IptvSource("provider", "Fournisseur", SourceType.XTREAM, "https://example.test")
        val items = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE).map { type ->
            IptvItem(itemId(source.id, type, type.name), source.id, type.name, type, type.name)
        }
        val library = IptvLibrary(sources = listOf(source), remembered = items, favorites = items.map { it.id }.toSet())
            .withAccount(IptvAccount("first", "Arnaud", "😎"))
            .withAccount(IptvAccount("second", "Invité"))
        val restored = iptvRecoveryLibrary(IptvSyncSnapshot(records = accountSyncRecords(library)))
        assertEquals(library.accounts, restored.accounts)
        assertEquals("first", restored.selectedAccountId)
        assertEquals(items.map { it.id }.toSet(), restored.favorites)
        val folders = IptvStore.projectHomeFavorites(restored.remembered, restored.favorites, setOf(source.id))
        assertEquals(setOf("movies", "series", "live"), folders.values.map {
            iptvJson.parseToJsonElement(it).jsonObject.string("category")
        }.toSet())
    }

    @Test fun existingAccountsImportFavoritesIntoTheFirstAccountOnly() {
        val old = IptvLibrary(accounts = listOf(IptvAccount("first", "Jojo"), IptvAccount("second", "Invité")),
            selectedAccountId = "second", favorites = setOf("live", "movie", "series"),
            settings = mapOf("themeAccent" to "4", "savedPrograms" to "[programme]", "channelWatchTime:live" to "500"))
        val upgraded = old.initializeAccounts()
        assertEquals(old.favorites, upgraded.accountStates.getValue("first").favorites)
        assertEquals(old.settings - "themeAccent", upgraded.accountStates.getValue("first").settings)
        assertTrue(upgraded.favorites.isEmpty())
        assertEquals(mapOf("themeAccent" to "4"), upgraded.settings)
        assertEquals("first", upgraded.legacyAccountId)
        assertEquals(upgraded, upgraded.initializeAccounts())
        assertEquals(old.favorites, upgraded.selectAccount("first").favorites)
    }

    @Test fun newAccountsShareParametersButKeepTheirPersonalDataPrivate() {
        val first = IptvLibrary(favorites = setOf("live", "movie", "series"),
            settings = mapOf("themeAccent" to "4", "remote.guide.ok.short" to "WATCH", "savedPrograms" to "[programme]"))
            .withAccount(IptvAccount("first", "Jojo"))
        val second = first.withAccount(IptvAccount("second", "Invité"))
        assertTrue(second.favorites.isEmpty())
        assertEquals("4", second.settings["themeAccent"])
        assertEquals("WATCH", second.settings["remote.guide.ok.short"])
        assertFalse(second.settings.containsKey("savedPrograms"))
        val changed = second.copy(favorites = setOf("guest-film"), settings = second.settings + ("themeAccent" to "8"))
        val restored = changed.selectAccount("first")
        assertEquals(first.favorites, restored.favorites)
        assertEquals(first.settings + ("themeAccent" to "8"), restored.settings)
        assertEquals(setOf("guest-film"), restored.selectAccount("second").favorites)
        assertEquals("8", restored.selectAccount("second").settings["themeAccent"])
        val third = restored.selectAccount("second").withAccount(IptvAccount("third", "Autre"))
        assertTrue(third.favorites.isEmpty())
        assertEquals("8", third.settings["themeAccent"])
    }

    @Test fun localProfileSelectionSurvivesRestartWithoutRewritingTheLibrary() {
        val fixture = accountSyncFixture()
        val movie = fixture.remembered.first { it.id == "movie" }
        val live = fixture.remembered.first { it.id == "live" }
        val disk = fixture.withPlayback(movie, "first", 100)
            .withPlayback(live, "second", 200).selectAccount("first")
        val reopened = iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(disk))
            .restoreLocalAccount("second")
        assertEquals("first", disk.selectedAccountId)
        assertEquals("second", reopened.selectedAccountId)
        assertEquals(setOf(live.id), reopened.favorites)
        assertEquals(listOf(live.id), reopened.history)
        assertEquals(mapOf(live.id to 200L), reopened.playedAt)
        assertEquals(iptvSyncValues(disk), iptvSyncValues(reopened))
        assertEquals(listOf(movie.id), reopened.selectAccount("first").history)
    }

    @Test fun localProfileSelectionFollowsNasAccountAliases() {
        val disk = accountSyncFixture().selectAccount("first").copy(syncRecords = mapOf(
            "accountAlias:old-second" to IptvSyncRecord(100, "nas", "second")))
        val reopened = disk.restoreLocalAccount("old-second")
        assertEquals("second", reopened.selectedAccountId)
        assertEquals(setOf("live"), reopened.favorites)
        assertEquals(disk.syncRecords, reopened.syncRecords)
    }

    @Test fun removedLocalProfileSelectionRetainsTheAvailableProfile() {
        val remaining = accountSyncFixture().withoutAccount("second")
        assertEquals("first", remaining.restoreLocalAccount("second").selectedAccountId)
        assertEquals(remaining, remaining.restoreLocalAccount(""))
        assertEquals(remaining, remaining.restoreLocalAccount("unknown"))
    }

    @Test fun accountSwitchesKeepNasFavoritesPrivateAndParametersShared() {
        val source = IptvSource("provider", "Fournisseur", SourceType.XTREAM, "https://example.test")
        val movie = IptvItem("film", source.id, "1", ContentType.MOVIE, "Film")
        val first = IptvLibrary(sources = listOf(source), remembered = listOf(movie), favorites = setOf(movie.id),
            settings = mapOf("themeAccent" to "4")).withAccount(IptvAccount("first", "Jojo"))
        val second = first.withAccount(IptvAccount("second", "Invité"))
        assertEquals(iptvSyncValues(second), iptvSyncValues(second.selectAccount("first")))
        assertTrue(iptvSyncValues(second).containsKey("favorite:film"))
        assertFalse(iptvSyncValues(second).containsKey("account:second:favorite:film"))
        val changed = second.copy(favorites = setOf(movie.id), settings = mapOf("themeAccent" to "8")).checkpointAccount()
        val records = iptvSyncValues(changed).mapValues { IptvSyncRecord(10, "tv", it.value) }
        val merged = mergeIptvLibrary(second, records)
        assertEquals(setOf(movie.id), merged.favorites)
        assertEquals("8", merged.settings["themeAccent"])
        assertEquals("8", merged.selectAccount("first").settings["themeAccent"])
        val unfavorited = mergeIptvLibrary(merged, mapOf("account:second:favorite:film" to IptvSyncRecord(20, "tv")))
        assertTrue(unfavorited.favorites.isEmpty())
        assertEquals(setOf(movie.id), unfavorited.selectAccount("first").favorites)
        assertEquals(listOf(movie), unfavorited.remembered)
    }

    @Test fun switchingProfilesSeparatesAllPlaybackTypesSearchesAndSavedPrograms() {
        val source = IptvSource("provider", "Provider", SourceType.XTREAM, "https://example.test")
        val items = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.EPISODE).map { type ->
            IptvItem(itemId(source.id, type, "1"), source.id, "1", type, type.name)
        }
        var first = IptvLibrary(sources = listOf(source), favorites = setOf(items.first().id),
            settings = mapOf("themeAccent" to "4", "savedPrograms" to "[first]",
                "channelWatchTime:${items.first().id}" to "1000"))
            .withAccount(IptvAccount("first", "Arnaud"))
        items.forEachIndexed { index, item -> first = first.withPlayback(item, "first", 100L + index) }
        first = first.copy(searches = listOf("first search")).checkpointAccount()
        val empty = first.withAccount(IptvAccount("second", "Stephanie"))
        assertTrue(empty.history.isEmpty())
        assertTrue(empty.playedAt.isEmpty())
        assertTrue(empty.favorites.isEmpty())
        assertTrue(empty.searches.isEmpty())
        assertFalse(empty.settings.containsKey("savedPrograms"))
        assertFalse(empty.settings.containsKey("channelWatchTime:${items.first().id}"))
        assertEquals(first.sources, empty.sources)
        assertEquals("4", empty.settings["themeAccent"])
        var second = empty.copy(favorites = setOf(items.last().id), searches = listOf("second search"),
            settings = empty.settings + mapOf("themeAccent" to "8", "savedPrograms" to "[second]"))
        items.forEachIndexed { index, item -> second = second.withPlayback(item, "second", 200L + index) }
        val restored = second.selectAccount("first")
        assertEquals(first.history, restored.history)
        assertEquals(first.playedAt, restored.playedAt)
        assertEquals(first.favorites, restored.favorites)
        assertEquals(first.searches, restored.searches)
        assertEquals("[first]", restored.settings["savedPrograms"])
        assertEquals("1000", restored.settings["channelWatchTime:${items.first().id}"])
        assertEquals("8", restored.settings["themeAccent"])
        val back = restored.selectAccount("second")
        assertEquals(second.history, back.history)
        assertEquals(second.playedAt, back.playedAt)
        assertEquals(second.searches, back.searches)
        assertEquals("[second]", back.settings["savedPrograms"])
        assertEquals(setOf(items.last().id), back.favorites)
        assertEquals(back, iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(back)))
    }

    @Test fun delayedPlaybackStaysOnItsOriginalProfileAndCannotRecreateADeletedProfile() {
        val source = IptvSource("provider", "Provider", SourceType.M3U, "https://example.test")
        val item = IptvItem(itemId(source.id, ContentType.LIVE, "1"), source.id, "1", ContentType.LIVE, "Channel")
        val second = IptvLibrary(sources = listOf(source)).withAccount(IptvAccount("first", "Arnaud"))
            .withAccount(IptvAccount("second", "Stephanie"))
        val delayed = second.withPlayback(item, "first", 123)
        assertEquals("second", delayed.selectedAccountId)
        assertTrue(delayed.history.isEmpty())
        assertTrue(delayed.playedAt.isEmpty())
        val first = delayed.selectAccount("first")
        assertEquals(listOf(item.id), first.history)
        assertEquals(mapOf(item.id to 123L), first.playedAt)
        assertEquals(item, first.remembered.single())
        val deleted = delayed.withoutAccount("first")
        assertEquals(deleted, deleted.withPlayback(item, "first", 456))
    }

    @Test fun upgradingExistingProfilesAssignsUnownedHistoryOnlyToTheOriginalProfile() {
        val old = IptvLibrary(accounts = listOf(IptvAccount("first", "Arnaud"), IptvAccount("second", "Stephanie")),
            legacyAccountId = "first", selectedAccountId = "second",
            accountStates = mapOf("first" to IptvAccountState(), "second" to IptvAccountState()),
            history = listOf("live", "movie", "episode"), playedAt = mapOf("live" to 100L, "movie" to 200L, "episode" to 300L),
            searches = listOf("old search"), settings = mapOf("themeAccent" to "4"))
        val upgraded = old.initializeAccounts()
        assertEquals("second", upgraded.selectedAccountId)
        assertTrue(upgraded.history.isEmpty())
        assertTrue(upgraded.playedAt.isEmpty())
        assertTrue(upgraded.searches.isEmpty())
        val first = upgraded.selectAccount("first")
        assertEquals(old.history, first.history)
        assertEquals(old.playedAt, first.playedAt)
        assertEquals(old.searches, first.searches)
        assertEquals(upgraded, upgraded.initializeAccounts())
        assertEquals(first, iptvJson.decodeFromString<IptvLibrary>(iptvJson.encodeToString(first)).initializeAccounts())
    }

    @Test fun nasRestoresEachProfilesHistoryAndSearchesAndKeepsDeletionsPrivate() {
        val source = IptvSource("provider", "Provider", SourceType.XTREAM, "https://example.test")
        val items = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.EPISODE).map { type ->
            IptvItem(itemId(source.id, type, "1"), source.id, "1", type, type.name)
        }
        var sender = IptvLibrary(sources = listOf(source), settings = mapOf("themeAccent" to "8"))
            .withAccount(IptvAccount("first", "Arnaud"))
        items.forEachIndexed { index, item -> sender = sender.withPlayback(item, "first", 100L + index) }
        sender = sender.copy(searches = listOf("Arnaud search")).withAccount(IptvAccount("second", "Stephanie"))
        sender = sender.withPlayback(items.first(), "second", 500).copy(searches = listOf("Stephanie search")).checkpointAccount()
        val values = iptvSyncValues(sender)
        assertEquals(values, iptvSyncValues(sender.selectAccount("first")))
        assertEquals(100L, iptvJson.decodeFromString<IptvSyncPlayback>(values.getValue("play:${items.first().id}")).playedAt)
        assertEquals(500L, iptvJson.decodeFromString<IptvSyncPlayback>(values.getValue("account:second:play:${items.first().id}")).playedAt)
        val restored = iptvRecoveryLibrary(IptvSyncSnapshot(records = accountSyncRecords(sender)))
        assertEquals(sender.selectAccount("first").history, restored.history)
        assertEquals(sender.selectAccount("first").playedAt, restored.playedAt)
        assertEquals(listOf("Arnaud search"), restored.searches)
        val second = restored.selectAccount("second")
        assertEquals(listOf(items.first().id), second.history)
        assertEquals(mapOf(items.first().id to 500L), second.playedAt)
        assertEquals(listOf("Stephanie search"), second.searches)
        assertEquals("8", second.settings["themeAccent"])
        val deleted = mergeIptvLibrary(second, mapOf(
            "account:second:play:${items.first().id}" to IptvSyncRecord(200, "tv-b"),
            "account:second:searches" to IptvSyncRecord(200, "tv-b")))
        assertTrue(deleted.history.isEmpty())
        assertTrue(deleted.playedAt.isEmpty())
        assertTrue(deleted.searches.isEmpty())
        assertEquals(restored.history, deleted.selectAccount("first").history)
        assertEquals(restored.playedAt, deleted.selectAccount("first").playedAt)
        assertEquals(restored.searches, deleted.selectAccount("first").searches)
        assertEquals(deleted, mergeIptvLibrary(deleted, accountSyncRecords(sender)))
    }

    @Test fun oldSharedNasPlaybackIsImportedOnlyIntoTheOriginalProfile() {
        val template = accountSyncFixture()
        val item = template.remembered.first()
        val legacy = mapOf("play:${item.id}" to IptvSyncRecord(200, "old-tv",
            iptvJson.encodeToString(IptvSyncPlayback(item, 1234))),
            "searches" to IptvSyncRecord(200, "old-tv", iptvJson.encodeToString(listOf("old search"))))
        val merged = mergeIptvLibrary(template, legacy)
        assertEquals("second", merged.selectedAccountId)
        assertTrue(merged.history.isEmpty())
        assertTrue(merged.searches.isEmpty())
        assertEquals(listOf(item.id), merged.selectAccount("first").history)
        assertEquals(mapOf(item.id to 1234L), merged.selectAccount("first").playedAt)
        assertEquals(listOf("old search"), merged.selectAccount("first").searches)
    }

    @Test fun removingAProviderPrunesEveryProfilesHistoryWithoutDroppingOtherProviders() {
        val source = IptvSource("provider", "Provider", SourceType.M3U, "https://example.test")
        val other = source.copy(id = "other")
        val item = IptvItem(itemId(source.id, ContentType.MOVIE, "1"), source.id, "1", ContentType.MOVIE, "Movie")
        val retained = item.copy(id = itemId(other.id, ContentType.MOVIE, "1"), sourceId = other.id)
        val both = IptvLibrary(sources = listOf(source, other)).withAccount(IptvAccount("first", "Arnaud"))
            .withPlayback(item, "first", 100).withPlayback(retained, "first", 200)
            .withAccount(IptvAccount("second", "Stephanie")).withPlayback(item, "second", 300)
        val removed = mergeIptvLibrary(both, mapOf("source:${source.id}" to IptvSyncRecord(1000, "tv")))
        assertTrue(removed.history.isEmpty())
        assertEquals(listOf(retained.id), removed.selectAccount("first").history)
        assertEquals(mapOf(retained.id to 200L), removed.selectAccount("first").playedAt)
        assertEquals(listOf(retained), removed.remembered)
    }

    @Test fun ordinaryParametersFromOlderProfilesConvergeToOneSharedValue() {
        val local = accountSyncFixture()
        val upgraded = mergeIptvLibrary(local, mapOf(
            "account:first:setting:themeAccent" to IptvSyncRecord(100, "old-tv", "3"),
            "account:second:setting:themeAccent" to IptvSyncRecord(200, "old-tv", "7")))
        assertEquals("7", upgraded.settings["themeAccent"])
        assertEquals("7", upgraded.selectAccount("first").settings["themeAccent"])
        assertTrue(upgraded.accountStates.values.none { it.settings.containsKey("themeAccent") })
        assertEquals("7", iptvSyncValues(upgraded)["setting:themeAccent"])
        val updated = mergeIptvLibrary(upgraded, mapOf("setting:themeAccent" to IptvSyncRecord(300, "new-tv", "8")))
        assertEquals("8", updated.selectAccount("first").settings["themeAccent"])
        assertEquals("8", updated.selectAccount("second").settings["themeAccent"])
        val deleted = mergeIptvLibrary(updated, mapOf("profile:second" to IptvSyncRecord(400, "nas"),
            "account:second:setting:themeAccent" to IptvSyncRecord(400, "nas")))
        assertEquals("8", deleted.settings["themeAccent"])
        assertEquals(listOf("first"), deleted.accounts.map { it.id })
    }

    @Test fun playbackScopeKeepsAccountFilteringStableDuringSwitchesAndResolvesAliases() {
        val first = accountSyncFixture().selectAccount("first").copy(syncRecords = mapOf(
            "accountAlias:old-second" to IptvSyncRecord(100, "nas", "second")))
        val firstScope = first.playbackScope()
        val secondScope = first.selectAccount("second").playbackScope()
        val movie = "iptv:provider:movie:1"
        val guestMovie = iptvPlaybackContentId(movie, "second", "first")
        val aliasedMovie = iptvPlaybackContentId(movie, "old-second", "first")
        assertTrue(firstScope.owns(movie))
        assertFalse(firstScope.owns(guestMovie))
        assertFalse(firstScope.owns(aliasedMovie))
        assertFalse(secondScope.owns(movie))
        assertTrue(secondScope.owns(guestMovie))
        assertTrue(secondScope.owns(aliasedMovie))
        assertTrue(firstScope.owns("freebox:video"))
        assertTrue(secondScope.owns("external:video"))
        assertNotEquals(firstScope, secondScope)
        // A previously captured selection remains immutable after the switch.
        assertTrue(firstScope.owns(movie))
    }

    @Test fun vodProgressKeepsCompletedAndInProgressEpisodesFromNas() {
        val id = itemId("provider", ContentType.SERIES, "tyson")
        val first = com.nuvio.tv.domain.model.WatchProgress(id, "series", "Tyson", null, null, null,
            "episode1", 1, 1, "Episode 1", 93, 100, 10)
        val second = first.copy(videoId = "episode2", episode = 2, position = 25, lastWatched = 20)
        val scope = IptvPlaybackScope("arnaud", "arnaud", emptyMap())
        val progress = scope.progressFor(id, "series", listOf(first, second))
        assertEquals(2, progress.size)
        assertTrue(progress.single { it.episode == 1 }.isCompleted())
        assertFalse(progress.single { it.episode == 2 }.isCompleted())
        assertEquals(25L, progress.single { it.episode == 2 }.position)
    }

    @Test fun vodProgressUsesNewestEpisodeEntryAndHonorsReset() {
        val id = itemId("provider", ContentType.SERIES, "tyson")
        val completed = com.nuvio.tv.domain.model.WatchProgress(id, "series", "Tyson", null, null, null,
            "episode1", 1, 1, "Episode 1", 100, 100, 10, progressPercent = 100f)
        val reset = completed.copy(position = 0, progressPercent = 0f, lastWatched = 20)
        val scope = IptvPlaybackScope("arnaud", "arnaud", emptyMap())
        assertEquals(listOf(reset), scope.progressFor(id, "series", listOf(reset, completed, completed)))
        assertFalse(scope.progressFor(id, "series", listOf(completed, reset)).single().isCompleted())
    }

    @Test fun vodProgressResolvesAliasesWithoutSharingAnotherAccountsProgress() {
        val id = itemId("provider", ContentType.SERIES, "tyson")
        val alias = com.nuvio.tv.domain.model.WatchProgress(iptvPlaybackContentId(id, "old-arnaud", "legacy"),
            "series", "Tyson", null, null, null, "episode2", 1, 2, "Episode 2", 25, 100, 10)
        val guest = alias.copy(contentId = iptvPlaybackContentId(id, "guest", "legacy"), position = 80, lastWatched = 20)
        val legacy = alias.copy(contentId = id, position = 90, lastWatched = 30)
        val scope = IptvPlaybackScope("arnaud", "legacy", mapOf(
            "accountAlias:old-arnaud" to IptvSyncRecord(1, "nas", "arnaud")))
        assertEquals(listOf(alias), scope.progressFor(id, "series", listOf(alias, guest, legacy)))
        assertEquals(listOf(guest), scope.copy(selectedAccountId = "guest")
            .progressFor(id, "series", listOf(alias, guest, legacy)))
        assertEquals("old-arnaud", iptvPlaybackAccount(alias.contentId, "legacy"))
    }

    @Test fun vodProgressSeparatesProvidersAndContentTypes() {
        val id = itemId("provider", ContentType.SERIES, "tyson")
        val saved = com.nuvio.tv.domain.model.WatchProgress(id, "series", "Tyson", null, null, null,
            "episode2", 1, 2, "Episode 2", 25, 100, 10)
        val otherProvider = saved.copy(contentId = itemId("other", ContentType.SERIES, "tyson"), lastWatched = 20)
        val movie = saved.copy(contentType = "movie", lastWatched = 30)
        val scope = IptvPlaybackScope("arnaud", "arnaud", emptyMap())
        assertEquals(listOf(saved), scope.progressFor(id, "series", listOf(saved, otherProvider, movie)))
    }

    @Test fun vodProgressPreservesSyncedCompletionWithoutPositionOrDuration() {
        val id = itemId("provider", ContentType.SERIES, "tyson")
        val completed = com.nuvio.tv.domain.model.WatchProgress(id, "series", "Tyson", null, null, null,
            "episode1", 1, 1, "Episode 1", 0, 0, 10, progressPercent = 100f)
        val scope = IptvPlaybackScope("arnaud", "arnaud", emptyMap())
        assertTrue(scope.progressFor(id, "series", listOf(completed)).single().isCompleted())
    }

    @Test fun privatePlaybackIdsKeepProviderIdentityAndSeparateEpisodeProgress() {
        val series = IptvItem(itemId("provider", ContentType.SERIES, "7"), "provider", "7", ContentType.SERIES, "Series")
        val episode = IptvItem(itemId("provider", ContentType.EPISODE, "10"), "provider", "10", ContentType.EPISODE,
            "Episode", season = 1, episode = 1, seriesId = "7")
        val firstId = iptvPlaybackContentId(series.id, "first", "first")
        val secondId = iptvPlaybackContentId(series.id, "second", "first")
        assertEquals(series.id, firstId)
        assertNotEquals(firstId, secondId)
        assertEquals(series.id, iptvCatalogContentId(secondId))
        assertEquals("first", iptvPlaybackAccount(firstId, "first"))
        assertEquals("second", iptvPlaybackAccount(secondId, "first"))
        assertEquals("freebox:movie", iptvPlaybackContentId("freebox:movie", "second", "first"))
        val firstProgress = com.nuvio.tv.domain.model.WatchProgress(firstId, "series", series.title, null, null, null,
            episode.id, 1, 1, episode.title, 1200, 1200, 100)
        val secondProgress = firstProgress.copy(contentId = secondId, position = 300, lastWatched = 200)
        fun metadata(id: String) = iptvJson.parseToJsonElement(IptvStore.homeSeriesPayload(series, listOf(episode),
            listOf(firstProgress, secondProgress), id)).jsonObject.getValue("seasons").jsonArray
            .single().jsonObject.getValue("episodes").jsonArray.single().jsonObject
        assertEquals("true", metadata(firstId).string("watched"))
        assertEquals(1200, metadata(firstId).number("position"))
        assertEquals("false", metadata(secondId).string("watched"))
        assertEquals(300, metadata(secondId).number("position"))
        assertEquals(episode.id, metadata(secondId).string("id"))
        assertEquals(series.id, metadata(secondId).string("iptvSeriesId"))
        assertEquals(firstProgress, IptvStore.homeFavoritePlaybackProgress(firstId, "series", listOf(firstProgress, secondProgress)))
        assertEquals(secondProgress, IptvStore.homeFavoritePlaybackProgress(secondId, "series", listOf(firstProgress, secondProgress)))
    }

    @Test fun recordingAndReminderOwnershipSurvivesStorageWithoutChangingLegacyFiles() {
        val channel = IptvItem(itemId("provider", ContentType.LIVE, "1"), "provider", "1", ContentType.LIVE, "Channel")
        val recording = IptvRecording("recording", channel, "Program", 100, 200, accountId = "first")
        val reminder = IptvReminder("reminder", channel, "Program", 90, 100, 200, accountId = "second")
        assertEquals(recording, iptvJson.decodeFromString<IptvRecording>(iptvJson.encodeToString(recording)))
        assertEquals(reminder, iptvJson.decodeFromString<IptvReminder>(iptvJson.encodeToString(reminder)))
        assertEquals("", iptvJson.decodeFromString<IptvRecording>(
            iptvJson.encodeToString(recording).replace(",\"accountId\":\"first\"", "")).accountId)
        assertEquals("", iptvJson.decodeFromString<IptvReminder>(
            iptvJson.encodeToString(reminder).replace(",\"accountId\":\"second\"", "")).accountId)
    }

    @Test fun deletingTheFirstAccountDoesNotPromoteAnotherAccountsFavoritesToLegacyNasKeys() {
        val source = IptvSource("provider", "Fournisseur", SourceType.XTREAM, "https://example.test")
        val movie = IptvItem("film", source.id, "1", ContentType.MOVIE, "Film")
        val both = IptvLibrary(sources = listOf(source), remembered = listOf(movie), favorites = setOf(movie.id))
            .withAccount(IptvAccount("first", "Jojo")).withAccount(IptvAccount("second", "Invité"))
            .copy(favorites = setOf(movie.id)).checkpointAccount()
        val deleted = both.withoutAccount("first")
        assertEquals("first", deleted.legacyAccountId)
        assertFalse(iptvSyncValues(deleted).containsKey("favorite:film"))
        assertTrue(iptvSyncValues(deleted).containsKey("account:second:favorite:film"))
        val next = deleted.withoutAccount("second").withAccount(IptvAccount("third", "Nouveau"))
        assertTrue(next.favorites.isEmpty())
    }

    @Test fun homeSeriesEpisodesKeepProviderIdentityProgressAndPrivatePlaybackData() {
        val series = IptvItem(itemId("a", ContentType.SERIES, "7"), "a", "7", ContentType.SERIES, "Ma série",
            artwork = "https://example.test/series.jpg")
        val episode = IptvItem(itemId("a", ContentType.EPISODE, "10"), "a", "10", ContentType.EPISODE, "Premier épisode",
            streamUrl = "https://example.test/user/secret/episode.mp4", command = "private command",
            headers = mapOf("Authorization" to "Bearer private-token"), description = "Synopsis fournisseur", season = 1, episode = 1, seriesId = "7")
        val next = episode.copy(id = itemId("a", ContentType.EPISODE, "11"), providerId = "11", season = 2)
        val other = episode.copy(id = "other", sourceId = "b")
        val progress = com.nuvio.tv.domain.model.WatchProgress(series.id, "series", series.title, null, null, null,
            episode.id, 1, 1, episode.title, 1200, 1200, 100)
        val payload = IptvStore.homeSeriesPayload(series, listOf(next, other, episode, episode), listOf(progress))
        val seasons = iptvJson.parseToJsonElement(payload).jsonObject.getValue("seasons").jsonArray
        assertEquals(listOf(1, 2), seasons.map { it.jsonObject.number("number") })
        val first = seasons.first().jsonObject.getValue("episodes").jsonArray.single().jsonObject
        assertEquals(episode.id, first.string("id"))
        assertEquals(series.id, IptvStore.homeContentId(episode))
        assertEquals(series.id, first.string("iptvSeriesId"))
        assertEquals(series.title, first.string("title"))
        assertEquals(episode.title, first.string("episodeTitle"))
        assertEquals(episode.description, first.string("description"))
        assertEquals(series.artwork, first.string("artwork"))
        assertEquals("true", first.string("watched"))
        assertEquals("false", first.string("favorite"))
        assertEquals(1200, first.number("position"))
        assertFalse(payload.contains("secret"))
        assertFalse(payload.contains("private-token"))
        assertFalse(first.containsKey("url"))
        assertFalse(first.containsKey("headers"))
        assertEquals(next.id, seasons.last().jsonObject.getValue("episodes").jsonArray.single().jsonObject.string("id"))
    }

    @Test fun homeM3uSeriesUsesSeriesIdentityInsteadOfTheFirstEpisodeProviderId() {
        val series = IptvItem(itemId("m", ContentType.SERIES, "Exemple"), "m", "first-stream", ContentType.SERIES,
            "Exemple", seriesId = "Exemple")
        val episode = series.copy(id = itemId("m", ContentType.EPISODE, "stream"), type = ContentType.EPISODE, season = 1, episode = 2)
        val seasons = iptvJson.parseToJsonElement(IptvStore.homeSeriesPayload(series, listOf(episode), emptyList())).jsonObject.getValue("seasons").jsonArray
        assertEquals(episode.id, seasons.single().jsonObject.getValue("episodes").jsonArray.single().jsonObject.string("id"))
        assertEquals(series.id, IptvStore.homeContentId(episode))
        assertEquals(series.id, IptvStore.homeContentId(series))
    }

    @Test fun removingHomeSeriesIncludesEverySeasonAndKeepsOtherSeriesAndSources() {
        val series = IptvItem(itemId("a", ContentType.SERIES, "42"), "a", "42", ContentType.SERIES, "Série")
        val first = series.copy(id = itemId("a", ContentType.EPISODE, "101"), providerId = "101",
            type = ContentType.EPISODE, seriesId = "42", season = 1, episode = 1)
        val second = first.copy(id = itemId("a", ContentType.EPISODE, "202"), season = 2, episode = 1)
        val other = first.copy(id = itemId("a", ContentType.EPISODE, "303"), seriesId = "43")
        val otherSource = first.copy(id = itemId("b", ContentType.EPISODE, "101"), sourceId = "b")
        val remembered = listOf(series, first, second, other, otherSource)
        assertEquals(setOf(series.id, first.id, second.id), IptvStore.homeFavoriteRemovalIds(series, remembered))
        // A Home resume can only know an episode; the synthesized parent must remove the same family.
        val parentFromEpisode = first.copy(id = IptvStore.homeContentId(first), providerId = first.seriesId,
            type = ContentType.SERIES)
        assertEquals(setOf(series.id, first.id, second.id), IptvStore.homeFavoriteRemovalIds(parentFromEpisode,
            remembered.filterNot { it.type == ContentType.SERIES }))
    }

    @Test fun removingHomeMovieIsIdempotentAndDoesNotRemoveAnIdenticalProviderIdElsewhere() {
        val movie = IptvItem(itemId("a", ContentType.MOVIE, "42"), "a", "42", ContentType.MOVIE, "Film")
        val other = movie.copy(id = itemId("b", ContentType.MOVIE, "42"), sourceId = "b")
        val ids = IptvStore.homeFavoriteRemovalIds(movie, listOf(movie, other))
        val after = setOf(movie.id, other.id) - ids
        assertEquals(setOf(other.id), after)
        assertEquals(after, after - ids)
    }

    @Test fun homeResumeRemovalKeepsTheOwningAccountAndTheParentSeriesIdentity() {
        val episode = IptvItem(itemId("a", ContentType.EPISODE, "101"), "a", "101", ContentType.EPISODE,
            "Episode", seriesId = "42", season = 1, episode = 1)
        val parentId = IptvStore.homeContentId(episode)
        val legacy = IptvLibrary(selectedAccountId = "first", legacyAccountId = "first", remembered = listOf(episode))
        val switched = legacy.copy(selectedAccountId = "second")
        val first = IptvStore.homeRemovalContentId(parentId, legacy)
        val second = IptvStore.homeRemovalContentId(parentId, switched)
        assertTrue(first.startsWith("iptv:account:first:"))
        assertEquals("external-playback|$parentId", IptvStore.homeResumeDismissalKey(first, legacy))
        assertEquals("external-playback|$parentId", IptvStore.homeResumeDismissalKey(first, switched))
        assertEquals(IptvStore.homeResumeDismissalKey(first, legacy), IptvStore.homeResumeDismissalKey(
            IptvStore.homeRemovalContentId(episode.id, legacy), legacy))
        assertEquals("external-playback|$second", IptvStore.homeResumeDismissalKey(second, switched))
        assertNotEquals(IptvStore.homeResumeDismissalKey(first, switched), IptvStore.homeResumeDismissalKey(second, switched))
        assertEquals(parentId, IptvStore.catalogContentId(first))
    }

    @Test fun multicastStreamsKeepTheirIdentityAndUseTheConfiguredUdpProxy() {
        val source = IptvSource("s", "M3U", SourceType.M3U, "http://example.test/list.m3u")
        val channels = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Multicast\nudp://@239.0.0.1:1234\n".reader().buffered(), source)
        assertEquals(1, channels.size)
        assertEquals("udp://@239.0.0.1:1234", channels.single().streamUrl)
        assertEquals("http://192.0.2.1:8080/udp/239.0.0.1:1234", proxiedStream(channels.single().streamUrl, "192.0.2.1:8080"))
        assertEquals("https://example.test/video", proxiedStream("https://example.test/video", ""))
        assertThrows(IllegalStateException::class.java) { proxiedStream(channels.single().streamUrl, "") }
        assertThrows(IllegalArgumentException::class.java) { proxiedStream("udp://239.0.0.1:99999", "192.0.2.1:8080") }
    }

    @Test fun nextEpisodeFollowsTheProviderSeriesAcrossSeasons() {
        val first = IptvItem("first", "s", "1", ContentType.EPISODE, "Episode", season = 1, episode = 1, seriesId = "series")
        val second = first.copy(id = "second", episode = 2)
        val nextSeason = first.copy(id = "next-season", season = 2)
        val otherProvider = second.copy(id = "other", sourceId = "other")
        val otherSeries = second.copy(id = "other-series", seriesId = "different")
        val episodes = listOf(nextSeason, otherProvider, otherSeries, second, first)
        assertEquals(second, IptvStore.nextEpisode(first, episodes))
        assertEquals(nextSeason, IptvStore.nextEpisode(second, episodes))
        assertNull(IptvStore.nextEpisode(nextSeason, episodes))
        assertNull(IptvStore.nextEpisode(first.copy(id = "missing"), episodes))
    }

    @Test fun homeResumeResolvesTheExactEpisodeInsideItsProviderSeries() {
        val series = IptvItem("series", "source", "7", ContentType.SERIES, "L’arène")
        val second = IptvItem("episode2", "source", "12", ContentType.EPISODE, "Épisode 2", season = 1, episode = 2, seriesId = "7")
        val episodes = listOf(second.copy(sourceId = "other"), second.copy(seriesId = "8"),
            second.copy(episode = 1), second.copy(season = 2), second)
        assertEquals(second, IptvStore.homeResumeEpisode(series, episodes, 1, 2))
        assertNull(IptvStore.homeResumeEpisode(series, episodes, 1, 3))
        assertEquals(second, IptvStore.homeResumeEpisode(series.copy(providerId = "first-stream", seriesId = "7"), episodes, 1, 2))
    }

    @Test fun homeEpisodeRestartUsesTheCurrentProviderRecordInsteadOfTheRememberedCommand() {
        val remembered = IptvItem("episode2", "source", "12", ContentType.EPISODE, "Épisode 2",
            season = 6, episode = 2, seriesId = "7", command = "old-command", streamUrl = "https://example.test/old")
        val current = remembered.copy(command = "current-command", streamUrl = "https://example.test/current")
        val catalogue = listOf(current.copy(sourceId = "other"), current.copy(seriesId = "8"),
            current.copy(episode = 3), current)
        assertEquals(current, IptvStore.homeResumeEpisode(remembered, catalogue, 6, 2))
        assertEquals("current-command", IptvStore.homeResumeEpisode(remembered, catalogue, 6, 2)?.command)
        assertNull(IptvStore.homeResumeEpisode(remembered, catalogue.dropLast(1), 6, 2))
    }

    @Test fun playerMenuOrderingRetainsNewCommandsAndIgnoresInvalidIds() {
        val commands = listOf("record", "audio", "subtitles", "display")
        assertEquals(commands, IptvStore.playerMenuOrder("invalid", commands))
        assertEquals(listOf("display", "audio", "record", "subtitles"),
            IptvStore.playerMenuOrder("[\"display\",\"unknown\",\"audio\",\"display\"]", commands))
        assertEquals(commands, IptvStore.playerMenuOrder("[]", commands))
    }

    @Test fun playerPanelTimeoutSupportsDisabledAndBoundsSyncedValues() {
        assertEquals(5000L, IptvStore.playerPanelTimeout(emptyMap()))
        assertEquals(0L, IptvStore.playerPanelTimeout(mapOf("playerPanelTimeout" to "0")))
        assertEquals(60000L, IptvStore.playerPanelTimeout(mapOf("playerPanelTimeout" to "120")))
        assertEquals(5000L, IptvStore.playerPanelTimeout(mapOf("playerPanelTimeout" to "invalid")))
    }

    @Test fun disabledPassthroughRequiresPcmAndPreservesSharedSinkBehavior() {
        val sinkType = androidx.media3.exoplayer.audio.AudioSink::class.java
        val sink = java.lang.reflect.Proxy.newProxyInstance(sinkType.classLoader, arrayOf(sinkType)) { _, method, _ ->
            when (method.name) {
                "getFormatSupport" -> androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                else -> null
            }
        } as androidx.media3.exoplayer.audio.AudioSink
        val encoded = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AC3).build()
        val pcm = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW).build()
        val shared = com.nuvio.tv.ui.screens.player.PlaybackSpeedAwareAudioSink(sink)
        val decoded = com.nuvio.tv.ui.screens.player.PlaybackSpeedAwareAudioSink(sink, false)
        assertFalse(shared.shouldForcePcmForFormat(encoded))
        assertTrue(decoded.shouldForcePcmForFormat(encoded))
        assertFalse(decoded.shouldForcePcmForFormat(pcm))
        assertEquals(androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED, decoded.getFormatSupport(encoded))
        assertEquals(androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, decoded.getFormatSupport(pcm))
    }

    @Test fun audioOutputChangesKeepCompatiblePlaybackAndAllowDecoderFallback() {
        val sinkType = androidx.media3.exoplayer.audio.AudioSink::class.java
        val listenerType = androidx.media3.exoplayer.audio.AudioSink.Listener::class.java
        var outputListener: androidx.media3.exoplayer.audio.AudioSink.Listener? = null
        var compressedOutputSupported = true
        val sink = java.lang.reflect.Proxy.newProxyInstance(sinkType.classLoader, arrayOf(sinkType)) { _, method, args ->
            when (method.name) {
                "setListener" -> { outputListener = args!![0] as androidx.media3.exoplayer.audio.AudioSink.Listener; null }
                "getFormatSupport" -> {
                    val format = args!![0] as androidx.media3.common.Format
                    if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW || compressedOutputSupported)
                        androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                    else androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED
                }
                else -> null
            }
        } as androidx.media3.exoplayer.audio.AudioSink
        var capabilityChanges = 0
        var sinkErrors = 0
        val callbacks = mutableListOf<Pair<String, List<Any?>>>()
        val listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
            when (method.name) {
                "onAudioCapabilitiesChanged" -> capabilityChanges++
                "onAudioSinkError" -> sinkErrors++
            }
            if (method.name != "onAudioCapabilitiesChanged") callbacks += method.name to args?.toList().orEmpty()
            null
        } as androidx.media3.exoplayer.audio.AudioSink.Listener
        val shared = com.nuvio.tv.ui.screens.player.PlaybackSpeedAwareAudioSink(sink)
        shared.setListener(listener)
        val callback = checkNotNull(outputListener)
        callback.onAudioCapabilitiesChanged()
        assertEquals(1, capabilityChanges)

        val pcm = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW).build()
        val encoded = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AC3).build()
        shared.configure(pcm, 0, null)
        compressedOutputSupported = false
        callback.onAudioCapabilitiesChanged()
        assertEquals(1, capabilityChanges)
        shared.notifyAudioProcessingRequirementChanged()
        assertEquals(2, capabilityChanges)

        compressedOutputSupported = true
        shared.configure(encoded, 0, null)
        callback.onAudioCapabilitiesChanged()
        assertEquals(2, capabilityChanges)
        compressedOutputSupported = false
        callback.onAudioCapabilitiesChanged()
        assertEquals(3, capabilityChanges)
        shared.configure(pcm, 0, null)
        compressedOutputSupported = true
        callback.onAudioCapabilitiesChanged()
        assertEquals(3, capabilityChanges)
        val error = IllegalStateException("Audio output failure")
        val track = androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig(2, 48_000, 12, false, false, 4096)
        callback.onAudioSinkError(error)
        assertEquals(1, sinkErrors)
        callback.onPositionAdvancing(1234L)
        callback.onOffloadBufferEmptying()
        callback.onOffloadBufferFull()
        callback.onAudioTrackInitialized(track)
        callback.onAudioTrackReleased(track)
        callback.onSilenceSkipped()
        callback.onAudioSessionIdChanged(42)
        assertEquals(listOf(
            "onAudioSinkError" to listOf(error),
            "onPositionAdvancing" to listOf(1234L),
            "onOffloadBufferEmptying" to emptyList<Any?>(),
            "onOffloadBufferFull" to emptyList<Any?>(),
            "onAudioTrackInitialized" to listOf(track),
            "onAudioTrackReleased" to listOf(track),
            "onSilenceSkipped" to emptyList<Any?>(),
            "onAudioSessionIdChanged" to listOf(42)), callbacks)
    }

    @Test fun playbackBufferChoicesIncludeNoWaitAndExtraLarge() {
        val base = com.nuvio.tv.data.local.PlayerSettings()
        val buffers = listOf("none", "small", "medium", "large", "extraLarge").map {
            IptvStore.playerSettings(mapOf("bufferSize" to it), base).bufferSettings
        }
        assertEquals(listOf(0, 15000, 30000, 60000, 120000), buffers.map { it.minBufferMs })
        assertEquals(0, buffers.first().bufferForPlaybackMs)
        assertEquals(0, buffers.first().bufferForPlaybackAfterRebufferMs)
        assertTrue(buffers.all { it.maxBufferMs >= it.minBufferMs && it.minBufferMs >= it.bufferForPlaybackMs &&
            it.minBufferMs >= it.bufferForPlaybackAfterRebufferMs })
        buffers.forEach { buffer ->
            androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(buffer.minBufferMs, buffer.maxBufferMs,
                buffer.bufferForPlaybackMs, buffer.bufferForPlaybackAfterRebufferMs).build()
        }
    }

    @Test fun afrSeparatesLiveAndVodAndSupportsResolutionWithoutRefreshRateChanges() {
        val base = com.nuvio.tv.data.local.PlayerSettings()
        val liveOnly = mapOf("afrLive" to "true", "afrVod" to "false")
        assertEquals(com.nuvio.tv.data.local.FrameRateMatchingMode.START_STOP, IptvStore.playerSettings(liveOnly, base, true).frameRateMatchingMode)
        assertEquals(com.nuvio.tv.data.local.FrameRateMatchingMode.OFF, IptvStore.playerSettings(liveOnly, base, false).frameRateMatchingMode)
        val vodOnly = mapOf("afrLive" to "false", "afrVod" to "true", "afrRefresh" to "false", "afrResolution" to "true")
        assertTrue(IptvStore.playerSettings(vodOnly, base, false).resolutionMatchingEnabled)
        assertFalse(IptvStore.playerSettings(vodOnly, base, true).resolutionMatchingEnabled)
        assertEquals(com.nuvio.tv.data.local.FrameRateMatchingMode.OFF,
            IptvStore.playerSettings(liveOnly + ("afrRefresh" to "false"), base, true).frameRateMatchingMode)
    }

    @Test fun subtitlePreferencesStayPrivateToEachAccountAndSurviveNasSync() {
        val firstValues = mapOf("subtitleLanguage" to "fr", "secondarySubtitleLanguage" to "en",
            "subtitleUseForced" to "true", "subtitleShowOnlyPreferredLanguages" to "true")
        val secondValues = mapOf("subtitleLanguage" to "ja", "secondarySubtitleLanguage" to "none",
            "subtitleUseForced" to "false", "subtitleShowOnlyPreferredLanguages" to "false")
        val initial = IptvLibrary().withAccount(IptvAccount("first", "Premier"))
            .withAccount(IptvAccount("second", "Second")).selectAccount("first")
        val first = initial.copy(settings = initial.settings + firstValues)
        val second = first.selectAccount("second")
        assertTrue(firstValues.keys.none { it in second.settings })
        val configured = second.copy(settings = second.settings + secondValues)
        firstValues.forEach { (key, value) -> assertEquals(value, configured.selectAccount("first").settings[key]) }
        val values = iptvSyncValues(configured)
        assertTrue(firstValues.keys.none { "setting:$it" in values })
        firstValues.forEach { (key, value) -> assertEquals(value, values["account:first:setting:$key"]) }
        secondValues.forEach { (key, value) -> assertEquals(value, values["account:second:setting:$key"]) }
        val restored = mergeIptvLibrary(IptvLibrary(), values.mapValues { IptvSyncRecord(100, "tv", it.value) })
        firstValues.forEach { (key, value) -> assertEquals(value, restored.selectAccount("first").settings[key]) }
        secondValues.forEach { (key, value) -> assertEquals(value, restored.selectAccount("second").settings[key]) }
    }

    @Test fun iptvSubtitlePreferencesReachThePlayerWithoutChangingItsStyleOrAudio() {
        val style = com.nuvio.tv.data.local.SubtitleStyleSettings(preferredLanguage = "de",
            secondaryPreferredLanguage = "it", size = 150, bold = true, verticalOffset = 12)
        val base = com.nuvio.tv.data.local.PlayerSettings(subtitleStyle = style, preferredAudioLanguage = "en",
            streamAutoPlayNextEpisodeEnabled = true)
        val configured = IptvStore.playerSettings(mapOf("subtitleLanguage" to "fr", "secondarySubtitleLanguage" to "ja",
            "subtitleUseForced" to "true", "subtitleShowOnlyPreferredLanguages" to "true"), base, false)
        assertEquals(base.copy(subtitleStyle = style.copy(preferredLanguage = "fr", secondaryPreferredLanguage = "ja",
            useForcedSubtitles = true, showOnlyPreferredLanguages = true)), configured)
    }

    @Test fun iptvSubtitlesCanBeDisabledAndUnconfiguredPreferencesKeepTheirCurrentValues() {
        val base = com.nuvio.tv.data.local.PlayerSettings(streamAutoPlayNextEpisodeEnabled = true,
            subtitleStyle = com.nuvio.tv.data.local.SubtitleStyleSettings(
            preferredLanguage = "fr", secondaryPreferredLanguage = "en", useForcedSubtitles = true, showOnlyPreferredLanguages = true))
        assertEquals(base, IptvStore.playerSettings(emptyMap(), base, false))
        val cleared = IptvStore.playerSettings(mapOf("subtitleLanguage" to "none", "secondarySubtitleLanguage" to "none",
            "subtitleUseForced" to "false", "subtitleShowOnlyPreferredLanguages" to "false"), base, false).subtitleStyle
        assertEquals("none", cleared.preferredLanguage)
        assertNull(cleared.secondaryPreferredLanguage)
        assertFalse(cleared.useForcedSubtitles)
        assertFalse(cleared.showOnlyPreferredLanguages)
        assertEquals(base, IptvStore.playerSettings(mapOf("subtitleUseForced" to "invalid",
            "subtitleShowOnlyPreferredLanguages" to "invalid"), base, false))
    }

    @Test fun audioLanguageChoicesStayPrivateToEachAccountAndSurviveNasSync() {
        val initial = IptvLibrary().withAccount(IptvAccount("first", "Premier"))
            .withAccount(IptvAccount("second", "Second")).selectAccount("first")
        val first = initial.copy(settings = initial.settings + mapOf("audioLanguage" to "en", "secondaryAudioLanguage" to "fr"))
        val second = first.selectAccount("second")
        assertNull(second.settings["audioLanguage"])
        assertNull(second.settings["secondaryAudioLanguage"])
        val configured = second.copy(settings = second.settings + mapOf("audioLanguage" to "ja", "secondaryAudioLanguage" to "original"))
        assertEquals("en", configured.selectAccount("first").settings["audioLanguage"])
        assertEquals("fr", configured.selectAccount("first").settings["secondaryAudioLanguage"])
        val values = iptvSyncValues(configured)
        assertFalse(values.containsKey("setting:audioLanguage"))
        assertFalse(values.containsKey("setting:secondaryAudioLanguage"))
        assertEquals("en", values["account:first:setting:audioLanguage"])
        assertEquals("ja", values["account:second:setting:audioLanguage"])
        val restored = mergeIptvLibrary(IptvLibrary(), values.mapValues { IptvSyncRecord(100, "tv", it.value) })
        assertEquals("en", restored.selectAccount("first").settings["audioLanguage"])
        assertEquals("original", restored.selectAccount("second").settings["secondaryAudioLanguage"])
    }

    @Test fun iptvPlayerForwardsPrimarySecondaryAndExplicitlyClearedAudioLanguages() {
        val base = com.nuvio.tv.data.local.PlayerSettings(secondaryPreferredAudioLanguage = "de", streamAutoPlayNextEpisodeEnabled = true)
        val configured = IptvStore.playerSettings(mapOf("audioLanguage" to "en", "secondaryAudioLanguage" to "fr"), base, false)
        assertEquals("en", configured.preferredAudioLanguage)
        assertEquals("fr", configured.secondaryPreferredAudioLanguage)
        assertNull(IptvStore.playerSettings(mapOf("secondaryAudioLanguage" to "none"), base, false).secondaryPreferredAudioLanguage)
        assertEquals(base, IptvStore.playerSettings(emptyMap(), base, false))
    }

    @Test fun audioLanguagePriorityUsesTheAccountBeforeTheDeviceAndSupportsOriginalFallback() {
        fun languages(primary: String, secondary: String?, original: String? = null) =
            com.nuvio.tv.ui.screens.player.resolvePreferredAudioLanguages(primary, secondary, listOf("deu"), original)
        assertEquals(listOf("en", "fr"), languages("en", "fr"))
        assertEquals(listOf("ja", "fr"), languages("original", "fr", "jpn"))
        assertEquals(listOf("fr", "de"), languages("original", "fr"))
        assertEquals(listOf("en", "ja"), languages("en", "original", "ja"))
        assertEquals(listOf("en"), languages("eng", "en"))
        assertEquals(listOf("fr", "de"), languages("original", "fr", "und"))
    }

    @Test fun originalAudioUsesDeclaredLanguageBeforeCountryAndRejectsAmbiguousOrigins() {
        assertEquals("ta", com.nuvio.tv.domain.model.resolveOriginalAudioLanguage("tam", listOf("IN")))
        assertEquals("en", com.nuvio.tv.domain.model.resolveOriginalAudioLanguage(null, listOf("US")))
        assertEquals("en", com.nuvio.tv.domain.model.resolveOriginalAudioLanguage(null, listOf("GB", "AU")))
        assertEquals("ja", com.nuvio.tv.domain.model.resolveOriginalAudioLanguage(null, listOf("JP")))
        assertNull(com.nuvio.tv.domain.model.resolveOriginalAudioLanguage(null, listOf("FR", "DE")))
        assertNull(com.nuvio.tv.domain.model.resolveOriginalAudioLanguage(null, listOf("FR", "CA")))
        assertNull(com.nuvio.tv.domain.model.resolveOriginalAudioLanguage("und"))
    }

    @Test fun iptvIntroOptionOverridesDetectionWithoutChangingOtherPlayerOptions() {
        val base = com.nuvio.tv.data.local.PlayerSettings(skipIntroEnabled = false, rewindStepSeconds = 30,
            streamAutoPlayNextEpisodeEnabled = true)
        val enabled = IptvStore.playerSettings(mapOf("skipIntroEnabled" to "true"), base, false)
        assertEquals(base.copy(skipIntroEnabled = true), enabled)
        assertEquals(base, IptvStore.playerSettings(mapOf("skipIntroEnabled" to "false"), enabled, false))
        assertEquals(base, IptvStore.playerSettings(emptyMap(), base, false))
        assertEquals(base, IptvStore.playerSettings(mapOf("skipIntroEnabled" to "invalid"), base, false))
    }

    @Test fun nextEpisodePreferencesStayPrivateToEachAccountAndSurviveNasSync() {
        val firstValues = mapOf("autoNextEpisode" to "true", "nextEpisodeThresholdMode" to "PERCENTAGE",
            "nextEpisodeThresholdPercent" to "97.5", "nextEpisodeThresholdMinutesBeforeEnd" to "2.0")
        val secondValues = mapOf("autoNextEpisode" to "false", "nextEpisodeThresholdMode" to "MINUTES_BEFORE_END",
            "nextEpisodeThresholdPercent" to "99.0", "nextEpisodeThresholdMinutesBeforeEnd" to "1.5")
        val initial = IptvLibrary().withAccount(IptvAccount("first", "Premier"))
            .withAccount(IptvAccount("second", "Second")).selectAccount("first")
        val first = initial.copy(settings = initial.settings + firstValues)
        val second = first.selectAccount("second")
        assertTrue(firstValues.keys.none { it in second.settings })
        val configured = second.copy(settings = second.settings + secondValues)
        val values = iptvSyncValues(configured)
        assertTrue(firstValues.keys.none { "setting:$it" in values })
        firstValues.forEach { (key, value) -> assertEquals(value, values["account:first:setting:$key"]) }
        secondValues.forEach { (key, value) -> assertEquals(value, values["account:second:setting:$key"]) }
        val restored = mergeIptvLibrary(IptvLibrary(), values.mapValues { IptvSyncRecord(100, "tv", it.value) })
        firstValues.forEach { (key, value) -> assertEquals(value, restored.selectAccount("first").settings[key]) }
        secondValues.forEach { (key, value) -> assertEquals(value, restored.selectAccount("second").settings[key]) }
    }

    @Test fun nextEpisodePreferencesReachVodWithoutChangingLiveOrOtherPlayerSettings() {
        val base = PlayerSettings(preferredAudioLanguage = "fr", rewindStepSeconds = 30)
        val values = mapOf("autoNextEpisode" to "false", "nextEpisodeThresholdMode" to "MINUTES_BEFORE_END",
            "nextEpisodeThresholdPercent" to "97.5", "nextEpisodeThresholdMinutesBeforeEnd" to "1.5")
        assertEquals(base.copy(nextEpisodeThresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END,
            nextEpisodeThresholdPercent = 97.5f, nextEpisodeThresholdMinutesBeforeEnd = 1.5f),
            IptvStore.playerSettings(values, base, false))
        assertEquals(base, IptvStore.playerSettings(values, base, true))
        assertTrue(IptvStore.playerSettings(values + ("autoNextEpisode" to "true"), base, false).streamAutoPlayNextEpisodeEnabled)
        // Keep IPTV's existing enabled-by-default autoplay behavior.
        assertEquals(base.copy(streamAutoPlayNextEpisodeEnabled = true), IptvStore.playerSettings(emptyMap(), base, false))
    }

    @Test fun nextEpisodeThresholdValuesKeepHalfStepsAndRejectInvalidNumbers() {
        val base = PlayerSettings(streamAutoPlayNextEpisodeEnabled = true,
            nextEpisodeThresholdPercent = 97f, nextEpisodeThresholdMinutesBeforeEnd = 1.5f)
        assertEquals(base, IptvStore.playerSettings(mapOf("nextEpisodeThresholdMode" to "invalid",
            "nextEpisodeThresholdPercent" to "NaN", "nextEpisodeThresholdMinutesBeforeEnd" to "Infinity"), base, false))
        val clamped = IptvStore.playerSettings(mapOf("nextEpisodeThresholdPercent" to "91",
            "nextEpisodeThresholdMinutesBeforeEnd" to "8"), base, false)
        assertEquals(95f, clamped.nextEpisodeThresholdPercent, 0f)
        assertEquals(3.5f, clamped.nextEpisodeThresholdMinutesBeforeEnd, 0f)
        val stepped = IptvStore.playerSettings(mapOf("nextEpisodeThresholdPercent" to "97.3",
            "nextEpisodeThresholdMinutesBeforeEnd" to "1.7"), base, false)
        assertEquals(97.5f, stepped.nextEpisodeThresholdPercent, 0f)
        assertEquals(1.5f, stepped.nextEpisodeThresholdMinutesBeforeEnd, 0f)
        val reversed = IptvStore.playerSettings(mapOf("nextEpisodeThresholdPercent" to "102",
            "nextEpisodeThresholdMinutesBeforeEnd" to "-2"), base, false)
        assertEquals(100f, reversed.nextEpisodeThresholdPercent, 0f)
        assertEquals(0f, reversed.nextEpisodeThresholdMinutesBeforeEnd, 0f)
    }

    @Test fun nextEpisodeCardUsesTheConfiguredThresholdUnlessAnOutroMarkerExists() {
        val duration = 2_400_000L
        fun visible(position: Long, mode: NextEpisodeThresholdMode, markers: List<SkipInterval> = emptyList()) =
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(position, duration, markers, mode, 97f, 2f)
        assertFalse(visible(2_327_999L, NextEpisodeThresholdMode.PERCENTAGE))
        assertTrue(visible(2_328_000L, NextEpisodeThresholdMode.PERCENTAGE))
        assertFalse(visible(2_279_999L, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        assertTrue(visible(2_280_000L, NextEpisodeThresholdMode.MINUTES_BEFORE_END))
        val marker = listOf(SkipInterval(2_100.0, 2_400.0, "outro", "introdb"))
        NextEpisodeThresholdMode.entries.forEach { mode ->
            assertFalse(visible(2_099_999L, mode, marker))
            assertTrue(visible(2_100_000L, mode, marker))
        }
        assertFalse(PlayerNextEpisodeRules.shouldShowNextEpisodeCard(10_000L, 0L, emptyList(),
            NextEpisodeThresholdMode.PERCENTAGE, 97f, 2f))
    }

    @Test fun nextEpisodeCardRecognizesEveryCreditsMarkerUsedByTheSkipButton() {
        listOf("outro", "ed", "mixed-ed", "ending", "credits", " CREDITS ").forEach { type ->
            val markers = listOf(SkipInterval(2_100.0, 2_400.0, type, "provider"))
            assertFalse(PlayerNextEpisodeRules.shouldShowNextEpisodeCard(2_099_999L, 2_400_000L,
                markers, NextEpisodeThresholdMode.PERCENTAGE, 100f, 0f))
            assertTrue(PlayerNextEpisodeRules.shouldShowNextEpisodeCard(2_100_000L, 2_400_000L,
                markers, NextEpisodeThresholdMode.PERCENTAGE, 100f, 0f))
        }
    }

    @Test fun nextEpisodeCardUsesTheFirstCreditsInPlaybackOrderAndIgnoresIntroAndRecap() {
        val markers = listOf(SkipInterval(2_300.0, 2_400.0, "outro", "provider"),
            SkipInterval(0.0, 30.0, "recap", "provider"),
            SkipInterval(60.0, 120.0, "intro", "provider"),
            SkipInterval(2_100.0, 2_200.0, "credits", "provider"))
        assertFalse(PlayerNextEpisodeRules.shouldShowNextEpisodeCard(120_000L, 2_400_000L,
            markers, NextEpisodeThresholdMode.PERCENTAGE, 100f, 0f))
        assertFalse(PlayerNextEpisodeRules.shouldShowNextEpisodeCard(2_099_999L, 2_400_000L,
            markers, NextEpisodeThresholdMode.PERCENTAGE, 100f, 0f))
        assertTrue(PlayerNextEpisodeRules.shouldShowNextEpisodeCard(2_100_000L, 2_400_000L,
            markers, NextEpisodeThresholdMode.PERCENTAGE, 100f, 0f))
    }

    @Test fun episodePlaybackUsesTheMatchingSeriesTmdbIdentity() {
        val series = IptvItem(itemId("s", ContentType.SERIES, "42"), "s", "42", ContentType.SERIES,
            "Série", tmdbId = 36)
        val episode = series.copy(id = itemId("s", ContentType.EPISODE, "11"), providerId = "11",
            type = ContentType.EPISODE, seriesId = "42", season = 1, episode = 2, tmdbId = 9999)
        assertEquals(36, IptvStore.playbackTmdbId(episode, series))
        assertEquals(0, IptvStore.playbackTmdbId(episode))
        assertEquals(0, IptvStore.playbackTmdbId(episode, series.copy(sourceId = "other")))
        assertEquals(0, IptvStore.playbackTmdbId(episode, series.copy(id = itemId("s", ContentType.SERIES, "43"))))
        assertEquals(0, IptvStore.playbackTmdbId(episode, series.copy(type = ContentType.MOVIE)))
    }

    @Test fun playbackIdentityKeepsMoviesAndRejectsLiveOrInvalidTmdbNumbers() {
        val item = IptvItem("film", "s", "42", ContentType.MOVIE, "Film", tmdbId = 36)
        assertEquals(36, IptvStore.playbackTmdbId(item))
        assertEquals(36, IptvStore.playbackTmdbId(item.copy(type = ContentType.SERIES)))
        assertEquals(0, IptvStore.playbackTmdbId(item.copy(type = ContentType.LIVE)))
        assertEquals(0, IptvStore.playbackTmdbId(item.copy(tmdbId = -1)))
    }

    @Test fun iptvPlayerOptionsPreserveUnconfiguredSharedSettings() {
        val base = com.nuvio.tv.data.local.PlayerSettings(decoderPriority = 1, rewindStepSeconds = 30,
            forwardStepSeconds = 30, osdClockEnabled = false)
        assertEquals(base, IptvStore.playerSettings(emptyMap(), base))
        val configured = IptvStore.playerSettings(mapOf("bufferSize" to "small", "decoderPriority" to "hardware",
            "afr" to "START_STOP", "seekSeconds" to "1200", "playerClock" to "true"), base)
        assertEquals(15_000, configured.bufferSettings.minBufferMs)
        assertEquals(30_000, configured.bufferSettings.maxBufferMs)
        assertEquals(0, configured.decoderPriority)
        assertEquals(com.nuvio.tv.data.local.FrameRateMatchingMode.START_STOP, configured.frameRateMatchingMode)
        assertEquals(120, configured.forwardStepSeconds)
        assertEquals(120, configured.rewindStepSeconds)
        assertTrue(configured.osdClockEnabled)
        assertEquals(base.subtitleStyle, configured.subtitleStyle)
        assertEquals(base, IptvStore.playerSettings(mapOf("afr" to "invalid", "bufferSize" to "shared", "decoderPriority" to "shared"), base))
    }

    @Test fun defaultUserAgentKeepsProviderSpecificHeaders() {
        val source = IptvSource("s", "Test", SourceType.M3U, "https://example.test/list", headers = mapOf("Referer" to "https://example.test"))
        assertEquals("Jojo", IptvStore.sourceWithUserAgent(source, "Jojo").headers["User-Agent"])
        assertEquals(source, IptvStore.sourceWithUserAgent(source, ""))
        val custom = source.copy(headers = mapOf("user-agent" to "Portal", "Cookie" to "test"))
        assertEquals(custom, IptvStore.sourceWithUserAgent(custom, "Jojo"))
    }

    @Test fun epgPreferencesShiftFilterAndRemoveDescriptions() {
        val now = 10 * 86_400_000L
        val programs = listOf(EpgProgram("Recent", "Description", now, now + 3_600_000),
            EpgProgram("Old", "Description", now - 3 * 86_400_000, now - 2 * 86_400_000))
        val shifted = IptvStore.epgPrograms(programs, now, 1, false, 60)
        assertEquals(1, shifted.size)
        assertEquals(now + 3_600_000, shifted.single().start)
        assertEquals("", shifted.single().description)
        assertEquals(2, IptvStore.epgPrograms(programs, now, 7, true, 0).size)
        assertEquals("Description", IptvStore.epgPrograms(programs, now, 1, true, 0).single().description)
    }

    @Test fun homeFoldersContainOnlyFavoritesFromAvailableProviders() {
        val movie = IptvItem(itemId("a", ContentType.MOVIE, "1"), "a", "1", ContentType.MOVIE, "Film")
        val series = movie.copy(id = itemId("a", ContentType.SERIES, "1"), type = ContentType.SERIES, title = "Série")
        val live = movie.copy(id = itemId("b", ContentType.LIVE, "1"), sourceId = "b", type = ContentType.LIVE)
        val browsed = movie.copy(id = itemId("a", ContentType.MOVIE, "2"), providerId = "2")
        val items = listOf(browsed, live, series, movie)
        val favorites = linkedSetOf(movie.id, series.id, live.id)
        val projected = IptvStore.projectHomeFavorites(items, favorites, setOf("a", "b"))
        assertEquals(favorites.toList(), projected.keys.toList())
        assertEquals(listOf("movies", "series", "live"), projected.values.map { iptvJson.parseToJsonElement(it).jsonObject.string("category") })
        assertFalse(projected.containsKey(browsed.id))
        assertFalse(IptvStore.projectHomeFavorites(items, favorites - movie.id, setOf("a", "b")).containsKey(movie.id))
        assertFalse(IptvStore.projectHomeFavorites(items, favorites, setOf("a")).containsKey(live.id))
    }

    @Test fun homeFavoriteKeepsMetadataAndIdentityWithoutPlaybackCredentials() {
        val item = IptvItem(itemId("a", ContentType.EPISODE, "1"), "a", "1", ContentType.EPISODE, "Épisode",
            artwork = "https://example.test/poster.jpg", backdrop = "https://example.test/backdrop.jpg",
            streamUrl = "https://example.test/user/secret/video.ts", command = "ffmpeg private-command",
            description = "Synopsis fournisseur", season = 2, episode = 3, seriesId = "7",
            headers = mapOf("Authorization" to "Bearer private-token"))
        val record = iptvJson.parseToJsonElement(IptvStore.projectHomeFavorites(listOf(item), setOf(item.id), setOf("a")).getValue(item.id)).jsonObject
        assertEquals(item.id, record.string("id"))
        assertEquals("series", record.string("category"))
        assertEquals(item.artwork, record.string("artwork"))
        assertEquals(item.description, record.string("description"))
        assertEquals(2, record.number("season"))
        assertEquals(3, record.number("episode"))
        assertEquals("true", record.string("favorite"))
        assertFalse(record.containsKey("url"))
        assertFalse(record.containsKey("streamUrl"))
        assertFalse(record.containsKey("headers"))
        assertFalse(record.containsKey("command"))
        assertFalse(record.toString().contains("secret"))
        assertFalse(record.toString().contains("private-"))
    }

    @Test fun stalkerUsesLocalDatesWhenUnixFieldsHaveWrongSummerOffset() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Paris"))
            val entries = iptvJson.parseToJsonElement("""[{"name":"Journal 20h","time":"2026-10-03 20:00:00","time_to":"2026-10-03 20:30:00","start_timestamp":1791054000,"stop_timestamp":1791055800}]""") as kotlinx.serialization.json.JsonArray
            val value = ProviderEpgParser.parse(entries, localDates = true).single()
            assertEquals("20:00", java.text.SimpleDateFormat("HH:mm").format(java.util.Date(value.start)))
            assertEquals(1_800_000L, value.end - value.start)
        } finally { java.util.TimeZone.setDefault(previous) }
    }
    @Test fun portalEpgAcceptsDateStringsAndDeduplicatesValidPrograms() {
        val values = iptvJson.parseToJsonElement("""[
            {"name":"Journal","time":"2026-10-03 20:00:00","time_to":"2026-10-03 20:30:00"},
            {"name":"Journal","time":"2026-10-03 20:00:00","time_to":"2026-10-03 20:30:00"},
            {"name":"Invalid","start_timestamp":200,"stop_timestamp":100},
            {"name":"Duration","start_timestamp":1791062100000,"duration":3600}
        ]""") as kotlinx.serialization.json.JsonArray
        val programs = ProviderEpgParser.parse(values)
        assertEquals(2, programs.size)
        assertEquals(1_800_000L, programs.first { it.title == "Journal" }.let { it.end - it.start })
        assertEquals(1791062100000L, programs.first { it.title == "Duration" }.start)
    }
    private val source = IptvSource("provider-a", "Test", SourceType.M3U, "https://example.test/list/playlist.m3u")
    private fun parse(value: String) = M3uParser.parse(value.trimIndent().reader().buffered(), source)

    @Test fun quotedCommaAndRelativeArtworkArePreserved() {
        val items = parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="id-1" group-title="Sport, Europe" tvg-logo="/logos/one.png",Chaîne, HD
            https://example.test/live/1.m3u8
        """)
        assertEquals("Chaîne, HD", items.single().title)
        assertEquals("Sport, Europe", items.single().categoryName)
        assertEquals("https://example.test/logos/one.png", items.single().artwork)
        assertEquals("id-1", items.single().epgId)
    }
    @Test fun headersAreScopedToOneStream() {
        val items = parse("""
            #EXTM3U
            #EXTINF:-1,Premier
            #EXTVLCOPT:http-user-agent=ProviderPlayer
            https://example.test/live/1.ts|Referer=https%3A%2F%2Fexample.test%2F
            #EXTINF:-1,Deuxième
            https://example.test/live/2.ts
        """)
        assertEquals("ProviderPlayer", items[0].headers["User-Agent"])
        assertEquals("https://example.test/", items[0].headers["Referer"])
        assertTrue(items[1].headers.isEmpty())
    }
    @Test fun epgIdentifierDoesNotMergeHdAndSdVariants() {
        val items = parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="same-id",Chaîne HD
            https://example.test/live/hd.ts
            #EXTINF:-1 tvg-id="same-id",Chaîne SD
            https://example.test/live/sd.ts
        """)
        assertEquals(2, items.size)
        assertNotEquals(items[0].id, items[1].id)
        assertEquals(items.map { it.id }, parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="same-id",Nouveau nom HD
            https://example.test/live/hd.ts
            #EXTINF:-1 tvg-id="same-id",Nouveau nom SD
            https://example.test/live/sd.ts
        """).map { it.id })
    }
    @Test fun episodesKeepSeasonAndEpisodeAndMoviesStaySeparate() {
        val items = parse("""
            #EXTM3U
            #EXTINF:-1 group-title="Séries",Exemple S02E03
            https://example.test/series/3.mp4
            #EXTINF:-1 group-title="Films",Film
            https://example.test/movie/4.mkv
        """)
        assertEquals(ContentType.EPISODE, items[0].type)
        assertEquals(2, items[0].season)
        assertEquals(3, items[0].episode)
        assertEquals("Exemple", items[0].seriesId)
        assertEquals(ContentType.MOVIE, items[1].type)
    }
    @Test fun sourceIdentitySeparatesAccountsAndContentTypes() {
        assertNotEquals(itemId("a", ContentType.MOVIE, "10"), itemId("b", ContentType.MOVIE, "10"))
        assertNotEquals(itemId("a", ContentType.MOVIE, "10"), itemId("a", ContentType.LIVE, "10"))
        assertFalse(itemId("a", ContentType.LIVE, "http://user:password@example.test/token").contains("password"))
    }
    @Test fun unsupportedSchemesAreRejected() {
        assertTrue(parse("""
            #EXTM3U
            #EXTINF:-1,Refusé
            file:///sdcard/private.mp4
            #EXTINF:-1,Valide
            ../live/1.m3u8
        """).single().streamUrl.startsWith("https://example.test/"))
        assertThrows(IllegalArgumentException::class.java) { httpUrl("javascript:alert(1)") }
    }
    @Test fun webPagesAreNotAcceptedAsPlaylists() {
        assertTrue(parse("<html>\nAccount expired\n</html>").isEmpty())
    }
    @Test fun xmltvFiltersChannelsWindowAndReadsCompressedInput() {
        val xml = """
            <tv>
              <programme channel="wanted" start="20261003090000 +0200" stop="20261003100000 +0200"><title>Émission &amp; suite</title><desc>Résumé</desc></programme>
              <programme channel="other" start="20261003090000 +0200" stop="20261003100000 +0200"><title>Autre</title></programme>
              <programme channel="wanted" start="20251003090000 +0200" stop="20251003100000 +0200"><title>Ancien</title></programme>
            </tv>
        """.trimIndent()
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write(xml.toByteArray()) }
        val start = XmltvParser.timestamp("20261003070000 +0000")
        val guide = XmltvParser.parse(output.toByteArray().inputStream(), setOf("wanted"), start, start + 3_600_000)
        assertEquals(setOf("wanted"), guide.keys)
        assertEquals("Émission & suite", guide.getValue("wanted").single().title)
        assertEquals(start, guide.getValue("wanted").single().start)
    }
}
