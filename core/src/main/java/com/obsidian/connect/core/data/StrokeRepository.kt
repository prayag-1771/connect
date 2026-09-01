package com.obsidian.connect.core.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Stroke
import com.obsidian.connect.core.model.StrokePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrokeRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun strokes(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(FirestorePaths.STROKES)

    /**
     * Every stroke on the shared canvas, oldest first so later strokes paint
     * over earlier ones.
     *
     * Ordered by the client timestamp rather than the server one. A server
     * timestamp is null locally until the write completes, so a query ordered
     * by it would not include the stroke you are drawing right now.
     */
    fun observe(pairingId: String, limit: Long = MAX_STROKES): Flow<List<Stroke>> =
        strokes(pairingId)
            .orderBy("createdAtMillis", Query.Direction.ASCENDING)
            .limitToLast(limit)
            .asFlow()

    suspend fun add(
        pairingId: String,
        senderId: String,
        points: List<StrokePoint>,
        color: Long,
        width: Float,
    ): Result<Unit> = runCatching {
        if (points.isEmpty()) return@runCatching

        strokes(pairingId).add(
            mapOf(
                "senderId" to senderId,
                "points" to points.map { mapOf("x" to it.x, "y" to it.y) },
                "color" to color,
                "width" to width,
                "createdAtMillis" to System.currentTimeMillis(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /** Wipes the canvas. Either person can do this — it is a shared surface. */
    suspend fun clear(pairingId: String): Result<Int> = runCatching {
        val all = strokes(pairingId).get().await().documents
        if (all.isEmpty()) return@runCatching 0

        // Batches cap at 500 writes, and a busy canvas can hold more than that.
        all.chunked(BATCH_LIMIT).forEach { chunk ->
            firestore.runBatch { batch -> chunk.forEach { batch.delete(it.reference) } }.await()
        }
        all.size
    }

    private companion object {
        /**
         * Old strokes fall off the top. Without a bound the canvas would grow
         * forever, and every device would re-read the whole history on open.
         */
        const val MAX_STROKES = 400L
        const val BATCH_LIMIT = 400
    }
}
