package com.obsidian.connect.archive

import android.content.Context
import com.obsidian.connect.core.model.Message
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything said more than four days ago, kept on this phone.
 *
 * Appended to rather than rebuilt. Firestore is asked once for each night's
 * worth of messages and never again, so the file is the only complete record -
 * which is the point, because it survives anything later pruned from the
 * database and costs nothing to keep.
 *
 * Written as it will be read. There is no separate export step and no
 * intermediate format: the file is already the transcript, so downloading it is
 * a copy rather than a conversion.
 */
object ChatArchive {

    private const val FILE = "conversation.txt"
    private const val PREFS = "connect_chat_archive"
    private const val KEY_UP_TO = "archived_up_to"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The timestamp everything up to has already been written.
     *
     * Kept so a nightly run asks Firestore only for what is new to it. Starting
     * at zero is correct on a fresh install: the first run collects the whole
     * history that is old enough, once.
     */
    fun archivedUpTo(context: Context): Long = prefs(context).getLong(KEY_UP_TO, 0L)

    private fun setArchivedUpTo(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_UP_TO, millis).apply()
    }

    fun exists(context: Context): Boolean = file(context).length() > 0

    fun sizeBytes(context: Context): Long = file(context).length()

    fun lineCount(context: Context): Int = runCatching {
        file(context).useLines { lines -> lines.count { it.isNotBlank() } }
    }.getOrDefault(0)

    fun read(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("")

    /**
     * Appends a batch, newest last, and remembers how far it got.
     *
     * [names] maps uid to display name, because a transcript full of
     * "yBqW3..." is a log rather than a conversation.
     */
    fun append(
        context: Context,
        messages: List<Message>,
        names: Map<String, String>,
    ): Int {
        val worth = messages.filter { it.createdAtMillis > archivedUpTo(context) }
        if (worth.isEmpty()) return 0

        val target = file(context)
        val fresh = !target.exists() || target.length() == 0L

        val text = buildString {
            if (fresh) {
                appendLine("Connect - conversation archive")
                appendLine("Everything here is older than four days.")
                appendLine()
            }

            var lastDay = lastDayIn(target)

            worth.forEach { message ->
                val day = DAY.format(Date(message.createdAtMillis))
                if (day != lastDay) {
                    appendLine()
                    appendLine("--- $day ---")
                    lastDay = day
                }
                appendLine(line(message, names))
            }
        }

        target.appendText(text)
        setArchivedUpTo(context, worth.maxOf { it.createdAtMillis })
        return worth.size
    }

    /**
     * One message, with everything about it that survives.
     *
     * A photo, a voice note or a GIF is named rather than described - the bytes
     * are long gone from the database by the time this runs, and a line saying
     * a photo was sent at a time is more honest than pretending to hold it.
     */
    private fun line(message: Message, names: Map<String, String>): String {
        val time = TIME.format(Date(message.createdAtMillis))
        val who = names[message.senderId] ?: "Someone"

        val body = buildString {
            if (message.isReply) {
                append("[replying to: ${message.replyToText.take(60)}] ")
            }
            if (message.hasChoiceRef) append("[about a card] ")

            when {
                message.isPhoto && message.text.isBlank() -> append("<photo>")
                message.isPhoto -> append("<photo> ${message.text}")
                message.hasAudio -> append("<voice note, ${message.audioDurationMs / 1000}s>")
                message.hasGif -> append("<gif> ${message.gifUrl}")
                else -> append(message.text)
            }

            if (message.starredBy.isNotEmpty()) append("  ★")
        }

        return "[$time] $who: $body"
    }

    /**
     * The last date header already in the file.
     *
     * Read back rather than remembered, so a run that appends to yesterday's
     * final day does not print the header twice.
     */
    private fun lastDayIn(target: File): String? = runCatching {
        if (!target.exists()) return null
        target.useLines { lines ->
            lines.filter { it.startsWith("--- ") && it.endsWith(" ---") }
                .lastOrNull()
                ?.removeSurrounding("--- ", " ---")
        }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        prefs(context).edit().remove(KEY_UP_TO).apply()
    }

    private val DAY = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())
    private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
}
