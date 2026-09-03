package com.obsidian.connect.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.Reminder
import java.util.Calendar
import java.util.Date

/**
 * Add or edit a reminder. Passing null for [initial] makes it an add sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorSheet(
    initial: Reminder?,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        note: String,
        dueAt: Date?,
        hasTime: Boolean,
        priority: Int,
        contactAlarm: Boolean,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var dueAt by remember { mutableStateOf(initial?.dueAt) }
    var hasTime by remember { mutableStateOf(initial?.dueHasTime ?: false) }
    var priority by remember { mutableIntStateOf(initial?.priorityValue ?: 1) }
    var contactAlarm by remember { mutableStateOf(initial?.contactAlarm ?: false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (initial == null) "New reminder" else "Edit reminder",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What needs doing?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val due = dueAt
                if (due == null) {
                    AssistChip(
                        onClick = { pickingDate = true },
                        label = { Text("Add a date") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Event,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                } else {
                    InputChip(
                        selected = true,
                        onClick = { pickingDate = true },
                        label = { Text(DueDateFormat.label(due)) },
                        trailingIcon = {
                            // Tapping the chip edits the date; the X clears it.
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove the date",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickableNoRipple {
                                        dueAt = null
                                        hasTime = false
                                    },
                            )
                        },
                    )

                    // Only offered once there is a date. A time on its own has
                    // nothing to be a time on.
                    InputChip(
                        selected = hasTime,
                        onClick = { pickingTime = true },
                        label = { Text(if (hasTime) timeLabel(due) else "Add a time") },
                        trailingIcon = if (!hasTime) null else {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove the time",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickableNoRipple { hasTime = false },
                                )
                            }
                        },
                    )
                }
            }

            PriorityRow(selected = priority, onSelect = { priority = it })

            // Only offered once there is a time to ring at. A switch that
            // silently does nothing because the reminder has no deadline is
            // worse than one that is not there.
            if (hasTime) {
                ContactAlarmRow(
                    enabled = contactAlarm,
                    onChange = { contactAlarm = it },
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { onSave(title, note, dueAt, hasTime, priority, contactAlarm && hasTime) },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (initial == null) "Add it" else "Save")
            }
        }
    }

    if (pickingTime) {
        val due = dueAt ?: Date()
        TimeOfDayDialog(
            initial = due,
            onDismiss = { pickingTime = false },
            onConfirm = { hour, minute ->
                dueAt = Calendar.getInstance().apply {
                    time = due
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                }.time
                hasTime = true
                pickingTime = false
            },
        )
    }

    if (pickingDate) {
        val pickerState = rememberDatePickerStateFor(dueAt)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { dueAt = Date(it) }
                        pickingDate = false
                    },
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Low, medium, high — the only three steps anyone actually distinguishes
 * between when they are writing a list rather than running a project.
 */
@Composable
private fun PriorityRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Low", 1 to "Medium", 2 to "High").forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayDialog(
    initial: Date,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val calendar = Calendar.getInstance().apply { time = initial }
    val state = rememberTimePickerState(
        initialHour = calendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = calendar.get(Calendar.MINUTE),
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

private fun timeLabel(date: Date): String = Calendar.getInstance().run {
    time = date
    "%02d:%02d".format(get(Calendar.HOUR_OF_DAY), get(Calendar.MINUTE))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberDatePickerStateFor(current: Date?) = rememberDatePickerState(
    initialSelectedDateMillis = current?.time ?: System.currentTimeMillis(),
)

/**
 * A tap target with no ripple, for an icon sitting inside a chip that already
 * draws its own pressed state.
 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/**
 * The switch that turns a reminder into something neither of you can ignore.
 *
 * Off, an alarm is addressed to whoever set it: they hear a ringtone, the
 * other person feels a buzz and sees the ripples. On, it rings in full on both
 * phones with the call ringtone, whoever wrote it.
 */
@Composable
private fun ContactAlarmRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Ring like a call", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Both phones ring, whoever set it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}
