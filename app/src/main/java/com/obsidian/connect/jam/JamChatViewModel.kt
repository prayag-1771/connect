package com.obsidian.connect.jam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.JamChatRepository
import com.obsidian.connect.core.data.JamRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.JamChatMessage
import com.obsidian.connect.core.model.JamChatRoom
import com.obsidian.connect.core.model.JamSession
import dagger.hilt.android.lifecycle.HiltViewModel
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

/**
 * The jam chat: a conversation where most lines are meant to become music.
 *
 * Every message is tried as a song first and kept as a message only when
 * nothing matches. That ordering is the whole feature - it means you never have
 * to say which you meant, and typing a title is the same gesture as asking for
 * it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class JamChatViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val chatRepository: JamChatRepository,
    private val jamRepository: JamRepository,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val room: StateFlow<JamChatRoom?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatRepository.observeRoom(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val messages: StateFlow<List<JamChatMessage>> = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun start() {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch { chatRepository.start(id, uid) }
    }

    fun join() {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch { chatRepository.join(id, uid) }
    }

    /** Ends it for both, and deletes everything said in it. */
    fun end() {
        val id = pairingId.value ?: return
        viewModelScope.launch { chatRepository.end(id) }
    }

    /**
     * Sends a line, having first tried to turn it into a track.
     *
     * The search runs before the message is written, so a hit and the message
     * saying so land together rather than the line appearing and changing
     * meaning a moment later.
     */
    fun send(text: String, spotify: Boolean) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            val played = if (spotify) playOnSpotify(id, uid, trimmed) else playOnYouTube(id, uid, trimmed)
            chatRepository.send(id, uid, trimmed, played.orEmpty())
        }
    }

    private suspend fun playOnYouTube(pairing: String, uid: String, query: String): String? {
        // A pasted link is unambiguous and should not be searched for.
        extractVideoId(query)?.let { id ->
            jamRepository.load(pairing, uid, id, query, JamSession.YOUTUBE)
            return query
        }

        val hit = YouTubeSearch.best(query) ?: return null
        jamRepository.load(pairing, uid, hit.videoId, hit.title, JamSession.YOUTUBE)
        return hit.title
    }

    private suspend fun playOnSpotify(pairing: String, uid: String, query: String): String? {
        // Search does not need Premium, so this half works on a free account
        // even though playing the result does not.
        val hit = spotifySearch?.invoke(query) ?: return null
        jamRepository.load(pairing, uid, hit.first, hit.second, JamSession.SPOTIFY)
        return hit.second
    }

    /**
     * Supplied by the screen, which has the context the Spotify API needs.
     *
     * Injecting a context into a view model to reach a token store would be the
     * wrong shape; handing the lookup in is smaller and keeps the Android
     * dependency where it already is.
     */
    var spotifySearch: (suspend (String) -> Pair<String, String>?)? = null

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
