package com.obsidian.connect.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import com.obsidian.connect.archive.DownloadChatCard
import com.obsidian.connect.timetable.TimetableActivity
import androidx.compose.material.icons.outlined.CalendarMonth
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
    val chatTheme by viewModel.chatTheme.collectAsStateWithLifecycle()
    val paired by viewModel.paired.collectAsStateWithLifecycle()
    val partnerOnline by viewModel.partnerOnline.collectAsStateWithLifecycle()
    val partnerFree by viewModel.partnerFree.collectAsStateWithLifecycle()

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoRow(
                        label = "Connected to",
                        value = partnerName.ifBlank { "Nobody yet" },
                        modifier = Modifier.weight(1f),
                    )

                    if (partnerName.isNotBlank()) {
                        // A dot and a word. The dot alone is ambiguous on a
                        // screen with several other coloured dots on it.
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (partnerOnline) {
                                        ONLINE_GREEN
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                ),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = if (partnerOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (partnerOnline) {
                                ONLINE_GREEN
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { context.startActivity(Intent(context, ArchiveActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
            Text("  Photos kept on this phone")
        }

        OutlinedButton(
            onClick = { TimetableActivity.open(context) },
            modifier = Modifier.fillMaxWidth(),
            colors = if (partnerFree) {
                ButtonDefaults.outlinedButtonColors(
                    containerColor = ONLINE_GREEN.copy(alpha = 0.14f),
                    contentColor = ONLINE_GREEN,
                )
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
            Text("  Timetable")
            Spacer(Modifier.weight(1f))
            Text(
                text = if (partnerFree) "Available" else "Not available",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        AppearanceCard(
            chatTheme = chatTheme,
            onChatTheme = viewModel::setChatTheme,
            canChangeChat = paired,
        )

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

        DownloadChatCard()

        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

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
    var fingerprint by remember { mutableStateOf(AppLock.isFingerprintEnabled(context)) }
    var hasPin by remember { mutableStateOf(AppLock.hasOwnPin(context)) }
    var settingPin by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lock the app", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Ask to be let in every time Connect is opened.",
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

            if (enabled) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprint", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Worth turning off on a phone whose fingerprints " +
                                "are not all yours.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = fingerprint,
                        // Turning both off would leave no way in at all.
                        enabled = hasPin || !fingerprint,
                        onCheckedChange = {
                            fingerprint = it
                            AppLock.setFingerprintEnabled(context, it)
                        },
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("A PIN of its own", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (hasPin) {
                                "Set. The screen lock on this phone will not open Connect."
                            } else {
                                "Off, so the screen lock on this phone opens Connect. " +
                                    "Set one if somebody else knows it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = hasPin,
                        // Removing the PIN with fingerprints off would lock
                        // everybody out, so it is refused rather than allowed
                        // and regretted.
                        enabled = fingerprint || !hasPin,
                        onCheckedChange = { wanted ->
                            if (wanted) {
                                settingPin = true
                            } else {
                                AppLock.clearOwnPin(context)
                                hasPin = false
                            }
                        },
                    )
                }

                if (hasPin) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { settingPin = true }) { Text("Change PIN") }
                }
            }
        }
    }

    if (settingPin) {
        PinDialog(
            onDismiss = { settingPin = false },
            onSet = { pin ->
                AppLock.setOwnPin(context, pin)
                hasPin = true
                settingPin = false
            },
        )
    }
}

/**
 * Setting a PIN, twice.
 *
 * Confirmed rather than typed once, because a PIN nobody can remember locks the
 * app for good - there is no account recovery behind this and nothing on a
 * server to reset it with.
 */
@Composable
private fun PinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }

    val longEnough = first.length >= 4
    val matches = first == second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text(
                    text = "Four to eight digits. Nothing can reset this for you, " +
                        "so pick one you will not forget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it.filter(Char::isDigit).take(8) },
                    label = { Text("Again") },
                    singleLine = true,
                    isError = second.isNotEmpty() && !matches,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSet(first) },
                enabled = longEnough && matches,
            ) { Text("Set it") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The one green in the app that means "yes, now". */
private val ONLINE_GREEN = Color(0xFF3DA35D)
