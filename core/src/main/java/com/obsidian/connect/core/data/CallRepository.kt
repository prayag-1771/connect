package com.obsidian.connect.core.data

import com.google.firebase.firestore.FirebaseFirestore
import com.obsidian.connect.core.FirestorePaths
import com.obsidian.connect.core.model.Call
import com.obsidian.connect.core.model.CallState
import com.obsidian.connect.core.model.IceCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the introduction between two phones, and nothing else.
 *
 * Once the two sides have exchanged descriptions and network candidates, the
 * media flows directly between them and this goes quiet. That is the whole
 * reason a call can be free: Firestore moves a few kilobytes of text, not
 * video.
 */
@Singleton
class CallRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun callDoc(pairingId: String) = firestore
        .collection(FirestorePaths.PAIRINGS)
        .document(pairingId)
        .collection(CALLS)
        .document(CURRENT)

    private fun candidates(pairingId: String, fromCaller: Boolean) =
        callDoc(pairingId).collection(if (fromCaller) CALLER_ICE else CALLEE_ICE)

    fun observe(pairingId: String): Flow<Call?> = callDoc(pairingId).asFlow()

    /**
     * Candidates from the other side.
     *
     * Listened to rather than fetched, because they keep arriving after the
     * call has started - a phone that switches from wifi to mobile mid-call
     * finds new paths and offers them.
     */
    fun observeCandidates(pairingId: String, fromCaller: Boolean): Flow<List<IceCandidate>> =
        candidates(pairingId, fromCaller).asFlow()

    /** Starts ringing, with this phone's description of itself. */
    suspend fun offer(
        pairingId: String,
        callerId: String,
        sdp: String,
        sharingScreen: Boolean,
    ): Result<Unit> = runCatching {
        // Old candidates belong to a call that is over; leaving them would have
        // the next one trying to connect to addresses nobody is listening on.
        clearCandidates(pairingId)

        callDoc(pairingId).set(
            mapOf(
                "callerId" to callerId,
                "stateName" to CallState.Ringing.name,
                "offer" to sdp,
                "answer" to "",
                "sharingScreen" to sharingScreen,
                "startedAtMillis" to System.currentTimeMillis(),
            ),
        ).await()
    }

    suspend fun answer(pairingId: String, sdp: String): Result<Unit> = runCatching {
        callDoc(pairingId).update(
            mapOf(
                "answer" to sdp,
                "stateName" to CallState.Answered.name,
            ),
        ).await()
    }

    suspend fun setSharingScreen(pairingId: String, sharing: Boolean): Result<Unit> =
        runCatching {
            callDoc(pairingId).update("sharingScreen", sharing).await()
        }

    /**
     * Ends it for both.
     *
     * The document is emptied rather than deleted, so the other phone sees a
     * state change it can react to. Deleting would just make the listener go
     * quiet, which is indistinguishable from a network problem.
     */
    suspend fun end(pairingId: String): Result<Unit> = runCatching {
        callDoc(pairingId).set(
            mapOf(
                "callerId" to "",
                "stateName" to CallState.Ended.name,
                "offer" to "",
                "answer" to "",
                "sharingScreen" to false,
                "startedAtMillis" to 0L,
            ),
        ).await()
        clearCandidates(pairingId)
    }

    suspend fun addCandidate(
        pairingId: String,
        fromCaller: Boolean,
        candidate: IceCandidate,
    ): Result<Unit> = runCatching {
        candidates(pairingId, fromCaller).add(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.candidate,
            ),
        ).await()
    }

    private suspend fun clearCandidates(pairingId: String) {
        listOf(true, false).forEach { fromCaller ->
            runCatching {
                val existing = candidates(pairingId, fromCaller).get().await()
                if (existing.isEmpty) return@runCatching
                firestore.runBatch { batch ->
                    existing.documents.forEach { batch.delete(it.reference) }
                }.await()
            }
        }
    }

    private companion object {
        const val CALLS = "call"
        const val CURRENT = "current"
        const val CALLER_ICE = "callerCandidates"
        const val CALLEE_ICE = "calleeCandidates"
    }
}
