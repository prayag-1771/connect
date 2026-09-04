package com.obsidian.connect.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What shows while the app is locked.
 *
 * Deliberately blank of content — no photo, no name, no message preview. The
 * point of the lock is that none of that is visible to whoever picked up the
 * phone, and a lock screen that leaks a preview defeats itself.
 */
@Composable
fun LockedScreen(
    /** Asks for a fingerprint; the prompt reports success itself. */
    onUnlock: () -> Unit,
    /** The PIN was right, so let them in. */
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasPin = remember { AppLock.hasOwnPin(context) }
    val canFingerprint = remember { AppLock.canPrompt(context) }

    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Connect is locked",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = when {
                hasPin && canFingerprint -> "Use your fingerprint, or enter your PIN."
                hasPin -> "Enter your Connect PIN."
                else -> "Unlock with your fingerprint or screen lock."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        if (hasPin) {
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    // Digits only, and short. A field that accepts anything
                    // invites a password, which is not what was set.
                    pin = it.filter(Char::isDigit).take(MAX_PIN)
                    wrong = false
                },
                label = { Text("PIN") },
                singleLine = true,
                isError = wrong,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )

            if (wrong) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "That is not the PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (AppLock.checkPin(context, pin)) {
                        pin = ""
                        onUnlocked()
                    } else {
                        wrong = true
                        pin = ""
                    }
                },
                enabled = pin.length >= MIN_PIN,
            ) { Text("Unlock") }
        }

        if (canFingerprint) {
            Spacer(Modifier.height(8.dp))
            // The prompt appears on its own; this is for after a cancel, so a
            // dismissed dialog does not leave a dead end.
            TextButton(onClick = onUnlock) { Text("Use fingerprint") }
        }
    }
}

/** Four is the shortest that is worth anything; eight is already a nuisance. */
private const val MIN_PIN = 4
private const val MAX_PIN = 8
