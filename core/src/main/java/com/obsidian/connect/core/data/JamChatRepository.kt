package com.obsidian.connect.core.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.JamChatMessage
import com.obsidian.connect.core.model.JamChatRoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The jam chat, which is thrown away rather than stored.
 *
 * Every write here is temporary by design. Ending the room deletes the messages
 * with it, so there is nothing to clear up later and nothing to find if anyone
 * goes looking - which is the point of it not being the ordinary chat.
 */
@Singleton
class JamChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun room(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(ROOMS)
        .document(CURRENT)

    private fun messages(pairingId: String) = room(pairingId).collection(MESSAGES)

    fun observeRoom(pairingId: String): Flow<JamChatRoom?> = room(pairingId).asFlow()

    fun observeMessages(pairingId: String): Flow<List<JamChatMessage>> =
        messages(pairingId)
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .limit(LIMIT)
            .asFlow()

    /** Opens the room and puts the opener in it, which doubles as the invite. */
    suspend fun start(pairingId: String, uid: String): Result<Unit> = runCatching {
        // Anything left from last time goes first. A room that reopened with
        // yesterday's messages in it would not be the ephemeral thing it claims.
        clearMessages(pairingId)

        room(pairingId).set(
            mapOf(
                "startedBy" to uid,
                "participants" to listOf(uid),
                "startedAtMillis" to System.currentTimeMillis(),
                // Zero, not now. Opening a room is not asking anybody to join
                // it - treating it as an invitation made the button read Asked
                // before anybody had pressed anything, and would have put a
                // dialog in front of the other person unprompted.
                "requestedAtMillis" to 0L,
                "declinedAtMillis" to 0L,
            ),
        ).await()
    }

    /**
     * Asks the other person to come in.
     *
     * Separate from opening the room, because the room may have been open for a
     * while by the time somebody thinks to invite them - and because asking
     * twice is a real thing to want to do.
     */
    suspend fun request(pairingId: String): Result<Unit> = runCatching {
        room(pairingId).set(
            mapOf("requestedAtMillis" to System.currentTimeMillis()),
            SetOptions.merge(),
        ).await()
    }

    /**
     * Records a no.
     *
     * The room is left exactly as it was - the other person is still sitting in
     * it, and turning down an invitation is not a reason to close it on them.
     * This only tells them the question was answered.
     */
    suspend fun decline(pairingId: String): Result<Unit> = runCatching {
        room(pairingId).set(
            mapOf("declinedAtMillis" to System.currentTimeMillis()),
            SetOptions.merge(),
        ).await()
    }

    suspend fun join(pairingId: String, uid: String): Result<Unit> = runCatching {
        room(pairingId).set(
            mapOf("participants" to FieldValue.arrayUnion(uid)),
            SetOptions.merge(),
        ).await()
    }

    suspend fun send(
        pairingId: String,
        uid: String,
        text: String,
        playedTitle: String,
    ): Result<Unit> = runCatching {
        messages(pairingId).add(
            mapOf(
                "senderId" to uid,
                "text" to text.trim().take(MAX_LENGTH),
                "playedTitle" to playedTitle,
                "createdAtMillis" to System.currentTimeMillis(),
            ),
        ).await()
    }

    /**
     * Ends it for both, and leaves nothing behind.
     *
     * Either person can call this. One of you leaving ends the room rather than
     * leaving the other talking into an empty one.
     */
    suspend fun end(pairingId: String): Result<Unit> = runCatching {
        clearMessages(pairingId)
        room(pairingId).delete().await()
    }

    private suspend fun clearMessages(pairingId: String) {
        runCatching {
            val existing = messages(pairingId).get().await()
            if (existing.isEmpty) return@runCatching
            firestore.runBatch { batch ->
                existing.documents.forEach { batch.delete(it.reference) }
            }.await()
        }
    }

    private companion object {
        const val ROOMS = "jamchat"
        const val CURRENT = "current"
        const val MESSAGES = "messages"
        const val LIMIT = 200L
        const val MAX_LENGTH = 300
    }
}
