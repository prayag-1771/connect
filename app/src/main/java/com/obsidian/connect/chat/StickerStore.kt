package com.obsidian.connect.chat

import android.content.Context
import java.io.File

/**
 * Saved images kept for sending again — memes, reaction pictures, whatever.
 *
 * Deliberately separate from [com.obsidian.connect.archive.PhotoArchive]. That
 * is a record of what the two of you actually exchanged and should not be
 * edited; this is a scratch collection you add to and delete from freely.
 * Mixing them would mean deleting a meme could quietly remove it from the
 * history of your conversation.
 *
 * Also app-private, so none of it shows up in the gallery either.
 */
object StickerStore {

    private const val DIR = "stickers"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Newest first, so the last thing saved is the easiest to reach. */
    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun save(context: Context, jpeg: ByteArray): File {
        val file = File(dir(context), "sticker_${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        return file
    }

    fun delete(file: File): Boolean = file.delete()

    fun count(context: Context): Int = dir(context).listFiles()?.size ?: 0
}
