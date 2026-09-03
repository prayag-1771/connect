package com.obsidian.connect.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Makes the noise, or only the buzz.
 *
 * Who hears what is the whole design here. An ordinary reminder belongs to one
 * person: they get a ringtone, and the other person gets a vibration and the
 * ripples so they know something is due without being made to deal with it.
 * A contact alarm is the exception — it rings in full on both phones, using
 * the call ringtone, because it exists for the things neither of you can miss.
 *
 * One player at a time. Two alarms landing on the same minute talking over
 * each other would be noise rather than information.
 */
object AlarmRinger {

    private var player: MediaPlayer? = null

    /** How loudly this phone should announce a given alarm. */
    enum class Volume {
        /** Ringtone plus vibration — this alarm is addressed to you. */
        Full,

        /** Vibration only — it is the other person's to act on. */
        Silent,
    }

    fun start(context: Context, volume: Volume, callRingtone: Boolean) {
        stop(context)
        vibrate(context, volume)
        if (volume == Volume.Silent) return

        // The call ringtone for a contact alarm, the alarm tone otherwise —
        // the point of the toggle is that it sounds like something you would
        // pick up for, not like a to-do list.
        val type = if (callRingtone) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM
        val uri = RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        player = runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM so it is audible through Do Not Disturb
                        // and rides the alarm volume rather than media.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun vibrate(context: Context, volume: Volume) {
        val vibrator = vibrator(context) ?: return

        // A long insistent pattern for an alarm you must answer; a short double
        // tap for one that is only telling you the other person has something
        // due. The difference should be legible through a pocket.
        val pattern = if (volume == Volume.Full) {
            longArrayOf(0, 800, 400, 800, 400, 800)
        } else {
            longArrayOf(0, 180, 120, 180)
        }

        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun stop(context: Context) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator(context)?.cancel() }
    }
}
