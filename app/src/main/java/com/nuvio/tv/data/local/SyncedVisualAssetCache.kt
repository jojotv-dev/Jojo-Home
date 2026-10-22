package com.nuvio.tv.data.local

import android.content.Context
import android.net.Uri
import com.nuvio.tv.core.sync.NasIptvFavoritesApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, explicit cache for artwork deliberately selected by the user.
 * It intentionally excludes Coil's general 200 MB cache: only these files are
 * copied to the NAS and included in a JojoTV backup.
 */
@Singleton
class SyncedVisualAssetCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nasApi: NasIptvFavoritesApi
) {
    companion object {
        private const val PROFILE_ID = FREEBOX_SHARED_MEDIA_PROFILE_ID
        private const val MAX_CACHE_BYTES = 50L * 1024L * 1024L
        private const val MAX_SINGLE_IMAGE_BYTES = 8L * 1024L * 1024L
    }

    private val directory = File(context.filesDir, "synced-visual-assets")
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun resolve(url: String): String {
        if (url.isBlank()) return url
        val file = fileFor(url)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file.toURI().toString()
        }
        ensureAvailable(url)
        return url
    }

    fun ensureAvailable(url: String) {
        if (url.isBlank() || fileFor(url).isFile || !inFlight.add(url)) return
        scope.launch {
            runCatching {
                val target = fileFor(url)
                val restored = nasApi.getProfileAsset(PROFILE_ID, assetKey(url), target)
                if (!restored) downloadInto(url, target)
                if (target.isFile) nasApi.putProfileAsset(PROFILE_ID, assetKey(url), target, "image/*")
                trimToLimit()
                _revision.value++
            }
            inFlight.remove(url)
        }
    }

    suspend fun cacheAndUpload(url: String) = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext
        val target = fileFor(url)
        if (!target.isFile) downloadInto(url, target)
        if (target.isFile) {
            nasApi.putProfileAsset(PROFILE_ID, assetKey(url), target, "image/*")
            trimToLimit()
            _revision.value++
        }
    }

    suspend fun syncSelectedArtwork(urls: Collection<String>) = withContext(Dispatchers.IO) {
        urls.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().forEach { url ->
            runCatching { cacheAndUpload(url) }
        }
    }

    private fun assetKey(url: String) = "visual_${sha256(url).take(48)}"
    private fun fileFor(url: String) = File(directory, assetKey(url))

    private suspend fun downloadInto(url: String, destination: File) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.delete()
        val input = if (url.startsWith("http://") || url.startsWith("https://")) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Téléchargement image HTTP ${response.code}")
                response.body?.byteStream()?.readBounded(MAX_SINGLE_IMAGE_BYTES)
                    ?: throw IllegalStateException("Image vide")
            }
        } else {
            context.contentResolver.openInputStream(Uri.parse(url))?.readBounded(MAX_SINGLE_IMAGE_BYTES)
                ?: throw IllegalStateException("Image locale inaccessible")
        }
        temporary.writeBytes(input)
        if (!temporary.renameTo(destination)) {
            destination.delete()
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun java.io.InputStream.readBounded(limit: Long): ByteArray {
        use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IllegalStateException("Image trop volumineuse")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun trimToLimit() {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf(File::length)
        files.forEach { file ->
            if (total <= MAX_CACHE_BYTES) return@forEach
            total -= file.length()
            file.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

