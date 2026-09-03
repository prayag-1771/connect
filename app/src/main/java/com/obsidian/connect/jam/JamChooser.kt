package com.obsidian.connect.jam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Which service to jam on.
 *
 * Two entries rather than one because the answer is going to change. YouTube
 * works today and needs nothing; Spotify needs Premium on both phones, which
 * is a thing you either have or do not, and the option is here so that the day
 * it becomes true there is somewhere obvious to turn it on.
 *
 * Apple Music is deliberately absent. Apple ships MusicKit for iOS and the web
 * only, so no Android app can drive it - listing it would be offering something
 * that cannot be built rather than something not built yet.
 */
@Composable
fun JamChooser(
    onYouTube: () -> Unit,
    onSpotify: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen together") },
        text = {
            Column {
                Choice(
                    icon = Icons.Filled.PlayCircle,
                    title = "YouTube",
                    detail = "Works now. Both phones stay in step on play, " +
                        "pause and seek.",
                    onClick = onYouTube,
                )
                Spacer(Modifier.size(8.dp))
                Choice(
                    icon = Icons.Filled.MusicNote,
                    title = "Spotify",
                    detail = "Connect your account, then jam on it. Playback " +
                        "control needs Premium.",
                    onClick = onSpotify,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

@Composable
private fun Choice(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(14.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
