package com.obsidian.connect.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Pairing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairingUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    /**
     * Follows the signed-in user's pairing as it changes.
     *
     * Two chained flatMapLatest calls: the uid can change on sign-out, and the
     * pairingId appears on the user document the moment a pairing is created.
     * Re-subscribing rather than reading once is what makes the invite screen
     * advance on its own when the other person joins, with no polling.
     */
    val pairing: StateFlow<Pairing?> = authRepository.uidFlow
        .flatMapLatest { uid ->
            if (uid == null) flowOf(null) else userRepository.observe(uid)
        }
        .map { it?.pairingId }
        .flatMapLatest { pairingId ->
            if (pairingId == null) flowOf(null) else pairingRepository.observe(pairingId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    /** Emits the partner's uid once someone has joined. */
    val partnerId: StateFlow<String?> = pairing
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun createInvite() {
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            pairingRepository.createInvite(uid)
                .onSuccess { _uiState.update { it.copy(busy = false) } }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "Couldn't create an invite") }
                }
        }
    }

    fun join(code: String) {
        val uid = authRepository.currentUid ?: return
        if (code.isBlank()) {
            _uiState.update { it.copy(error = "Enter the code they sent you") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            pairingRepository.join(uid, code)
                .onSuccess { _uiState.update { it.copy(busy = false) } }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "Couldn't join") }
                }
        }
    }

    fun cancelInvite() {
        val uid = authRepository.currentUid ?: return
        val pairingId = pairing.value?.id ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            pairingRepository.cancelInvite(uid, pairingId)
                .onSuccess { _uiState.update { it.copy(busy = false) } }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "Couldn't cancel that") }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private companion object {
        /**
         * Keeps Firestore listeners alive briefly across a rotation. Tearing
         * them down and rebuilding them costs a fresh round of document reads.
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
