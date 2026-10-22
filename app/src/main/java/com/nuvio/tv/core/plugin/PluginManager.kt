package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RemotePluginInfo
import com.nuvio.tv.domain.model.ScraperInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Web extensions are not included in this build. */
@Singleton class PluginManager @Inject constructor() {
    val repositories: Flow<List<PluginRepository>> = flowOf(emptyList())
    val scrapers: Flow<List<ScraperInfo>> = flowOf(emptyList())
    val pluginsEnabled: Flow<Boolean> = flowOf(false)
    val groupStreamsByRepository: Flow<Boolean> = flowOf(false)
    val enabledScrapers: Flow<List<ScraperInfo>> = flowOf(emptyList())
    var isSyncingFromRemote = false
    fun flushPendingSync() = Unit
    suspend fun addRepository(manifestUrl: String): Result<PluginRepository> = Result.failure(UnsupportedOperationException("Plugins unavailable"))
    suspend fun removeRepository(repoId: String) = Unit
    suspend fun reconcileWithRemoteRepoUrls(remotePlugins: List<RemotePluginInfo>, removeMissingLocal: Boolean = true) = Unit
    @JvmName("reconcileWithRemoteRepoUrlStrings") suspend fun reconcileWithRemoteRepoUrls(remoteUrls: List<String>, removeMissingLocal: Boolean = true) = Unit
    suspend fun refreshRepository(repoId: String): Result<Unit> = Result.failure(UnsupportedOperationException("Plugins unavailable"))
    suspend fun toggleScraper(scraperId: String, enabled: Boolean) = Unit
    suspend fun toggleAllScrapersForRepo(repoId: String, enabled: Boolean) = Unit
    suspend fun setPluginsEnabled(enabled: Boolean) = Unit
    suspend fun setGroupStreamsByRepository(enabled: Boolean) = Unit
    suspend fun executeScrapers(tmdbId: String, mediaType: String, season: Int? = null, episode: Int? = null): List<LocalScraperResult> = emptyList()
    fun executeScrapersStreaming(tmdbId: String, mediaType: String, season: Int? = null, episode: Int? = null): Flow<Pair<ScraperInfo, List<LocalScraperResult>>> = emptyFlow()
    suspend fun executeScraper(scraper: ScraperInfo, tmdbId: String, mediaType: String, season: Int?, episode: Int?): List<LocalScraperResult> = emptyList()
    suspend fun testScraper(scraperId: String): Result<Pair<List<LocalScraperResult>, TestDiagnostics>> = Result.failure(UnsupportedOperationException("Plugins unavailable"))
}
