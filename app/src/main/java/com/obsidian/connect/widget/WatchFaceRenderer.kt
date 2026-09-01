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

    /**
     * Fraction of the half-width at which the face ends.
     *
     * This is not a free choice. `AnalogClock` scales its dial drawable to fit
     * the view and centres it, so the ring drawn in `watch_dial.xml` lands at
     * whatever radius that file uses — currently 92 of its 100 unit half-width.
     * The photo has to stop at the same place or it spills past the rim.
     *
     * Change one and the other must change with it.
     */
    private const val DIAL_RATIO = 0.92f

    fun sizeFor(requestedPx: Int): Int = requestedPx.coerceIn(160, MAX_FACE_PX)

    /**
     * Returns a square bitmap holding [source] centre-cropped into the face,
     * with everything outside it transparent.
     */
    fun circularFace(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val centre = size / 2f
        val radius = centre * DIAL_RATIO

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw the circle first, then composite the photo through it. Drawing
        // the photo first and cutting afterwards would leave hard, aliased
        // edges — SRC_IN keeps the anti-aliased boundary of this circle.
        canvas.drawCircle(centre, centre, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, null, centreCropInto(source, centre, radius), paint)
        paint.xfermode = null

        drawScrim(canvas, centre, radius)
        return output
    }

    /**
     * Centre-crops [source] to fill the circle of [radius] exactly.
     *
     * Sized against the circle rather than the whole bitmap, so the photo is
     * scaled to the face it will actually occupy instead of being cropped
     * tighter by the mask afterwards.
     */
    private fun centreCropInto(source: Bitmap, centre: Float, radius: Float): RectF {
        val diameter = radius * 2f
        val scale = diameter / minOf(source.width, source.height).coerceAtLeast(1)
        val width = source.width * scale
        val height = source.height * scale
        return RectF(
            centre - width / 2f,
            centre - height / 2f,
            centre + width / 2f,
            centre + height / 2f,
        )
    }

    /**
     * Darkens the middle of the face.
     *
     * The hands are white and a photo can be white too. Baked into the bitmap
     * rather than layered as a separate view so it lines up with the circle
     * exactly, whatever shape the widget itself ends up.
     */
    private fun drawScrim(canvas: Canvas, centre: Float, radius: Float) {
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centre,
                centre,
                radius,
                intArrayOf(0x73000000, 0x40000000, 0x00000000),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(centre, centre, radius, scrim)
    }
}
