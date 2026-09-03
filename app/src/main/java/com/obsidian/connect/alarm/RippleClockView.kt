package com.obsidian.connect.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Calendar

/**
 * A clock face with rings pushing out of it, and the alarm's name underneath.
 *
 * Drawn rather than composed. This has to live in a WindowManager overlay,
 * which is a plain View hierarchy — and the whole content is a handful of
 * circles and two hands, which is less code as a Canvas than as anything else.
 *
 * The ripples are staggered rather than concentric-in-step: three rings, each
 * a third of a cycle behind the last, so there is always one leaving the face
 * and one fading out at the edge. A single pulsing ring reads as a loading
 * spinner; several reads as something insisting.
 */
@SuppressLint("ViewConstructor")
class RippleClockView(
    context: Context,
    private val title: String,
    private val subtitle: String,
) : View(context) {

    private val density = resources.displayMetrics.density

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SCRIM }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FACE }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = ACCENT
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 21f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }

    private val startedAt = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)

        val cx = width / 2f
        val cy = height / 2f - 40f * density
        val faceRadius = minOf(width, height) * 0.16f

        val elapsed = (System.currentTimeMillis() - startedAt) % CYCLE_MS
        val base = elapsed / CYCLE_MS.toFloat()

        // Three rings, evenly offset around one cycle.
        repeat(RIPPLES) { index ->
            val phase = (base + index / RIPPLES.toFloat()) % 1f
            val radius = faceRadius + phase * faceRadius * 2.6f

            // Fades as it travels, so the edge of the animation is soft rather
            // than a ring that simply vanishes at its limit.
            ripplePaint.color = ACCENT
            ripplePaint.alpha = ((1f - phase) * 150).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, ripplePaint)
        }

        canvas.drawCircle(cx, cy, faceRadius, facePaint)
        canvas.drawCircle(cx, cy, faceRadius, rimPaint)
        drawHands(canvas, cx, cy, faceRadius)

        canvas.drawText(title, cx, cy + faceRadius * 2.5f, titlePaint)
        canvas.drawText(subtitle, cx, cy + faceRadius * 2.5f + 26f * density, subtitlePaint)

        // Drives the animation. Cheaper and steadier than an animator for a
        // view that is only ever on screen for a few seconds at a time.
        postInvalidateOnAnimation()
    }

    private fun drawHands(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR) + minute / 60f

        handPaint.strokeWidth = 4f * density
        drawHand(canvas, cx, cy, hour / 12f, radius * 0.5f)

        handPaint.strokeWidth = 3f * density
        drawHand(canvas, cx, cy, minute / 60f, radius * 0.75f)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, fraction: Float, length: Float) {
        // Twelve o'clock is up, so a quarter turn comes off the angle.
        val angle = fraction * 2f * Math.PI - Math.PI / 2
        canvas.drawLine(
            cx,
            cy,
            cx + (Math.cos(angle) * length).toFloat(),
            cy + (Math.sin(angle) * length).toFloat(),
            handPaint,
        )
    }

    private companion object {
        const val RIPPLES = 3
        const val CYCLE_MS = 1800L

        val SCRIM = Color.argb(215, 8, 10, 16)
        val FACE = Color.argb(255, 20, 24, 34)
        val ACCENT = Color.argb(255, 108, 176, 255)
        val MUTED = Color.argb(255, 150, 158, 175)
    }
}
