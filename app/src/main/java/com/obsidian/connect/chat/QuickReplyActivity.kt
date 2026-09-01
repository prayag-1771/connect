package com.obsidian.connect.chat

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The strip that drops down when the watch face's unread dot is tapped.
 *
 * An Activity rather than anything drawn by the widget itself, because a
 * widget cannot show UI — a tap on one can only fire a PendingIntent. Styled
 * as a floating strip so it reads as a peek at the message rather than as
 * having opened the app.
 */
@AndroidEntryPoint
class QuickReplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pinned to the top so it sits near the status bar with the keyboard
        // below it, leaving the home screen visible behind.
        window.apply {
            setGravity(Gravity.TOP)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        setFinishOnTouchOutside(true)

        setContent {
            ConnectTheme {
                QuickReply(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun QuickReply(
    onDone: () -> Unit,
    viewModel: QuickReplyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var reply by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(state.sent) {
        if (state.sent) onDone()
    }

    // The keyboard comes up on its own — the whole point of this surface is
    // replying without going anywhere.
    LaunchedEffect(Unit) {
        focus.requestFocus()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .imePadding(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (state.fromName.isNotBlank()) {
                Text(
                    text = state.fromName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = state.text.ifBlank { "No messages yet" },
                style = MaterialTheme.typography.bodyLarge,
                // One line, truncated. This is a glance, not the conversation —
                // the Chat tab is a tap away for the rest of it.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    placeholder = { Text("Reply") },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send(reply) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                )

                FilledIconButton(
                    onClick = { viewModel.send(reply) },
                    enabled = reply.isNotBlank() && !state.sending,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply")
                }
            }
        }
    }
}
