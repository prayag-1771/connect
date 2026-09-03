package com.obsidian.connect.profile

import androidx.compose.foundation.layout.Arrangement
import java.util.Calendar
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
    var days by remember { mutableStateOf(WidgetSchedule.days(context)) }

    fun persist() {
        WidgetSchedule.save(context, enabled, start, end, days)
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

                Spacer(Modifier.height(14.dp))
                DayPicker(
                    selected = days,
                    onToggle = { day ->
                        days = if (day in days) days - day else days + day
                        persist()
                    },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = summaryFor(start, end) + daysSummary(days),
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

/**
 * The seven days, as circles you tap.
 *
 * Sunday last rather than first. Calendar counts from Sunday, but a week read
 * by a person starts on Monday, and the letters would otherwise not line up
 * with how anyone thinks about their week.
 */
@Composable
private fun DayPicker(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val order = listOf(
        Calendar.MONDAY to "M",
        Calendar.TUESDAY to "T",
        Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "T",
        Calendar.FRIDAY to "F",
        Calendar.SATURDAY to "S",
        Calendar.SUNDAY to "S",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        order.forEach { (day, letter) ->
            val on = day in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(
                        if (on) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    )
                    .clickable { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Named so a glance at the card says which days, not just which hours. */
private fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> ", but no days are selected, so it never shows."
    days.size == 7 -> ", every day."
    days == setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
    ) -> ", Monday to Friday."
    days == setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> ", at weekends."
    else -> ", on the days marked."
}
