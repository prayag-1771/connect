package com.obsidian.connect.core.data

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Moment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MomentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private val moments get() = firestore.collection(FirestorePaths.MOMENTS)

    /**
     * The newest photo the other person sent — the one the widget shows.
     *
     * Filtered by sender in the query rather than in memory. Every document
     * here carries its image inline, so pulling a handful and discarding your
     * own would mean transferring hundreds of kilobytes to throw most away.
     */
    fun observeLatestFrom(pairingId: String, partnerId: String): Flow<Moment?> =
        latestQuery(pairingId, partnerId).asFlow<Moment>().map { it.firstOrNull() }

    /** One-shot equivalent, for the background sync. */
    suspend fun latestFrom(pairingId: String, partnerId: String): Moment? =
        latestQuery(pairingId, partnerId)
            .get()
            .await()
            .toObjects(Moment::class.java)
            .firstOrNull()

    private fun latestQuery(pairingId: String, partnerId: String): Query =
        moments
            .whereEqualTo("pairingId", pairingId)
            .whereEqualTo("senderId", partnerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)

    /**
     * Sends a photo.
     *
     * [jpeg] must already be downscaled and compressed by the caller. The size
     * check is not defensive padding — a document over 1MiB is rejected by
     * Firestore outright, and the resulting error says nothing useful about
     * why, so it is worth catching here where the cause is obvious.
     */
    suspend fun send(
        pairingId: String,
        senderId: String,
        jpeg: ByteArray,
        caption: String = "",
    ): Result<Moment> = runCatching {
        check(jpeg.size <= MAX_IMAGE_BYTES) {
            "That photo is ${jpeg.size / 1024}KB, over the ${MAX_IMAGE_BYTES / 1024}KB limit"
        }

        val doc = moments.document()
        doc.set(
            mapOf(
                "pairingId" to pairingId,
                "senderId" to senderId,
                "image" to Blob.fromBytes(jpeg),
                "caption" to caption,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()

        Moment(
            id = doc.id,
            pairingId = pairingId,
            senderId = senderId,
            image = Blob.fromBytes(jpeg),
            caption = caption,
        )
    }

    /**
     * Drops the photo out of a moment once the other phone has it.
     *
     * The empty document is kept on purpose. Both sync paths decide whether
     * there is anything new by comparing the newest moment's id against the one
     * already on the widget, so deleting it outright would promote the previous
     * photo to newest and put an old picture back on the watch face.
     */
    suspend fun clearImage(momentId: String): Result<Unit> = runCatching {
        moments.document(momentId).update("image", FieldValue.delete()).await()
    }

    suspend fun delete(moment: Moment): Result<Unit> = runCatching {
        moments.document(moment.id).delete().await()
    }

    /**
     * Deletes everything but the most recent [keep] photos for a pairing.
     *
     * Storage is finite on the free plan — 1GiB across the whole database — and
     * every photo lives in it permanently otherwise. Called after each send so
     * the collection cannot grow without bound.
     */
    suspend fun pruneOlderThan(pairingId: String, keep: Int = KEEP_PER_PAIRING): Result<Int> =
        runCatching {
            val all = moments
                .whereEqualTo("pairingId", pairingId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
                .documents

            val stale = all.drop(keep)
            if (stale.isEmpty()) return@runCatching 0

            firestore.runBatch { batch -> stale.forEach { batch.delete(it.reference) } }.await()
            stale.size
        }

    private companion object {
        /**
         * Firestore's document ceiling is 1MiB and the rest of the fields need
         * room too, so this leaves generous headroom below it.
         */
        const val MAX_IMAGE_BYTES = 700 * 1024

        const val KEEP_PER_PAIRING = 30
    }
}
