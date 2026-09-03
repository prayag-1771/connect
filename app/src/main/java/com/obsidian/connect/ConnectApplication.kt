package com.obsidian.connect

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.obsidian.connect.messaging.Notifications
import com.obsidian.connect.sync.FastSyncReceiver
import com.obsidian.connect.ui.theme.AppearanceStore
import com.obsidian.connect.sync.SyncScheduler
import com.obsidian.connect.sync.WidgetLiveUpdater
import com.obsidian.connect.widget.DrawingBubble
import com.obsidian.connect.widget.ScheduleBoundaryReceiver
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is configured by hand rather than through its default startup
 * provider so its workers can be constructed by Hilt. The default initializer
 * is removed in the manifest to stop the two from racing.
 */
@HiltAndroidApp
class ConnectApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    /**
     * Coil renders a GIF as a still first frame unless a decoder is registered.
     * The decoder differs by API level — ImageDecoder exists from 28.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()


    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var widgetLiveUpdater: WidgetLiveUpdater

    /**
     * Lives as long as the process does, which is the point.
     *
     * These listeners used to hang off the root screen's ViewModel, so they
     * were torn down the moment the activity went away — which is precisely
     * when the widget most needs them. Backgrounding the app dropped every
     * live update onto the fifteen-minute worker, and that is what made a new
     * photo take so long to reach the watch face.
     *
     * SupervisorJob so one listener failing does not silently take the others
     * down with it.
     */
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Read before anything paints, or the first frame flashes the wrong
        // theme on the way to the right one.
        AppearanceStore.init(this)

        // Has to exist before the first nudge lands. A notification naming a
        // channel that was never created is dropped without a trace.
        Notifications.ensureChannels(this)

        // With no server to push to us, this schedule is the only thing that
        // updates a widget while the app is closed.
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.now(this)

        // Flips the watch face over when the active window opens or closes.
        ScheduleBoundaryReceiver.schedule(this)

        // Runs a sync every couple of minutes even with the app closed, which
        // the fifteen-minute worker on its own cannot.
        FastSyncReceiver.schedule(this)

        // The indicator is a window owned by this process, so it dies with it.
        // If something is still unseen, put it back.
        if (WidgetCaptionStore.hasNewDrawing(this)) DrawingBubble.show(this)

        // Closes the gap the worker's fifteen-minute floor leaves, for as long
        // as this process is alive — which outlasts the visible app by a good
        // deal.
        liveScope.launch { widgetLiveUpdater.watchMoments() }
        liveScope.launch { widgetLiveUpdater.watchMessages() }
        liveScope.launch { widgetLiveUpdater.watchDrawings() }
        liveScope.launch { widgetLiveUpdater.watchReminderAlarms() }
        liveScope.launch { widgetLiveUpdater.watchCalls() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
