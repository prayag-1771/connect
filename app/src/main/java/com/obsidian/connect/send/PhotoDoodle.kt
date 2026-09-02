package com.obsidian.connect.send

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.obsidian.connect.core.model.StrokePoint
import java.io.ByteArrayOutputStream

/**
 * One pen movement drawn on top of a photo before it is sent.
 *
 * Coordinates are normalised against the photo itself, not the screen, so the
 * mark lands in the same place regardless of the device it was drawn on or how
 * the photo happened to be displayed at the time.
 */
data class DoodleStroke(
    val points: List<StrokePoint>,
    val color: Long,
    /** Fraction of the photo's width, so a line keeps its weight at any size. */
    val widthFraction: Float,
)

/**
 * Burns doodles into the photo.
 *
 * Flattened rather than sent alongside as separate data. The photo ends up on a
 * home screen widget and in a local archive, neither of which knows anything
 * about strokes — carrying them separately would mean every surface that ever
 * displays a photo has to learn how to draw them.
 */
object PhotoDoodle {

    private const val QUALITY = 85
    private const val MAX_BYTES = 700 * 1024

    fun flatten(jpeg: ByteArray, strokes: List<DoodleStroke>): ByteArray {
        if (strokes.isEmpty()) return jpeg

        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val canvasBitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: return jpeg
        val canvas = Canvas(canvasBitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val width = canvasBitmap.width.toFloat()
        val height = canvasBitmap.height.toFloat()

        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach

            paint.color = stroke.color.toInt()
            paint.strokeWidth = (stroke.widthFraction * width).coerceAtLeast(1f)

            if (stroke.points.size == 1) {
                val only = stroke.points.first()
                canvas.drawPoint(only.x * width, only.y * height, paint)
                return@forEach
            }

            val path = Path().apply {
                moveTo(stroke.points.first().x * width, stroke.points.first().y * height)
                stroke.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
            }
            canvas.drawPath(path, paint)
        }

        return encodeToFit(canvasBitmap)
    }

    /**
     * Re-encodes, stepping quality down until it fits.
     *
     * The photo was already compressed to sit under Firestore's limit, and
     * re-encoding it with ink on top can land slightly larger — sharp
     * high-contrast edges are expensive in JPEG.
     */
    private fun encodeToFit(bitmap: Bitmap): ByteArray {
        var quality = QUALITY
        while (true) {
            val encoded = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            if (encoded.size <= MAX_BYTES || quality <= 40) return encoded
            quality -= 10
        }
    }
}
