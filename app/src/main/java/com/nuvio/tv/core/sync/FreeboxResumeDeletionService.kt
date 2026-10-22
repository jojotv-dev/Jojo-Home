package com.nuvio.tv.core.sync

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.FreeboxOsClient
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxLegacyContentIdFor
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.local.FreeboxArtworkCacheDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.FreeboxDeletedItemsDataStore
import com.nuvio.tv.data.local.FreeboxDurationCacheDataStore
import com.nuvio.tv.data.local.FreeboxPosterOverrideDataStore
import com.nuvio.tv.data.local.FreeboxSettingsDataStore
import com.nuvio.tv.domain.repository.FreeboxContinueWatchingDeletion
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** The single destructive Freebox path used by Home and inter-app requests. */
@Singleton
class FreeboxResumeDeletionService @Inject constructor(
    private val freeboxSettingsDataStore: FreeboxSettingsDataStore,
    private val freeboxOsClient: FreeboxOsClient,
    private val watchProgressRepository: WatchProgressRepository,
    private val freeboxDeletedItemsDataStore: FreeboxDeletedItemsDataStore,
    private val freeboxDurationCacheDataStore: FreeboxDurationCacheDataStore,
    private val freeboxPosterOverrideDataStore: FreeboxPosterOverrideDataStore,
    private val freeboxArtworkCacheDataStore: FreeboxArtworkCacheDataStore,
    private val continueWatchingEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val profileManager: ProfileManager
) {
    suspend fun delete(entry: FreeboxFileEntry): Result<Unit> = runCatching {
        val contentId = freeboxContentIdForEntry(entry)
        val legacyContentId = freeboxLegacyContentIdFor(contentId)
        val settings = freeboxSettingsDataStore.settings.first()
        var activeSettings = if (settings.sessionToken.isNotBlank()) {
            settings
        } else {
            val session = freeboxOsClient.openSession(settings).getOrThrow()
            freeboxSettingsDataStore.saveSession(session)
            settings.copy(sessionToken = session.sessionToken)
        }
        var deleteResult = freeboxOsClient.deleteFiles(activeSettings, listOf(entry))
        if (deleteResult.isFailure && settings.sessionToken.isNotBlank()) {
            val refreshedSession = freeboxOsClient.openSession(settings).getOrThrow()
            freeboxSettingsDataStore.saveSession(refreshedSession)
            activeSettings = settings.copy(sessionToken = refreshedSession.sessionToken)
            deleteResult = freeboxOsClient.deleteFiles(activeSettings, listOf(entry))
        }
        deleteResult.getOrThrow()
        check(freeboxOsClient.confirmFileAbsent(activeSettings, entry.path).getOrThrow()) {
            "La Freebox a accepté la demande, mais le fichier est toujours présent."
        }

        coroutineScope {
            listOf(
                async { freeboxDeletedItemsDataStore.remember(entry.path, contentId) },
                async { freeboxDurationCacheDataStore.remove(entry.path) },
                async { freeboxPosterOverrideDataStore.remove(listOf(contentId, legacyContentId)) },
                async { freeboxArtworkCacheDataStore.remove(listOf(contentId, legacyContentId)) }
            ).awaitAll()
        }

        val removedProgress = watchProgressRepository.allProgress.first().filter { progress ->
            progress.contentId == contentId || progress.contentId == legacyContentId ||
                freeboxPathFromContentId(progress.contentId) == entry.path
        }
        val progressTargets = linkedMapOf<String, Pair<Int?, Int?>>()
        removedProgress.forEach { progressTargets[it.contentId] = it.season to it.episode }
        progressTargets.putIfAbsent(contentId, null to null)
        progressTargets.putIfAbsent(legacyContentId, null to null)
        progressTargets.forEach { (id, episode) ->
            watchProgressRepository.removeProgress(id, episode.first, episode.second)
        }
        removedProgress.forEach {
            freeboxDurationCacheDataStore.remove(freeboxPathFromContentId(it.contentId))
        }
        val cachedInProgress = continueWatchingEnrichmentCache.getInProgressSnapshot()
        val cachedAfterDeletion = cachedInProgress.filterNot { cached ->
            cached.contentId == contentId || cached.contentId == legacyContentId ||
                freeboxPathFromContentId(cached.contentId) == entry.path
        }
        if (cachedAfterDeletion.size != cachedInProgress.size) {
            continueWatchingEnrichmentCache.saveInProgressSnapshot(cachedAfterDeletion, force = true)
        }
        watchProgressRepository.notifyFreeboxContinueWatchingDeletion(
            FreeboxContinueWatchingDeletion(
                profileId = profileManager.activeProfileId.value,
                contentId = contentId,
                legacyContentId = legacyContentId,
                path = entry.path,
                season = removedProgress.firstOrNull()?.season,
                episode = removedProgress.firstOrNull()?.episode
            )
        )
    }
}
