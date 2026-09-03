package com.obsidian.connect.jam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.JamRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.JamSession
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class JamViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val jamRepository: JamRepository,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val session: StateFlow<JamSession?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else jamRepository.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /**
     * Puts a track on for both of you.
     *
     * Accepts a link or a bare id, because the useful thing to do with a song
     * you want to share is paste it, and every YouTube link on a phone is one
     * of about four shapes.
     */
    fun load(linkOrId: String, title: String = "") {
        val id = extractVideoId(linkOrId)
        if (id == null) {
            _problem.value = "That does not look like a YouTube link."
            return
        }

        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        _problem.value = null

        viewModelScope.launch {
            jamRepository.load(pairing, uid, id, title, JamSession.YOUTUBE)
        }
    }

    /** Puts a Spotify track on for both of you. */
    fun loadSpotify(trackUri: String, title: String) {
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        _problem.value = null

        viewModelScope.launch {
            jamRepository.load(pairing, uid, trackUri, title, JamSession.SPOTIFY)
        }
    }

    fun showProblem(problem: String?) {
        _problem.value = problem
    }

    /**
     * Records what this phone just did, so the other one can follow.
     *
     * The position is where the track is *now*. The other side adds the
     * transit delay itself - sending a guess at where it will be by the time
     * the write lands would be guessing at a number the receiver already knows
     * more accurately.
     */
    fun report(playing: Boolean, positionMs: Long) {
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            jamRepository.update(pairing, uid, playing, positionMs)
        }
    }

    fun join() {
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch { jamRepository.join(pairing, uid) }
    }

    fun leave() {
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch { jamRepository.leave(pairing, uid) }
    }

    /** Whether the other person has the jam open too. */
    fun theyAreHere(session: JamSession?): Boolean {
        val me = authRepository.currentUid ?: return false
        return session?.listeners.orEmpty().any { it != me }
    }

    /**
     * Whether this phone is the one currently driving.
     *
     * Only the driver refreshes the position, so two phones do not both write
     * a heartbeat for the same track.
     */
    fun isDriver(session: JamSession?): Boolean =
        session?.byUid == authRepository.currentUid

    fun end() {
        val pairing = pairingId.value ?: return
        viewModelScope.launch { jamRepository.end(pairing) }
    }

    /**
     * Takes this person out, and tears the jam down if that was the last of
     * them.
     *
     * Leaving is per-person because the other one may still be listening -
     * ending it for both because you stopped would be taking their music away.
     */
    fun leaveJam() {
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            jamRepository.leave(pairing, uid)
            val remaining = session.value?.listeners.orEmpty().filterNot { it == uid }
            if (remaining.isEmpty()) jamRepository.end(pairing)
        }
    }

    /** True when this update came from the other phone and should be obeyed. */
    fun isTheirs(session: JamSession): Boolean =
        session.byUid.isNotBlank() && session.byUid != authRepository.currentUid

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/**
 * Pulls the id out of whatever was pasted.
 *
 * youtu.be links, watch?v= links, /embed/ links, music.youtube.com links and a
 * bare id all turn up in practice, usually with tracking parameters glued on
 * the end.
 */
fun extractVideoId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // A bare id: eleven characters of the YouTube alphabet and nothing else.
    if (trimmed.matches(Regex("[A-Za-z0-9_-]{11}"))) return trimmed

    val patterns = listOf(
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""/embed/([A-Za-z0-9_-]{11})"""),
        Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
    )

    return patterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.getOrNull(1) }
}
