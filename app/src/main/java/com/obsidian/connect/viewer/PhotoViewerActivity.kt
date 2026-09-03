package com.obsidian.connect.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.obsidian.connect.ui.theme.ConnectTheme
import java.io.File

/**
 * Full-screen photo, pinch to zoom.
 *
 * An activity rather than an overlay for the same reason the editor is one: a
 * layer inside the app sits under the bottom navigation bar, which both covers
 * part of the picture and stays tappable behind it.
 *
 * Photos arrive by file path. An intent crosses a Binder transaction far
 * smaller than these images can be.
 */
class PhotoViewerActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val path = intent.getStringExtra(EXTRA_PATH)
        val deletable = intent.getBooleanExtra(EXTRA_DELETABLE, false)
        val bitmap = path?.let { BitmapFactory.decodeFile(it) }

        if (bitmap == null) {
            finish()
            return
        }

        setContent {
            ConnectTheme {
                Viewer(
                    image = bitmap.asImageBitmap(),
                    deletable = deletable,
                    onClose = { finish() },
                    onDelete = {
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DELETED, true))
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "photo_path"
        private const val EXTRA_DELETABLE = "deletable"
        private const val EXTRA_DELETED = "deleted"

        /** For a photo already sitting in a file, such as an archived one. */
        fun intent(context: Context, path: String, deletable: Boolean = false): Intent =
            Intent(context, PhotoViewerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_DELETABLE, deletable)

        /**
         * For a photo held in memory, such as one inside a message.
         *
         * Written to the cache first, which the system clears on its own.
         */
        fun open(context: Context, bytes: ByteArray) {
            val file = File(context.cacheDir, "view_${System.currentTimeMillis()}.jpg")
            runCatching { file.writeBytes(bytes) }
                .onSuccess { context.startActivity(intent(context, file.absolutePath)) }
        }

        fun wasDeleted(data: Intent?): Boolean =
            data?.getBooleanExtra(EXTRA_DELETED, false) == true
    }
}

@Composable
private fun Viewer(
    image: androidx.compose.ui.graphics.ImageBitmap,
    deletable: Boolean,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ZoomableImage(
            bitmap = image,
            contentDescription = "Photo. Pinch to zoom, double tap to fill.",
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }

            if (deletable) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    IconButton(onClick = { confirming = true }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete this photo",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this photo?") },
            text = {
                Text(
                    "This is the only copy on this phone. It is not in your " +
                        "gallery, so it cannot be recovered.",
                )
            },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep it") }
            },
        )
    }
}
