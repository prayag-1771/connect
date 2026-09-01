package com.obsidian.connect.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** Which way the camera is pointing. */
enum class Lens { Front, Back }

/**
 * A live camera preview that hands back JPEG bytes when [captureRequested]
 * flips to true.
 *
 * Photos are captured straight into memory instead of to a file. The bytes are
 * compressed and uploaded immediately, so writing them to disk first would only
 * add a temp file to clean up and a copy of a private photo left lying in app
 * storage.
 */
@Composable
fun CameraCapture(
    lens: Lens,
    captureRequested: Boolean,
    onCaptured: (ByteArray) -> Unit,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Rebinding on lens change is the documented way to switch cameras; a
    // provider can only have one camera bound to a lifecycle at a time.
    LaunchedEffect(hasPermission, lens) {
        if (!hasPermission) return@LaunchedEffect

        runCatching {
            val provider = ProcessCameraProvider.awaitInstance(context)
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                lens.toSelector(),
                preview,
                imageCapture,
            )
        }.onFailure(onError)
    }

    LaunchedEffect(captureRequested) {
        if (!captureRequested || !hasPermission) return@LaunchedEffect

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context) as Executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // The proxy holds a buffer from a fixed-size pool. Failing
                    // to close it stalls every later capture with no error.
                    image.use { onCaptured(it.toJpegBytes()) }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun Lens.toSelector(): CameraSelector = when (this) {
    Lens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
    Lens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** ImageCapture in JPEG mode puts the whole encoded image in a single plane. */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
