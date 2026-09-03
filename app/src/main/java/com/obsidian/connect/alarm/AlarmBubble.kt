package com.obsidian.connect.alarm

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.obsidian.connect.widget.DrawingBubble

/**
 * The alarm, as something you see rather than only hear.
 *
 * Full screen and over everything, for the same reason the drawing light is a
 * window rather than part of the widget: an alarm that only appears on the
 * home screen is invisible to someone who is in another app, which is most of
 * the time.
 *
 * Added straight to the WindowManager. The process putting it there is already
 * awake — the alarm receiver has just run — and the window lives as long as
 * that process does.
 *
 * It gives up on its own after a couple of minutes. An alarm nobody dismissed
 * has been missed, and leaving a full-screen window over the phone until
 * someone notices would be worse than the alarm itself.
 */
object AlarmBubble {

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { hide(null) }

    private var appContext: Context? = null

    fun show(context: Context, title: String, subtitle: String) {
        val app = context.applicationContext
        appContext = app

        // Same permission the drawing light needs; no reason to ask twice.
        if (!DrawingBubble.canShow(app)) return

        hide(app)

        val windows = app.getSystemService(WindowManager::class.java) ?: return
        val face = RippleClockView(app, title, subtitle)

        // Anywhere on the screen dismisses it. Hunting for a button while
        // something is ringing is not a design worth defending.
        face.setOnClickListener { dismiss(app) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Shown over the lock screen and allowed to wake the display: an
            // alarm that waits for the phone to be unlocked has already failed.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )

        runCatching { windows.addView(face, params) }
            .onSuccess {
                view = face
                handler.removeCallbacks(giveUp)
                handler.postDelayed(giveUp, TIMEOUT_MS)
            }
    }

    /** Tapped, so stop the noise as well as the picture. */
    private fun dismiss(context: Context) {
        AlarmRinger.stop(context)
        hide(context)
    }

    fun hide(context: Context?) {
        val app = context?.applicationContext ?: appContext ?: return
        handler.removeCallbacks(giveUp)

        val current = view ?: return
        view = null

        // Silence goes with the window either way. A timed-out alarm that keeps
        // ringing with nothing on screen is the worst of both.
        AlarmRinger.stop(app)
        runCatching {
            app.getSystemService(WindowManager::class.java)?.removeView(current)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private const val TIMEOUT_MS = 2 * 60 * 1000L
}
