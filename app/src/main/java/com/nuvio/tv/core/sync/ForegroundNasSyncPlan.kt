package com.nuvio.tv.core.sync

internal const val NAS_BLOB_LIBRARY = "library"
internal const val NAS_BLOB_COLLECTIONS = "collections"
internal const val NAS_BLOB_HOME_CATALOG = "home_catalog"
internal const val NAS_BLOB_ADDONS = "addons"
internal const val NAS_BLOB_PLUGINS = "plugins"
internal const val NAS_BLOB_AVATAR_CATALOG = "avatar_catalog"

internal val ALL_FOREGROUND_NAS_BLOBS = linkedSetOf(
    NAS_BLOB_LIBRARY,
    NAS_BLOB_COLLECTIONS,
    NAS_BLOB_HOME_CATALOG,
    NAS_BLOB_ADDONS,
    NAS_BLOB_PLUGINS,
    NAS_BLOB_AVATAR_CATALOG
)

internal data class ForegroundNasSyncPlan(
    val foreignDomains: Set<String>,
    val pullWatchProgress: Boolean,
    val pullIptvFavorites: Boolean,
    val pullWatchedItems: Boolean,
    val pullProfiles: Boolean,
    val pullProfileSettings: Boolean,
    val profileBlobKeys: Set<String>
)

/**
 * Converts the NAS change journal into the smallest safe pull plan.
 * A missing/gapped local revision deliberately restores every domain.
 */
internal fun buildForegroundNasSyncPlan(
    knownRevision: Long,
    changes: NasSyncChanges,
    localDeviceId: String
): ForegroundNasSyncPlan {
    val revisionGap = knownRevision == 0L ||
        (knownRevision > 0L && knownRevision < changes.oldestAvailableRevision - 1L)
    val foreignChanges = changes.changes.filter {
        it.deviceId.isBlank() || it.deviceId != localDeviceId
    }
    val foreignDomains = foreignChanges.mapTo(linkedSetOf()) { it.domain }
    val unknownDomain = foreignDomains.any {
        it !in setOf(
            "watch_progress",
            "iptv_favorites",
            "watched_items",
            "profiles",
            "profile_settings",
            "profile_blobs"
        )
    }
    val changedBlobKeys = foreignChanges
        .asSequence()
        .filter { it.domain == "profile_blobs" }
        .flatMap { it.keys.asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(linkedSetOf())
    val hasUnknownBlob = changedBlobKeys.any { it !in ALL_FOREGROUND_NAS_BLOBS }
    val restoreAll = revisionGap || unknownDomain || hasUnknownBlob

    return ForegroundNasSyncPlan(
        foreignDomains = foreignDomains,
        pullWatchProgress = restoreAll || "watch_progress" in foreignDomains,
        pullIptvFavorites = restoreAll || "iptv_favorites" in foreignDomains,
        pullWatchedItems = restoreAll || "watched_items" in foreignDomains,
        pullProfiles = restoreAll || "profiles" in foreignDomains,
        pullProfileSettings = restoreAll || "profile_settings" in foreignDomains,
        profileBlobKeys = if (restoreAll) ALL_FOREGROUND_NAS_BLOBS else changedBlobKeys
    )
}
