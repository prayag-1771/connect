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

    /**
     * Long edge for photos bound for a widget.
     *
     * 720 because a widget never renders more than this — Glance caps its
     * bitmaps well below it — and every pixel past that is thrown away.
     */
    const val TARGET_LONG_EDGE = 720

    /**
     * Long edge for photos that are only ever looked at on screen.
     *
     * Chat pictures and choice cards do not go near a widget, so the 720 cap
     * exists for them only as an accident of sharing this code. At that size
     * anything with fine detail — a screenshot, a label, a price tag — comes
     * out soft, which is exactly the sort of thing people put up to be judged.
     *
     * The ceiling is still Firestore's 1MiB document, and the encoder below
     * steps down until it fits, so this is an upper bound rather than a promise.
     */
    const val DETAIL_LONG_EDGE = 2560

    private const val QUALITY = 92

    /**
     * Matches the repository ceiling, which Firestore's 1MiB document limit
     * sets. 900KB rather than 700: the rest of a message is a few hundred
     * bytes, so the earlier figure was leaving a quarter of the budget unused
     * and softening photos for no reason.
     */
    private const val MAX_BYTES = 900 * 1024

    private const val MIN_QUALITY = 60

    /**
     * Compresses until the result actually fits.
     *
     * A single fixed quality is not enough: a noisy photo — foliage, confetti,
     * anything high-frequency — can encode several times larger than a smooth
     * one at identical dimensions. Guessing once and hoping means a document
     * Firestore rejects, so this steps quality down until it measures small
     * enough, then gives up on dimensions if that is not enough either.
     */
    fun compress(
        source: ByteArray,
        longEdge: Int = TARGET_LONG_EDGE,
        quality: Int = QUALITY,
        maxBytes: Int = MAX_BYTES,
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

        return encodeToFit(oriented, quality, maxBytes)
    }

    private fun encodeToFit(bitmap: Bitmap, startQuality: Int, maxBytes: Int): ByteArray {
        var current = bitmap
        var quality = startQuality

        while (true) {
            val encoded = encode(current, quality)
            if (encoded.size <= maxBytes) return encoded

            if (quality > MIN_QUALITY) {
                quality -= 10
                continue
            }

            // Quality alone was not enough. Halving the pixel count cuts size
            // far more sharply than any further quality drop would, and looks
            // better than the smeared result of very low quality.
            val smaller = Bitmap.createScaledBitmap(
                current,
                (current.width * 0.75f).toInt().coerceAtLeast(1),
                (current.height * 0.75f).toInt().coerceAtLeast(1),
                true,
            )

            // Nothing left to give; hand back the smallest we managed.
            if (smaller.width < 2 || smaller.height < 2) return encoded

            current = smaller
            quality = startQuality
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
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
