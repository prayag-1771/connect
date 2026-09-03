package com.obsidian.connect.core.data

import com.google.firebase.firestore.FirebaseFirestore
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Timetable
import com.obsidian.connect.core.model.TimetableEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A timetable each, both visible to both.
 *
 * Stored under the pairing rather than under the user, because the whole point
 * is that the other person can see it. Writable only by whoever it belongs to:
 * seeing someone's week and editing it are different things.
 *
 * Only the extracted entries are kept, never the photograph. The image was a
 * means of reading the timetable, and holding on to a picture of somebody's
 * week would be storing more than was asked for.
 */
@Singleton
class TimetableRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun timetables(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(TIMETABLES)

    fun observe(pairingId: String, uid: String): Flow<Timetable?> =
        timetables(pairingId).document(uid).asFlow()

    suspend fun save(
        pairingId: String,
        uid: String,
        entries: List<TimetableEntry>,
    ): Result<Unit> = runCatching {
        timetables(pairingId).document(uid).set(
            mapOf(
                "entries" to entries.map { entry ->
                    mapOf(
                        "id" to entry.id.ifBlank { java.util.UUID.randomUUID().toString() },
                        "day" to entry.day,
                        "start" to entry.start,
                        "end" to entry.end,
                        "title" to entry.title,
                        "location" to entry.location,
                    )
                },
                "updatedAtMillis" to System.currentTimeMillis(),
            ),
        ).await()
    }

    suspend fun clear(pairingId: String, uid: String): Result<Unit> = runCatching {
        timetables(pairingId).document(uid).delete().await()
    }

    private companion object {
        const val TIMETABLES = "timetables"
    }
}
