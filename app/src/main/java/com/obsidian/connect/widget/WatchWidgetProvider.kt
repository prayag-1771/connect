package com.obsidian.connect.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
            val photo = WidgetImageStore.decode(context, facePx)

            if (photo == null) {
                views.setViewVisibility(R.id.watch_photo, View.GONE)
            } else {
                views.setViewVisibility(R.id.watch_photo, View.VISIBLE)
                views.setImageViewBitmap(
                    R.id.watch_photo,
                    WatchFaceRenderer.circularFace(photo, facePx),
                )
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

        private const val DEFAULT_FACE_DP = 160
    }
}
