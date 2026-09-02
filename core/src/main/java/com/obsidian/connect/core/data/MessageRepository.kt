package com.obsidian.connect.core.data

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun messages(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(FirestorePaths.MESSAGES)

    /**
     * The recent conversation, oldest first.
     *
     * Ordered by the client timestamp rather than the server one so a message
     * you just sent appears immediately instead of after the round trip.
     */
    fun observe(pairingId: String, limit: Long = PAGE_SIZE): Flow<List<Message>> =
        messages(pairingId)
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .limitToLast(limit)
            .asFlow()

    suspend fun send(pairingId: String, senderId: String, text: String): Result<Unit> =
        runCatching {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@runCatching

            messages(pairingId).add(
                mapOf(
                    "senderId" to senderId,
                    "text" to trimmed.take(MAX_LENGTH),
                    "createdAtMillis" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }

    /**
     * Sends a photo, optionally with a caption alongside it.
     *
     * [jpeg] must already be compressed by the caller; an oversized document is
     * rejected by Firestore with an error that explains nothing useful.
     */
    suspend fun sendPhoto(
        pairingId: String,
        senderId: String,
        jpeg: ByteArray,
        caption: String = "",
    ): Result<String> = runCatching {
        check(jpeg.size <= MAX_IMAGE_BYTES) {
            "That photo is ${jpeg.size / 1024}KB, over the ${MAX_IMAGE_BYTES / 1024}KB limit"
        }

        val doc = messages(pairingId).document()
        doc.set(
            mapOf(
                "senderId" to senderId,
                "text" to caption.trim().take(MAX_LENGTH),
                "image" to Blob.fromBytes(jpeg),
                "createdAtMillis" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        doc.id
    }

    /**
     * The newest message the other person sent.
     *
     * Fetches a handful and filters here rather than querying by sender.
     * Firestore requires an inequality filter to lead the ordering, which would
     * conflict with ordering by time — and the last few messages are cheap.
     */
    suspend fun latestFrom(pairingId: String, uid: String): Message? =
        messages(pairingId)
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)
            .limit(RECENT_LOOKBACK)
            .get()
            .await()
            .toObjects(Message::class.java)
            .firstOrNull { it.senderId != uid }

    /**
     * Messages from the other person that arrived after [sinceMillis].
     *
     * Used to decide whether the widget shows its unread dot. Counting on the
     * device rather than storing a read receipt in Firestore keeps "unread"
     * meaning *unread on this phone*, which is what a widget on this phone
     * should reflect.
     */
    suspend fun unreadCount(pairingId: String, uid: String, sinceMillis: Long): Int =
        messages(pairingId)
            .whereGreaterThan("createdAtMillis", sinceMillis)
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .limit(UNREAD_CAP)
            .get()
            .await()
            .toObjects(Message::class.java)
            .count { it.senderId != uid }

    private companion object {
        const val PAGE_SIZE = 200L
        const val MAX_LENGTH = 2000

        /** Firestore caps a document at 1MiB; this leaves room for the rest. */
        const val MAX_IMAGE_BYTES = 700 * 1024

        /** Past this the dot is on either way; no point counting further. */
        const val UNREAD_CAP = 50L

        /** Enough to find the other person's last message in any real exchange. */
        const val RECENT_LOOKBACK = 15L
    }
}
