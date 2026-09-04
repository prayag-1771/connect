package com.obsidian.connect.sync

import android.content.Context
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.MomentRepository
import com.obsidian.connect.core.data.CallRepository
import com.obsidian.connect.core.data.ChoiceRepository
import com.obsidian.connect.core.data.JamChatRepository
import com.obsidian.connect.core.data.JamRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.ReminderRepository
import com.obsidian.connect.core.data.StrokeRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.alarm.ReminderAlarmScheduler
import com.obsidian.connect.call.CallActivity
import com.obsidian.connect.core.model.CallState
import com.obsidian.connect.core.model.JamSession
import com.obsidian.connect.core.model.QueueItem
import com.obsidian.connect.jam.YouTubeSearch
import com.obsidian.connect.jam.JamPlayerHolder
import com.obsidian.connect.jam.JamRequestGate
import com.obsidian.connect.jam.JamService
import com.obsidian.connect.core.model.Moment
import com.obsidian.connect.core.model.ReminderScope
import com.obsidian.connect.widget.DrawingBubble
import com.obsidian.connect.widget.MomentWidgetUpdater
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
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
    private val callRepository: CallRepository,
    private val choiceRepository: ChoiceRepository,
    private val jamRepository: JamRepository,
    private val jamChatRepository: JamChatRepository,
    private val syncState: SyncState,
) {

    /**
     * For work that starts from a callback rather than from a collector.
     *
     * The player reports a finished track through a listener, which has no
     * coroutine of its own to write the result back on. This singleton lives as
     * long as the process, so the scope can too.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * Lights the yellow dot when a card is put up for you to judge.
     *
     * Only for cards the other person added - your own are not news to you.
     * The watermark is not advanced here: showing the dot is not the same as
     * having looked, and advancing it on show is what made the drawing light
     * appear exactly once and never again. Opening the deck is what clears it.
     */
    suspend fun watchChoices() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) flowOf(emptyList()) else choiceRepository.observe(current.first)
            }
            .collect { choices ->
                val uid = authRepository.currentUid ?: return@collect

                val newest = choices
                    .filter { it.addedBy != uid }
                    .maxOfOrNull { it.createdAtMillis }
                    ?: return@collect

                if (newest <= syncState.lastSeenChoiceAt) return@collect
                if (WidgetCaptionStore.hasNewChoice(context)) return@collect

                WidgetCaptionStore.writeNewChoice(context, true)
                MomentWidgetUpdater.refresh(context)
            }
    }

    /**
     * Keeps the music playing, wherever you are in the app.
     *
     * Watched here rather than from the jam screen, because the jam screen is
     * exactly what a person leaves when they go back to the chat. Anything
     * driving playback from inside it would stop the moment they did.
     *
     * The player is only released when the session ends, which is the one
     * thing that actually means "stop".
     */
    suspend fun watchJam() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) {
                    flowOf(null)
                } else {
                    // The pairing id is carried through, because ending a track
                    // needs to write back and the session itself does not say
                    // which pairing it belongs to.
                    jamRepository.observe(current.first).map { current.first to it }
                }
            }
            .collect { pair ->
                val pairingId = pair?.first
                val session = pair?.second

                if (pairingId == null || session == null || !session.isLoaded) {
                    withContext(Dispatchers.Main) {
                        JamPlayerHolder.release(context)
                        JamService.stop(context)
                    }
                    return@collect
                }

                // Spotify plays in its own app; there is nothing here to drive.
                if (!session.isFor(JamSession.YOUTUBE)) return@collect

                // Only for someone who opened the jam. Starting a track should
                // not make sound come out of the other person's phone while it
                // is in their pocket - they join by opening it, and until then
                // this is none of their phone's business.
                val uid = authRepository.currentUid
                if (uid == null || uid !in session.listeners) {
                    withContext(Dispatchers.Main) {
                        JamPlayerHolder.release(context)
                        JamService.stop(context)
                    }
                    return@collect
                }

                withContext(Dispatchers.Main) {
                    // A track running out is the one state change nothing else
                    // reports. Without this the session goes on claiming to be
                    // playing, and the pause button in the chat offers to stop
                    // silence.
                    // Only the driving phone advances. Both reacting would
                    // race, and two writes of "next" would skip a track.
                    JamPlayerHolder.onFinished = {
                        if (session.byUid == uid) {
                            scope.launch { advance(pairingId, uid, session) }
                        }
                    }

                    // Started before the player, so the platform already knows
                    // this process is making sound by the time it does.
                    // Kept running while paused rather than stopped, so the
                    // lock screen controls stay there to press play with. It
                    // goes only when the jam does.
                    JamService.start(context, session.title, session.playing)

                    if (session.videoId != JamPlayerHolder.loadedVideoId) {
                        JamPlayerHolder.load(
                            context = context,
                            videoId = session.videoId,
                            startMs = session.expectedPositionMs(),
                            play = session.playing,
                        )
                    } else {
                        // Correcting drift, and carrying a seek across.
                        //
                        // This was lost when the player moved out of the jam
                        // screen: only play and pause were being applied, so
                        // dragging the bar moved one phone and not the other,
                        // and the two slid apart over a long track with nothing
                        // to pull them back.
                        val target = session.expectedPositionMs()
                        if (abs(target - JamPlayerHolder.lastPositionMs) > DRIFT_TOLERANCE_MS) {
                            JamPlayerHolder.seekTo(context, target)
                        }

                        if (session.playing) {
                            JamPlayerHolder.play(context)
                        } else {
                            JamPlayerHolder.pause(context)
                        }
                    }
                }
            }
    }

    /**
     * Plays whatever is next when a track runs out.
     *
     * The queue first, and failing that something in the same territory that
     * has not already played this session - so a jam left alone carries on
     * rather than falling silent, and never loops the song it just finished.
     */
    private suspend fun advance(pairingId: String, uid: String, session: JamSession) {
        val played = session.playedIds + session.videoId

        val next = session.queue.firstOrNull()
            ?: YouTubeSearch.similar(session.title, exclude = played)
                .firstOrNull()
                ?.let { QueueItem(it.videoId, it.title) }

        if (next == null) {
            jamRepository.update(pairingId, uid, false, 0L)
            return
        }

        jamRepository.advance(
            pairingId = pairingId,
            uid = uid,
            next = next,
            remainingQueue = session.queue.drop(1),
            played = played,
        )
    }

    /**
     * Notices somebody waiting in a jam chat.
     *
     * Watched app-wide rather than from the jam screen, because the whole point
     * is reaching somebody who is not looking at it - in another tab, or with
     * the app shut and only the widget on screen.
     */
    suspend fun watchJamChat() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) flowOf(null) else jamChatRepository.observeRoom(current.first)
            }
            .collect { room ->
                val uid = authRepository.currentUid
                val waiting = uid != null && room != null && room.isWaitingFor(uid)

                if (waiting) {
                    // Asked in the app if it is open, and marked on the face if
                    // it is not. Both end in the same dialog.
                    JamRequestGate.raiseIfNew(room.requestedAtMillis)
                }

                if (waiting == WidgetCaptionStore.hasJamRequest(context)) return@collect
                WidgetCaptionStore.writeJamRequest(context, waiting)
                MomentWidgetUpdater.refresh(context)
            }
    }

    /**
     * Answers the door when the other phone calls.
     *
     * The call document is the only thing that says a call is happening, and
     * nothing else is watching it - without this the ringing side would wait
     * forever for someone who never knew.
     *
     * Only rings for calls this phone did not place, and only while the call is
     * still ringing: an answered call has already been dealt with, on whichever
     * screen dealt with it.
     */
    suspend fun watchCalls() {
        pairing()
            .flatMapLatest { current ->
                if (current == null) flowOf(null) else callRepository.observe(current.first)
            }
            .collect { call ->
                val uid = authRepository.currentUid ?: return@collect
                if (call == null) return@collect
                if (call.state != CallState.Ringing) return@collect
                if (call.isMine(uid)) return@collect
                if (call.offer.isBlank()) return@collect

                withContext(Dispatchers.Main) { CallActivity.answer(context) }
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
                    flowOf(null)
                } else {
                    messageRepository.observeRecent(current.first)
                        .map { current.first to it }
                }
            }
            .collect { pair ->
                val (pairingId, recent) = pair ?: return@collect
                val uid = authRepository.currentUid ?: return@collect

                val newestFromPartner = recent
                    .filter { it.senderId != uid }
                    .maxOfOrNull { it.createdAtMillis }
                    ?: return@collect

                // Delivered means the message is on this phone, which is true
                // right now whether or not anyone has the chat open. Without
                // this the only thing that ever moved a watermark was opening
                // the conversation - which also marks it seen - so the middle
                // state could never be reached and a message went straight
                // from sent to seen.
                //
                // Seen is deliberately not touched here. Receiving something
                // is not reading it.
                messageRepository.markProgress(
                    pairingId = pairingId,
                    uid = uid,
                    deliveredAtMillis = newestFromPartner,
                )

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

/**
 * How far the two phones may drift before it is worth correcting.
 *
 * Below this nobody can hear the difference, and seeking to fix it stutters
 * the audio worse than the drift did.
 */
private const val DRIFT_TOLERANCE_MS = 1_500L
