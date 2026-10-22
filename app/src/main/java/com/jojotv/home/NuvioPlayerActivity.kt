package com.jojotv.home

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.data.freebox.freeboxPathFromContentId
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import java.util.concurrent.Executors
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.nuvio.tv.core.player.ExternalPlayerLauncher
import com.nuvio.tv.core.player.ExternalPlayerResultContract
import java.io.File

@AndroidEntryPoint
class NuvioPlayerActivity : ComponentActivity() {
    private val homeResumePlaybackStartedAt = System.currentTimeMillis()
    private var streamUrlForCleanup: String = ""
    private var resumeTitle: String = ""
    private var resumeContentId: String = ""
    private var resumeContentType: String = ""
    private var resumeFilename: String = ""
    @Volatile private var resumeProfileId: Int? = null
    private var activePlayerViewModel: PlayerViewModel? = null
    @Volatile private var externalPlaybackEnded = false
    private var latestHomeResumeSnapshotAtMs = 0L
    private var nextSmbEpisodeId: String? = null
    @Volatile private var explicitExitSaveCompleted = false
    @Volatile private var homeFileResumeCompleted = false
    private var exitDispatched = false
    private var externalPlayerPending = false
    private val externalResultContract = ExternalPlayerResultContract()
    private val defaultPlayerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned ->
        externalPlayerPending = false
        val result = externalResultContract.parseResult(returned.resultCode, returned.data)
        if (result != null) {
            if (intent.getBooleanExtra(EXTRA_IPTV_RESULT, false) && !result.endedByUser &&
                (result.durationMs ?: 0L) > 0L && result.positionMs >= (result.durationMs ?: 0L) - 1500L) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_IPTV_COMPLETED, true))
            }
            saveHomeResume(result.positionMs, result.durationMs ?: 0L, streamUrlForCleanup, result.positionMs > 0L)
            if (!result.endedByUser) {
                if (resumeContentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX)) {
                    val id = resumeContentId.removePrefix(ExternalPlaybackStore.CONTENT_PREFIX)
                    ExternalPlaybackStore.markWatched(this, id)
                    ExternalPlaybackStore.removeFromContinueWatching(this, id)
                } else if (isHomeFileResumePlayback()) {
                    removeHomeFileResume(null)
                }
            }
        }
        finishPlayback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumeProfileId = HomeWatchProgressBridge.currentProfileId(this)
        val externalUri = intent.data
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
            .ifBlank { externalUri?.toString().orEmpty() }
        streamUrlForCleanup = streamUrl
        val externalName = externalUri?.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { externalName }
        val contentId = intent.getStringExtra(EXTRA_CONTENT_ID).orEmpty()
            .ifBlank { if (externalUri != null) "external:$streamUrl" else "" }
        val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE).orEmpty()
            .ifBlank { if (externalUri != null) "external" else "" }
        val filename = intent.getStringExtra(EXTRA_FILENAME).orEmpty()
            .ifBlank { externalName }
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0).takeIf { it > 0 }
        val tmdbMediaType = intent.getStringExtra(EXTRA_TMDB_MEDIA_TYPE).orEmpty()
        val titleLogo = intent.getStringExtra(EXTRA_TITLE_LOGO).orEmpty().ifBlank {
            if (contentType.equals("freebox", ignoreCase = true) ||
                contentType.equals("video", ignoreCase = true) || contentId.startsWith("freebox:", ignoreCase = true)
            ) HomeMediaRules.programmeTitleLogoFallback(title.ifBlank { filename }, tmdbId ?: 0, tmdbMediaType)
            else ""
        }
        val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it > 0 }
        val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
        val episodeTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE).orEmpty()
        val startFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        val playerAccent = intent.getIntExtra(EXTRA_PLAYER_ACCENT, DEFAULT_PLAYER_ACCENT)
        val folderAccent = Color(MainActivity.folderAccentColor(this))
        resumeTitle = title
        resumeContentId = contentId
        resumeContentType = contentType
        resumeFilename = filename
        externalPlayerPending = savedInstanceState?.getBoolean("defaultPlayerPending", false) ?: false
        if (externalPlayerPending || tryLaunchDefaultPlayer(startFromBeginning)) return
        Log.i(
            "JojoHomeFreeboxTrace",
            "ACTIVITY_CREATE at=${android.os.SystemClock.elapsedRealtime()} type=$contentType streamScheme=${Uri.parse(streamUrl).scheme}"
        )

        setContent {
            NuvioTheme(customAccent = Color(playerAccent)) {
                val navController = rememberNavController()
                val startRoute = remember(streamUrl, title, contentId, contentType, filename, description, titleLogo, tmdbId, tmdbMediaType, season, episode, episodeTitle, startFromBeginning) {
                    buildRoute(streamUrl, title, contentId, contentType, filename, description, titleLogo, tmdbId, tmdbMediaType, season, episode, episodeTitle, startFromBeginning)
                }
                NavHost(navController = navController, startDestination = startRoute) {
                    composable(
                        route = PLAYER_ROUTE,
                        arguments = listOf(
                            navArgument("streamUrl") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType; nullable = true },
                            navArgument("contentId") { type = NavType.StringType; nullable = true },
                            navArgument("contentType") { type = NavType.StringType; nullable = true },
                            navArgument("filename") { type = NavType.StringType; nullable = true },
                            navArgument("headers") { type = NavType.StringType; defaultValue = "" },
                            navArgument("iptvDns") { type = NavType.StringType; defaultValue = "" },
                            navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                            navArgument("backdrop") { type = NavType.StringType; defaultValue = "" },
                            navArgument("presentation") { type = NavType.StringType; defaultValue = "" },
                            navArgument("streamDescription") { type = NavType.StringType; nullable = true },
                            navArgument("logo") { type = NavType.StringType; nullable = true },
                            navArgument("tmdbId") { type = NavType.IntType; defaultValue = 0 },
                            navArgument("tmdbMediaType") { type = NavType.StringType; nullable = true },
                            navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                            navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                            navArgument("episodeTitle") { type = NavType.StringType; nullable = true },
                            navArgument("startFromBeginning") { type = NavType.StringType; defaultValue = "false" }
                        )
                    ) { playerEntry ->
                        // Use the entry's decoded arguments consistently, including the
                        // fallback values resolved above for external playback.
                        val playerRouteEnteredAt = android.os.SystemClock.elapsedRealtime()
                        Log.i("JojoHomeFreeboxTrace", "PLAYER_ROUTE_ENTER at=$playerRouteEnteredAt")
                        val playerViewModel: PlayerViewModel = hiltViewModel(playerEntry)
                        Log.i(
                            "JojoHomeFreeboxTrace",
                            "PLAYER_VIEWMODEL_READY at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - playerRouteEnteredAt}ms"
                        )
                        activePlayerViewModel = playerViewModel
                        LaunchedEffect(playerViewModel, "iptv-watch-time") {
                            if (resumeContentId.startsWith("iptv:") && contentType == "iptv") {
                                val store = com.jojotv.home.iptv.IptvStore(this@NuvioPlayerActivity)
                                val clock = com.jojotv.home.iptv.IptvWatchClock()
                                val accountId = store.playbackAccountId(resumeContentId)
                                suspend fun record(playing: Boolean) {
                                    val elapsed = clock.update(playing, android.os.SystemClock.elapsedRealtime())
                                    if (elapsed > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        store.recordWatchTime(com.jojotv.home.iptv.IptvStore.catalogContentId(resumeContentId), elapsed, accountId)
                                    }
                                }
                                try {
                                    playerViewModel.uiState.map { it.isPlaying }.distinctUntilChanged().collect { record(it) }
                                } finally {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(false) }
                                }
                            }
                        }
                        LaunchedEffect(playerViewModel, "external-start") {
                            if (resumeContentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX)) {
                                var markedStarted = false
                                playerViewModel.uiState.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                                    if (playing && !markedStarted) {
                                        markedStarted = true
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            if (resumeProfileId == HomeWatchProgressBridge.currentProfileId(this@NuvioPlayerActivity)) {
                                                ExternalPlaybackStore.markStarted(this@NuvioPlayerActivity,
                                                    resumeContentId.removePrefix(ExternalPlaybackStore.CONTENT_PREFIX))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        LaunchedEffect(playerViewModel) {
                            if (resumeContentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX)) {
                                while (true) {
                                    delay(5000)
                                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                                        saveHomeResumeWhilePlaying(playerViewModel)
                                    }
                                }
                            } else if (isHomeFileResumePlayback()) {
                                // Publish the first real file checkpoint while
                                // playback is active. Relying only on Activity
                                // exit dropped files whose final lifecycle
                                // timeline was still at zero.
                                while (true) {
                                    delay(500)
                                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                                        playerViewModel.uiState.value.isPlaying &&
                                        playerViewModel.playbackTimeline.value.currentPosition >= 1000L
                                    ) {
                                        saveHomeResumeWhilePlaying(playerViewModel, saveWatchProgress = false)
                                        break
                                    }
                                }
                            }
                        }
                        PlayerScreen(
                            viewModel = playerViewModel,
                            errorAccentColor = folderAccent,
                            onBeforeExit = { saveHomeResumeBeforeExit(playerViewModel) },
                            onIptvNavigation = if (resumeContentId.startsWith("iptv:") && contentType == "iptv" &&
                                intent.getBooleanExtra(EXTRA_IPTV_NAVIGATION_ENABLED, false)) { action ->
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_IPTV_NAVIGATION_ACTION, action)
                                    .putExtra(EXTRA_CONTENT_ID, com.jojotv.home.iptv.IptvStore.catalogContentId(resumeContentId)))
                                finishPlayback()
                            } else null,
                            onBackPress = { selectedVideoId, _, _, _, _ ->
                                if (resumeContentId.startsWith("freebox:smb:") && selectedVideoId != null &&
                                    selectedVideoId != resumeContentId &&
                                    com.nuvio.tv.ui.screens.player.FreeboxLocalEpisodeCache.get(resumeContentId)
                                        .any { it.id == selectedVideoId }) {
                                    nextSmbEpisodeId = selectedVideoId
                                }
                                Log.i("JojoHomeFreeboxTrace", "EXIT_NAVIGATE at=${android.os.SystemClock.elapsedRealtime()} contentId=$resumeContentId")
                                finishPlayback()
                            },
                            onPlaybackErrorBack = {
                                Log.i("JojoHomeFreeboxTrace", "EXIT_ERROR_NAVIGATE at=${android.os.SystemClock.elapsedRealtime()} contentId=$resumeContentId")
                                finishPlayback()
                            },
                            onPlaybackEnded = { _, _, _, _ ->
                                if (resumeContentId.startsWith("iptv:") && intent.getBooleanExtra(EXTRA_IPTV_RESULT, false)) {
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_IPTV_COMPLETED, true))
                                }
                                if (resumeContentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX)) {
                                    externalPlaybackEnded = true
                                    ExternalPlaybackStore.markWatched(
                                        this@NuvioPlayerActivity,
                                        resumeContentId.removePrefix(ExternalPlaybackStore.CONTENT_PREFIX)
                                    )
                                    // A completed external item must leave
                                    // Continue Watching. The store keeps a
                                    // favorite in its favorite rail while
                                    // removing only its playback state; a
                                    // non-favorite is tombstoned instead.
                                    ExternalPlaybackStore.removeFromContinueWatching(
                                        this@NuvioPlayerActivity,
                                        resumeContentId.removePrefix(ExternalPlaybackStore.CONTENT_PREFIX)
                                    )
                                } else if (isHomeFileResumePlayback()) {
                                    // A completed local/Freebox video must leave
                                    // Continue Watching instead of being saved
                                    // again by the activity lifecycle callbacks.
                                    explicitExitSaveCompleted = true
                                    homeFileResumeCompleted = true
                                    removeHomeFileResume(activePlayerViewModel)
                                }
                                Log.i("JojoHomeFreeboxTrace", "EXIT_ENDED_NAVIGATE at=${android.os.SystemClock.elapsedRealtime()} contentId=$resumeContentId")
                                finishPlayback()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Log.i("JojoHomeFreeboxTrace", "ACTIVITY_DESTROY contentId=$resumeContentId")
        // Fallback for destruction before PlayerScreen's DisposableEffect is
        // reached. stopAndRelease() is idempotent and posts the teardown so
        // Activity destruction is never held up by decoder/network cleanup.
        runCatching { activePlayerViewModel?.stopAndRelease() }
        if (!externalPlayerPending) MainActivity.stopSmbVideoProxy(streamUrlForCleanup)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("defaultPlayerPending", externalPlayerPending)
        super.onSaveInstanceState(outState)
    }

    private fun tryLaunchDefaultPlayer(startFromBeginning: Boolean): Boolean {
        val prefs = getSharedPreferences("home_settings", MODE_PRIVATE)
        val preferredPackage = if (intent.hasExtra(EXTRA_PLAYER_PACKAGE)) {
            intent.getStringExtra(EXTRA_PLAYER_PACKAGE).orEmpty()
        } else prefs.getString("default_player_package", "").orEmpty()
        if (preferredPackage.isBlank() || preferredPackage == packageName) return false
        return try {
            val sourceUri = Uri.parse(streamUrlForCleanup)
            val playbackUri = if (sourceUri.scheme == "file") {
                FileProvider.getUriForFile(this, "$packageName.files", File(requireNotNull(sourceUri.path)))
            } else sourceUri
            val source = if (resumeContentType.equals("freebox", ignoreCase = true)) {
                freeboxPathFromContentId(resumeContentId)
            } else streamUrlForCleanup
            val resumePosition = when {
                startFromBeginning -> 0L
                resumeContentId.startsWith("iptv:") -> com.jojotv.home.iptv.IptvStore.playbackResumePosition(this,
                    resumeContentId, intent.getIntExtra(EXTRA_SEASON, 0).takeIf { it > 0 },
                    intent.getIntExtra(EXTRA_EPISODE, 0).takeIf { it > 0 })
                resumeContentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX) ->
                    ExternalPlaybackStore.get(this, resumeContentId.removePrefix(ExternalPlaybackStore.CONTENT_PREFIX))
                        .optLong("position", 0L)
                else -> prefs.getLong("video_resume_$source", 0L)
            }
            val externalIntent = externalResultContract.createIntent(this,
                ExternalPlayerLauncher.createInput(playbackUri.toString(), resumeTitle,
                    headers = runCatching { com.jojotv.home.iptv.iptvJson.decodeFromString<Map<String, String>>(
                        intent.getStringExtra(EXTRA_STREAM_HEADERS).orEmpty()) }.getOrNull(), resumePositionMs = resumePosition))
                .setPackage(preferredPackage)
                .putExtra("from_start", startFromBeginning)
            if (startFromBeginning) {
                externalIntent.putExtra("position", 0).putExtra("startfrom", 0)
            }
            if (playbackUri.scheme == "content") {
                externalIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                externalIntent.clipData = android.content.ClipData.newRawUri("Vidéo", playbackUri)
            }
            if (packageManager.resolveActivity(externalIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null) {
                Toast.makeText(this, "Lecteur par défaut indisponible pour cette vidéo : lecture avec JojoHome", Toast.LENGTH_LONG).show()
                return false
            }
            externalPlayerPending = true
            defaultPlayerLauncher.launch(externalIntent)
            true
        } catch (error: Exception) {
            externalPlayerPending = false
            if (resumeContentId.startsWith("iptv:")) Log.w("JojoHomePlayer", "Impossible de lancer le lecteur IPTV par défaut")
            else Log.w("JojoHomePlayer", "Impossible de lancer le lecteur par défaut", error)
            Toast.makeText(this, "Impossible d’ouvrir le lecteur par défaut : lecture avec JojoHome", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun finishPlayback() {
        if (exitDispatched) return
        val finishStartedAt = android.os.SystemClock.elapsedRealtime()
        Log.i("JojoHomeFreeboxTrace", "FINISH_PLAYBACK_BEGIN at=$finishStartedAt")
        exitDispatched = true
        val returnToTiviMate = intent.getBooleanExtra(EXTRA_RETURN_TO_TIVIMATE, false)
        val tiviMateLaunch = if (returnToTiviMate) runCatching {
            packageManager.getLaunchIntentForPackage(TIVIMATE_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }.getOrNull() else null
        // Close this Activity before exposing the previous screen. The former
        // order launched the destination first, so the underlying information
        // page stayed visible while the player and its surface were tearing down.
        val returnContext = Intent()
        var hasReturnContext = false
        nextSmbEpisodeId?.let {
            returnContext.putExtra(EXTRA_NEXT_SMB_EPISODE, it)
            hasReturnContext = true
        }
        intent.getStringExtra(EXTRA_RETURN_FOLDER_URL)?.takeIf { it.isNotBlank() }?.let {
            returnContext.putExtra(EXTRA_RETURN_FOLDER_URL, it)
            hasReturnContext = true
        }
        intent.getStringExtra(EXTRA_RETURN_SERIES_ID)?.takeIf { it.isNotBlank() }?.let {
            returnContext.putExtra(EXTRA_RETURN_SERIES_ID, it)
            hasReturnContext = true
        }
        intent.getStringExtra(EXTRA_RETURN_HOME_CATEGORY)?.takeIf { it.isNotBlank() }?.let {
            returnContext.putExtra(EXTRA_RETURN_HOME_CATEGORY, it)
            hasReturnContext = true
        }
        if (hasReturnContext) {
            // The player may have advanced to another episode or season before exiting.
            val playbackState = activePlayerViewModel?.uiState?.value
            val returnSeason = playbackState?.currentSeason ?: intent.getIntExtra(EXTRA_SEASON, -1)
            val returnEpisode = playbackState?.currentEpisode ?: intent.getIntExtra(EXTRA_EPISODE, -1)
            if (returnSeason > 0) returnContext.putExtra(EXTRA_SEASON, returnSeason)
            if (returnEpisode > 0) returnContext.putExtra(EXTRA_EPISODE, returnEpisode)
            setResult(RESULT_OK, returnContext)
        }
        finish()
        overridePendingTransition(0, 0)
        Log.i(
            "JojoHomeFreeboxTrace",
            "FINISH_PLAYBACK_RETURN at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - finishStartedAt}ms"
        )
        if (tiviMateLaunch != null) {
            android.os.Handler(mainLooper).post {
                runCatching { applicationContext.startActivity(tiviMateLaunch) }
            }
        }
    }

    override fun onPause() {
        // Pressing Home or switching input pauses the player without going
        // through PlayerScreen's explicit exit callback. Persist the current
        // Freebox/SMB position before the activity is backgrounded.
        Log.i("JojoHomeFreeboxTrace", "ACTIVITY_PAUSE contentId=$resumeContentId")
        // Keep this activity-level guard in addition to the Compose lifecycle
        // observer: a pause can arrive before the player composition is fully
        // attached, and IPTV must not continue playing in that window.
        if (!exitDispatched) runCatching { activePlayerViewModel?.pauseForLifecycle() }
        saveHomeResumeFromLifecycle()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val keyCode = event.keyCode
        val navigationKey = keyCode == android.view.KeyEvent.KEYCODE_BACK ||
            keyCode == android.view.KeyEvent.KEYCODE_ESCAPE ||
            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == android.view.KeyEvent.KEYCODE_HOME
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 && navigationKey) {
            Log.i(
                "JojoPlayerInputTrace",
                "KEY at=${android.os.SystemClock.elapsedRealtime()} code=$keyCode " +
                    "device=${event.deviceId} source=${event.source}"
            )
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        // Some TV launchers dispatch onPause before the playback timeline has
        // received its final position. Retry once the player is fully hidden.
        Log.i("JojoHomeFreeboxTrace", "ACTIVITY_STOP contentId=$resumeContentId")
        saveHomeResumeFromLifecycle()
        super.onStop()
    }

    private fun saveHomeResumeFromLifecycle() {
        if (explicitExitSaveCompleted) return
        runCatching {
            val timeline = activePlayerViewModel?.playbackTimeline?.value
            Log.i(
                "JojoHomeFreeboxTrace",
                "LIFECYCLE_SAVE position=${timeline?.currentPosition ?: -1L} duration=${timeline?.duration ?: -1L} contentId=$resumeContentId"
            )
            activePlayerViewModel?.let(::saveHomeResume)
        }
    }

    private fun saveHomeResumeBeforeExit(viewModel: PlayerViewModel) {
        if (explicitExitSaveCompleted) return
        // Read the player on its application thread before disposal. The
        // background writer must only receive this immutable checkpoint.
        val timeline = viewModel.playbackTimeline.value
        val position = timeline.currentPosition
        val duration = timeline.duration
        val streamUrl = viewModel.getCurrentStreamUrl()
        val isPlaying = viewModel.exoPlayer?.isPlaying == true
        val metadata = homeVideoMetadataSnapshot(viewModel)
        explicitExitSaveCompleted = true
        val saveQueuedAt = android.os.SystemClock.elapsedRealtime()
        Log.i("JojoHomeFreeboxTrace", "SAVE_HOME_BEFORE_EXIT_QUEUED at=$saveQueuedAt")
        // PlayerScreen has already persisted the repository checkpoint. Only
        // publish the Home resume here; onPause/onStop must not repeat it.
        // JSON/SharedPreferences work can be slow on this TV, so let Activity
        // finish first while the existing repository checkpoint stays intact.
        homeResumeSaveExecutor.execute {
            val saveStartedAt = android.os.SystemClock.elapsedRealtime()
            Log.i(
                "JojoHomeFreeboxTrace",
                "SAVE_HOME_BEFORE_EXIT_BEGIN at=$saveStartedAt queueDelay=${saveStartedAt - saveQueuedAt}ms"
            )
            runCatching { saveHomeResume(position, duration, streamUrl, isPlaying, metadata = metadata) }
                .onSuccess {
                    Log.i(
                        "JojoHomeFreeboxTrace",
                        "SAVE_HOME_BEFORE_EXIT_END at=${android.os.SystemClock.elapsedRealtime()} elapsed=${android.os.SystemClock.elapsedRealtime() - saveStartedAt}ms"
                    )
                }
                .onFailure {
                    Log.e("JojoHomeFreeboxTrace", "SAVE_HOME_BEFORE_EXIT_FAILED", it)
                }
        }
    }

    @Synchronized
    private fun removeHomeFileResume(viewModel: PlayerViewModel?) {
        val source = if (resumeContentType.equals("freebox", ignoreCase = true)) {
            freeboxPathFromContentId(resumeContentId)
        } else {
            viewModel?.getCurrentStreamUrl() ?: streamUrlForCleanup
        }
        if (source.isBlank()) return

        val prefs = getSharedPreferences("home_settings", MODE_PRIVATE)
        val updated = runCatching {
            val stored = org.json.JSONArray(prefs.getString("file_video_resumes", "[]"))
            val remaining = org.json.JSONArray()
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                if (!source.equals(item.optString("key", ""), ignoreCase = false)) remaining.put(item)
            }
            remaining.toString()
        }.getOrDefault("[]")
        val now = System.currentTimeMillis()
        val revisions = runCatching {
            org.json.JSONObject(prefs.getString("nas_setting_changes", "{}").orEmpty())
        }.getOrDefault(org.json.JSONObject())
        val writer = prefs.getString("nas_device_id", "").orEmpty()
        var latestRevision = now
        fun nextRevision(key: String): Long {
            val previous = revisions.optJSONObject(key)?.optLong("changedAt", 0L) ?: 0L
            val changedAt = maxOf(now, previous + 1L)
            latestRevision = maxOf(latestRevision, changedAt)
            return changedAt
        }
        revisions.put("video_resume_$source", org.json.JSONObject()
            .put("deleted", true)
            .put("changedAt", nextRevision("video_resume_$source"))
            .put("writer", writer))
        revisions.put("video_resume_invalidated_$source", org.json.JSONObject()
            .put("type", "long")
            .put("value", now)
            .put("changedAt", nextRevision("video_resume_invalidated_$source"))
            .put("writer", writer))
        revisions.put("file_video_resumes", org.json.JSONObject()
            .put("type", "string")
            .put("value", updated)
            .put("changedAt", nextRevision("file_video_resumes"))
            .put("writer", writer))
        prefs.edit()
            .remove("video_resume_$source")
            .putLong("video_resume_invalidated_$source", now)
            .remove("continue_watching_home_cache")
            .putString("file_video_resumes", updated)
            .putString("nas_setting_changes", revisions.toString())
            .putLong("nas_home_updated_at", latestRevision)
            .putBoolean("nas_home_pending", true)
            .putString("nas_home_snapshot", "")
            .apply()
    }

    private fun isHomeFileResumePlayback(): Boolean =
        resumeContentType.equals("freebox", ignoreCase = true) ||
            resumeContentType.equals("video", ignoreCase = true) ||
            resumeContentId.startsWith("freebox:", ignoreCase = true)

    @Synchronized
    private fun saveHomeResume(viewModel: PlayerViewModel, saveWatchProgress: Boolean = true) {
        // Playback-end dispatch queues this save immediately before invoking
        // the completion callback. The callback removes the resume; skip any
        // queued save that runs after that removal so it cannot recreate it.
        if (isHomeFileResumePlayback() && homeFileResumeCompleted) return
        // Save the shared progress record before closing. Player disposal is
        // performed after finish(), so Home can render the new position without
        // waiting for decoder/network teardown.
        if (saveWatchProgress) viewModel.saveWatchProgress()
        val position = viewModel.playbackTimeline.value.currentPosition
        val duration = viewModel.playbackTimeline.value.duration
        saveHomeResume(position, duration, viewModel.getCurrentStreamUrl(), viewModel.exoPlayer?.isPlaying == true,
            metadata = homeVideoMetadataSnapshot(viewModel))
    }

    private suspend fun saveHomeResumeWhilePlaying(viewModel: PlayerViewModel, saveWatchProgress: Boolean = true) {
        // ExoPlayer and its timeline stay on the player thread; only storage runs on IO.
        val snapshotAtMs = android.os.SystemClock.elapsedRealtime()
        val position = viewModel.playbackTimeline.value.currentPosition
        val duration = viewModel.playbackTimeline.value.duration
        val streamUrl = viewModel.getCurrentStreamUrl()
        val isPlaying = viewModel.uiState.value.isPlaying
        val metadata = homeVideoMetadataSnapshot(viewModel)
        if (saveWatchProgress) viewModel.saveWatchProgress()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (resumeProfileId == HomeWatchProgressBridge.currentProfileId(this@NuvioPlayerActivity)) {
                saveHomeResume(position, duration, streamUrl, isPlaying, snapshotAtMs, metadata)
            }
        }
    }

    private fun homeVideoMetadataSnapshot(viewModel: PlayerViewModel): Pair<String, String>? {
        if (!isHomeFileResumePlayback()) return null
        val state = viewModel.uiState.value
        if (state.logo.isNullOrBlank() && state.description.isNullOrBlank() &&
            state.genres.isEmpty() && state.director.isEmpty()
        ) return null
        val source = if (resumeContentType.equals("freebox", ignoreCase = true)) {
            freeboxPathFromContentId(resumeContentId)
        } else {
            viewModel.getCurrentStreamUrl()
        }.removePrefix("file://")
        if (source.isBlank()) return null
        val prefs = getSharedPreferences("home_settings", MODE_PRIVATE)
        val metadata = org.json.JSONObject()
            .put("sourcePath", source)
            .put("fileSignature", prefs.getString("video_resume_signature_$source", "").orEmpty())
            .put("playbackStartedAt", homeResumePlaybackStartedAt)
            .put("tmdbId", state.tmdbId ?: intent.getIntExtra(EXTRA_TMDB_ID, 0))
            .put("mediaType", intent.getStringExtra(EXTRA_TMDB_MEDIA_TYPE).orEmpty())
            .put("logo", state.logo.orEmpty())
            .put("titleLogoPolicyVersion", HomeMediaRules.TITLE_LOGO_POLICY_VERSION)
            .put("overview", state.description.orEmpty())
            .put("genres", org.json.JSONArray(state.genres))
            .put("director", org.json.JSONArray(state.director))
            .put("actors", org.json.JSONArray(state.castMembers.map { it.name }))
            .put("castDetails", org.json.JSONArray(state.castMembers.map { member ->
                org.json.JSONObject().put("name", member.name).put("character", member.character.orEmpty())
                    .put("photo", member.photo.orEmpty()).put("tmdbId", member.tmdbId ?: 0)
            }))
            .put("peoplePolicyVersion", HomeMediaRules.TMDB_PEOPLE_POLICY_VERSION)
            .put("year", state.releaseYear.orEmpty())
            .put("runtimeMinutes", state.runtimeMinutes ?: 0)
        return "video_player_metadata_${Integer.toUnsignedString(source.hashCode(), 16)}" to metadata.toString()
    }

    @Synchronized
    private fun saveHomeResume(position: Long, duration: Long, streamUrl: String, isPlaying: Boolean,
        snapshotAtMs: Long = android.os.SystemClock.elapsedRealtime(), metadata: Pair<String, String>? = null) {
        if (HomeWatchProgressBridge.isCloud(this)) return // The selected canonical cache owns cloud resumes.
        // An older periodic IO write must not undo a newer exit checkpoint.
        if (snapshotAtMs < latestHomeResumeSnapshotAtMs) return
        metadata?.let { (key, value) ->
            val prefs = getSharedPreferences("home_settings", MODE_PRIVATE)
            if (prefs.getString(key, "") != value) prefs.edit().putString(key, value).apply()
        }
        // IPTV progress uses stable provider IDs; account URLs must not enter the NAS file list.
        if (resumeContentId.startsWith("iptv:")) return
        // A queued checkpoint must not recreate a resume removed at completion.
        if (isHomeFileResumePlayback() && homeFileResumeCompleted) return
        if (resumeContentId.startsWith(ExternalPlaybackStore.CONTENT_PREFIX)) {
            // Live streams can genuinely play at position zero. Confirm actual
            // playback too instead of using a positive VOD timestamp as proof.
            if (!externalPlaybackEnded && (position > 0L || isPlaying)) {
                latestHomeResumeSnapshotAtMs = snapshotAtMs
                ExternalPlaybackStore.progress(this,
                    resumeContentId.removePrefix(ExternalPlaybackStore.CONTENT_PREFIX), position, duration)
            }
            return
        }
        // A video belongs to Continue Watching as soon as real playback has
        // advanced. The former five-second gate dropped short viewing sessions
        // even though the first frame had already been shown.
        if (position < 1000L) return
        val source = if (resumeContentType.equals("freebox", ignoreCase = true)) {
            freeboxPathFromContentId(resumeContentId)
        } else {
            streamUrl
        }
        if (source.isBlank()) return
        latestHomeResumeSnapshotAtMs = snapshotAtMs

        val prefs = getSharedPreferences("home_settings", MODE_PRIVATE)
        val stored = runCatching {
            org.json.JSONArray(prefs.getString("file_video_resumes", "[]"))
        }.getOrDefault(org.json.JSONArray())
        val updated = org.json.JSONArray()
        val displayTitle = resumeTitle.ifBlank { resumeFilename.ifBlank { "Vidéo" } }
        val current = org.json.JSONObject()
            .put("key", source)
            .put("contentId", resumeContentId)
            .put("contentType", resumeContentType)
            .put("profileId", resumeProfileId ?: HomeWatchProgressBridge.currentProfileId(this).also { resumeProfileId = it })
            .put("position", position)
            .put("duration", duration)
            .put("title", displayTitle)
            .put("source", source)
            .put("fileSignature", prefs.getString("video_resume_signature_$source", "").orEmpty())
            .put("updatedAt", System.currentTimeMillis())
        updated.put(current)
        for (index in 0 until stored.length()) {
            val item = stored.optJSONObject(index) ?: continue
            if ((!source.equals(item.optString("key", ""), ignoreCase = false) ||
                    item.optInt("profileId", 1) != current.optInt("profileId")) && updated.length() < 12) {
                updated.put(item)
            }
        }
        val hiddenKey = "jojotv_home_hidden_resume_keys"
        val hidden = prefs.getStringSet(hiddenKey, emptySet()).orEmpty().toMutableSet()
        // A deliberate new playback makes the file eligible again even if an
        // older resume card was removed manually. Clear both the stable path
        // key and the legacy title key stored by older Home dismissal records.
        val restorationPrefix = HomeWatchProgressBridge.RESTORATION_PREFIX + current.optInt("profileId", 1) + "_"
        val removalPrefix = HomeWatchProgressBridge.REMOVAL_PREFIX + current.optInt("profileId", 1) + "_"
        val restorationKeys = mutableListOf(source.removePrefix("file://"),
            "com.jojotv.home.file-video|$source", "com.jojotv.home.file-video|$displayTitle|")
        // A new playback can restore a removed video/folder. An older session's
        // checkpoints must retain removals received while that session was playing.
        hidden.filter { HomeMediaRules.homeVideoListKeyMatches(it, source) }.forEach { key ->
            if (homeResumePlaybackStartedAt > prefs.getLong(removalPrefix + key, 0L)) {
                hidden.remove(key)
                restorationKeys.add(key)
            }
        }
        val removedAt = restorationKeys.maxOf { prefs.getLong(removalPrefix + it, 0L) }
        if (homeResumePlaybackStartedAt > removedAt) {
            hidden.remove("com.jojotv.home.file-video|$source")
            hidden.remove("com.jojotv.home.file-video|$displayTitle|")
        }
        // Persist per-setting revisions here too: MainActivity's listener may
        // no longer exist while the player is the foreground Activity.
        val revisions = runCatching {
            org.json.JSONObject(prefs.getString("nas_setting_changes", "{}").orEmpty())
        }.getOrDefault(org.json.JSONObject())
        val now = System.currentTimeMillis()
        val writer = prefs.getString("nas_device_id", "").orEmpty()
        fun revision(key: String, type: String, value: Any) {
            val previous = revisions.optJSONObject(key)?.optLong("changedAt", 0L) ?: 0L
            revisions.put(key, org.json.JSONObject()
                .put("type", type).put("value", value)
                .put("changedAt", maxOf(now, previous + 1L)).put("writer", writer))
        }
        // An ongoing session's checkpoints must not undo a dismissal made
        // later on another TV. Only this session's initial launch is a replay.
        val restorationTimes = restorationKeys.associateWith {
            maxOf(homeResumePlaybackStartedAt, prefs.getLong(restorationPrefix + it, 0L))
        }
        for ((key, at) in restorationTimes) revision(restorationPrefix + key, "long", at)
        revision("video_resume_$source", "long", position)
        revision("file_video_resumes", "string", updated.toString())
        if (hidden != prefs.getStringSet(hiddenKey, emptySet())) {
            revision(hiddenKey, "set", org.json.JSONArray(hidden.toList()))
        }
        val editor = prefs.edit()
        for ((key, at) in restorationTimes) editor.putLong(restorationPrefix + key, at)
        editor
            .putLong("video_resume_$source", position)
            .putString(ExternalPlaybackStore.LAST_HOME_PLAYBACK_KEY,
                "com.jojotv.home.file-video|${ExternalPlaybackStore.id(source)}")
            .putLong(ExternalPlaybackStore.LAST_HOME_PLAYBACK_AT, System.currentTimeMillis())
            .putString("file_video_resumes", updated.toString())
            .putStringSet(hiddenKey, hidden)
            .putString("nas_setting_changes", revisions.toString())
            // Keep the previous card visible while MainActivity refreshes the
            // resume section in place on return from the player.
            // The player is a separate Activity. Mark this write as newer
            // than the last NAS snapshot so Jojo Home pushes it instead of
            // restoring the previous snapshot when it resumes.
            .putLong("nas_home_updated_at", System.currentTimeMillis())
            .putBoolean("nas_home_pending", true)
            .apply()
    }

    private fun mediaPresentationJson(): String {
        val value = runCatching { org.json.JSONObject(intent.getStringExtra(EXTRA_MEDIA_PRESENTATION).orEmpty()) }
            .getOrDefault(org.json.JSONObject())
        for ((key, extra) in listOf("description" to EXTRA_DESCRIPTION, "logo" to EXTRA_TITLE_LOGO,
            "poster" to EXTRA_POSTER, "backdrop" to EXTRA_BACKDROP)) {
            val text = HomeMediaRules.mediaMetadataText(intent.getStringExtra(extra))
            if (text.isNotBlank() && (key != "description" || HomeMediaRules.mediaMetadataText(value.optString(key)).isBlank()))
                value.put(key, text)
        }
        return value.toString()
    }

    private fun buildRoute(streamUrl: String, title: String, contentId: String, contentType: String, filename: String, description: String, titleLogo: String, tmdbId: Int?, tmdbMediaType: String, season: Int?, episode: Int?, episodeTitle: String, startFromBeginning: Boolean): String =
        "player?streamUrl=${Uri.encode(streamUrl)}" +
            "&title=${Uri.encode(title)}" +
            "&contentId=${Uri.encode(contentId)}" +
            "&contentType=${Uri.encode(contentType)}" +
            "&filename=${Uri.encode(filename)}" +
            "&streamDescription=${Uri.encode(description)}" +
            "&logo=${Uri.encode(titleLogo)}" +
            "&tmdbId=${tmdbId ?: 0}" +
            "&tmdbMediaType=${Uri.encode(tmdbMediaType)}" +
            "&season=${season ?: -1}" +
            "&episode=${episode ?: -1}" +
            "&episodeTitle=${Uri.encode(episodeTitle)}" +
            "&startFromBeginning=$startFromBeginning" +
            "&headers=${Uri.encode(intent.getStringExtra(EXTRA_STREAM_HEADERS).orEmpty())}" +
            "&iptvDns=${Uri.encode(intent.getStringExtra(EXTRA_IPTV_DNS).orEmpty())}" +
            "&poster=${Uri.encode(intent.getStringExtra(EXTRA_POSTER).orEmpty())}" +
            "&backdrop=${Uri.encode(intent.getStringExtra(EXTRA_BACKDROP).orEmpty())}" +
            "&presentation=${Uri.encode(mediaPresentationJson())}"

    companion object {
        private val homeResumeSaveExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "home-resume-save").apply { isDaemon = true }
        }
        private const val PLAYER_ROUTE = "player?streamUrl={streamUrl}&title={title}&contentId={contentId}&contentType={contentType}&filename={filename}&streamDescription={streamDescription}&logo={logo}&tmdbId={tmdbId}&tmdbMediaType={tmdbMediaType}&season={season}&episode={episode}&episodeTitle={episodeTitle}&startFromBeginning={startFromBeginning}&headers={headers}&iptvDns={iptvDns}&poster={poster}&backdrop={backdrop}&presentation={presentation}"
        const val EXTRA_MEDIA_PRESENTATION = "mediaPresentation"
        const val EXTRA_STREAM_URL = "streamUrl"
        const val EXTRA_NEXT_SMB_EPISODE = "nextSmbEpisode"
        const val EXTRA_STREAM_HEADERS = "streamHeaders"
        const val EXTRA_IPTV_DNS = "iptvDns"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_PLAYER_PACKAGE = "playerPackage"
        const val EXTRA_IPTV_ACCOUNT_ID = "iptvAccountId"
        const val EXTRA_IPTV_RESULT = "iptvReturnPlaybackResult"
        const val EXTRA_IPTV_COMPLETED = "iptvPlaybackCompleted"
        const val EXTRA_IPTV_NAVIGATION_ENABLED = "iptvNavigationEnabled"
        const val EXTRA_IPTV_NAVIGATION_ACTION = "iptvNavigationAction"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT_ID = "contentId"
        const val EXTRA_CONTENT_TYPE = "contentType"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_TITLE_LOGO = "titleLogo"
        const val EXTRA_TMDB_ID = "tmdbId"
        const val EXTRA_TMDB_MEDIA_TYPE = "tmdbMediaType"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_EPISODE_TITLE = "episodeTitle"
        const val EXTRA_PLAYER_ACCENT = "playerAccent"
        const val EXTRA_START_FROM_BEGINNING = "startFromBeginning"
        const val EXTRA_RETURN_TO_TIVIMATE = "returnToTiviMate"
        const val EXTRA_RETURN_FOLDER_URL = "returnFolderUrl"
        const val EXTRA_RETURN_SERIES_ID = "returnSeriesId"
        const val EXTRA_RETURN_HOME_CATEGORY = "returnHomeCategory"
        private const val TIVIMATE_PACKAGE = "ar.tvplayer.tv"
        private const val DEFAULT_PLAYER_ACCENT = 0xFF42A5F5.toInt()
    }
}
