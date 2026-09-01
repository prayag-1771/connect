package com.obsidian.connect.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PERIODIC_WORK = "connect_periodic_sync"
    private const val IMMEDIATE_WORK = "connect_immediate_sync"

    /**
     * Fifteen minutes is not a choice — it is WorkManager's hard floor for
     * periodic work, and anything smaller is silently rounded up to it.
     *
     * This is the worst case for a photo landing on a closed phone. The live
     * listener while the app is open, and [now] on launch and boot, are what
     * keep that worst case rare.
     */
    private const val INTERVAL_MINUTES = 15L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ConnectSyncWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            // KEEP, not UPDATE: replacing the request on every launch resets
            // its interval, so a frequently-opened app would keep pushing the
            // next run further away and effectively never fire it.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Runs a sync straight away — on launch, on boot, after signing in. */
    fun now(context: Context) {
        val request = OneTimeWorkRequestBuilder<ConnectSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(IMMEDIATE_WORK)
        }
    }
}
