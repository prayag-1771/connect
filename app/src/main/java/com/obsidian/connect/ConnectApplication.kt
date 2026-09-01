package com.obsidian.connect

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
