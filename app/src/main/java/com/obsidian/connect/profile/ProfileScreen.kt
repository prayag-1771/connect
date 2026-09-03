package com.obsidian.connect.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Settings
import com.obsidian.connect.starred.StarredActivity
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.obsidian.connect.archive.ArchiveActivity
import com.obsidian.connect.lock.AppLock
import com.obsidian.connect.widget.DrawingBubble

/**
 * Account and pairing controls — the way back out.
 */
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val myName by viewModel.myName.collectAsStateWithLifecycle()
    val partnerName by viewModel.partnerName.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var confirmingLeave by remember { mutableStateOf(false) }
    var confirmingSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "You",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { SettingsActivity.open(context) }) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(label = "Name", value = myName.ifBlank { "—" })
                Spacer(Modifier.height(8.dp))
                InfoRow(label = "Email", value = viewModel.myEmail ?: "—")
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                InfoRow(
                    label = "Connected to",
                    value = partnerName.ifBlank { "Nobody yet" },
                )
            }
        }

        OutlinedButton(
            onClick = { context.startActivity(Intent(context, ArchiveActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
            Text("  Photos kept on this phone")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { StarredActivity.open(context) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Starred messages", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Everything either of you kept",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { confirmingLeave = true },
            enabled = !state.busy && partnerName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.HeartBroken, contentDescription = null)
            Text("  Disconnect from them")
        }

        OutlinedButton(
            onClick = { confirmingSignOut = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
            Text("  Sign out")
        }

        Text(
            text = "Signing out leaves your pairing intact — sign back in and " +
                "everything is where you left it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Disconnect?") },
            // Says it plainly because it is not reversible and it affects
            // someone who is not holding this phone.
            text = {
                Text(
                    "This ends the connection for both of you. Your shared " +
                        "reminders, drawings and photos go with it. Your private " +
                        "list stays.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.leavePairing()
                        confirmingLeave = false
                    },
                ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Stay connected") }
            },
        )
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your pairing stays. The widget on this phone goes blank.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.signOut()
                        confirmingSignOut = false
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Overlay permission, which drives the floating blue drawing indicator.
 *
 * Not a runtime permission — Android only grants it through a Settings screen,
 * so there is nothing to request in-app beyond sending the user there.
 */
@Composable
fun DrawingIndicatorCard() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(DrawingBubble.canShow(context)) }

    // Re-checked on resume, since the answer changes in Settings rather than here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = DrawingBubble.canShow(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Show drawings over the screen", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "A blue light appears on the edge of your screen when they " +
                    "draw something. Android needs permission for anything drawn " +
                    "over other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedButton(
                onClick = { context.startActivity(DrawingBubble.settingsIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open settings") }
        }
    }
}

/**
 * The app lock switch.
 *
 * Hidden entirely when the phone has no screen lock or enrolled fingerprint —
 * there would be nothing to authenticate against, and a switch that silently
 * never works is worse than no switch.
 */
@Composable
fun AppLockCard() {
    val context = LocalContext.current
    if (!AppLock.isAvailable(context)) return

    var enabled by remember { mutableStateOf(AppLock.isEnabled(context)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Lock the app", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Ask for your fingerprint or screen lock every time " +
                        "Connect is opened.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    AppLock.setEnabled(context, it)
                },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
