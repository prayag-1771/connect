package com.obsidian.connect.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MomentRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.ReminderRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.messaging.Notifications
import com.obsidian.connect.widget.MomentWidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Pulls anything new from Firestore and applies it locally.
 *
 * This exists because Cloud Functions need a paid plan. Sending a push to
 * another user's device requires credentials that cannot ship inside an app,
 * so with no server in the middle the receiving device has to come looking
 * instead of being told.
 *
 * The cost is latency. Run from the app or on boot it is immediate; run on
 * WorkManager's periodic schedule the floor is fifteen minutes.
 */
@HiltWorker
class ConnectSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val momentRepository: MomentRepository,
    private val reminderRepository: ReminderRepository,
    private val syncState: SyncState,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uid = authRepository.currentUid ?: return@withContext Result.success()

        val user = runCatching { userRepository.get(uid) }.getOrNull()
        val pairingId = user?.pairingId ?: return@withContext Result.success()

        val pairing = runCatching { pairingRepository.get(pairingId) }.getOrNull()
        // Normal while an invite is still outstanding — nothing to sync yet.
        val partnerId = pairing?.partnerOf(uid) ?: return@withContext Result.success()

        val partnerName = runCatching { userRepository.get(partnerId)?.displayName }
            .getOrNull()
            .orEmpty()

        val outcome = runCatching {
            syncWidget(pairingId, partnerId, partnerName)
            syncNudges(pairingId, uid)
        }

        // Retry covers a flaky connection. There is always another scheduled
        // run behind this one, so failing is not losing anything permanently.
        if (outcome.isSuccess) Result.success() else Result.retry()
    }

    private suspend fun syncWidget(pairingId: String, partnerId: String, partnerName: String) {
        val latest = momentRepository.latestFrom(pairingId, partnerId) ?: return

        // Already on the widget. Skipping matters — redrawing costs a bitmap
        // decode and a Binder round trip to the launcher for no visible change.
        if (latest.id == syncState.lastMomentId) return

        val bytes = latest.bytes ?: return
        MomentWidgetUpdater.show(
            context = applicationContext,
            jpeg = bytes,
            caption = latest.caption,
            senderName = partnerName,
        )
        syncState.lastMomentId = latest.id
    }

    private suspend fun syncNudges(pairingId: String, uid: String) {
        val since = syncState.lastNudgeAt
        val nudges = reminderRepository.nudgesSince(pairingId, uid, since)
        if (nudges.isEmpty()) return

        nudges.forEach { nudge ->
            Notifications.showNudge(
                context = applicationContext,
                title = "You were nudged",
                body = nudge.reminderTitle.ifEmpty { "About something on your shared list" },
                tag = nudge.reminderId.ifEmpty { nudge.id },
            )
        }

        // Watermark from the newest nudge's own timestamp, not from the clock.
        // Using the clock would silently skip anything written while this sync
        // was in flight.
        syncState.lastNudgeAt = nudges.mapNotNull { it.createdAt }.maxOrNull() ?: Date()
    }
}
