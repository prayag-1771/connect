package com.obsidian.connect.jam

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The invitation, wherever you happen to be.
 *
 * Hung off the root of the app rather than the jam screen, because somebody
 * being asked to join is by definition not on the jam screen yet. The same
 * dialog serves the white dot on the widget, which opens the app straight onto
 * this question.
 *
 * Saying yes takes you there. Saying no does nothing at all - it does not end
 * the room the other person is sitting in, and it does not ask again until they
 * actually ask again.
 */
@Composable
fun JamRequestDialog() {
    if (!JamRequestGate.asking) return

    val context = LocalContext.current
    val viewModel: JamChatViewModel = hiltViewModel()
    val room by viewModel.room.collectAsStateWithLifecycle()

    val current = room
    val uid = viewModel.myUid

    // The request may have been withdrawn, or already joined from elsewhere,
    // between the dot being tapped and this appearing.
    if (current == null || uid == null || !current.isWaitingFor(uid)) {
        JamRequestGate.accept()
        return
    }

    AlertDialog(
        onDismissRequest = {
            viewModel.decline()
            JamRequestGate.decline(current.requestedAtMillis)
        },
        title = { Text("Join the jam chat?") },
        text = {
            Text(
                "They are waiting in a jam chat. Whatever either of you types " +
                    "gets searched and put on, and nothing said in it is kept.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.join()
                    JamRequestGate.accept()
                    JamActivity.open(context)
                },
            ) { Text("Join") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    // Written down, so their Request button can tell a no from
                    // silence and offer to ask again.
                    viewModel.decline()
                    JamRequestGate.decline(current.requestedAtMillis)
                },
            ) { Text("Not now") }
        },
    )
}
