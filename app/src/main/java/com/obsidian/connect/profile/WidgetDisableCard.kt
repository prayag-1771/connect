package com.obsidian.connect.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.widget.ScheduleBoundaryReceiver
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetSchedule

/**
 * Switches the widget off by hand, indefinitely.
 *
 * Does exactly what falling outside the scheduled hours does — plain clock, no
 * photo, no indicators, tap opens the clock app — except it holds until it is
 * switched back on rather than until the hour passes.
 *
 * Kept visually distinct in red because it is the one control here that makes
 * the app stop doing its job, and that should not look like the settings
 * around it.
 */
@Composable
fun WidgetDisableCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var disabled by remember { mutableStateOf(WidgetSchedule.isDisabled(context)) }

    fun apply(value: Boolean) {
        disabled = value
        WidgetSchedule.setDisabled(context, value)
        WatchWidgetProvider.refreshAll(context)
        // The next boundary changes meaning entirely when this flips, so the
        // pending alarm has to be recomputed rather than left pointing at an
        // hour that no longer does anything.
        ScheduleBoundaryReceiver.schedule(context)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // A wash rather than a solid error surface: this is a warning, not
            // an error, and a full red card would shout over everything else.
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.30f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (disabled) "Widget is off" else "Turn the widget off",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = if (disabled) {
                    "It is showing the time and nothing else. Their photo, the " +
                        "message dot and the drawing light are all hidden, and " +
                        "tapping it opens your clock app."
                } else {
                    "Same as being outside your chosen hours — just a clock, " +
                        "nothing personal on show. Stays that way until you " +
                        "turn it back on."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            if (disabled) {
                Button(
                    onClick = { apply(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Turn it back on") }
            } else {
                OutlinedButton(
                    onClick = { apply(true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Disable") }
            }
        }
    }
}
