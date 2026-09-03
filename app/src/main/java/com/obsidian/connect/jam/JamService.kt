package com.obsidian.connect.jam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.obsidian.connect.MainActivity
import com.obsidian.connect.R

/**
 * Keeps a jam playing once the app is off the screen.
 *
 * Without this the music stops shortly after you leave, and not because the
 * player fails - Android stops giving audio to a process nobody is looking at,
 * and reclaims it entirely under any memory pressure. A foreground service is
 * the only supported way to say "this is playing music, leave it alone", and
 * the notification it requires is a fair trade: something making sound in the
 * background should be visible and stoppable.
 *
 * It does no playing itself. The player lives in [JamPlayerHolder]; this exists
 * so the platform allows it to keep going.
 */
class JamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        ensureChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title.ifBlank { "Listening together" })
            .setContentText("Jam is playing")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(ID, notification)
        }

        // Not sticky: a restarted service with no jam behind it would be a
        // notification for music that is not playing.
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Jam",
                // Low: it is a standing note that music is on, not news.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL = "jam_playback"
        private const val ID = 4313
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, title: String) {
            val intent = Intent(context, JamService::class.java)
                .putExtra(EXTRA_TITLE, title)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, JamService::class.java)) }
        }
    }
}
