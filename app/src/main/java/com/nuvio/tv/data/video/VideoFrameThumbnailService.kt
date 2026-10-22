package com.nuvio.tv.data.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoFrameThumbnailService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val extractionLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun thumbnailUri(videoUrl: String): String? =
        thumbnailUri(videoUrl = videoUrl, positionUs = 10_000_000L)

    suspend fun thumbnailUris(videoUrl: String, positionsUs: List<Long>): List<String> =
        positionsUs
            .distinct()
            .mapNotNull { positionUs -> thumbnailUri(videoUrl = videoUrl, positionUs = positionUs) }
            .distinct()

    private suspend fun thumbnailUri(videoUrl: String, positionUs: Long): String? = withContext(Dispatchers.IO) {
        val url = videoUrl.trim().takeIf { it.isNotBlank() } ?: return@withContext null
        val cacheKey = "$url@$positionUs".sha256()
        val cacheDirectory = File(context.cacheDir, "video-frame-thumbnails").apply { mkdirs() }
        val thumbnailFile = File(cacheDirectory, "$cacheKey.jpg")
        if (thumbnailFile.length() > 0L) return@withContext Uri.fromFile(thumbnailFile).toString()

        val lock = extractionLocks.getOrPut(cacheKey) { Mutex() }
        lock.withLock {
            if (thumbnailFile.length() > 0L) return@withLock Uri.fromFile(thumbnailFile).toString()

            var retriever: MediaMetadataRetriever? = null
            var frame: Bitmap? = null
            var scaledFrame: Bitmap? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSourceCompat(context, url)
                frame = retriever.getFrameAtTime(positionUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                    ?: return@withLock null
                scaledFrame = frame.scaleDown(maxWidth = 720)

                val temporaryFile = File(cacheDirectory, "$cacheKey.tmp")
                temporaryFile.outputStream().use { output ->
                    if (!scaledFrame.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                        return@withLock null
                    }
                }
                if (!temporaryFile.renameTo(thumbnailFile)) {
                    temporaryFile.copyTo(thumbnailFile, overwrite = true)
                    temporaryFile.delete()
                }
                Uri.fromFile(thumbnailFile).toString()
            } catch (error: Exception) {
                Log.w(TAG, "Impossible d'extraire une vignette video: $url", error)
                null
            } finally {
                if (scaledFrame !== frame) scaledFrame?.recycle()
                frame?.recycle()
                runCatching { retriever?.release() }
                extractionLocks.remove(cacheKey, lock)
            }
        }
    }

    private fun Bitmap.scaleDown(maxWidth: Int): Bitmap {
        if (width <= maxWidth) return this
        val targetHeight = (height * (maxWidth.toFloat() / width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, maxWidth, targetHeight, true)
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun MediaMetadataRetriever.setDataSourceCompat(context: Context, source: String) {
        val uri = runCatching { Uri.parse(source) }.getOrNull()
        when (uri?.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> setDataSource(context, uri)
            else -> setDataSource(source, emptyMap())
        }
    }

    private companion object {
        const val TAG = "VideoFrameThumbnail"
    }
}
