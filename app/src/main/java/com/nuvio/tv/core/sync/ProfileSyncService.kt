package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.remote.nas.NasProfilePinVerifyResult
import com.nuvio.tv.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"

sealed class SetProfilePinResult {
    object Success : SetProfilePinResult()
    object CurrentPinRequired : SetProfilePinResult()
    data class Failure(val throwable: Throwable) : SetProfilePinResult()
}

@Singleton
class ProfileSyncService @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager,
    private val nasSyncApi: NasIptvFavoritesApi
) {
    // Profile edits and startup restoration can happen at the same time. Keep
    // the read/replace and write operations ordered so an older NAS snapshot
    // cannot overwrite a freshly selected color/avatar.
    private val profileSyncMutex = Mutex()

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        profileSyncMutex.withLock {
            try {
                val profiles = profileManager.profiles.value
                require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser les profils." }
                val json = JSONArray().apply {
                    profiles.forEach { profile -> put(JSONObject()
                        .put("id", profile.id).put("name", profile.name).put("avatarColorHex", profile.avatarColorHex)
                        .put("usesPrimaryAddons", profile.usesPrimaryAddons).put("usesPrimaryPlugins", profile.usesPrimaryPlugins)
                        .put("avatarId", profile.avatarId).put("avatarUrl", profile.avatarUrl))
                    }
                }.toString()
                nasSyncApi.pushProfileBlobJson(1, "profiles", json)
                Log.d(TAG, "Pushed ${profiles.size} profiles to NAS")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push profiles to remote", e)
                Result.failure(e)
            }
        }
    }

    suspend fun pullFromRemote(): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        profileSyncMutex.withLock {
            try {
                require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer les profils." }
                val json = nasSyncApi.pullProfileBlobJson(1, "profiles") ?: return@withLock Result.success(emptyList())
                val profiles = JSONArray(json).let { values -> buildList {
                    for (index in 0 until values.length()) {
                        val item = values.optJSONObject(index) ?: continue
                        val id = item.optInt("id")
                        val name = item.optString("name").trim()
                        if (id !in 1..ProfileManager.MAX_PROFILES || name.isBlank()) continue
                        add(UserProfile(id, name, item.optString("avatarColorHex", "#1E88E5"),
                            item.optBoolean("usesPrimaryAddons", false), item.optBoolean("usesPrimaryPlugins", false),
                            item.optString("avatarId").ifBlank { null }, item.optString("avatarUrl").ifBlank { null }))
                    }
                } }
                if (profiles.isNotEmpty()) profileDataStore.replaceAllProfiles(profiles)
                Log.d(TAG, "Pulled ${profiles.size} profiles from NAS")
                Result.success(profiles)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull profiles from remote", e)
                Result.failure(e)
            }
        }
    }

    suspend fun deleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour supprimer les données du profil." }
            nasSyncApi.deleteProfileData(profileId)
            Log.d(TAG, "Deleted NAS data for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote profile data for profile $profileId", e)
            Result.failure(e)
        }
    }

    suspend fun pullProfileLockStates(): Result<Map<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour lire les codes PIN." }
            Result.success(nasSyncApi.pullProfilePins())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile lock states", e)
            Result.failure(e)
        }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): SetProfilePinResult = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour modifier le code PIN." }
            nasSyncApi.setProfilePin(profileId, pin, currentPin)
            SetProfilePinResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set profile PIN", e)
            if (isCurrentPinRequiredError(e)) {
                SetProfilePinResult.CurrentPinRequired
            } else {
                SetProfilePinResult.Failure(e)
            }
        }
    }

    private fun isCurrentPinRequiredError(e: Throwable): Boolean =
        e.message?.contains("Current PIN is required", ignoreCase = true) == true

    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour supprimer le code PIN." }
            nasSyncApi.clearProfilePin(profileId, currentPin)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear profile PIN", e)
            Result.failure(e)
        }
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<NasProfilePinVerifyResult> = withContext(Dispatchers.IO) {
        try {
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour vérifier le code PIN." }
            Result.success(NasProfilePinVerifyResult(unlocked = nasSyncApi.verifyProfilePin(profileId, pin), retryAfterSeconds = 0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify profile PIN", e)
            Result.failure(e)
        }
    }
}
