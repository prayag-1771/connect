package com.obsidian.connect.core.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.JamSession
import com.obsidian.connect.core.model.QueueItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing both phones are agreeing on.
 *
 * A fixed document rather than one per session, because two people can only be
 * listening to one thing together, and a fixed path means neither side has to
 * discover an id before it can start listening.
 *
 * Written on change only - a play, a pause, a seek, a new track. Never on a
 * position tick: a progress bar driven through Firestore would spend the daily
 * write allowance in an afternoon, and both players already know how to count
 * seconds on their own.
 */
@Singleton
class JamRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun session(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(JAM)
        .document(SESSION)

    fun observe(pairingId: String): Flow<JamSession?> = session(pairingId).asFlow()

    /**
     * Puts a track on for both of you, playing.
     *
     * Playing rather than cued. Pasting a link is asking for the song, not
     * asking for it to be lined up - and a player that sits silent after being
     * handed exactly what to play reads as broken rather than as considerate.
     */
    suspend fun load(
        pairingId: String,
        uid: String,
        videoId: String,
        title: String,
        service: String = JamSession.YOUTUBE,
    ): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf(
                "service" to service,
                "videoId" to videoId,
                "title" to title,
                "playing" to true,
                "positionMs" to 0L,
                "byUid" to uid,
                // Whoever puts a track on is listening to it. Also guards the
                // case where this is the first write and nobody has joined yet.
                "listeners" to FieldValue.arrayUnion(uid),
                "updatedAtMillis" to System.currentTimeMillis(),
            ),
            // Merge, not replace. A plain set wiped the listener list on every
            // new track, which took every phone straight back out of the jam it
            // had just joined - and silenced the one that started it.
            SetOptions.merge(),
        ).await()
    }

    /**
     * Records a play, pause or seek.
     *
     * [positionMs] is where the track is at the instant of writing. The other
     * phone adds the delay itself, so this must be the position now rather than
     * a guess at where it will be when the write lands.
     */
    suspend fun update(
        pairingId: String,
        uid: String,
        playing: Boolean,
        positionMs: Long,
    ): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf(
                "playing" to playing,
                "positionMs" to positionMs,
                "byUid" to uid,
                "updatedAtMillis" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * Says this phone has the jam open.
     *
     * arrayUnion rather than read-and-write, because both of you can arrive at
     * once and a read-modify-write would let one arrival erase the other.
     */
    suspend fun join(pairingId: String, uid: String): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf("listeners" to FieldValue.arrayUnion(uid)),
            SetOptions.merge(),
        ).await()
    }

    suspend fun leave(pairingId: String, uid: String): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf("listeners" to FieldValue.arrayRemove(uid)),
            SetOptions.merge(),
        ).await()
    }

    /** Adds a track to the end of the queue. */
    suspend fun enqueue(
        pairingId: String,
        item: QueueItem,
    ): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf(
                "queue" to FieldValue.arrayUnion(
                    mapOf("videoId" to item.videoId, "title" to item.title),
                ),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun dequeue(pairingId: String, item: QueueItem): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf(
                "queue" to FieldValue.arrayRemove(
                    mapOf("videoId" to item.videoId, "title" to item.title),
                ),
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * Writes a new running order.
     *
     * The whole list at once rather than a swap, because the queue is one field
     * on one document - there is no smaller thing to move.
     */
    suspend fun reorderQueue(
        pairingId: String,
        queue: List<QueueItem>,
    ): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf(
                "queue" to queue.map {
                    mapOf("videoId" to it.videoId, "title" to it.title)
                },
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * Moves to the next track, whatever it is.
     *
     * The whole session is written at once rather than the queue being popped
     * and the track set separately: those two happening apart would leave a
     * moment where the finished song is still current and its replacement is
     * already out of the queue.
     */
    suspend fun advance(
        pairingId: String,
        uid: String,
        next: QueueItem,
        remainingQueue: List<QueueItem>,
        played: List<String>,
    ): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf(
                "service" to JamSession.YOUTUBE,
                "videoId" to next.videoId,
                "title" to next.title,
                "playing" to true,
                "positionMs" to 0L,
                "byUid" to uid,
                "queue" to remainingQueue.map {
                    mapOf("videoId" to it.videoId, "title" to it.title)
                },
                "playedIds" to played.takeLast(PLAYED_MEMORY),
                "updatedAtMillis" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun end(pairingId: String): Result<Unit> = runCatching {
        session(pairingId).delete().await()
    }

    private companion object {
        const val JAM = "jam"
        const val SESSION = "session"

        /** Enough to stop a short session looping; not a permanent history. */
        const val PLAYED_MEMORY = 40
    }
}
