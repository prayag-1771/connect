package com.obsidian.connect.core.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storageMetadata
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Moment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MomentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
) {
    private val moments get() = firestore.collection(FirestorePaths.MOMENTS)

    fun observeHistory(pairingId: String, limit: Long = 30): Flow<List<Moment>> =
        moments
            .whereEqualTo("pairingId", pairingId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .asFlow()

    /**
     * Uploads the photo, then writes the document.
     *
     * Order matters. The Firestore write is what a Cloud Function watches to
     * fire the push, so if it landed first the receiving device could start
     * downloading a file that isn't in Storage yet.
     *
     * [jpeg] is expected to be already downscaled and compressed by the caller.
     */
    suspend fun send(
        pairingId: String,
        senderId: String,
        jpeg: ByteArray,
        caption: String = "",
    ): Result<Moment> = runCatching {
        val path = "moments/$pairingId/${UUID.randomUUID()}.jpg"
        val fileRef = storage.reference.child(path)

        fileRef.putBytes(jpeg, storageMetadata { contentType = "image/jpeg" }).await()
        val downloadUrl = fileRef.downloadUrl.await().toString()

        val doc = moments.document()
        doc.set(
            mapOf(
                "pairingId" to pairingId,
                "senderId" to senderId,
                "storagePath" to path,
                "downloadUrl" to downloadUrl,
                "caption" to caption,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()

        Moment(
            id = doc.id,
            pairingId = pairingId,
            senderId = senderId,
            storagePath = path,
            downloadUrl = downloadUrl,
            caption = caption,
        )
    }

    /** Removes both the document and the file behind it. */
    suspend fun delete(moment: Moment): Result<Unit> = runCatching {
        moments.document(moment.id).delete().await()
        if (moment.storagePath.isNotEmpty()) {
            storage.reference.child(moment.storagePath).delete().await()
        }
    }
}
