package com.obsidian.connect.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickReplyState(
    val text: String = "",
    val fromName: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
)

/**
 * Backs the strip that opens when the unread dot is tapped.
 *
 * Loads once rather than observing. This is a transient surface opened from a
 * home screen — it shows the message that prompted the tap, and if a newer one
 * lands mid-reply, quietly swapping the text underneath the reader would be
 * worse than showing what they came for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QuickReplyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val syncState: SyncState,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickReplyState())
    val state: StateFlow<QuickReplyState> = _state.asStateFlow()

    private var pairingId: String? = null

    init {
        load()
    }

    private fun load() {
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            val user = runCatching { userRepository.get(uid) }.getOrNull() ?: return@launch
            val id = user.pairingId ?: return@launch
            pairingId = id

            val latest = runCatching {
                messageRepository.latestFrom(id, uid)
            }.getOrNull() ?: return@launch

            val name = runCatching { userRepository.get(latest.senderId)?.displayName }
                .getOrNull()
                .orEmpty()

            _state.update { it.copy(text = latest.text, fromName = name) }
            markRead(latest.createdAtMillis)
        }
    }

    fun send(reply: String) {
        val id = pairingId ?: return
        val uid = authRepository.currentUid ?: return
        if (reply.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            messageRepository.send(id, uid, reply)
            _state.update { it.copy(sending = false, sent = true) }
        }
    }

    /**
     * Opening the strip counts as reading it, so the dot clears immediately
     * rather than waiting for the next sync.
     */
    private suspend fun markRead(newestMillis: Long) {
        if (newestMillis > syncState.lastReadMessageAt) {
            syncState.lastReadMessageAt = newestMillis
        }
        WidgetCaptionStore.writeUnread(context, false)
        MomentWidgetUpdater.refresh(context)
    }
}
