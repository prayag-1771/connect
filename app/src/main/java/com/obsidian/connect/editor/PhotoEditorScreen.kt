package com.obsidian.connect.editor

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.obsidian.connect.core.model.StrokePoint
import com.obsidian.connect.draw.DrawPalette

private enum class EditorMode { Crop, Draw }

/**
 * Crop and draw on a photo before it is sent.
 *
 * Shown between choosing a photo and sending it, from chat and from the choice
 * deck alike. The photo is displayed whole and fitted rather than filling the
 * screen: both tools need screen coordinates to map onto image coordinates,
 * and a cropped display shows only part of the picture — so a mark or a crop
 * edge would land somewhere other than where it was put.
 */
@Composable
fun PhotoEditorScreen(
    jpeg: ByteArray,
    onCancel: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A dialog rather than a layer inside the screen. Drawn in the host's
    // content, the app's bottom navigation bar covered the crop and confirm
    // controls entirely — and worse, remained tappable, so a stray press on
    // another tab would throw the edit away mid-crop.
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        EditorContent(
            jpeg = jpeg,
            onCancel = onCancel,
            onConfirm = onConfirm,
            modifier = modifier,
        )
    }
}

@Composable
private fun EditorContent(
    jpeg: ByteArray,
    onCancel: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
    val aspect = remember(bitmap) {
        if (bitmap == null || bitmap.height == 0) 1f
        else bitmap.width.toFloat() / bitmap.height.toFloat()
    }

    var mode by remember { mutableStateOf(EditorMode.Crop) }
    var crop by remember { mutableStateOf(CropRect()) }
    var colour by remember { mutableStateOf(DrawPalette.Chalk) }
    val strokes = remember { mutableListOf<EditStroke>().toMutableStateList() }
    val current = remember { mutableListOf<StrokePoint>().toMutableStateList() }
    var working by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (mode == EditorMode.Draw && strokes.isNotEmpty()) {
                IconButton(onClick = { strokes.removeLastOrNull() }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo the last mark",
                        tint = Color.White,
                    )
                }
            }
            if (mode == EditorMode.Crop && !crop.isWholeImage) {
                TextButton(onClick = { crop = CropRect() }) {
                    Text("Reset", color = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspect)) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                when (mode) {
                    EditorMode.Crop -> CropLayer(
                        crop = crop,
                        onCrop = { crop = it },
                        modifier = Modifier.fillMaxSize(),
                    )

                    EditorMode.Draw -> DrawLayer(
                        strokes = strokes,
                        current = current,
                        colour = colour,
                        // Drawn inside the crop, since that is the frame the
                        // marks are stored against.
                        crop = crop,
                        onFinish = { points ->
                            if (points.size >= 2) {
                                strokes.add(
                                    EditStroke(points.toList(), colour, PEN_WIDTH_FRACTION),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (mode == EditorMode.Draw) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                            .clickable { colour = swatch },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text(
            text = if (mode == EditorMode.Crop) {
                "Drag the corners to crop, or the middle to move it"
            } else {
                "Draw on the photo"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == EditorMode.Crop,
                onClick = { mode = EditorMode.Crop },
                label = { Text("Crop") },
            )
            FilterChip(
                selected = mode == EditorMode.Draw,
                onClick = { mode = EditorMode.Draw },
                label = { Text("Draw") },
            )

            Spacer(Modifier.weight(1f))

            FilledIconButton(
                onClick = {
                    working = true
                    onConfirm(ImageEdits.apply(jpeg, crop, strokes.toList()))
                },
                enabled = !working,
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Use this photo")
            }
        }
    }
}

/**
 * The crop rectangle and its handles.
 *
 * Corners are hit-tested at a generous radius because a fingertip is far
 * larger than the dot drawn for it, and a crop handle you cannot reliably grab
 * is worse than no handle at all.
 */
@Composable
private fun CropLayer(
    crop: CropRect,
    onCrop: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(Corner.None) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { start ->
                    dragging = nearestCorner(start, crop, size.width, size.height)
                },
                onDrag = { change, delta ->
                    change.consume()
                    val dx = delta.x / size.width
                    val dy = delta.y / size.height
                    onCrop(crop.moved(dragging, dx, dy))
                },
                onDragEnd = { dragging = Corner.None },
                onDragCancel = { dragging = Corner.None },
            )
        },
    ) {
        val rect = Rect(
            offset = Offset(crop.left * size.width, crop.top * size.height),
            size = Size(crop.width * size.width, crop.height * size.height),
        )

        // Everything outside the crop dimmed, using an even-odd fill so the
        // overlap is a hole rather than another filled region.
        val mask = Path().apply {
            addRect(Rect(Offset.Zero, size))
            addRect(rect)
            fillType = PathFillType.EvenOdd
        }
        drawPath(mask, Color.Black.copy(alpha = 0.6f))

        drawRect(
            color = Color.White,
            topLeft = rect.topLeft,
            size = rect.size,
            style = DrawStroke(width = 2.dp.toPx()),
        )

        listOf(
            rect.topLeft,
            Offset(rect.right, rect.top),
            Offset(rect.left, rect.bottom),
            rect.bottomRight,
        ).forEach { drawCircle(Color.White, radius = 8.dp.toPx(), center = it) }
    }
}

private enum class Corner { TopLeft, TopRight, BottomLeft, BottomRight, Inside, None }

private fun nearestCorner(at: Offset, crop: CropRect, width: Int, height: Int): Corner {
    val grab = minOf(width, height) * 0.12f
    val corners = mapOf(
        Corner.TopLeft to Offset(crop.left * width, crop.top * height),
        Corner.TopRight to Offset(crop.right * width, crop.top * height),
        Corner.BottomLeft to Offset(crop.left * width, crop.bottom * height),
        Corner.BottomRight to Offset(crop.right * width, crop.bottom * height),
    )

    val closest = corners.minByOrNull { (_, point) -> (point - at).getDistance() }
    if (closest != null && (closest.value - at).getDistance() <= grab) return closest.key

    val inside = at.x / width in crop.left..crop.right && at.y / height in crop.top..crop.bottom
    return if (inside) Corner.Inside else Corner.None
}

/** Never lets an edge cross its opposite, or the rectangle inverts. */
private fun CropRect.moved(corner: Corner, dx: Float, dy: Float): CropRect {
    val min = 0.1f
    return when (corner) {
        Corner.TopLeft -> copy(
            left = (left + dx).coerceIn(0f, right - min),
            top = (top + dy).coerceIn(0f, bottom - min),
        )

        Corner.TopRight -> copy(
            right = (right + dx).coerceIn(left + min, 1f),
            top = (top + dy).coerceIn(0f, bottom - min),
        )

        Corner.BottomLeft -> copy(
            left = (left + dx).coerceIn(0f, right - min),
            bottom = (bottom + dy).coerceIn(top + min, 1f),
        )

        Corner.BottomRight -> copy(
            right = (right + dx).coerceIn(left + min, 1f),
            bottom = (bottom + dy).coerceIn(top + min, 1f),
        )

        Corner.Inside -> {
            // Shifted as a whole, clamped so it cannot be pushed off the image.
            val shiftX = dx.coerceIn(-left, 1f - right)
            val shiftY = dy.coerceIn(-top, 1f - bottom)
            copy(
                left = left + shiftX,
                right = right + shiftX,
                top = top + shiftY,
                bottom = bottom + shiftY,
            )
        }

        Corner.None -> this
    }
}

@Composable
private fun DrawLayer(
    strokes: List<EditStroke>,
    current: androidx.compose.runtime.snapshots.SnapshotStateList<StrokePoint>,
    colour: Long,
    crop: CropRect,
    onFinish: (List<StrokePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(crop) {
            detectDragGestures(
                onDragStart = { offset ->
                    current.clear()
                    current.add(offset.intoCrop(crop, size.width, size.height))
                },
                onDrag = { change, _ ->
                    change.consume()
                    current.add(change.position.intoCrop(crop, size.width, size.height))
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
        },
    ) {
        strokes.forEach { drawMark(it.points, Color(it.color.toInt()), it.widthFraction, crop) }
        drawMark(current, Color(colour.toInt()), PEN_WIDTH_FRACTION, crop)
    }
}

/**
 * Screen point to a fraction of the *cropped* image.
 *
 * Stored against the crop because that is what survives to be sent; against
 * the whole photo, every mark would shift the moment the crop changed.
 */
private fun Offset.intoCrop(crop: CropRect, width: Int, height: Int): StrokePoint {
    val x = if (width > 0) x / width else 0f
    val y = if (height > 0) y / height else 0f
    return StrokePoint(
        x = ((x - crop.left) / crop.width.coerceAtLeast(0.001f)).coerceIn(0f, 1f),
        y = ((y - crop.top) / crop.height.coerceAtLeast(0.001f)).coerceIn(0f, 1f),
    )
}

private fun DrawScope.drawMark(
    points: List<StrokePoint>,
    colour: Color,
    widthFraction: Float,
    crop: CropRect,
) {
    if (points.isEmpty()) return

    fun place(p: StrokePoint) = Offset(
        (crop.left + p.x * crop.width) * size.width,
        (crop.top + p.y * crop.height) * size.height,
    )

    val stroke = (widthFraction * size.width * crop.width).coerceAtLeast(1f)

    if (points.size == 1) {
        drawCircle(colour, stroke / 2f, place(points.first()))
        return
    }

    val path = Path().apply {
        val first = place(points.first())
        moveTo(first.x, first.y)
        points.drop(1).forEach { val o = place(it); lineTo(o.x, o.y) }
    }
    drawPath(path, colour, style = DrawStroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private const val PEN_WIDTH_FRACTION = 0.012f
