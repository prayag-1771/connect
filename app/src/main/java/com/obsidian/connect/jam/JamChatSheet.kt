package com.obsidian.connect.jam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.JamChatMessage

/**
 * The jam chat, while it lasts.
 *
 * A sheet rather than a screen, so the player stays visible behind it - the
 * point of typing a song name is watching it come on, and hiding the thing you
 * are changing would be an odd way to arrange that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamChatSheet(
    messages: List<JamChatMessage>,
    myUid: String?,
    searchable: Boolean,
    onSend: (String) -> Unit,
    onEnd: () -> Unit,
    onHide: () -> Unit,
    onRequest: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    ModalBottomSheet(
        // Swiping down or pressing back only puts the sheet away. It used to
        // end the room outright, which meant a stray back gesture deleted the
        // conversation for both people - a dismissal is not a decision, and
        // treating it as one made the whole thing feel broken.
        onDismissRequest = onHide,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Jam chat",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRequest) { Text("Request") }
                TextButton(onClick = onEnd) { Text("End") }
            }

            Text(
                text = if (searchable) {
                    "Type a song and it goes on. Anything else stays a message."
                } else {
                    "Paste a link to put something on. Search needs a key adding."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                // Capped so a long jam chat cannot push the composer off the
                // bottom of the sheet.
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = messages, key = { it.id }) { message ->
                    Line(message = message, mine = message.senderId == myUid)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("A song, or anything") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * One line, as a message.
 *
 * A conversation first. Every line is something somebody said and sits where
 * they said it - yours on the right, theirs on the left - and a line that also
 * put a song on carries that underneath rather than being replaced by it. The
 * words were still spoken, and turning them into a system notice threw away
 * half of what happened.
 */
@Composable
private fun Line(message: JamChatMessage, mine: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        // The squared corner points at whoever sent it, which
                        // is quicker to read than colour alone.
                        bottomStart = if (mine) 14.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 14.dp,
                    ),
                )
                .background(
                    if (mine) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
        }

        if (message.becameATrack) {
            Spacer(Modifier.size(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = message.playedTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 230.dp),
                )
            }
        }
    }
}

/**
 * The other person has opened a jam chat and is waiting.
 *
 * Declining ends the room rather than leaving it open, so nobody is left
 * typing into something that was never answered.
 */
@Composable
fun JamChatInvite(onJoin: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Jam chat?") },
        text = {
            Text(
                "They have opened a jam chat. Whatever either of you types gets " +
                    "searched and put on. Nothing said in it is kept.",
            )
        },
        confirmButton = { TextButton(onClick = onJoin) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } },
    )
}
