package com.obsidian.connect.core.data

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Bridges Firestore's listener API onto Flow.
 *
 * The listener is removed in [awaitClose], which matters more than it looks:
 * a leaked snapshot listener keeps a live socket to Firestore open and bills
 * a document read every time the data changes, forever.
 */
inline fun <reified T : Any> Query.asFlow(): Flow<List<T>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        snapshot?.let { trySend(it.toObjects(T::class.java)) }
    }
    awaitClose { registration.remove() }
}

inline fun <reified T : Any> DocumentReference.asFlow(): Flow<T?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        trySend(snapshot?.toObject(T::class.java))
    }
    awaitClose { registration.remove() }
}
