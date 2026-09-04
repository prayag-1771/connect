package com.obsidian.connect

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.auth.AuthScreen
import com.obsidian.connect.lock.AppLock
import com.obsidian.connect.lock.LockedScreen
import com.obsidian.connect.pairing.PairingScreen
import com.obsidian.connect.jam.JamRequestGate
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.UserRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /**
     * Which tab an incoming intent asked for, and a counter beside it.
     *
     * The counter is what makes a repeat request work. Selecting the same tab
     * twice is not a state change, so keying off the tab alone would ignore a
     * second tap on the widget after the user had navigated somewhere else.
     */
    private val requestedTab = mutableStateOf<HomeTab?>(null)
    private val requestId = mutableIntStateOf(0)

    /** Cleared on every stop, so returning to the app asks again. */
    private val unlocked = mutableStateOf(false)

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var userRepository: UserRepository

    /**
     * Beats while the app is in front, and stops when it is not.
     *
     * Tied to the activity rather than the process, because "online" here means
     * somebody is looking at it - a process kept alive by a jam playing in the
     * background is not the same as being present.
     */
    private var heartbeat: Job? = null

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
                    if (AppLock.isEnabled(this) && !unlocked.value) {
                        LockedScreen(onUnlock = ::askToUnlock)
                    } else {
                        ConnectApp(
                            requestedTab = requestedTab.value,
                            requestId = requestId.intValue,
                        )
                    }
                }
            }
        }
    }

    private fun beat(online: Boolean) {
        val uid = authRepository.currentUid ?: return
        lifecycleScope.launch { userRepository.markOnline(uid, online) }
    }

    override fun onStart() {
        super.onStart()
        if (AppLock.isEnabled(this) && !unlocked.value) askToUnlock()

        heartbeat?.cancel()
        heartbeat = lifecycleScope.launch {
            while (true) {
                beat(online = true)
                delay(HEARTBEAT_MS)
            }
        }
    }

    /**
     * Re-locks whenever the app leaves the screen.
     *
     * Locking only at launch would leave the app open behind a recents card
     * for anyone who picked the phone up, which is the case this exists for.
     */
    override fun onStop() {
        super.onStop()
        unlocked.value = false

        heartbeat?.cancel()
        heartbeat = null
        // Written rather than left to lapse, so closing the app looks like
        // closing the app rather than like a slow network.
        beat(online = false)
    }

    private fun askToUnlock() {
        AppLock.prompt(
            activity = this,
            onSuccess = { unlocked.value = true },
            // Cancelled or locked out. Leaving the app on the lock screen with
            // its own retry button is better than closing under someone.
            onFailure = { unlocked.value = false },
        )
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
        // Arrived from the white dot on the widget, which exists precisely to
        // put this question in front of somebody.
        if (intent?.getBooleanExtra(EXTRA_JAM_REQUEST, false) == true) {
            JamRequestGate.raise()
        }

        val name = intent?.getStringExtra(EXTRA_TAB) ?: return
        val tab = HomeTab.entries.firstOrNull { it.name == name } ?: return
        requestedTab.value = tab
        requestId.intValue += 1
    }

    companion object {
        /** Comfortably inside the window the other end trusts it for. */
        private const val HEARTBEAT_MS = 45_000L

        const val EXTRA_TAB = "open_tab"
        const val EXTRA_JAM_REQUEST = "jam_request"
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
