package com.obsidian.connect.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Stroke
import com.obsidian.connect.core.model.StrokePoint

/**
 * A canvas both people draw on at once.
 *
 * Strokes are written on lift, not while dragging, and both sides watch the
 * same collection — so a line appears on the other phone a moment after the
 * finger leaves the glass rather than following it live. That is a deliberate
 * trade: streaming every touch sample would be a document write each, and the
 * free tier allows 20,000 writes a day.
 */
@Composable
fun DrawScreen(
    modifier: Modifier = Modifier,
    viewModel: DrawViewModel = hiltViewModel(),
) {
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()
    val color by viewModel.color.collectAsStateWithLifecycle()
    val width by viewModel.width.collectAsStateWithLifecycle()

    // The stroke under the finger, in normalised coordinates. Kept out of the
    // view model so every touch sample does not cross a StateFlow.
    val inProgress = remember { mutableListOf<StrokePoint>().toMutableStateList() }
    var confirmingClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color.White, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        ) {
            DrawCanvas(
                strokes = strokes,
                inProgress = inProgress,
                activeColor = color,
                activeWidth = width,
                onStart = { point ->
                    inProgress.clear()
                    inProgress.add(point)
                },
                onMove = { inProgress.add(it) },
                onFinish = {
                    viewModel.commit(inProgress.toList())
                    inProgress.clear()
                },
            )
        }

        Toolbar(
            selectedColor = color,
            selectedWidth = width,
            onColor = viewModel::selectColor,
            onWidth = viewModel::selectWidth,
            onClear = { confirmingClear = true },
        )
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear the canvas?") },
            // Worth a confirmation: it wipes their drawing as well as yours,
            // and there is no undo.
            text = { Text("This removes everything, for both of you.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        confirmingClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun DrawCanvas(
    strokes: List<Stroke>,
    inProgress: SnapshotStateList<StrokePoint>,
    activeColor: Long,
    activeWidth: Float,
    onStart: (StrokePoint) -> Unit,
    onMove: (StrokePoint) -> Unit,
    onFinish: () -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> onStart(offset.normalised(size.width, size.height)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onMove(change.position.normalised(size.width, size.height))
                    },
                    onDragEnd = onFinish,
                    onDragCancel = onFinish,
                )
            },
    ) {
        strokes.forEach { stroke ->
            drawStroke(stroke.points, Color(stroke.color.toInt()), stroke.width)
        }
        drawStroke(inProgress, Color(activeColor.toInt()), activeWidth)
    }
}

private fun Offset.normalised(width: Int, height: Int): StrokePoint = StrokePoint(
    x = if (width > 0) (x / width).coerceIn(0f, 1f) else 0f,
    y = if (height > 0) (y / height).coerceIn(0f, 1f) else 0f,
)

private fun DrawScope.drawStroke(points: List<StrokePoint>, color: Color, width: Float) {
    if (points.isEmpty()) return

    // A single tap has no line to draw, so render the nib itself. Without this
    // a dot simply does not appear.
    if (points.size == 1) {
        val only = points.first()
        drawCircle(
            color = color,
            radius = width / 2f,
            center = Offset(only.x * size.width, only.y * size.height),
        )
        return
    }

    val path = Path().apply {
        val first = points.first()
        moveTo(first.x * size.width, first.y * size.height)
        points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
    }

    drawPath(
        path = path,
        color = color,
        style = DrawStroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

@Composable
private fun Toolbar(
    selectedColor: Long,
    selectedWidth: Float,
    onColor: (Long) -> Unit,
    onWidth: (Float) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DrawPalette.colors.forEach { swatch ->
            Box(
                modifier = Modifier
                    .size(if (swatch == selectedColor) 34.dp else 28.dp)
                    .background(Color(swatch.toInt()), CircleShape)
                    .border(
                        width = if (swatch == selectedColor) 3.dp else 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onColor(swatch) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DrawPalette.widths.forEach { candidate ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { onWidth(candidate) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(candidate.dp.coerceAtMost(20.dp))
                                .background(
                                    if (candidate == selectedWidth) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = "Clear the canvas",
            )
        }
    }
}
