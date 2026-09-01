package com.obsidian.connect.messaging

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.sync.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives pushes, on the day there is anything to send them.
 *
 * Nothing currently does. Sending to another user's device needs a trusted
 * server, and Cloud Functions require a paid plan, so the app polls instead —
 * see ConnectSyncWorker.
 *
 * This is kept deliberately. A push here just triggers the same sync the
 * worker runs, so deploying the functions later turns polling into instant
 * delivery without touching the client.
 */
@AndroidEntryPoint
class ConnectMessagingService : FirebaseMessagingService() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var userRepository: UserRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fired when the token rotates — reinstall, restore to a new device, or
     * occasionally for no visible reason.
     *
     * A plain coroutine is enough here despite the service being killable. The
     * app writes its token on every launch too, so a write lost to an early
     * process death repairs itself the next time the app is opened.
     */
    override fun onNewToken(token: String) {
        val uid = authRepository.currentUid ?: return
        scope.launch {
            runCatching { userRepository.updateFcmToken(uid, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data[KEY_TYPE]) {
            TYPE_MOMENT -> SyncScheduler.now(applicationContext)

            TYPE_NUDGE -> {
                // Only reached with the app in the foreground. Backgrounded,
                // the system tray renders the notification payload itself and
                // this callback is never invoked.
                val notification = message.notification
                Notifications.showNudge(
                    context = applicationContext,
                    title = notification?.title ?: "Nudge",
                    body = notification?.body.orEmpty(),
                    tag = message.data[KEY_REMINDER_ID].orEmpty().ifEmpty { "nudge" },
                )
            }
        }
    }

    private companion object {
        const val KEY_TYPE = "type"

        const val KEY_REMINDER_ID = "reminderId"

        const val TYPE_MOMENT = "moment"
        const val TYPE_NUDGE = "nudge"
    }
}
