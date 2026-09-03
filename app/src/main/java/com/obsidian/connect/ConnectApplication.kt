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
import com.obsidian.connect.sync.SyncScheduler
import com.obsidian.connect.widget.DrawingBubble
import com.obsidian.connect.widget.ScheduleBoundaryReceiver
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.HiltAndroidApp
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

        // The indicator is a window owned by this process, so it dies with it.
        // If something is still unseen, put it back.
        if (WidgetCaptionStore.hasNewDrawing(this)) DrawingBubble.show(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
