package com.obsidian.connect.widget

import android.content.Context
import java.util.Calendar

/**
 * The window during which the watch face shows anything personal.
 *
 * Outside it the widget is deliberately just a clock: no photo, no caption, no
 * unread dot, and tapping it opens the phone's clock app rather than Connect.
 * The point is that a face on your home screen all day should not be showing
 * someone you love to whoever happens to glance at your phone in a meeting.
 *
 * Times are minutes from midnight, which sidesteps time zones and DST — the
 * window means "between these numbers on the clock in front of you", not an
 * instant on a timeline.
 */
object WidgetSchedule {

    private const val PREFS = "connect_widget_schedule"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_START = "start_minute"
    private const val KEY_END = "end_minute"
    private const val KEY_DAYS = "days"
    private const val KEY_ARMED_ON = "armed_on"

    /** 18:00 to 23:00 — evening, when a photo of someone is most wanted. */
    private const val DEFAULT_START = 18 * 60
    private const val DEFAULT_END = 23 * 60

    /**
     * Monday to Friday by default.
     *
     * Calendar numbers days from Sunday as 1, which is why these look
     * arbitrary: 2 through 6 is Monday through Friday.
     */
    private val DEFAULT_DAYS = setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Off by default: the widget is a photo frame until someone says otherwise. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * A manual override that outranks the schedule entirely.
     *
     * Separate from the hourly window rather than folded into it, because the
     * two answer different questions. The window is a standing arrangement;
     * this is "not right now", and it holds until it is switched back on
     * rather than until the clock passes some hour.
     */
    fun isDisabled(context: Context): Boolean = prefs(context).getBoolean(KEY_DISABLED, false)

    fun setDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLED, disabled).apply()
    }

    /**
     * The days the window applies on.
     *
     * Stored as strings because SharedPreferences has a string set and no int
     * set. An empty stored value means every day was deselected, which is a
     * real choice and must not silently fall back to the default.
     */
    fun days(context: Context): Set<Int> {
        val stored = prefs(context).getStringSet(KEY_DAYS, null) ?: return DEFAULT_DAYS
        return stored.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun startMinute(context: Context): Int = prefs(context).getInt(KEY_START, DEFAULT_START)

    fun endMinute(context: Context): Int = prefs(context).getInt(KEY_END, DEFAULT_END)

    fun save(
        context: Context,
        enabled: Boolean,
        startMinute: Int,
        endMinute: Int,
        days: Set<Int> = days(context),
    ) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_START, startMinute.coerceIn(0, 1439))
            .putInt(KEY_END, endMinute.coerceIn(0, 1439))
            .putStringSet(KEY_DAYS, days.map { it.toString() }.toSet())
            .apply()
    }

    /**
     * The day this was last deliberately turned on for.
     *
     * Stored as a day number rather than a flag, so it expires by itself. A
     * boolean would need something to come along and clear it; a date is simply
     * no longer today tomorrow.
     */
    fun armedDay(context: Context): Int = prefs(context).getInt(KEY_ARMED_ON, -1)

    fun armForToday(context: Context) {
        prefs(context).edit().putInt(KEY_ARMED_ON, todayNumber()).apply()
        setDisabled(context, false)
    }

    fun isArmedForToday(context: Context): Boolean = armedDay(context) == todayNumber()

    /** Year and day together, so the same date next year is not mistaken for today. */
    private fun todayNumber(): Int = Calendar.getInstance().let {
        it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR)
    }

    /** Whether the face should currently show anything beyond the time. */
    fun isActive(context: Context, nowMinute: Int = currentMinute()): Boolean {
        // Checked first: a manual switch-off means off, whatever the hours say.
        if (isDisabled(context)) return false

        // Being somewhere you said to keep it off outranks the schedule too.
        // The hours are about when you usually want it; this is about where you
        // actually are, which is the better answer when the two disagree.
        if (PlaceGuard.isAtSavedPlace(context)) return false
        if (!isEnabled(context)) return true

        // A day that is not selected is off for the whole of it, regardless of
        // the hours - the hours describe when, the days describe whether.
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        if (today !in days(context)) return false

        return contains(startMinute(context), endMinute(context), nowMinute)
    }

    /**
     * Handles windows that wrap past midnight.
     *
     * 22:00 to 07:00 is a perfectly reasonable thing to ask for, and a naive
     * `start <= now && now < end` silently matches nothing for the whole of it.
     */
    fun contains(start: Int, end: Int, now: Int): Boolean = when {
        start == end -> true // A zero-length window reads as "always".
        start < end -> now >= start && now < end
        else -> now >= start || now < end
    }

    /**
     * Minutes until the window next opens or closes.
     *
     * Used to schedule the redraw that flips the face over. Always at least one
     * minute, so a boundary landing exactly now cannot schedule a zero-delay
     * alarm and spin.
     */
    fun minutesUntilNextBoundary(context: Context, nowMinute: Int = currentMinute()): Int {
        // Nothing to wake for while switched off — only a tap turns it back on.
        if (isDisabled(context)) return MINUTES_PER_DAY
        if (!isEnabled(context)) return MINUTES_PER_DAY

        // On an unselected day the next thing that changes anything is
        // midnight, so there is nothing to wake for before then.
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        if (today !in days(context)) return MINUTES_PER_DAY - nowMinute

        val start = startMinute(context)
        val end = endMinute(context)
        if (start == end) return MINUTES_PER_DAY

        val toStart = Math.floorMod(start - nowMinute, MINUTES_PER_DAY)
        val toEnd = Math.floorMod(end - nowMinute, MINUTES_PER_DAY)
        return listOf(toStart, toEnd).filter { it > 0 }.minOrNull() ?: MINUTES_PER_DAY
    }

    fun currentMinute(): Int = Calendar.getInstance().let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    fun format(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    const val MINUTES_PER_DAY = 24 * 60
}
