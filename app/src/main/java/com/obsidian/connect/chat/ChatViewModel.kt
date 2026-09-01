package com.obsidian.connect.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.sync.SyncState
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val syncState: SyncState,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val messages: StateFlow<List<Message>> = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else messageRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun send(text: String) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (text.isBlank()) return

        viewModelScope.launch { messageRepository.send(id, uid, text) }
    }

    /**
     * Marks the conversation seen and clears the watch face's dot.
     *
     * Called when the chat is actually on screen rather than when the app
     * opens, so opening the camera tab does not silently mark unread messages
     * as read.
     */
    fun markRead() {
        val newest = messages.value.maxOfOrNull { it.createdAtMillis } ?: return
        if (newest <= syncState.lastReadMessageAt) return

        syncState.lastReadMessageAt = newest
        viewModelScope.launch {
            WidgetCaptionStore.writeUnread(context, false)
            MomentWidgetUpdater.refresh(context)
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
