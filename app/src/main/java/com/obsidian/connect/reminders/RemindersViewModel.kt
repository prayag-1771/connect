package com.obsidian.connect.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    val partnerId: StateFlow<String?> = pairingId
        .flatMapLatest { id -> if (id == null) flowOf(null) else pairingRepository.observe(id) }
        .map { it?.partnerOf(authRepository.currentUid.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

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

    fun add(title: String, note: String, dueAt: Date?) {
        if (title.isBlank()) return
        withOwner { scope, ownerId, uid ->
            reminderRepository.add(
                scope = scope,
                ownerId = ownerId,
                title = title,
                note = note,
                dueAt = dueAt,
                createdBy = uid,
            )
        }
    }

    fun toggle(reminder: Reminder) {
        withOwner { scope, ownerId, uid ->
            reminderRepository.setDone(scope, ownerId, reminder.id, !reminder.done, uid)
        }
    }

    fun edit(reminder: Reminder, title: String, note: String, dueAt: Date?) {
        withOwner { scope, ownerId, _ ->
            reminderRepository.edit(scope, ownerId, reminder.id, title, note, dueAt)
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

    private fun withOwner(block: suspend (ReminderScope, String, String) -> Result<*>) {
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
                _uiState.update { it.copy(message = e.message ?: "That didn't work") }
            }
            _uiState.update { it.copy(busy = false) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/**
 * Outstanding items first, soonest due at the top, undated ones after those,
 * and everything finished pushed to the bottom.
 *
 * Done in memory rather than in the query: ordering by done then due then
 * created would need a composite index per list, for a handful of rows.
 */
private fun List<Reminder>.sortedForDisplay(): List<Reminder> = sortedWith(
    compareBy<Reminder> { it.done }
        .thenBy { it.dueAt?.time ?: Long.MAX_VALUE }
        .thenByDescending { it.createdAt?.time ?: 0L },
)
