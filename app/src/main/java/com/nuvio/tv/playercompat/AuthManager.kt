package com.nuvio.tv.core.auth

import com.nuvio.tv.core.sync.NasIptvFavoritesApi
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compatibility façade for account-aware callers.
 *
 * JojoTV accounts are NAS accounts: the NAS session is the sole identity and
 * ownership token. Legacy callers can keep observing [authState] while the
 * No third-party authentication backend is used.
 */
@Singleton
class AuthManager @Inject constructor(
    private val nasApi: NasIptvFavoritesApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        scope.launch {
            combine(nasApi.accountEmailFlow, nasApi.sessionTokenFlow) { email, token ->
                email?.trim().takeIf { !it.isNullOrBlank() } to (token?.isNotBlank() == true)
            }.collect { (email, hasToken) ->
                _authState.value = if (hasToken && !email.isNullOrBlank()) {
                    // The e-mail is stable within a NAS account and replaces the
                    // former opaque account ID for legacy UI consumers.
                    AuthState.FullAccount(userId = email.lowercase(), email = email)
                } else {
                    AuthState.SignedOut
                }
            }
        }
    }

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.FullAccount

    val currentUserId: String?
        get() = (_authState.value as? AuthState.FullAccount)?.userId

    val currentUserEmail: String?
        get() = (_authState.value as? AuthState.FullAccount)?.email

    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String? = currentUserId

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        nasApi.register(email, password)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        nasApi.login(email, password)
    }

    suspend fun signOut(explicit: Boolean = true) {
        nasApi.signOut()
    }

    fun clearEffectiveUserIdCache() = Unit

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean = false

}
