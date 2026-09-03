package com.obsidian.connect.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.Timetable
import com.obsidian.connect.core.model.TimetableEntry

/**
 * One slot, by hand.
 *
 * Reading a photograph gets most of a timetable right and some of it wrong -
 * a room code read as part of a subject, a Thursday lecture landing on
 * Tuesday. Rather than trying to make the extraction perfect, everything it
 * produces is editable, and anything it missed can be typed.
 *
 * Times are plain text rather than a picker. A timetable is entered in a
 * burst, and four taps through a clock dial for every one of twenty slots is
 * slower than typing four digits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotEditor(
    initial: TimetableEntry,
    defaultDay: String,
    onSave: (TimetableEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var day by remember { mutableStateOf(initial.day.ifBlank { defaultDay }) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    var title by remember { mutableStateOf(initial.title) }
    var location by remember { mutableStateOf(initial.location) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (onDelete == null) "New slot" else "Edit slot",
                style = MaterialTheme.typography.titleLarge,
            )

            // Scrolls, because seven chips do not fit across a phone at a size
            // anybody can hit.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Timetable.DAYS.forEach { name ->
                    FilterChip(
                        selected = day == name,
                        onClick = { day = name },
                        label = { Text(name.take(3)) },
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What is it") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("From") },
                    placeholder = { Text("09:00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("Until") },
                    placeholder = { Text("10:30") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Where (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            day = day,
                            // Tidied on the way in, so "9:5" sorts and reads
                            // like every other row rather than sitting above
                            // 10:00 for the rest of its life.
                            start = tidyTime(start),
                            end = tidyTime(end),
                            title = title.trim(),
                            location = location.trim(),
                        ),
                    )
                },
                enabled = title.isNotBlank() && day.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            onDelete?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete this slot", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Zero-pads what somebody typed, so the list sorts.
 *
 * Entries are ordered on the string, which is correct only while every time is
 * the same width. "9:00" would otherwise sort after "10:00" and the day would
 * read in the wrong order for no visible reason.
 */
private fun tidyTime(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""

    val digits = trimmed.filter { it.isDigit() }
    val parts = trimmed.split(":", ".")

    val (hour, minute) = when {
        parts.size >= 2 -> parts[0] to parts[1]
        // Four digits with no separator is how a number pad gets used.
        digits.length == 4 -> digits.take(2) to digits.drop(2)
        digits.length == 3 -> digits.take(1) to digits.drop(1)
        else -> return trimmed
    }

    val h = hour.filter { it.isDigit() }.toIntOrNull() ?: return trimmed
    val m = minute.filter { it.isDigit() }.toIntOrNull() ?: return trimmed
    if (h !in 0..23 || m !in 0..59) return trimmed

    return "%02d:%02d".format(h, m)
}
