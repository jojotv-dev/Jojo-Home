package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonSyncService"

@Singleton
class AddonSyncService @Inject constructor(
    private val addonPreferences: AddonPreferences,
    private val profileManager: ProfileManager,
    private val nasSyncApi: NasIptvFavoritesApi
) {

    /**
     * Push local addon URLs to the NAS profile snapshot.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val activeProfile = profileManager.activeProfile
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "pushToRemote: activeProfile=${activeProfile?.id} isPrimary=${activeProfile?.isPrimary} usesPrimaryAddons=${activeProfile?.usesPrimaryAddons} profileId=$profileId")

            if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryAddons) {
                Log.d(TAG, "Profile ${activeProfile.id} uses primary addons, skipping push")
                return@withContext Result.success(Unit)
            }

            val localUrls = addonPreferences.installedAddonUrls.first()
                .filterNot(::isBlockedAutomaticAddonUrl)
            val userSetNames = addonPreferences.userSetNames.first()
            val enabledStates = addonPreferences.addonEnabledStates.first()
            Log.d(TAG, "pushToRemote: localUrls count=${localUrls.size} for profile $profileId")
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les extensions." }
            val payload = JSONArray().apply { localUrls.forEachIndexed { index, url ->
                val canonical = canonicalizeUrl(url)
                put(JSONObject().put("url", url).put("sortOrder", index).put("enabled", enabledStates[canonical] ?: true)
                    .put("name", userSetNames[canonical] ?: userSetNames[url]))
            } }.toString()
            nasSyncApi.pushProfileBlobJson(profileId, "addons", payload)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les extensions." }
            val activeProfile = profileManager.activeProfile
            val profileId = if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
            val payload = nasSyncApi.pullProfileBlobJson(profileId, "addons")
            if (payload == null) {
                    // An existing device may connect to a NAS account created before
                    // the addon snapshot existed. Preserve its working addons by
                    // seeding only a genuinely missing remote blob. An explicit []
                    // remains authoritative, so an empty new device can never erase
                    // a populated NAS profile.
                    val localUrls = addonPreferences.installedAddonUrls.first()
                        .filterNot(::isBlockedAutomaticAddonUrl)
                    if (localUrls.isNotEmpty()) {
                        Log.i(TAG, "NAS addon snapshot missing for profile $profileId; seeding ${localUrls.size} local addons")
                        pushToRemote().getOrThrow()
                    }
                    return@withContext Result.success(localUrls)
            }
            val entries = JSONArray(payload)
            val urls = mutableListOf<String>(); val names = mutableMapOf<String, String>(); val enabled = mutableMapOf<String, Boolean>()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val url = item.optString("url").trim(); if (url.isBlank()) continue
                val canonical = canonicalizeUrl(url); urls += url
                item.optString("name").takeIf { it.isNotBlank() }?.let { names[canonical] = it }
                enabled[canonical] = item.optBoolean("enabled", true)
            }
            addonPreferences.setAddonOrder(urls); addonPreferences.setUserSetNames(names); addonPreferences.setAddonEnabledStates(enabled)
            Result.success(urls.filterNot(::isBlockedAutomaticAddonUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs", e)
            Result.failure(e)
        }
    }

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    private fun isBlockedAutomaticAddonUrl(url: String): Boolean {
        val normalized = canonicalizeUrl(url).lowercase()
        return normalized == "https://v3-cinemeta.strem.io" ||
            normalized == "https://opensubtitles-v3.strem.io"
    }
}
