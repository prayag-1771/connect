package com.obsidian.connect.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

enum class AttachmentTab { Gifs, Saved }

/**
 * The GIF and saved-image drawer that sits above the keyboard.
 */
@Composable
fun AttachmentPanel(
    tab: AttachmentTab,
    onTab: (AttachmentTab) -> Unit,
    gifs: List<GifSearch.Gif>,
    gifsLoading: Boolean,
    gifQuery: String,
    onGifQuery: (String) -> Unit,
    onSendGif: (GifSearch.Gif) -> Unit,
    saved: List<File>,
    onSendSaved: (File) -> Unit,
    onAddSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = tab == AttachmentTab.Gifs,
                onClick = { onTab(AttachmentTab.Gifs) },
                label = { Text("GIFs") },
            )
            FilterChip(
                selected = tab == AttachmentTab.Saved,
                onClick = { onTab(AttachmentTab.Saved) },
                label = { Text("Saved") },
            )
        }

        when (tab) {
            AttachmentTab.Gifs -> GifTab(
                gifs = gifs,
                loading = gifsLoading,
                query = gifQuery,
                onQuery = onGifQuery,
                onSend = onSendGif,
            )

            AttachmentTab.Saved -> SavedTab(
                saved = saved,
                onSend = onSendSaved,
                onAdd = onAddSaved,
            )
        }
    }
}

@Composable
private fun GifTab(
    gifs: List<GifSearch.Gif>,
    loading: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSend: (GifSearch.Gif) -> Unit,
) {
    if (!GifSearch.isConfigured) {
        Box(
            modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "GIF search needs a free GIPHY API key.\nAdd it and this fills up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search GIFs") },
        singleLine = true,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )

    Box(
        modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()

            gifs.isEmpty() -> Text(
                text = "Nothing found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = gifs, key = { it.id }) { gif ->
                    AsyncImage(
                        model = gif.previewUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSend(gif) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedTab(
    saved: List<File>,
    onSend: (File) -> Unit,
    onAdd: () -> Unit,
) {
    Column {
        TextButton(onClick = onAdd, modifier = Modifier.padding(start = 8.dp)) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
            Text("  Add an image")
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(PANEL_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (saved.isEmpty()) {
                Text(
                    text = "Nothing saved yet.\nAdd memes and reaction images here to " +
                        "send them without hunting through your gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = saved, key = { it.path }) { file ->
                        SavedThumbnail(file = file, onClick = { onSend(file) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedThumbnail(file: File, onClick: () -> Unit) {
    val bitmap = remember(file.path) {
        // Downsampled: a grid of full-size decodes is the reliable way to run
        // a phone out of memory.
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = (bounds.outWidth / THUMB_PX).coerceAtLeast(1)
        }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val PANEL_HEIGHT = 220.dp
private const val THUMB_PX = 200
