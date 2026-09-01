package com.obsidian.connect.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.obsidian.connect.MainActivity
import com.obsidian.connect.chat.QuickReplyActivity
import com.obsidian.connect.R

/**
 * A watch face built from the other person's latest photo.
 *
 * Plain RemoteViews rather than Glance, for one reason: `AnalogClock` keeps
 * its own time. Once the launcher has inflated it the hands move on their own,
 * with no process of ours running. A clock drawn in Glance would need the
 * widget redrawn every minute from the background, which is precisely the work
 * Android now goes to great lengths to prevent — and the widget would sit
 * frozen at whatever minute it was last updated.
 */
class WatchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, appWidgetManager, id))
        }
    }

    /**
     * Re-renders when the widget is resized.
     *
     * The face is rasterised to a fixed pixel size, so a widget stretched after
     * placement would otherwise keep displaying the bitmap cut for its old
     * dimensions and look soft.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildViews(context, appWidgetManager, appWidgetId),
        )
    }

    companion object {

        /**
         * Redraws every placed watch widget from whatever is currently on disk.
         *
         * Called by [MomentWidgetUpdater] so the two widget styles stay in step
         * and a photo lands on both.
         */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WatchWidgetProvider::class.java),
            )
            ids.forEach { manager.updateAppWidget(it, buildViews(context, manager, it)) }
        }

        private fun buildViews(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_watch)

            val facePx = WatchFaceRenderer.sizeFor(requestedFacePx(context, manager, appWidgetId))
            val active = WidgetSchedule.isActive(context)

            // Outside the window this is a clock and nothing else. No photo, no
            // caption, no dot — a face on a home screen all day should not be
            // showing someone you love to whoever glances over your shoulder.
            views.setImageViewBitmap(
                R.id.watch_photo,
                WatchFaceRenderer.circularFace(
                    source = if (active) WidgetImageStore.decode(context, facePx) else null,
                    size = facePx,
                    hasUnread = active && WidgetCaptionStore.hasUnread(context),
                ),
            )

            val caption = WidgetCaptionStore.read(context).takeIf { active }
            if (caption.isNullOrBlank()) {
                views.setViewVisibility(R.id.watch_caption, View.GONE)
            } else {
                views.setTextViewText(R.id.watch_caption, caption)
                views.setViewVisibility(R.id.watch_caption, View.VISIBLE)
            }

            // Off-hours it behaves like the clock it is pretending to be.
            views.setOnClickPendingIntent(
                R.id.watch_root,
                if (active) openApp(context) else openClock(context),
            )

            // The dot is painted into the bitmap, so it needs a separate
            // invisible view over it to be tappable at all.
            val unread = active && WidgetCaptionStore.hasUnread(context)
            views.setViewVisibility(
                R.id.watch_unread_touch,
                if (unread) View.VISIBLE else View.GONE,
            )
            if (unread) {
                views.setOnClickPendingIntent(R.id.watch_unread_touch, openQuickReply(context))
            }

            return views
        }

        /**
         * How large this particular widget actually is, in pixels.
         *
         * Rendering a fixed size for every instance would either waste memory
         * on a small widget or look soft on a large one. The reported value is
         * in dp and can be missing, so it falls back to a sensible default.
         */
        private fun requestedFacePx(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
        ): Int {
            val options = manager.getAppWidgetOptions(appWidgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

            // The face is a circle, so the limiting dimension is the smaller one.
            val dp = listOf(widthDp, heightDp).filter { it > 0 }.minOrNull() ?: DEFAULT_FACE_DP
            return (dp * context.resources.displayMetrics.density).toInt()
        }

        /** Opens the reply strip over the home screen. */
        private fun openQuickReply(context: Context): PendingIntent {
            val intent = Intent(context, QuickReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                REPLY_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Opens the phone's clock app.
         *
         * ACTION_SHOW_ALARMS is the only documented way to reach it without
         * knowing the OEM's package name, which differs on every device. If
         * nothing handles it — rare, but possible on a stripped ROM — falling
         * back to our own app is better than a tap that does nothing.
         */
        private fun openClock(context: Context): PendingIntent {
            val clock = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val resolves = clock.resolveActivity(context.packageManager) != null
            if (!resolves) return openApp(context)

            return PendingIntent.getActivity(
                context,
                CLOCK_REQUEST,
                clock,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                APP_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val DEFAULT_FACE_DP = 160

        // Distinct request codes, or the two intents would overwrite each other.
        private const val APP_REQUEST = 0
        private const val CLOCK_REQUEST = 1
        private const val REPLY_REQUEST = 2
    }
}
