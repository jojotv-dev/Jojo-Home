package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.RemotePluginInfo
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginSyncService"

@Singleton
class PluginSyncService @Inject constructor(
    private val pluginDataStore: PluginDataStore,
    private val profileManager: ProfileManager,
    private val nasSyncApi: NasIptvFavoritesApi
) {

    /**
     * Push local plugin repository URLs to the NAS profile snapshot.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val activeProfile = profileManager.activeProfile
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "pushToRemote: activeProfile=${activeProfile?.id} isPrimary=${activeProfile?.isPrimary} usesPrimaryPlugins=${activeProfile?.usesPrimaryPlugins} profileId=$profileId")

            if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryPlugins) {
                Log.d(TAG, "Profile ${activeProfile.id} uses primary plugins, skipping push")
                return@withContext Result.success(Unit)
            }

            val localRepos = pluginDataStore.repositories.first()
            Log.d(TAG, "pushToRemote: localRepos count=${localRepos.size} for profile $profileId")
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les plugins." }
            val payload = JSONArray().apply { localRepos.forEach { repo -> put(JSONObject()
                .put("id", repo.id).put("name", repo.name).put("url", repo.url).put("description", repo.description)
                .put("enabled", repo.enabled).put("lastUpdated", repo.lastUpdated).put("scraperCount", repo.scraperCount).put("type", repo.type.name)) } }.toString()
            nasSyncApi.pushProfileBlobJson(profileId, "plugins", payload)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push plugins to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteRepoUrls(): Result<List<RemotePluginInfo>> = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les plugins." }
            val activeProfile = profileManager.activeProfile
            val profileId = if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryPlugins) 1
                            else profileManager.activeProfileId.value
            val payload = nasSyncApi.pullProfileBlobJson(profileId, "plugins") ?: return@withContext Result.success(emptyList())
            val entries = JSONArray(payload)
            val repos = buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim(); if (url.isBlank()) continue
                    add(PluginRepository(item.optString("id").ifBlank { url }, item.optString("name").ifBlank { url }, url,
                        item.optString("description").ifBlank { null }, item.optBoolean("enabled", true), item.optLong("lastUpdated"), item.optInt("scraperCount"),
                        runCatching { RepositoryType.valueOf(item.optString("type")) }.getOrDefault(RepositoryType.NUVIO_JS)))
                }
            }
            pluginDataStore.saveRepositories(repos)
            Result.success(repos.map { RemotePluginInfo(it.url, it.type.name) })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote repo URLs", e)
            Result.failure(e)
        }
    }
}
