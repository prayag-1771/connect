package com.obsidian.connect.messaging

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.work.MomentDownloadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the push that tells this device a new photo is waiting.
 *
 * Messages arrive as data-only payloads on purpose. A notification payload gets
 * swallowed by the system tray whenever the app is backgrounded and this class
 * never runs at all — which is precisely the case the widget exists for.
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
            TYPE_MOMENT -> {
                val storagePath = message.data[KEY_STORAGE_PATH] ?: return
                MomentDownloadWorker.enqueue(
                    context = applicationContext,
                    storagePath = storagePath,
                    caption = message.data[KEY_CAPTION].orEmpty(),
                    senderName = message.data[KEY_SENDER_NAME].orEmpty(),
                )
            }

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
        const val KEY_STORAGE_PATH = "storagePath"
        const val KEY_CAPTION = "caption"
        const val KEY_SENDER_NAME = "senderName"

        const val KEY_REMINDER_ID = "reminderId"

        const val TYPE_MOMENT = "moment"
        const val TYPE_NUDGE = "nudge"
    }
}
