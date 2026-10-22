package com.jojotv.home.iptv

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SupabaseIptvContentTest {
    private fun progress(id: String, time: Long = 1, type: String = "movie", episode: Int? = null) =
        com.nuvio.tv.domain.model.WatchProgress(id, type, "Film test", null, null, null, id,
            if (episode == null) null else 1, episode, null, 120000, 600000, time)

    @Test fun removedFavoriteMasksHideCatalogueBadgesWithoutDiscardingPlaybackPositions() {
        val first = progress("iptv:first")
        val second = progress("iptv:second")
        val kept = progress("iptv:kept")
        val saved = listOf(first, second, kept)
        assertEquals(listOf(kept), IptvStore.visibleVodProgress(saved, setOf(first.storageKey, second.storageKey)))
        assertEquals(120000L, first.position)
        assertEquals(saved, IptvStore.visibleVodProgress(saved, emptySet()))
    }

    @Test fun catalogueMasksRespectTheAccountAndEpisodeAndKeepTheNewestCheckpoint() {
        val first = progress("iptv:account:a:series", type = "series", episode = 1)
        val second = progress(first.contentId, 2, "series", 2)
        val otherAccount = first.copy(contentId = "iptv:account:b:series")
        val newer = second.copy(position = 180000, lastWatched = 3)
        assertEquals(listOf(newer, otherAccount), IptvStore.visibleVodProgress(
            listOf(first, second, otherAccount, newer), setOf(first.storageKey)))
    }

    @Test fun homeSuggestionsRespectACloudResumeDismissalWithoutRemovingTheFavorite() {
        val watched = progress("iptv:film")
        assertTrue(IptvStore.homeFavoriteResumeHidden(watched.contentId, "movies", listOf(watched), setOf(watched.storageKey)))
        assertFalse(IptvStore.homeFavoriteResumeHidden("iptv:another", "movies", listOf(watched), setOf(watched.storageKey)))
        assertFalse(IptvStore.homeFavoriteResumeHidden(watched.contentId, "series", listOf(watched), setOf(watched.storageKey)))
        assertFalse(IptvStore.homeFavoriteResumeHidden(watched.contentId, "live", listOf(watched), setOf(watched.storageKey)))
    }

    @Test fun newPlaybackClearsTheSuggestionMaskAndOlderEpisodeMasksCannotHideTheNewEpisode() {
        val first = progress("iptv:series", type = "series", episode = 1)
        val second = progress(first.contentId, 2, "series", 2)
        assertFalse(IptvStore.homeFavoriteResumeHidden(first.contentId, "series", listOf(first, second), setOf(first.storageKey)))
        assertTrue(IptvStore.homeFavoriteResumeHidden(first.contentId, "series", listOf(first, second), setOf(second.storageKey)))
        assertFalse(IptvStore.homeFavoriteResumeHidden(first.contentId, "series", listOf(first, second), emptySet()))
    }

    private val account = "a"
    private val item = IptvItem("iptv:source:live:hash", "source", "17", ContentType.LIVE, "Chaîne test",
        streamUrl = "http://host/private-user/private-password/17", headers = mapOf("Authorization" to "secret"),
        artwork = "https://host/token/image", command = "sensitive-command", description = "private-description")
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun projectionOmitsEveryConnectionField() {
        val clear = cloudPlayback(IptvCloudPlayback(IptvCloudItem.from(item), 2000))
        listOf("http", "private-user", "private-password", "Authorization", "secret", "sensitive-command", "private-description")
            .forEach { assertFalse(clear.contains(it)) }
    }
    @Test fun publicImageReferencesRoundTripWithoutExportingTheirUrls() {
        val movie = item.copy(type = ContentType.MOVIE, artwork = "https://image.tmdb.org/t/p/w500/Poster17.jpg",
            backdrop = "https://image.tmdb.org/t/p/original/Backdrop17.jpg", titleLogo = "https://image.tmdb.org/t/p/w300/Logo17.png")
        val wire = cloudPlayback(IptvCloudPlayback(IptvCloudItem.from(movie)))
        assertFalse(wire.contains("https://")); assertFalse(wire.contains("image.tmdb.org"))
        val restored = cloudPlayback(wire).item.item()
        assertEquals(movie.artwork, restored.artwork)
        assertEquals(movie.backdrop, restored.backdrop)
        assertEquals(movie.titleLogo, restored.titleLogo)
        assertEquals("", restored.streamUrl); assertTrue(restored.headers.isEmpty())
    }
    @Test fun publicFilmIdentitySurvivesReceptionAndDistinguishesHomonymousFilms() {
        val movie = item.copy(type = ContentType.MOVIE, title = "Film homonyme", tmdbId = 170,
            year = "2026-10-20")
        val restored = cloudPlayback(cloudPlayback(IptvCloudPlayback(IptvCloudItem.from(movie)))).item.item()
        assertEquals(170, restored.tmdbId); assertEquals("2026", restored.year)
        val candidates = iptvJson.parseToJsonElement("""[
            {"id":160,"title":"Film homonyme","release_date":"2010-01-01"},
            {"id":170,"title":"Film homonyme","release_date":"2026-01-01"}]
        """) as kotlinx.serialization.json.JsonArray
        assertNull(IptvMediaPresentation.match(restored.copy(year = ""), candidates))
        assertEquals(170, IptvMediaPresentation.match(restored, candidates))
        assertEquals("", restored.description); assertEquals("", restored.streamUrl)
        assertTrue(restored.headers.isEmpty())
    }
    @Test fun publicFilmIdentityCannotCarryFreeTextOrNegativeIds() {
        val reference = IptvCloudItem.from(item)
        reject { reference.copy(tmdbId = -1).item() }
        listOf("2026-secret", "http://host/private-user", "unknown", "26").forEach { year ->
            reject { reference.copy(year = year).item() }
        }
        assertEquals("", IptvCloudItem.from(item.copy(year = "unknown")).year)
    }
    @Test fun providerImageEnrichmentDoesNotInventAnActivityEditButAManualChoiceDoes() {
        val initial = IptvCloudPlayback(IptvCloudItem.from(item.copy(type = ContentType.MOVIE)), 9000)
        val enriched = initial.copy(item = initial.item.copy(poster = "Poster17.jpg", backdrop = "Backdrop17.jpg",
            tmdbId = 170, year = "2026"))
        assertEquals(cloudPlayback(initial), cloudPlaybackTracked(cloudPlayback(enriched)))
        val manual = enriched.copy(artwork = mapOf("hero" to IptvCloudArtwork("Backdrop17.jpg", 200, "device-a")))
        assertNotEquals(cloudPlaybackTracked(cloudPlayback(initial)), cloudPlaybackTracked(cloudPlayback(manual)))
        assertNotEquals(cloudPlaybackTracked(cloudPlayback(manual)), cloudPlaybackTracked(cloudPlayback(manual.copy(playedAt = 10000))))
    }
    @Test fun privateUrlsLocalFilesAndLookalikeHostsCannotBecomeSharedImages() {
        listOf("https://image.tmdb.org.evil.test/t/p/w500/Poster17.jpg", "http://image.tmdb.org/t/p/w500/Poster17.jpg",
            "https://user:secret@image.tmdb.org/t/p/w500/Poster17.jpg", "https://image.tmdb.org/t/p/w500/Poster17.jpg?token=secret",
            "https://image.tmdb.org:8443/t/p/w500/Poster17.jpg", "smb://nas/private/Poster17.jpg", "/private/Poster17.jpg",
            "file:///private/Poster17.jpg", "https://provider/private/image.jpg").forEach {
            assertEquals("", cloudImageReference(it))
        }
        reject { cloudImageUrl("../secret.jpg") }
        reject { IptvCloudItem.from(item).copy(poster = "https://provider/secret").item() }
    }
    private fun selection(url: String, time: Long = 100, writer: String = "device-a") =
        """{"value":"$url","manualArtwork":true,"changedAt":$time,"writer":"$writer"}"""
    @Test fun portraitAndHeroChoicesKeepSeparateTargetsAndRevisions() {
        val metadata = IptvCloudItem.from(item.copy(type = ContentType.MOVIE))
        val poster = requireNotNull(cloudArtworkChoice(selection("https://image.tmdb.org/t/p/w500/Poster17.jpg")))
        val hero = requireNotNull(cloudArtworkChoice(selection("https://image.tmdb.org/t/p/w1280/Backdrop17.jpg", 200)))
        val restored = cloudPlayback(cloudPlayback(IptvCloudPlayback(metadata, artwork = mapOf("thumbnail" to poster, "hero" to hero))))
        assertEquals("w500:Poster17.jpg", restored.artwork.getValue("thumbnail").image)
        assertEquals("w1280:Backdrop17.jpg", restored.artwork.getValue("hero").image)
        val keys = cloudArtworkKeys(metadata)
        assertEquals(com.jojotv.home.MainActivity.externalThumbnailArtworkKey("external-playback|${item.id}"), keys["thumbnail"])
        assertEquals(com.jojotv.home.MainActivity.externalHeroArtworkKey("external-playback|${item.id}"), keys["hero"])
        assertNotEquals(keys["thumbnail"], keys["hero"])
    }
    @Test fun onlyNewerExplicitChoicesReplaceAnExistingManualSelection() {
        val newer = IptvCloudArtwork("Backdrop17.jpg", 200, "device-b")
        assertNotNull(receivedArtworkChoice(selection("https://provider/private/image", 100), newer))
        assertNull(receivedArtworkChoice(selection("https://provider/private/image", 300), newer))
        val applied = requireNotNull(receivedArtworkChoice(null, newer))
        assertEquals(newer, cloudArtworkChoice(applied)); assertNull(receivedArtworkChoice(applied, newer))
        assertNull(cloudArtworkChoice(selection("https://provider/private/image")))
    }
    @Test fun explicitArtworkDeletionRoundTripsWithoutRemovingPlayback() {
        val removed = IptvCloudArtwork("", 200, "device-b", deleted = true)
        val applied = requireNotNull(receivedArtworkChoice(null, removed))
        assertEquals(removed, cloudArtworkChoice(applied))
        val playback = IptvCloudPlayback(IptvCloudItem.from(item), playedAt = 9000, artwork = mapOf("hero" to removed))
        assertEquals(9000L, cloudPlayback(cloudPlayback(playback)).playedAt)
        reject { removed.copy(image = "Poster17.jpg").validate() }
    }
    @Test fun oldPayloadsKeepWorkingAndCannotInjectOtherPreferenceKeys() {
        val old = """{"item":{"id":"iptv:source:movie:17","source":"source","provider":"17","type":"MOVIE","title":"Movie"},"playedAt":100}"""
        assertTrue(cloudPlayback(old).artwork.isEmpty()); assertEquals("", cloudPlayback(old).item.item().artwork)
        val picture = IptvCloudArtwork("Poster17.jpg", 200, "device-b")
        reject { IptvCloudPlayback(IptvCloudItem.from(item), artwork = mapOf("nas_token" to picture)).validate() }
        reject { picture.copy(writer = "http://private/token").validate() }
        reject { picture.copy(changedAt = -1).validate() }
    }
    @Test fun episodeArtworkTargetsMatchTheExistingSeriesAndEpisodeKeys() {
        val metadata = IptvCloudItem.from(item.copy(type = ContentType.EPISODE, seriesId = "55", season = 2, episode = 3))
        val keys = cloudArtworkKeys(metadata)
        val series = "external-playback|${itemId(item.sourceId, ContentType.SERIES, "55")}"
        assertEquals(com.jojotv.home.MainActivity.externalThumbnailArtworkKey("$series|episodes"), keys["episodes"])
        assertEquals(com.jojotv.home.MainActivity.externalThumbnailArtworkKey("$series|season:2|episode:3"), keys["episode"])
        assertEquals(com.jojotv.home.MainActivity.externalHeroArtworkKey(series), keys["seriesHero"])
        assertNotEquals(keys["episode"], cloudArtworkKeys(metadata.copy(episode = 4))["episode"])
    }
    @Test fun homeObserversReceiveChangesAndCanBeRemovedWithoutAPoll() {
        var received = 0
        val observer = Runnable { received++ }
        val before = IptvStore.homeArtworkRevision()
        IptvStore.observeHomeChanges(observer)
        try {
            IptvStore.homeArtworkChanged()
            assertEquals(1, received); assertEquals(before + 1, IptvStore.homeArtworkRevision())
        } finally { IptvStore.removeHomeChangeObserver(observer) }
        IptvStore.homeArtworkChanged(); assertEquals(1, received)
    }
    @Test fun receivedPublicArtworkFillsAMissingPosterWithoutReplacingProviderCredentialsOrRicherMetadata() {
        val movie = item.copy(type = ContentType.MOVIE, artwork = "", backdrop = "", titleLogo = "")
        val before = activityFixture().withFavorite(movie, true, account).checkpointAccount()
        val incoming = IptvCloudItem.from(movie.copy(artwork = "https://image.tmdb.org/t/p/w500/Poster17.jpg")).item()
        val after = before.withSupabaseActivity(account, "favorite", movie.id, incoming, 0, false)
        val restored = after.remembered.single { it.id == movie.id }
        assertEquals("https://image.tmdb.org/t/p/w500/Poster17.jpg", restored.artwork)
        assertEquals(movie.streamUrl, restored.streamUrl); assertEquals(movie.headers, restored.headers)
        assertSame(after, after.withSupabaseActivity(account, "favorite", movie.id, incoming, 0, false))
        val different = incoming.copy(artwork = "https://image.tmdb.org/t/p/original/Other17.jpg")
        assertSame(after, after.withSupabaseActivity(account, "favorite", movie.id, different, 0, false))
    }
    @Test fun catalogueReferenceCanBeRebuiltWithoutCredentials() {
        val clear = cloudPlayback(IptvCloudPlayback(IptvCloudItem.from(item), 2000))
        val local = cloudPlayback(clear).item.item()
        assertEquals(item.id, local.id); assertEquals(item.providerId, local.providerId)
        assertEquals("", local.streamUrl); assertTrue(local.headers.isEmpty())
    }
    @Test fun providerMetadataCompletesALegacyFavoriteEvenWhenItsRuntimeWasAlreadyKnown() {
        val local = item.copy(type = ContentType.MOVIE, description = "", artwork = "", duration = "106 min")
        val details = local.copy(description = "Résumé fournisseur", genre = "Histoire", year = "2026", tmdbId = 170,
            cast = "Acteur A, Actrice B", director = "Réalisatrice", duration = "107 min", artwork = "poster",
            streamUrl = "http://other/input", headers = emptyMap(), command = "other-command")
        val enriched = local.withProviderMetadata(details)
        assertEquals(details.description, enriched.description); assertEquals(details.genre, enriched.genre)
        assertEquals(details.cast, enriched.cast); assertEquals(details.director, enriched.director)
        assertEquals(170, enriched.tmdbId); assertEquals("2026", enriched.year)
        assertEquals("106 min", enriched.duration)
        assertEquals(local.streamUrl, enriched.streamUrl); assertEquals(local.headers, enriched.headers)
        assertEquals(local.command, enriched.command)
    }
    @Test fun providerMetadataDoesNotOverwriteExistingEditorialOrPlaybackInputs() {
        val local = item.copy(type = ContentType.MOVIE, backdrop = "local-background", titleLogo = "local-logo",
            genre = "Genre local", year = "2026", tmdbId = 170, duration = "106 min", cast = "Acteur local", director = "Local")
        val details = local.copy(artwork = "other-poster", backdrop = "other-background", titleLogo = "other-logo",
            genre = "Other", year = "2010", tmdbId = 160, duration = "80 min", cast = "Other", director = "Other",
            description = "Other", streamUrl = "http://other", headers = emptyMap(), command = "other")
        assertEquals(local, local.withProviderMetadata(details))
    }
    @Test fun providerMetadataRequiresTheExactCatalogueIdentity() {
        listOf(item.copy(id = "other"), item.copy(sourceId = "other"), item.copy(providerId = "other"),
            item.copy(type = ContentType.MOVIE)).forEach { wrong -> assertSame(item, item.withProviderMetadata(wrong)) }
    }
    @Test fun receivedIdentityCompletesAnExistingFavoriteWithoutChangingHistoryOrOtherProfiles() {
        val movie = item.copy(type = ContentType.MOVIE, year = "", tmdbId = 0)
        val before = activityFixture().withFavorite(movie, true, account).withPlayback(movie, account, 300)
            .withAccount(IptvAccount("b", "Other")).withPlayback(movie, "b", 700).selectAccount(account).checkpointAccount()
        val incoming = IptvCloudItem.from(movie.copy(tmdbId = 170, year = "2026")).item()
        val after = before.withSupabaseActivity(account, "favorite", movie.id, incoming, 0, false)
        val restored = after.remembered.single { it.id == movie.id }
        assertEquals(170, restored.tmdbId); assertEquals("2026", restored.year)
        assertEquals(before.history, after.history); assertEquals(before.playedAt, after.playedAt)
        assertEquals(before.accountStates, after.accountStates); assertEquals(before.favorites, after.favorites)
        assertEquals(movie.streamUrl, restored.streamUrl); assertEquals(movie.description, restored.description)
        assertSame(after, after.withSupabaseActivity(account, "favorite", movie.id, incoming, 0, false))
    }
    @Test fun urlDisguisedAsProviderIdIsRejected() { reject { IptvCloudItem.from(item.copy(providerId = "http://user:pass@host/17")) } }
    @Test fun titlesCannotSmuggleUrl() { reject { IptvCloudItem.from(item.copy(title = "http://user:pass@host/")) } }
    @Test fun announcedEpisodeCannotBecomeHistory() { reject { IptvCloudItem.from(item.copy(scheduledEpisode = true)) } }
    @Test fun cloudPlanNeverContainsFileOrDirectoryOrPlaybackBytes() {
        val plan = IptvRecording(UUID.randomUUID().toString(), item, "Programme test", 10000, 20000,
            file = "/private/video.ts", directory = "smb://user:pass@host/share", bytes = 987654321,
            error = "secret", accountId = "private-account")
        val wire = cloudPlan(IptvCloudPlan.from(plan))
        listOf("private", "987654321", "smb://", "http://", "secret").forEach { assertFalse(wire.contains(it)) }
        val restored = cloudPlan(wire).recording(account)
        assertEquals(account, restored.accountId); assertEquals("", restored.file); assertEquals("", restored.directory)
    }
    @Test fun commandCannotRescheduleAnotherRecording() {
        val plan = IptvCloudPlan.from(IptvRecording(UUID.randomUUID().toString(), item, "Test", 10000, 20000))
        reject { IptvCloudCommand("EDIT", plan).validate(UUID.randomUUID().toString()) }
    }
    @Test fun byteCountersAndLocalFileUpdatesDoNotChangeTheSharedPlan() {
        val plan = IptvRecording(UUID.randomUUID().toString(), item, "Test", 10000, 20000)
        assertEquals(IptvCloudPlan.from(plan), IptvCloudPlan.from(plan.copy(bytes = 99999, file = "/local/file", error = "local-error")))
    }
    @Test fun cancelCannotCarryAnotherPlan() {
        val plan = IptvCloudPlan.from(IptvRecording(UUID.randomUUID().toString(), item, "Test", 10000, 20000))
        reject { IptvCloudCommand("CANCEL", plan).validate(plan.id) }
    }
    @Test fun nasFilterOnlyCoversCloudActivity() {
        assertTrue(iptvCloudActivityRecord("favorite:item")); assertTrue(iptvCloudActivityRecord("play:item"))
        assertTrue(iptvCloudActivityRecord("account:id:favorite:item")); assertTrue(iptvCloudActivityRecord("account:id:play:item"))
        listOf("source:id", "profile:id", "account:id:setting:audioLanguage", "setting:bufferSize", "searches")
            .forEach { assertFalse(iptvCloudActivityRecord(it)) }
    }
    @Test fun nasCannotReintroduceDeletedFavoriteOrHistoryInCloudMode() {
        val local = IptvLibrary(accounts = listOf(IptvAccount(account, "Test")), selectedAccountId = account,
            legacyAccountId = account, profileActivityScoped = true, accountStates = mapOf(account to IptvAccountState()))
        val remote = local.copy(favorites = setOf(item.id), history = listOf(item.id), playedAt = mapOf(item.id to 9000),
            remembered = listOf(item), accountStates = mapOf(account to IptvAccountState(setOf(item.id), history = listOf(item.id), playedAt = mapOf(item.id to 9000))))
        val protected = preserveCloudIptvActivity(local, remote)
        assertTrue(protected.favorites.isEmpty()); assertTrue(protected.history.isEmpty())
    }
    @Test fun perProfileActivitySurvivesNasSettingsMerge() {
        val local = IptvLibrary(accounts = listOf(IptvAccount(account, "Test"), IptvAccount("b", "Other")),
            selectedAccountId = "b", legacyAccountId = account, profileActivityScoped = true,
            accountStates = mapOf(account to IptvAccountState(setOf(item.id)), "b" to IptvAccountState()))
        val remote = local.copy(selectedAccountId = account, favorites = emptySet())
        val result = preserveCloudIptvActivity(local, remote)
        assertEquals(setOf(item.id), result.favorites); assertTrue(result.accountStates.getValue("b").favorites.isEmpty())
    }

    private fun activityFixture(): IptvLibrary {
        val other = item.copy(id = "iptv:source:live:other", providerId = "18", title = "Autre chaîne")
        return IptvLibrary(sources = listOf(IptvSource("source", "Test", SourceType.XTREAM, "https://example.test")))
            .withAccount(IptvAccount(account, "Test")).withFavorite(item, true, account)
            .withPlayback(item, account, 100).withPlayback(other, account, 200).checkpointAccount()
    }

    @Test fun repeatedCloudFavoriteKeepsTheSameLibraryAndHistoryOrder() {
        val before = activityFixture()
        val after = before.withSupabaseActivity(account, "favorite", item.id, item, 0, false)
        assertSame(before, after)
        assertEquals(before.history, after.history); assertEquals(before.remembered, after.remembered)
    }

    @Test fun repeatedCloudHistoryDoesNotMoveAnOlderPlaybackToTheFront() {
        val before = activityFixture()
        assertNotEquals(item.id, before.history.first())
        val after = before.withSupabaseActivity(account, "history", item.id, item, 100, false)
        assertSame(before, after)
    }

    @Test fun inFlightCloudHistoryCannotUndoAnIndividualLocalDeletion() {
        val before = activityFixture()
        val expected = before.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }
        val deleted = before.withoutHistory(item, account)
        assertNull(deleted.withSupabaseActivityIfUnchanged(account, "history", item.id, item, 100, false, expected))
        assertFalse(item.id in deleted.history); assertTrue(item.id in deleted.favorites)
    }

    @Test fun inFlightCloudHistoryCannotUndoClearingOneAccount() {
        val before = activityFixture().withAccount(IptvAccount("b", "Other"))
            .withPlayback(item, "b", 700).selectAccount(account).checkpointAccount()
        val expected = before.supabaseActivitySnapshot().filter { it.account == account && it.playedAt > 0 }
        val deleted = before.withoutHistory(null, account)
        expected.forEach { old ->
            assertNull(deleted.withSupabaseActivityIfUnchanged(account, "history", old.item.id, old.item, old.playedAt, false, old))
        }
        assertTrue(deleted.history.isEmpty()); assertEquals(before.accountStates["b"], deleted.accountStates["b"])
        assertEquals(before.favorites, deleted.favorites)
    }

    @Test fun unrelatedLocalHistoryDeletionDoesNotBlockRestoringAnotherEntry() {
        val before = activityFixture()
        val expected = before.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }
        val other = before.remembered.first { it.id != item.id }
        val deleted = before.withoutHistory(other, account)
        val restored = requireNotNull(deleted.withSupabaseActivityIfUnchanged(account, "history", item.id, item, 300, false, expected))
        assertEquals(300L, restored.playedAt[item.id]); assertFalse(other.id in restored.history)
    }

    @Test fun favoriteAndHistoryRowsUseSuccessiveAppliedSnapshots() {
        val before = activityFixture().withFavorite(item, false, account).withoutHistory(item, account)
        assertTrue(before.supabaseActivitySnapshot().none { it.account == account && it.item.id == item.id })
        val favorite = requireNotNull(before.withSupabaseActivityIfUnchanged(account, "favorite", item.id, item, 0, false, null))
        val expected = favorite.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }
        val history = requireNotNull(favorite.withSupabaseActivityIfUnchanged(account, "history", item.id, item, 300, false, expected))
        assertTrue(item.id in history.favorites); assertEquals(300L, history.playedAt[item.id])
    }

    @Test fun appliedReceiptDoesNotAcknowledgeALaterLocalHistoryDeletion() {
        val before = activityFixture()
        val expected = before.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }
        val applied = requireNotNull(before.withSupabaseActivityIfUnchanged(account, "history", item.id, item, 300, false, expected))
        val receipt = IptvCloudRestore(applied.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id })
        val deleted = applied.withoutHistory(item, account)
        // Runtime must track the receipt, so its next capture still sees this local deletion.
        assertEquals(300L, receipt.activity!!.playedAt)
        assertEquals(0L, deleted.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }.playedAt)
        assertNotEquals(receipt.activity, deleted.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id })
    }

    @Test fun cloudHistoryDeletionKeepsFavoriteAndOtherAccountHistory() {
        val before = activityFixture().withAccount(IptvAccount("b", "Other"))
            .withPlayback(item, "b", 700).selectAccount(account).checkpointAccount()
        val expected = before.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }
        val deleted = requireNotNull(before.withSupabaseActivityIfUnchanged(account, "history", item.id, null, 0, true, expected))
        assertTrue(item.id in deleted.favorites); assertFalse(item.id in deleted.history)
        assertEquals(before.accountStates["b"], deleted.accountStates["b"])
    }

    @Test fun inFlightCloudFavoriteCannotUndoALocalFavoriteDeletion() {
        val before = activityFixture()
        val expected = before.supabaseActivitySnapshot().single { it.account == account && it.item.id == item.id }
        val deleted = before.withFavorite(item, false, account)
        assertNull(deleted.withSupabaseActivityIfUnchanged(account, "favorite", item.id, item, 0, false, expected))
        assertFalse(item.id in deleted.favorites); assertTrue(item.id in deleted.history)
    }

    @Test fun changedCloudHistoryPreservesOtherProfilesFavoritesAndLocalCredentials() {
        val before = activityFixture().withAccount(IptvAccount("b", "Other"))
            .withPlayback(item, "b", 700).selectAccount(account).checkpointAccount()
        val incoming = cloudPlayback(cloudPlayback(IptvCloudPlayback(IptvCloudItem.from(item), 300))).item.item()
        val after = before.withSupabaseActivity(account, "history", item.id, incoming, 300, false)
        assertNotEquals(before, after)
        assertEquals(300L, after.playedAt[item.id]); assertEquals(item.id, after.history.first())
        assertEquals(before.accountStates["b"], after.accountStates["b"])
        assertEquals(before.favorites, after.favorites)
        val restored = after.remembered.single { it.id == item.id }
        assertEquals(item.streamUrl, restored.streamUrl); assertEquals(item.headers, restored.headers)
    }

    @Test fun repeatedCloudHistoryDeletionIsANoOpAndKeepsTheFavorite() {
        val before = activityFixture().withoutHistory(item, account).checkpointAccount()
        val after = before.withSupabaseActivity(account, "history", item.id, null, 0, true)
        assertSame(before, after); assertTrue(item.id in after.favorites)
    }

    @Test fun repeatedCloudFavoriteDeletionIsANoOpAndKeepsHistory() {
        val before = activityFixture().withFavorite(item, false, account).checkpointAccount()
        val after = before.withSupabaseActivity(account, "favorite", item.id, null, 0, true)
        assertSame(before, after); assertTrue(item.id in after.history)
    }

    @Test fun sameCloudTimestampStillRestoresMissingCatalogueMetadata() {
        val before = activityFixture().let { it.copy(remembered = it.remembered.filterNot { saved -> saved.id == item.id }) }
        val after = before.withSupabaseActivity(account, "history", item.id, item, 100, false)
        assertTrue(after.remembered.any { it.id == item.id }); assertEquals(100L, after.playedAt[item.id])
    }

    @Test fun cloudSeriesDeletionStillRemovesItsFavoriteEpisodeAlias() {
        val series = item.copy(id = itemId("source", ContentType.SERIES, "17"), type = ContentType.SERIES)
        val episode = item.copy(id = itemId("source", ContentType.EPISODE, "19"), providerId = "19", type = ContentType.EPISODE, seriesId = "17")
        val before = activityFixture().withFavorite(episode, true, account).checkpointAccount()
        val after = before.withSupabaseActivity(account, "favorite", series.id, series, 0, true)
        assertFalse(episode.id in after.favorites); assertTrue(item.id in after.favorites)
    }

    @Test fun cloudRestoreDoesNotInventASourceOrAnAccount() {
        val before = activityFixture()
        assertSame(before, before.withSupabaseActivity("missing", "history", item.id, item, 300, false))
        assertSame(before, before.withSupabaseActivity(account, "history", item.id, item.copy(sourceId = "missing"), 300, false))
    }

    @Test fun receivedMovieMetadataUsesThePlayerIdentityWithoutInventingProgress() {
        val movie = item.copy(id = itemId("source", ContentType.MOVIE, "17"), type = ContentType.MOVIE)
        val received = cloudPlayback(cloudPlayback(IptvCloudPlayback(IptvCloudItem.from(movie), 500))).item.item()
        val id = iptvPlaybackContentId(movie.id, account, "legacy")
        val template = requireNotNull(iptvCloudProgressTemplate(received, id))
        assertEquals("movie", template.contentType); assertEquals(id, template.contentId)
        assertEquals(movie.id, template.videoId); assertNull(template.season); assertNull(template.episode)
        assertEquals(0L, template.position); assertEquals(0L, template.duration); assertEquals(1L, template.lastWatched)
        assertNull(template.addonBaseUrl)
    }

    @Test fun receivedEpisodeKeepsTheProviderSeriesSeasonAndEpisodeIdentity() {
        val episode = item.copy(id = itemId("source", ContentType.EPISODE, "19"), providerId = "19",
            type = ContentType.EPISODE, seriesId = "17", season = 2, episode = 3)
        val received = IptvCloudItem.from(episode).item()
        val seriesId = IptvStore.homeContentId(received)
        assertEquals(itemId("source", ContentType.SERIES, "17"), seriesId)
        val id = iptvPlaybackContentId(seriesId, account, "legacy")
        val template = requireNotNull(iptvCloudProgressTemplate(received, id))
        assertEquals("series", template.contentType); assertEquals(id, template.contentId)
        assertEquals(2, template.season); assertEquals(3, template.episode); assertEquals(episode.id, template.videoId)
        assertNotEquals(template.storageKey, template.copy(episode = 4).storageKey)
        assertNotEquals(template.storageKey, template.copy(contentId = iptvPlaybackContentId(seriesId, "other", "legacy")).storageKey)
    }

    @Test fun liveSeriesPostersAndFutureEpisodesCannotCreatePlaybackTemplates() {
        assertNull(iptvCloudProgressTemplate(item, item.id))
        assertNull(iptvCloudProgressTemplate(item.copy(type = ContentType.SERIES), item.id))
        assertNull(iptvCloudProgressTemplate(item.copy(type = ContentType.EPISODE, scheduledEpisode = true), item.id))
    }

    @Test fun receivedFavoriteDeletionChangesHomeProjectionButKeepsHistoryAndOtherProfiles() {
        val movie = item.copy(id = itemId("source", ContentType.MOVIE, "17"), type = ContentType.MOVIE)
        val before = activityFixture().withFavorite(movie, true, account).withPlayback(movie, account, 300)
            .withAccount(IptvAccount("b", "Other")).withFavorite(movie, true, "b").selectAccount(account).checkpointAccount()
        val sources = before.sources.map { it.id }.toSet()
        val beforeProjection = IptvStore.projectHomeFavorites(before.remembered, before.favorites, sources)
        val after = before.withSupabaseActivity(account, "favorite", movie.id, null, 0, true)
        val afterProjection = IptvStore.projectHomeFavorites(after.remembered, after.favorites, sources)
        assertTrue(movie.id in beforeProjection); assertFalse(movie.id in afterProjection)
        assertTrue(movie.id in after.history); assertTrue(movie.id in after.accountStates.getValue("b").favorites)
        assertNotEquals(beforeProjection, afterProjection)
        assertEquals(afterProjection, IptvStore.projectHomeFavorites(after.remembered, after.favorites, sources))
    }
}
