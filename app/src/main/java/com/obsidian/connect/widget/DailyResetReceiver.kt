package com.obsidian.connect.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Switches the face off every morning, so that showing it is a decision.
 *
 * A standing arrangement drifts. Hours set once in a good week keep applying
 * in a week where the phone is on a desk in front of other people, and nobody
 * remembers to go and change them. Turning it off at six means the face is
 * only ever on because somebody chose it that morning.
 *
 * Off by default. This is a deliberate piece of friction, and friction nobody
 * asked for is just an annoyance.
 */
class DailyResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (isEnabled(context)) {
            WidgetSchedule.setDisabled(context, true)
            WatchWidgetProvider.refreshAll(context)
        }
        // Re-armed on every fire rather than repeating, so a change to the
        // setting takes effect tomorrow rather than needing a reboot.
        schedule(context)
    }

    companion object {

        private const val PREFS = "connect_widget_schedule"
        private const val KEY_DAILY_ASK = "daily_ask"
        private const val REQUEST_CODE = 4312

        /** Six in the morning: before a working day, after most nights. */
        private const val RESET_HOUR = 6

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DAILY_ASK, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DAILY_ASK, enabled)
                .apply()

            if (enabled) schedule(context) else cancel(context)
        }

        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = pendingIntent(context)
            alarms.cancel(pending)
            if (!isEnabled(context)) return

            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, RESET_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // Already past six today, so the next one is tomorrow.
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            // Inexact is fine here. Nobody can tell whether the face went dark
            // at six or ten past, and an exact alarm is a permission to spend
            // on something that matters more.
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.timeInMillis,
                pending,
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, DailyResetReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
