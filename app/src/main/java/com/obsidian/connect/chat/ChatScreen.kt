package com.obsidian.connect.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import com.obsidian.connect.choose.CaptureTarget
import com.obsidian.connect.choose.ChooseOverlay
import com.obsidian.connect.editor.EditPhotoContract
import kotlinx.coroutines.launch
import com.obsidian.connect.core.model.DeliveryStatus
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.core.model.deliveryStatusOf
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.viewer.PhotoViewerActivity
import kotlinx.coroutines.delay
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
    val pendingVoice by viewModel.pendingVoice.collectAsStateWithLifecycle()
    val myUid = viewModel.myUid
    val context = LocalContext.current

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var pickingSource by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The editor is its own activity, so a photo goes out to it and the
    // edited bytes come back — nothing is held on this screen in between.
    val editor = rememberLauncherForActivityResult(
        EditPhotoContract(context),
    ) { edited -> edited?.let(viewModel::sendPhoto) }

    fun openEditor(uri: Uri) {
        scope.launch { viewModel.prepare(uri)?.let(editor::launch) }
    }

    // The system photo picker needs no storage permission at all — it hands
    // back a single grant for exactly what was chosen.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(::openEditor) }

    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        if (saved) pendingCapture?.let(::openEditor)
        pendingCapture = null
        CaptureTarget.clearStale(context)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        val (_, uri) = CaptureTarget.create(context)
        pendingCapture = uri
        camera.launch(uri)
    }

    fun takePhoto() {
        val allowed = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (allowed) {
            val (_, uri) = CaptureTarget.create(context)
            pendingCapture = uri
            camera.launch(uri)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startRecording() }

    val partnerReceipt by viewModel.partnerReceipt.collectAsStateWithLifecycle()
    val gifs by viewModel.gifs.collectAsStateWithLifecycle()
    val gifsLoading by viewModel.gifsLoading.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var panelOpen by remember { mutableStateOf(false) }
    var chooseOpen by remember { mutableStateOf(false) }
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
    var playProgress by remember { mutableFloatStateOf(0f) }

    // Polled rather than driven by a listener: MediaPlayer offers no position
    // callback, and ten frames a second is enough for a bar this size.
    LaunchedEffect(playingId) {
        val id = playingId ?: return@LaunchedEffect
        while (player.currentlyPlaying() == id) {
            val total = player.durationMs()
            playProgress = if (total > 0) player.positionMs() / total.toFloat() else 0f
            delay(100)
        }
        playProgress = 0f
    }

    // Released with the screen, or a note carries on playing after you leave.
    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }

    // Marking read here rather than at app launch: opening the camera tab
    // should not quietly clear the dot for messages nobody has looked at.
    LaunchedEffect(messages.size) {
        viewModel.markRead()
        viewModel.archiveIncoming(messages)
        viewModel.markProgress(messages)
    }

    // Newest first, drawn bottom-up. Scrolling to the end after layout meant
    // one frame rendered at the top of the history before jumping — the flash
    // of the oldest messages every time the tab opened. Reversed, the list
    // starts where it should and never has to move.
    val ordered = remember(messages) { messages.asReversed() }

    // The scaffold reserves room for the bottom navigation bar, and the
    // keyboard covers that bar when it opens. Adding both leaves a gap the
    // height of the bar between the input row and the keyboard, so take
    // whichever is actually taller.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val barBottom = contentPadding.calculateBottomPadding()

    Box(modifier = modifier.fillMaxSize()) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = maxOf(imeBottom, barBottom),
            )
            // Blurred behind the deck. A no-op below API 31, which is why the
            // overlay carries its own scrim rather than relying on this.
            .then(if (chooseOpen) Modifier.blur(18.dp) else Modifier),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { chooseOpen = true }) {
                Icon(
                    imageVector = Icons.Outlined.Style,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("  Choose for me")
            }
        }

        if (messages.isEmpty()) {
            EmptyConversation(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                reverseLayout = true,
            ) {
                items(items = ordered, key = { it.id }) { message ->
                    Bubble(
                        message = message,
                        mine = message.senderId == myUid,
                        status = deliveryStatusOf(message, partnerReceipt),
                        playing = player.isPlaying(message.id),
                        progress = if (playingId == message.id) playProgress else 0f,
                        onTogglePlay = {
                            message.audioBytes?.let { bytes ->
                                player.toggle(message.id, bytes) { playingId = null }
                                playingId = player.currentlyPlaying()
                            }
                        },
                        onSeek = { fraction ->
                            message.audioBytes?.let { bytes ->
                                val target = (fraction * message.audioDurationMs).toInt()
                                player.seekTo(message.id, bytes, target) { playingId = null }
                                playingId = player.currentlyPlaying()
                                playProgress = fraction
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

        pendingVoice?.let { clip ->
            VoiceReview(
                clip = clip,
                onDiscard = viewModel::discardPendingVoice,
                onSend = viewModel::sendPendingVoice,
            )
        }

        if (recording) {
            Text(
                text = "Recording — tap stop to review it",
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
                onClick = { pickingSource = true },
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
                            recording -> viewModel.stopRecording()

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

        if (pickingSource) {
            PhotoSourceSheet(
                onDismiss = { pickingSource = false },
                onCamera = {
                    pickingSource = false
                    takePhoto()
                },
                onGallery = {
                    pickingSource = false
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }

        if (chooseOpen) {
            ChooseOverlay(
                onDismiss = { chooseOpen = false },
                bottomInset = barBottom,
            )
        }
    }
}

@Composable
private fun Bubble(
    message: Message,
    mine: Boolean,
    status: DeliveryStatus,
    playing: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
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
            if (message.isPhoto) {
                val bubbleContext = LocalContext.current

                // The document first, this phone's own copy second.
                //
                // A photo only stays in Firestore long enough to arrive; after
                // that the bytes are erased and the archive is the only place
                // it exists. Reading the blob first still matters for the brief
                // window before the receiving phone has filed it away.
                //
                // Decoded once per message rather than on every recomposition,
                // which matters in a list that scrolls.
                val bytes = remember(message.id) {
                    message.bytes ?: PhotoArchive.bytesFor(bubbleContext, message.id)
                }
                val bitmap = remember(bytes) {
                    bytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                }

                if (bitmap != null && bytes != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Photo, tap to open",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { PhotoViewerActivity.open(bubbleContext, bytes) },
                    )
                } else {
                    // Delivered to a phone that no longer has it — reinstalled,
                    // cleared, or a second device that was never the one this
                    // photo was handed to. Saying so is better than an empty
                    // bubble that looks like a bug.
                    MissingPhoto()
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
                    progress = progress,
                    mine = mine,
                    onToggle = onTogglePlay,
                    onSeek = onSeek,
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
                    modifier = Modifier.padding(top = if (message.isPhoto) 6.dp else 0.dp),
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
/**
 * A recording waiting to be sent or thrown away.
 *
 * A voice note cannot be skimmed before it goes the way a typed message can be
 * re-read, so this is the only chance to catch a bad take.
 */
@Composable
private fun VoiceReview(
    clip: VoiceRecorder.Recording,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Voice note  ${formatDuration(clip.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDiscard) {
            Text("Discard", color = MaterialTheme.colorScheme.error)
        }
        FilledIconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send the voice note")
        }
    }
}

@Composable
private fun VoiceNote(
    durationMs: Long,
    playing: Boolean,
    progress: Float,
    mine: Boolean,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val tint = if (mine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.widthIn(min = 180.dp),
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.clickable(onClick = onToggle),
        )

        // Draggable, so a note can be replayed from a particular moment
        // instead of only from the beginning.
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = onSeek,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelMedium,
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

/**
 * Stands in for a photo this phone does not have.
 *
 * Photos are transferred rather than hosted, so there is no copy to fetch back
 * — this is a statement of fact, not a failed load worth retrying.
 */
@Composable
private fun MissingPhoto() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.HideImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Photo isn't on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
