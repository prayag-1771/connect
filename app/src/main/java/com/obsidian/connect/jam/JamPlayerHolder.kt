package com.obsidian.connect.jam

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import com.obsidian.connect.widget.DrawingBubble

/**
 * The player, living outside any screen.
 *
 * A jam should not stop because you went back to the chat, so the WebView
 * cannot belong to the jam screen - anything owned by a composable dies with
 * it. It lives here instead, for as long as the track does.
 *
 * It is attached to a one-pixel window rather than left floating unattached.
 * A WebView with no window does not reliably play media at all: the platform
 * treats it as off-screen and stops giving it a surface. One transparent pixel
 * in the corner is the cheapest way to stay legitimately on screen.
 *
 * That also settles what the jam screen shows. There is no video to display
 * anywhere, because the thing playing it is a pixel - which is the right
 * arrangement for music, where the picture was never the point.
 */
object JamPlayerHolder {

    private var player: JamPlayer? = null
    private var attached = false

    /** Set by whatever is currently interested; may be nobody. */
    var onStateChange: ((Boolean) -> Unit)? = null
    var onPositionMs: ((Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    var isReady: Boolean = false
        private set

    var lastPositionMs: Long = 0L
        private set

    var loadedVideoId: String = ""
        private set

    fun ensure(context: Context): JamPlayer {
        player?.let { return it }

        val app = context.applicationContext
        val created = JamPlayer(
            context = app,
            onReady = {
                isReady = true
                onReady?.invoke()
            },
            onStateChange = { playing -> onStateChange?.invoke(playing) },
            onPositionMs = { position ->
                lastPositionMs = position
                onPositionMs?.invoke(position)
            },
            onError = { message -> onError?.invoke(message) },
        )

        player = created
        attach(app, created)
        return created
    }

    /**
     * One transparent pixel, top-left, behind everything.
     *
     * NOT_FOCUSABLE and NOT_TOUCHABLE so it can never take a tap or a keyboard
     * from whatever is actually on screen.
     */
    private fun attach(context: Context, created: JamPlayer) {
        if (attached) return
        if (!DrawingBubble.canShow(context)) return

        val windows = context.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )

        runCatching { windows.addView(created.view, params) }
            .onSuccess { attached = true }
    }

    fun load(context: Context, videoId: String, startMs: Long, play: Boolean) {
        val active = ensure(context)
        loadedVideoId = videoId
        active.load(videoId, startMs, play)
    }

    fun play(context: Context) = ensure(context).play()

    fun pause(context: Context) = ensure(context).pause()

    fun seekTo(context: Context, positionMs: Long) = ensure(context).seekTo(positionMs)

    fun requestPosition(context: Context) = ensure(context).requestPosition()

    /** Ends the jam and takes the pixel back. */
    fun release(context: Context) {
        val current = player ?: return
        runCatching {
            context.applicationContext
                .getSystemService(WindowManager::class.java)
                ?.removeView(current.view)
        }
        current.release()

        player = null
        attached = false
        isReady = false
        loadedVideoId = ""
        lastPositionMs = 0L
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
