package com.nuvio.tv.core.sync.androidtv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.tvprovider.media.tv.TvContractCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.MainActivity
import com.nuvio.tv.core.recommendations.AndroidTvLaunchRequest
import com.nuvio.tv.data.local.CachedInProgressItem
import java.io.File
import java.io.FileNotFoundException

/** Read-only bridge for the companion Jojo Home launcher.
 *
 * The permission is intentionally a normal permission: Jojo Home is installed as
 * a separate APK and therefore cannot satisfy a signature permission when users
 * install the two APKs with different signing certificates.
 */
class LauncherContinueWatchingProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(COLUMNS)
        val appContext = context ?: return result

        // This is the authoritative source used by JojoTV itself.  Reading the
        // app-private snapshots avoids relying on the system TV provider's
        // refresh timing or on firmware-specific visibility of preview channels.
        val hasLocalSnapshots = appendInProgressSnapshots(appContext, result)

        // Fallback for an older install that has published Android TV rows but
        // has not yet written its local continue-watching snapshot.
        val resolver = appContext.contentResolver
        val providerPrefix = "${appContext.packageName}:continue_watching:"
        val channelIds = mutableListOf<Long>()
        resolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { channels ->
            val idIndex = channels.getColumnIndex(TvContractCompat.Channels._ID)
            val providerIndex = channels.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            while (channels.moveToNext()) {
                if (channels.getString(providerIndex)?.startsWith(providerPrefix) == true) {
                    channelIds += channels.getLong(idIndex)
                }
            }
        }
        // Plusieurs profils JojoTV publient plusieurs chaînes. Il faut les agréger :
        // choisir la première pouvait sélectionner une chaîne inactive et masquer les
        // reprises pourtant visibles dans Android TV.
        fun appendPrograms(
            source: Uri,
            selection: String?,
            selectionArgs: Array<String>?
        ) {
            resolver.query(
                source,
                PROGRAM_COLUMNS,
                selection,
                selectionArgs,
                null
            )?.use { programs ->
                while (programs.moveToNext()) result.addRow(arrayOf<Any?>(
                    programs.getString(0), programs.getString(1), programs.getString(2),
                    programs.getString(3), programs.getString(4), programs.getLong(5), programs.getLong(6),
                    null, null
                ))
            }
        }
        // Preview rows are a compatibility fallback only.  Appending them after
        // the on-disk snapshots resurrects entries that were deliberately
        // removed from Continue Watching but are still retained by Android TV.
        if (!hasLocalSnapshots && result.count == 0) {
            if (channelIds.isEmpty()) {
                // Quelques firmwares réécrivent ou masquent l'identifiant interne ; les
                // programmes restent néanmoins limités à l'UID de JojoTV.
                appendPrograms(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null)
            } else {
                channelIds.forEach { channelId ->
                    appendPrograms(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        "${TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID}=?",
                        arrayOf(channelId.toString())
                    )
                }
            }
        }
        // Watch Next is maintained by Android itself. The official API does not
        // support a selection here; querying the whole table returns this app's
        // programs. Use it only when neither the snapshots nor the profile
        // preview channels contain an entry.
        if (!hasLocalSnapshots && result.count == 0) {
            appendPrograms(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null)
        }
        return result
    }

    private fun appendInProgressSnapshots(
        appContext: android.content.Context,
        result: MatrixCursor
    ): Boolean {
        val directory = File(appContext.filesDir, "cw_enrichment")
        val snapshots = directory.listFiles { file ->
            file.isFile && file.name.startsWith("inprogress_") && file.name.endsWith(".json")
        }.orEmpty()
        val gson = Gson()
        val itemType = object : TypeToken<List<CachedInProgressItem>>() {}.type
        val seen = mutableSetOf<String>()
        snapshots.flatMap { file ->
            val profileId = file.name
                .removePrefix("inprogress_")
                .removeSuffix(".json")
                .toIntOrNull()
                ?: return@flatMap emptyList<Pair<Int, CachedInProgressItem>>()
            runCatching { gson.fromJson<List<CachedInProgressItem>>(file.readText(), itemType).orEmpty() }
                .getOrDefault(emptyList())
                .map { item -> profileId to item }
        }.groupBy { (_, item) -> item.contentId }
            .values
            .mapNotNull { candidates -> candidates.maxByOrNull { (_, item) -> item.lastWatched } }
            .sortedByDescending { (_, item) -> item.lastWatched }
            .forEach { (profileId, item) ->
                val key = "$profileId:${item.contentId}"
                if (!seen.add(key)) return@forEach
                val exportedPoster = exportArtworkUri(appContext, item.poster)
                val exportedBackdrop = exportArtworkUri(
                    appContext,
                    item.backdrop ?: item.episodeThumbnail ?: item.poster
                )
                result.addRow(
                    arrayOf<Any?>(
                        item.name,
                        item.episodeDescription ?: item.episodeTitle,
                        exportedPoster,
                        // Match JojoTV's own card precedence exactly. A stale
                        // extracted episode frame must never replace the stable
                        // backdrop/manual programme image in Jojo Home.
                        exportedBackdrop,
                        launchIntentUri(appContext, profileId, item),
                        item.duration,
                        item.position,
                        item.season,
                        item.episode
                    )
                )
            }
        // The presence of a snapshot file means JojoTV has initialized this
        // bridge. Its empty list is authoritative after a deletion and must not
        // fall back to stale Preview/Watch Next rows.
        return snapshots.isNotEmpty()
    }

    /**
     * JojoTV resolves durable artwork to an app-private file for instant local
     * rendering. Jojo Home is a separate APK and cannot read a raw file:// URI,
     * so expose only whitelisted artwork/cache files through this read-only
     * provider and give both apps the exact same bitmap.
     */
    private fun exportArtworkUri(appContext: android.content.Context, source: String?): String? {
        val value = source?.takeIf(String::isNotBlank) ?: return null
        val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return value
        if (!parsed.scheme.equals("file", ignoreCase = true)) return value
        val path = parsed.path?.takeIf(String::isNotBlank) ?: return value
        val file = File(path)
        if (!file.isReadableBridgeArtwork(appContext)) return value
        return Uri.Builder()
            .scheme("content")
            .authority("${appContext.packageName}.homebridge")
            .appendPath("artwork")
            .appendQueryParameter("path", file.absolutePath)
            .build()
            .toString()
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.pathSegments.firstOrNull() != "artwork") {
            throw FileNotFoundException("Read-only artwork URI expected")
        }
        val appContext = context ?: throw FileNotFoundException("Provider unavailable")
        val path = uri.getQueryParameter("path") ?: throw FileNotFoundException("Missing artwork path")
        val file = File(path)
        if (!file.isReadableBridgeArtwork(appContext)) {
            throw FileNotFoundException("Artwork is outside the JojoTV cache")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun File.isReadableBridgeArtwork(appContext: android.content.Context): Boolean {
        val canonical = runCatching { canonicalFile }.getOrNull() ?: return false
        val allowedRoots = listOf(appContext.filesDir, appContext.cacheDir).mapNotNull {
            runCatching { it.canonicalFile }.getOrNull()
        }
        return canonical.isFile && canonical.length() > 0L && allowedRoots.any { root ->
            canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
        }
    }

    private fun launchIntentUri(
        appContext: android.content.Context,
        profileId: Int,
        item: CachedInProgressItem
    ): String = Uri.parse(
        Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(AndroidTvLaunchRequest.EXTRA_CONTENT_ID, item.contentId)
            putExtra(AndroidTvLaunchRequest.EXTRA_CONTENT_TYPE, item.contentType)
            putExtra(AndroidTvLaunchRequest.EXTRA_PROFILE_ID, profileId)
            putExtra(AndroidTvLaunchRequest.EXTRA_DIRECT_PLAY, true)
            putExtra(AndroidTvLaunchRequest.EXTRA_NAME, item.name)
            putExtra(AndroidTvLaunchRequest.EXTRA_POSTER, item.poster)
            putExtra(
                AndroidTvLaunchRequest.EXTRA_BACKDROP,
                item.backdrop ?: item.episodeThumbnail ?: item.poster
            )
            putExtra(AndroidTvLaunchRequest.EXTRA_LOGO, item.logo)
            putExtra(AndroidTvLaunchRequest.EXTRA_VIDEO_ID, item.videoId)
            item.season?.let { putExtra(AndroidTvLaunchRequest.EXTRA_SEASON, it) }
            item.episode?.let { putExtra(AndroidTvLaunchRequest.EXTRA_EPISODE, it) }
            putExtra(AndroidTvLaunchRequest.EXTRA_EPISODE_TITLE, item.episodeTitle)
            putExtra(AndroidTvLaunchRequest.EXTRA_POSITION, item.position)
            putExtra(AndroidTvLaunchRequest.EXTRA_DURATION, item.duration)
            putExtra(AndroidTvLaunchRequest.EXTRA_LAST_WATCHED, item.lastWatched)
            putExtra(AndroidTvLaunchRequest.EXTRA_DESCRIPTION, item.episodeDescription)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }.toUri(Intent.URI_INTENT_SCHEME)
    ).toString()

    override fun getType(uri: Uri) = if (uri.pathSegments.firstOrNull() == "artwork") {
        "image/*"
    } else {
        "vnd.android.cursor.dir/vnd.jojotv.continue-watching"
    }
    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read-only")

    companion object {
        private val COLUMNS = arrayOf("title", "description", "poster", "thumbnail", "intent_uri", "duration", "position", "season_number", "episode_number")
        private val PROGRAM_COLUMNS = arrayOf(
            TvContractCompat.PreviewPrograms.COLUMN_TITLE,
            TvContractCompat.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI,
            "duration_millis",
            "last_playback_position_millis"
        )
    }
}
