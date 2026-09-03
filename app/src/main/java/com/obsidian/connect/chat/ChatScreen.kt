package com.obsidian.connect.chat

import android.Manifest
import android.widget.Toast
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.FilledIconButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import com.obsidian.connect.core.model.Choice
import com.obsidian.connect.core.model.DeliveryStatus
import com.obsidian.connect.core.model.Message
import com.obsidian.connect.core.model.deliveryStatusOf
import androidx.compose.material.icons.filled.VideoCall
import com.obsidian.connect.call.CallActivity
import androidx.compose.material.icons.filled.LibraryMusic
import com.obsidian.connect.jam.JamActivity
import com.obsidian.connect.jam.JamChooser
import com.obsidian.connect.jam.SpotifySetupActivity
import com.obsidian.connect.jam.SpotifyStore
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.ui.theme.ThemeMode
import com.obsidian.connect.ui.theme.ChatColors
import com.obsidian.connect.ui.theme.AppearanceStore
import androidx.compose.foundation.isSystemInDarkTheme
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

    val chatTheme by viewModel.chatTheme.collectAsStateWithLifecycle()
    val palette = chatTheme.colors(isSystemInDarkTheme() || AppearanceStore.themeMode == ThemeMode.Dark)

    val partnerReceipt by viewModel.partnerReceipt.collectAsStateWithLifecycle()
    val gifs by viewModel.gifs.collectAsStateWithLifecycle()
    val gifsLoading by viewModel.gifsLoading.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    // The message a long press is asking about. Null when nothing is held.
    var chosen by remember { mutableStateOf<Message?>(null) }

    // What the next message will be answering: a message swiped aside, or a
    // card sent over from the choose deck. Never both.
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    var referring by remember { mutableStateOf<Choice?>(null) }

    // A message the conversation has been asked to jump to, from a card's
    // reference list. Highlighted briefly on arrival so the eye can find it.
    var spotlight by remember { mutableStateOf<String?>(null) }

    // The card the deck should land on when opened from a message about it.
    var openChoiceId by remember { mutableStateOf<String?>(null) }

    // Held between choosing to delete and confirming it.
    var confirmingDelete by remember { mutableStateOf<Message?>(null) }

    // Which service to jam on, asked before anything is opened.
    var jamChooser by remember { mutableStateOf(false) }

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

    // A message asked for from the starred list, which is a different screen
    // and cannot reach in here directly.
    LaunchedEffect(ChatFocus.pendingMessageId) {
        val wanted = ChatFocus.pendingMessageId ?: return@LaunchedEffect
        spotlight = wanted
        ChatFocus.consume()
    }

    // Walking a reference back to the message it points at.
    //
    // The conversation only holds its last two hundred messages, so a
    // reference to something older has nothing to scroll to. Saying so beats
    // scrolling somewhere arbitrary and leaving someone to wonder what they
    // are looking at.
    LaunchedEffect(spotlight, ordered) {
        val target = spotlight ?: return@LaunchedEffect
        val index = ordered.indexOfFirst { it.id == target }

        if (index < 0) {
            Toast.makeText(
                context,
                "That message is too old to jump to",
                Toast.LENGTH_SHORT,
            ).show()
            spotlight = null
            return@LaunchedEffect
        }

        listState.animateScrollToItem(index)
        // Long enough to notice, short enough not to become the new normal
        // appearance of that bubble.
        delay(2_000)
        spotlight = null
    }


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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Calling on the left, deciding on the right. Two unrelated things,
            // kept at opposite ends so neither is ever hit by mistake while
            // reaching for the other.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { CallActivity.place(context) }) {
                    Icon(
                        imageVector = Icons.Filled.VideoCall,
                        contentDescription = "Video call",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { jamChooser = true }) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = "Listen together",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

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
                    SwipeToReply(
                        mine = message.senderId == myUid,
                        onReply = {
                            replyingTo = message
                            referring = null
                        },
                    ) {
                    Bubble(
                        message = message,
                        mine = message.senderId == myUid,
                        // Either person's star shows for both. Something one
                        // of you kept is kept.
                        starred = message.starredBy.isNotEmpty(),
                        palette = palette,
                        status = deliveryStatusOf(message, partnerReceipt),
                        onLongPress = { chosen = message },
                        onOpenChoice = { id ->
                            openChoiceId = id
                            chooseOpen = true
                        },
                        onOpenMessage = { id -> spotlight = id },
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
                        spotlit = spotlight == message.id,
                    )
                    }
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

        // Whichever of the two is live. A message swiped aside, or a card sent
        // over from the deck - never both, so one strip serves for either.
        val card = referring
        val target = replyingTo
        if (card != null) {
            ReplyPreview(
                heading = "About this card",
                label = card.note.ifBlank { "Choose for me" },
                onCancel = { referring = null },
            )
        } else if (target != null) {
            ReplyPreview(
                heading = if (target.senderId == myUid) "Replying to yourself" else "Replying",
                label = target.quotedSummary,
                onCancel = { replyingTo = null },
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
                        viewModel.send(draft, replyTo = replyingTo, choice = referring)
                        draft = ""
                        replyingTo = null
                        referring = null
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
                onDismiss = {
                    chooseOpen = false
                    openChoiceId = null
                },
                onRefer = { card ->
                    referring = card
                    replyingTo = null
                },
                onOpenRef = { ref -> spotlight = ref.messageId },
                focusChoiceId = openChoiceId,
                bottomInset = barBottom,
            )
        }
    }

    chosen?.let { message ->
        MessageActions(
            starred = message.isStarredBy(myUid),
            // Deleting takes it off both phones, so it is only ever offered
            // for your own. The rules refuse it for anyone else's regardless.
            deletable = message.senderId == myUid,
            onStar = {
                viewModel.toggleStar(message)
                chosen = null
            },
            onDelete = {
                // Close the sheet first, so the question is not asked from
                // underneath the thing that asked it.
                chosen = null
                confirmingDelete = message
            },
            onDismiss = { chosen = null },
        )
    }

    if (jamChooser) {
        JamChooser(
            onYouTube = {
                jamChooser = false
                JamActivity.open(context)
            },
            onSpotify = {
                jamChooser = false
                // Straight to the jam once it is connected; to setup if not.
                if (SpotifyStore.isConnected(context)) {
                    JamActivity.openSpotify(context)
                } else {
                    SpotifySetupActivity.open(context)
                }
            },
            onDismiss = { jamChooser = false },
        )
    }

    confirmingDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this message?") },
            text = {
                Text(
                    "It goes from both phones, and there is no undo. " +
                        "Any photo already saved to this phone stays in your photos.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(message)
                        confirmingDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Keep it") }
            },
        )
    }
}

