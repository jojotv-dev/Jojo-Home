package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvRecommendationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val programBuilder: ProgramBuilder
) {

    private val mutex = Mutex()

    private companion object {
        const val TAG = "TvRecommendation"
    }

    suspend fun updateWatchNextFromCwItems(profileId: Int, items: List<ContinueWatchingItem>) {
        if (!isTvDevice()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val inProgress = items
                        .filterIsInstance<ContinueWatchingItem.InProgress>()

                    val desiredIds = inProgress.map { item ->
                        programBuilder.watchNextId(item.progress, profileId)
                    }.toSet()
                    programBuilder.removeWatchNextProgramsForProfileExcept(profileId, desiredIds)
                    inProgress.forEach { item ->
                        val program = programBuilder.buildWatchNextProgram(item.progress, profileId)
                        programBuilder.upsertWatchNextProgram(
                            program,
                            programBuilder.watchNextId(item.progress, profileId)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updateWatchNextFromCwItems failed", e)
                }
            }
        }
    }

    suspend fun onProgressRemoved(contentId: String) {
        if (!isTvDevice()) return
        withContext(Dispatchers.IO) {
            try {
                programBuilder.removeWatchNextByContentId(contentId)
            } catch (_: Exception) {
            }
        }
    }

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
