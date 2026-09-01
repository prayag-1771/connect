package com.obsidian.connect.core.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Reminder
import com.obsidian.connect.core.model.ReminderScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {

    /**
     * [ownerId] is a pairing id for the shared list and a uid for the private
     * one. Keeping them under different parents is what makes the private list
     * actually private — security rules can enforce a path, but they cannot
     * stop someone from flipping a boolean on a document they can already write.
     */
    private fun collection(scope: ReminderScope, ownerId: String): CollectionReference =
        when (scope) {
            ReminderScope.Shared -> firestore
                .collection(FirestorePaths.PAIRINGS)
                .document(ownerId)
                .collection(FirestorePaths.REMINDERS)

            ReminderScope.Private -> firestore
                .collection(FirestorePaths.USERS)
                .document(ownerId)
                .collection(FirestorePaths.REMINDERS)
        }

    /**
     * Ordered by creation only. Sorting by done-then-due would need a composite
     * index per list, and these lists are small enough that ordering them for
     * display is better done in the view model.
     */
    fun observe(scope: ReminderScope, ownerId: String): Flow<List<Reminder>> =
        collection(scope, ownerId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .asFlow()

    suspend fun add(
        scope: ReminderScope,
        ownerId: String,
        title: String,
        note: String = "",
        dueAt: Date? = null,
        createdBy: String,
    ): Result<String> = runCatching {
        val doc = collection(scope, ownerId).document()
        doc.set(
            buildMap {
                put("title", title.trim())
                put("note", note.trim())
                put("done", false)
                put("createdBy", createdBy)
                put("createdAt", FieldValue.serverTimestamp())
                dueAt?.let { put("dueAt", it) }
            },
        ).await()
        doc.id
    }

    suspend fun setDone(
        scope: ReminderScope,
        ownerId: String,
        reminderId: String,
        done: Boolean,
        byUid: String,
    ): Result<Unit> = runCatching {
        collection(scope, ownerId).document(reminderId).update(
            mapOf(
                "done" to done,
                // Cleared on un-checking so a reopened item doesn't keep
                // claiming it was finished.
                "completedBy" to if (done) byUid else null,
                "completedAt" to if (done) FieldValue.serverTimestamp() else null,
            ),
        ).await()
    }

    suspend fun edit(
        scope: ReminderScope,
        ownerId: String,
        reminderId: String,
        title: String,
        note: String,
        dueAt: Date?,
    ): Result<Unit> = runCatching {
        collection(scope, ownerId).document(reminderId).update(
            mapOf(
                "title" to title.trim(),
                "note" to note.trim(),
                // Explicit null rather than omitting the key, so clearing a due
                // date actually removes it instead of leaving the old one.
                "dueAt" to dueAt,
            ),
        ).await()
    }

    suspend fun delete(
        scope: ReminderScope,
        ownerId: String,
        reminderId: String,
    ): Result<Unit> = runCatching {
        collection(scope, ownerId).document(reminderId).delete().await()
    }

    /** Clears every finished item from a list in one batch. */
    suspend fun clearCompleted(scope: ReminderScope, ownerId: String): Result<Int> = runCatching {
        val finished = collection(scope, ownerId)
            .whereEqualTo("done", true)
            .get()
            .await()

        if (finished.isEmpty) return@runCatching 0

        firestore.runBatch { batch ->
            finished.documents.forEach { batch.delete(it.reference) }
        }.await()

        finished.size()
    }

    /**
     * Pokes the other person about a shared reminder.
     *
     * Writes a document instead of calling anything directly — the push has to
     * originate from a trusted server, and a Cloud Function watching this
     * collection is what turns the write into a notification.
     */
    suspend fun nudge(
        pairingId: String,
        reminder: Reminder,
        fromUid: String,
        toUid: String,
    ): Result<Unit> = runCatching {
        firestore
            .collection(FirestorePaths.PAIRINGS)
            .document(pairingId)
            .collection(FirestorePaths.NUDGES)
            .add(
                mapOf(
                    "reminderId" to reminder.id,
                    "reminderTitle" to reminder.title,
                    "fromUid" to fromUid,
                    "toUid" to toUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
            .await()
        Unit
    }
}
