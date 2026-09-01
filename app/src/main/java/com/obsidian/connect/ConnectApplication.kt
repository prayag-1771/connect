package com.obsidian.connect

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.obsidian.connect.messaging.Notifications
import com.obsidian.connect.sync.SyncScheduler
import com.obsidian.connect.widget.ScheduleBoundaryReceiver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager is configured by hand rather than through its default startup
 * provider so its workers can be constructed by Hilt. The default initializer
 * is removed in the manifest to stop the two from racing.
 */
@HiltAndroidApp
class ConnectApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Has to exist before the first nudge lands. A notification naming a
        // channel that was never created is dropped without a trace.
        Notifications.ensureChannels(this)

        // With no server to push to us, this schedule is the only thing that
        // updates a widget while the app is closed.
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.now(this)

        // Flips the watch face over when the active window opens or closes.
        ScheduleBoundaryReceiver.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
