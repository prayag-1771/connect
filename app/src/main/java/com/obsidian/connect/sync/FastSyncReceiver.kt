package com.obsidian.connect.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Pulls the closed-app delay down from fifteen minutes to about two.
 *
 * WorkManager's periodic floor is fifteen minutes and no amount of asking
 * moves it, which is why a photo could sit on the server for a quarter of an
 * hour before reaching a watch face. The live listeners fix that while the
 * process is alive, but Android reclaims backgrounded processes and takes the
 * listeners with them — so something outside the process has to do the asking.
 *
 * An alarm is that something. It re-arms itself on every fire rather than
 * repeating, because a repeating alarm cannot have its interval changed and
 * this one deliberately backs off while the schedule says nobody is looking.
 *
 * The work itself is still handed to [SyncScheduler], so a fire during a dead
 * network is retried rather than skipped.
 */
class FastSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        SyncScheduler.now(context)
        schedule(context)
    }

    companion object {

        private const val REQUEST_CODE = 4211

        /**
         * Two minutes is a compromise, not a target.
         *
         * Shorter is technically allowed and would drain the battery for a gain
         * nobody would notice; longer starts to feel like the lag this exists
         * to remove. Doze stretches it regardless once the phone has been idle
         * for a while, which is the right behaviour — a photo arriving at 4am
         * can wait.
         */
        private const val INTERVAL_MS = 2 * 60 * 1000L

        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = pendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

            alarms.cancel(pending)

            // Exact where it is allowed, inexact where it is not. Unlike the
            // schedule boundary — where a minute of drift is invisible — the
            // whole point here is promptness, so it is worth asking. But the
            // permission is revocable, and an app that crashes because someone
            // said no is worse than one that runs a little late.
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarms.canScheduleExactAlarms()

            if (exact) {
                runCatching {
                    alarms.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pending,
                    )
                }.onFailure {
                    alarms.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pending,
                    )
                }
            } else {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, FastSyncReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
