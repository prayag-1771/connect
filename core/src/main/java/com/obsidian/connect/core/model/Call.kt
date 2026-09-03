package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId

/** Where a call has got to. */
enum class CallState { Idle, Ringing, Answered, Ended }

/**
 * The handshake between two phones, kept in one document.
 *
 * WebRTC connects peers directly, but it cannot introduce them - each side has
 * to hand the other a description of itself first, and something has to carry
 * that. Firestore is that something. It is only used for the introduction; once
 * the connection is up, video and audio never touch it.
 *
 * One document per pairing rather than one per call. Two people can only be in
 * one call with each other, and a fixed path means neither side has to discover
 * an id before it can listen.
 */
data class Call(
    @DocumentId val id: String = "",
    val callerId: String = "",

    /** Stored as a name so an unknown value from a newer build degrades quietly. */
    val stateName: String = CallState.Idle.name,

    /** Session descriptions - the offer from the caller, the answer back. */
    val offer: String = "",
    val answer: String = "",

    /** Whether the caller is sending their screen as well as their camera. */
    val sharingScreen: Boolean = false,

    val startedAtMillis: Long = 0L,
) {
    val state: CallState
        get() = runCatching { CallState.valueOf(stateName) }.getOrDefault(CallState.Idle)

    val isLive: Boolean get() = state == CallState.Ringing || state == CallState.Answered

    fun isMine(uid: String): Boolean = callerId == uid
}

/**
 * One network path a phone thinks it can be reached on.
 *
 * There are usually several - a local address, a public one discovered through
 * STUN, and a relayed one - and both sides trade every candidate they find.
 * WebRTC then works out which pair actually connects.
 */
data class IceCandidate(
    @DocumentId val id: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
)
