package com.obsidian.connect.core.data

import com.google.firebase.firestore.FirebaseFirestore
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.JamSession
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
                "updatedAtMillis" to System.currentTimeMillis(),
            ),
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
            com.google.firebase.firestore.SetOptions.merge(),
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
            mapOf("listeners" to com.google.firebase.firestore.FieldValue.arrayUnion(uid)),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }

    suspend fun leave(pairingId: String, uid: String): Result<Unit> = runCatching {
        session(pairingId).set(
            mapOf("listeners" to com.google.firebase.firestore.FieldValue.arrayRemove(uid)),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }

    suspend fun end(pairingId: String): Result<Unit> = runCatching {
        session(pairingId).delete().await()
    }

    private companion object {
        const val JAM = "jam"
        const val SESSION = "session"
    }
}
