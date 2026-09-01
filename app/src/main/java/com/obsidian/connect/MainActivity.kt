package com.obsidian.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                ConnectApp()
            }
        }
    }
}

// Placeholder shell. Auth, pairing, camera, chat and the drawing canvas land here.
@Composable
private fun ConnectApp() {
    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Text(text = "Connect", modifier = Modifier.padding(insets))
    }
}
