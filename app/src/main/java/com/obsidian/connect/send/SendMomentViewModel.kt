package com.obsidian.connect.send

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.camera.ImageCompressor
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MomentRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.archive.PhotoArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SendMomentState(
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SendMomentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val momentRepository: MomentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SendMomentState())
    val state: StateFlow<SendMomentState> = _state.asStateFlow()

    fun send(jpeg: ByteArray, caption: String = "") {
        val uid = authRepository.currentUid
        if (uid == null) {
            _state.update { it.copy(error = "You're signed out") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(sending = true, sent = false, error = null) }

            val pairingId = runCatching { userRepository.get(uid)?.pairingId }.getOrNull()
            if (pairingId == null) {
                _state.update {
                    it.copy(sending = false, error = "You aren't paired with anyone yet")
                }
                return@launch
            }

            // Decoding and re-encoding a full-resolution photo is heavy enough
            // to drop frames if it runs on the main thread.
            val compressed = withContext(Dispatchers.Default) {
                runCatching { ImageCompressor.compress(jpeg) }
            }.getOrElse {
                _state.update { s -> s.copy(sending = false, error = "Couldn't process that photo") }
                return@launch
            }

            momentRepository.send(
                pairingId = pairingId,
                senderId = uid,
                jpeg = compressed,
                caption = caption.trim(),
            )
                .onSuccess { moment ->
                    // Kept on this phone too. Firestore prunes to the last 30,
                    // and the sender should not lose their own photos to that.
                    PhotoArchive.save(
                        context = context,
                        jpeg = compressed,
                        origin = PhotoArchive.Origin.Sent,
                        id = moment.id,
                    )
                    _state.update { it.copy(sending = false, sent = true) }
                    // Photos live inside Firestore documents now, and the free
                    // plan caps the whole database at 1GiB. Without this the
                    // collection grows until writes start failing.
                    momentRepository.pruneOlderThan(pairingId)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(sending = false, error = error.message ?: "Couldn't send that")
                    }
                }
        }
    }

    fun consumeResult() = _state.update { SendMomentState() }
}
