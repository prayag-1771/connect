package com.obsidian.connect.archive

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puts the archive somewhere a person can actually get at it.
 *
 * Into the phone's Downloads folder rather than shared through a picker,
 * because "download" is the word for a file that ends up where every other
 * downloaded file is - findable later without going back through this app.
 *
 * A zip rather than a text file, because the photographs belong with the
 * conversation. They are not in the transcript and cannot be: by the time a
 * message is four days old its bytes are long gone from the database. The
 * copies on this phone are the only ones left, so they are packed alongside,
 * named by the message id the transcript quotes.
 *
 * MediaStore on Android 10 and later, which needs no storage permission at
 * all: the app is only allowed to add its own file, which is exactly the
 * amount of access this needs.
 */
object ChatExport {

    /**
     * Writes a copy and returns the name it was saved under.
     *
     * Timestamped, so exporting twice leaves two files rather than silently
     * replacing the earlier one - somebody who exports again probably wants
     * both, and the older copy is the one that cannot be regenerated if the
     * archive is ever cleared.
     */
    fun save(context: Context): Result<String> = runCatching {
        val text = ChatArchive.read(context)
        check(text.isNotBlank()) { "There is nothing archived yet." }

        val name = "connect-chat-${STAMP.format(Date())}.zip"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the file")

            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "Could not write the file" }
                writeZip(context, text, out)
            }

            // Cleared last, so nothing else sees a half-written file.
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } else {
            @Suppress("DEPRECATION")
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            File(downloads, name).outputStream().use { writeZip(context, text, it) }
        }

        name
    }

    /**
     * The transcript, then every photograph this phone still holds.
     *
     * All of them rather than only the ones the transcript mentions. The
     * archive covers messages older than four days, but the photos on this
     * phone include everything since - and a download that silently omitted
     * the recent ones would be the wrong kind of surprise.
     *
     * Stored rather than compressed: a JPEG does not get smaller for being
     * deflated, and the time spent trying is time the export is not finishing.
     */
    private fun writeZip(context: Context, text: String, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("conversation.txt"))
            zip.write(text.toByteArray())
            zip.closeEntry()

            PhotoArchive.list(context).forEach { entry ->
                runCatching {
                    zip.setLevel(0)
                    zip.putNextEntry(ZipEntry("photos/${entry.file.name}"))
                    entry.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.getDefault())
}
