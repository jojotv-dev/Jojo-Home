package com.nuvio.tv.data.local

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

/** A profile has one launcher resume per logical content. */
internal fun deduplicateCachedInProgressItems(
    items: List<CachedInProgressItem>
): List<CachedInProgressItem> {
    val latestByContentId = LinkedHashMap<String, CachedInProgressItem>()
    items.forEach { item ->
        val current = latestByContentId[item.contentId]
        if (current == null || item.lastWatched >= current.lastWatched) {
            latestByContentId[item.contentId] = item
        }
    }
    return latestByContentId.values.sortedByDescending { it.lastWatched }
}

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
    }

    private val gson = Gson()
    private val mutex = Mutex()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    @Volatile private var lastNextUpHash = 0
    @Volatile private var lastInProgressHash = 0

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(profileId: Int = profileManager.activeProfileId.value): File {
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = nextUpFile()
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedNextUpItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    /** Reads a specific profile without changing the profile currently used by the app UI. */
    suspend fun getNextUpSnapshot(profileId: Int): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = nextUpFile(profileId)
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedNextUpItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache for profile $profileId: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastNextUpHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastNextUpWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = nextUpFile()
                atomicWrite(file, gson.toJson(items))
                lastNextUpWriteMs = System.currentTimeMillis()
                lastNextUpHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write next-up cache: ${e.message}")
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(profileId: Int = profileManager.activeProfileId.value): File {
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = inProgressFile()
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedInProgressItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    /** Reads a specific profile without changing the profile currently used by the app UI. */
    suspend fun getInProgressSnapshot(profileId: Int): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = inProgressFile(profileId)
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedInProgressItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache for profile $profileId: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(
        items: List<CachedInProgressItem>,
        force: Boolean = false,
        profileId: Int = profileManager.activeProfileId.value
    ) = withContext(Dispatchers.IO) {
        val normalizedItems = deduplicateCachedInProgressItems(items)
        val contentHash = normalizedItems.hashCode()
        if (!force) {
            if (contentHash == lastInProgressHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastInProgressWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = inProgressFile(profileId)
                atomicWrite(file, gson.toJson(normalizedItems))
                lastInProgressWriteMs = System.currentTimeMillis()
                lastInProgressHash = contentHash
                _snapshotVersion.value++
                // Jojo Home reads this exported ContentProvider.  Notify registered
                // observers immediately instead of making it wait for navigation or
                // for a JojoTV profile to be opened again.
                context.contentResolver.notifyChange(
                    Uri.parse("content://${context.packageName}.homebridge"),
                    null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write in-progress cache: ${e.message}")
            }
        }
    }

    /**
     * Updates artwork already exported to Jojo Home without waiting for the next
     * Continue Watching enrichment cycle. A manually selected landscape image must
     * also replace the old episode thumbnail used by launcher cards.
     */
    suspend fun updateInProgressArtwork(
        contentId: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        poster: String?,
        backdrop: String?,
        useBackdropAsThumbnail: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val snapshot = getInProgressSnapshot(profileId)
        val updated = snapshot.map { item ->
            if (
                item.contentId == contentId &&
                item.videoId == videoId &&
                item.season == season &&
                item.episode == episode
            ) {
                item.copy(
                    poster = poster,
                    backdrop = backdrop,
                    episodeThumbnail = if (useBackdropAsThumbnail) backdrop else item.episodeThumbnail
                )
            } else {
                item
            }
        }
        if (updated != snapshot) {
            saveInProgressSnapshot(updated, force = true, profileId = profileId)
        }
    }

    /** Applies the final JojoTV artwork to every matching launcher item in one write. */
    suspend fun updateInProgressArtworkByContentId(
        artwork: Map<String, Pair<String?, String?>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (artwork.isEmpty()) return
        val snapshot = getInProgressSnapshot(profileId)
        val updated = snapshot.map { item ->
            val selected = artwork[item.contentId] ?: return@map item
            val poster = selected.first
            val backdrop = selected.second ?: poster
            item.copy(
                poster = poster,
                backdrop = backdrop,
                // Jojo Home consumes this snapshot directly. Keep the legacy
                // thumbnail slot aligned too so no older reader can resurrect
                // a stale extracted frame.
                episodeThumbnail = backdrop ?: item.episodeThumbnail
            )
        }
        if (updated != snapshot) {
            saveInProgressSnapshot(updated, force = true, profileId = profileId)
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        mutex.withLock {
            for (file in listOf(nextUpFile(profileId), inProgressFile(profileId))) {
                if (file.exists() && !file.delete()) throw java.io.IOException("CW enrichment cache could not be cleared")
            }
            lastNextUpWriteMs = 0L
            lastInProgressWriteMs = 0L
            lastNextUpHash = 0
            lastInProgressHash = 0
            Log.d(TAG, "Cleared CW enrichment cache for profile $profileId")
        }
        _cacheCleared.value++
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}

