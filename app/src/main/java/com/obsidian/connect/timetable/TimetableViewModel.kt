package com.obsidian.connect.timetable

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.camera.ImageCompressor
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.TimetableRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Timetable
import com.obsidian.connect.core.model.TimetableEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimetableViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val timetableRepository: TimetableRepository,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val partnerId: StateFlow<String?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mine: StateFlow<Timetable?> = pairingId
        .flatMapLatest { id ->
            val uid = authRepository.currentUid
            if (id == null || uid == null) flowOf(null) else timetableRepository.observe(id, uid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /**
     * Their week.
     *
     * Both ids are combined rather than one being read imperatively out of the
     * other. It happened to work, because the partner is derived from the
     * pairing and so always arrives second - but a flow that is correct only
     * because of the order two other flows happen to emit in is one bad day
     * from showing an empty timetable and no reason why.
     */
    val theirs: StateFlow<Timetable?> = combine(pairingId, partnerId) { id, partner ->
        id to partner
    }
        .flatMapLatest { (id, partner) ->
            if (id == null || partner == null) {
                flowOf(null)
            } else {
                timetableRepository.observe(id, partner)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val partnerName: StateFlow<String> = partnerId
        .flatMapLatest { id -> if (id == null) flowOf(null) else userRepository.observe(id) }
        .map { it?.displayName.orEmpty().ifBlank { "Them" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "Them")

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * Reads a photograph and replaces this person's timetable with what it
     * found.
     *
     * Replaces rather than merges. A new photograph is a new timetable - if a
     * term has changed, keeping last term's entries alongside would leave two
     * weeks stacked on top of each other with no way to tell them apart.
     */
    fun readFrom(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            _busy.value = true

            // Read one at a time and gathered, because a timetable is often
            // photographed in halves - a morning page and an afternoon one -
            // and reading them separately would mean the second replacing the
            // first rather than completing it.
            val gathered = mutableListOf<TimetableEntry>()
            var failed = 0

            uris.forEachIndexed { index, uri ->
                _status.value = if (uris.size == 1) {
                    "Reading the image..."
                } else {
                    "Reading image ${index + 1} of ${uris.size}..."
                }

                val jpeg = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri).use { it?.readBytes() }
                            ?.let {
                                ImageCompressor.compress(
                                    source = it,
                                    longEdge = ImageCompressor.DETAIL_LONG_EDGE,
                                )
                            }
                    }.getOrNull()
                }

                if (jpeg == null) {
                    failed++
                    return@forEachIndexed
                }

                TimetableReader.read(jpeg)
                    .onSuccess { gathered += it }
                    .onFailure { failed++ }
            }

            // Duplicates are expected when two photos overlap, and a timetable
            // with the same lecture twice reads as a mistake.
            val entries = gathered.distinctBy {
                listOf(it.day, it.start, it.title.lowercase())
            }

            _status.value = when {
                entries.isEmpty() && failed > 0 -> "Could not read those images."
                entries.isEmpty() -> "Nothing that looked like a timetable was in there."
                else -> {
                    timetableRepository.save(id, uid, entries)
                    val note = if (failed > 0) " ($failed image(s) could not be read)" else ""
                    "Read ${entries.size} slots from ${uris.size} image(s)$note."
                }
            }

            _busy.value = false
        }
    }

    /**
     * Adds or replaces one slot.
     *
     * The whole list is written back rather than the one row, because the
     * entries live inside a single document - there is no smaller thing to
     * update. Matched on id, so a slot that shares a day and a time with
     * another is still edited individually.
     */
    fun save(entry: TimetableEntry) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        val existing = mine.value?.entries.orEmpty()
        val withId = if (entry.id.isBlank()) {
            entry.copy(id = java.util.UUID.randomUUID().toString())
        } else {
            entry
        }

        val updated = if (existing.any { it.id == withId.id }) {
            existing.map { if (it.id == withId.id) withId else it }
        } else {
            existing + withId
        }

        viewModelScope.launch { timetableRepository.save(id, uid, updated) }
    }

    fun remove(entry: TimetableEntry) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        val remaining = mine.value?.entries.orEmpty().filterNot { it.id == entry.id }
        viewModelScope.launch { timetableRepository.save(id, uid, remaining) }
    }

    fun clearMine() {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        viewModelScope.launch {
            timetableRepository.clear(id, uid)
            _status.value = null
        }
    }

    fun dismissStatus() {
        _status.value = null
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
