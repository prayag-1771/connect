package com.obsidian.connect.lock

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Opens whatever this phone calls its clock.
 *
 * Used by the widget outside its active hours, and by the decoy PIN. Finding it
 * is more awkward than it sounds: SHOW_ALARMS resolves to a stub on some
 * phones that opens nothing at all, so the package is resolved first and then
 * launched by its own launcher intent.
 */
object ClockApp {

    private val KNOWN = listOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.oneplus.deskclock",
        "com.oplus.alarmclock",
        "com.coloros.alarmclock",
        "com.sec.android.app.clockpackage",
        "com.miui.clock",
        "com.motorola.timeweatherwidget",
    )

    fun intent(context: Context): Intent? {
        val pm = context.packageManager

        val candidates = buildList {
            pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
                ?.activityInfo
                ?.packageName
                ?.let(::add)
            addAll(KNOWN)
        }

        candidates.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return showAlarms.takeIf { pm.resolveActivity(it, 0) != null }
    }

    /** Returns false when this phone has no clock worth opening. */
    fun open(context: Context): Boolean {
        val intent = intent(context) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
