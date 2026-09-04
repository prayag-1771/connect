package com.obsidian.connect.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Getting the older conversation off the phone.
 *
 * Says how much there is before asking, because "download your chat history"
 * means nothing without a sense of whether that is a page or a year.
 */
@Composable
fun DownloadChatCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var version by remember { mutableIntStateOf(0) }
    val lines = remember(version) { ChatArchive.lineCount(context) }
    val has = remember(version) { ChatArchive.exists(context) }

    var confirming by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Older conversation", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (has) {
                    "Everything older than four days is kept here - about " +
                        "$lines lines. It leaves the chat but not the phone."
                } else {
                    "Nothing has aged out yet. Messages older than four days " +
                        "move here automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { confirming = true },
                enabled = has,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Text("  Download it")
            }

            result?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Download the older chat?") },
            text = {
                Text(
                    "A zip goes into your Downloads folder: the conversation " +
                        "with dates, times and who said what, plus every photo " +
                        "kept on this phone. Anything already there is left alone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        result = ChatExport.save(context)
                            .fold(
                                onSuccess = { "Saved to Downloads as $it" },
                                onFailure = { it.message ?: "That did not work." },
                            )
                        version++
                    },
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Not now") }
            },
        )
    }
}

/**
 * The same thing, said at the top of the conversation.
 *
 * Where somebody actually runs out of chat is where they think to ask for the
 * rest of it, so the offer is put there as well as in the settings.
 */
@Composable
fun ArchiveHint(onDownload: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Text("  Older than four days - download it")
        }
    }
}
