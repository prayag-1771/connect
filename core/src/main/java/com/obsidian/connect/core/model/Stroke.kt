package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * One point along a stroke.
 *
 * Coordinates are normalised to 0..1 against the canvas rather than stored as
 * pixels, so a stroke drawn on a tablet lands in the right place on a phone.
 */
data class StrokePoint(
    val x: Float = 0f,
    val y: Float = 0f,
)

/**
 * One continuous pen movement on the shared canvas, from touch down to lift.
 */
data class Stroke(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val points: List<StrokePoint> = emptyList(),
    val color: Long = 0xFF000000L,
    val width: Float = 4f,

    /**
     * Ordering key, set from the sending device's clock.
     *
     * Not the server timestamp, which is what [createdAt] is for. A server
     * timestamp is null on the writing device until the round trip completes,
     * so a query ordered by it would not match the stroke you just drew — your
     * own line would vanish and reappear a moment later.
     *
     * The cost is that ordering depends on two phones roughly agreeing about
     * the time, which for overlapping scribbles is not worth worrying about.
     */
    val createdAtMillis: Long = 0L,

    @ServerTimestamp val createdAt: Date? = null,
)
