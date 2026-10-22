package com.nuvio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class NasSyncDiagnostics(
    val revision: Long,
    val pendingProgress: Int,
    val favoritesPending: Boolean,
    val lastSuccessAt: Long,
    val lastError: String?
)

/**
 * Durable client-side state for NAS synchronization.
 *
 * Progress rows themselves remain in [WatchProgressPreferences] and IPTV
 * favorites remain in Room.  This store only records the operations that the
 * NAS has not acknowledged yet, so a process kill or a temporary NAS outage
 * cannot silently lose a mutation.
 */
@Singleton
class NasSyncStateStore internal constructor(
    private val preferences: SharedPreferences
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("nas_sync_state_v2", Context.MODE_PRIVATE))
    private val lock = Any()

    val deviceId: String
        get() = synchronized(lock) {
            preferences.getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString().also { value ->
                    preferences.edit().putString(KEY_DEVICE_ID, value).commit()
                }
        }

    fun bindAccount(accountKey: String) = synchronized(lock) {
        val normalized = accountKey.trim().lowercase()
        if (normalized.isEmpty() || preferences.getString(KEY_ACCOUNT, "") == normalized) return@synchronized
        val stableDeviceId = deviceId
        preferences.edit().clear()
            .putString(KEY_DEVICE_ID, stableDeviceId)
            .putString(KEY_ACCOUNT, normalized)
            .commit()
    }

    fun enqueueProgressUpsert(profileId: Int, key: String) = synchronized(lock) {
        val normalized = key.trim()
        if (normalized.isEmpty()) return@synchronized
        val preferenceKey = progressKey(profileId)
        val pending = preferences.getStringSet(preferenceKey, emptySet()).orEmpty().toMutableSet()
        pending += normalized
        val generation = preferences.getLong("progress_generation", 0L) + 1L
        preferences.edit().putStringSet(preferenceKey, pending)
            .putLong("progress_generation", generation)
            .putLong("${preferenceKey}:$normalized", generation).commit()
    }

    fun pendingProgressUpserts(profileId: Int): Set<String> = synchronized(lock) {
        preferences.getStringSet(progressKey(profileId), emptySet()).orEmpty().toSet()
    }

    fun pendingProgressVersions(profileId: Int): Map<String, Long> = synchronized(lock) {
        pendingProgressUpserts(profileId).associateWith {
            preferences.getLong("${progressKey(profileId)}:$it", 0L)
        }
    }

    /** An acknowledgement may only clear the generation actually sent. */
    fun acknowledgeProgressVersions(profileId: Int, sent: Map<String, Long>) = synchronized(lock) {
        val current = pendingProgressVersions(profileId)
        acknowledgeProgressUpserts(profileId, sent.filter { (key, version) -> current[key] == version }.keys)
    }

    fun acknowledgeProgressUpserts(profileId: Int, keys: Collection<String>) = synchronized(lock) {
        if (keys.isEmpty()) return@synchronized
        val preferenceKey = progressKey(profileId)
        val pending = preferences.getStringSet(preferenceKey, emptySet()).orEmpty().toMutableSet()
        pending.removeAll(keys.toSet())
        val editor = preferences.edit().putStringSet(preferenceKey, pending)
        keys.forEach { editor.remove("$preferenceKey:$it") }
        editor.commit()
    }

    fun markFavoritesDirty(profileId: Int) = synchronized(lock) {
        preferences.edit().putBoolean(favoritesDirtyKey(profileId), true).commit()
    }

    fun clearFavoritesDirty(profileId: Int) = synchronized(lock) {
        preferences.edit().putBoolean(favoritesDirtyKey(profileId), false).commit()
    }

    fun favoritesAreDirty(profileId: Int): Boolean = synchronized(lock) {
        preferences.getBoolean(favoritesDirtyKey(profileId), false)
    }

    fun migrationCompleted(profileId: Int, domain: String = "watch_progress"): Boolean = synchronized(lock) {
        preferences.getBoolean(migrationKey(profileId, domain), false)
    }

    fun legacyProgressImportedAt(profileId: Int, key: String): Long = synchronized(lock) {
        preferences.getLong("legacy_progress_p$profileId:$key", 0L)
    }

    fun markLegacyProgressImported(profileId: Int, key: String, timestamp: Long) = synchronized(lock) {
        preferences.edit().putLong("legacy_progress_p$profileId:$key", timestamp).commit()
    }

    fun markMigrationCompleted(profileId: Int, domain: String = "watch_progress") = synchronized(lock) {
        preferences.edit().putBoolean(migrationKey(profileId, domain), true).commit()
    }

    fun revision(profileId: Int): Long = synchronized(lock) {
        preferences.getLong(revisionKey(profileId), 0L)
    }

    fun setRevision(profileId: Int, revision: Long) = synchronized(lock) {
        if (revision <= preferences.getLong(revisionKey(profileId), 0L)) return@synchronized
        preferences.edit().putLong(revisionKey(profileId), revision).commit()
    }

    fun recordSuccess(profileId: Int, domain: String, revision: Long? = null) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
            .putLong("last_success_${domain}_p$profileId", now)
            .remove("last_error_${domain}_p$profileId")
        if (revision != null) editor.putLong(revisionKey(profileId), revision)
        editor.commit()
    }

    fun recordFailure(profileId: Int, domain: String, error: Throwable) = synchronized(lock) {
        preferences.edit()
            .putLong("last_failure_${domain}_p$profileId", System.currentTimeMillis())
            .putString("last_error_${domain}_p$profileId", error.message.orEmpty().take(240))
            .commit()
    }

    fun diagnostics(profileId: Int): NasSyncDiagnostics = synchronized(lock) {
        val successKeys = listOf("changes", "watch_progress", "iptv_favorites")
        val lastSuccess = successKeys.maxOf { preferences.getLong("last_success_${it}_p$profileId", 0L) }
        val lastError = successKeys.firstNotNullOfOrNull { domain ->
            preferences.getString("last_error_${domain}_p$profileId", null)?.takeIf(String::isNotBlank)
        }
        NasSyncDiagnostics(
            revision = preferences.getLong(revisionKey(profileId), 0L),
            pendingProgress = preferences.getStringSet(progressKey(profileId), emptySet()).orEmpty().size,
            favoritesPending = preferences.getBoolean(favoritesDirtyKey(profileId), false),
            lastSuccessAt = lastSuccess,
            lastError = lastError
        )
    }

    private fun progressKey(profileId: Int) = "pending_progress_upserts_p$profileId"
    private fun favoritesDirtyKey(profileId: Int) = "pending_favorites_p$profileId"
    private fun migrationKey(profileId: Int, domain: String) = "local_seed_v2_${domain}_completed_p$profileId"
    private fun revisionKey(profileId: Int) = "server_revision_p$profileId"

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACCOUNT = "account"
    }
}
