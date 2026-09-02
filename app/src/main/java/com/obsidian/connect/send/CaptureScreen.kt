package com.obsidian.connect.send

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.camera.CameraCapture
import com.obsidian.connect.camera.Lens
import com.obsidian.connect.widget.WatchFaceRenderer
import kotlinx.coroutines.delay

/**
 * Take a photo and put it on the other person's home screen.
 *
 * Two states: framing through the live camera, then reviewing what was caught
 * before it goes anywhere. The review step exists because this photo lands
 * somewhere the other person cannot dismiss — it sits on their home screen
 * until it is replaced — which is a bad place to discover a bad shot.
 */
@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    viewModel: SendMomentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var lens by remember { mutableStateOf(Lens.Back) }
    var captureRequested by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<ByteArray?>(null) }
    var caption by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Back to framing once it has gone, ready for the next one.
    LaunchedEffect(state.sent) {
        if (state.sent) {
            captured = null
            caption = ""
            viewModel.consumeResult()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val shot = captured

        if (shot == null) {
            CameraCapture(
                lens = lens,
                captureRequested = captureRequested,
                onCaptured = { bytes ->
                    captured = bytes
                    // Reset so the next tap retriggers the capture effect.
                    captureRequested = false
                },
                onError = {
                    captureRequested = false
                    error = it.message ?: "The camera didn't cooperate"
                },
                modifier = Modifier.fillMaxSize(),
            )

            CameraFrameHint(modifier = Modifier.fillMaxSize())

            FramingControls(
                onFlip = { lens = if (lens == Lens.Front) Lens.Back else Lens.Front },
                onShutter = { captureRequested = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            ReviewScreen(
                jpeg = shot,
                caption = caption,
                onCaptionChange = { caption = it },
                sending = state.sending,
                onDiscard = {
                    captured = null
                    caption = ""
                },
                onSend = { doodles -> viewModel.send(shot, caption, doodles) },
            )
        }

        val message = error ?: state.error
        if (message != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .navigationBarsPadding(),
            ) {
                Text(message)
            }

            LaunchedEffect(message) {
                delay(3_000)
                error = null
                viewModel.consumeResult()
            }
        }
    }
}

/**
 * Shows the part of the frame that actually reaches the watch face.
 *
 * The face centre-crops to a square and then cuts a circle out of it, so most
 * of what fills this viewfinder is discarded. Without the guide you frame a
 * shot, send it, and find the subject sliced off at the edges.
 *
 * The radius comes from the renderer rather than a matching constant here —
 * two copies of the same number would drift apart the first time either
 * changed.
 */
/**
 * A rough indication of the watch crop while framing.
 *
 * Approximate on purpose. The preview fills the screen by cropping the sensor
 * frame, so the viewfinder is not showing the whole photo and no circle drawn
 * here can be exact. The review screen, which displays the captured photo
 * whole, is where the real crop is shown.
 */
@Composable
private fun CameraFrameHint(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val radius = minOf(size.width, size.height) / 2f * WatchFaceRenderer.DIAL_RATIO
        val middle = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f),
            radius = radius,
            center = middle,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun FramingControls(
    onFlip: () -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.size(56.dp))

        Spacer(Modifier.size(32.dp))

        // Deliberately large and plain. A shutter is the one control that has
        // to be hittable without looking at the screen.
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(width = 4.dp, color = Color.White, shape = CircleShape)
                .padding(6.dp)
                .background(Color.White, CircleShape)
                .clickable(onClick = onShutter),
        )

        Spacer(Modifier.size(32.dp))

        IconButton(onClick = onFlip, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Filled.Cameraswitch,
                contentDescription = "Switch camera",
                tint = Color.White,
            )
        }
    }
}
