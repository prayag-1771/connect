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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.camera.CameraCapture
import com.obsidian.connect.camera.Lens
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

            FramingControls(
                onFlip = { lens = if (lens == Lens.Front) Lens.Back else Lens.Front },
                onShutter = { captureRequested = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            ReviewControls(
                jpeg = shot,
                caption = caption,
                onCaptionChange = { caption = it },
                sending = state.sending,
                onDiscard = {
                    captured = null
                    caption = ""
                },
                onSend = { viewModel.send(shot, caption) },
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

@Composable
private fun ReviewControls(
    jpeg: ByteArray,
    caption: String,
    onCaptionChange: (String) -> Unit,
    sending: Boolean,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    // Keyed on the array so a new photo decodes, but scrolling and typing do
    // not re-decode the same JPEG on every recomposition.
    val bitmap = remember(jpeg) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "The photo you just took",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        IconButton(
            onClick = onDiscard,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Discard and retake",
                tint = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            OutlinedTextField(
                value = caption,
                onValueChange = onCaptionChange,
                placeholder = { Text("Say something (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            FilledIconButton(
                onClick = onSend,
                enabled = !sending,
                modifier = Modifier
                    .align(Alignment.End)
                    .size(64.dp),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send to their home screen",
                    )
                }
            }
        }
    }
}
