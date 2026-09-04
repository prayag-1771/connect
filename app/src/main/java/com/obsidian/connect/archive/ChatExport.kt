package com.obsidian.connect.archive

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
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

        val name = "connect-chat-${STAMP.format(Date())}.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the file")

            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "Could not write the file" }
                out.write(text.toByteArray())
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
            File(downloads, name).writeText(text)
        }

        name
    }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.getDefault())
}
