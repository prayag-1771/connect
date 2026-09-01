package com.obsidian.connect.core.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Pairing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private val pairings get() = firestore.collection(FirestorePaths.PAIRINGS)
    private val users get() = firestore.collection(FirestorePaths.USERS)

    fun observe(pairingId: String): Flow<Pairing?> = pairings.document(pairingId).asFlow()

    /** One-shot read, for the background sync where there is no lifecycle. */
    suspend fun get(pairingId: String): Pairing? =
        pairings.document(pairingId).get().await().toObject(Pairing::class.java)

    /** Opens a pairing with one member and a code for the other person to enter. */
    suspend fun createInvite(uid: String): Result<Pairing> = runCatching {
        val code = generateInviteCode()
        val doc = pairings.document()

        firestore.runBatch { batch ->
            batch.set(
                doc,
                mapOf(
                    "members" to listOf(uid),
                    "inviteCode" to code,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.set(users.document(uid), mapOf("pairingId" to doc.id), SetOptions.merge())
        }.await()

        Pairing(id = doc.id, members = listOf(uid), inviteCode = code)
    }

    /**
     * Joins an existing invite.
     *
     * Runs in a transaction because two people racing on the same code would
     * otherwise both pass the "is there room" check and land a three-member
     * pairing in the database, which nothing downstream knows how to handle.
     */
    suspend fun join(uid: String, inviteCode: String): Result<String> = runCatching {
        val code = inviteCode.trim().uppercase()
        val match = pairings.whereEqualTo("inviteCode", code).limit(1).get().await()
        val ref = match.documents.firstOrNull()?.reference
            ?: error("That code doesn't match any invite")

        firestore.runTransaction { txn ->
            val pairing = txn.get(ref).toObject(Pairing::class.java)
                ?: error("That invite no longer exists")

            if (uid !in pairing.members) {
                check(!pairing.isComplete) { "That invite has already been used" }
                txn.update(ref, "members", FieldValue.arrayUnion(uid))
                txn.set(users.document(uid), mapOf("pairingId" to ref.id), SetOptions.merge())
            }
            ref.id
        }.await()
    }

    /**
     * Withdraws an invite nobody has accepted.
     *
     * Without this, tapping "Create an invite" is irreversible: the pairing id
     * is written to the user document, so the app opens on the waiting screen
     * forever — a reinstall does not help, because the state is on the server.
     *
     * Refuses once someone has joined. Tearing down a live pairing would strand
     * the other person on a dangling id, and leaving is a different operation
     * needing its own confirmation.
     */
    suspend fun cancelInvite(uid: String, pairingId: String): Result<Unit> = runCatching {
        val ref = pairings.document(pairingId)
        val pairing = ref.get().await().toObject(Pairing::class.java)
            ?: error("That invite no longer exists")

        check(!pairing.isComplete) { "Someone already joined this one" }
        check(uid in pairing.members) { "That isn't your invite" }

        firestore.runBatch { batch ->
            batch.delete(ref)
            batch.update(users.document(uid), "pairingId", null)
        }.await()
    }

    /**
     * Ends a pairing for both people.
     *
     * Deletes the pairing document rather than removing yourself from its
     * member list, because security rules only let you write your own user
     * document — there is no way to clear the pairing id off theirs.
     *
     * Deleting works because everything reads through that document. Their
     * stale pairing id then points at nothing, which resolves to null, which
     * puts them back on the pairing screen exactly as intended. Creating or
     * joining a new invite overwrites the stale id.
     */
    suspend fun leave(uid: String, pairingId: String): Result<Unit> = runCatching {
        firestore.runBatch { batch ->
            batch.delete(pairings.document(pairingId))
            batch.update(users.document(uid), "pairingId", null)
        }.await()
    }

    /**
     * Six characters from an alphabet with I, O, 0 and 1 removed, because these
     * codes get read aloud and those four are the ones people mishear.
     */
    private fun generateInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString {
            repeat(CODE_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private companion object {
        const val CODE_LENGTH = 6
    }
}
