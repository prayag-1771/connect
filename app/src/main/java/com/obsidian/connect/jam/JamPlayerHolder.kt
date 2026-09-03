package com.obsidian.connect.jam

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.obsidian.connect.widget.DrawingBubble

/**
 * The player, living outside any screen.
 *
 * A jam should not stop because you went back to the chat, so the WebView
 * cannot belong to the jam screen - anything owned by a composable dies with
 * it. It lives here instead, for as long as the track does.
 *
 * It is attached to a small, near-transparent window rather than left floating
 * unattached. A WebView with no window is never given a surface, and one too
 * small to see is treated as invisible and suspended - both end in silence.
 *
 * That also settles what the jam screen shows: nothing. The picture was never
 * the point for music, and there is no longer anywhere it could appear.
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

    /**
     * A track asked for before the page could take it.
     *
     * Creating the WebView and calling into it are not the same moment: the
     * page takes a few hundred milliseconds to fetch its script and define
     * anything. Commands sent in that window hit a document with no functions
     * in it and vanish with a ReferenceError - the player then sits perfectly
     * ready, having been told nothing.
     */
    private var pending: Pending? = null

    private data class Pending(val videoId: String, val startMs: Long, val play: Boolean)

    fun ensure(context: Context): JamPlayer {
        player?.let { return it }

        val app = context.applicationContext
        val created = JamPlayer(
            context = app,
            onReady = {
                isReady = true
                // Whatever was asked for while the page was still loading.
                pending?.let { queued ->
                    pending = null
                    player?.load(queued.videoId, queued.startMs, queued.play)
                }
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
     * A real rectangle, in a corner, almost completely transparent.
     *
     * It was one pixel at first, and that is exactly why the sound stopped:
     * Chromium suspends a video element it considers invisible, and a
     * one-by-one surface counts as invisible. It has to have genuine size and
     * genuine visibility to keep decoding, so it gets both - and an alpha low
     * enough that nobody will ever notice it.
     *
     * NOT_FOCUSABLE and NOT_TOUCHABLE so it can never take a tap or the
     * keyboard from whatever is actually on screen.
     */
    private fun attach(context: Context, created: JamPlayer) {
        if (attached) return

        if (!DrawingBubble.canShow(context)) {
            Log.w(TAG, "no overlay permission, jam cannot play off-screen")
            return
        }

        val windows = context.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            WIDTH_PX,
            HEIGHT_PX,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = INVISIBLE_ENOUGH
        }

        runCatching { windows.addView(created.view, params) }
            .onSuccess {
                attached = true
                Log.d(TAG, "player attached")
            }
            .onFailure { Log.w(TAG, "player could not attach: ${it.message}") }
    }

    fun load(context: Context, videoId: String, startMs: Long, play: Boolean) {
        val active = ensure(context)
        loadedVideoId = videoId

        if (isReady) {
            active.load(videoId, startMs, play)
        } else {
            pending = Pending(videoId, startMs, play)
        }
    }

    /**
     * Nothing is sent before the page can receive it.
     *
     * A play or a pause that arrives early is dropped rather than queued: by
     * the time the page is ready the session will have been applied in full,
     * and replaying a stale instruction on top of it would fight that.
     */
    fun play(context: Context) {
        val active = ensure(context)
        if (isReady) active.play() else pending = pending?.copy(play = true)
    }

    fun pause(context: Context) {
        val active = ensure(context)
        if (isReady) active.pause() else pending = pending?.copy(play = false)
    }

    fun seekTo(context: Context, positionMs: Long) {
        val active = ensure(context)
        if (isReady) active.seekTo(positionMs)
    }

    fun requestPosition(context: Context) {
        val active = ensure(context)
        if (isReady) active.requestPosition()
    }

    /** Ends the jam and takes the window back. */
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
        pending = null
        loadedVideoId = ""
        lastPositionMs = 0L
    }

    private const val TAG = "JamPlayerHolder"

    /** Big enough that Chromium treats it as a real, visible player. */
    private const val WIDTH_PX = 320
    private const val HEIGHT_PX = 180

    /** Visible to the compositor, invisible to a person. */
    private const val INVISIBLE_ENOUGH = 0.02f

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
