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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import coil.compose.AsyncImage
import java.io.File

enum class AttachmentTab { Gifs, Starred, Saved }

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

            AttachmentTab.Starred -> StarredGifTab(onSend = onSendGif)

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
            // Only while there is nothing to look at yet. Once results are on
            // screen, loading is shown over them rather than replacing them.
            loading && gifs.isEmpty() -> CircularProgressIndicator()

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
                    val gifContext = LocalContext.current
                    var starred by remember(gif.id) {
                        mutableStateOf(GifStore.isStarred(gifContext, gif.sendUrl))
                    }

                    Box {
                        AsyncImage(
                            model = gif.previewUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSend(gif) },
                        )

                        // A corner button rather than a long press. Long press
                        // on a small tile is easy to trigger by accident when
                        // the tap next to it sends something.
                        IconButton(
                            onClick = {
                                GifStore.toggle(gifContext, gif.sendUrl)
                                starred = !starred
                            },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (starred) {
                                    Icons.Filled.Star
                                } else {
                                    Icons.Outlined.StarBorder
                                },
                                contentDescription = if (starred) "Unstar" else "Star",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // One spinner for the whole panel, over whatever is already there.
        //
        // A search replaces the results wholesale, so the old grid stays put
        // and dims rather than vanishing - and because this sits in the Box
        // rather than inside the grid, it stays centred on screen however far
        // down somebody has scrolled.
        if (loading && gifs.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
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

/**
 * The GIFs you kept.
 *
 * Only URLs are stored, so this is a grid of requests rather than a folder -
 * which is also why it needs no clearing and costs no space.
 */
@Composable
private fun StarredGifTab(onSend: (GifSearch.Gif) -> Unit) {
    val context = LocalContext.current

    // Re-read on each change rather than observed, because the only thing that
    // edits this list is the star button a few pixels away.
    var version by remember { mutableIntStateOf(0) }
    val urls = remember(version) { GifStore.starred(context) }

    if (urls.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Star a GIF and it waits here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items = urls, key = { it }) { url ->
            Box {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onSend(
                                GifSearch.Gif(
                                    id = url,
                                    previewUrl = url,
                                    sendUrl = url,
                                ),
                            )
                        },
                )

                IconButton(
                    onClick = {
                        GifStore.toggle(context, url)
                        version++
                    },
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Unstar",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
