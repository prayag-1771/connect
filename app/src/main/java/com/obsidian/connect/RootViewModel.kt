package com.obsidian.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.sync.WidgetLiveUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    widgetLiveUpdater: WidgetLiveUpdater,
) : ViewModel() {

    init {
        // Closes the gap the fifteen-minute periodic worker leaves. While the
        // app is open a Firestore listener costs nothing extra, so the widget
        // tracks the partner's latest photo in near real time.
        viewModelScope.launch { widgetLiveUpdater.run() }
    }

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
                userRepository.observe(uid).map { user ->
                    if (user?.pairingId != null) Stage.Ready else Stage.Unpaired
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Stage.Loading,
        )
}
