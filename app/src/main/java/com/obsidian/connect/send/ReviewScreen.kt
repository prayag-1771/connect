package com.obsidian.connect.send

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.StrokePoint
import com.obsidian.connect.draw.DrawPalette
import com.obsidian.connect.widget.WatchFaceRenderer

/**
 * Reviewing a photo before it goes, with the option to draw on it.
 *
 * The photo is shown whole rather than filling the screen. That is not a
 * styling choice: the crop guide and any doodle have to line up with the actual
 * image, and a cropped display shows only part of it — on a tall phone barely
 * sixty percent of the width — so a circle drawn across the screen would mark
 * out entirely the wrong region of the photograph.
 */
@Composable
fun ReviewScreen(
    jpeg: ByteArray,
    caption: String,
    onCaptionChange: (String) -> Unit,
    sending: Boolean,
    onDiscard: () -> Unit,
    onSend: (List<DoodleStroke>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the array so a new photo decodes, but typing does not re-decode
    // the same JPEG on every keystroke.
    val bitmap = remember(jpeg) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }
    val aspect = remember(bitmap) {
        if (bitmap == null || bitmap.height == 0) 1f
        else bitmap.width.toFloat() / bitmap.height.toFloat()
    }

    var drawing by remember { mutableStateOf(false) }
    var colour by remember { mutableStateOf(DrawPalette.Chalk) }
    val strokes = remember { mutableListOf<DoodleStroke>().toMutableStateList() }
    val current = remember { mutableListOf<StrokePoint>().toMutableStateList() }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect),
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "The photo you just took",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Both overlays share the photo's exact bounds, so screen
                // coordinates map straight onto image coordinates.
                DoodleLayer(
                    strokes = strokes,
                    current = current,
                    colour = colour,
                    enabled = drawing,
                    onFinish = { points ->
                        if (points.size >= 2) {
                            strokes.add(
                                DoodleStroke(
                                    points = points.toList(),
                                    color = colour,
                                    widthFraction = PEN_WIDTH_FRACTION,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                WatchCropGuide(modifier = Modifier.fillMaxSize())
            }
        }

        IconButton(
            onClick = onDiscard,
            modifier = Modifier.statusBarsPadding(),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Discard and retake", tint = Color.White)
        }

        Controls(
            caption = caption,
            onCaptionChange = onCaptionChange,
            drawing = drawing,
            onToggleDrawing = { drawing = !drawing },
            colour = colour,
            onColour = { colour = it },
            canUndo = strokes.isNotEmpty(),
            onUndo = { strokes.removeLastOrNull() },
            sending = sending,
            onSend = { onSend(strokes.toList()) },
        )
    }
}

@Composable
private fun DoodleLayer(
    strokes: List<DoodleStroke>,
    current: SnapshotStateList<StrokePoint>,
    colour: Long,
    enabled: Boolean,
    onFinish: (List<StrokePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = if (!enabled) {
            modifier
        } else {
            modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        current.clear()
                        current.add(offset.normalised(size.width, size.height))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        current.add(change.position.normalised(size.width, size.height))
                    },
                    onDragEnd = {
                        onFinish(current.toList())
                        current.clear()
                    },
                    onDragCancel = {
                        onFinish(current.toList())
                        current.clear()
                    },
                )
            }
        },
    ) {
        strokes.forEach { drawDoodle(it.points, Color(it.color.toInt()), it.widthFraction) }
        drawDoodle(current, Color(colour.toInt()), PEN_WIDTH_FRACTION)
    }
}

private fun DrawScope.drawDoodle(points: List<StrokePoint>, colour: Color, widthFraction: Float) {
    if (points.isEmpty()) return
    val stroke = (widthFraction * size.width).coerceAtLeast(1f)

    if (points.size == 1) {
        val only = points.first()
        drawCircle(colour, stroke / 2f, Offset(only.x * size.width, only.y * size.height))
        return
    }

    val path = Path().apply {
        moveTo(points.first().x * size.width, points.first().y * size.height)
        points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
    }
    drawPath(path, colour, style = DrawStroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The circle that actually reaches the watch face.
 *
 * Drawn over the photo's own bounds, so the ring marks the real crop rather
 * than an arbitrary circle on the screen.
 */
@Composable
private fun WatchCropGuide(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = minOf(size.width, size.height) / 2f * WatchFaceRenderer.DIAL_RATIO
        val middle = Offset(size.width / 2f, size.height / 2f)

        // A rectangle with the circle punched out. Even-odd is what makes the
        // overlap a hole rather than another filled region.
        val mask = Path().apply {
            addRect(Rect(Offset.Zero, size))
            addOval(Rect(center = middle, radius = radius))
            fillType = PathFillType.EvenOdd
        }

        drawPath(mask, Color.Black.copy(alpha = 0.45f))
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = radius,
            center = middle,
            style = DrawStroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun Controls(
    caption: String,
    onCaptionChange: (String) -> Unit,
    drawing: Boolean,
    onToggleDrawing: () -> Unit,
    colour: Long,
    onColour: (Long) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(12.dp)
            .navigationBarsPadding(),
    ) {
        if (drawing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DrawPalette.colors.forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(if (swatch == colour) 32.dp else 26.dp)
                            .background(Color(swatch.toInt()), CircleShape)
                            .border(
                                width = if (swatch == colour) 3.dp else 1.dp,
                                color = Color.White.copy(alpha = 0.8f),
                                shape = CircleShape,
                            )
                            .clickable { onColour(swatch) },
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo the last mark",
                        tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = onToggleDrawing,
                colors = if (drawing) {
                    IconButtonDefaults.filledIconButtonColors()
                } else {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                    )
                },
            ) {
                Icon(Icons.Outlined.Brush, contentDescription = "Draw on this photo")
            }

            OutlinedTextField(
                value = caption,
                onValueChange = onCaptionChange,
                placeholder = { Text("Say something (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )

            FilledIconButton(onClick = onSend, enabled = !sending) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send to their home screen",
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

private fun Offset.normalised(width: Int, height: Int): StrokePoint = StrokePoint(
    x = if (width > 0) (x / width).coerceIn(0f, 1f) else 0f,
    y = if (height > 0) (y / height).coerceIn(0f, 1f) else 0f,
)

/** Pen weight as a fraction of the photo's width, so it scales with the image. */
private const val PEN_WIDTH_FRACTION = 0.012f
