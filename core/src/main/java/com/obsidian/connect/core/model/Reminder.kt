package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Calendar
import java.util.Date

/**
 * Which list a reminder belongs to.
 *
 * The two are stored in different places rather than as a flag on one
 * collection, because "private" has to be enforced by security rules and a
 * boolean field is not something rules can protect — anyone able to write the
 * document could flip it.
 */
enum class ReminderScope {
    /** Lives under the pairing. Both people read, write and complete these. */
    Shared,

    /** Lives under the user. Nobody else can read it, including the partner. */
    Private,
}

/** How much something matters, in the only three steps anyone actually uses. */
enum class Priority { Low, Medium, High }

data class Reminder(
    @DocumentId val id: String = "",
    val title: String = "",
    val note: String = "",
    val dueAt: Date? = null,

    /**
     * Whether [dueAt] carries a time or only a date.
     *
     * A date-only reminder is overdue at the end of its day; one with a time
     * is overdue at that moment. Without this flag every dated item would look
     * overdue from one minute past midnight.
     */
    val dueHasTime: Boolean = false,

    /** 0 low, 1 medium, 2 high. Stored as a number so it sorts in a query. */
    val priorityValue: Int = 1,

    /**
     * Manual position in the list.
     *
     * Defaults to the creation time so a new item lands at the top without
     * anything having to be renumbered; dragging rewrites it.
     */
    val orderIndex: Long = 0L,

    /**
     * Rings like an incoming call, on both phones, whoever set it.
     *
     * The ordinary alarm is addressed to one person — its owner hears a
     * ringtone, the other only feels a buzz. This is the opposite: something
     * neither of you can afford to sleep through, so it uses the phone's call
     * ringtone and both phones ring in full.
     */
    val contactAlarm: Boolean = false,
    val done: Boolean = false,
    val createdBy: String = "",
    val completedBy: String? = null,
    val completedAt: Date? = null,
    @ServerTimestamp val createdAt: Date? = null,
) {
    val hasDueDate: Boolean get() = dueAt != null

    val priority: Priority
        get() = when {
            priorityValue >= 2 -> Priority.High
            priorityValue <= 0 -> Priority.Low
            else -> Priority.Medium
        }

    /**
     * Past due and still outstanding.
     *
     * A date without a time is not late until its day is over — marking
     * "Tuesday" overdue at 00:01 on Tuesday would be wrong and constant.
     */
    /**
     * The moment this should ring, or null if it never should.
     *
     * Only a reminder with an actual time rings. A date-only item has no
     * moment to ring at — waking someone at midnight because they wrote
     * "Tuesday" would be a bug, not a feature.
     */
    val alarmAtMillis: Long?
        get() = dueAt?.time?.takeIf { dueHasTime && !done }

    /**
     * Due today and still outstanding.
     *
     * Drives the warning tint on the row. Deliberately not the same question as
     * [isOverdue] — this is the day something lands, whether or not the hour
     * has passed, which is when it is worth catching the eye.
     */
    fun isDueToday(now: Date = Date()): Boolean {
        val due = dueAt ?: return false
        if (done) return false

        val today = Calendar.getInstance().apply { time = now }
        val target = Calendar.getInstance().apply { time = due }
        return today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    fun isOverdue(now: Date = Date()): Boolean {
        val due = dueAt ?: return false
        if (done) return false
        return if (dueHasTime) due.before(now) else endOfDay(due).before(now)
    }

    private fun endOfDay(date: Date): Date = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
    }.time
}

/**
 * A poke from one person to the other about a shared reminder.
 *
 * Stored as its own document rather than a field on the reminder so the Cloud
 * Function can trigger on creation. A field would mean triggering on every
 * update to the reminder and then working out which one was the nudge.
 */
data class Nudge(
    @DocumentId val id: String = "",
    val reminderId: String = "",
    val reminderTitle: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    @ServerTimestamp val createdAt: Date? = null,
)
