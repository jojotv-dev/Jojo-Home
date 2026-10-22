package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class FreeboxConnectionSettings(
    val name: String = "",
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val showInSidebar: Boolean = false,
    val showSourceFolder: Boolean = false,
    val showExtensions: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val visibleServerFolders: Set<String> = emptySet(),
    val sidebarServerFolders: Set<String> = emptySet(),
    val serverFoldersConfigured: Boolean = false,
    val appToken: String = "",
    val sessionToken: String = "",
    val authTrackId: Int = -1,
    val authStatus: String = "",
    val serverFolders: Set<String> = emptySet(),
    val browserSortMode: String = "NAME_ASC",
    val browserFolderSortModes: Map<String, String> = emptyMap(),
    val browserViewMode: String = "LIST",
    val browserFolderViewMode: String = browserViewMode,
    val browserFileViewMode: String = browserViewMode,
    val browserFolderViewModes: Map<String, String> = emptyMap(),
    val browserFileViewModes: Map<String, String> = emptyMap()
) {
    val hasSavedConnection: Boolean
        get() = name.isNotBlank() && address.isNotBlank()
}


private const val DEFAULT_BROWSER_SORT_MODE = "NAME_ASC"
private const val DEFAULT_BROWSER_VIEW_MODE = "LIST"
private const val FREEBOX_DEFAULT_SORT_PATH = "/Freebox"

fun normalizeFreeboxBrowserSortPath(path: String): String {
    return path.trim().ifBlank { FREEBOX_DEFAULT_SORT_PATH }
}

fun FreeboxConnectionSettings.browserSortModeFor(folderPath: String): String {
    val normalizedPath = normalizeFreeboxBrowserSortPath(folderPath)
    return browserFolderSortModes[normalizedPath]
        ?: browserFolderSortModes[FREEBOX_DEFAULT_SORT_PATH]
        ?: DEFAULT_BROWSER_SORT_MODE
}

fun FreeboxConnectionSettings.browserFolderViewModeFor(folderPath: String): String {
    val normalizedPath = normalizeFreeboxBrowserSortPath(folderPath)
    return browserFolderViewModes[normalizedPath]
        ?: browserFolderViewModes[FREEBOX_DEFAULT_SORT_PATH]
        ?: browserFolderViewMode.ifBlank { DEFAULT_BROWSER_VIEW_MODE }
}

fun FreeboxConnectionSettings.browserFileViewModeFor(folderPath: String): String {
    val normalizedPath = normalizeFreeboxBrowserSortPath(folderPath)
    return browserFileViewModes[normalizedPath]
        ?: browserFileViewModes[FREEBOX_DEFAULT_SORT_PATH]
        ?: browserFileViewMode.ifBlank { DEFAULT_BROWSER_VIEW_MODE }
}

private fun Set<String>.toBrowserSortModes(): Map<String, String> {
    return mapNotNull { item ->
        val separator = item.indexOf('\t')
        if (separator <= 0 || separator >= item.lastIndex) return@mapNotNull null
        val path = normalizeFreeboxBrowserSortPath(item.substring(0, separator))
        val mode = item.substring(separator + 1).ifBlank { DEFAULT_BROWSER_SORT_MODE }
        path to mode
    }.toMap()
}

private fun Map<String, String>.toBrowserSortModeSet(): Set<String> {
    return map { (path, mode) -> "${normalizeFreeboxBrowserSortPath(path)}\t${mode.ifBlank { DEFAULT_BROWSER_SORT_MODE }}" }.toSet()
}

private fun Set<String>.toBrowserPathModes(defaultMode: String): Map<String, String> {
    return mapNotNull { item ->
        val separator = item.indexOf('\t')
        if (separator <= 0 || separator >= item.lastIndex) return@mapNotNull null
        val path = normalizeFreeboxBrowserSortPath(item.substring(0, separator))
        val mode = item.substring(separator + 1).ifBlank { defaultMode }
        path to mode
    }.toMap()
}

private fun Map<String, String>.toBrowserPathModeSet(defaultMode: String): Set<String> {
    return map { (path, mode) -> "${normalizeFreeboxBrowserSortPath(path)}\t${mode.ifBlank { defaultMode }}" }.toSet()
}

data class FreeboxAuthUpdate(
    val appToken: String,
    val trackId: Int,
    val status: String
)

data class FreeboxSessionUpdate(
    val sessionToken: String,
    val status: String
)

