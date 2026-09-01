package com.obsidian.connect.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class AuthState(
    val busy: Boolean = false,
    val signedIn: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState(signedIn = authRepository.currentUid != null))
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        // Covers the case where the token rotated while the app was closed and
        // onNewToken fired with nobody signed in to attach it to.
        if (authRepository.currentUid != null) syncFcmToken()
    }

    fun signIn(email: String, password: String) = run(
        action = { authRepository.signIn(email, password) },
    )

    fun signUp(email: String, password: String, displayName: String) = run(
        action = {
            authRepository.signUp(email, password).onSuccess { uid ->
                userRepository.createProfile(uid, displayName.trim())
            }
        },
    )

    fun signOut() {
        authRepository.signOut()
        _state.value = AuthState(signedIn = false)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun run(action: suspend () -> Result<String>) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            action()
                .onSuccess {
                    syncFcmToken()
                    _state.update { s -> s.copy(busy = false, signedIn = true) }
                }
                .onFailure { error ->
                    _state.update { s -> s.copy(busy = false, error = error.readable()) }
                }
        }
    }

    /**
     * Writes this device's push token against the signed-in account.
     *
     * Without it the partner's Cloud Function has nowhere to send to, and the
     * failure is invisible: photos upload fine, the sender sees success, and
     * the widget on the other side simply never changes.
     */
    private fun syncFcmToken() {
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                userRepository.updateFcmToken(uid, token)
            }
        }
    }
}

private fun Throwable.readable(): String = when {
    message?.contains("password is invalid", ignoreCase = true) == true ->
        "That password doesn't match"
    message?.contains("no user record", ignoreCase = true) == true ->
        "No account with that email"
    message?.contains("email address is already in use", ignoreCase = true) == true ->
        "That email already has an account"
    message?.contains("network", ignoreCase = true) == true ->
        "Couldn't reach the network"
    else -> message ?: "Something went wrong"
}
