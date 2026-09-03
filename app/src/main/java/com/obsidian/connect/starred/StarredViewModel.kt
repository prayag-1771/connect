package com.obsidian.connect.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StarredViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    pairingRepository: PairingRepository,
    messageRepository: MessageRepository,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val pairingId = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()

    private val pairing = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else pairingRepository.observe(id).map { id to it }
        }

    /**
     * Everything either of you has kept, newest first.
     *
     * Both uids go into the query because a star is shared - something one of
     * you thought worth keeping is kept for both, which is the whole point of
     * a list two people can look at.
     */
    val starred: StateFlow<List<Message>> = pairing
        .flatMapLatest { current ->
            val id = current?.first
            val members = current?.second?.members.orEmpty()
            if (id == null || members.isEmpty()) {
                flowOf(emptyList())
            } else {
                messageRepository.observeStarred(id, members)
            }
        }
        .map { list -> list.sortedByDescending { it.createdAtMillis } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** Their name, so a kept message can say whose words they were. */
    val partnerName: StateFlow<String> = pairing
        .map { it?.second?.partnerOf(authRepository.currentUid.orEmpty()) }
        .distinctUntilChanged()
        .flatMapLatest { partner ->
            if (partner == null) flowOf(null) else userRepository.observe(partner)
        }
        .map { it?.displayName.orEmpty().ifBlank { "Them" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "Them")

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
