package com.obsidian.connect.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.obsidian.connect.R
import com.obsidian.connect.draw.DrawingOverlayActivity

/**
 * A blue light on the right edge of the screen, over everything.
 *
 * Not part of the watch widget: this sits on the screen itself, so it is
 * visible whatever app happens to be open. Tapping it shows the drawing.
 *
 * Added straight to the WindowManager rather than through a service. The
 * process adding it is already alive — a sync worker or the app — and the
 * window lives as long as that process does. A foreground service would keep
 * it alive longer at the cost of a permanent notification, which is a poor
 * trade for an indicator that exists to be dismissed.
 *
 * The consequence, stated plainly: if Android reclaims the process the light
 * disappears until the next sync puts it back.
 */
object DrawingBubble {

    private var view: ImageView? = null

    /** Overlay windows need a permission the user grants in Settings. */
    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("InflateParams")
    fun show(context: Context) {
        if (!canShow(context)) return
        if (view != null) return

        val app = context.applicationContext
        val windows = app.getSystemService(WindowManager::class.java) ?: return

        val bubble = ImageView(app).apply {
            setImageResource(R.drawable.drawing_glow)
            val size = (BUBBLE_DP * app.resources.displayMetrics.density).toInt()
            layoutParams = WindowManager.LayoutParams(size, size)
            setOnClickListener {
                hide(app)
                app.startActivity(
                    Intent(app, DrawingOverlayActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        val params = WindowManager.LayoutParams(
            (BUBBLE_DP * app.resources.displayMetrics.density).toInt(),
            (BUBBLE_DP * app.resources.displayMetrics.density).toInt(),
            overlayType(),
            // NOT_FOCUSABLE so it never steals the keyboard or the back button
            // from whatever is underneath; it is an indicator, not a dialog.
            //
            // LAYOUT_NO_LIMITS is deliberately absent: with it the window can
            // be positioned under the status bar, where the light is hidden by
            // the clock and battery icons.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (EDGE_MARGIN_DP * app.resources.displayMetrics.density).toInt()
            y = (TOP_MARGIN_DP * app.resources.displayMetrics.density).toInt()
        }

        runCatching { windows.addView(bubble, params) }
            .onSuccess { view = bubble }
    }

    fun hide(context: Context) {
        val current = view ?: return
        val windows = context.applicationContext
            .getSystemService(WindowManager::class.java)

        runCatching { windows?.removeView(current) }
        view = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private const val BUBBLE_DP = 44

    /** Just inside the top-right corner, clear of the status bar. */
    private const val EDGE_MARGIN_DP = 6
    private const val TOP_MARGIN_DP = 6
}
