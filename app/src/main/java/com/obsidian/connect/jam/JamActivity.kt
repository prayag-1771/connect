package com.obsidian.connect.jam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Listening to one thing, on two phones, at the same moment.
 *
 * The sync is deliberately one-directional per event: whoever touches the
 * controls writes what they did, and the other phone follows. There is no
 * negotiation and no leader - either of you can take it at any time, which is
 * what makes it a jam rather than a broadcast.
 */
@AndroidEntryPoint
class JamActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JamScreen(
                        spotify = intent.getBooleanExtra(EXTRA_SPOTIFY, false),
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SPOTIFY = "spotify"

        fun open(context: Context) {
            context.startActivity(Intent(context, JamActivity::class.java))
        }

        fun openSpotify(context: Context) {
            context.startActivity(
                Intent(context, JamActivity::class.java).putExtra(EXTRA_SPOTIFY, true),
            )
        }
    }
}

@Composable
private fun JamScreen(
    spotify: Boolean,
    onBack: () -> Unit,
    viewModel: JamViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()

    val chatViewModel: JamChatViewModel = hiltViewModel()
    val room by chatViewModel.room.collectAsStateWithLifecycle()
    val chatMessages by chatViewModel.messages.collectAsStateWithLifecycle()

    // The Spotify lookup needs a context to reach its token store, so it is
    // handed in rather than the view model growing an Android dependency.
    LaunchedEffect(spotify) {
        chatViewModel.spotifySearch = if (!spotify) {
            null
        } else {
            { query ->
                SpotifyApi.search(context, query).getOrNull()
                    ?.firstOrNull()
                    ?.let { it.trackUri to "${it.title} - ${it.artist}" }
            }
        }
    }

    var link by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var localPlaying by remember { mutableStateOf(false) }
    var localPositionMs by remember { mutableLongStateOf(0L) }
    var loadedVideo by remember { mutableStateOf("") }

    // Set while a remote instruction is being carried out, so the resulting
    // player callback is not mistaken for something this person did and written
    // straight back - which is how two phones talk each other into a loop.
    var applying by remember { mutableStateOf(false) }

    val player = remember {
        JamPlayer(
            context = context,
            onReady = { ready = true },
            onStateChange = { playing ->
                localPlaying = playing
                if (!applying) viewModel.report(playing, localPositionMs)
            },
            onPositionMs = { localPositionMs = it },
            onError = { viewModel.showProblem(it) },
        )
    }

    DisposableEffect(Unit) {
        viewModel.join()
        onDispose {
            viewModel.leave()
            player.release()
        }
    }

    // The driver refreshes the stored position every half minute.
    //
    // Without it the position is only ever written on a play, a pause or a
    // seek, and someone joining ten minutes into a track would be told the
    // position from ten minutes ago plus ten minutes of elapsed time - which
    // runs off the end of anything shorter than that. Refreshing bounds the
    // guess to thirty seconds and costs two writes a minute.
    LaunchedEffect(ready, localPlaying, session?.byUid) {
        if (!ready || !localPlaying) return@LaunchedEffect
        if (!viewModel.isDriver(session)) return@LaunchedEffect

        while (true) {
            delay(30_000)
            if (!localPlaying) break
            viewModel.report(true, localPositionMs)
        }
    }

    // Follow whatever the session says. Keyed on the whole thing, so a pause
    // written by the other phone lands as promptly as a new track does.
    LaunchedEffect(session, ready) {
        val current = session ?: return@LaunchedEffect
        if (!ready || !current.isLoaded) return@LaunchedEffect

        applying = true

        if (current.videoId != loadedVideo) {
            loadedVideo = current.videoId
            player.load(current.videoId, current.expectedPositionMs(), current.playing)
        } else {
            val target = current.expectedPositionMs()
            // Only correct real drift. Nudging by a tenth of a second would
            // stutter the audio constantly and fix nothing anyone could hear.
            if (abs(target - localPositionMs) > DRIFT_TOLERANCE_MS) player.seekTo(target)
            if (current.playing) player.play() else player.pause()
        }

        // Long enough for the player to have finished reacting, short enough
        // that a real tap a moment later is still treated as one.
        delay(600)
        applying = false
    }

    // The player is asked where it is rather than told; the answer feeds drift
    // correction and the position written on the next play or pause.
    LaunchedEffect(ready) {
        while (ready) {
            player.requestPosition()
            delay(1_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Jam",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { chatViewModel.start() }) { Text("Jam chat") }

            if (session?.isLoaded == true) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = viewModel::end) { Text("End") }
            }
        }

        if (spotify) {
            SpotifyJamScreen(
                session = session,
                onLoad = viewModel::loadSpotify,
                onProblem = viewModel::showProblem,
                onReport = viewModel::report,
            )
            problem?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            AndroidView(
                factory = { player.view },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = session
            if (current?.isLoaded == true) {
                Text(
                    text = current.title.ifBlank { "Playing together" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (viewModel.theyAreHere(session)) {
                        "They are here too. Either of you can take the controls."
                    } else {
                        "Playing. They will drop straight into this when they open it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = {
                            if (localPlaying) player.pause() else player.play()
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (localPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (localPlaying) "Pause" else "Play",
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Paste a YouTube link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    viewModel.load(link)
                    link = ""
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Put it on for both of us") }

            problem?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    JamChatLayer(
        room = room,
        messages = chatMessages,
        myUid = chatViewModel.myUid,
        spotify = spotify,
        onJoin = chatViewModel::join,
        onEnd = chatViewModel::end,
        onSend = { text -> chatViewModel.send(text, spotify) },
    )
}

/**
 * The jam chat, in whichever of its three states it is in.
 *
 * Absent, invited, or open. Kept together so the transitions are readable:
 * every one of them is somebody joining or somebody ending it.
 */
@Composable
private fun JamChatLayer(
    room: com.obsidian.connect.core.model.JamChatRoom?,
    messages: List<com.obsidian.connect.core.model.JamChatMessage>,
    myUid: String?,
    spotify: Boolean,
    onJoin: () -> Unit,
    onEnd: () -> Unit,
    onSend: (String) -> Unit,
) {
    val current = room ?: return
    if (!current.isLive) return
    val me = myUid ?: return

    // Invited but not in it yet. Declining ends the room rather than leaving it
    // open, so nobody is left typing into something never answered.
    if (current.isWaitingFor(me)) {
        JamChatInvite(onJoin = onJoin, onDecline = onEnd)
        return
    }

    JamChatSheet(
        messages = messages,
        myUid = myUid,
        // Spotify search works on a free account; YouTube needs a key.
        searchable = spotify || YouTubeSearch.isConfigured,
        onSend = onSend,
        onEnd = onEnd,
    )
}

/**
 * How far apart the two phones may drift before it is worth a correction.
 *
 * Below about a second nobody can tell, and seeking to fix it is more
 * disruptive than the drift.
 */
private const val DRIFT_TOLERANCE_MS = 1_500L
