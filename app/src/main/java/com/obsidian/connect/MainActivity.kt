package com.obsidian.connect

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.auth.AuthScreen
import com.obsidian.connect.pairing.PairingScreen
import com.obsidian.connect.reminders.RemindersScreen
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ConnectApp()
                }
            }
        }
    }
}

@Composable
private fun ConnectApp(viewModel: RootViewModel = hiltViewModel()) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    when (stage) {
        // Deliberately blank. This state lasts a few frames while Firebase
        // reports the cached session, and a spinner that brief reads as a
        // flicker rather than as progress.
        Stage.Loading -> Unit

        Stage.SignedOut -> AuthScreen(onSignedIn = {})

        Stage.Unpaired -> PairingScreen(onPaired = {})

        Stage.Ready -> RemindersScreen()
    }
}

/**
 * Asks once, on first composition.
 *
 * Without this on Android 13 and up, nudges are accepted by the system and
 * then silently discarded — the sender sees success and the notification
 * simply never appears.
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
