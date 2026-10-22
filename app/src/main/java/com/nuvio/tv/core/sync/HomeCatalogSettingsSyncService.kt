package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeCatalogSettingsSyncService"
private const val PAYLOAD_SAMPLE_LIMIT = 5

@Serializable
data class SyncCatalogItem(
    @SerialName("addon_id") val addonId: String,
    val type: String,
    @SerialName("catalog_id") val catalogId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("custom_title") val customTitle: String = "",
    @SerialName("is_collection") val isCollection: Boolean = false,
    @SerialName("collection_id") val collectionId: String = "",
)

@Serializable
data class SyncHomeCatalogPayload(
    val items: List<SyncCatalogItem> = emptyList(),
)

@Singleton
class HomeCatalogSettingsSyncService @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val nasSyncApi: NasIptvFavoritesApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    suspend fun pushToRemote(reason: String = "unspecified"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val payload = loadLocalPayload()
            Log.d(TAG, "Push start profile=$profileId reason=$reason ${payload.summary()}")
            if (!payload.hasEnabledItems()) {
                Log.w(TAG, "Push skipped profile=$profileId reason=$reason because payload would disable every home row")
                return@withContext Result.success(Unit)
            }
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour synchroniser l'accueil." }
            nasSyncApi.pushProfileBlobJson(profileId, "home_catalog", json.encodeToString(SyncHomeCatalogPayload.serializer(), payload))

            Log.d(TAG, "Push success profile=$profileId reason=$reason")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Push failed reason=$reason", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val localState = layoutPreferenceDataStore.getHomeCatalogSettingsState()
            Log.d(TAG, "Pull start profile=$profileId ${localState.summary()}")
            require(nasSyncApi.hasSession()) { "Connecte ton compte NAS pour restaurer l'accueil." }

            if (localState.disabledKeys.any(::hasLegacyHomeCatalogDisabledKeyFormat)) {
                val localPayload = loadLocalPayload()
                if (localPayload.items.isNotEmpty()) {
                    isSyncingFromRemote = true
                    try {
                        layoutPreferenceDataStore.applyCatalogSettingsFromRemote(localPayload)
                    } finally {
                        isSyncingFromRemote = false
                    }
                    Log.i(TAG, "Migrated legacy local keys profile=$profileId ${localPayload.summary()} (no startup push)")
                    return@withContext Result.success(true)
                }
            }

            val remoteJson = nasSyncApi.pullProfileBlobJson(profileId, "home_catalog")
                ?: return@withContext Result.success(false)
            val remotePayload = runCatching { json.decodeFromString(SyncHomeCatalogPayload.serializer(), remoteJson) }.getOrNull()
                ?: return@withContext Result.success(false)
            if (remotePayload.items.isEmpty() || !remotePayload.hasEnabledItems()) return@withContext Result.success(false)
            isSyncingFromRemote = true
            try {
                layoutPreferenceDataStore.applyCatalogSettingsFromRemote(remotePayload)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Pull NAS success profile=$profileId ${remotePayload.summary()}")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull home catalog settings", e)
            Result.failure(e)
        }
    }

    fun triggerPush() {
        if (isSyncingFromRemote) return
        pushJob?.cancel()
        pushJob = scope.launch {
            if (!nasSyncApi.hasSession()) return@launch
            delay(500)
            pushToRemote(reason = "triggerPush")
        }
    }

    private suspend fun loadLocalPayload(): SyncHomeCatalogPayload {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val collections = collectionsDataStore.getCurrentCollections()
        return layoutPreferenceDataStore.exportCatalogSettingsToSyncPayload(addons, collections)
    }

}

private fun LocalHomeCatalogSettingsState.summary(): String {
    val orderSample = orderKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val disabledSample = disabledKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val titleSample = customTitles.keys.take(PAYLOAD_SAMPLE_LIMIT)
    return "localState(order=${orderKeys.size}, disabled=${disabledKeys.size}, titles=${customTitles.size}, orderSample=$orderSample, disabledSample=$disabledSample, titleSample=$titleSample)"
}

private fun SyncHomeCatalogPayload.summary(): String {
    val disabledCount = items.count { !it.enabled }
    val collectionCount = items.count { it.isCollection }
    val sample = items.take(PAYLOAD_SAMPLE_LIMIT).joinToString(separator = " | ") { item ->
        if (item.isCollection) {
            "collection:${item.collectionId},enabled=${item.enabled},order=${item.order}"
        } else {
            "catalog:${item.addonId}/${item.type}/${item.catalogId},enabled=${item.enabled},order=${item.order}"
        }
    }
    return "payload(items=${items.size}, disabled=$disabledCount, collections=$collectionCount, sample=[$sample])"
}

private fun SyncHomeCatalogPayload.hasEnabledItems(): Boolean =
    items.any { it.enabled }
