package com.obsidian.connect.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Flips the watch face over when the active window opens or closes.
 *
 * Nothing else would. The widget only redraws when something asks it to, and
 * a schedule boundary is a moment when nothing has changed except the clock —
 * so without this the face would keep showing a photo for up to fifteen
 * minutes past the end of the window, or stay blank past the start of it.
 */
class ScheduleBoundaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        WatchWidgetProvider.refreshAll(context)
        // Re-arms itself. A repeating alarm cannot be used because the gap to
        // the next boundary is not a fixed interval.
        schedule(context)
    }

    companion object {

        private const val REQUEST_CODE = 4210

        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = pendingIntent(context)

            val minutes = WidgetSchedule.minutesUntilNextBoundary(context)
            val triggerAt = SystemClock.elapsedRealtime() + minutes * 60_000L

            alarms.cancel(pending)

            // Inexact deliberately. An exact alarm needs SCHEDULE_EXACT_ALARM,
            // which is a permission users are asked to grant and Play scrutinises,
            // and this is a widget changing appearance — a minute of drift at the
            // boundary is not worth any of that.
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME,
                triggerAt,
                pending,
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ScheduleBoundaryReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
