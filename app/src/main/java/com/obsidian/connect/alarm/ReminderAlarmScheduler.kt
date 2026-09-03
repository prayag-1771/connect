package com.obsidian.connect.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.obsidian.connect.core.model.Reminder

/**
 * Keeps the phone's alarm clock in step with the reminder list.
 *
 * Both phones schedule from their own copy of a shared list, so a deadline
 * rings on each without anything having to be pushed at the moment it lands.
 * That is what makes this work with the app closed: an alarm set hours ago
 * fires whether or not the process is still alive.
 *
 * The set of scheduled ids is remembered, because an alarm has to be cancelled
 * when its reminder is ticked off, retimed or deleted — and by then the
 * reminder is gone and there is nothing left to derive the alarm from.
 */
object ReminderAlarmScheduler {

    private const val PREFS = "connect_reminder_alarms"
    private const val KEY_SCHEDULED = "scheduled_ids"

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_OWNER = "reminder_owner"
    const val EXTRA_CONTACT = "reminder_contact"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Replaces the whole schedule with one derived from [reminders].
     *
     * Wholesale rather than incrementally. Working out the difference between
     * two states of a list is more code and more ways to be wrong than simply
     * cancelling everything and re-setting what still applies, and the list is
     * never big enough for that to cost anything.
     */
    fun sync(context: Context, reminders: List<Reminder>) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return

        val previous = prefs(app).getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        previous.forEach { id -> alarms.cancel(pendingIntent(app, id, null)) }

        val now = System.currentTimeMillis()
        val live = mutableSetOf<String>()

        reminders.forEach { reminder ->
            // Past alarms are not resurrected. A deadline that went by while
            // the phone was off has been missed, and ringing about it on the
            // next boot would be an alarm for the wrong moment.
            val at = reminder.alarmAtMillis?.takeIf { it > now } ?: return@forEach

            val pending = pendingIntent(app, reminder.id, reminder)
            if (setExact(alarms, at, pending)) live += reminder.id
        }

        prefs(app).edit().putStringSet(KEY_SCHEDULED, live).apply()
    }

    /**
     * Exact where allowed, inexact where not.
     *
     * Unlike the sync heartbeat, drift genuinely matters here — an alarm is a
     * promise about a specific minute. But the permission is revocable, and an
     * alarm a few minutes late still beats an app that crashed asking for it.
     */
    private fun setExact(
        alarms: AlarmManager,
        at: Long,
        pending: PendingIntent,
    ): Boolean {
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()

        return runCatching {
            if (canBeExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.isSuccess
    }

    /**
     * [reminder] is null when cancelling — the extras are irrelevant then, and
     * matching a PendingIntent ignores them anyway.
     */
    private fun pendingIntent(
        context: Context,
        id: String,
        reminder: Reminder?,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            // A distinct action per reminder, because PendingIntent equality
            // ignores extras — without it every alarm would collapse onto one.
            action = "com.obsidian.connect.ALARM_$id"
            putExtra(EXTRA_ID, id)
            reminder?.let {
                putExtra(EXTRA_TITLE, it.title)
                putExtra(EXTRA_OWNER, it.createdBy)
                putExtra(EXTRA_CONTACT, it.contactAlarm)
            }
        }

        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
