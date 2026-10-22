package com.nuvio.tv.core.sync

import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time local-to-NAS seed used only after creating a NAS account.  It keeps
 * the NAS authoritative afterwards; a login on another device is always pull-first.
 */
@Singleton
class NasMigrationService @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val librarySyncService: LibrarySyncService,
    private val collectionSyncService: CollectionSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val addonSyncService: AddonSyncService,
    private val pluginSyncService: PluginSyncService
) {
    suspend fun seedAllLocalData(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val originalProfile = profileManager.activeProfileId.value
            profileSyncService.pushToRemote().getOrThrow()
            try {
                profileManager.profiles.value.forEach { profile ->
                    profileManager.setActiveProfile(profile.id)
                    // Addons and several settings are profile-scoped DataStores.
                    // Wait for the active profile flow before serialising them, or a
                    // fast migration can publish every profile under the previous id.
                    profileManager.activeProfileId.first { it == profile.id }
                    watchProgressSyncService.pushToRemote(profile.id).getOrThrow()
                    watchedItemsSyncService.pushToRemote().getOrThrow()
                    librarySyncService.pushToRemote().getOrThrow()
                    collectionSyncService.pushToRemote().getOrThrow()
                    homeCatalogSettingsSyncService.pushToRemote("nas_initial_migration").getOrThrow()
                    addonSyncService.pushToRemote().getOrThrow()
                    pluginSyncService.pushToRemote().getOrThrow()
                }
            } finally {
                profileManager.setActiveProfile(originalProfile)
            }
        }
    }
}
