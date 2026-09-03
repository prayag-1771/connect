package com.obsidian.connect.jam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.core.model.JamSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The Spotify half of a jam.
 *
 * Same shared document as YouTube, same rule: whoever touches the controls
 * writes, the other follows. The difference is where the audio comes from -
 * Spotify plays it in its own app on each phone, and this only tells it what
 * to do.
 *
 * That means both people need Spotify installed, open at least once, and
 * Premium. There is no way around any of the three; Spotify enforces all of
 * them at the API.
 */
@Composable
fun SpotifyJamScreen(
    session: JamSession?,
    onLoad: (String, String) -> Unit,
    onProblem: (String?) -> Unit,
    onReport: (Boolean, Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SpotifyApi.State>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var localPlaying by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var loadedUri by remember { mutableStateOf("") }

    // Follow the session, exactly as the YouTube side does.
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        if (!current.isFor(JamSession.SPOTIFY) || !current.isLoaded) return@LaunchedEffect

        applying = true
        val target = current.expectedPositionMs()

        val outcome = if (current.videoId != loadedUri) {
            loadedUri = current.videoId
            SpotifyApi.play(context, current.videoId, target)
        } else {
            val state = SpotifyApi.state(context).getOrNull()
            val drift = state?.let { abs(target - it.positionMs) } ?: 0L
            if (drift > DRIFT_TOLERANCE_MS) SpotifyApi.seek(context, target)
            if (current.playing) SpotifyApi.resume(context) else SpotifyApi.pause(context)
        }

        outcome.onFailure { onProblem(it.message) }
        localPlaying = current.playing
        delay(600)
        applying = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session?.isLoaded == true && session.isFor(JamSession.SPOTIFY)) {
            Text(
                text = session.title.ifBlank { "Playing together" },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Playing in Spotify on both phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FilledIconButton(
                onClick = {
                    val next = !localPlaying
                    localPlaying = next
                    scope.launch {
                        val state = SpotifyApi.state(context).getOrNull()
                        onReport(next, state?.positionMs ?: 0L)
                    }
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (localPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (localPlaying) "Pause" else "Play",
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search Spotify") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                searching = true
                scope.launch {
                    SpotifyApi.search(context, query)
                        .onSuccess { results = it; onProblem(null) }
                        .onFailure { onProblem(it.message) }
                    searching = false
                }
            },
            enabled = query.isNotBlank() && !searching,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (searching) "Searching..." else "Search") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items = results, key = { it.trackUri }) { track ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLoad(track.trackUri, "${track.title} - ${track.artist}") }
                        .padding(vertical = 8.dp),
                ) {
                    Text(track.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val DRIFT_TOLERANCE_MS = 1_500L
