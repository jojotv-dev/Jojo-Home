package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    private val gson = Gson()

    private fun cacheFile(url: String): File {
        val directory = File(context.cacheDir, "home_catalogs")
        directory.mkdirs()
        return File(directory, "${url.length}_${Integer.toHexString(url.hashCode())}.json")
    }

    private suspend fun readCachedCatalog(url: String): CatalogRow? = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(url)
            if (!file.exists()) null else gson.fromJson(file.readText(), CatalogRow::class.java)
        }.onFailure { error ->
            Log.w(TAG, "Ignoring unreadable catalog cache for $url", error)
        }.getOrNull()
    }

    private suspend fun writeCachedCatalog(url: String, row: CatalogRow) = withContext(Dispatchers.IO) {
        runCatching {
            val target = cacheFile(url)
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(gson.toJson(row))
            if (!temporary.renameTo(target)) {
                target.writeText(temporary.readText())
                temporary.delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to cache catalog for $url", error)
        }
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        // Home must not wait on a remote addon before it can draw. A cached
        // response is emitted first, then replaced by the fresh response below.
        readCachedCatalog(url)?.let { cached ->
            emit(NetworkResult.Success(cached))
        }
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas.map { it.toDomain() }.distinctBy { it.id }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

                val effectiveSkipStep = if (skip == 0 && items.isNotEmpty() && items.size < skipStep) {
                    items.size
                } else {
                    skipStep
                }
                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && items.isNotEmpty(),
                    currentPage = if (effectiveSkipStep > 0) skip / effectiveSkipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = effectiveSkipStep,
                    extraArgs = extraArgs
                )
                writeCachedCatalog(url, catalogRow)
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
