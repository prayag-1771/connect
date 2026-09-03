package com.obsidian.connect.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.widget.WidgetSchedule
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A deadline arriving.
 *
 * Decides how loud this particular phone should be about it. An ordinary
 * reminder belongs to whoever wrote it: they get the ringtone, the other
 * person gets the buzz and the ripples. A contact alarm ignores that and rings
 * on both, which is the entire point of the toggle.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var authRepository: AuthRepository

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_TITLE).orEmpty()
        val owner = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_OWNER).orEmpty()
        val contact = intent.getBooleanExtra(ReminderAlarmScheduler.EXTRA_CONTACT, false)

        // Disable and the active window silence everything, including this.
        // Someone who has switched the face off has said they do not want to
        // be reached, and an alarm that talks over that is not a feature.
        if (WidgetSchedule.isDisabled(context)) return
        if (WidgetSchedule.isEnabled(context) && !WidgetSchedule.isActive(context)) return

        val me = authRepository.currentUid
        val mine = owner.isBlank() || owner == me

        val volume = when {
            contact -> AlarmRinger.Volume.Full
            mine -> AlarmRinger.Volume.Full
            else -> AlarmRinger.Volume.Silent
        }

        AlarmRinger.start(context, volume, callRingtone = contact)

        // A window cannot be added from a receiver's thread pool, and this
        // arrives on the main thread already — but the overlay is the kind of
        // thing worth being explicit about.
        Handler(Looper.getMainLooper()).post {
            AlarmBubble.show(
                context = context,
                title = title.ifBlank { "Reminder" },
                subtitle = if (volume == AlarmRinger.Volume.Silent) {
                    "Their reminder is due"
                } else {
                    "Due now - tap to dismiss"
                },
            )
        }
    }
}
