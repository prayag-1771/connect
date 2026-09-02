package com.obsidian.connect.archive

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Every photo the two of you have exchanged, on this phone only.
 *
 * A separate activity rather than a tab: it is somewhere you go occasionally to
 * look back, not one of the things the app is for day to day.
 */
@AndroidEntryPoint
class ArchiveActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                ArchiveScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Read once. The archive only changes while photos are arriving, and
    // re-listing a directory on every recomposition would be wasteful.
    val entries = remember { PhotoArchive.list(context) }
    var viewing by remember { mutableStateOf<PhotoArchive.Entry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing here yet.\nPhotos you send and receive are kept " +
                        "on this phone, inside the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.padding(insets),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = entries, key = { it.file.path }) { entry ->
                    Thumbnail(entry = entry, onClick = { viewing = entry })
                }
            }
        }
    }

    viewing?.let { entry ->
        FullPhoto(entry = entry, onDismiss = { viewing = null })
    }
}

@Composable
private fun Thumbnail(entry: PhotoArchive.Entry, onClick: () -> Unit) {
    val bitmap = remember(entry.file.path) {
        // Downsampled for the grid. Decoding a hundred full photos at once is
        // the reliable way to run a phone out of memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(entry.file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = (bounds.outWidth / THUMB_PX).coerceAtLeast(1)
        }
        BitmapFactory.decodeFile(entry.file.path, options)?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FullPhoto(entry: PhotoArchive.Entry, onDismiss: () -> Unit) {
    val bitmap = remember(entry.file.path) {
        BitmapFactory.decodeFile(entry.file.path)?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Opaque, or the grid shows through behind the photo.
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap ?: return,
            contentDescription = "Photo, tap to close",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val THUMB_PX = 240
