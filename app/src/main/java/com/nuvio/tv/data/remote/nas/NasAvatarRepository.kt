package com.nuvio.tv.data.remote.nas

import android.content.Context
import android.graphics.BitmapFactory
import com.nuvio.tv.core.sync.NasIptvFavoritesApi
import com.nuvio.tv.ui.components.PROFILE_ANIMAL_AVATARS
import com.nuvio.tv.ui.components.PROFILE_EMOJI_AVATAR_CATALOG
import com.nuvio.tv.ui.components.PROFILE_FAMILY_AVATARS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class NasAvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String = "",
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null,
    val assetKey: String? = null
)

@Singleton
class NasAvatarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nasApi: NasIptvFavoritesApi
) {
    private var cachedCatalog: List<NasAvatarCatalogItem>? = null
    private val catalogLoadMutex = Mutex()
    private val assetLoadMutex = Mutex()
    private val cacheDirectory = File(context.filesDir, "nas-avatar-catalog")

    suspend fun getAvatarCatalog(): List<NasAvatarCatalogItem> {
        cachedCatalog?.let { return it }
        // Profile selection is rendered on the main dispatcher.  Loading this
        // catalog can validate and download dozens of bitmap assets, so never
        // let it decode files or perform NAS I/O on the UI thread.  The mutex
        // also prevents MainActivity and the profile ViewModel from loading
        // the full catalog simultaneously on a cold start.
        return catalogLoadMutex.withLock {
            cachedCatalog?.let { return@withLock it }
            // Do not permanently cache the offline fallback.  The profile
            // screen can be created before NAS authentication completes; a
            // later visit must be allowed to fetch the real catalog/images.
            if (!nasApi.hasSession()) {
                return@withLock loadBundledCachedCatalog()
            }
            withContext(Dispatchers.IO) {
                runCatching { loadNasCatalog() }.getOrElse { loadBundledCachedCatalog() }
            }.also { cachedCatalog = it }
        }
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<NasAvatarCatalogItem>): String? =
        catalog.find { it.id == avatarId }?.imageUrl?.takeIf(String::isNotBlank)

    /** Resolves only an already downloaded asset; never starts NAS traffic. */
    fun getCachedAvatarImageUrl(avatarId: String?): String? {
        val item = avatarId?.let { id -> mergeBundledCatalog(emptyList()).firstOrNull { it.id == id } }
            ?: return null
        val assetKey = item.assetKey ?: return null
        val file = File(cacheDirectory, assetKey)
        return file.takeIf(::isValidImage)?.toURI()?.toString()
    }

    fun invalidateCache() { cachedCatalog = null }

    /**
     * Downloads NAS artwork after the catalog metadata has already been
     * published to Compose. A missing or slow asset must never hide the
     * Animals/Family choices themselves.
     */
    suspend fun hydrateAvatarImages(
        catalog: List<NasAvatarCatalogItem>,
        onItemUpdated: (NasAvatarCatalogItem) -> Unit
    ) = assetLoadMutex.withLock {
        if (!nasApi.hasSession()) return@withLock
        val downloadSlots = Semaphore(4)
        coroutineScope {
            catalog.mapNotNull { item ->
                val assetKey = item.assetKey ?: return@mapNotNull null
                async(Dispatchers.IO) {
                    val destination = File(cacheDirectory, assetKey)
                    downloadSlots.withPermit {
                        if (!isValidImage(destination)) {
                            destination.delete()
                            nasApi.getProfileAsset(PROFILE_ID, assetKey, destination)
                        }
                    }
                    val imageUrl = destination.takeIf(::isValidImage)
                        ?.toURI()
                        ?.toString()
                        .orEmpty()
                    if (imageUrl.isNotBlank() && imageUrl != item.imageUrl) {
                        val updated = item.copy(imageUrl = imageUrl)
                        cachedCatalog = cachedCatalog?.map { current ->
                            if (current.id == updated.id) updated else current
                        }
                        onItemUpdated(updated)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun loadNasCatalog(): List<NasAvatarCatalogItem> {
        if (!nasApi.hasSession()) return defaultCatalog()
        val raw = nasApi.pullProfileBlobJson(PROFILE_ID, CATALOG_BLOB_KEY)
        val existing = raw?.let(::parseCatalog).orEmpty().ifEmpty(::defaultCatalog)
        val source = mergeBundledCatalog(existing)
        if (source != existing || raw.isNullOrBlank()) {
            nasApi.pushProfileBlobJson(PROFILE_ID, CATALOG_BLOB_KEY, serializeCatalog(source))
        }
        // Publish the complete catalog immediately. Cached images are attached
        // synchronously; uncached assets are hydrated afterwards by the
        // ViewModel without blocking category visibility.
        return source.map { item ->
            val assetKey = item.assetKey ?: return@map item
            val cached = File(cacheDirectory, assetKey).takeIf(::isValidImage)
            item.copy(imageUrl = cached?.toURI()?.toString().orEmpty())
        }
    }

    private fun mergeBundledCatalog(existing: List<NasAvatarCatalogItem>): List<NasAvatarCatalogItem> {
        val bundledAnimals = PROFILE_ANIMAL_AVATARS.associateBy { it.id }
        val bundledFamily = PROFILE_FAMILY_AVATARS.associateBy { it.id }
        val knownIds = existing.mapTo(mutableSetOf()) { it.id }
        return buildList {
            existing.forEach { item ->
                val animal = bundledAnimals[item.id]
                val family = bundledFamily[item.id]
                add(
                    when {
                        animal != null -> item.copy(
                        displayName = animal.label,
                        category = "animals",
                        assetKey = animal.assetKey
                        )
                        family != null -> item.copy(
                            displayName = family.label,
                            category = "family",
                            assetKey = family.assetKey
                        )
                        else -> item
                    }
                )
            }
            PROFILE_ANIMAL_AVATARS.forEachIndexed { index, animal ->
                if (knownIds.add(animal.id)) {
                    add(
                        NasAvatarCatalogItem(
                            id = animal.id,
                            displayName = animal.label,
                            category = "animals",
                            sortOrder = 1_000 + index,
                            assetKey = animal.assetKey
                        )
                    )
                }
            }
            PROFILE_FAMILY_AVATARS.forEachIndexed { index, family ->
                if (knownIds.add(family.id)) {
                    add(
                        NasAvatarCatalogItem(
                            id = family.id,
                            displayName = family.label,
                            category = "family",
                            sortOrder = 1_500 + index,
                            assetKey = family.assetKey
                        )
                    )
                }
            }
            PROFILE_EMOJI_AVATAR_CATALOG.forEach { avatar ->
                if (knownIds.add(avatar.id)) {
                    add(NasAvatarCatalogItem(avatar.id, avatar.displayName, category = avatar.category, sortOrder = avatar.sortOrder, assetKey = avatar.assetKey))
                }
            }
        }
    }

    private fun isValidImage(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        // Decoding every avatar at its full resolution allocates a large bitmap
        // for each file and was enough to freeze the profile picker on 4K TVs.
        // Bounds decoding validates the image header without allocating pixels.
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun parseCatalog(raw: String): List<NasAvatarCatalogItem> = JSONArray(raw).let { values ->
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val id = value.optString("id").trim(); val name = value.optString("displayName").trim()
                if (id.isBlank() || name.isBlank()) continue
                add(NasAvatarCatalogItem(id, name, category = value.optString("category", "all").ifBlank { "all" },
                    sortOrder = value.optInt("sortOrder", index), bgColor = value.optString("bgColor").takeIf(String::isNotBlank),
                    assetKey = value.optString("assetKey").takeIf(String::isNotBlank)))
            }
        }.sortedBy(NasAvatarCatalogItem::sortOrder)
    }

    private fun serializeCatalog(items: List<NasAvatarCatalogItem>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("displayName", it.displayName).put("category", it.category)
            .put("sortOrder", it.sortOrder).put("bgColor", it.bgColor).put("assetKey", it.assetKey)) }
    }.toString()

    private fun defaultCatalog(): List<NasAvatarCatalogItem> = listOf(
        fallback("blue", "Azur", "#1E88E5", 0), fallback("violet", "Violet", "#7E57C2", 1),
        fallback("pink", "Rose", "#D81B60", 2), fallback("red", "Rouge", "#E53935", 3),
        fallback("orange", "Orange", "#FB8C00", 4), fallback("yellow", "Soleil", "#F9A825", 5),
        fallback("green", "Forêt", "#43A047", 6), fallback("teal", "Lagune", "#00897B", 7),
        fallback("cyan", "Océan", "#00ACC1", 8), fallback("indigo", "Indigo", "#3949AB", 9),
        fallback("brown", "Terre", "#6D4C41", 10), fallback("slate", "Ardoise", "#546E7A", 11)
    )

    private fun loadBundledCachedCatalog(): List<NasAvatarCatalogItem> =
        mergeBundledCatalog(defaultCatalog()).map { item ->
            val assetKey = item.assetKey ?: return@map item
            val cached = File(cacheDirectory, assetKey).takeIf(::isValidImage)
            item.copy(imageUrl = cached?.toURI()?.toString().orEmpty())
        }

    private fun fallback(id: String, name: String, color: String, order: Int) =
        NasAvatarCatalogItem(id = "fallback_$id", displayName = name, category = "all", sortOrder = order, bgColor = color)

    private companion object {
        const val PROFILE_ID = 1
        const val CATALOG_BLOB_KEY = "avatar_catalog"
        const val FREEBOX_ROOT = "/Freebox"
    }
}
