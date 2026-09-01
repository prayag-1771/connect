package com.obsidian.connect

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.obsidian.connect.draw.DrawScreen
import com.obsidian.connect.profile.ProfileScreen
import com.obsidian.connect.reminders.RemindersScreen
import com.obsidian.connect.send.CaptureScreen

private enum class HomeTab(val label: String, val icon: ImageVector) {
    Capture("Send", Icons.Filled.PhotoCamera),
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
fun HomeScreen(modifier: Modifier = Modifier) {
    // Stored as an index rather than the enum itself, because rememberSaveable
    // can only persist what goes into a Bundle.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = HomeTab.entries

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
            HomeTab.Draw -> DrawScreen(modifier = Modifier.padding(insets))
            HomeTab.Reminders -> RemindersScreen(modifier = Modifier.padding(insets))
            HomeTab.You -> ProfileScreen(modifier = Modifier.padding(insets))
        }
    }
}
