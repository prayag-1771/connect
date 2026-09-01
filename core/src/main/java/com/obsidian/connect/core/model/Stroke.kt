package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * One continuous pen movement on the shared canvas, from touch down to lift.
 *
 * Coordinates are normalised to 0..1 against the canvas rather than stored as
 * pixels, so a stroke drawn on a tablet lands in the right place on a phone.
 */
data class StrokePoint(
    val x: Float = 0f,
    val y: Float = 0f,
)

data class Stroke(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val points: List<StrokePoint> = emptyList(),
    val color: Long = 0xFF000000L,
    val width: Float = 4f,
    @ServerTimestamp val createdAt: Date? = null,
)
