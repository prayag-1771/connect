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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.Reminder
import java.util.Date

/**
 * Add or edit a reminder. Passing null for [initial] makes it an add sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorSheet(
    initial: Reminder?,
    onDismiss: () -> Unit,
    onSave: (title: String, note: String, dueAt: Date?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var dueAt by remember { mutableStateOf(initial?.dueAt) }
    var pickingDate by remember { mutableStateOf(false) }

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
                                    .clickableNoRipple { dueAt = null },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { onSave(title, note, dueAt) },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (initial == null) "Add it" else "Save")
            }
        }
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
