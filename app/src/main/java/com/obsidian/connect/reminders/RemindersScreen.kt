package com.obsidian.connect.reminders

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Reminder
import com.obsidian.connect.core.model.ReminderScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    modifier: Modifier = Modifier,
    viewModel: RemindersViewModel = hiltViewModel(),
) {
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pairingId by viewModel.pairingId.collectAsStateWithLifecycle()
    val partnerId by viewModel.partnerId.collectAsStateWithLifecycle()
    val partnerName by viewModel.partnerName.collectAsStateWithLifecycle()
    val myUid = viewModel.myUid

    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }

    // A working copy the drag rearranges, so an item follows the finger rather
    // than waiting on a round trip to Firestore and back.
    var arranged by remember(reminders) { mutableStateOf(reminders) }

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        onMove = { from, to ->
            arranged = arranged.toMutableList().apply {
                if (from in indices && to in indices) add(to, removeAt(from))
            }
        },
        onSettled = { viewModel.reorder(arranged.map { it.id }) },
    )

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                actions = {
                    if (reminders.any { it.done }) {
                        TextButton(onClick = viewModel::clearCompleted) {
                            Text("Clear done")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = EditorTarget.New }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a reminder")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Column(modifier = Modifier.padding(insets)) {
            ScopeSelector(
                selected = scope,
                sharedAvailable = pairingId != null,
                onSelect = viewModel::selectScope,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (reminders.isEmpty()) {
                EmptyState(scope = scope, paired = pairingId != null)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = arranged, key = { it.id }) { reminder ->
                        val dragging = reorder.isDragging(reminder.id)

                        // Read here rather than inside the graphicsLayer block.
                        // In the block it is only reached when the row is being
                        // dragged, so on the composition where the drag starts
                        // there is nothing subscribed to it yet.
                        val translation = if (dragging) reorder.offset else 0f

                        // Lifts as it is picked up, so a held row reads as
                        // detached from the list rather than merely tinted.
                        val lift by animateFloatAsState(
                            targetValue = if (dragging) 1f else 0f,
                            label = "lift",
                        )

                        ReminderRow(
                            reminder = reminder,
                            // Only worth offering on the shared list, and only
                            // when there is actually someone on the other end.
                            canNudge = scope == ReminderScope.Shared && partnerId != null,
                            // Only on the shared list. On a private one every
                            // row would say the same thing.
                            ownerLabel = if (scope == ReminderScope.Shared) {
                                if (reminder.createdBy == myUid) "You" else partnerName
                            } else {
                                null
                            },
                            onToggle = { viewModel.toggle(reminder) },
                            onNudge = { viewModel.nudge(reminder) },
                            onDelete = { viewModel.delete(reminder) },
                            onClick = { editing = EditorTarget.Existing(reminder) },
                            modifier = Modifier
                                // Above its neighbours while held, so it
                                // passes over them rather than through them.
                                .zIndex(lift)
                                .graphicsLayer {
                                    translationY = translation
                                    val scale = 1f + 0.03f * lift
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = 12.dp.toPx() * lift
                                    shape = RoundedCornerShape(16.dp)
                                    clip = false
                                }
                                // The rows it displaces slide out of the way.
                                // Not the dragged row itself - it is already
                                // being positioned by the finger, and a second
                                // animation fighting that is what makes a
                                // reorder feel rubbery.
                                .then(
                                    if (dragging) Modifier else Modifier.animateItem(),
                                ),
                            dragHandle = {
                                DragHandle(
                                    onStart = { reorder.start(reminder.id) },
                                    onDrag = reorder::drag,
                                    onEnd = reorder::stop,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    when (val target = editing) {
        null -> Unit

        EditorTarget.New -> ReminderEditorSheet(
            initial = null,
            sharedList = scope == ReminderScope.Shared,
            onDismiss = { editing = null },
            onSave = { title, note, dueAt, hasTime, priority, contactAlarm ->
                viewModel.add(title, note, dueAt, hasTime, priority, contactAlarm)
                editing = null
            },
        )

        is EditorTarget.Existing -> ReminderEditorSheet(
            initial = target.reminder,
            sharedList = scope == ReminderScope.Shared,
            onDismiss = { editing = null },
            onSave = { title, note, dueAt, hasTime, priority, contactAlarm ->
                viewModel.edit(target.reminder, title, note, dueAt, hasTime, priority, contactAlarm)
                editing = null
            },
        )
    }
}

private sealed interface EditorTarget {
    data object New : EditorTarget
    data class Existing(val reminder: Reminder) : EditorTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSelector(
    selected: ReminderScope,
    sharedAvailable: Boolean,
    onSelect: (ReminderScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scopes = ReminderScope.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        scopes.forEachIndexed { index, scope ->
            SegmentedButton(
                selected = scope == selected,
                onClick = { onSelect(scope) },
                // The shared tab is dead weight until an invite is accepted.
                enabled = scope != ReminderScope.Shared || sharedAvailable,
                shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                icon = {
                    Icon(
                        imageVector = when (scope) {
                            ReminderScope.Shared -> Icons.Outlined.People
                            ReminderScope.Private -> Icons.Outlined.Lock
                        },
                        contentDescription = null,
                    )
                },
            ) {
                Text(scope.label())
            }
        }
    }
}

@Composable
private fun EmptyState(scope: ReminderScope, paired: Boolean) {
    val headline: String
    val detail: String

    when {
        scope == ReminderScope.Shared && !paired -> {
            headline = "No one to share with yet"
            detail = "Pair with someone and this list becomes yours together."
        }

        scope == ReminderScope.Shared -> {
            headline = "Nothing here yet"
            detail = "Anything you add, they see. And they can nudge you about it."
        }

        else -> {
            headline = "Your list is empty"
            detail = "This one is only ever visible to you."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun ReminderScope.label(): String = when (this) {
    ReminderScope.Shared -> "Together"
    ReminderScope.Private -> "Just mine"
}
