package com.obsidian.connect.jam

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.ui.theme.ConnectTheme

/**
 * Connecting Spotify, once.
 *
 * Everything here except the client id is automatic. The client id cannot be:
 * Spotify issues one per registered application, tied to the account that
 * registered it, so it is not something this app can ship on your behalf. The
 * two steps that need doing on their website are spelled out rather than
 * assumed, because the errors Spotify gives when they are wrong say nothing
 * useful about which one it was.
 */
class SpotifySetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SpotifySetupScreen(
                        redirect = intent?.data,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    /**
     * Also the target of the login redirect.
     *
     * singleTask with the scheme filter, so coming back from the browser lands
     * on the screen that started it rather than stacking a second copy.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(
                Intent(context, SpotifySetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun SpotifySetupScreen(redirect: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current

    var clientId by remember { mutableStateOf(SpotifyStore.clientId(context)) }
    var connected by remember { mutableStateOf(SpotifyStore.isConnected(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Coming back from the browser with a code to exchange.
    LaunchedEffect(redirect) {
        val uri = redirect ?: return@LaunchedEffect
        if (!uri.toString().startsWith(SpotifyStore.REDIRECT_URI)) return@LaunchedEffect

        busy = true
        SpotifyAuth.exchange(context, uri)
            .onSuccess {
                connected = true
                status = "Connected. Spotify is ready to jam on."
            }
            .onFailure { status = it.message }
        busy = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Spotify", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (connected) {
                    "Connected. You can pick Spotify when you start a jam."
                } else {
                    "Two things on Spotify's website, then one tap here."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("1. Create an app", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Go to developer.spotify.com/dashboard, log in, and " +
                            "create an app. Any name will do.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://developer.spotify.com/dashboard"),
                                ),
                            )
                        },
                    ) { Text("Open the dashboard") }

                    Spacer(Modifier.height(4.dp))

                    Text("2. Add this redirect URI", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = SpotifyStore.REDIRECT_URI,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "It has to match exactly. One wrong character and " +
                            "the login fails without saying which.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("redirect", SpotifyStore.REDIRECT_URI),
                            )
                            status = "Copied."
                        },
                    ) { Text("Copy it") }
                }
            }

            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    SpotifyStore.setClientId(context, it)
                },
                label = { Text("Client ID from the dashboard") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val url = SpotifyAuth.authorizeUrl(context)
                    if (url == null) {
                        status = "Paste the client ID first."
                        return@Button
                    }
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                enabled = clientId.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (connected) "Sign in again" else "Connect Spotify") }

            if (connected) {
                OutlinedButton(
                    onClick = {
                        SpotifyStore.disconnect(context)
                        connected = false
                        status = "Disconnected."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect") }
            }

            Text(
                text = "Controlling playback needs Premium. Search and choosing " +
                    "tracks work without it, but play, pause and seek do not - " +
                    "that is Spotify's rule, not this app's.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
