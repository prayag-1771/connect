package com.obsidian.connect.core.data

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Choice
import com.obsidian.connect.core.model.ChoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChoiceRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun choices(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(FirestorePaths.CHOICES)

    /**
     * Newest first, so the thing being decided right now is the card you land
     * on rather than something from last week.
     */
    fun observe(pairingId: String, limit: Long = MAX_CHOICES): Flow<List<Choice>> =
        choices(pairingId)
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)
            .limit(limit)
            .asFlow()

    suspend fun add(
        pairingId: String,
        addedBy: String,
        jpeg: ByteArray,
        note: String = "",
    ): Result<String> = runCatching {
        check(jpeg.size <= MAX_IMAGE_BYTES) {
            "That photo is ${jpeg.size / 1024}KB, over the ${MAX_IMAGE_BYTES / 1024}KB limit"
        }

        val doc = choices(pairingId).document()
        doc.set(
            mapOf(
                "addedBy" to addedBy,
                "image" to Blob.fromBytes(jpeg),
                // Outlives the bytes, which are erased once delivered.
                "photo" to true,
                "note" to note.trim(),
                "verdict" to 0,
                "verdictBy" to "",
                "createdAtMillis" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        doc.id
    }

    /**
     * Records an opinion.
     *
     * [verdict] is 1 for liked, -1 for disliked, 0 to take it back. Voting the
     * same way twice clears it, so a mis-tap is undone by repeating it rather
     * than by hunting for an undo.
     */
    suspend fun judge(
        pairingId: String,
        choiceId: String,
        uid: String,
        verdict: Int,
    ): Result<Unit> = runCatching {
        choices(pairingId).document(choiceId).update(
            mapOf(
                "verdict" to verdict,
                "verdictBy" to if (verdict == 0) "" else uid,
            ),
        ).await()
    }

    /**
     * Records that this card was talked about.
     *
     * Appended with arrayUnion rather than read-modify-write: both of you can
     * be replying to the same card at once, and rewriting the whole list would
     * let one reply quietly erase the other.
     */
    suspend fun addRef(
        pairingId: String,
        choiceId: String,
        ref: ChoiceRef,
    ): Result<Unit> = runCatching {
        choices(pairingId).document(choiceId).update(
            "refs",
            FieldValue.arrayUnion(
                mapOf(
                    "messageId" to ref.messageId,
                    "byUid" to ref.byUid,
                    "text" to ref.text,
                    "atMillis" to ref.atMillis,
                ),
            ),
        ).await()
    }

    /**
     * Drops the photo out of a card once the other phone has it.
     *
     * The card itself has to stay — it is still being voted on, and the verdict
     * is the reason it exists. Only the picture moves off the server, to be
     * read from each phone's own copy from then on.
     */
    suspend fun clearImage(pairingId: String, choiceId: String): Result<Unit> =
        runCatching {
            choices(pairingId).document(choiceId)
                .update("image", FieldValue.delete())
                .await()
        }

    suspend fun delete(pairingId: String, choiceId: String): Result<Unit> = runCatching {
        choices(pairingId).document(choiceId).delete().await()
    }

    private companion object {
        /**
         * Photos live in the documents, and the free plan caps the whole
         * database at 1GiB. A decision worth making rarely has fifty options.
         */
        const val MAX_CHOICES = 40L
        const val MAX_IMAGE_BYTES = 900 * 1024
    }
}