/**
 * What a held message offers: keep it, or take it back.
 *
 * A sheet rather than a menu floating by the bubble. Delete removes the
 * message from both phones, which is worth a moment of deliberate attention
 * rather than something to be brushed against near the edge of a conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActions(
    starred: Boolean,
    deletable: Boolean,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            ActionRow(
                icon = if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (starred) "Remove star" else "Star this",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onStar,
            )

            if (deletable) {
                ActionRow(
                    icon = Icons.Outlined.Delete,
                    label = "Delete for both of us",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: Message,
    mine: Boolean,
    starred: Boolean,
    status: DeliveryStatus,
    playing: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onLongPress: () -> Unit,
    onOpenChoice: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    palette: ChatColors?,
    spotlit: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Long press rather than a tap: a tap on a photo opens it, and on
            // a voice note plays it, so the gesture has to be one that nothing
            // in a bubble has already claimed.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            ),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = when {
                        // Briefly lifted when arrived at from a card's
                        // reference list, so the eye lands on the right one.
                        spotlit -> MaterialTheme.colorScheme.tertiaryContainer
                        mine -> palette?.mine ?: MaterialTheme.colorScheme.primaryContainer
                        else -> palette?.theirs
                            ?: MaterialTheme.colorScheme.surfaceContainerHighest
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
            // The quote sits inside the bubble, above what was actually said,
            // so an answer and the thing it answers read as one block.
            if (message.isReply || message.hasChoiceRef) {
                val quoteContext = LocalContext.current

                // The card's own picture, off this phone's disk.
                //
                // Both sides archive a card under its id when it is added or
                // received, so the image is already here - no listener, no
                // fetch, and nothing kept on the server to fetch from. Saying
                // which card in words is a poor substitute for showing it.
                val cardThumb = remember(message.choiceRefId) {
                    if (!message.hasChoiceRef) {
                        null
                    } else {
                        PhotoArchive.bytesFor(quoteContext, message.choiceRefId)
                            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            ?.asImageBitmap()
                    }
                }

                QuotedStrip(
                    label = when {
                        message.hasChoiceRef -> "About this card — tap to open"
                        message.replyToIsPhoto -> "Photo"
                        else -> message.replyToText
                    },
                    mine = mine,
                    thumbnail = cardThumb,
                    // Both kinds of quote are now walkable. A card opens the
                    // deck; a quoted message scrolls the conversation back to
                    // it. "A few rows up" was only ever true for the reply
                    // immediately after - by the time a conversation has moved
                    // on, the thing being answered is as far away as anything
                    // else.
                    onOpen = when {
                        message.hasChoiceRef -> {
                            { onOpenChoice(message.choiceRefId) }
                        }
                        message.isReply -> {
                            { onOpenMessage(message.replyToId) }
                        }
                        else -> null
                    },
                )
                Spacer(Modifier.height(6.dp))
            }

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
                if (starred) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Starred",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
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

/**
 * The thing a message is answering, drawn inside it.
 *
 * A bar and one line. A quote is a reminder of what was being talked about,
 * not a second copy of it - anything longer competes with the reply itself.
 */
@Composable
private fun QuotedStrip(
    label: String,
    mine: Boolean,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap? = null,
    onOpen: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .background(
                if (mine) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                },
            )
            .height(IntrinsicSize.Min)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )

        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(38.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }

        Text(
            text = label.ifBlank { "Message" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        )
    }
}

/**
 * What the next message will be answering, shown above the composer.
 *
 * Dismissable, because starting a reply by accident is easy - the gesture is a
 * sideways drag on something people also scroll past.
 */
@Composable
private fun ReplyPreview(label: String, heading: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label.ifBlank { "Message" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Don't reply to this",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
