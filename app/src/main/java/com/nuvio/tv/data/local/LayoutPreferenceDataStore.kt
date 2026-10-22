@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.jojotv.home.playercompat.LocalHomeCatalogSettingsState
import com.jojotv.home.playercompat.SyncHomeCatalogPayload
import com.jojotv.home.playercompat.buildHomeCatalogSyncPayload
import com.jojotv.home.playercompat.homeCatalogKey
import com.jojotv.home.playercompat.homeCollectionKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.ThumbnailSize
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.HomeCardSize
import com.nuvio.tv.domain.model.ThumbnailOrientation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "layout_settings"
        private const val DEFAULT_POSTER_CARD_WIDTH_DP = 126
        private const val DEFAULT_POSTER_CARD_HEIGHT_DP = 189
        private const val DEFAULT_POSTER_CARD_CORNER_RADIUS_DP = 12
        private const val DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 3
        private const val MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 0
        private const val THUMBNAIL_SIZE_SCALE_VERSION = 2
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()

    private val layoutKey = stringPreferencesKey("selected_layout")
    private val hasChosenKey = booleanPreferencesKey("has_chosen_layout")
    private val heroCatalogKey = stringPreferencesKey("hero_catalog_key")
    private val heroCatalogKeysKey = stringPreferencesKey("hero_catalog_keys")
    private val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
    private val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val customCatalogTitlesKey = stringPreferencesKey("custom_catalog_titles")
    private val sidebarCollapsedKey = booleanPreferencesKey("sidebar_collapsed_by_default")
    private val modernSidebarEnabledKey = booleanPreferencesKey("modern_sidebar_enabled")
    private val legacyModernSidebarEnabledKey = booleanPreferencesKey("glass_sidepanel_enabled")
    private val modernSidebarBlurEnabledKey = booleanPreferencesKey("modern_sidebar_blur_enabled")
    private val modernLandscapePostersEnabledKey = booleanPreferencesKey("modern_landscape_posters_enabled")
    private val heroSectionEnabledKey = booleanPreferencesKey("hero_section_enabled")
    private val posterLabelsEnabledKey = booleanPreferencesKey("poster_labels_enabled")
    private val catalogAddonNameEnabledKey = booleanPreferencesKey("catalog_addon_name_enabled")
    private val catalogTypeSuffixEnabledKey = booleanPreferencesKey("catalog_type_suffix_enabled")
    private val classicFocusGradientEnabledKey = booleanPreferencesKey("classic_focus_gradient_enabled")
    private val focusedPosterBackdropExpandEnabledKey = booleanPreferencesKey("focused_poster_backdrop_expand_enabled")
    private val focusedPosterBackdropExpandDelaySecondsKey = intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")
    private val focusedPosterBackdropTrailerEnabledKey = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
    private val focusedPosterBackdropTrailerMutedKey = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
    private val focusedPosterBackdropTrailerPlaybackTargetKey =
        stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")
    private val posterCardWidthDpKey = intPreferencesKey("poster_card_width_dp")
    private val posterCardHeightDpKey = intPreferencesKey("poster_card_height_dp")
    private val posterCardCornerRadiusDpKey = intPreferencesKey("poster_card_corner_radius_dp")
    private val homeCarouselCardSizeKey = stringPreferencesKey("home_carousel_card_size")
    private val homeMediaRowCardSizeKey = stringPreferencesKey("home_media_row_card_size")
    private val blurUnwatchedEpisodesKey = booleanPreferencesKey("blur_unwatched_episodes")
    private val useEpisodeThumbnailsInCwKey = booleanPreferencesKey("use_episode_thumbnails_in_cw")
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")
    private val nextUpFromFurthestEpisodeKey = booleanPreferencesKey("next_up_from_furthest_episode")
    private val blurContinueWatchingNextUpKey = booleanPreferencesKey("blur_continue_watching_next_up")
    private val continueWatchingSortModeKey = stringPreferencesKey("continue_watching_sort_mode")
    private val continueWatchingPortraitModeKey = booleanPreferencesKey("continue_watching_portrait_mode")
    private val continueWatchingThumbnailOrientationKey =
        stringPreferencesKey("continue_watching_thumbnail_orientation")
    private val videoThumbnailOrientationKey =
        stringPreferencesKey("freebox_video_thumbnail_orientation")
    private val movieFavoritesThumbnailOrientationKey =
        stringPreferencesKey("iptv_movie_favorites_thumbnail_orientation")
    private val seriesFavoritesThumbnailOrientationKey =
        stringPreferencesKey("iptv_series_favorites_thumbnail_orientation")
    private val continueWatchingThumbnailSizeKey = stringPreferencesKey("continue_watching_thumbnail_size")
    private val continueWatchingThumbnailSizeScaleVersionKey =
        intPreferencesKey("continue_watching_thumbnail_size_scale_version")
    private val detailPageTrailerButtonEnabledKey = booleanPreferencesKey("detail_page_trailer_button_enabled")
    private val preferExternalMetaAddonDetailKey = booleanPreferencesKey("prefer_external_meta_addon_detail")
    private val modernHeroFullScreenBackdropKey = booleanPreferencesKey("modern_hero_full_screen_backdrop")
    private val detailedHomeMediaRowsEnabledKey = booleanPreferencesKey("detailed_home_media_rows_enabled")
    private val homeRowCarouselKeysKey = stringPreferencesKey("home_row_carousel_keys")
    private val homeRowDetailedKeysKey = stringPreferencesKey("home_row_detailed_keys")
    private val hideUnreleasedContentKey = booleanPreferencesKey("hide_unreleased_content")
    private val showFullReleaseDateKey = booleanPreferencesKey("show_full_release_date")
    private val memoryOnlyVerticalScrollKey = booleanPreferencesKey("memory_only_vertical_scroll")
    private val smoothBringIntoViewEnabledKey = booleanPreferencesKey("smooth_bring_into_view_enabled")
    private val fastHorizontalNavigationEnabledKey = booleanPreferencesKey("fast_horizontal_navigation_enabled")
    private val followAddonsOrderKey = booleanPreferencesKey("follow_addons_order")
    private val composeHighlighterEnabledKey = booleanPreferencesKey("compose_highlighter_enabled")

    private fun <T> profileFlow(extract: (prefs: androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> extract(prefs) }
        }

    val selectedLayout: Flow<HomeLayout> = profileFlow { prefs ->
        val layoutName = prefs[layoutKey] ?: HomeLayout.MODERN.name
        try {
            HomeLayout.valueOf(layoutName)
        } catch (e: IllegalArgumentException) {
            HomeLayout.MODERN
        }
    }

    val hasChosenLayout: Flow<Boolean> = profileFlow { prefs ->
        prefs[hasChosenKey] ?: false
    }

    val heroCatalogSelections: Flow<List<String>> = profileFlow { prefs ->
        val multiSelection = parseCatalogKeys(prefs[heroCatalogKeysKey])
        if (multiSelection.isNotEmpty()) {
            multiSelection
        } else {
            prefs[heroCatalogKey]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::listOf)
                .orEmpty()
        }
    }

    val heroCatalogSelection: Flow<String?> = heroCatalogSelections.map { selections ->
        selections.firstOrNull()
    }

    val homeCatalogOrderKeys: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs[homeCatalogOrderKeysKey])
        }
    }

    val disabledHomeCatalogKeys: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCatalogKeys(prefs[disabledHomeCatalogKeysKey])
        }
    }

    val customCatalogTitles: Flow<Map<String, String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        val effectivePid = if (usePrimary) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            parseCustomTitles(prefs[customCatalogTitlesKey])
        }
    }

    val sidebarCollapsedByDefault: Flow<Boolean> = profileFlow { prefs ->
        val modernSidebarEnabled =
            prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
        if (modernSidebarEnabled) {
            false
        } else {
            prefs[sidebarCollapsedKey] ?: false
        }
    }

    val modernSidebarEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
    }

    val modernSidebarBlurEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernSidebarBlurEnabledKey] ?: false
    }

    val modernLandscapePostersEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernLandscapePostersEnabledKey] ?: false
    }

    val modernHeroFullScreenBackdropEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernHeroFullScreenBackdropKey] ?: false
    }

    val detailedHomeMediaRowsEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[detailedHomeMediaRowsEnabledKey] ?: false
    }

    /** Rows using the Continue Watching carousel presentation. */
    val homeRowCarouselKeys: Flow<Set<String>> = profileFlow { prefs ->
        decodeRowKeys(prefs[homeRowCarouselKeysKey]).ifEmpty {
            if (prefs[detailedHomeMediaRowsEnabledKey] == true) {
                setOf("continue_watching")
            } else {
                emptySet()
            }
        }
    }

    /** Rows whose focused item exposes the metadata/detail presentation. */
    val homeRowDetailedKeys: Flow<Set<String>> = profileFlow { prefs ->
        decodeRowKeys(prefs[homeRowDetailedKeysKey])
    }

    val heroSectionEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[heroSectionEnabledKey] ?: true
    }

    val discoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[discoverLocationKey] ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(stored) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val lastNonOffDiscoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[lastNonOffDiscoverLocationKey]
            ?.takeIf { it != DiscoverLocation.OFF.name }
        val fallback = prefs[discoverLocationKey]
            ?.takeIf { it != DiscoverLocation.OFF.name }
        val source = stored ?: fallback ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(source) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val posterLabelsEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[posterLabelsEnabledKey] ?: true
    }

    val catalogAddonNameEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogAddonNameEnabledKey] ?: true
    }

    val catalogTypeSuffixEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogTypeSuffixEnabledKey] ?: true
    }

    val classicFocusGradientEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[classicFocusGradientEnabledKey] ?: false
    }

    val focusedPosterBackdropExpandEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropExpandEnabledKey] ?: true
    }

    val focusedPosterBackdropExpandDelaySeconds: Flow<Int> = profileFlow { prefs ->
        (prefs[focusedPosterBackdropExpandDelaySecondsKey]
            ?: DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
            .coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
    }

    val focusedPosterBackdropTrailerEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerEnabledKey] ?: false
    }

    val focusedPosterBackdropTrailerMuted: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerMutedKey] ?: true
    }

    val focusedPosterBackdropTrailerPlaybackTarget: Flow<FocusedPosterTrailerPlaybackTarget> =
        profileFlow { prefs ->
            val stored = prefs[focusedPosterBackdropTrailerPlaybackTargetKey]
                ?: FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
            runCatching { FocusedPosterTrailerPlaybackTarget.valueOf(stored) }
                .getOrDefault(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
        }

    val posterCardWidthDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardWidthDpKey] ?: DEFAULT_POSTER_CARD_WIDTH_DP
    }

    val posterCardHeightDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardHeightDpKey] ?: DEFAULT_POSTER_CARD_HEIGHT_DP
    }

    val posterCardCornerRadiusDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardCornerRadiusDpKey] ?: DEFAULT_POSTER_CARD_CORNER_RADIUS_DP
    }

    val homeCarouselCardSize: Flow<HomeCardSize> = profileFlow { prefs ->
        HomeCardSize.fromName(prefs[homeCarouselCardSizeKey])
    }

    val homeMediaRowCardSize: Flow<HomeCardSize> = profileFlow { prefs ->
        HomeCardSize.fromName(prefs[homeMediaRowCardSizeKey])
    }

    val blurUnwatchedEpisodes: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurUnwatchedEpisodesKey] ?: false
    }

    val useEpisodeThumbnailsInCw: Flow<Boolean> = profileFlow { prefs ->
        prefs[useEpisodeThumbnailsInCwKey] ?: true
    }

    val showUnairedNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[showUnairedNextUpKey] ?: true
    }

    val nextUpFromFurthestEpisode: Flow<Boolean> = profileFlow { prefs ->
        prefs[nextUpFromFurthestEpisodeKey] ?: true
    }

    val blurContinueWatchingNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurContinueWatchingNextUpKey] ?: false
    }

    val continueWatchingThumbnailOrientation: Flow<ThumbnailOrientation> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            val profileStore = factory.get(profileId, FEATURE)
            profileStore.data.map { prefs ->
                val storedOrientation = prefs[continueWatchingThumbnailOrientationKey]
                if (storedOrientation != null) {
                    ThumbnailOrientation.fromName(storedOrientation)
                } else {
                    val migratedOrientation = if (prefs[continueWatchingPortraitModeKey] == true) {
                        ThumbnailOrientation.PORTRAIT
                    } else {
                        ThumbnailOrientation.LANDSCAPE
                    }
                    profileStore.edit { mutablePrefs ->
                        if (mutablePrefs[continueWatchingThumbnailOrientationKey] == null) {
                            mutablePrefs[continueWatchingThumbnailOrientationKey] =
                                migratedOrientation.name
                        }
                    }
                    migratedOrientation
                }
            }
        }

    val videoThumbnailOrientation: Flow<ThumbnailOrientation> = profileFlow { prefs ->
        ThumbnailOrientation.fromName(
            prefs[videoThumbnailOrientationKey] ?: ThumbnailOrientation.DEFAULT.name
        )
    }

    val movieFavoritesThumbnailOrientation: Flow<ThumbnailOrientation> = profileFlow { prefs ->
        ThumbnailOrientation.fromName(
            prefs[movieFavoritesThumbnailOrientationKey] ?: ThumbnailOrientation.PORTRAIT.name
        )
    }

    val seriesFavoritesThumbnailOrientation: Flow<ThumbnailOrientation> = profileFlow { prefs ->
        ThumbnailOrientation.fromName(
            prefs[seriesFavoritesThumbnailOrientationKey] ?: ThumbnailOrientation.PORTRAIT.name
        )
    }

    val continueWatchingThumbnailSize: Flow<ThumbnailSize> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            val profileStore = factory.get(profileId, FEATURE)
            profileStore.data.map { prefs ->
                val storedName = prefs[continueWatchingThumbnailSizeKey]
                val requiresMigration =
                    (prefs[continueWatchingThumbnailSizeScaleVersionKey] ?: 1) <
                        THUMBNAIL_SIZE_SCALE_VERSION
                val size = if (requiresMigration && storedName != null) {
                    ThumbnailSize.fromLegacyName(storedName)
                } else {
                    ThumbnailSize.fromName(storedName ?: ThumbnailSize.DEFAULT.name)
                }

                if (requiresMigration) {
                    profileStore.edit { mutablePrefs ->
                        val currentVersion =
                            mutablePrefs[continueWatchingThumbnailSizeScaleVersionKey] ?: 1
                        if (currentVersion < THUMBNAIL_SIZE_SCALE_VERSION) {
                            val currentName = mutablePrefs[continueWatchingThumbnailSizeKey]
                            if (currentName != null) {
                                mutablePrefs[continueWatchingThumbnailSizeKey] =
                                    ThumbnailSize.fromLegacyName(currentName).name
                            }
                            mutablePrefs[continueWatchingThumbnailSizeScaleVersionKey] =
                                THUMBNAIL_SIZE_SCALE_VERSION
                        }
                    }
                }
                size
            }
        }

        val continueWatchingSortMode: Flow<ContinueWatchingSortMode> = profileFlow { prefs ->
        val stored = prefs[continueWatchingSortModeKey] ?: ContinueWatchingSortMode.DEFAULT.name
        runCatching { ContinueWatchingSortMode.valueOf(stored) }
            .getOrDefault(ContinueWatchingSortMode.DEFAULT)
    }

    val detailPageTrailerButtonEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[detailPageTrailerButtonEnabledKey] ?: true
    }

    val preferExternalMetaAddonDetail: Flow<Boolean> = profileFlow { prefs ->
        prefs[preferExternalMetaAddonDetailKey] ?: true
    }

    val hideUnreleasedContent: Flow<Boolean> = profileFlow { prefs ->
        prefs[hideUnreleasedContentKey] ?: false
    }

    val showFullReleaseDate: Flow<Boolean> = profileFlow { prefs ->
        prefs[showFullReleaseDateKey] ?: true
    }

    val memoryOnlyVerticalScroll: Flow<Boolean> = profileFlow { prefs ->
        prefs[memoryOnlyVerticalScrollKey] ?: true
    }

    val smoothBringIntoViewEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[smoothBringIntoViewEnabledKey] ?: true
    }

    val fastHorizontalNavigationEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[fastHorizontalNavigationEnabledKey] ?: false
    }

    val followAddonsOrder: Flow<Boolean> = profileFlow { prefs ->
        prefs[followAddonsOrderKey] ?: false
    }

    val composeHighlighterEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[composeHighlighterEnabledKey] ?: false
    }

    suspend fun setMemoryOnlyVerticalScroll(enabled: Boolean) {
        store().edit { prefs ->
            prefs[memoryOnlyVerticalScrollKey] = enabled
        }
    }

    suspend fun setSmoothBringIntoViewEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[smoothBringIntoViewEnabledKey] = enabled
        }
    }

    suspend fun setFastHorizontalNavigationEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fastHorizontalNavigationEnabledKey] = enabled
        }
    }

    suspend fun setFollowAddonsOrder(enabled: Boolean) {
        store().edit { prefs ->
            prefs[followAddonsOrderKey] = enabled
        }
    }

    suspend fun setComposeHighlighterEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[composeHighlighterEnabledKey] = enabled
        }
    }

    suspend fun setLayout(layout: HomeLayout) {
        store().edit { prefs ->
            val hadChosenLayout = prefs[hasChosenKey] ?: false
            prefs[layoutKey] = layout.name
            if (
                layout == HomeLayout.MODERN &&
                !hadChosenLayout &&
                prefs[focusedPosterBackdropTrailerPlaybackTargetKey] == null
            ) {
                prefs[focusedPosterBackdropTrailerPlaybackTargetKey] =
                    FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
            }
            prefs[hasChosenKey] = true
        }
    }

    suspend fun setHeroCatalogKeys(catalogKeys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(catalogKeys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(heroCatalogKeysKey)
                prefs.remove(heroCatalogKey)
            } else {
                prefs[heroCatalogKeysKey] = gson.toJson(normalizedKeys)
                prefs[heroCatalogKey] = normalizedKeys.first()
            }
        }
    }

    suspend fun setHeroCatalogKey(catalogKey: String) {
        setHeroCatalogKeys(listOf(catalogKey))
    }

    suspend fun setHomeCatalogOrderKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(homeCatalogOrderKeysKey)
            } else {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setDisabledHomeCatalogKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(disabledHomeCatalogKeysKey)
            } else {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setSidebarCollapsedByDefault(collapsed: Boolean) {
        store().edit { prefs ->
            val modernSidebarEnabled =
                prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
            prefs[sidebarCollapsedKey] = if (modernSidebarEnabled) false else collapsed
        }
    }

    suspend fun setModernSidebarEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernSidebarEnabledKey] = enabled
            prefs.remove(legacyModernSidebarEnabledKey)
            if (enabled) {
                prefs[sidebarCollapsedKey] = false
            }
        }
    }

    suspend fun setModernSidebarBlurEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernSidebarBlurEnabledKey] = enabled
        }
    }

    suspend fun setModernLandscapePostersEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernLandscapePostersEnabledKey] = enabled
        }
    }

    suspend fun setModernHeroFullScreenBackdropEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernHeroFullScreenBackdropKey] = enabled
        }
    }

    suspend fun setDetailedHomeMediaRowsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[detailedHomeMediaRowsEnabledKey] = enabled
        }
    }

    suspend fun setHomeRowCarouselEnabled(rowKey: String, enabled: Boolean) {
        updateRowKeySet(homeRowCarouselKeysKey, rowKey, enabled)
    }

    suspend fun setHomeRowDetailedEnabled(rowKey: String, enabled: Boolean) {
        updateRowKeySet(homeRowDetailedKeysKey, rowKey, enabled)
    }

    private suspend fun updateRowKeySet(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        rowKey: String,
        enabled: Boolean
    ) {
        store().edit { prefs ->
            val keys = decodeRowKeys(prefs[key]).toMutableSet()
            if (enabled) keys += rowKey else keys -= rowKey
            prefs[key] = gson.toJson(keys.toList().sorted())
            // Presence of the new per-row setting disables the legacy global
            // fallback, including when the user deliberately clears the list.
            prefs[detailedHomeMediaRowsEnabledKey] = false
        }
    }

    private fun decodeRowKeys(value: String?): Set<String> = runCatching {
        gson.fromJson<List<String>>(value ?: "[]", object : TypeToken<List<String>>() {}.type)
            .orEmpty()
            .filter(String::isNotBlank)
            .toSet()
    }.getOrDefault(emptySet())

    suspend fun setHeroSectionEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[heroSectionEnabledKey] = enabled
        }
    }

    suspend fun setDiscoverLocation(location: DiscoverLocation) {
        store().edit { prefs ->
            prefs[discoverLocationKey] = location.name
            if (location != DiscoverLocation.OFF) {
                prefs[lastNonOffDiscoverLocationKey] = location.name
            }
            prefs.remove(legacySearchDiscoverEnabledKey)
        }
    }

    suspend fun setPosterLabelsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[posterLabelsEnabledKey] = enabled
        }
    }

    suspend fun setCatalogAddonNameEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogAddonNameEnabledKey] = enabled
        }
    }

    suspend fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogTypeSuffixEnabledKey] = enabled
        }
    }

    suspend fun setClassicFocusGradientEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[classicFocusGradientEnabledKey] = enabled
        }
    }

    suspend fun setFocusedPosterBackdropExpandEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerEnabledKey] = false
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setFocusedPosterBackdropExpandDelaySeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandDelaySecondsKey] =
                seconds.coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
        }
    }

    suspend fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerMutedKey] = muted
        }
    }

    suspend fun setFocusedPosterBackdropTrailerPlaybackTarget(
        target: FocusedPosterTrailerPlaybackTarget
    ) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerPlaybackTargetKey] = target.name
        }
    }

    suspend fun setPosterCardWidthDp(widthDp: Int) {
        store().edit { prefs ->
            prefs[posterCardWidthDpKey] = widthDp
        }
    }

    suspend fun setPosterCardHeightDp(heightDp: Int) {
        store().edit { prefs ->
            prefs[posterCardHeightDpKey] = heightDp
        }
    }

    suspend fun setPosterCardCornerRadiusDp(cornerRadiusDp: Int) {
        store().edit { prefs ->
            prefs[posterCardCornerRadiusDpKey] = cornerRadiusDp
        }
    }

    suspend fun setHomeCarouselCardSize(size: HomeCardSize) {
        store().edit { prefs ->
            prefs[homeCarouselCardSizeKey] = size.name
        }
    }

    suspend fun setHomeMediaRowCardSize(size: HomeCardSize) {
        store().edit { prefs ->
            prefs[homeMediaRowCardSizeKey] = size.name
        }
    }

    suspend fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurUnwatchedEpisodesKey] = enabled
        }
    }

    suspend fun setUseEpisodeThumbnailsInCw(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useEpisodeThumbnailsInCwKey] = enabled
        }
    }

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
        }
    }

    suspend fun setNextUpFromFurthestEpisode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[nextUpFromFurthestEpisodeKey] = enabled
        }
    }

    suspend fun setBlurContinueWatchingNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurContinueWatchingNextUpKey] = enabled
        }
    }

    suspend fun setContinueWatchingThumbnailOrientation(orientation: ThumbnailOrientation) {
        store().edit { prefs ->
            prefs[continueWatchingThumbnailOrientationKey] = orientation.name
            prefs[continueWatchingPortraitModeKey] =
                orientation == ThumbnailOrientation.PORTRAIT
        }
    }

    suspend fun setVideoThumbnailOrientation(orientation: ThumbnailOrientation) {
        store().edit { prefs ->
            prefs[videoThumbnailOrientationKey] = orientation.name
        }
    }

    suspend fun setMovieFavoritesThumbnailOrientation(orientation: ThumbnailOrientation) {
        store().edit { prefs ->
            prefs[movieFavoritesThumbnailOrientationKey] = orientation.name
        }
    }

    suspend fun setSeriesFavoritesThumbnailOrientation(orientation: ThumbnailOrientation) {
        store().edit { prefs ->
            prefs[seriesFavoritesThumbnailOrientationKey] = orientation.name
        }
    }

    suspend fun setContinueWatchingThumbnailSize(size: ThumbnailSize) {
        store().edit { prefs ->
            prefs[continueWatchingThumbnailSizeKey] = size.name
            prefs[continueWatchingThumbnailSizeScaleVersionKey] = THUMBNAIL_SIZE_SCALE_VERSION
        }
    }

        suspend fun setContinueWatchingSortMode(mode: ContinueWatchingSortMode) {
        store().edit { prefs ->
            prefs[continueWatchingSortModeKey] = mode.name
        }
    }

    suspend fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[detailPageTrailerButtonEnabledKey] = enabled
        }
    }

    suspend fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        store().edit { prefs ->
            prefs[preferExternalMetaAddonDetailKey] = enabled
        }
    }

    suspend fun setHideUnreleasedContent(enabled: Boolean) {
        store().edit { prefs ->
            prefs[hideUnreleasedContentKey] = enabled
        }
    }

    suspend fun setShowFullReleaseDate(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showFullReleaseDateKey] = enabled
        }
    }

    private fun parseCatalogKeys(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(json, type).orEmpty()
            normalizeCatalogOrderKeys(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeCatalogOrderKeys(keys: List<String>): List<String> {
        return keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun parseCustomTitles(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun setCustomCatalogTitles(titles: Map<String, String>) {
        store().edit { prefs ->
            val filtered = titles.filterValues { it.isNotBlank() }
            if (filtered.isEmpty()) {
                prefs.remove(customCatalogTitlesKey)
            } else {
                prefs[customCatalogTitlesKey] = gson.toJson(filtered)
            }
        }
    }

    internal suspend fun getHomeCatalogSettingsState(): LocalHomeCatalogSettingsState {
        return readHomeCatalogSettingsState(store().data.first())
    }

    internal suspend fun exportCatalogSettingsToSyncPayload(
        addons: List<Addon>,
        collections: List<Collection>
    ): SyncHomeCatalogPayload {
        return buildHomeCatalogSyncPayload(
            addons = addons,
            collections = collections,
            localState = getHomeCatalogSettingsState()
        )
    }

    suspend fun applyCatalogSettingsFromRemote(payload: SyncHomeCatalogPayload) {
        val sortedItems = payload.items.sortedBy { it.order }
        val orderKeys = sortedItems.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val disabledKeys = sortedItems.filter { !it.enabled }.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val titles = sortedItems.associate { item ->
            val key = if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
            key to item.customTitle
        }.filterValues { it.isNotBlank() }

        store().edit { prefs ->
            if (orderKeys.isNotEmpty()) {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(orderKeys)
            } else {
                prefs.remove(homeCatalogOrderKeysKey)
            }
            if (disabledKeys.isNotEmpty()) {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(disabledKeys)
            } else {
                prefs.remove(disabledHomeCatalogKeysKey)
            }
            if (titles.isNotEmpty()) {
                prefs[customCatalogTitlesKey] = gson.toJson(titles)
            } else {
                prefs.remove(customCatalogTitlesKey)
            }
        }
    }

    private fun readHomeCatalogSettingsState(prefs: Preferences): LocalHomeCatalogSettingsState {
        return LocalHomeCatalogSettingsState(
            orderKeys = parseCatalogKeys(prefs[homeCatalogOrderKeysKey]),
            disabledKeys = parseCatalogKeys(prefs[disabledHomeCatalogKeysKey]).toSet(),
            customTitles = parseCustomTitles(prefs[customCatalogTitlesKey])
        )
    }
}

internal val legacySearchDiscoverEnabledKey = booleanPreferencesKey("search_discover_enabled")
internal val discoverLocationKey = stringPreferencesKey("discover_location")
internal val lastNonOffDiscoverLocationKey = stringPreferencesKey("last_non_off_discover_location")
