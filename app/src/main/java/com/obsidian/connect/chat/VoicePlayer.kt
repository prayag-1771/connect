package com.obsidian.connect.chat

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Plays voice notes, one at a time.
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

    /**
     * Starts [id], or stops it if it is already playing.
     *
     * [onFinished] fires on natural completion and on stop, so the caller can
     * drop its playing state without tracking two separate paths.
     */
    fun toggle(id: String, bytes: ByteArray, onFinished: () -> Unit) {
        if (playingId == id) {
            stop()
            onFinished()
            return
        }

        stop()

        val file = File(context.cacheDir, "play_$id.m4a")
        if (!file.exists()) runCatching { file.writeBytes(bytes) }.onFailure { return }

        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
                prepare()
                start()
            }
        }.getOrNull()

        if (created == null) {
            onFinished()
            return
        }

        player = created
        playingId = id
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }
}
