package com.obsidian.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
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
 * Which of the three top-level states the app is in.
 *
 * [Loading] only shows for the moment before Firebase reports the cached
 * session, which is why it renders as a blank surface rather than a spinner —
 * a spinner that appears for 80ms reads as a flicker.
 */
enum class Stage { Loading, SignedOut, Unpaired, Ready }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
    userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
) : ViewModel() {

    /**
     * Derived from live data rather than tracked as navigation state, so the
     * app moves on by itself the moment a pairing completes — including when
     * it completes because the *other* person accepted the invite.
     */
    val stage: StateFlow<Stage> = authRepository.uidFlow
        .flatMapLatest { uid ->
            if (uid == null) {
                flowOf(Stage.SignedOut)
            } else {
                userRepository.observe(uid)
                    .map { it?.pairingId }
                    .distinctUntilChanged()
                    .flatMapLatest { pairingId ->
                        if (pairingId == null) {
                            flowOf(Stage.Unpaired)
                        } else {
                            // Having a pairing id is not the same as being
                            // paired. Creating an invite writes the id
                            // immediately, while the second member arrives
                            // whenever the other person gets round to it.
                            // Treating the id alone as "ready" skipped the
                            // waiting screen entirely, so the person who
                            // created the invite never saw their own code.
                            pairingRepository.observe(pairingId).map { pairing ->
                                if (pairing?.isComplete == true) Stage.Ready else Stage.Unpaired
                            }
                        }
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Stage.Loading,
        )
}
