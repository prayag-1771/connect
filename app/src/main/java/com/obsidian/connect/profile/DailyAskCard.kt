package com.obsidian.connect.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.widget.DailyResetReceiver
import com.obsidian.connect.widget.WatchWidgetProvider

/**
 * Whether the face has to be switched on again each morning.
 *
 * Off by default. It is deliberate friction, and friction nobody asked for is
 * just an annoyance - but for someone whose phone spends the day among other
 * people, having to choose each morning is the difference between a setting
 * and a decision.
 */
@Composable
fun DailyAskCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DailyResetReceiver.isEnabled(context)) }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Ask me each morning", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "At 6am the face turns itself off. It stays off until " +
                        "you open Connect and say yes for the day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    DailyResetReceiver.setEnabled(context, it)
                    WatchWidgetProvider.refreshAll(context)
                },
            )
        }
    }
}
