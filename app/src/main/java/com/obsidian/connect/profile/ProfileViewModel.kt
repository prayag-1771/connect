package com.obsidian.connect.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.ui.theme.ChatTheme
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.widget.WidgetCaptionStore
import com.obsidian.connect.widget.WidgetImageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileState(
    val busy: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val syncState: SyncState,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val pairingIdFlow = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()

    /** The conversation's palette, shared with the other person. */
    val chatTheme: StateFlow<ChatTheme> = pairingIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { ChatTheme.from(it?.chatTheme.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ChatTheme.Default)

    val paired: StateFlow<Boolean> = pairingIdFlow
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

    /** Repaints the chat for both of you. */
    fun setChatTheme(theme: ChatTheme) {
        viewModelScope.launch {
            val id = pairingIdFlow.first() ?: return@launch
            pairingRepository.setChatTheme(id, theme.name)
        }
    }

    val myName: StateFlow<String> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.displayName.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "")

    val myEmail: String? get() = authRepository.currentEmail

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val partnerName: StateFlow<String> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .flatMapLatest { partner ->
            if (partner == null) flowOf(null) else userRepository.observe(partner)
        }
        .map { it?.displayName.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "")

    fun leavePairing() {
        val uid = authRepository.currentUid ?: return
        val id = pairingId.value ?: return

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            pairingRepository.leave(uid, id)
                .onSuccess {
                    clearLocalTraces()
                    _state.update { it.copy(busy = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = e.message ?: "Couldn't do that") }
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            clearLocalTraces()
            authRepository.signOut()
        }
    }

    /**
     * Wipes what this device cached about the other person.
     *
     * The widget photo lives on disk and survives sign-out on its own, so
     * without this their face stays on the home screen of a phone that is no
     * longer signed in to anything.
     */
    private suspend fun clearLocalTraces() {
        syncState.clear()
        WidgetImageStore.clear(context)
        WidgetCaptionStore.clear(context)
        MomentWidgetUpdater.refresh(context)
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
