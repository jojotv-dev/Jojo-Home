package com.nuvio.tv.core.sync

import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LibrarySyncService"

@Singleton
class LibrarySyncService @Inject constructor(
    private val libraryPreferences: LibraryPreferences,
    private val profileManager: ProfileManager,
    private val nasSyncApi: NasIptvFavoritesApi
) {
    private val gson = Gson()

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val items = libraryPreferences.getAllItems()
            val profileId = profileManager.activeProfileId.value
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser la bibliothèque." }
            nasSyncApi.pushProfileBlobJson(profileId, "library", gson.toJson(items))
            Log.d(TAG, "Pushed ${items.size} library items to NAS for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push library to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<List<SavedLibraryItem>> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer la bibliothèque." }
            val payload = nasSyncApi.pullProfileBlobJson(profileId, "library")
                ?: return@withContext Result.success(emptyList())
            Result.success(gson.fromJson(payload, Array<SavedLibraryItem>::class.java)?.toList().orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull library from remote", e)
            Result.failure(e)
        }
    }
}
