package com.obsidian.connect.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Cuts a photo into the round face of the watch.
 *
 * RemoteViews cannot clip a view to a shape — there is no mask, no outline
 * provider worth relying on, and `scaleType` only ever produces a rectangle.
 * So the circle is drawn into the bitmap before it is handed over, and the
 * ImageView simply displays what it is given.
 */
object WatchFaceRenderer {

    /**
     * Upper bound on the rendered face, in pixels.
     *
     * This is a memory limit, not a taste one. The bitmap crosses a Binder
     * transaction to the launcher which caps out near 1MB, and an ARGB_8888
     * square costs four bytes a pixel — 360x360 is about 506KB, which leaves
     * room for everything else in the transaction. Alpha is not optional here
     * because the corners outside the circle have to be transparent.
     */
    private const val MAX_FACE_PX = 360

    fun sizeFor(requestedPx: Int): Int = requestedPx.coerceIn(160, MAX_FACE_PX)

    /**
     * Returns a square bitmap holding [source] centre-cropped into a circle,
     * with everything outside the circle transparent.
     */
    fun circularFace(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = size / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw the circle first, then composite the photo through it. Drawing
        // the photo first and cutting afterwards would leave hard, aliased
        // edges — SRC_IN keeps the anti-aliased boundary of this circle.
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, null, centreCropInto(source, size), paint)
        paint.xfermode = null

        drawScrim(canvas, radius)
        return output
    }

    /**
     * The destination rectangle that centre-crops [source] into a square,
     * scaling by the shorter edge so the frame is filled rather than letterboxed.
     */
    private fun centreCropInto(source: Bitmap, size: Int): RectF {
        val scale = size.toFloat() / minOf(source.width, source.height).coerceAtLeast(1)
        val width = source.width * scale
        val height = source.height * scale
        val left = (size - width) / 2f
        val top = (size - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    /**
     * Darkens the middle of the face.
     *
     * The hands are white and a photo can be white too. Baked into the bitmap
     * rather than layered as a separate view so it lines up with the circle
     * exactly, whatever shape the widget itself ends up.
     */
    private fun drawScrim(canvas: Canvas, radius: Float) {
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                radius,
                radius,
                radius,
                intArrayOf(0x73000000, 0x40000000, 0x00000000),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(radius, radius, radius, scrim)
    }
}
