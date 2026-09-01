package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
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

data class Reminder(
    @DocumentId val id: String = "",
    val title: String = "",
    val note: String = "",
    val dueAt: Date? = null,
    val done: Boolean = false,
    val createdBy: String = "",
    val completedBy: String? = null,
    val completedAt: Date? = null,
    @ServerTimestamp val createdAt: Date? = null,
) {
    val hasDueDate: Boolean get() = dueAt != null

    /** Past due and still outstanding. */
    fun isOverdue(now: Date = Date()): Boolean =
        !done && dueAt != null && dueAt.before(now)
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
