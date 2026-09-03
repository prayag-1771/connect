package com.obsidian.connect.reminders

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.Priority
import com.obsidian.connect.core.model.Reminder

@Composable
fun ReminderRow(
    reminder: Reminder,
    canNudge: Boolean,
    /**
     * Who put this on the list, or null on a private one.
     *
     * Nothing is said on a list with one person in it - labelling every row
     * "You" is noise that never distinguishes anything.
     */
    ownerLabel: String? = null,
    onToggle: () -> Unit,
    onNudge: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val overdue = reminder.isOverdue()
    val dueToday = reminder.isDueToday()

    // Animated so ticking something off reads as a state change rather than a
    // sudden repaint.
    val titleColor by animateColorAsState(
        targetValue = if (reminder.done) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "titleColor",
    )

    // A light red wash on the day something is due, so today's deadlines
    // separate themselves from the list without needing to be read. Kept pale
    // deliberately — a row that shouts is no use in a list where several rows
    // might be shouting at once.
    // Mixed against the row's normal colour rather than being a fixed value.
    // A literal dark red was almost black against a light theme - the tint has
    // to be derived from whatever the surface actually is, not guessed.
    val plain = MaterialTheme.colorScheme.surfaceContainerLow
    val background by animateColorAsState(
        targetValue = if (dueToday) {
            lerp(plain, MaterialTheme.colorScheme.errorContainer, DUE_TODAY_TINT)
        } else {
            plain
        },
        label = "rowBackground",
    )

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A coloured spine rather than a badge or a word. Priority is
            // something you want to read down a list at a glance, not stop and
            // parse on each row.
            Box(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(priorityColour(reminder.priority, reminder.done)),
            )

            Checkbox(checked = reminder.done, onCheckedChange = { onToggle() })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = reminder.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    textDecoration = if (reminder.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (reminder.note.isNotBlank()) {
                    Text(
                        text = reminder.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                reminder.dueAt?.let { due ->
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Event,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (overdue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = DueDateFormat.label(due),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (overdue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        ownerLabel?.let {
                            // On the same line as the date rather than a line
                            // of its own. Both answer "when and whose", and a
                            // third line per row would make the list twice as
                            // tall for one word.
                            Spacer(Modifier.size(8.dp))
                            OwnerTag(it)
                        }
                    }
                } ?: ownerLabel?.let {
                    // Undated, so it needs a line of its own after all.
                    Spacer(Modifier.height(2.dp))
                    OwnerTag(it)
                }
            }

            // Nudging something already finished would just be annoying.
            if (canNudge && !reminder.done) {
                IconButton(onClick = onNudge) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = "Nudge them about this",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Dragging lives on its own handle rather than the whole row.
            // A list you can accidentally rearrange by scrolling it is worse
            // than one you cannot rearrange at all.
            dragHandle?.invoke()
        }
    }
}

/**
 * Grey once finished — a completed item's urgency is no longer information,
 * and a row of red spines under things already done reads as a list of
 * problems rather than a list of achievements.
 */
@Composable
private fun priorityColour(priority: Priority, done: Boolean): Color = when {
    done -> MaterialTheme.colorScheme.outlineVariant
    priority == Priority.High -> Color(0xFFE05252)
    priority == Priority.Medium -> Color(0xFFE0A030)
    else -> Color(0xFF5B9BD5)
}

/** Just enough red to catch the eye, nowhere near enough to fight the text. */
private const val DUE_TODAY_TINT = 0.28f

/**
 * Whose task this is, said quietly.
 *
 * A tinted pill rather than plain text - at this size a name reads as part of
 * the note otherwise, and the whole value of it is being separable at a glance.
 */
@Composable
private fun OwnerTag(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
