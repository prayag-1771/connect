package com.obsidian.connect.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.widget.ScheduleBoundaryReceiver
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetSchedule

/**
 * Controls the hours during which the watch face shows anything personal.
 */
@Composable
fun WatchScheduleCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(WidgetSchedule.isEnabled(context)) }
    var start by remember { mutableIntStateOf(WidgetSchedule.startMinute(context)) }
    var end by remember { mutableIntStateOf(WidgetSchedule.endMinute(context)) }
    var picking by remember { mutableStateOf<Boundary?>(null) }

    fun persist() {
        WidgetSchedule.save(context, enabled, start, end)
        // Redraw now, and move the next boundary alarm to match the new window.
        WatchWidgetProvider.refreshAll(context)
        ScheduleBoundaryReceiver.schedule(context)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only during these hours", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Outside them the widget is just a clock, and tapping " +
                            "it opens your clock app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        persist()
                    },
                )
            }

            if (enabled) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { picking = Boundary.Start },
                        modifier = Modifier.weight(1f),
                    ) { Text("From ${WidgetSchedule.format(start)}") }

                    OutlinedButton(
                        onClick = { picking = Boundary.End },
                        modifier = Modifier.weight(1f),
                    ) { Text("Until ${WidgetSchedule.format(end)}") }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = summaryFor(start, end),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    picking?.let { boundary ->
        val initial = if (boundary == Boundary.Start) start else end
        TimeDialog(
            initialMinute = initial,
            onDismiss = { picking = null },
            onConfirm = { chosen ->
                if (boundary == Boundary.Start) start = chosen else end = chosen
                picking = null
                persist()
            },
        )
    }
}

private enum class Boundary { Start, End }

/**
 * Spells out what the window actually means, because a wrapped one reads
 * wrong at a glance — "from 22:00 until 07:00" looks like an empty range
 * until you notice it crosses midnight.
 */
private fun summaryFor(start: Int, end: Int): String = when {
    start == end -> "That is a zero-length window, so the widget stays on all day."
    start < end -> "Active for ${durationLabel(end - start)} each day."
    else -> "Crosses midnight — active overnight, for " +
        "${durationLabel(WidgetSchedule.MINUTES_PER_DAY - start + end)} each day."
}

private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours hr"
        else -> "$hours hr $rest min"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
