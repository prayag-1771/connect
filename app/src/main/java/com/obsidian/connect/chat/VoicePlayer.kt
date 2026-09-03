package com.obsidian.connect.chat

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Plays voice notes, one at a time, and can be scrubbed.
 *
 * Deliberately single-instance: starting a second note stops the first. Two
 * people talking over each other out of one phone speaker is never what was
 * wanted, and it is the obvious result of tapping a second bubble.
 *
 * Audio arrives as bytes inside a Firestore document, and MediaPlayer cannot
 * read from a byte array — so each clip is written to the cache directory
 * first, keyed by message id so the same note is only ever written once.
 */
class VoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var playingId: String? = null

    fun currentlyPlaying(): String? = playingId

    /** Where playback has reached, in milliseconds. */
    fun positionMs(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun durationMs(): Int = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    fun isPlaying(id: String): Boolean =
        playingId == id && runCatching { player?.isPlaying == true }.getOrDefault(false)

    /**
     * Starts [id], or pauses it if it is already running.
     *
     * Pausing rather than stopping, so the position survives — the whole point
     * of being able to scrub is not losing your place.
     */
    fun toggle(id: String, bytes: ByteArray, onFinished: () -> Unit) {
        if (playingId == id) {
            val active = player ?: return
            runCatching {
                if (active.isPlaying) active.pause() else active.start()
            }
            return
        }

        stop()
        prepare(id, bytes, onFinished)?.start()
    }

    /**
     * Jumps to a point in a note, loading it first if it is not the one
     * currently open. Scrubbing a note you have not started should work.
     */
    fun seekTo(id: String, bytes: ByteArray, positionMs: Int, onFinished: () -> Unit) {
        if (playingId != id) {
            stop()
            prepare(id, bytes, onFinished)
        }
        runCatching { player?.seekTo(positionMs) }
    }

    private fun prepare(id: String, bytes: ByteArray, onFinished: () -> Unit): MediaPlayer? {
        val file = File(context.cacheDir, "play_$id.m4a")
        if (!file.exists()) {
            runCatching { file.writeBytes(bytes) }.onFailure { return null }
        }

        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
                prepare()
            }
        }.getOrNull()

        if (created == null) {
            onFinished()
            return null
        }

        player = created
        playingId = id
        return created
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }
}
