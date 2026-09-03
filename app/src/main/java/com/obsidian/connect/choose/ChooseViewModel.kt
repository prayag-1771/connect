package com.obsidian.connect.choose

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.camera.ImageCompressor
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.ChoiceRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Choice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChooseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    private val choiceRepository: ChoiceRepository,
) : ViewModel() {

    val myUid: String? get() = authRepository.currentUid

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val choices: StateFlow<List<Choice>> = pairingId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else choiceRepository.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun add(uri: Uri) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return

        viewModelScope.launch {
            _busy.value = true
            val compressed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { it?.readBytes() }
                        ?.let { ImageCompressor.compress(it) }
                }.getOrNull()
            }

            if (compressed != null) choiceRepository.add(id, uid, compressed)
            _busy.value = false
        }
    }

    /**
     * Records a verdict, or takes it back.
     *
     * Voting the same way twice clears it, so a mis-tap is undone by repeating
     * it rather than by looking for an undo that is not there.
     */
    fun judge(choice: Choice, verdict: Int) {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        val next = if (choice.verdict == verdict) 0 else verdict

        viewModelScope.launch { choiceRepository.judge(id, choice.id, uid, next) }
    }

    fun delete(choice: Choice) {
        val id = pairingId.value ?: return
        viewModelScope.launch { choiceRepository.delete(id, choice.id) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
