package com.obsidian.connect.call

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.CallRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Call
import com.obsidian.connect.core.model.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import javax.inject.Inject
import com.obsidian.connect.core.model.IceCandidate as SignalCandidate

/** What the call screen is showing at any moment. */
data class CallUi(
    val state: CallState = CallState.Idle,
    val outgoing: Boolean = false,
    val connected: Boolean = false,
    val micOn: Boolean = true,
    val cameraOn: Boolean = true,
    val sharingScreen: Boolean = false,
    val partnerSharingScreen: Boolean = false,
    val partnerName: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val callRepository: CallRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private val _localTrack = MutableStateFlow<VideoTrack?>(null)
    val localTrack: StateFlow<VideoTrack?> = _localTrack.asStateFlow()

    private val _remoteFace = MutableStateFlow<VideoTrack?>(null)
    val remoteFace: StateFlow<VideoTrack?> = _remoteFace.asStateFlow()

    private val _remoteScreen = MutableStateFlow<VideoTrack?>(null)
    val remoteScreen: StateFlow<VideoTrack?> = _remoteScreen.asStateFlow()

    private val pairingId: StateFlow<String?> = authRepository.uidFlow
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else userRepository.observe(uid) }
        .map { it?.pairingId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var session: RtcSession? = null
    private var signalJob: Job? = null
    private var candidateJob: Job? = null

    /** True while this phone is the one that placed the call. */
    private var amCaller = false

    val eglContext get() = session?.eglBase?.eglBaseContext

    /**
     * Starts a call.
     *
     * The session is built before the offer, because an offer describes the
     * tracks that already exist - build it first and you would be promising
     * media nothing is producing.
     */
    fun place() {
        val id = pairingId.value ?: return
        val uid = authRepository.currentUid ?: return
        if (session != null) return

        amCaller = true
        _ui.value = _ui.value.copy(state = CallState.Ringing, outgoing = true)

        val rtc = build()
        rtc.start()

        viewModelScope.launch {
            val sdp = rtc.createOffer() ?: return@launch
            callRepository.offer(id, uid, sdp, sharingScreen = false)
            listen(id)
        }
    }

    /** Picks up a call the other person placed. */
    fun answer() {
        val id = pairingId.value ?: return
        if (session != null) return

        amCaller = false
        _ui.value = _ui.value.copy(state = CallState.Ringing, outgoing = false)

        val rtc = build()
        rtc.start()

        viewModelScope.launch {
            val call = callRepository.observe(id).first { it?.offer?.isNotBlank() == true }
                ?: return@launch

            rtc.setRemoteDescription(call.offer, isOffer = true)
            val sdp = rtc.createAnswer() ?: return@launch
            callRepository.answer(id, sdp)
            listen(id)
        }
    }

    private fun build(): RtcSession {
        val rtc = RtcSession(
            context = context,
            onIceCandidate = { candidate -> sendCandidate(candidate) },
            onRemoteTrack = { track, isScreen ->
                if (isScreen) _remoteScreen.value = track else _remoteFace.value = track
            },
            onConnectionChange = { state ->
                _ui.value = _ui.value.copy(
                    connected = state == PeerConnection.PeerConnectionState.CONNECTED,
                )
                if (state == PeerConnection.PeerConnectionState.FAILED) hangUp()
            },
        )
        session = rtc
        _localTrack.value = rtc.localCamera
        // The camera track only exists once start() has run, so this is set
        // again after the fact rather than guessed at here.
        return rtc
    }

    private fun listen(pairing: String) {
        signalJob?.cancel()
        signalJob = viewModelScope.launch {
            callRepository.observe(pairing).collect { call ->
                if (call == null) return@collect
                onSignal(call)
            }
        }

        // Candidates from the other side only. Feeding a phone its own paths
        // back would at best waste time and at worst confuse the negotiation.
        candidateJob?.cancel()
        candidateJob = viewModelScope.launch {
            callRepository.observeCandidates(pairing, fromCaller = !amCaller)
                .collect { candidates ->
                    candidates.forEach { c ->
                        session?.addRemoteCandidate(
                            IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate),
                        )
                    }
                }
        }

        _localTrack.value = session?.localCamera
    }

    private fun onSignal(call: Call) {
        _ui.value = _ui.value.copy(
            state = call.state,
            partnerSharingScreen = call.sharingScreen && !call.isMine(authRepository.currentUid.orEmpty()),
        )

        // The caller waits for an answer; the callee has already handled the
        // offer on the way in.
        if (amCaller && call.answer.isNotBlank()) {
            session?.setRemoteDescription(call.answer, isOffer = false)
        }

        if (call.state == CallState.Ended) tearDown()
    }

    private fun sendCandidate(candidate: IceCandidate) {
        val id = pairingId.value ?: return
        viewModelScope.launch {
            callRepository.addCandidate(
                pairingId = id,
                fromCaller = amCaller,
                candidate = SignalCandidate(
                    sdpMid = candidate.sdpMid.orEmpty(),
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp.orEmpty(),
                ),
            )
        }
    }

    fun startScreenShare(permission: Intent) {
        val id = pairingId.value ?: return
        session?.startScreenShare(permission)
        _ui.value = _ui.value.copy(sharingScreen = true)
        viewModelScope.launch { callRepository.setSharingScreen(id, true) }
    }

    fun stopScreenShare() {
        val id = pairingId.value ?: return
        session?.stopScreenShare()
        _ui.value = _ui.value.copy(sharingScreen = false)
        viewModelScope.launch { callRepository.setSharingScreen(id, false) }
    }

    fun toggleMic() {
        val next = !_ui.value.micOn
        session?.setMicrophoneEnabled(next)
        _ui.value = _ui.value.copy(micOn = next)
    }

    fun toggleCamera() {
        val next = !_ui.value.cameraOn
        session?.setCameraEnabled(next)
        _ui.value = _ui.value.copy(cameraOn = next)
    }

    fun flipCamera() {
        session?.flipCamera()
    }

    fun hangUp() {
        val id = pairingId.value
        viewModelScope.launch {
            if (id != null) callRepository.end(id)
            tearDown()
        }
    }

    private fun tearDown() {
        signalJob?.cancel()
        candidateJob?.cancel()
        session?.dispose()
        session = null
        _localTrack.value = null
        _remoteFace.value = null
        _remoteScreen.value = null
        _ui.value = CallUi(state = CallState.Ended)
    }

    override fun onCleared() {
        super.onCleared()
        // A peer connection left open holds the camera and the microphone.
        session?.dispose()
        session = null
    }
}
