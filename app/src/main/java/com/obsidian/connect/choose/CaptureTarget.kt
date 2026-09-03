package com.obsidian.connect.choose

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Somewhere for the camera app to write a photo.
 *
 * `TakePicture` hands the capture to another app, which needs a URI it is
 * allowed to write to — it cannot return the image itself. The alternative,
 * `TakePicturePreview`, gives back a thumbnail-sized bitmap, which is useless
 * for something being examined closely enough to be chosen between.
 */
object CaptureTarget {

    private const val DIR = "captures"

    /**
     * Creates an empty file and the URI pointing at it.
     *
     * Named by timestamp rather than reused, because a camera app that fails
     * or is cancelled leaves whatever was there before, and a stale photo
     * silently taking the place of the one just cancelled is worse than an
     * extra file in a cache the system clears anyway.
     */
    fun create(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return file to uri
    }

    /** Clears anything left behind by cancelled or failed captures. */
    fun clearStale(context: Context, keep: File? = null) {
        File(context.cacheDir, DIR).listFiles()
            ?.filter { it != keep }
            ?.forEach { it.delete() }
    }
}
