package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId

/**
 * A conversation that exists only while it is happening.
 *
 * Deliberately not part of the main chat and deliberately not kept. Half of
 * what gets typed here is song titles, and a permanent record of "play that
 * one again" is clutter rather than memory. Cancelling deletes it outright.
 *
 * Both people have to be in it. One leaving ends it for the other, because a
 * jam chat with one person in it is just talking to yourself.
 */
data class JamChatRoom(
    @DocumentId val id: String = "",

    /** Who opened it, so the other side knows who is asking. */
    val startedBy: String = "",

    /** Everyone who has accepted. The room is live once both are here. */
    val participants: List<String> = emptyList(),

    val startedAtMillis: Long = 0L,

    /**
     * When the other person was last actively asked to come in.
     *
     * A timestamp rather than a flag, so asking again after a decline is
     * meaningful: the other phone remembers which request it turned down, and a
     * newer one is a new question rather than the same one reappearing.
     */
    val requestedAtMillis: Long = 0L,
) {
    val isLive: Boolean get() = startedBy.isNotBlank()

    fun hasJoined(uid: String): Boolean = uid in participants

    fun isWaitingFor(uid: String): Boolean = isLive && !hasJoined(uid)
}

/**
 * One line in a jam chat.
 *
 * Every message is tried as a song first. [playedTitle] is set when that
 * worked, so the line reads as "this got put on" rather than as something
 * somebody said - the same text means two different things depending on
 * whether it found anything.
 */
data class JamChatMessage(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val text: String = "",

    /** Non-empty when this turned into a track. */
    val playedTitle: String = "",

    val createdAtMillis: Long = 0L,
) {
    val becameATrack: Boolean get() = playedTitle.isNotBlank()
}
