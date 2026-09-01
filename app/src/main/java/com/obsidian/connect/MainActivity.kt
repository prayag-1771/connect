package com.obsidian.connect

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.auth.AuthScreen
import com.obsidian.connect.pairing.PairingScreen
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Which tab an incoming intent asked for, and a counter beside it.
     *
     * The counter is what makes a repeat request work. Selecting the same tab
     * twice is not a state change, so keying off the tab alone would ignore a
     * second tap on the widget after the user had navigated somewhere else.
     */
    private val requestedTab = mutableStateOf<HomeTab?>(null)
    private val requestId = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyIntent(intent)

        setContent {
            ConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ConnectApp(
                        requestedTab = requestedTab.value,
                        requestId = requestId.intValue,
                    )
                }
            }
        }
    }

    /**
     * The widget launches with FLAG_ACTIVITY_SINGLE_TOP, so when the app is
     * already open this arrives here rather than through onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_TAB) ?: return
        val tab = HomeTab.entries.firstOrNull { it.name == name } ?: return
        requestedTab.value = tab
        requestId.intValue += 1
    }

    companion object {
        const val EXTRA_TAB = "open_tab"
    }
}

@Composable
private fun ConnectApp(
    requestedTab: HomeTab? = null,
    requestId: Int = 0,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    when (stage) {
        // Deliberately blank. This state lasts a few frames while Firebase
        // reports the cached session, and a spinner that brief reads as a
        // flicker rather than as progress.
        Stage.Loading -> Unit

        Stage.SignedOut -> AuthScreen(onSignedIn = {})

        Stage.Unpaired -> PairingScreen(onPaired = {})

        Stage.Ready -> HomeScreen(requestedTab = requestedTab, requestId = requestId)
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
