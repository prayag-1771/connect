package com.obsidian.connect.jam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.obsidian.connect.MainActivity
import com.obsidian.connect.R
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.JamRepository
import com.obsidian.connect.core.data.UserRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps a jam playing off-screen, and puts it where music belongs.
 *
 * Two jobs that are really one. A foreground service is the only supported way
 * to tell Android that a backgrounded process is playing music rather than
 * idling - and once something is playing music, the place people expect to
 * control it is the lock screen and the shade, not by finding the app again.
 *
 * The controls write to the shared session rather than to the player. Pausing
 * from the lock screen has to pause it for both of you, and going through the
 * session is what makes that the same action as pausing from inside the app.
 */
@AndroidEntryPoint
class JamService : Service() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var jamRepository: JamRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "Connect").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = write(playing = true)
                    override fun onPause() = write(playing = false)
                    override fun onStop() = write(playing = false)

                    override fun onSeekTo(pos: Long) = write(playing = null, position = pos)

                    override fun onSkipToNext() {
                        // Handled where the queue lives; the service only knows
                        // that somebody asked for the next thing.
                        JamPlayerHolder.onFinished?.invoke()
                    }
                },
            )
            isActive = true
        }
    }

    /**
     * Writes the transport action to the shared session.
     *
     * [playing] null means "leave it as it is" - a seek should not also start
     * a paused track.
     */
    private fun write(playing: Boolean?, position: Long? = null) {
        scope.launch {
            val uid = authRepository.currentUid ?: return@launch
            val pairingId = userRepository.get(uid)?.pairingId ?: return@launch

            jamRepository.update(
                pairingId = pairingId,
                uid = uid,
                playing = playing ?: JamPlayerHolder.isReady,
                positionMs = position ?: JamPlayerHolder.lastPositionMs,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true

        ensureChannel()
        publish(title, playing)
        startForeground(ID, notification(title), foregroundType())

        return START_NOT_STICKY
    }

    /**
     * What the lock screen shows and which buttons it offers.
     *
     * The position is given rather than described, so Android draws a real
     * progress bar and lets it be scrubbed - which is the whole point of this
     * over a plain notification with two buttons.
     */
    private fun publish(title: String, playing: Boolean) {
        val active = session ?: return

        active.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "Jam" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Listening together")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, JamPlayerHolder.durationMs)
                .build(),
        )

        active.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    JamPlayerHolder.lastPositionMs,
                    if (playing) 1f else 0f,
                )
                .build(),
        )
    }

    private fun notification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title.ifBlank { "Listening together" })
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(
                Notification.MediaStyle().setMediaSession(session?.sessionToken),
            )
            .build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Jam",
                // Low: a standing note that music is on, not news.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.isActive = false
        session?.release()
        session = null
        scope.cancel()
    }

    companion object {
        private const val CHANNEL = "jam_playback"
        private const val ID = 4313
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, title: String, playing: Boolean = true) {
            val intent = Intent(context, JamService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAYING, playing)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, JamService::class.java)) }
        }
    }
}
