package com.obsidian.connect.call

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.CallState
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * A call, with faces and optionally a screen.
 *
 * Its own activity rather than a screen in the tab bar: a call has to survive
 * whatever else the app is doing, and it needs the whole display.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val incoming = intent.getBooleanExtra(EXTRA_INCOMING, false)

        setContent {
            ConnectTheme {
                CallScreen(
                    incoming = incoming,
                    onFinished = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_INCOMING = "incoming"

        fun place(context: Context) {
            context.startActivity(
                Intent(context, CallActivity::class.java)
                    .putExtra(EXTRA_INCOMING, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun answer(context: Context) {
            context.startActivity(
                Intent(context, CallActivity::class.java)
                    .putExtra(EXTRA_INCOMING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun CallScreen(
    incoming: Boolean,
    onFinished: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val local by viewModel.localTrack.collectAsStateWithLifecycle()
    val remoteFace by viewModel.remoteFace.collectAsStateWithLifecycle()
    val remoteScreen by viewModel.remoteScreen.collectAsStateWithLifecycle()

    // Camera and microphone first. Starting a call without them would produce a
    // connection that carries nothing.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            if (incoming) viewModel.answer() else viewModel.place()
        } else {
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    // Android will not let an app capture the screen without asking every time.
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    val projectionManager = activityContext.getSystemService(MediaProjectionManager::class.java)

    val screenPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // The service has to exist before capture begins, or the system
            // refuses the projection outright.
            ScreenShareService.start(activityContext)
            viewModel.startScreenShare(data)
        }
    }

    LaunchedEffect(ui.state) {
        if (ui.state == CallState.Ended) onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C10)),
    ) {
        val egl = viewModel.eglContext

        // Their screen takes the whole display when it is coming through; their
        // face falls back to the corner beside yours. When it is only a call,
        // the face gets the display instead.
        val main = remoteScreen ?: remoteFace
        if (egl != null && main != null) {
            VideoSurface(
                track = main,
                eglContext = egl,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        ui.connected -> "Connected"
                        ui.outgoing -> "Ringing..."
                        else -> "Connecting..."
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Both faces stay visible while a screen is being shared - that is
            // the entire point of watching something together rather than
            // simply being sent a video.
            if (remoteScreen != null && remoteFace != null && egl != null) {
                VideoSurface(
                    track = remoteFace!!,
                    eglContext = egl,
                    modifier = Modifier
                        .width(96.dp)
                        .height(128.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            if (egl != null && local != null) {
                VideoSurface(
                    track = local!!,
                    eglContext = egl,
                    mirror = true,
                    modifier = Modifier
                        .width(96.dp)
                        .height(128.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallButton(
                icon = if (ui.micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = "Microphone",
                active = ui.micOn,
                onClick = viewModel::toggleMic,
            )
            CallButton(
                icon = if (ui.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = "Camera",
                active = ui.cameraOn,
                onClick = viewModel::toggleCamera,
            )
            CallButton(
                icon = Icons.Filled.Cameraswitch,
                label = "Flip camera",
                active = true,
                onClick = viewModel::flipCamera,
            )
            CallButton(
                icon = if (ui.sharingScreen) {
                    Icons.Filled.StopScreenShare
                } else {
                    Icons.Filled.ScreenShare
                },
                label = "Share screen",
                active = ui.sharingScreen,
                onClick = {
                    if (ui.sharingScreen) {
                        viewModel.stopScreenShare()
                        ScreenShareService.stop(activityContext)
                    } else {
                        projectionManager?.createScreenCaptureIntent()
                            ?.let { screenPermission.launch(it) }
                    }
                },
            )
            CallButton(
                icon = Icons.Filled.CallEnd,
                label = "Hang up",
                active = false,
                danger = true,
                onClick = viewModel::hangUp,
            )
        }
    }
}

@Composable
private fun CallButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = when {
            danger -> IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White,
            )
            active -> IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.9f),
                contentColor = Color(0xFF0B0C10),
            )
            else -> IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.18f),
                contentColor = Color.White,
            )
        },
    ) {
        Icon(imageVector = icon, contentDescription = label)
    }
}
