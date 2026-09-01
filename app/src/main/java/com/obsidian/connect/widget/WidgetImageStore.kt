package com.obsidian.connect.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Where the widget's current photo lives on disk.
 *
 * The widget reads from a file rather than holding the image in memory because
 * a widget's host process can be restarted at any time — after a reboot, after
 * a launcher crash, after the system reclaims memory. A file means the photo is
 * still there afterwards with no network round trip.
 */
object WidgetImageStore {

    private const val FILE_NAME = "widget_moment.jpg"
    private const val TEMP_NAME = "widget_moment.jpg.tmp"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    /**
     * Writes through a temp file and renames.
     *
     * A direct write can be interrupted — the process is killed mid-download —
     * and leave a truncated JPEG that decodes to null, showing the user an
     * empty widget with no way to recover until the next photo arrives.
     */
    fun write(context: Context, bytes: ByteArray) {
        val temp = File(context.filesDir, TEMP_NAME)
        temp.writeBytes(bytes)
        val target = file(context)
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * Decodes the stored photo, downsampled so it fits within [maxDimension].
     *
     * This bound is not cosmetic. Glance hands the bitmap to the launcher across
     * a Binder transaction that hard-caps somewhere near 1 MB, and going over it
     * fails silently — the widget simply doesn't draw, with nothing in logcat
     * pointing at the size.
     */
    fun decode(context: Context, maxDimension: Int): Bitmap? {
        val source = file(context)
        if (!source.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return BitmapFactory.decodeFile(source.path, options)
    }

    /** Powers of two only — BitmapFactory rounds anything else down to one. */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }
}
