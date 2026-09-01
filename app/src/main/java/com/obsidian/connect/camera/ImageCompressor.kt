package com.obsidian.connect.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Shrinks a captured photo down to something a widget can actually carry.
 *
 * Compression happens on the sending device rather than in a Cloud Function so
 * the sender pays the upload cost of a small file instead of a large one, and
 * so the receiver never has to wait on a server-side resize before the photo
 * can appear.
 */
object ImageCompressor {

    /** Long edge of the stored image. Comfortably above any widget's needs. */
    const val TARGET_LONG_EDGE = 1080

    private const val QUALITY = 82

    fun compress(
        source: ByteArray,
        longEdge: Int = TARGET_LONG_EDGE,
        quality: Int = QUALITY,
    ): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, longEdge)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: error("Could not decode the captured photo")

        val scaled = scaleToFit(decoded, longEdge)
        val oriented = applyOrientation(scaled, readOrientation(source))

        return ByteArrayOutputStream().use { out ->
            oriented.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    private fun scaleToFit(bitmap: Bitmap, longEdge: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= longEdge) return bitmap

        val ratio = longEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true,
        )
    }

    /**
     * Bakes rotation into the pixels.
     *
     * The EXIF tag is dropped by the re-encode, and a widget draws raw pixels
     * with no EXIF handling of its own — so a photo taken sideways would show
     * up sideways on the other person's home screen.
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readOrientation(source: ByteArray): Int = runCatching {
        val stream: InputStream = source.inputStream()
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Powers of two only — BitmapFactory rounds anything else down to one. */
    private fun sampleSizeFor(width: Int, height: Int, longEdge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= longEdge || height / (sample * 2) >= longEdge) {
            sample *= 2
        }
        return sample
    }
}
