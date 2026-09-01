package com.obsidian.connect.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Pairing

/**
 * Where a user either opens an invite or redeems one.
 *
 * Both people land here; whoever gets there first creates the code.
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val current = pairing

    // In a LaunchedEffect rather than inline: calling back during composition
    // fires on every recomposition, and navigating out of a composable while
    // it is still being composed is how you get a frame of the wrong screen.
    LaunchedEffect(current?.isComplete) {
        if (current != null && current.isComplete) onPaired()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (current != null) {
            WaitingForPartner(
                pairing = current,
                busy = uiState.busy,
                onCancel = viewModel::cancelInvite,
            )
        } else {
            StartPairing(
                busy = uiState.busy,
                onCreate = viewModel::createInvite,
                onJoin = viewModel::join,
            )
        }

        uiState.error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WaitingForPartner(
    pairing: Pairing,
    busy: Boolean,
    onCancel: () -> Unit,
) {
    Text(
        text = "Share this code",
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "They enter it on their phone to connect with you.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))

    Card {
        Text(
            text = pairing.inviteCode,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
            // Monospace and widely spaced because this code gets read aloud
            // and typed by hand more often than it gets copied.
            fontFamily = FontFamily.Monospace,
            fontSize = 34.sp,
            letterSpacing = 8.sp,
        )
    }

    Spacer(Modifier.height(24.dp))
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Waiting for them to join",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))

    // Without this the screen is a dead end. Creating an invite writes the
    // pairing id onto the user document, so there is no way back to the start
    // — not even by reinstalling, since the state lives on the server.
    TextButton(onClick = onCancel, enabled = !busy) {
        Text("Cancel this invite")
    }
}

@Composable
private fun StartPairing(
    busy: Boolean,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Text(
        text = "Connect with someone",
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(32.dp))

    Button(
        onClick = onCreate,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Create an invite")
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(24.dp))

    OutlinedTextField(
        value = code,
        // Codes are generated uppercase, so uppercasing here means someone
        // typing lowercase still matches instead of hitting "no such code".
        onValueChange = { code = it.uppercase().take(6) },
        label = { Text("Have a code?") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { onJoin(code) },
        enabled = !busy && code.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Join")
    }
}
