package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId

/**
 * One track, playing on both phones at once.
 *
 * The whole session is a single document because there is only one thing to
 * agree on: what is playing, whether it is running, and where it has got to.
 * Either person may change any of it - a jam where one of you holds the remote
 * is not a jam.
 *
 * YouTube rather than Apple Music, and not by preference. Apple ships MusicKit
 * for iOS and the web only, so no Android app can drive Apple Music; Spotify's
 * SDK can, but demands Premium at both ends. A player this app owns is the only
 * one it can actually keep in step.
 */
data class JamSession(
    @DocumentId val id: String = "",

    /** Empty when nothing is loaded, which is how the screen knows to ask. */
    val videoId: String = "",
    val title: String = "",

    val playing: Boolean = false,

    /**
     * Where the track had reached when this was written.
     *
     * Meaningless on its own - it is a position at a moment, and the moment is
     * [updatedAtMillis]. A phone reading it has to add however long the write
     * took to arrive, or it lands late by exactly the network delay.
     */
    val positionMs: Long = 0L,

    /** Who last touched the controls, so a device can ignore its own echo. */
    val byUid: String = "",

    val updatedAtMillis: Long = 0L,
) {
    val isLoaded: Boolean get() = videoId.isNotBlank()

    /**
     * Where the track should be *now*, not where it was when this was written.
     *
     * A paused session has not moved since, so it stays put.
     */
    fun expectedPositionMs(now: Long = System.currentTimeMillis()): Long =
        if (playing) positionMs + (now - updatedAtMillis).coerceAtLeast(0L) else positionMs
}
