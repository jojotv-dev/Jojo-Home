package com.nuvio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nasSyncSessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "nas_sync_session")

data class NasSyncSession(val token: String?, val email: String?, val baseUrl: String) {
    override fun toString() = "NasSyncSession(authenticated=${!token.isNullOrBlank()})"
}

@Singleton
class NasSyncSessionDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tokenKey = stringPreferencesKey("token")
    private val emailKey = stringPreferencesKey("email")
    private val home = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)

    val baseUrl: String
        get() = home.getString("nas_url", null)?.trim()?.trimEnd('/')
            ?.takeIf(String::isNotEmpty) ?: com.jojotv.home.BuildConfig.JOJOTV_NAS_SYNC_URL.trimEnd('/')

    private fun homeSession() = NasSyncSession(
        home.getString("nas_token", null)?.takeIf(String::isNotBlank),
        home.getString("nas_email", null)?.takeIf(String::isNotBlank),
        baseUrl
    )

    private val homeSessionFlow = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf("nas_token", "nas_email", "nas_url")) {
                trySend(home.contains("nas_token") to homeSession())
            }
        }
        home.registerOnSharedPreferenceChangeListener(listener)
        trySend(home.contains("nas_token") to homeSession())
        awaitClose { home.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    // Home owns the session whenever it has a token key, including an empty
    // token after logout. Existing player-only sessions remain readable.
    val sessionFlow: Flow<NasSyncSession> = combine(
        homeSessionFlow, context.nasSyncSessionDataStore.data
    ) { (ownedByHome, session), legacy ->
        if (ownedByHome) session else NasSyncSession(
            legacy[tokenKey]?.takeIf(String::isNotBlank),
            legacy[emailKey]?.takeIf(String::isNotBlank),
            session.baseUrl
        )
    }.distinctUntilChanged()

    val tokenFlow: Flow<String?> = sessionFlow.map { it.token }.distinctUntilChanged()
    val emailFlow: Flow<String?> = sessionFlow.map { it.email }.distinctUntilChanged()

    suspend fun token(): String? = if (home.contains("nas_token")) homeSession().token
        else context.nasSyncSessionDataStore.data.first()[tokenKey]?.takeIf(String::isNotBlank)

    suspend fun saveSession(token: String, email: String) {
        home.edit().putString("nas_token", token).putString("nas_email", email).apply()
    }

    suspend fun clearIfCurrent(authorization: String?, requestBaseUrl: String) {
        if (authorization == null || requestBaseUrl != baseUrl || authorization != "Bearer ${token()}") return
        clear()
    }

    suspend fun clear() {
        home.edit().putString("nas_token", "").putString("nas_email", "").apply()
        context.nasSyncSessionDataStore.edit {
            it.remove(tokenKey)
            it.remove(emailKey)
        }
    }
}
