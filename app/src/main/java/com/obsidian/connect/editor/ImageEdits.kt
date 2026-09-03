package com.obsidian.connect.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.obsidian.connect.core.model.StrokePoint
import java.io.ByteArrayOutputStream

/**
 * A pen mark laid over a photo before it is sent.
 *
 * Normalised to the *cropped* image, so a mark stays where it was put no
 * matter what happens to the photo afterwards.
 */
data class EditStroke(
    val points: List<StrokePoint>,
    val color: Long,
    val widthFraction: Float,
)

/**
 * The crop rectangle, in fractions of the original image.
 *
 * Fractions rather than pixels so the same rectangle means the same thing
 * whatever size the photo is displayed at — the editor shows it fitted to the
 * screen, which is nothing like its real dimensions.
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isWholeImage: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f
}

object ImageEdits {

    private const val QUALITY = 88
    private const val MAX_BYTES = 700 * 1024
    private const val MIN_QUALITY = 45

    /**
     * Applies the crop, then the strokes, then encodes.
     *
     * Order matters: strokes are recorded against the cropped frame, so
     * drawing them before cropping would move every mark.
     */
    fun apply(
        jpeg: ByteArray,
        crop: CropRect,
        strokes: List<EditStroke>,
        maxBytes: Int = MAX_BYTES,
    ): ByteArray {
        if (crop.isWholeImage && strokes.isEmpty()) return jpeg

        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val cropped = cropTo(source, crop)
        val canvasBitmap = cropped.copy(Bitmap.Config.ARGB_8888, true) ?: return jpeg

        if (strokes.isNotEmpty()) draw(canvasBitmap, strokes)
        return encodeToFit(canvasBitmap, maxBytes)
    }

    private fun cropTo(source: Bitmap, crop: CropRect): Bitmap {
        if (crop.isWholeImage) return source

        val x = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
        val w = (crop.width * source.width).toInt().coerceAtLeast(1)
            .coerceAtMost(source.width - x)
        val h = (crop.height * source.height).toInt().coerceAtLeast(1)
            .coerceAtMost(source.height - y)

        return Bitmap.createBitmap(source, x, y, w, h)
    }

    private fun draw(bitmap: Bitmap, strokes: List<EditStroke>) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

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
    }

    /**
     * Steps quality down until it fits.
     *
     * Ink and crops both change the size in ways that are hard to predict —
     * sharp high-contrast edges are expensive in JPEG, and a crop can raise
     * detail per pixel rather than lower it.
     */
    private fun encodeToFit(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var quality = QUALITY
        while (true) {
            val encoded = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            if (encoded.size <= maxBytes || quality <= MIN_QUALITY) return encoded
            quality -= 8
        }
    }
}
