package com.obsidian.connect.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.ReminderRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Reminder
import com.obsidian.connect.core.model.ReminderScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

data class RemindersUiState(
    val busy: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val reminderRepository: ReminderRepository,
) : ViewModel() {

    private val _scope = MutableStateFlow(ReminderScope.Shared)
    val scope: StateFlow<ReminderScope> = _scope.asStateFlow()

    private val _uiState = MutableStateFlow(RemindersUiState())
    val uiState: StateFlow<RemindersUiState> = _uiState.asStateFlow()

    private val currentUser = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** Null until the invite is accepted, which is what disables the shared tab. */
    val pairingId: StateFlow<String?> = currentUser
        .map { it?.pairingId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val partnerIdFlow = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .distinctUntilChanged()

    val partnerId: StateFlow<String?> = partnerIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** Their name, for saying whose a shared task is without spelling out a uid. */
    val partnerName: StateFlow<String> = partnerIdFlow
        .flatMapLatest { id -> if (id == null) flowOf(null) else userRepository.observe(id) }
        .map { it?.displayName.orEmpty().ifBlank { "Them" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "Them")

    val myUid: String? get() = authRepository.currentUid

    val reminders: StateFlow<List<Reminder>> =
        combine(_scope, currentUser) { scope, user -> scope to user }
            .flatMapLatest { (scope, user) ->
                val ownerId = ownerIdFor(scope, user?.pairingId, user?.uid)
                if (ownerId == null) {
                    flowOf(emptyList())
                } else {
                    reminderRepository.observe(scope, ownerId).map { it.sortedForDisplay() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun selectScope(scope: ReminderScope) {
        _scope.value = scope
    }

    fun add(
        title: String,
        note: String,
        dueAt: Date?,
        dueHasTime: Boolean,
        priorityValue: Int,
        contactAlarm: Boolean,
    ) {
        if (title.isBlank()) return
        withOwner { scope, ownerId, uid ->
            reminderRepository.add(
                scope = scope,
                ownerId = ownerId,
                title = title,
                note = note,
                dueAt = dueAt,
                dueHasTime = dueHasTime,
                priorityValue = priorityValue,
                contactAlarm = contactAlarm,
                createdBy = uid,
            )
        }
    }

    /**
     * Persists a hand-arranged order.
     *
     * The list is already showing the new arrangement by the time this runs —
     * the drag reorders locally so the item follows the finger. This only
     * writes it down.
     */
    fun reorder(orderedIds: List<String>) {
        // Ids go stale easily here. A drop lands a moment after the drag began,
        // and in between the row - or one above it - can be deleted, by you or
        // by the other person on a shared list. The write is a single batch, so
        // one id pointing at a document that no longer exists fails the whole
        // reorder and reports a Firestore path at the user, which is neither
        // their problem nor anything they can act on.
        val live = reminders.value.mapTo(mutableSetOf()) { it.id }
        val ids = orderedIds.filter { it in live }
        if (ids.isEmpty()) return

        // Quiet on failure. The list is a live query, so a lost race corrects
        // itself on the next snapshot; there is nothing to tell anyone.
        withOwner(quiet = true) { scope, ownerId, _ ->
            reminderRepository.reorder(scope, ownerId, ids)
        }
    }

    fun toggle(reminder: Reminder) {
        withOwner { scope, ownerId, uid ->
            reminderRepository.setDone(scope, ownerId, reminder.id, !reminder.done, uid)
        }
    }

    fun edit(
        reminder: Reminder,
        title: String,
        note: String,
        dueAt: Date?,
        dueHasTime: Boolean,
        priorityValue: Int,
        contactAlarm: Boolean,
    ) {
        withOwner { scope, ownerId, _ ->
            reminderRepository.edit(
                scope = scope,
                ownerId = ownerId,
                reminderId = reminder.id,
                title = title,
                note = note,
                dueAt = dueAt,
                dueHasTime = dueHasTime,
                priorityValue = priorityValue,
                contactAlarm = contactAlarm,
            )
        }
    }

    fun delete(reminder: Reminder) {
        withOwner { scope, ownerId, _ ->
            reminderRepository.delete(scope, ownerId, reminder.id)
        }
    }

    fun clearCompleted() {
        withOwner { scope, ownerId, _ ->
            reminderRepository.clearCompleted(scope, ownerId).map { count ->
                _uiState.update {
                    it.copy(message = if (count == 0) "Nothing to clear" else "Cleared $count")
                }
            }
        }
    }

    /** Only meaningful on the shared list — there is nobody to poke on a private one. */
    fun nudge(reminder: Reminder) {
        val pairing = pairingId.value ?: return
        val partner = partnerId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            reminderRepository.nudge(pairing, reminder, uid, partner)
                .onSuccess { _uiState.update { it.copy(message = "Nudged") } }
                .onFailure { e ->
                    _uiState.update { it.copy(message = e.message ?: "Couldn't send that nudge") }
                }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private fun ownerIdFor(scope: ReminderScope, pairingId: String?, uid: String?): String? =
        when (scope) {
            ReminderScope.Shared -> pairingId
            ReminderScope.Private -> uid
        }

    private fun withOwner(
        quiet: Boolean = false,
        block: suspend (ReminderScope, String, String) -> Result<*>,
    ) {
        val scope = _scope.value
        val uid = authRepository.currentUid ?: return
        val ownerId = ownerIdFor(scope, currentUser.value?.pairingId, uid)

        if (ownerId == null) {
            _uiState.update { it.copy(message = "Pair with someone to use the shared list") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            block(scope, ownerId, uid).onFailure { e ->
                if (!quiet && !e.isMissingDocument()) {
                    _uiState.update { it.copy(message = e.message ?: "That didn't work") }
                }
            }
            _uiState.update { it.copy(busy = false) }
        }
    }

    /**
     * Acting on something that has already been deleted.
     *
     * Always a race rather than a fault: the row was on screen when it was
     * tapped and gone by the time the write landed, usually because the other
     * person removed it. The live query puts the list right by itself, so
     * there is nothing worth interrupting anyone about.
     */
    private fun Throwable.isMissingDocument(): Boolean =
        (this as? FirebaseFirestoreException)?.code ==
            FirebaseFirestoreException.Code.NOT_FOUND

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/**
 * Finished items to the bottom; everything else in the order it was arranged.
 *
 * Manual position wins over due date and priority on purpose. Someone who has
 * dragged an item somewhere means it, and a list that quietly re-sorts itself
 * afterwards is a list nobody trusts.
 */
private fun List<Reminder>.sortedForDisplay(): List<Reminder> = sortedWith(
    // Finished items sink. Above them, the next thing due comes first - which
    // is the question a list like this is usually being asked.
    compareBy<Reminder> { it.done }
        // Undated items after dated ones. Something with no deadline is not
        // urgent by omission, and floating it to the top would say it was.
        .thenBy { it.dueAt == null }
        .thenBy { it.dueAt?.time ?: Long.MAX_VALUE }
        // Hand-arranged position breaks ties: among things due at the same
        // time, or among the undated, the order you put them in still holds.
        .thenByDescending { it.orderIndex },
)
