package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.jojotv.home.R

object ExternalPlayerLauncher {

    private const val EXTERNAL_PLAYER_PACKAGE = "external_player_package"

    /** Restrict ACTION_VIEW to the player selected in Jojo Home when it is still installed. */
    fun applyPreferredPlayer(context: Context, intent: Intent) {
        val preferredPackage = context
            .getSharedPreferences("home_settings", Context.MODE_PRIVATE)
            .getString(EXTERNAL_PLAYER_PACKAGE, "")
            .orEmpty()
        if (preferredPackage.isEmpty()) return
        val handlesIntent = context.packageManager
            .queryIntentActivities(intent, 0)
            .any { it.activityInfo?.packageName == preferredPackage }
        if (handlesIntent) intent.setPackage(preferredPackage)
    }

    /**
     * Fire-and-forget launch of an external player.
     * Used as a fallback when ActivityResultLauncher is not available (e.g. non-Activity context).
     */
    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        subtitles: List<SubtitleInput>? = null
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(Uri.parse(url), "video/*")

                title?.let {
                    putExtra("title", it)
                    putExtra(Intent.EXTRA_TITLE, it)
                    putExtra("forcename", it) // Vimu Player
                }

                headers?.let { hdrs ->
                    if (hdrs.isNotEmpty()) {
                        val headerArray = hdrs.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                        putExtra("headers", headerArray)
                    }
                }

                if (resumePositionMs > 0L) {
                    putExtra("position", resumePositionMs.toInt())
                    putExtra("startfrom", resumePositionMs.toInt())
                    putExtra("from_start", false)
                }

                // Subtitle extras for external players
                if (!subtitles.isNullOrEmpty()) {
                    val subtitleUris = subtitles.map { Uri.parse(it.url) }.toTypedArray()
                    val subtitleNames = subtitles.map { it.name }.toTypedArray()
                    val subtitleFilenames = subtitles.map { "${it.lang}_${it.name}.srt" }.toTypedArray()

                    // MX Player / mpv-android / Nova
                    putExtra("subs", subtitleUris)
                    putExtra("subs.name", subtitleNames)
                    putExtra("subs.filename", subtitleFilenames)
                    putExtra("subs.enable", arrayOf(Uri.parse(subtitles.first().url)))

                    // Just Player
                    putExtra("subtitle_uri", subtitleUris)
                    putExtra("subtitle_name", subtitleNames)

                    // VLC (single subtitle — use first one)
                    putExtra("subtitles_location", Uri.parse(subtitles.first().url))

                    // Vimu Player
                    putExtra("forcedsrt", subtitles.first().url)
                }

                applyPreferredPlayer(context, this)

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
                true
            } catch (preferredLaunchError: Exception) {
                // The preferred package may have advertised a generic video
                // handler but not accept this concrete stream URL. Retry with
                // the normal system resolver before reporting that no player
                // exists.
                if (intent.`package` != null) {
                    intent.setPackage(null)
                    context.startActivity(intent)
                    true
                } else {
                    throw preferredLaunchError
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ExternalPlayerLauncher", "No player resolved for url=$url package=${homePreferredPackage(context)}", e)
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun homePreferredPackage(context: Context): String = context
        .getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        .getString(EXTERNAL_PLAYER_PACKAGE, "")
        .orEmpty()

    /**
     * Create an [ExternalPlayerInput] for use with [ExternalPlayerResultContract].
     * Prefer this over [launch] when you have an Activity context and want to receive
     * playback progress back from the external player.
     */
    fun createInput(
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        subtitles: List<SubtitleInput>? = null
    ): ExternalPlayerInput = ExternalPlayerInput(
        url = url,
        title = title,
        headers = headers,
        resumePositionMs = resumePositionMs,
        subtitles = subtitles
    )
}
