package com.obsidian.connect.reminders

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Holds a row under the finger while the list rearranges beneath it.
 *
 * The earlier version stepped by a fixed row height, which was wrong the
 * moment two rows differed — and they differ constantly, since a note or a due
 * date makes a row taller. Positions come from the list's own layout now, so a
 * drag lands where it looks like it will regardless of what the rows contain.
 *
 * [offset] is the dragged row's displacement from wherever the layout has just
 * put it, not from where the drag began. Every time a swap happens the row is
 * re-laid-out somewhere new, and the offset is adjusted by exactly that much —
 * which is what keeps it pinned under the finger instead of jumping by a row
 * each time it passes one.
 */
class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettled: () -> Unit,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set

    var offset by mutableFloatStateOf(0f)
        private set

    /**
     * Whether this gesture actually rearranged anything.
     *
     * A long press that never moves still starts and ends a drag, and without
     * this it would write an unchanged order back to Firestore every time
     * someone rested a finger on the handle.
     */
    private var moved = false

    fun isDragging(key: String): Boolean = draggingKey == key

    fun start(key: String) {
        draggingKey = key
        offset = 0f
        moved = false
    }

    fun drag(delta: Float) {
        val key = draggingKey ?: return
        offset += delta

        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: return

        // Where the row actually appears right now, rather than where the
        // layout thinks it is.
        val centre = dragged.offset + offset + dragged.size / 2f

        val target = items.firstOrNull { other ->
            other.key != key && centre >= other.offset && centre <= other.offset + other.size
        } ?: run {
            autoScroll(centre)
            return
        }

        onMove(dragged.index, target.index)
        moved = true

        // The row is about to be laid out where the target was. Take that
        // distance straight back out of the offset, or it would visibly leap
        // by one row every time it crossed a neighbour.
        offset -= (target.offset - dragged.offset)
    }

    /**
     * Creeps the list along when the row is dragged against an edge.
     *
     * Without it the list can only be rearranged as far as the screen shows,
     * which on a long list means the drag simply stops working near the ends.
     */
    private fun autoScroll(centre: Float) {
        val info = listState.layoutInfo
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset

        val push = when {
            centre < top + EDGE_PX -> -SCROLL_PX
            centre > bottom - EDGE_PX -> SCROLL_PX
            else -> return
        }

        scope.launch { listState.nudgeBy(push) }
    }

    /**
     * Lets go, and lets the row fall into place rather than blinking there.
     *
     * The key is held until the settle finishes so the row keeps its lift and
     * its z-order on the way down; clearing it first would drop it behind its
     * neighbours for the length of the animation.
     */
    fun stop() {
        val settling = draggingKey ?: return

        scope.launch {
            Animatable(offset).animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = 400f),
            ) { offset = value }

            if (draggingKey == settling) {
                draggingKey = null
                offset = 0f
                if (moved) onSettled()
            }
        }
    }

    private companion object {
        const val EDGE_PX = 120f
        const val SCROLL_PX = 18f
    }
}

private suspend fun LazyListState.nudgeBy(pixels: Float) {
    scroll { scrollBy(pixels) }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onSettled: () -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()

    // Held by reference, not by value.
    //
    // The list being reordered is rebuilt every time the query re-emits, and
    // the callbacks are rebuilt with it - they close over a state object that
    // no longer exists a moment later. Captured once, a drag would go on
    // writing into a list nothing was reading any more, which looks exactly
    // like a row that refuses to move.
    val move by rememberUpdatedState(onMove)
    val settled by rememberUpdatedState(onSettled)

    return remember(listState) {
        ReorderState(
            listState = listState,
            scope = scope,
            onMove = { from, to -> move(from, to) },
            onSettled = { settled() },
        )
    }
}

/**
 * The three lines you press and hold to move a row.
 *
 * Long press rather than a plain drag, so scrolling the list past the handle
 * does not pick a row up by accident.
 */
@Composable
fun DragHandle(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    // pointerInput(Unit) never restarts, so whatever it captures on the first
    // composition is what it keeps forever. These have to be read through a
    // holder or the gesture ends up calling into a dead composition.
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)

    Box(
        // A finger is about nine millimetres across and the icon is twenty-four
        // density pixels. The grab area has to be bigger than the drawing.
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start() },
                    onDrag = { change, delta ->
                        // Consumed so the list underneath scrolls instead of
                        // fighting the drag for the same finger.
                        change.consume()
                        drag(delta.y)
                    },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = "Hold and drag to move this",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
