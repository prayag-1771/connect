package com.obsidian.connect.choose

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Choice
import com.obsidian.connect.core.model.ChoiceRef
import com.obsidian.connect.editor.EditPhotoContract
import com.obsidian.connect.archive.PhotoArchive
import com.obsidian.connect.viewer.PhotoViewerActivity
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * A deck of things to decide between.
 *
 * One person puts up photos of what they are choosing among; the other swipes
 * through and says yes or no. Laid out like the recent-apps switcher — cards
 * side by side, neighbours peeking in — because that gesture already means
 * "several of a thing, pick one" to anyone holding an Android phone.
 */
@Composable
fun ChooseOverlay(
    onDismiss: () -> Unit,
    /** Start a reply in the chat about this card. */
    onRefer: (Choice) -> Unit = {},
    /** Jump back to a message that was written about a card. */
    onOpenRef: (ChoiceRef) -> Unit = {},
    /**
     * A card to land on, from a message written about it.
     *
     * The card may be on either side of the deck - a reference in the chat
     * says nothing about whose option it was - so the tab is chosen here
     * rather than assumed.
     */
    focusChoiceId: String? = null,
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    viewModel: ChooseViewModel = hiltViewModel(),
) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val myUid = viewModel.myUid
    val context = LocalContext.current

    // Keyed on how many cards still carry their bytes, not on the list itself.
    // Filing one clears its photo, which produces another snapshot — keying on
    // the list would restart this on its own result. This count only falls to
    // zero and stays there until something new actually arrives.
    LaunchedEffect(choices.count { it.hasImage }) {
        viewModel.archiveIncoming(choices)
    }

    // Looking at the deck is what puts the yellow dot out.
    LaunchedEffect(choices.size) {
        viewModel.markChoicesSeen(choices)
    }

    var confirmingDelete by remember { mutableStateOf<Choice?>(null) }

    // Cards already deleted, dropped from the deck before Firestore has been
    // asked. The write is quick, but the pager re-settling around a page that
    // is still there reads as a stall - taking the card out first makes the
    // gesture land immediately and the network catch up behind it.
    var deleted by remember { mutableStateOf(setOf<String>()) }
    var side by remember { mutableStateOf(ChoiceSide.Mine) }

    // A referenced card can be on either side. Switch to whichever tab holds
    // it before the deck tries to scroll to it, or it would land on the right
    // index of the wrong list.
    LaunchedEffect(focusChoiceId, choices) {
        val id = focusChoiceId ?: return@LaunchedEffect
        val card = choices.firstOrNull { it.id == id } ?: return@LaunchedEffect
        side = if (card.addedBy == myUid) ChoiceSide.Mine else ChoiceSide.Theirs
    }

    val scope = rememberCoroutineScope()

    val editor = rememberLauncherForActivityResult(
        EditPhotoContract(context),
    ) { edited -> edited?.let(viewModel::add) }

    fun openEditor(uri: Uri) {
        scope.launch { viewModel.prepare(uri)?.let(editor::launch) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(::openEditor) }

    // The camera writes into a file we hand it, so the URI has to survive
    // until the result comes back.
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        if (saved) pendingCapture?.let(::openEditor)
        pendingCapture = null
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

    // Split by who put it up. Your own deck is a list of questions waiting on
    // an answer; theirs is a list of answers waiting on you. Reading both in
    // one stack made it unclear which cards you were meant to act on.
    val live = remember(choices, deleted) { choices.filterNot { it.id in deleted } }
    val mine = remember(live, myUid) { live.filter { it.addedBy == myUid } }
    val theirs = remember(live, myUid) { live.filter { it.addedBy != myUid } }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Tapping the backdrop closes. No ripple — this is a dismiss
            // surface, and one spreading across the whole screen looks broken.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // This overlay sits beside the chat column rather than inside
                // it, so it covers the whole screen — including the strip the
                // bottom navigation bar draws over. Without this the like and
                // dislike buttons are clipped by it.
                .padding(bottom = bottomInset),
        ) {
            Header(onClose = onDismiss)

            SideTabs(
                side = side,
                onSide = { side = it },
                mineCount = mine.size,
                theirsCount = theirs.size,
            )

            Deck(
                choices = if (side == ChoiceSide.Mine) mine else theirs,
                myUid = myUid,
                busy = busy,
                // Only your own deck takes new cards. Adding an option to
                // theirs would be answering a question they had not asked.
                allowAdding = side == ChoiceSide.Mine,
                onJudge = viewModel::judge,
                onDelete = { confirmingDelete = it },
                // Both close the deck: one to write about a card, the other to
                // go and read what was written. Either way the answer is in
                // the conversation, not here.
                onRefer = {
                    onRefer(it)
                    onDismiss()
                },
                onOpenRef = {
                    onOpenRef(it)
                    onDismiss()
                },
                focusChoiceId = focusChoiceId,
                onPickFromGallery = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onTakePhoto = ::takePhoto,
                modifier = Modifier.weight(1f),
            )
        }
    }

    confirmingDelete?.let { choice ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this?") },
            // Asked because it removes the card for both of you, and there is
            // no undo behind it.
            text = { Text("It goes for both of you, and it cannot be brought back.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleted = deleted + choice.id
                        viewModel.delete(choice)
                        confirmingDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
        }
        Text(
            text = "Choose for me",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Which deck is on show: the ones you put up, or the ones they did. */
enum class ChoiceSide { Mine, Theirs }

@Composable
private fun SideTabs(
    side: ChoiceSide,
    onSide: (ChoiceSide) -> Unit,
    mineCount: Int,
    theirsCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SideTab(
            // Labelled by what the deck is for rather than by whose it is.
            // "Mine" and "Theirs" both need a moment's thought about which
            // way round they run.
            label = "Asking them",
            count = mineCount,
            selected = side == ChoiceSide.Mine,
            onClick = { onSide(ChoiceSide.Mine) },
            modifier = Modifier.weight(1f),
        )
        SideTab(
            label = "They asked",
            count = theirsCount,
            selected = side == ChoiceSide.Theirs,
            onClick = { onSide(ChoiceSide.Theirs) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SideTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
    ) {
        Text(
            text = if (count > 0) "$label  ·  $count" else label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun Deck(
    choices: List<Choice>,
    myUid: String?,
    busy: Boolean,
    allowAdding: Boolean,
    onJudge: (Choice, Int) -> Unit,
    onDelete: (Choice) -> Unit,
    onRefer: (Choice) -> Unit,
    onOpenRef: (ChoiceRef) -> Unit,
    focusChoiceId: String? = null,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One page past the end on your own deck. Swiping to the right-most card
    // is how another option goes in, so there is no separate button to hunt
    // for. Their deck takes no additions, so it has no trailing card.
    val addPage = if (allowAdding) 1 else 0
    val pagerState = rememberPagerState(pageCount = { choices.size + addPage })

    // Arriving from a message written about one particular card. Keyed on the
    // list too, because the deck is often still loading when the id arrives.
    LaunchedEffect(focusChoiceId, choices) {
        val id = focusChoiceId ?: return@LaunchedEffect
        val index = choices.indexOfFirst { it.id == id }
        if (index >= 0) pagerState.scrollToPage(index)
    }

    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 40.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val offset = (pagerState.currentPage - page + pagerState.currentPageOffsetFraction)
                .absoluteValue

            // Neighbours sit back slightly, so the one in front reads as the
            // one you are deciding about.
            val scale by animateFloatAsState(
                targetValue = if (offset < 0.5f) 1f else 0.92f,
                label = "cardScale",
            )

            if (allowAdding && page == choices.size) {
                AddCard(
                    busy = busy,
                    firstOne = choices.isEmpty(),
                    onPickFromGallery = onPickFromGallery,
                    onTakePhoto = onTakePhoto,
                    modifier = Modifier.scale(scale),
                )
            } else {
                ChoiceCard(
                    choice = choices[page],
                    myUid = myUid,
                    onJudge = onJudge,
                    onDelete = onDelete,
                    onRefer = onRefer,
                    onOpenRef = onOpenRef,
                    modifier = Modifier.scale(scale),
                )
            }
        }

        if (choices.isEmpty() && !allowAdding) {
            Text(
                text = "They have not asked you anything yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }

        Text(
            text = when {
                allowAdding && pagerState.currentPage == choices.size -> "Add another"
                choices.isEmpty() -> ""
                else -> "${pagerState.currentPage + 1} of ${choices.size}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun ChoiceCard(
    choice: Choice,
    myUid: String?,
    onJudge: (Choice, Int) -> Unit,
    onDelete: (Choice) -> Unit,
    onRefer: (Choice) -> Unit,
    onOpenRef: (ChoiceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Deep enough for the whole delete button. At 20dp the button
                // overflowed onto the card, which is what made a white icon
                // disappear against it.
                .padding(top = BIN_STRIP),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                val cardContext = LocalContext.current

                // The document first, this phone's own copy second — the bytes
                // only stay online long enough to arrive.
                val bytes = remember(choice.id) {
                    choice.bytes ?: PhotoArchive.bytesFor(cardContext, choice.id)
                }
                val bitmap = remember(bytes) {
                    bytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable {
                            bytes?.let { PhotoViewerActivity.open(cardContext, it) }
                        },
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = choice.note.ifBlank { "An option, tap to open" },
                            // Fit, not Crop. You are being asked to judge the
                            // thing in the photo; cropping its edges away to
                            // fill the card can remove the part being decided
                            // about.
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (choice.note.isNotBlank()) {
                    Text(
                        text = choice.note,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // Everything ever said about this card, kept with the card.
                // A decision is rarely made in one go, and the argument for it
                // is worth as much as the verdict.
                RefStrip(refs = choice.refs, onOpenRef = onOpenRef)

                Verdict(
                    choice = choice,
                    // Judging your own option would be a note to self.
                    enabled = myUid != null && choice.canBeJudgedBy(myUid),
                    onJudge = onJudge,
                )
            }
        }

        // Paired with the bin in the opposite corner, in the same gap above
        // the card. Both are actions about the card rather than about the
        // thing in the photograph, so neither belongs on the card itself.
        IconButton(
            onClick = { onRefer(choice) },
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Chat,
                contentDescription = "Talk about this in the chat",
                tint = Color.White,
            )
        }

        // Sits in the gap above the card, not over it — which is why it can
        // stay plain white: the backdrop behind it is the dimmed overlay, not
        // the card's own light corner.
        IconButton(
            onClick = { onDelete(choice) },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete this option",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun Verdict(
    choice: Choice,
    enabled: Boolean,
    onJudge: (Choice, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        FilledIconButton(
            onClick = { onJudge(choice, -1) },
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            colors = if (choice.isDisliked) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Icon(Icons.Outlined.ThumbDown, contentDescription = "No")
        }

        FilledIconButton(
            onClick = { onJudge(choice, 1) },
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            colors = if (choice.isLiked) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = Liked,
                    contentColor = Color.White,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Icon(Icons.Outlined.ThumbUp, contentDescription = "Yes")
        }
    }
}

/**
 * The card past the end of the deck.
 *
 * Padded at the top exactly like a real card leaves room for its bin, so the
 * cards stay aligned as you swipe between them.
 */
@Composable
private fun AddCard(
    busy: Boolean,
    firstOne: Boolean,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Surface(
            // Same strip a real card leaves for its bin, so the two line up
            // as you swipe between them.
            modifier = Modifier.fillMaxSize().padding(top = BIN_STRIP),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (busy) {
                    CircularProgressIndicator()
                    return@Column
                }

                Text(
                    text = if (firstOne) {
                        "Add something to choose between"
                    } else {
                        "Add another"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )

                if (firstOne) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "They swipe through and tell you which one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Two routes, side by side rather than behind a menu. Half of
                // what gets compared is already on the phone and half is in
                // front of you in a shop, and neither is the odd one out.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    AddOption(
                        icon = Icons.Outlined.PhotoCamera,
                        label = "Take one",
                        onClick = onTakePhoto,
                    )
                    AddOption(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = "From gallery",
                        onClick = onPickFromGallery,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Height of the gap above each card, sized to hold the delete button. */
private val BIN_STRIP = 48.dp

private val Liked = Color(0xFF3DDC84)

/**
 * The button that starts a conversation about a card, and the list of every
 * conversation already had about it.
 *
 * Collapsed to a count until asked, because a card that has been argued over
 * for a week would otherwise bury the photograph being argued about.
 */
@Composable
private fun RefStrip(
    refs: List<ChoiceRef>,
    onOpenRef: (ChoiceRef) -> Unit,
) {
    if (refs.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(if (open) "Hide ${refs.size}" else "${refs.size} referred")
        }

        if (open) {
            // Newest first: the last thing said about a decision is nearly
            // always the part you came back for.
            refs.sortedByDescending { it.atMillis }.forEach { ref ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRef(ref) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(22.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        text = ref.text.ifBlank { "Message" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
