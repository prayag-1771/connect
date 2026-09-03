package com.obsidian.connect.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.obsidian.connect.R

/**
 * Keeps the screen capture alive and visible.
 *
 * Android refuses to hand out screen frames unless a foreground service of type
 * mediaProjection is already running, and it insists on a notification so that
 * nobody can be recorded without a permanent sign that it is happening. Both of
 * those are the point rather than an obstacle.
 *
 * It does no work itself. Capture belongs to the call session; this exists so
 * the system allows the capture to continue while the app is in the background,
 * which is exactly when a screen share matters.
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Sharing your screen")
            .setContentText("Your screen is visible to them until you stop")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Screen sharing",
                // Low: the notification is a required disclosure, not news.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL = "screen_share"
        private const val ID = 4311

        /**
         * Must be running *before* the capture starts.
         *
         * Starting it afterwards raises a SecurityException on Android 10 and
         * later - the system checks for the service at the moment the
         * projection begins, not at some point during it.
         */
        fun start(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
