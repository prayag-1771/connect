package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId

/**
 * One thing that happens at a set time on a set day.
 *
 * Times are strings rather than numbers because that is what a timetable
 * photograph actually contains, and converting them would mean deciding what
 * "9-10" means on a page that never said am or pm. Sorting is done on the
 * string, which is correct as long as they are zero-padded - which is what the
 * extraction is told to produce.
 */
data class TimetableEntry(
    /**
     * Identity, so editing one slot does not have to guess which it was.
     *
     * Two lectures can share a day, a time and a name across different weeks,
     * so matching on content would sometimes edit the wrong one. Generated when
     * a slot is created, whether by hand or by reading an image.
     */
    val id: String = "",

    /** Full English day name, so it can be matched without a lookup table. */
    val day: String = "",
    val start: String = "",
    val end: String = "",
    val title: String = "",
    val location: String = "",
) {
    val isUsable: Boolean get() = day.isNotBlank() && title.isNotBlank()
}

/**
 * Somebody's week, read off a photograph.
 *
 * One per person rather than one shared, because two people have two
 * timetables - the point of showing them together is seeing where they differ.
 */
data class Timetable(
    @DocumentId val uid: String = "",
    val entries: List<TimetableEntry> = emptyList(),
    val updatedAtMillis: Long = 0L,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Whether something is happening right now.
     *
     * An entry with no end time counts as busy for an hour, which is the
     * commonest length and better than treating it as instantaneous - a lecture
     * whose end nobody wrote down is not over the moment it starts.
     */
    fun isBusyNow(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val day = DAYS.getOrNull((now.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7)
            ?: return false
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)

        return entriesOn(day).any { entry ->
            val from = entry.start.toMinutes() ?: return@any false
            val until = entry.end.toMinutes() ?: (from + DEFAULT_LENGTH)
            minute in from until until
        }
    }

    fun entriesOn(day: String): List<TimetableEntry> =
        entries.filter { it.day.equals(day, ignoreCase = true) }.sortedBy { it.start }

    companion object {
        /** A slot with no stated end is treated as an hour. */
        private const val DEFAULT_LENGTH = 60

        private fun String.toMinutes(): Int? {
            val parts = split(":")
            if (parts.size < 2) return null
            val h = parts[0].trim().toIntOrNull() ?: return null
            val m = parts[1].trim().toIntOrNull() ?: return null
            return h * 60 + m
        }

        /** Monday first, because that is how a week is read. */
        val DAYS = listOf(
            "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday", "Sunday",
        )
    }
}
