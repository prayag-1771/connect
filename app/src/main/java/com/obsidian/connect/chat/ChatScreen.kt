package com.obsidian.connect.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.obsidian.connect.core.model.DeliveryStatus
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.core.model.deliveryStatusOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val myUid = viewModel.myUid
    val context = LocalContext.current

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The system photo picker needs no storage permission at all — it hands
    // back a single grant for exactly what was chosen.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::sendPhoto) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startRecording() }

    val partnerReceipt by viewModel.partnerReceipt.collectAsStateWithLifecycle()
    val gifs by viewModel.gifs.collectAsStateWithLifecycle()
    val gifsLoading by viewModel.gifsLoading.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var panelOpen by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf(AttachmentTab.Gifs) }
    var gifQuery by remember { mutableStateOf("") }

    // Adds to the saved collection rather than sending, which is the whole
    // point of it being a separate store.
    val stickerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::saveSticker) }

    LaunchedEffect(panelOpen, panelTab) {
        if (!panelOpen) return@LaunchedEffect
        when (panelTab) {
            AttachmentTab.Gifs -> if (gifs.isEmpty()) viewModel.searchGifs("")
            AttachmentTab.Saved -> viewModel.refreshSaved()
        }
    }

    val player = remember { VoicePlayer(context) }
    var playingId by remember { mutableStateOf<String?>(null) }

    // Released with the screen, or a note carries on playing after you leave.
    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    // Whether the list has been positioned at least once.
    var positioned by remember { mutableStateOf(false) }

    // Marking read here rather than at app launch: opening the camera tab
    // should not quietly clear the dot for messages nobody has looked at.
    LaunchedEffect(messages.size) {
        viewModel.markRead()
        viewModel.archiveIncoming(messages)
        viewModel.markProgress(messages)
        if (messages.isEmpty()) return@LaunchedEffect

        if (positioned) {
            // A message arrived while you were looking; sliding to it shows
            // that something moved.
            listState.animateScrollToItem(messages.lastIndex)
        } else {
            // Opening the tab should simply start at the bottom. Animating
            // here scrolls the whole history past you first, which reads as
            // the screen running away.
            listState.scrollToItem(messages.lastIndex)
            positioned = true
        }
    }

    // The scaffold reserves room for the bottom navigation bar, and the
    // keyboard covers that bar when it opens. Adding both leaves a gap the
    // height of the bar between the input row and the keyboard, so take
    // whichever is actually taller.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val barBottom = contentPadding.calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = maxOf(imeBottom, barBottom),
            ),
    ) {
        if (messages.isEmpty()) {
            EmptyConversation(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = messages, key = { it.id }) { message ->
                    Bubble(
                        message = message,
                        mine = message.senderId == myUid,
                        status = deliveryStatusOf(message, partnerReceipt),
                        playing = playingId == message.id,
                        onTogglePlay = {
                            message.audioBytes?.let { bytes ->
                                player.toggle(message.id, bytes) { playingId = null }
                                playingId = player.currentlyPlaying()
                            }
                        },
                    )
                }
            }
        }

        if (panelOpen) {
            AttachmentPanel(
                tab = panelTab,
                onTab = { panelTab = it },
                gifs = gifs,
                gifsLoading = gifsLoading,
                gifQuery = gifQuery,
                onGifQuery = {
                    gifQuery = it
                    viewModel.searchGifs(it)
                },
                onSendGif = {
                    viewModel.sendGif(it)
                    panelOpen = false
                },
                saved = saved,
                onSendSaved = {
                    viewModel.sendSaved(it)
                    panelOpen = false
                },
                onAddSaved = {
                    stickerPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }

        if (recording) {
            Text(
                text = "Recording — tap stop to send",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !recording,
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Send a photo")
            }

            IconButton(
                onClick = { panelOpen = !panelOpen },
                enabled = !recording,
            ) {
                // A GIF box, not a smiley. The keyboard already puts an
                // emoji button a couple of centimetres away, and two smiling
                // faces side by side inviting different things is a trap.
                Icon(
                    imageVector = Icons.Outlined.GifBox,
                    contentDescription = if (panelOpen) "Close" else "GIFs and saved images",
                    tint = if (panelOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (recording) "Recording…" else "Say something") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                enabled = !recording,
                modifier = Modifier.weight(1f),
            )

            // The send button becomes a microphone with nothing typed — one
            // control, showing whichever thing you are about to do.
            if (draft.isBlank()) {
                FilledIconButton(
                    onClick = {
                        when {
                            recording -> viewModel.stopRecordingAndSend()

                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED -> viewModel.startRecording()

                            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = if (recording) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    Icon(
                        imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (recording) {
                            "Stop and send"
                        } else {
                            "Record a voice note"
                        },
                    )
                }
            } else {
                FilledIconButton(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    message: Message,
    mine: Boolean,
    status: DeliveryStatus,
    playing: Boolean,
    onTogglePlay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (mine) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        // The squared-off corner points at whoever sent it,
                        // which is quicker to read than colour alone.
                        bottomStart = if (mine) 18.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (message.hasImage) {
                // Decoded once per message rather than on every recomposition,
                // which matters in a list that scrolls.
                val bitmap = remember(message.id) {
                    message.bytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }

            if (message.hasGif) {
                AsyncImage(
                    model = message.gifUrl,
                    contentDescription = "GIF",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            if (message.hasAudio) {
                VoiceNote(
                    durationMs = message.audioDurationMs,
                    playing = playing,
                    mine = mine,
                    onToggle = onTogglePlay,
                )
            }

            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = if (message.hasImage) 6.dp else 0.dp),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (message.createdAtMillis > 0) {
                    Text(
                        text = timeFormat.format(Date(message.createdAtMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Only on your own messages. Showing how far theirs got would
                // be telling them something they already know.
                if (mine) DeliveryRail(status = status)
            }
        }
    }
}

/**
 * A voice note bubble.
 *
 * Length comes from the stored duration rather than from decoding the clip, so
 * a list of notes lays out without touching the audio at all.
 */
@Composable
private fun VoiceNote(
    durationMs: Long,
    playing: Boolean,
    mine: Boolean,
    onToggle: () -> Unit,
) {
    val tint = if (mine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Stop" else "Play",
            tint = tint,
        )
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}

private fun formatDuration(millis: Long): String {
    val total = (millis / 1000).toInt().coerceAtLeast(1)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Nothing here yet.\nWhatever you send lands on their watch face too.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
