package com.obsidian.connect.choose

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Choice
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
    modifier: Modifier = Modifier,
    viewModel: ChooseViewModel = hiltViewModel(),
) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val myUid = viewModel.myUid

    var confirmingDelete by remember { mutableStateOf<Choice?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::add) }

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
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            Header(onClose = onDismiss)

            Deck(
                choices = choices,
                myUid = myUid,
                busy = busy,
                onJudge = viewModel::judge,
                onDelete = { confirmingDelete = it },
                onAdd = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
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

@Composable
private fun Deck(
    choices: List<Choice>,
    myUid: String?,
    busy: Boolean,
    onJudge: (Choice, Int) -> Unit,
    onDelete: (Choice) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One page past the end, always. Swiping to the right-most card is how you
    // add another, so the deck is never empty and there is no separate button
    // to go hunting for.
    val pagerState = rememberPagerState(pageCount = { choices.size + 1 })

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

            if (page == choices.size) {
                AddCard(
                    busy = busy,
                    firstOne = choices.isEmpty(),
                    onAdd = onAdd,
                    modifier = Modifier.scale(scale),
                )
            } else {
                ChoiceCard(
                    choice = choices[page],
                    myUid = myUid,
                    onJudge = onJudge,
                    onDelete = onDelete,
                    modifier = Modifier.scale(scale),
                )
            }
        }

        Text(
            text = if (pagerState.currentPage == choices.size) {
                "Add another"
            } else {
                "${pagerState.currentPage + 1} of ${choices.size}"
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
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Room at the top for the bin, which sits outside the card.
                .padding(top = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                val bitmap = remember(choice.id) {
                    choice.bytes?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = choice.note.ifBlank { "An option" },
                            contentScale = ContentScale.Crop,
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

                Verdict(
                    choice = choice,
                    // Judging your own option would be a note to self.
                    enabled = myUid != null && choice.canBeJudgedBy(myUid),
                    onJudge = onJudge,
                )
            }
        }

        // Outside the card, top right, as asked.
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
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 20.dp)
                .clickable(onClick = onAdd),
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
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Add an option",
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

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
                            text = "Photos of what you are deciding between. They swipe " +
                                "through and tell you which one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private val Liked = Color(0xFF3DDC84)
