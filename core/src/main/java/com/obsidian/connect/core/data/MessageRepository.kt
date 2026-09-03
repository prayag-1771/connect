package com.obsidian.connect.core.data

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.core.model.Receipt
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

    /**
     * A live view of just the newest few messages.
     *
     * Deliberately not [observe]. That one carries two hundred documents, and
     * any of them may still be holding a photo — fine for a conversation you
     * are looking at, far too heavy for a listener whose only question is
     * whether anything new has arrived.
     *
     * Three is enough: the answer is about the newest message from the other
     * person, and a couple of your own replies on top of it is the worst
     * realistic case.
     */
    fun observeRecent(pairingId: String, limit: Long = RECENT_WATCH): Flow<List<Message>> =
        messages(pairingId)
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)
            .limit(limit)
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
                // Outlives the bytes, which are erased once delivered.
                "photo" to true,
                "createdAtMillis" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        doc.id
    }

    /**
     * Drops the photo out of a message once both phones hold it.
     *
     * The document stays: the conversation still needs to know a photo was sent
     * here, at this moment, by this person. Only the bytes go — and they go as
     * soon as the receiving phone has written its own copy, so the server holds
     * a photo for the length of one delivery rather than for good.
     *
     * Failure is deliberately quiet. The archive copy is already safe, and the
     * worst case is a blob that lingers until the next read retries this.
     */
    suspend fun clearImage(pairingId: String, messageId: String): Result<Unit> =
        runCatching {
            messages(pairingId).document(messageId)
                .update("image", FieldValue.delete())
                .await()
        }

    /**
     * Sends a voice note.
     *
     * [durationMs] is stored rather than derived on playback, so a bubble can
     * show its length without every device decoding the clip just to lay out a
     * list.
     */
    suspend fun sendAudio(
        pairingId: String,
        senderId: String,
        audio: ByteArray,
        durationMs: Long,
    ): Result<String> = runCatching {
        check(audio.size <= MAX_IMAGE_BYTES) {
            "That recording is ${audio.size / 1024}KB, over the ${MAX_IMAGE_BYTES / 1024}KB limit"
        }

        val doc = messages(pairingId).document()
        doc.set(
            mapOf(
                "senderId" to senderId,
                "text" to "",
                "audio" to Blob.fromBytes(audio),
                "audioDurationMs" to durationMs,
                "createdAtMillis" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        doc.id
    }

    /**
     * Sends a GIF as a link.
     *
     * No size check, because nothing is being stored — the URL is a couple of
     * hundred bytes whatever the GIF weighs.
     */
    suspend fun sendGif(
        pairingId: String,
        senderId: String,
        gifUrl: String,
    ): Result<String> = runCatching {
        val doc = messages(pairingId).document()
        doc.set(
            mapOf(
                "senderId" to senderId,
                "text" to "",
                "gifUrl" to gifUrl,
                "createdAtMillis" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        doc.id
    }

    private fun receipts(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(FirestorePaths.RECEIPTS)

    fun observeReceipt(pairingId: String, uid: String): Flow<Receipt?> =
        receipts(pairingId).document(uid).asFlow()

    /**
     * Moves a watermark forward.
     *
     * Never backwards: messages can arrive slightly out of order, and an older
     * one landing later must not undo a receipt already given. Both values are
     * merged rather than set, so marking delivered does not wipe seen.
     */
    suspend fun markProgress(
        pairingId: String,
        uid: String,
        deliveredAtMillis: Long? = null,
        seenAtMillis: Long? = null,
    ): Result<Unit> = runCatching {
        val existing = receipts(pairingId).document(uid).get().await()
            .toObject(Receipt::class.java)

        val delivered = maxOf(deliveredAtMillis ?: 0L, existing?.deliveredAtMillis ?: 0L)
        val seen = maxOf(seenAtMillis ?: 0L, existing?.seenAtMillis ?: 0L)

        if (delivered == (existing?.deliveredAtMillis ?: 0L) &&
            seen == (existing?.seenAtMillis ?: 0L)
        ) {
            // Nothing moved. Writing anyway would burn the daily allowance on
            // every recomposition of an idle conversation.
            return@runCatching
        }

        receipts(pairingId).document(uid).set(
            mapOf(
                "deliveredAtMillis" to delivered,
                // Seeing something implies having received it; without this a
                // message read the instant it arrived could show as seen but
                // never delivered.
                "seenAtMillis" to seen,
            ),
            SetOptions.merge(),
        ).await()
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
        const val MAX_IMAGE_BYTES = 900 * 1024

        /** Past this the dot is on either way; no point counting further. */
        const val UNREAD_CAP = 50L

        /** Enough to find the other person's last message in any real exchange. */
        const val RECENT_LOOKBACK = 15L

        /** Kept tiny — this one is held open continuously. */
        const val RECENT_WATCH = 3L
    }
}
