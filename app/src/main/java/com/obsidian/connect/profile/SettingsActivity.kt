package com.obsidian.connect.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Everything that is a setting rather than a fact about you.
 *
 * Split out because the You tab had become a wall of switches with a name at
 * the top. What is on that tab now is who you are and what is yours; what is
 * here is how the app behaves, which is looked at rarely and changed rarely.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SettingsScreen(onBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val chatTheme by viewModel.chatTheme.collectAsStateWithLifecycle()
    val paired by viewModel.paired.collectAsStateWithLifecycle()

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
            Text(text = "Settings", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppLockCard()

            AppearanceCard(
                chatTheme = chatTheme,
                onChatTheme = viewModel::setChatTheme,
                canChangeChat = paired,
            )

            WatchScheduleCard()

            DailyAskCard()

            PlacesCard()

            WidgetDisableCard()

            DrawingIndicatorCard()
        }
    }
}
