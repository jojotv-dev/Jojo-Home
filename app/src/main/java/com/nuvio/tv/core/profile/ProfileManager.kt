package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.ProfileSelectionVisual
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_PROFILES = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val profileSwitchMutex = Mutex()

    private val loadedProfileId = MutableStateFlow(1)
    private val profileReady = MutableStateFlow(false)
    val activeProfileId: StateFlow<Int> = loadedProfileId.asStateFlow()
    val activeProfileReady: StateFlow<Boolean> = profileReady.asStateFlow()

    init {
        scope.launch {
            profileDataStore.activeProfileId.collect { id ->
                loadedProfileId.value = id
                profileReady.value = true
            }
        }
    }

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val profileSelectionVisual: StateFlow<ProfileSelectionVisual> = profileDataStore.profileSelectionVisual
        .stateIn(scope, SharingStarted.Eagerly, ProfileSelectionVisual.CLASSIC)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = "Profile 1", avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileSwitchMutex.withLock {
                profileDataStore.setActiveProfile(id)
            }
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun setProfileSelectionVisual(visual: ProfileSelectionVisual) {
        profileDataStore.setProfileSelectionVisual(visual)
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null
    ): Boolean {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return false

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return false

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { "Profile $nextId" },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        return true
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        if (activeProfileId.value == id) {
            profileDataStore.setActiveProfile(1)
        }
        deleteProfileDataAsync(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)
        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffixWithExtension)) {
                    file.delete()
                }
            }
        }

        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }
    }
}
