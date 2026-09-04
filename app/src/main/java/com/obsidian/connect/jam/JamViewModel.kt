package com.obsidian.connect.jam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.JamRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.JamSession
import com.obsidian.connect.core.model.QueueItem
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
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val typed = linkOrId.trim()
        if (typed.isEmpty()) return

        _problem.value = null

        viewModelScope.launch {
            // A link if it is one, a search if it is not. Typing a song name is
            // faster than finding the link for it, and the search already picks
            // a version the player will accept - so there is no reason to make
            // this screen the only place that still demands a URL.
            val id = extractVideoId(typed)
            if (id != null) {
                val name = title.ifBlank { YouTubeSearch.titleFor(id).orEmpty() }
                jamRepository.load(pairing, uid, id, name, JamSession.YOUTUBE)
                return@launch
            }

            if (!YouTubeSearch.isConfigured) {
                _problem.value = "Paste a YouTube link, or add a key to search by name."
                return@launch
            }

            val hit = YouTubeSearch.best(typed)
            if (hit == null) {
                _problem.value = "Could not find \"$typed\" on YouTube."
                return@launch
            }

            jamRepository.load(pairing, uid, hit.videoId, hit.title, JamSession.YOUTUBE)
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

    /**
     * Adds a song to the queue by name or link.
     *
     * Searched here rather than when its turn comes, so a track that cannot be
     * found is reported while somebody is still looking at the screen instead
     * of failing silently in twenty minutes.
     */
    fun enqueue(linkOrId: String) {
        val pairing = pairingId.value ?: return
        val typed = linkOrId.trim()
        if (typed.isEmpty()) return

        _problem.value = null

        viewModelScope.launch {
            val item = resolve(typed)
            if (item == null) {
                _problem.value = "Could not find \"$typed\"."
                return@launch
            }
            jamRepository.enqueue(pairing, item)
        }
    }

    /**
     * Moves one track up or down the queue.
     *
     * Buttons rather than a drag. The queue lives in a scrolling column of
     * mixed content rather than a list, so there is no layout to measure a drag
     * against - and for a handful of songs, two taps that always land beat a
     * gesture that sometimes does.
     */
    fun moveInQueue(item: QueueItem, up: Boolean) {
        val pairing = pairingId.value ?: return
        val queue = session.value?.queue.orEmpty()

        val index = queue.indexOf(item)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in queue.indices) return

        val reordered = queue.toMutableList().apply {
            set(index, set(target, item))
        }

        viewModelScope.launch { jamRepository.reorderQueue(pairing, reordered) }
    }

    fun removeFromQueue(item: QueueItem) {
        val pairing = pairingId.value ?: return
        viewModelScope.launch { jamRepository.dequeue(pairing, item) }
    }

    /**
     * Plays whatever is next.
     *
     * Called when a track ends. With an empty queue it finds something rather
     * than stopping - and never the same song twice in a session, which is what
     * the played list is for.
     */
    fun playNext() {
        val pairing = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val current = session.value ?: return

        viewModelScope.launch {
            val played = current.playedIds + current.videoId

            val next = current.queue.firstOrNull()
                ?: findSomethingNew(current.title, played)
                ?: return@launch

            jamRepository.advance(
                pairingId = pairing,
                uid = uid,
                next = next,
                remainingQueue = current.queue.drop(1),
                played = played,
            )
        }
    }

    /**
     * Something to follow on with when the queue is empty.
     *
     * Searched on the title that just finished, which keeps the next track in
     * roughly the same territory, and filtered against what has already played
     * so a two-song session does not become one song twice.
     */
    private suspend fun findSomethingNew(
        after: String,
        played: List<String>,
    ): QueueItem? {
        if (!YouTubeSearch.isConfigured || after.isBlank()) return null
        val hits = YouTubeSearch.similar(after, exclude = played)
        return hits.firstOrNull()?.let { QueueItem(it.videoId, it.title) }
    }

    private suspend fun resolve(typed: String): QueueItem? {
        extractVideoId(typed)?.let { id ->
            return QueueItem(id, YouTubeSearch.titleFor(id) ?: typed)
        }
        val hit = YouTubeSearch.best(typed) ?: return null
        return QueueItem(hit.videoId, hit.title)
    }

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
