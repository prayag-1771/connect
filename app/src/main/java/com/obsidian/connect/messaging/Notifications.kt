package com.obsidian.connect.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.obsidian.connect.MainActivity
import com.obsidian.connect.R

object Notifications {

    const val CHANNEL_NUDGES = "nudges"

    /**
     * Creates the channels the app posts to.
     *
     * Safe to call repeatedly — creating a channel that already exists is a
     * no-op, and the id must match the channelId the Cloud Function sends or
     * the notification is dropped on Android 8 and above.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_NUDGES,
            "Nudges",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "When the person you're paired with pokes you about a reminder"
            enableVibration(true)
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * Posts a nudge.
     *
     * Only needed while the app is in the foreground. Backgrounded, the system
     * tray handles the notification payload itself and this never runs.
     */
    fun showNudge(context: Context, title: String, body: String, tag: String) {
        if (!canPost(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // Tagging by reminder means repeated pokes about the same thing replace
        // each other rather than stacking into a wall of notifications.
        NotificationManagerCompat.from(context).notify(tag, NUDGE_NOTIFICATION_ID, notification)
    }

    /**
     * From Android 13 posting needs a granted runtime permission, and calling
     * notify() without it throws rather than failing quietly.
     */
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private const val NUDGE_NOTIFICATION_ID = 1001
}
