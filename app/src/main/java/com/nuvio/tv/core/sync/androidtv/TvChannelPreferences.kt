package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvChannelDataStore by preferencesDataStore(name = "tv_channel_prefs")

@Singleton
class TvChannelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val channelIdKey = longPreferencesKey("continue_watching_channel_id")
    private fun channelIdKey(profileId: Int) =
        longPreferencesKey("continue_watching_channel_id_profile_$profileId")

    suspend fun getChannelId(): Long? =
        context.tvChannelDataStore.data.map { it[channelIdKey] }.first()

    suspend fun setChannelId(id: Long) {
        context.tvChannelDataStore.edit { it[channelIdKey] = id }
    }

    suspend fun clearChannelId() {
        context.tvChannelDataStore.edit { it.remove(channelIdKey) }
    }

    /**
     * Each JojoTV profile owns its Android TV Home channel. Profile 1 reuses the
     * old single-channel entry once, so an existing launcher subscription survives
     * the migration.
     */
    suspend fun getChannelId(profileId: Int): Long? =
        context.tvChannelDataStore.data.map { preferences ->
            preferences[channelIdKey(profileId)]
                ?: if (profileId == 1) preferences[channelIdKey] else null
        }.first()

    suspend fun setChannelId(profileId: Int, id: Long) {
        context.tvChannelDataStore.edit { preferences ->
            preferences[channelIdKey(profileId)] = id
            if (profileId == 1) preferences.remove(channelIdKey)
        }
    }

    suspend fun clearChannelId(profileId: Int) {
        context.tvChannelDataStore.edit { preferences ->
            preferences.remove(channelIdKey(profileId))
            if (profileId == 1) preferences.remove(channelIdKey)
        }
    }

    suspend fun getAllChannelIds(): Set<Long> =
        context.tvChannelDataStore.data.map { preferences ->
            preferences.asMap()
                .filter { (key, value) ->
                    (key.name == "continue_watching_channel_id" ||
                        key.name.startsWith("continue_watching_channel_id_profile_")) && value is Long
                }
                .values
                .filterIsInstance<Long>()
                .toSet()
        }.first()
}
