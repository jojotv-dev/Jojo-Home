package com.nuvio.tv.playercompat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.freebox.FreeboxFileEntry
import com.nuvio.tv.data.freebox.FreeboxOsClient
import com.nuvio.tv.data.freebox.freeboxContentIdForEntry
import com.nuvio.tv.data.freebox.freeboxLegacyContentIdFor
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.FreeboxArtworkCacheDataStore
import com.nuvio.tv.data.local.FreeboxDeletedItemsDataStore
import com.nuvio.tv.data.local.FreeboxDurationCacheDataStore
import com.nuvio.tv.data.local.FreeboxPosterOverrideDataStore
import com.nuvio.tv.data.local.FreeboxSettingsDataStore
import com.nuvio.tv.domain.repository.FreeboxContinueWatchingDeletion
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.scopes.ViewModelScoped
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FreeboxSmbDeletionEntryPoint {
    fun freeboxSettingsDataStore(): FreeboxSettingsDataStore
    fun freeboxOsClient(): FreeboxOsClient
}

/** The confirmed destructive path shared by player-side Freebox deletion. */
@ViewModelScoped
class FreeboxResumeDeletionService @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
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
        if (entry.path.startsWith("smb://", ignoreCase = true)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                deleteViaSmb(entry).getOrThrow()
            }
        } else {
            val settings = freeboxSettingsDataStore.settings.first()
            var activeSettings = if (settings.sessionToken.isNotBlank()) settings else {
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
            progress.contentId == contentId ||
                progress.contentId == legacyContentId ||
                freeboxPathFromContentId(progress.contentId) == entry.path
        }
        val progressTargets = linkedMapOf<String, Pair<Int?, Int?>>()
        removedProgress.forEach { progressTargets[it.contentId] = it.season to it.episode }
        progressTargets.putIfAbsent(contentId, null to null)
        progressTargets.putIfAbsent(legacyContentId, null to null)
        progressTargets.forEach { (id, episode) ->
            watchProgressRepository.removeProgress(id, episode.first, episode.second)
        }
        removedProgress.forEach { freeboxDurationCacheDataStore.remove(freeboxPathFromContentId(it.contentId)) }

        val cached = continueWatchingEnrichmentCache.getInProgressSnapshot()
        val filtered = cached.filterNot { item ->
            item.contentId == contentId ||
                item.contentId == legacyContentId ||
                freeboxPathFromContentId(item.contentId) == entry.path
        }
        if (filtered.size != cached.size) {
            continueWatchingEnrichmentCache.saveInProgressSnapshot(filtered, force = true)
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

    private fun deleteViaSmb(entry: FreeboxFileEntry): Result<Unit> = runCatching {
        val preferences = applicationContext.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val connection = com.jojotv.home.SmbConnectionConfig.fromPrefsString(
            preferences.getString("smb_connections_json", ""),
            preferences.getString("smb_server", ""),
            preferences.getString("smb_user", ""),
            preferences.getString("smb_password", "")
        ).filter {
            android.net.Uri.decode(entry.path).startsWith(android.net.Uri.decode(it.browserUrl()), ignoreCase = true)
        }.maxByOrNull { it.browserUrl().length }
        val user = connection?.user ?: preferences.getString("smb_user", "").orEmpty()
        val password = connection?.password ?: preferences.getString("smb_password", "").orEmpty()
        val properties = java.util.Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "8000")
            setProperty("jcifs.smb.client.soTimeout", "8000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
        }
        val base = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(properties))
        val smbContext = if (user.isBlank()) base else base.withCredentials(
            com.jojotv.home.SmbConnectionConfig.authenticatorFor(user, password)
        )
        val remote = jcifs.smb.SmbFile(entry.path, smbContext)
        com.jojotv.home.MainActivity.deleteSmbTree(remote, applicationContext)
    }

    companion object {
        /** Called only from deletion workers after the shared SMB path has refused a file. */
        @JvmStatic
        fun deleteSmbViaFreeboxOs(context: Context, smbPath: String, expectedSize: Long) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val segments = com.jojotv.home.SmbConnectionConfig.freeboxDeletePathSegments(smbPath)
                check(segments.isNotEmpty()) { "Le chemin SMB ne permet pas d’identifier le fichier Freebox." }
                val dependencies = EntryPointAccessors.fromApplication(
                    context.applicationContext, FreeboxSmbDeletionEntryPoint::class.java)
                val store = dependencies.freeboxSettingsDataStore()
                val settings = store.settings.first()
                check(settings.address.isNotBlank() && settings.appToken.isNotBlank()) {
                    "Accès SMB refusé. Activez Paramètres > Serveurs > Autorisation Freebox dans Jojo Home."
                }
                val smbHost = java.net.URI(smbPath.replace(" ", "%20")).host
                val apiAddress = settings.address.let { if (it.contains("://")) it else "http://$it" }
                val apiHost = java.net.URI(apiAddress.replace(" ", "%20")).host
                check(!apiHost.isNullOrBlank() && (smbHost.equals(apiHost, ignoreCase = true)
                    || java.net.InetAddress.getAllByName(smbHost).toSet()
                        .intersect(java.net.InetAddress.getAllByName(apiHost).toSet()).isNotEmpty())) {
                    "L’autorisation Freebox ne correspond pas au serveur SMB de ce fichier."
                }
                val client = dependencies.freeboxOsClient()
                val session = client.openSession(settings).getOrThrow()
                store.saveSession(session)
                val active = settings.copy(sessionToken = session.sessionToken)
                fun sameName(first: String, second: String) = java.text.Normalizer.normalize(
                    first, java.text.Normalizer.Form.NFC).equals(java.text.Normalizer.normalize(
                    second, java.text.Normalizer.Form.NFC), ignoreCase = true)
                val volume = client.listDirectory(active, "/Freebox").getOrThrow().singleOrNull {
                    it.isDirectory && sameName(it.name, segments.first())
                } ?: error("Le partage SMB ne correspond pas à un disque de la Freebox autorisée.")
                var parent = volume.path
                for (folder in segments.drop(1).dropLast(1)) {
                    parent = client.listDirectory(active, parent).getOrThrow().singleOrNull {
                        it.isDirectory && sameName(it.name, folder)
                    }?.path ?: error("Le dossier SMB n’a pas été retrouvé dans Freebox OS.")
                }
                val target = client.listDirectory(active, parent).getOrThrow().singleOrNull {
                    !it.isDirectory && sameName(it.name, segments.last())
                } ?: error("Le fichier SMB n’a pas été retrouvé dans Freebox OS.")
                val targetSize = target.size ?: 0L
                check(expectedSize <= 0L || targetSize <= 0L || targetSize == expectedSize) {
                    "Le fichier Freebox a changé depuis la demande de suppression. Réouvrez le dossier."
                }
                client.deleteFiles(active, listOf(target)).getOrThrow()
                repeat(8) {
                    if (client.confirmFileAbsent(active, target.path).getOrThrow()) return@runBlocking
                    kotlinx.coroutines.delay(250L)
                }
                error("La Freebox a reçu la demande, mais le fichier est toujours présent.")
            }
        }
    }
}
