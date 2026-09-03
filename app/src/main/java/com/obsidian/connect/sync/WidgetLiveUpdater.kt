package com.obsidian.connect.sync

import android.content.Context
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.MomentRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.ReminderRepository
import com.obsidian.connect.core.data.StrokeRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.alarm.ReminderAlarmScheduler
import com.obsidian.connect.core.model.Moment
import com.obsidian.connect.core.model.ReminderScope
import com.obsidian.connect.widget.DrawingBubble
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps everything current while the app is actually open.
 *
 * The periodic worker has a fifteen-minute floor, which is a long time to wait
 * for something the other person just did while you are sitting in the app.
 * Firestore listeners cost nothing extra here — the process is already running
 * — and close that gap to a round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WidgetLiveUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val momentRepository: MomentRepository,
    private val messageRepository: MessageRepository,
    private val strokeRepository: StrokeRepository,
    private val reminderRepository: ReminderRepository,
    private val syncState: SyncState,
) {

    /** The signed-in user's pairing, and who is on the other side of it. */
    private fun pairing(): Flow<Pair<String, String>?> = authRepository.uidFlow
        .flatMapLatest { uid ->
            if (uid == null) flowOf(null) else userRepository.observe(uid).map { it?.pairingId to uid }
        }
        .map { it?.takeIf { (pairingId, _) -> pairingId != null } }
        .distinctUntilChanged()
        .flatMapLatest { pair ->
            val (pairingId, uid) = pair ?: return@flatMapLatest flowOf(null)
            pairingRepository.observe(pairingId!!)
                .map { it?.partnerOf(uid!!) }
                .distinctUntilChanged()
                .map { partner -> partner?.let { pairingId to it } }
        }

    /** Collect for as long as the UI is alive. Never completes on its own. */
    suspend fun watchMoments() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) {
                    flowOf(null as Moment? to "")
                } else {
                    val (pairingId, partnerId) = current
                    combine(
                        momentRepository.observeLatestFrom(pairingId, partnerId),
                        userRepository.observe(partnerId).map { it?.displayName.orEmpty() },
                    ) { moment, name -> moment to name }
                }
            }
            .collect { (moment, partnerName) ->
                if (moment == null || moment.id == syncState.lastMomentId) return@collect

                val bytes = moment.bytes ?: return@collect
                val saved = PhotoArchive.save(
                    context = context,
                    jpeg = bytes,
                    origin = PhotoArchive.Origin.Received,
                    id = moment.id,
                )
                MomentWidgetUpdater.show(
                    context = context,
                    jpeg = bytes,
                    caption = moment.caption,
                    senderName = partnerName,
                )
                syncState.lastMomentId = moment.id

                // Same as the scheduled sync: once it is on disk and on the
                // widget, the copy in Firestore is erased.
                if (saved.exists()) momentRepository.clearImage(moment.id)
            }
    }

    /**
     * Keeps the phone's alarm clock in step with both reminder lists.
     *
     * Watched rather than set at the moment of writing, because a shared
     * deadline can be added or retimed from the *other* phone — and that phone
     * cannot reach into this one's AlarmManager. Each device schedules from
     * its own copy, which is also what lets a deadline ring hours later with
     * the app long since closed.
     */
    suspend fun watchReminderAlarms() {
        authRepository.uidFlow
            .flatMapLatest { uid ->
                if (uid == null) {
                    flowOf(emptyList())
                } else {
                    // Both lists feed one schedule. A private deadline rings
                    // exactly like a shared one; the only difference is that
                    // nobody else is holding a copy of it.
                    userRepository.observe(uid)
                        .map { it?.pairingId }
                        .distinctUntilChanged()
                        .flatMapLatest { pairingId ->
                            val private = reminderRepository.observe(ReminderScope.Private, uid)
                            if (pairingId == null) {
                                private
                            } else {
                                combine(
                                    private,
                                    reminderRepository.observe(ReminderScope.Shared, pairingId),
                                ) { mine, ours -> mine + ours }
                            }
                        }
                }
            }
            .collect { reminders -> ReminderAlarmScheduler.sync(context, reminders) }
    }

    /**
     * Drives the green dot on the watch face, live.
     *
     * This is what the dot was missing. Unread was only ever recomputed inside
     * the fifteen-minute worker, so a message could sit unannounced for a
     * quarter of an hour — and if you happened to open and read the chat before
     * the next run, the dot never appeared at all. That is why it looked like
     * it worked once and then stopped.
     *
     * The watermark is not advanced here. Showing the dot is not reading the
     * message; only opening the chat does that.
     */
    suspend fun watchMessages() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) {
                    flowOf(emptyList())
                } else {
                    messageRepository.observeRecent(current.first)
                }
            }
            .collect { recent ->
                val uid = authRepository.currentUid ?: return@collect

                val newestFromPartner = recent
                    .filter { it.senderId != uid }
                    .maxOfOrNull { it.createdAtMillis }
                    ?: return@collect

                val unread = newestFromPartner > syncState.lastReadMessageAt

                // A redraw costs a bitmap render and a Binder round trip to the
                // launcher, so only when the answer actually changes.
                if (unread == WidgetCaptionStore.hasUnread(context)) return@collect

                WidgetCaptionStore.writeUnread(context, unread)
                MomentWidgetUpdater.refresh(context)
            }
    }

    /**
     * Raises the drawing indicator the moment a stroke arrives.
     *
     * Without this a drawing only registered on the next scheduled sync, which
     * made the light look like it fired once and then stopped — it was simply
     * up to fifteen minutes behind.
     */
    suspend fun watchDrawings() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) flowOf(emptyList()) else strokeRepository.observe(current.first)
            }
            .collect { strokes ->
                val uid = authRepository.currentUid ?: return@collect
                val newest = strokes
                    .filter { it.senderId != uid }
                    .maxOfOrNull { it.createdAtMillis }
                    ?: return@collect

                if (newest <= syncState.lastSeenStrokeAt) return@collect

                // Not marked seen here — that happens when the drawing is
                // actually looked at, so the light survives until then.
                WidgetCaptionStore.writeNewDrawing(context, true)
                withContext(Dispatchers.Main) { DrawingBubble.show(context) }
                WatchWidgetProvider.refreshAll(context)
            }
    }
}
