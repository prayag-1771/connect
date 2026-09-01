package com.obsidian.connect.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Renders a due date the way someone glancing at a list wants to read it.
 *
 * "Tomorrow" carries more meaning at a glance than "3 Sep", and the year only
 * earns its space when the date isn't in the current one.
 */
object DueDateFormat {

    private val sameYear = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun label(due: Date, today: LocalDate = LocalDate.now()): String {
        val date = due.toLocalDate()
        val days = today.until(date).days
        return when {
            date == today -> "Today"
            date == today.plusDays(1) -> "Tomorrow"
            date == today.minusDays(1) -> "Yesterday"
            date.isBefore(today) -> "${today.toEpochDay() - date.toEpochDay()} days ago"
            days in 2..6 || date.isBefore(today.plusWeeks(1)) -> date.format(sameYear)
            date.year == today.year -> date.format(sameYear)
            else -> date.format(otherYear)
        }
    }

    fun Date.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
}
