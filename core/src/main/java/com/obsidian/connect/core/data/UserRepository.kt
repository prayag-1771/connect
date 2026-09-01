package com.obsidian.connect.core.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private val users get() = firestore.collection(FirestorePaths.USERS)

    fun observe(uid: String): Flow<User?> = users.document(uid).asFlow()

    suspend fun get(uid: String): User? =
        users.document(uid).get().await().toObject(User::class.java)

    suspend fun createProfile(uid: String, displayName: String) {
        users.document(uid).set(
            mapOf(
                "displayName" to displayName,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * FCM tokens rotate without warning — on reinstall, on restore to a new
     * device, and occasionally for no visible reason. This runs on every launch
     * as well as from onNewToken, because a stale token means the partner's
     * photos silently stop arriving with no error anywhere.
     */
    suspend fun updateFcmToken(uid: String, token: String) {
        users.document(uid)
            .set(mapOf("fcmToken" to token), SetOptions.merge())
            .await()
    }
}
