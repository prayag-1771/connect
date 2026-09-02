package com.obsidian.connect.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Records a voice note straight to a file.
 *
 * AAC mono at 32kbps, which lands around 4KB per second — a minute is roughly
 * 240KB, comfortably inside Firestore's 1MiB document limit. Higher bitrates
 * buy nothing here: this is speech on a phone speaker, not music.
 *
 * Length is capped rather than left open. A recording that quietly grew past
 * the document limit would fail at the moment of sending, after the person had
 * already said their piece.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return false

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val created = runCatching {
            @Suppress("DEPRECATION")
            val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BITRATE)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrNull() ?: return false

        recorder = created
        outputFile = file
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    /**
     * Stops and returns the recording, or null if it failed or was too short.
     *
     * A sub-second clip is almost always a mis-tap, and sending one is more
     * annoying than losing it.
     */
    fun stop(): Recording? {
        val active = recorder ?: return null
        val file = outputFile

        // stop() throws if the recorder never captured anything — a tap so
        // brief the encoder produced no frames. The file is useless either way.
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }

        recorder = null
        outputFile = null

        val duration = SystemClock.elapsedRealtime() - startedAt
        if (!stopped || file == null || !file.exists() || duration < MIN_DURATION_MS) {
            file?.delete()
            return null
        }

        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return bytes?.let { Recording(it, duration) }
    }

    /** Abandons a recording in progress. */
    fun cancel() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    data class Recording(val bytes: ByteArray, val durationMs: Long) {
        // Generated equals on a ByteArray compares references, which is never
        // what anyone means; these are here so the data class behaves.
        override fun equals(other: Any?): Boolean =
            other is Recording && durationMs == other.durationMs && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + durationMs.hashCode()
    }

    companion object {
        const val MAX_DURATION_MS = 60_000
        private const val MIN_DURATION_MS = 700
        private const val SAMPLE_RATE = 22_050
        private const val BITRATE = 32_000
    }
}
