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
/** One track waiting its turn. */
data class QueueItem(
    val videoId: String = "",
    val title: String = "",
)

data class JamSession(
    @DocumentId val id: String = "",

    /**
     * Which player this is for.
     *
     * Two people have to be running the same one, or a play written by a
     * YouTube session would be obeyed by a Spotify player holding a completely
     * different track.
     */
    val service: String = YOUTUBE,

    /** A YouTube video id or a Spotify track uri. Empty when nothing is on. */
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

    /**
     * Who currently has the jam screen open.
     *
     * Not who can hear it - who is in the room. Someone who started a track and
     * walked off leaves it playing for the other person, and the difference
     * between that and listening together is worth showing.
     */
    val listeners: List<String> = emptyList(),

    /**
     * What plays next, in order.
     *
     * On the session rather than on either phone, because both of you add to it
     * and both of you should see the same list - a queue only one person can
     * see is a playlist.
     */
    val queue: List<QueueItem> = emptyList(),

    /**
     * Everything already played this session.
     *
     * Kept so that when the queue runs dry and something is picked
     * automatically, it is not the song that just finished.
     */
    val playedIds: List<String> = emptyList(),

    val updatedAtMillis: Long = 0L,
) {
    val isLoaded: Boolean get() = videoId.isNotBlank()

    fun isFor(which: String): Boolean = service == which

    companion object {
        const val YOUTUBE = "youtube"
        const val SPOTIFY = "spotify"
    }

    /**
     * Where the track should be *now*, not where it was when this was written.
     *
     * A paused session has not moved since, so it stays put.
     */
    fun expectedPositionMs(now: Long = System.currentTimeMillis()): Long =
        if (playing) positionMs + (now - updatedAtMillis).coerceAtLeast(0L) else positionMs
}
