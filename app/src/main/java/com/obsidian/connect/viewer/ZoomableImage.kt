package com.obsidian.connect.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A photo you can pinch into and drag around.
 *
 * Tapping deliberately does nothing. A tap is how a pan begins, so closing on
 * one made zooming impossible — every attempt to reach into the picture shut
 * it instead. Closing is left to an explicit control and the back gesture.
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    maxScale: Float = 5f,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val liveScale by rememberUpdatedState(scale)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (liveScale * zoom).coerceIn(1f, maxScale)

                    // Panning is only allowed as far as there is picture off
                    // screen to reach. Without this the photo can be flung
                    // away and there is no way to find it again.
                    val maxX = max(0f, (size.width * (next - 1f)) / 2f)
                    val maxY = max(0f, (size.height * (next - 1f)) / 2f)

                    scale = next
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)

                    // Snapping home at 1x saves a fiddly drag back to centre
                    // after pinching out.
                    if (abs(next - 1f) < 0.01f) {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (liveScale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = min(maxScale, 2.5f)
                            // Zoom toward the point tapped rather than the
                            // centre, so a detail stays under the finger.
                            offsetX = (size.width / 2f - tap.x) * (scale - 1f)
                            offsetY = (size.height / 2f - tap.y) * (scale - 1f)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val animatedScale by animateFloatAsState(scale, label = "zoom")

        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}
