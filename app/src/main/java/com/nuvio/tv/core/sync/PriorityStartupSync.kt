package com.nuvio.tv.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun runPriorityStartupSync(
    pullProfileSettings: suspend () -> Unit,
    pullWatchState: suspend () -> Unit,
    pullBroadData: suspend () -> Unit
) {
    coroutineScope {
        awaitAll(
            async { pullProfileSettings() },
            async { pullWatchState() }
        )
    }
    pullBroadData()
}
