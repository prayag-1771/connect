package com.obsidian.connect.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drag a message aside to answer it.
 *
 * The direction follows the bubble. Their messages sit on the left and pull
 * right; yours sit on the right and pull left. Dragging a message further into
 * the edge it is already against would be fighting the layout, and the gesture
 * reads as "pull it out to work with it" either way.
 *
 * The bubble springs back regardless of whether the threshold was met - the
 * message is not going anywhere, the drag is only a way of pointing at it.
 */
@Composable
fun SwipeToReply(
    mine: Boolean,
    onReply: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val threshold = with(LocalDensity.current) { THRESHOLD_DP.dp.toPx() }
    val limit = with(LocalDensity.current) { LIMIT_DP.dp.toPx() }

    // Read inside a pointerInput lambda that never restarts, so the callback
    // has to be kept fresh by hand or it would answer the wrong message.
    val reply by rememberUpdatedState(onReply)

    Box(modifier = Modifier.fillMaxWidth()) {
        // Fades in as the bubble travels, so the gesture explains itself the
        // first time rather than having to be discovered.
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                .alpha((abs(offset.value) / threshold).coerceIn(0f, 1f)),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .pointerInput(mine) {
                    var armed = false

                    detectHorizontalDragGestures(
                        onDragStart = { armed = false },
                        onDragEnd = {
                            if (armed) reply()
                            scope.launch { offset.animateTo(0f) }
                        },
                        onDragCancel = {
                            scope.launch { offset.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, delta ->
                            // Only the direction that pulls away from the edge
                            // the bubble is against. The other way is a scroll
                            // or a stray thumb, and should do nothing.
                            val allowed = if (mine) {
                                (offset.value + delta).coerceIn(-limit, 0f)
                            } else {
                                (offset.value + delta).coerceIn(0f, limit)
                            }

                            if (allowed != offset.value) change.consume()
                            scope.launch { offset.snapTo(allowed) }

                            // One tap of haptics as the threshold is crossed,
                            // so a reply can be started without looking.
                            val past = abs(allowed) >= threshold
                            if (past && !armed) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            armed = past
                        },
                    )
                },
        ) {
            content()
        }
    }
}

private const val THRESHOLD_DP = 56
private const val LIMIT_DP = 88
