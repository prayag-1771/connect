package com.obsidian.connect.call

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * One call, from this phone's side.
 *
 * Owns the peer connection and the tracks going into it: a microphone, the
 * front camera, and - when asked - the screen. Everything about *finding* the
 * other phone lives in the signalling layer; this only cares about media.
 *
 * The camera and the screen are separate tracks rather than one switched
 * source, so sharing a screen does not cost you the ability to see each other.
 * That is the whole point of watching something together.
 */
class RtcSession(
    private val context: Context,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onRemoteTrack: (VideoTrack, Boolean) -> Unit,
    private val onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit,
) {
    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                // Hardware encoding where the phone offers it. Encoding a whole
                // screen in software is a battery fire.
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var peer: PeerConnection? = null

    private var cameraCapturer: VideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraHelper: SurfaceTextureHelper? = null

    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenHelper: SurfaceTextureHelper? = null

    var localCamera: VideoTrack? = null
        private set

    private var localAudio: AudioTrack? = null
    private var localScreen: VideoTrack? = null

    /**
     * Remote tracks arrive without saying what they are.
     *
     * Order is the only clue, and it holds because both sides add the camera
     * before anything else: the first video track to arrive is a face, the
     * second is a screen.
     */
    private var remoteVideoCount = 0

    fun start() {
        peer = factory.createPeerConnection(
            RtcConfig.configuration(),
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) =
                    this@RtcSession.onIceCandidate.invoke(candidate)

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver?.track() ?: return
                    if (track.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) return
                    val video = track as? VideoTrack ?: return
                    onRemoteTrack(video, remoteVideoCount > 0)
                    remoteVideoCount++
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) =
                    this@RtcSession.onConnectionChange.invoke(state)

                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
            },
        )

        addAudio()
        addCamera()
    }

    private fun addAudio() {
        val source = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("audio", source)
        peer?.addTrack(localAudio, listOf(STREAM))
    }

    private fun addCamera() {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return

        val capturer = enumerator.createCapturer(front, null) ?: return
        val source = factory.createVideoSource(false)
        val textureHelper = SurfaceTextureHelper.create("camera", eglBase.eglBaseContext)

        capturer.initialize(textureHelper, context, source.capturerObserver)
        capturer.startCapture(CAMERA_WIDTH, CAMERA_HEIGHT, CAMERA_FPS)

        val track = factory.createVideoTrack("camera", source)
        peer?.addTrack(track, listOf(STREAM))

        cameraCapturer = capturer
        cameraSource = source
        cameraHelper = textureHelper
        localCamera = track
    }

    /**
     * Adds the screen alongside the camera.
     *
     * [permission] is the result of the system consent dialog, which Android
     * insists on showing every single time. It cannot be remembered, and that
     * is deliberate.
     */
    fun startScreenShare(permission: Intent) {
        if (screenCapturer != null) return

        val capturer = ScreenCapturerAndroid(
            permission,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // Stopped from the system notification rather than from the
                    // app, so tidy up here or the next share starts broken.
                    stopScreenShare()
                }
            },
        )

        val source = factory.createVideoSource(true)
        val textureHelper = SurfaceTextureHelper.create("screen", eglBase.eglBaseContext)
        capturer.initialize(textureHelper, context, source.capturerObserver)
        capturer.startCapture(SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_FPS)

        val track = factory.createVideoTrack("screen", source)
        peer?.addTrack(track, listOf(STREAM))

        screenCapturer = capturer
        screenSource = source
        screenHelper = textureHelper
        localScreen = track
    }

    fun stopScreenShare() {
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenHelper?.dispose() }
        screenCapturer = null
        screenSource = null
        screenHelper = null
        localScreen = null
    }

    suspend fun createOffer(): String? = suspendCoroutine { cont ->
        val connection = peer
        if (connection == null) {
            cont.resume(null)
            return@suspendCoroutine
        }
        connection.createOffer(
            sdpObserver(
                onCreated = { sdp ->
                    connection.setLocalDescription(sdpObserver(), sdp)
                    cont.resume(sdp.description)
                },
                onFailed = { cont.resume(null) },
            ),
            MediaConstraints(),
        )
    }

    suspend fun createAnswer(): String? = suspendCoroutine { cont ->
        val connection = peer
        if (connection == null) {
            cont.resume(null)
            return@suspendCoroutine
        }
        connection.createAnswer(
            sdpObserver(
                onCreated = { sdp ->
                    connection.setLocalDescription(sdpObserver(), sdp)
                    cont.resume(sdp.description)
                },
                onFailed = { cont.resume(null) },
            ),
            MediaConstraints(),
        )
    }

    fun setRemoteDescription(sdp: String, isOffer: Boolean) {
        val type = if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        peer?.setRemoteDescription(sdpObserver(), SessionDescription(type, sdp))
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        peer?.addIceCandidate(candidate)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudio?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localCamera?.setEnabled(enabled)
    }

    fun flipCamera() {
        (cameraCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun dispose() {
        stopScreenShare()
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { cameraHelper?.dispose() }
        runCatching { peer?.close() }
        peer = null
        runCatching { eglBase.release() }
    }

    private fun sdpObserver(
        onCreated: (SessionDescription) -> Unit = {},
        onFailed: () -> Unit = {},
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreated(sdp)
        override fun onCreateFailure(reason: String?) = onFailed()
        override fun onSetSuccess() = Unit
        override fun onSetFailure(reason: String?) = Unit
    }

    private companion object {
        const val STREAM = "connect"

        const val CAMERA_WIDTH = 640
        const val CAMERA_HEIGHT = 480
        const val CAMERA_FPS = 24

        /**
         * Deliberately below the phone's real resolution.
         *
         * A full-resolution screen at thirty frames is far more than a mobile
         * uplink carries, and far more than a free relay will pass. Half height
         * at fifteen frames still reads fine for anything but fast motion.
         */
        const val SCREEN_WIDTH = 720
        const val SCREEN_HEIGHT = 1280
        const val SCREEN_FPS = 15
    }
}
