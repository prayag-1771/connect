package com.obsidian.connect.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DragHandle
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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

    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }

    // A working copy the drag rearranges, so an item follows the finger rather
    // than waiting on a round trip to Firestore and back.
    var arranged by remember(reminders) { mutableStateOf(reminders) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

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
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items = arranged, key = { _, item -> item.id }) { index, reminder ->
                        ReminderRow(
                            reminder = reminder,
                            // Only worth offering on the shared list, and only
                            // when there is actually someone on the other end.
                            canNudge = scope == ReminderScope.Shared && partnerId != null,
                            onToggle = { viewModel.toggle(reminder) },
                            onNudge = { viewModel.nudge(reminder) },
                            onDelete = { viewModel.delete(reminder) },
                            onClick = { editing = EditorTarget.Existing(reminder) },
                            modifier = Modifier.zIndex(if (draggingId == reminder.id) 1f else 0f),
                            dragHandle = {
                                DragHandle(
                                    onStart = {
                                        draggingId = reminder.id
                                        dragOffset = 0f
                                    },
                                    onDrag = { delta ->
                                        dragOffset += delta

                                        // One row's height of travel moves it
                                        // one place. Measuring real row heights
                                        // would be exact, but they vary with
                                        // notes and dates, and a fixed step is
                                        // steadier under the finger.
                                        val step = (dragOffset / ROW_HEIGHT_PX).toInt()
                                        if (step != 0) {
                                            val target = (index + step)
                                                .coerceIn(0, arranged.lastIndex)
                                            if (target != index) {
                                                arranged = arranged.toMutableList().apply {
                                                    add(target, removeAt(index))
                                                }
                                                dragOffset -= step * ROW_HEIGHT_PX
                                            }
                                        }
                                    },
                                    onEnd = {
                                        draggingId = null
                                        dragOffset = 0f
                                        viewModel.reorder(arranged.map { it.id })
                                    },
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
            onDismiss = { editing = null },
            onSave = { title, note, dueAt, hasTime, priority ->
                viewModel.add(title, note, dueAt, hasTime, priority)
                editing = null
            },
        )

        is EditorTarget.Existing -> ReminderEditorSheet(
            initial = target.reminder,
            onDismiss = { editing = null },
            onSave = { title, note, dueAt, hasTime, priority ->
                viewModel.edit(target.reminder, title, note, dueAt, hasTime, priority)
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

/**
 * The three lines you press and hold to move a row.
 *
 * Long press rather than a plain drag, so scrolling the list past the handle
 * does not pick a row up by accident.
 */
@Composable
private fun DragHandle(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    Icon(
        imageVector = Icons.Outlined.DragHandle,
        contentDescription = "Hold and drag to move this",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onStart() },
                    onDrag = { change, delta ->
                        change.consume()
                        onDrag(delta.y)
                    },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                )
            },
    )
}

/** Roughly one row, used to decide when a drag has crossed into the next slot. */
private const val ROW_HEIGHT_PX = 190f
