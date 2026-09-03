package com.obsidian.connect.archive

import android.content.Context
import java.io.File

/**
 * Every photo the two of you have exchanged, kept on this phone.
 *
 * Stored in the app's internal directory, which is the whole point: nothing
 * there is indexed by MediaStore, so these never appear in the gallery, in a
 * file manager, or in any other app's picker. They are readable only by this
 * app, and they go when the app is uninstalled.
 *
 * The rectangular photo is archived, not the circle. The circle is a crop the
 * watch face applies for display; throwing away the rest of the frame because
 * of how one widget renders it would be losing the photograph.
 */
object PhotoArchive {

    private const val DIR = "photos"

    /** Which side of a conversation a photo came from. */
    enum class Origin { Sent, Received }

    data class Entry(
        val file: File,
        val origin: Origin,
        val takenAtMillis: Long,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Writes a photo into the archive.
     *
     * The filename carries the timestamp and origin, so listing needs no index
     * and nothing can fall out of sync with a separate database. Duplicate
     * saves of the same photo are ignored rather than stacking up, because
     * receiving is retried and re-syncs are routine.
     */
    fun save(
        context: Context,
        jpeg: ByteArray,
        origin: Origin,
        id: String,
        takenAtMillis: Long = System.currentTimeMillis(),
    ): File {
        val target = File(dir(context), "${takenAtMillis}_${origin.name}_$id.jpg")
        if (!target.exists()) target.writeBytes(jpeg)
        return target
    }

    /**
     * The archived copy of one photo, by the id it was sent under.
     *
     * This is what makes the server copy disposable. Once both phones hold the
     * file, the only thing the document still needs to carry is the fact that
     * the message happened — the bytes can go, and everything that used to read
     * them reads this instead.
     *
     * Matched on the id suffix because the filename also carries a timestamp
     * and an origin, neither of which the caller knows.
     */
    fun find(context: Context, id: String): File? {
        if (id.isBlank()) return null
        val suffix = "_$id.jpg"
        return dir(context).listFiles()?.firstOrNull { it.name.endsWith(suffix) }
    }

    fun bytesFor(context: Context, id: String): ByteArray? =
        find(context, id)?.let { runCatching { it.readBytes() }.getOrNull() }

    /** Newest first. */
    fun list(context: Context): List<Entry> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension == "jpg" }
            ?.mapNotNull { file ->
                val parts = file.nameWithoutExtension.split("_", limit = 3)
                val millis = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val origin = runCatching { Origin.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(file, origin, millis)
            }
            ?.sortedByDescending { it.takenAtMillis }
            .orEmpty()

    fun delete(entry: Entry): Boolean = entry.file.delete()

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
