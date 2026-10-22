package com.nuvio.tv.data.freebox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.NasIptvFavoritesApi
import com.nuvio.tv.data.local.FreeboxSettingsDataStore
import com.nuvio.tv.data.local.FreeboxConnectionSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

@Singleton
class FreeboxFrameThumbnailService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val freeboxOsClient: FreeboxOsClient,
    private val freeboxSettingsDataStore: FreeboxSettingsDataStore,
    private val nasIptvFavoritesApi: NasIptvFavoritesApi,
    private val profileManager: ProfileManager
) {
    private val extractionLocks = ConcurrentHashMap<String, Mutex>()

    /** Returns an already validated frame without opening Freebox, NAS or a decoder. */
    fun cachedThumbnailUri(entry: FreeboxFileEntry): String? {
        if (entry.isDirectory) return null
        val candidates = buildList {
            entry.durationMs?.takeIf { it > 0L }?.let { durationMs ->
                val positionMs = (durationMs * 0.28).roundToLong()
                    .coerceIn(1_000L, (durationMs - 1_000L).coerceAtLeast(1_000L))
                val nasKey = "${entry.path}|${entry.size ?: 0L}|${entry.modifiedMs ?: 0L}|$positionMs|nas-v1".sha256()
                add(File(context.cacheDir, "freebox-video-thumbnails-nas/$nasKey.jpg"))
            }
            val legacyKey = "$THUMBNAIL_CACHE_VERSION:${freeboxContentIdForEntry(entry)}@10000000".sha256()
            add(File(context.cacheDir, "freebox-video-thumbnails/$legacyKey.jpg"))
        }
        return candidates.firstOrNull { file -> file.isUsableCachedThumbnail() }
            ?.let { Uri.fromFile(it).toString() }
    }

    suspend fun thumbnailUri(entry: FreeboxFileEntry): String? {
        if (nasIptvFavoritesApi.hasSession()) {
            return nasThumbnailChoices(entry, 1).firstOrNull()
        }
        return legacyThumbnailUri(entry = entry, positionUs = 10_000_000L)
    }

    suspend fun thumbnailUris(entry: FreeboxFileEntry, positionsUs: List<Long>): List<String> =
        thumbnailChoices(entry, positionsUs.distinct().size.coerceAtLeast(1))

    /**
     * Extracts a poster strip with one authenticated Freebox connection and one
     * shared decoder. Candidates are reported as soon as they are ready; the
     * slower Android retriever is used only as a controlled failover when
     * FFmpeg cannot yield a usable frame.
     */
    suspend fun thumbnailChoices(
        entry: FreeboxFileEntry,
        minimumCount: Int = 5,
        onThumbnailReady: (String) -> Unit = {}
    ): List<String> =
        withContext(Dispatchers.IO) {
            if (entry.isDirectory || minimumCount <= 0) return@withContext emptyList()
            if (nasIptvFavoritesApi.hasSession()) {
                val nasFrames = nasThumbnailChoices(entry, minimumCount)
                if (nasFrames.isNotEmpty()) {
                    nasFrames.forEach(onThumbnailReady)
                    return@withContext nasFrames
                }
                // A reachable NAS is not proof that its frame endpoint could
                // decode this particular recording. Fall through to the
                // authenticated Freebox/FFmpeg extractor instead of showing
                // the misleading "no thumbnail" empty state.
            }
            val contentKey = freeboxContentIdForEntry(entry)
            val lockKey = "$contentKey@poster-strip"
            val lock = extractionLocks.getOrPut(lockKey) { Mutex() }
            lock.withLock {
                var retriever: MediaMetadataRetriever? = null
                var ffmpegSource: FfmpegFrameSource? = null
                try {
                    val settings = freeboxSettingsDataStore.settings.first()
                    if (!settings.hasSavedConnection) return@withLock emptyList()
                    val sessionToken = settings.sessionToken.ifBlank {
                        freeboxOsClient.openSession(settings).getOrThrow().sessionToken
                    }
                    val settingsWithToken = settings.copy(sessionToken = sessionToken)
                    ffmpegSource = createFfmpegFrameSource(settingsWithToken, sessionToken, entry)
                    fun getOrOpenRetriever(): MediaMetadataRetriever {
                        retriever?.let { return it }
                        return MediaMetadataRetriever().apply {
                            setDataSource(
                                freeboxOsClient.downloadUrl(settingsWithToken, entry.path, entry.encodedPath),
                                freeboxOsClient.sessionHeaders(sessionToken)
                            )
                            retriever = this
                        }
                    }
                    // Frame extraction does not require a duration. Some MKV
                    // files expose frames through FFmpeg but no duration; the
                    // previous code then opened the slow remote retriever only
                    // to read that optional metadata. If that opening failed,
                    // the whole picker returned an empty list without trying a
                    // single fallback position. Keep the FFmpeg path alive and
                    // use the fixed positions below when duration is unknown.
                    val durationMs = entry.durationMs?.takeIf { it > 0L }
                        ?: ffmpegSource?.durationMs?.takeIf { it > 0L }
                        ?: if (ffmpegSource == null) {
                            runCatching {
                                getOrOpenRetriever()
                                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull()
                            }.getOrNull()?.takeIf { it > 0L }
                        } else {
                            null
                        }
                    val durationUs = durationMs?.times(1_000L)
                    // A single seek can land on a broken/non-decodable GOP.
                    // Sample broadly enough to obtain five real choices even
                    // for long recordings with sparse or damaged keyframes.
                    // FFmpeg reads the same authenticated proxy descriptor, so
                    // this does not reopen the remote video for every choice.
                    val fractions = (1..20).map { index -> index / 21.0 }
                    val positionsUs = if (durationUs != null) {
                        fractions.map { fraction -> (durationUs * fraction).toLong() }
                    } else {
                        listOf(
                            30_000_000L, 90_000_000L, 180_000_000L, 300_000_000L,
                            450_000_000L, 600_000_000L, 750_000_000L, 900_000_000L,
                            1_050_000_000L, 1_200_000_000L, 1_350_000_000L, 1_500_000_000L,
                            1_650_000_000L, 1_800_000_000L, 1_950_000_000L, 2_100_000_000L
                        )
                    }
                    val cacheDirectory = File(context.cacheDir, "freebox-video-thumbnails").apply { mkdirs() }
                    val results = mutableListOf<String>()
                    val fingerprints = mutableSetOf<Long>()
                    var consecutiveUnusableFfmpegFrames = 0
                    var retrieverFailoverAttempted = false

                    positionsUs.forEach { requestedPositionUs ->
                        if (results.size >= minimumCount) return@forEach
                        val positionUs = durationUs
                            ?.let { requestedPositionUs.coerceIn(0L, (it - 500_000L).coerceAtLeast(0L)) }
                            ?: requestedPositionUs.coerceAtLeast(0L)
                        // v4 switches the primary extractor from Android's
                        // faulty MKV decoder to the bundled FFmpeg decoder.
                        val cacheKey = "$THUMBNAIL_CACHE_VERSION:$contentKey@$positionUs".sha256()
                        val thumbnailFile = File(cacheDirectory, "$cacheKey.jpg")
                        if (thumbnailFile.length() > 0L) {
                            val cached = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                            if (cached != null) {
                                try {
                                    if (cached.isUsableThumbnailFrame() && fingerprints.add(cached.frameFingerprint())) {
                                        val uri = Uri.fromFile(thumbnailFile).toString()
                                        results += uri
                                        onThumbnailReady(uri)
                                        return@forEach
                                    }
                                } finally {
                                    cached.recycle()
                                }
                            }
                            thumbnailFile.delete()
                        }

                        var scaledFrame: Bitmap? = null
                        try {
                            scaledFrame = ffmpegSource?.extractUsableFrameNear(positionUs)
                            if (scaledFrame == null && ffmpegSource != null) {
                                consecutiveUnusableFfmpegFrames++
                            } else if (scaledFrame != null) {
                                consecutiveUnusableFfmpegFrames = 0
                            }
                            // Android's remote retriever is substantially slower.
                            // Try it once after two unusable FFmpeg frames, rather
                            // than rejecting the entire video merely because the
                            // FFmpeg source itself opened successfully.
                            val shouldTryRetriever = scaledFrame == null &&
                                !retrieverFailoverAttempted &&
                                (ffmpegSource == null ||
                                    (results.isEmpty() && consecutiveUnusableFfmpegFrames >= 2))
                            if (shouldTryRetriever) {
                                retrieverFailoverAttempted = true
                                scaledFrame = getOrOpenRetriever().extractUsableFrame(positionUs)
                            }
                            if (scaledFrame == null) return@forEach
                            if (!fingerprints.add(scaledFrame.frameFingerprint())) return@forEach
                            val temporaryFile = File(cacheDirectory, "$cacheKey.tmp")
                            temporaryFile.outputStream().use { output ->
                                if (!scaledFrame.compress(Bitmap.CompressFormat.JPEG, 86, output)) return@forEach
                            }
                            if (!temporaryFile.renameTo(thumbnailFile)) {
                                temporaryFile.copyTo(thumbnailFile, overwrite = true)
                                temporaryFile.delete()
                            }
                            val uri = Uri.fromFile(thumbnailFile).toString()
                            results += uri
                            onThumbnailReady(uri)
                        } finally {
                            scaledFrame?.recycle()
                        }
                    }
                    results.distinct().take(minimumCount)
                } catch (error: Exception) {
                    Log.w(TAG, "Impossible d'extraire les choix de vignette Freebox: ${entry.path}", error)
                    emptyList()
                } finally {
                    ffmpegSource?.close()
                    runCatching { retriever?.release() }
                    extractionLocks.remove(lockKey, lock)
                }
            }
        }

    suspend fun durationMs(entry: FreeboxFileEntry): Long? = withContext(Dispatchers.IO) {
        if (entry.isDirectory) return@withContext null
        runCatching {
            val settings = freeboxSettingsDataStore.settings.first()
            if (!settings.hasSavedConnection) return@runCatching null
            val sessionToken = settings.sessionToken.ifBlank {
                freeboxOsClient.openSession(settings).getOrThrow().sessionToken
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(
                    freeboxOsClient.downloadUrl(
                        settings.copy(sessionToken = sessionToken),
                        entry.path,
                        entry.encodedPath
                    ),
                    freeboxOsClient.sessionHeaders(sessionToken)
                )
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private suspend fun nasThumbnailChoices(entry: FreeboxFileEntry, count: Int): List<String> =
        runCatching {
            nasIptvFavoritesApi.getFreeboxThumbnailFrames(
                profileId = profileManager.activeProfileId.value,
                path = entry.path,
                size = entry.size,
                durationMs = entry.durationMs,
                modifiedMs = entry.modifiedMs,
                count = count,
                destinationDirectory = File(context.cacheDir, "freebox-video-thumbnails-nas")
            ).map { Uri.fromFile(it).toString() }
        }.onFailure { error ->
            Log.w(TAG, "Le NAS n'a pas pu générer les vignettes Freebox: ${entry.path}", error)
        }.getOrDefault(emptyList())

    private suspend fun legacyThumbnailUri(entry: FreeboxFileEntry, positionUs: Long): String? = withContext(Dispatchers.IO) {
        val cacheKey = "$THUMBNAIL_CACHE_VERSION:${freeboxContentIdForEntry(entry)}@$positionUs".sha256()
        val cacheDirectory = File(context.cacheDir, "freebox-video-thumbnails").apply { mkdirs() }
        val thumbnailFile = File(cacheDirectory, "$cacheKey.jpg")
        if (thumbnailFile.length() > 0L && thumbnailFile.isUsableCachedThumbnail()) {
            return@withContext Uri.fromFile(thumbnailFile).toString()
        }
        thumbnailFile.delete()

        val lock = extractionLocks.getOrPut(cacheKey) { Mutex() }
        lock.withLock {
            if (thumbnailFile.length() > 0L && thumbnailFile.isUsableCachedThumbnail()) {
                return@withLock Uri.fromFile(thumbnailFile).toString()
            }
            thumbnailFile.delete()

            var retriever: MediaMetadataRetriever? = null
            var ffmpegSource: FfmpegFrameSource? = null
            var scaledFrame: Bitmap? = null
            try {
                val settings = freeboxSettingsDataStore.settings.first()
                if (!settings.hasSavedConnection) return@withLock null
                val sessionToken = settings.sessionToken.ifBlank {
                    freeboxOsClient.openSession(settings).getOrThrow().sessionToken
                }
                val settingsWithToken = settings.copy(sessionToken = sessionToken)
                ffmpegSource = createFfmpegFrameSource(settingsWithToken, sessionToken, entry)
                scaledFrame = ffmpegSource?.frameAt(positionUs)
                if (scaledFrame != null && !scaledFrame.isUsableThumbnailFrame()) {
                    scaledFrame.recycle()
                    scaledFrame = null
                }
                if (scaledFrame == null) {
                    // Opening MediaMetadataRetriever on a remote Freebox file can
                    // block for tens of seconds. It is only a compatibility
                    // fallback, so avoid opening the same video a second time
                    // when the bundled FFmpeg extractor already returned a frame.
                    retriever = MediaMetadataRetriever().apply {
                        setDataSource(
                            freeboxOsClient.downloadUrl(settingsWithToken, entry.path, entry.encodedPath),
                            freeboxOsClient.sessionHeaders(sessionToken)
                        )
                    }
                    scaledFrame = retriever.extractUsableFrame(positionUs)
                        ?: return@withLock null
                }

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
                Log.w(TAG, "Impossible d'extraire une vignette Freebox: ${entry.path}", error)
                null
            } finally {
                scaledFrame?.recycle()
                ffmpegSource?.close()
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

    private fun createFfmpegFrameSource(
        settings: FreeboxConnectionSettings,
        sessionToken: String,
        entry: FreeboxFileEntry
    ): FfmpegFrameSource? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val fileSize = entry.size?.takeIf { it > 0L } ?: return null
        var handlerThread: HandlerThread? = null
        var descriptor: ParcelFileDescriptor? = null
        var mediaInfo: MediaInfo? = null
        return try {
            val thread = HandlerThread("FreeboxFfmpegProxy").apply { start() }
            handlerThread = thread
            val callback = FreeboxRangeProxyCallback(
                size = fileSize,
                readRange = { offset, requestedSize, destination ->
                    freeboxOsClient.readDownloadRange(
                        settings = settings,
                        sessionToken = sessionToken,
                        path = entry.path,
                        encodedPath = entry.encodedPath,
                        offset = offset,
                        requestedSize = requestedSize,
                        destination = destination
                    )
                }
            )
            val storageManager = context.getSystemService(StorageManager::class.java)
                ?: error("Service de stockage Android indisponible.")
            val proxyDescriptor = storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                Handler(thread.looper)
            )
            descriptor = proxyDescriptor
            val builtMediaInfo = MediaInfoBuilder().from(proxyDescriptor).build()
            mediaInfo = builtMediaInfo
            if (builtMediaInfo?.supportsFrameLoading != true) {
                builtMediaInfo?.release()
                proxyDescriptor.close()
                thread.quitSafely()
                null
            } else {
                FfmpegFrameSource(builtMediaInfo, proxyDescriptor, thread)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Extracteur FFmpeg Freebox indisponible pour ${entry.path}", error)
            runCatching { mediaInfo?.release() }
            runCatching { descriptor?.close() }
            handlerThread?.quitSafely()
            null
        }
    }

    private class FfmpegFrameSource(
        private val mediaInfo: MediaInfo,
        private val descriptor: ParcelFileDescriptor,
        private val handlerThread: HandlerThread
    ) {
        val durationMs: Long? = mediaInfo.duration.takeIf { it > 0L }

        fun frameAt(positionUs: Long): Bitmap? = runCatching {
            mediaInfo.getFrameAt(positionUs.coerceAtLeast(0L) / 1_000L)
        }.getOrNull()

        fun close() {
            runCatching { mediaInfo.release() }
            runCatching { descriptor.close() }
            handlerThread.quitSafely()
        }
    }

    /**
     * A requested timestamp can resolve to an undecodable keyframe while a
     * neighbouring GOP is perfectly valid.  Keep the retry local to the same
     * file/decoder; do not fall back to artwork or a frame from another item.
     */
    private fun FfmpegFrameSource.extractUsableFrameNear(positionUs: Long): Bitmap? {
        val positions = listOf(
            positionUs,
            positionUs + 2_000_000L,
            (positionUs - 2_000_000L).coerceAtLeast(0L)
        ).distinct()
        positions.forEach { candidatePositionUs ->
            val decoded = frameAt(candidatePositionUs) ?: return@forEach
            val scaled = decoded.scaleDown(maxWidth = 720)
            if (scaled !== decoded) decoded.recycle()
            if (scaled.isUsableThumbnailFrame()) return scaled
            scaled.recycle()
        }
        return null
    }

    private class FreeboxRangeProxyCallback(
        private val size: Long,
        private val readRange: (offset: Long, requestedSize: Int, destination: ByteArray) -> Int
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, requestedSize: Int, data: ByteArray): Int {
            if (offset >= size) return 0
            val boundedSize = minOf(requestedSize.toLong(), size - offset).toInt()
            return readRange(offset, boundedSize, data)
        }

        override fun onRelease() = Unit
    }

    /**
     * Tries independent sync-frame directions before the non-sync fallback.
     * Every candidate is validated before it can reach the disk cache.
     */
    private fun MediaMetadataRetriever.extractUsableFrame(positionUs: Long): Bitmap? {
        val safePositionUs = positionUs.coerceAtLeast(0L)
        val attempts = listOf(
            safePositionUs to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            (safePositionUs + 2_000_000L) to MediaMetadataRetriever.OPTION_NEXT_SYNC,
            (safePositionUs - 2_000_000L).coerceAtLeast(0L) to MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
            safePositionUs to MediaMetadataRetriever.OPTION_CLOSEST,
        ).distinct()
        attempts.forEach { (attemptPositionUs, option) ->
            val frame = runCatching { getFrameAtTime(attemptPositionUs, option) }.getOrNull()
                ?: return@forEach
            val scaled = frame.scaleDown(maxWidth = 720)
            if (scaled !== frame) frame.recycle()
            if (scaled.isUsableThumbnailFrame()) return scaled
            scaled.recycle()
        }
        return null
    }

    private fun Bitmap.frameFingerprint(): Long {
        var fingerprint = 17L
        val sampleColumns = 8
        val sampleRows = 5
        repeat(sampleRows) { row ->
            repeat(sampleColumns) { column ->
                val x = ((column + 0.5f) * width / sampleColumns).toInt().coerceIn(0, width - 1)
                val y = ((row + 0.5f) * height / sampleRows).toInt().coerceIn(0, height - 1)
                fingerprint = fingerprint * 31L + getPixel(x, y)
            }
        }
        return fingerprint
    }

    /** Rejects decoder corruption while preserving genuine green scenes.
     *
     * Broken frames returned by Android's retriever are overwhelmingly green
     * across almost every sample. A real scene may contain vegetation or a
     * green wall, but normally retains enough neutral/dark/skin pixels not to
     * cross both thresholds.
     */
    private fun Bitmap.isBrokenGreenFrame(): Boolean {
        val sampleColumns = 16
        val sampleRows = 9
        var stronglyGreen = 0
        var vividGreen = 0
        var chromatic = 0
        var greenDominatedRows = 0
        val total = sampleColumns * sampleRows
        repeat(sampleRows) { row ->
            var rowGreen = 0
            repeat(sampleColumns) { column ->
                val x = ((column + 0.5f) * width / sampleColumns).toInt().coerceIn(0, width - 1)
                val y = ((row + 0.5f) * height / sampleRows).toInt().coerceIn(0, height - 1)
                val pixel = getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val channelSpread = maxOf(red, green, blue) - minOf(red, green, blue)
                if (channelSpread >= 18 && maxOf(red, green, blue) >= 45) chromatic++
                if (green >= red + 24 && green >= blue + 24 && green >= 45) {
                    stronglyGreen++
                    rowGreen++
                }
                if (green >= 80 && green >= red * 3 / 2 && green >= blue * 3 / 2) vividGreen++
            }
            if (rowGreen >= sampleColumns / 3) greenDominatedRows++
        }
        val nearlySolidGreen = stronglyGreen >= (total * 85 / 100) && vividGreen >= (total * 70 / 100)
        val greenBandCorruption = stronglyGreen >= (total * 25 / 100) &&
            chromatic > 0 && stronglyGreen >= (chromatic * 70 / 100) &&
            greenDominatedRows >= sampleRows / 2
        return nearlySolidGreen || greenBandCorruption
    }

    /** Rejects the multicoloured block mosaics produced by a failed remote seek. */
    private fun Bitmap.isDecoderMosaic(): Boolean {
        val sampleColumns = 24
        val sampleRows = 14
        var saturatedSamples = 0
        var abruptTransitions = 0
        var transitions = 0
        var previousRow = IntArray(sampleColumns)
        repeat(sampleRows) { row ->
            var previousPixel: Int? = null
            repeat(sampleColumns) { column ->
                val x = ((column + 0.5f) * width / sampleColumns).toInt().coerceIn(0, width - 1)
                val y = ((row + 0.5f) * height / sampleRows).toInt().coerceIn(0, height - 1)
                val pixel = getPixel(x, y)
                val spread = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                    minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (spread >= 90) saturatedSamples++
                previousPixel?.let { left ->
                    transitions++
                    if (pixelColourDistance(pixel, left) >= 270) abruptTransitions++
                }
                if (row > 0) {
                    transitions++
                    if (pixelColourDistance(pixel, previousRow[column]) >= 270) abruptTransitions++
                }
                previousPixel = pixel
                previousRow[column] = pixel
            }
        }
        val samples = sampleColumns * sampleRows
        // Require near-total colourful discontinuity. Detailed cityscapes and
        // action scenes retain far more continuity than the decoder's block
        // mosaic shown in the failing Freebox recordings.
        return saturatedSamples >= samples * 75 / 100 &&
            abruptTransitions >= transitions * 70 / 100
    }

    private fun pixelColourDistance(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))

    private fun Bitmap.isUsableThumbnailFrame(): Boolean =
        !isBrokenGreenFrame() && !isDecoderMosaic()

    private fun File.isUsableCachedThumbnail(): Boolean {
        val bitmap = BitmapFactory.decodeFile(absolutePath) ?: return false
        return try {
            bitmap.isUsableThumbnailFrame()
        } finally {
            bitmap.recycle()
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TAG = "FreeboxFrameThumbnail"
        const val THUMBNAIL_CACHE_VERSION = "v7"
    }
}
