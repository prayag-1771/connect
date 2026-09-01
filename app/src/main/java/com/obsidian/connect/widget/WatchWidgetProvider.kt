package com.obsidian.connect.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.obsidian.connect.MainActivity
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
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
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
            if (ids.isEmpty()) return

            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_watch)

            // Same bound as the Glance widget. RemoteViews cross a Binder
            // transaction capped near 1MB, and an oversized bitmap fails
            // silently — the widget just does not draw.
            val bitmap = WidgetImageStore.decode(context, MAX_DIMENSION_PX)
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.watch_photo, bitmap)
            }

            val caption = WidgetCaptionStore.read(context)
            if (caption.isNullOrBlank()) {
                views.setViewVisibility(R.id.watch_caption, View.GONE)
            } else {
                views.setTextViewText(R.id.watch_caption, caption)
                views.setViewVisibility(R.id.watch_caption, View.VISIBLE)
            }

            views.setOnClickPendingIntent(R.id.watch_root, openApp(context))
            return views
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val MAX_DIMENSION_PX = 720
    }
}
