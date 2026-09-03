package com.obsidian.connect.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * One WebRTC video track, on screen.
 *
 * A plain Android view rather than anything Compose-native, because decoded
 * frames go straight to a surface - there is no bitmap to hand to a Composable,
 * and copying every frame into one would throw away the reason hardware
 * decoding exists.
 *
 * The renderer is released when it leaves, and the track is detached from it
 * first. Leaving a dead renderer attached to a live track is how a call ends up
 * drawing into a surface that no longer exists.
 */
@Composable
fun VideoSurface(
    track: VideoTrack,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                track.addSink(this)
            }
        },
        onRelease = { renderer ->
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        },
    )

    DisposableEffect(track) {
        onDispose { }
    }
}
