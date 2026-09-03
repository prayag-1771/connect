package com.obsidian.connect.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

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
    const val DIAL_RATIO = 0.92f

    /** Unread dot, as a fraction of the face's full width. */
    private const val DOT_RATIO = 0.055f
    private const val DOT_OUTLINE_RATIO = 0.014f

    private val DOT_GREEN = 0xFF3DDC84u.toInt()
    private val DOT_YELLOW = 0xFFFFC53Du.toInt()
    private val DOT_OUTLINE = 0xCC0B0B0Fu.toInt()

    /** Stand-in disc for before any photo has arrived. */
    private val EMPTY_FACE = 0xFF1F1F25u.toInt()

    fun sizeFor(requestedPx: Int): Int = requestedPx.coerceIn(160, MAX_FACE_PX)

    /**
     * Returns a square bitmap holding [source] centre-cropped into the face,
     * with everything outside it transparent.
     *
     * [source] may be null — before the first photo arrives the face is drawn
     * as a plain dark disc rather than left empty, so the hands have something
     * to sit on and the unread dot still has a rim to sit on.
     */
    fun circularFace(source: Bitmap?, size: Int, hasUnread: Boolean = false): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val centre = size / 2f
        val radius = centre * DIAL_RATIO

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (source == null) {
            paint.color = EMPTY_FACE
            canvas.drawCircle(centre, centre, radius, paint)
        } else {
            // Draw the circle first, then composite the photo through it.
            // Drawing the photo first and cutting afterwards would leave hard,
            // aliased edges — SRC_IN keeps this circle's anti-aliased boundary.
            canvas.drawCircle(centre, centre, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(source, null, centreCropInto(source, centre, radius), paint)
            paint.xfermode = null

            drawScrim(canvas, centre, radius)
        }

        // Dots are no longer painted here. They live on their own layer above
        // the clock, because the dial ring is drawn over this bitmap and was
        // cutting straight through them.
        return output
    }

    /**
     * The status dots, on a transparent layer of their own.
     *
     * Separate from the face because the AnalogClock sits on top of the photo,
     * and its dial ring was being drawn straight through anything painted into
     * it. A dot the rim cuts through reads as a smudge, not a signal.
     *
     * Returns null when there is nothing to say, so the caller can hide the
     * view entirely rather than stacking an empty bitmap over the clock.
     */
    fun dotsOverlay(size: Int, hasUnread: Boolean, hasNewChoice: Boolean): Bitmap? {
        if (!hasUnread && !hasNewChoice) return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val centre = size / 2f
        val radius = centre * DIAL_RATIO

        // Both on the left, one below the other. Left rather than right so
        // neither collides with the blue corner light, which marks a new
        // drawing and needs its own tap target.
        if (hasUnread) dot(canvas, centre, radius, size, UNREAD_ANGLE, DOT_GREEN)
        if (hasNewChoice) dot(canvas, centre, radius, size, CHOICE_ANGLE, DOT_YELLOW)

        return output
    }

    /**
     * One dot, centred on the ring rather than inside it, so it reads as part
     * of the bezel instead of something floating on the photo.
     *
     * The dark outline is what keeps it visible against a light picture — a
     * flat coloured circle disappears against grass or a bright wall.
     */
    private fun dot(
        canvas: Canvas,
        centre: Float,
        radius: Float,
        size: Int,
        degrees: Double,
        colour: Int,
    ) {
        val angle = Math.toRadians(degrees)
        val x = centre + (radius * cos(angle)).toFloat()
        val y = centre + (radius * sin(angle)).toFloat()
        val dot = size * DOT_RATIO

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT_OUTLINE }
        canvas.drawCircle(x, y, dot + size * DOT_OUTLINE_RATIO, outline)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
        canvas.drawCircle(x, y, dot, fill)
    }

    /** Roughly ten-thirty, and a little below it. */
    private const val UNREAD_ANGLE = -135.0
    private const val CHOICE_ANGLE = -163.0

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
