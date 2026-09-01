package com.obsidian.connect.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.obsidian.connect.core.model.Stroke
import java.io.File

/**
 * Turns the shared canvas into a picture a widget can display.
 *
 * A widget cannot query Firestore or run Compose, so it cannot draw the canvas
 * itself. The app renders it to a file whenever the strokes change, and the
 * widget shows whatever it last found there — the same arrangement the photo
 * uses, for the same reason.
 */
object StrokeRasterizer {

    private const val FILE_NAME = "widget_drawing.png"
    private const val TEMP_NAME = "widget_drawing.png.tmp"

    /**
     * Rendered square, in pixels.
     *
     * Bounded for the same reason the watch face is: the bitmap crosses a
     * Binder transaction to the launcher that caps out near 1MB.
     */
    const val SIZE_PX = 360

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * Draws [strokes] and writes the result.
     *
     * Coordinates arrive normalised to 0..1, so they scale to whatever size is
     * rendered without the sender needing to know anything about this device.
     */
    fun render(context: Context, strokes: List<Stroke>, size: Int = SIZE_PX) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        strokes.forEach { stroke ->
            val points = stroke.points
            if (points.isEmpty()) return@forEach

            paint.color = stroke.color.toInt()
            // Widths were chosen against a full-screen canvas; scale them so a
            // fine line stays fine and a thick one stays thick at widget size.
            paint.strokeWidth = (stroke.width * size / REFERENCE_CANVAS_PX)
                .coerceAtLeast(1f)

            if (points.size == 1) {
                val only = points.first()
                canvas.drawPoint(only.x * size, only.y * size, paint)
                return@forEach
            }

            val path = Path().apply {
                moveTo(points.first().x * size, points.first().y * size)
                points.drop(1).forEach { lineTo(it.x * size, it.y * size) }
            }
            canvas.drawPath(path, paint)
        }

        writeAtomically(context, bitmap)
        bitmap.recycle()
    }

    /**
     * Writes through a temp file and renames.
     *
     * A direct write interrupted by the process being killed leaves a truncated
     * PNG that decodes to null, and the widget then shows nothing until the
     * next stroke happens to arrive.
     */
    private fun writeAtomically(context: Context, bitmap: Bitmap) {
        val temp = File(context.filesDir, TEMP_NAME)
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val target = file(context)
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }

    /**
     * Roughly the pixel width of the drawing canvas on a phone, used only to
     * keep stroke weights proportional between the app and the widget.
     */
    private const val REFERENCE_CANVAS_PX = 1000f
}