data class FreeboxFolderUpdate(
    val folders: Set<String>,
    val visibleFolders: Set<String>,
    val sidebarFolders: Set<String> = emptySet()
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class FreeboxSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "freebox_settings"
    }

    // The Freebox connection and its Home Videos library are household data,
    // not private profile data.  Reading profile-local credentials here made
    // the Videos row disappear as soon as a secondary profile was selected.
    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(freeboxSharedMediaProfileId(profileId), FEATURE)

    private val nameKey = stringPreferencesKey("freebox_name")
    private val addressKey = stringPreferencesKey("freebox_address")
    private val usernameKey = stringPreferencesKey("freebox_username")
    private val passwordKey = stringPreferencesKey("freebox_password")
    private val showInSidebarKey = booleanPreferencesKey("freebox_show_in_sidebar")
    private val showSourceFolderKey = booleanPreferencesKey("freebox_show_source_folder")
    private val showExtensionsKey = booleanPreferencesKey("freebox_show_extensions")
    private val showHiddenFilesKey = booleanPreferencesKey("freebox_show_hidden_files")
    private val visibleServerFoldersKey = stringSetPreferencesKey("freebox_visible_server_folders")
    private val sidebarServerFoldersKey = stringSetPreferencesKey("freebox_sidebar_server_folders")
    private val serverFoldersConfiguredKey = booleanPreferencesKey("freebox_server_folders_configured")
    private val appTokenKey = stringPreferencesKey("freebox_app_token")
    private val sessionTokenKey = stringPreferencesKey("freebox_session_token")
    private val authTrackIdKey = intPreferencesKey("freebox_auth_track_id")
    private val authStatusKey = stringPreferencesKey("freebox_auth_status")
    private val serverFoldersKey = stringSetPreferencesKey("freebox_server_folders")
    private val browserSortModeKey = stringPreferencesKey("freebox_browser_sort_mode")
    private val browserFolderSortModesKey = stringSetPreferencesKey("freebox_browser_folder_sort_modes")
    private val browserViewModeKey = stringPreferencesKey("freebox_browser_view_mode")
    private val browserFolderViewModeKey = stringPreferencesKey("freebox_browser_folder_view_mode")
    private val browserFileViewModeKey = stringPreferencesKey("freebox_browser_file_view_mode")
    private val browserFolderViewModesKey = stringSetPreferencesKey("freebox_browser_folder_view_modes")
    private val browserFileViewModesKey = stringSetPreferencesKey("freebox_browser_file_view_modes")

    val settings: Flow<FreeboxConnectionSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(freeboxSharedMediaProfileId(pid), FEATURE).data.map { prefs ->
            FreeboxConnectionSettings(
                name = prefs[nameKey].orEmpty(),
                address = prefs[addressKey].orEmpty(),
                username = prefs[usernameKey].orEmpty(),
                password = prefs[passwordKey].orEmpty(),
                showInSidebar = prefs[showInSidebarKey] ?: false,
                showSourceFolder = prefs[showSourceFolderKey] ?: false,
                showExtensions = prefs[showExtensionsKey] ?: true,
                showHiddenFiles = prefs[showHiddenFilesKey] ?: false,
                visibleServerFolders = prefs[visibleServerFoldersKey].orEmpty(),
                sidebarServerFolders = prefs[sidebarServerFoldersKey].orEmpty(),
                serverFoldersConfigured = prefs[serverFoldersConfiguredKey] ?: false,
                appToken = prefs[appTokenKey].orEmpty(),
                sessionToken = prefs[sessionTokenKey].orEmpty(),
                authTrackId = prefs[authTrackIdKey] ?: -1,
                authStatus = prefs[authStatusKey].orEmpty(),
                serverFolders = prefs[serverFoldersKey].orEmpty(),
                browserSortMode = prefs[browserSortModeKey] ?: DEFAULT_BROWSER_SORT_MODE,
                browserFolderSortModes = prefs[browserFolderSortModesKey].orEmpty().toBrowserSortModes(),
                browserViewMode = prefs[browserViewModeKey] ?: DEFAULT_BROWSER_VIEW_MODE,
                browserFolderViewMode = prefs[browserFolderViewModeKey] ?: prefs[browserViewModeKey] ?: DEFAULT_BROWSER_VIEW_MODE,
                browserFileViewMode = prefs[browserFileViewModeKey] ?: prefs[browserViewModeKey] ?: DEFAULT_BROWSER_VIEW_MODE,
                browserFolderViewModes = prefs[browserFolderViewModesKey].orEmpty().toBrowserPathModes(DEFAULT_BROWSER_VIEW_MODE),
                browserFileViewModes = prefs[browserFileViewModesKey].orEmpty().toBrowserPathModes(DEFAULT_BROWSER_VIEW_MODE)
            )
        }
    }

    suspend fun save(settings: FreeboxConnectionSettings) {
        store().edit { prefs ->
            prefs[nameKey] = settings.name.trim()
            prefs[addressKey] = settings.address.trim()
            prefs[usernameKey] = settings.username.trim()
            prefs[passwordKey] = settings.password
            prefs[showInSidebarKey] = settings.showInSidebar
            prefs[showSourceFolderKey] = settings.showSourceFolder
            prefs[showExtensionsKey] = settings.showExtensions
            prefs[showHiddenFilesKey] = settings.showHiddenFiles
            prefs[visibleServerFoldersKey] = settings.visibleServerFolders
            prefs[sidebarServerFoldersKey] = settings.sidebarServerFolders
            prefs[serverFoldersConfiguredKey] = settings.serverFoldersConfigured
        }
    }

    suspend fun setShowInSidebar(enabled: Boolean) {
        store().edit { it[showInSidebarKey] = enabled }
    }

    suspend fun setShowSourceFolder(enabled: Boolean) {
        store().edit { it[showSourceFolderKey] = enabled }
    }

    suspend fun setShowExtensions(enabled: Boolean) {
        store().edit { it[showExtensionsKey] = enabled }
    }

    suspend fun setShowHiddenFiles(enabled: Boolean) {
        store().edit { it[showHiddenFilesKey] = enabled }
    }

    suspend fun setVisibleServerFolder(folderName: String, visible: Boolean) {
        store().edit { prefs ->
            val current = prefs[visibleServerFoldersKey].orEmpty()
            prefs[visibleServerFoldersKey] = if (visible) current + folderName else current - folderName
            prefs[serverFoldersConfiguredKey] = true
        }
    }

    suspend fun setSidebarServerFolder(folderName: String, visible: Boolean) {
        store().edit { prefs ->
            val current = prefs[sidebarServerFoldersKey].orEmpty()
            prefs[sidebarServerFoldersKey] = if (visible) current + folderName else current - folderName
        }
    }

    suspend fun setBrowserSortMode(modeName: String) {
        setBrowserSortModeForFolder(FREEBOX_DEFAULT_SORT_PATH, modeName)
    }

    suspend fun setBrowserSortModeForFolder(folderPath: String, modeName: String) {
        val normalizedPath = normalizeFreeboxBrowserSortPath(folderPath)
        val normalizedMode = modeName.ifBlank { DEFAULT_BROWSER_SORT_MODE }
        store().edit { prefs ->
            val current = prefs[browserFolderSortModesKey].orEmpty().toBrowserSortModes()
            val updated = current + (normalizedPath to normalizedMode)
            prefs[browserFolderSortModesKey] = updated.toBrowserSortModeSet()
            prefs[browserSortModeKey] = normalizedMode
        }
    }

    suspend fun setBrowserViewMode(modeName: String) {
        store().edit { prefs ->
            prefs[browserViewModeKey] = modeName.ifBlank { "LIST" }
        }
    }

    suspend fun setBrowserFolderViewMode(modeName: String) {
        setBrowserFolderViewModeForFolder(FREEBOX_DEFAULT_SORT_PATH, modeName)
    }

    suspend fun setBrowserFileViewMode(modeName: String) {
        setBrowserFileViewModeForFolder(FREEBOX_DEFAULT_SORT_PATH, modeName)
    }

    suspend fun setBrowserFolderViewModeForFolder(folderPath: String, modeName: String) {
        val normalizedPath = normalizeFreeboxBrowserSortPath(folderPath)
        val normalizedMode = modeName.ifBlank { DEFAULT_BROWSER_VIEW_MODE }
        store().edit { prefs ->
            val current = prefs[browserFolderViewModesKey].orEmpty().toBrowserPathModes(DEFAULT_BROWSER_VIEW_MODE)
            prefs[browserFolderViewModesKey] = (current + (normalizedPath to normalizedMode))
                .toBrowserPathModeSet(DEFAULT_BROWSER_VIEW_MODE)
            prefs[browserFolderViewModeKey] = normalizedMode
        }
    }

    suspend fun setBrowserFileViewModeForFolder(folderPath: String, modeName: String) {
        val normalizedPath = normalizeFreeboxBrowserSortPath(folderPath)
        val normalizedMode = modeName.ifBlank { DEFAULT_BROWSER_VIEW_MODE }
        store().edit { prefs ->
            val current = prefs[browserFileViewModesKey].orEmpty().toBrowserPathModes(DEFAULT_BROWSER_VIEW_MODE)
            prefs[browserFileViewModesKey] = (current + (normalizedPath to normalizedMode))
                .toBrowserPathModeSet(DEFAULT_BROWSER_VIEW_MODE)
            prefs[browserFileViewModeKey] = normalizedMode
        }
    }

    suspend fun saveAuthorization(update: FreeboxAuthUpdate) {
        store().edit { prefs ->
            prefs[appTokenKey] = update.appToken
            prefs[authTrackIdKey] = update.trackId
            prefs[authStatusKey] = update.status
        }
    }

    suspend fun saveAuthStatus(status: String) {
        store().edit { it[authStatusKey] = status }
    }

    suspend fun saveSession(update: FreeboxSessionUpdate) {
        store().edit { prefs ->
            prefs[sessionTokenKey] = update.sessionToken
            prefs[authStatusKey] = update.status
        }
    }

    suspend fun saveServerFolders(update: FreeboxFolderUpdate) {
        store().edit { prefs ->
            prefs[serverFoldersKey] = update.folders
            prefs[visibleServerFoldersKey] = update.visibleFolders
            prefs[sidebarServerFoldersKey] = update.sidebarFolders
            prefs[serverFoldersConfiguredKey] = true
        }
    }
}

