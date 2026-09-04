package com.obsidian.connect

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.obsidian.connect.chat.ChatFocus
import com.obsidian.connect.jam.JamRequestDialog
import com.obsidian.connect.profile.DailyArmPrompt
import com.obsidian.connect.chat.ChatScreen
import com.obsidian.connect.draw.DrawScreen
import com.obsidian.connect.profile.ProfileScreen
import com.obsidian.connect.reminders.RemindersScreen
import com.obsidian.connect.send.CaptureScreen

enum class HomeTab(val label: String, val icon: ImageVector) {
    Capture("Send", Icons.Filled.PhotoCamera),
    Chat("Chat", Icons.AutoMirrored.Outlined.Chat),
    Draw("Draw", Icons.Outlined.Brush),
    Reminders("Reminders", Icons.Outlined.Checklist),
    You("You", Icons.Outlined.Person),
}

/**
 * What you see once signed in and paired.
 *
 * Both destinations stay composed only while selected, which matters for the
 * camera: leaving it composed in the background would hold the capture session
 * open and keep the sensor powered while the reminder list is on screen.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    requestedTab: HomeTab? = null,
    requestId: Int = 0,
) {
    val tabs = HomeTab.entries

    // Stored as an index rather than the enum itself, because rememberSaveable
    // can only persist what goes into a Bundle.
    var selected by rememberSaveable { mutableIntStateOf(tabs.indexOf(HomeTab.Chat)) }

    // Keyed on the request id, not the tab, so tapping the widget twice still
    // brings you back here after you have navigated elsewhere in between.
    LaunchedEffect(requestId) {
        requestedTab?.let { selected = tabs.indexOf(it) }
    }

    // Coming back from the starred list, which asked for a particular message.
    // The chat clears the request itself once it has scrolled to it.
    LaunchedEffect(ChatFocus.pendingMessageId) {
        if (ChatFocus.pendingMessageId != null) selected = tabs.indexOf(HomeTab.Chat)
    }

    // The morning question, if it is switched on and today is unanswered.
    DailyArmPrompt()

    // Somebody waiting in a jam chat, asked here rather than on the jam screen
    // because that is exactly where they are not.
    JamRequestDialog()

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = index == selected,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { insets ->
        when (tabs[selected]) {
            HomeTab.Capture -> CaptureScreen(modifier = Modifier.padding(insets))
            // Given the raw insets rather than pre-padded. Chat has to
            // reconcile them against the keyboard itself; applying both here
            // leaves a dead gap the height of the navigation bar.
            HomeTab.Chat -> ChatScreen(contentPadding = insets)
            HomeTab.Draw -> DrawScreen(modifier = Modifier.padding(insets))
            HomeTab.Reminders -> RemindersScreen(modifier = Modifier.padding(insets))
            HomeTab.You -> ProfileScreen(modifier = Modifier.padding(insets))
        }
    }
}
