package com.obsidian.connect.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.DeliveryStatus

/**
 * How far a message has got, shown as a journey rather than as ticks.
 *
 * A short rail with a dot on it: the dot sits at the start when the message has
 * only reached the database, midway once their phone has it, and at the far end
 * once they have actually looked. Progress is read from *position*, which needs
 * no decoding — unlike one tick versus two, which everyone has to learn.
 *
 * The dot animates between positions, so a message being read while you are
 * looking at it is something you notice rather than something you find.
 */
@Composable
fun DeliveryRail(
    status: DeliveryStatus,
    modifier: Modifier = Modifier,
) {
    val target = when (status) {
        DeliveryStatus.Sent -> 0f
        DeliveryStatus.Reached -> 0.5f
        DeliveryStatus.Seen -> 1f
    }

    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 320),
        label = "deliveryProgress",
    )

    // Colour carries the same information as position, so the state is legible
    // to anyone who cannot easily judge a few pixels of offset.
    val dotColour by animateColorAsState(
        targetValue = when (status) {
            DeliveryStatus.Sent -> Muted
            DeliveryStatus.Reached -> Muted
            DeliveryStatus.Seen -> Seen
        },
        label = "deliveryColour",
    )

    Canvas(modifier = modifier.size(width = RAIL_WIDTH, height = RAIL_HEIGHT)) {
        val y = size.height / 2f
        val radius = size.height / 2f
        val left = radius
        val right = size.width - radius

        drawLine(
            color = Muted.copy(alpha = 0.35f),
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // The travelled portion, so the rail reads left to right even at a glance.
        if (progress > 0f) {
            drawLine(
                color = dotColour.copy(alpha = 0.55f),
                start = Offset(left, y),
                end = Offset(left + (right - left) * progress, y),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = dotColour,
            radius = radius,
            center = Offset(left + (right - left) * progress, y),
        )
    }
}

private val RAIL_WIDTH = 22.dp
private val RAIL_HEIGHT = 5.dp

private val Muted = Color(0xFF9AA0A6)
private val Seen = Color(0xFF3DDC84)
