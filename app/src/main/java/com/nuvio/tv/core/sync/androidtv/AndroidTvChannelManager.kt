package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.jojotv.home.R
import com.nuvio.tv.core.recommendations.AndroidTvLaunchRequest
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"

@Singleton
class AndroidTvChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences,
) {
    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /**
     * Returns the channel id, creating or reusing the channel as needed.
     * Returns null on non-leanback devices or on failure.
     */
    suspend fun ensureChannel(profileId: Int, profileName: String): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        runCatching {
            val displayName = "$profileName · ${context.getString(R.string.tv_channel_continue_watching)}"
            val internalProviderId = "${context.packageName}:continue_watching:profile:$profileId"
            val stored = prefs.getChannelId(profileId)
            if (stored != null) {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored),
                    arrayOf(TvContractCompat.Channels._ID),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        updateChannelMetadata(stored, displayName, internalProviderId)
                        return@runCatching stored
                    }
                }
                Log.d(TAG, "Stored channel $stored gone; recreating")
                prefs.clearChannelId(profileId)
            }

            val orphan = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val providerIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx < 0 || providerIdx < 0) return@use null
                while (c.moveToNext()) {
                    val providerId = c.getString(providerIdx)
                    if (providerId == internalProviderId) {
                        return@use c.getLong(idIdx)
                    }
                }
                null
            }
            if (orphan != null) {
                Log.d(TAG, "Reusing orphaned channel $orphan")
                prefs.setChannelId(profileId, orphan)
                updateChannelMetadata(orphan, displayName, internalProviderId)
                writeChannelLogo(orphan)
                return@runCatching orphan
            }

            val appLinkUri = Uri.parse(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .toUri(Intent.URI_INTENT_SCHEME)
            )
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setInternalProviderId(internalProviderId)
                .setAppLinkIntentUri(appLinkUri)
                .build()

            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@runCatching null

            val id = ContentUris.parseId(inserted)
            prefs.setChannelId(profileId, id)
            writeChannelLogo(id)
            TvContractCompat.requestChannelBrowsable(context, id)
            Log.d(TAG, "Created channel id=$id")
            id
        }.onFailure { Log.w(TAG, "ensureChannel failed", it) }.getOrNull()
    }

    /**
     * Syncs [items] to the Continue Watching channel: upserts present items and
     * removes rows that are no longer in the list (completed or dismissed).
     */
    suspend fun reconcile(profileId: Int, profileName: String, items: List<WatchProgress>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = ensureChannel(profileId, profileName) ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredItems = items.distinctBy { progressKey(it) }
            val desiredKeys = desiredItems.map { progressKey(it) }.toSet()

            existing.duplicateRowIds.forEach { rowId ->
                deletePreviewProgram(rowId, "duplicate row=$rowId")
            }

            for ((key, rowId) in existing.byKey) {
                if (key !in desiredKeys) {
                    deletePreviewProgram(rowId, "key=$key")
                }
            }

            desiredItems.forEachIndexed { index, progress ->
                val key = progressKey(progress)
                val values = buildProgramValues(progress, channelId, index, key, profileId)
                val existingRow = existing.byKey[key]
                if (existingRow != null) {
                    // UPDATE the existing program row in place — keeping its row ID stable.
                    // This mirrors the canonical androidx PreviewChannelHelper.updatePreviewProgram
                    // approach (which Stremio uses and which launchers like Projectivy repaint
                    // on). The previous delete+re-insert of every program on every reconcile
                    // churned the provider and left launchers showing a stale cached view until
                    // the channel's visibility was toggled. buildProgramValues() explicitly nulls
                    // absent columns, so updates don't leave stale artwork/position behind.
                    updatePreviewProgram(existingRow, values, key)
                } else {
                    insertPreviewProgram(values, key)
                }
                Log.d(TAG, "${if (existingRow != null) "Updated" else "Inserted"} program key=$key pos=${values.getAsInteger("last_playback_position_millis")} dur=${values.getAsInteger("duration_millis")} pct=${progress.progressPercent}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /** Keeps existing callers compatible while the profile-aware channel migration rolls out. */
    @Deprecated("Use the profile-aware reconcile overload")
    suspend fun reconcile(items: List<WatchProgress>) {
        reconcile(
            profileId = 1,
            profileName = context.getString(R.string.tv_channel_continue_watching),
            items = items
        )
    }

    /** Removes all preview programs from our channel (used on sign-out / history clear). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            prefs.getAllChannelIds().forEach { channelId ->
                val rows = queryExistingPrograms(channelId)
                (rows.byKey.values + rows.duplicateRowIds).forEach { rowId ->
                    deletePreviewProgram(rowId, "clearAll")
                }
                Log.d(TAG, "Cleared ${rows.byKey.size + rows.duplicateRowIds.size} programs for channel $channelId")
            }
        }.onFailure { Log.w(TAG, "clearAll failed", it) }
    }

    // ---

    private data class ExistingPrograms(
        val byKey: Map<String, Long>,
        val duplicateRowIds: List<Long>
    )

    private fun queryExistingPrograms(channelId: Long): ExistingPrograms {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            "title",
            "season_number",
            "episode_number"
        )
        val byKey = linkedMapOf<String, Long>()
        val byEpisodeIdentity = mutableMapOf<String, Long>()
        val duplicateRowIds = mutableListOf<Long>()
        // Fire OS rejects any selection clause on preview_program URIs with SecurityException;
        // the provider auto-scopes to the calling package, so we filter channelId in memory.
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            val titleIdx = c.getColumnIndex("title")
            val seasonIdx = c.getColumnIndex("season_number")
            val episodeIdx = c.getColumnIndex("episode_number")
            while (c.moveToNext()) {
                if (c.getLong(channelIdx) != channelId) continue
                val key = c.getString(keyIdx) ?: continue
                val rowId = c.getLong(idIdx)
                val episodeIdentity = existingEpisodeIdentity(c, titleIdx, seasonIdx, episodeIdx)
                if (key in byKey) {
                    duplicateRowIds += rowId
                } else {
                    byKey[key] = rowId
                }
                if (episodeIdentity != null) {
                    val existingRow = byEpisodeIdentity.putIfAbsent(episodeIdentity, rowId)
                    if (existingRow != null && rowId !in duplicateRowIds) {
                        duplicateRowIds += rowId
                    }
                }
            }
        }
        return ExistingPrograms(byKey = byKey, duplicateRowIds = duplicateRowIds)
    }

    private fun existingEpisodeIdentity(
        cursor: android.database.Cursor,
        titleIdx: Int,
        seasonIdx: Int,
        episodeIdx: Int
    ): String? {
        if (titleIdx < 0 || seasonIdx < 0 || episodeIdx < 0) return null
        val title = cursor.getString(titleIdx)?.episodeIdentityTitle() ?: return null
        val season = cursor.getInt(seasonIdx).takeIf { it > 0 } ?: return null
        val episode = cursor.getInt(episodeIdx).takeIf { it > 0 } ?: return null
        return "$title:s$season:e$episode"
    }

    private fun deletePreviewProgram(rowId: Long, reason: String) {
        runCatching {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramUri(rowId), null, null
            )
        }.onSuccess {
            Log.d(TAG, "Removed program $reason")
        }.onFailure {
            Log.w(TAG, "Failed to remove program $reason", it)
        }
    }

    private fun updatePreviewProgram(rowId: Long, values: ContentValues, key: String) {
        runCatching {
            context.contentResolver.update(
                TvContractCompat.buildPreviewProgramUri(rowId), values, null, null
            )
        }.onFailure {
            Log.w(TAG, "Failed to update program key=$key", it)
        }
    }

    private fun insertPreviewProgram(values: ContentValues, key: String) {
        runCatching {
            context.contentResolver.insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI, values
            )
        }.onFailure {
            Log.w(TAG, "Failed to insert program key=$key", it)
        }
    }

    private fun buildProgramValues(
        progress: WatchProgress,
        channelId: Long,
        sortOrder: Int,
        key: String,
        profileId: Int
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(AndroidTvLaunchRequest.EXTRA_CONTENT_ID, progress.contentId)
                putExtra(AndroidTvLaunchRequest.EXTRA_CONTENT_TYPE, progress.contentType)
                putExtra(AndroidTvLaunchRequest.EXTRA_PROFILE_ID, profileId)
                putExtra(AndroidTvLaunchRequest.EXTRA_DIRECT_PLAY, true)
                putExtra(AndroidTvLaunchRequest.EXTRA_NAME, progress.name)
                putExtra(AndroidTvLaunchRequest.EXTRA_POSTER, progress.poster)
                putExtra(AndroidTvLaunchRequest.EXTRA_BACKDROP, progress.backdrop)
                putExtra(AndroidTvLaunchRequest.EXTRA_LOGO, progress.logo)
                putExtra(AndroidTvLaunchRequest.EXTRA_VIDEO_ID, progress.videoId)
                progress.season?.let { putExtra(AndroidTvLaunchRequest.EXTRA_SEASON, it) }
                progress.episode?.let { putExtra(AndroidTvLaunchRequest.EXTRA_EPISODE, it) }
                putExtra(AndroidTvLaunchRequest.EXTRA_EPISODE_TITLE, progress.episodeTitle)
                putExtra(AndroidTvLaunchRequest.EXTRA_POSITION, progress.position)
                putExtra(AndroidTvLaunchRequest.EXTRA_DURATION, progress.duration)
                putExtra(AndroidTvLaunchRequest.EXTRA_LAST_WATCHED, progress.lastWatched)
                putExtra(AndroidTvLaunchRequest.EXTRA_DESCRIPTION, progress.androidTvDescription())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (progress.contentType.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        progress.androidTvDescription()?.let { description ->
            builder.setDescription(description)
            builder.setLongDescription(description)
        }

        // Keep the normal launcher card as portrait, then expose the landscape artwork only
        // as the focused thumbnail. Launchers such as Android TV Home use these separately.
        if (!progress.poster.isNullOrBlank()) {
            builder.setPosterArtUri(Uri.parse(progress.poster))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        } else if (!progress.backdrop.isNullOrBlank()) {
            builder.setPosterArtUri(Uri.parse(progress.backdrop))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        }

        if (!progress.backdrop.isNullOrBlank()) {
            builder.setThumbnailUri(Uri.parse(progress.backdrop))
                .setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        } else if (!progress.poster.isNullOrBlank()) {
            builder.setThumbnailUri(Uri.parse(progress.poster))
                .setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        }

        progress.logo?.let { builder.setLogoUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                // Trakt items may have duration (from runtime hydration) but position=0 with only progressPercent.
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS drives launcher ordering;
            // the Builder method was added after tvprovider 1.0.0, so set directly.
            it.put("last_engagement_time_utc_millis", progress.lastWatched)
            // Explicitly clear poster art when no image is available, so UPDATE operations
            // don't leave stale artwork from previous reconcile cycles.
            if (progress.poster.isNullOrBlank() && progress.backdrop.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }
            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
            // Clear duration/position when unknown so stale values (e.g. previous 1hr fallback)
            // don't persist across UPDATE cycles.
            if (progress.duration <= 0) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?: return
            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "writeChannelLogo failed", it) }
    }

    private fun updateChannelMetadata(channelId: Long, displayName: String, internalProviderId: String) {
        runCatching {
            ContentValues().apply {
                put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, displayName)
                put(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, internalProviderId)
            }.also { values ->
                context.contentResolver.update(TvContractCompat.buildChannelUri(channelId), values, null, null)
            }
        }.onFailure { Log.w(TAG, "Failed to update channel $channelId metadata", it) }
    }

    /**
     * Mirrors WatchProgressPreferences.createKey() so channel and storage agree on identity.
     */
    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null) {
            progress.name.episodeIdentityTitle()?.let { title ->
                "${progress.contentType.lowercase(Locale.ROOT)}:$title:s${progress.season}:e${progress.episode}"
            } ?: "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
}

internal fun WatchProgress.androidTvDescription(): String? {
    val description = description?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return description
        .removeEpisodePrefix(season, episode)
        .trim(' ', '-', '–', '—', ':')
        .takeIf { it.isNotBlank() }
}

private fun String.removeEpisodePrefix(season: Int?, episode: Int?): String {
    val explicitPrefix = if (season != null && episode != null) {
        val escapedSeason = Regex.escape(season.toString())
        val escapedEpisode = Regex.escape(episode.toString())
        Regex(
            pattern = """^\s*(?:(?:saison|season)\s*${escapedSeason}\s*(?:[·.\-:|]\s*)?(?:épisode|episode|ep\.?|e)\s*${escapedEpisode}|S0?${escapedSeason}\s*E0?${escapedEpisode})\s*[-–—:|]?\s*""",
            option = RegexOption.IGNORE_CASE
        )
    } else {
        null
    }
    if (explicitPrefix != null) {
        val cleaned = replace(explicitPrefix, "")
        if (cleaned != this) return cleaned
    }
    return removeAnyEpisodePrefix()
}

private fun String.removeAnyEpisodePrefix(): String =
    replace(
        Regex(
        pattern = """^\s*(?:(?:saison|season)\s*\d+\s*(?:[·.\-:|]\s*)?(?:épisode|episode|ep\.?|e)\s*\d+|S\d+\s*E\d+)\s*[-–—:|]?\s*""",
            option = RegexOption.IGNORE_CASE
        ),
        ""
    ).replace(
        Regex(
            pattern = """^\s*S\d+\s*E\d+\s*[-–—:|]?\s*""",
            option = RegexOption.IGNORE_CASE
        ),
        ""
    )

private fun String.episodeIdentityTitle(): String? =
    lowercase(Locale.ROOT)
        .replace(Regex("""\(\s*(?:19|20)\d{2}\s*\)"""), "")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')
        .takeIf { it.isNotBlank() }
