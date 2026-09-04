package com.obsidian.connect.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.viewer.PhotoViewerActivity

/**
 * Photos sent together, drawn as one block.
 *
 * Five photos as five bubbles is five times the scrolling for one moment, and
 * loses the fact that they were sent together at all. A grid says it was one
 * action - which is what it was.
 *
 * At most four are shown. Past that the fourth carries the count, because a
 * grid that keeps growing stops being glanceable, and anybody who wants the
 * rest is going to open it anyway.
 */
@Composable
fun PhotoAlbum(
    messages: List<Message>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Decoded once per album rather than on every recomposition, which matters
    // in a list that scrolls.
    val loaded = remember(messages.map { it.id }) {
        // Oldest first. The list they arrived in is newest-first so the chat
        // can draw bottom-up, but inside one batch the order they were picked
        // in is the order they should read in.
        messages.sortedBy { it.createdAtMillis }.mapNotNull { message ->
            val bytes = message.bytes ?: PhotoArchive.bytesFor(context, message.id)
            bytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()?.let { bitmap ->
                    bitmap to it
                }
            }
        }
    }

    if (loaded.isEmpty()) return

    val shown = loaded.take(MAX_SHOWN)
    val hidden = loaded.size - shown.size

    Column(
        modifier = modifier.width(240.dp),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        shown.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GAP),
            ) {
                row.forEachIndexed { columnIndex, (bitmap, bytes) ->
                    val index = rowIndex * 2 + columnIndex
                    val last = index == shown.lastIndex

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { PhotoViewerActivity.open(context, bytes) },
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Photo, tap to open",
                            // Crop here, not Fit. A grid of mixed shapes with
                            // letterboxing reads as broken; a photo cropped to
                            // a square thumbnail reads as a thumbnail, and the
                            // whole frame is one tap away.
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (last && hidden > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+$hidden",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                // An odd photo on the last row keeps its half rather than
                // stretching across, so the grid stays a grid.
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

private val GAP = 3.dp

/** Four fills a square grid; past that the fourth carries the count. */
private const val MAX_SHOWN = 4
