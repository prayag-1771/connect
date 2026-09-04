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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /**
     * The pairing, waited for rather than sampled.
     *
     * It arrives from a Firestore read a moment after this screen opens, so
     * reading the current value and giving up when it was still null meant a
     * tap in that first half second did nothing at all - silently, which is the
     * worst way for a button to fail.
     */
    private suspend fun pairing(): String = pairingId.filterNotNull().first()

    fun start() {
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            chatRepository.start(pairing(), uid)
                .onFailure { _problem.value = it.message ?: "Could not open the jam chat." }
        }
    }

    /** Asks the other person to come in, again if need be. */
    fun request() {
        viewModelScope.launch {
            chatRepository.request(pairing())
                .onSuccess { _problem.value = "Asked them to join." }
                .onFailure { _problem.value = it.message ?: "Could not send that." }
        }
    }

    /** Says no, without closing the room they are sitting in. */
    fun decline() {
        viewModelScope.launch { chatRepository.decline(pairing()) }
    }

    fun join() {
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            chatRepository.join(pairing(), uid)
                .onFailure { _problem.value = it.message ?: "Could not join." }
        }
    }

    /** Ends it for both, and deletes everything said in it. */
    fun end() {
        viewModelScope.launch { chatRepository.end(pairing()) }
    }

    /**
     * Why a line stayed a message.
     *
     * The three reasons are genuinely different and want different responses:
     * one needs a key adding, one needs Spotify connecting, and one just means
     * the song was not found.
     */
    private fun whyNot(query: String, spotify: Boolean): String = when {
        spotify && spotifySearch == null ->
            "Connect Spotify first, then typing a song will put it on."

        spotify -> "Could not find \"$query\" on Spotify. Sent as a message."

        !YouTubeSearch.isConfigured ->
            "Searching by name needs a YouTube key. Paste a link and it plays. " +
                "Sent as a message."

        else -> "Could not find \"$query\" on YouTube. Sent as a message."
    }

    fun dismissProblem() {
        _problem.value = null
    }

    /**
     * Sends a line, having first tried to turn it into a track.
     *
     * The search runs before the message is written, so a hit and the message
     * saying so land together rather than the line appearing and changing
     * meaning a moment later.
     */
    fun send(text: String, spotify: Boolean) {
        val uid = authRepository.currentUid ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            val id = pairing()
            val played = if (spotify) {
                playOnSpotify(id, uid, trimmed)
            } else {
                playOnYouTube(id, uid, trimmed)
            }

            chatRepository.send(id, uid, trimmed, played.orEmpty())
                .onFailure { _problem.value = it.message ?: "That did not send." }

            // Said only to whoever typed it. A line that stayed a message looks
            // identical to one that was never meant to be a song, and without
            // this the difference is invisible - which reads as the feature
            // being broken rather than as the search coming up empty.
            if (played == null) _problem.value = whyNot(trimmed, spotify)
        }
    }

    private suspend fun playOnYouTube(pairing: String, uid: String, query: String): String? {
        // A pasted link is unambiguous and should not be searched for.
        extractVideoId(query)?.let { id ->
            // Looked up, so the now-playing line reads as a song rather than
            // as the URL somebody just pasted.
            val name = YouTubeSearch.titleFor(id) ?: query
            jamRepository.load(pairing, uid, id, name, JamSession.YOUTUBE)
            return name
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
