package com.obsidian.connect.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.obsidian.connect.archive.ChatArchiveWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PERIODIC_WORK = "connect_periodic_sync"
    private const val IMMEDIATE_WORK = "connect_immediate_sync"
    private const val ARCHIVE_WORK = "connect_chat_archive"
    private const val ARCHIVE_NOW_WORK = "connect_chat_archive_now"

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

    /**
     * The nightly archive run, just after midnight.
     *
     * A daily period with an initial delay to the next midnight, rather than an
     * alarm: this is not time-critical to the minute, and WorkManager will
     * catch up a run the phone slept through, which an alarm would simply miss.
     */
    fun scheduleArchive(context: Context) {
        val now = Calendar.getInstance()
        val midnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val request = PeriodicWorkRequestBuilder<ChatArchiveWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(
                midnight.timeInMillis - now.timeInMillis,
                TimeUnit.MILLISECONDS,
            )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ARCHIVE_WORK,
            // KEEP, so opening the app does not push tonight's run to tomorrow.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Used when somebody asks for the archive before tonight's run. */
    fun archiveNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ChatArchiveWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ARCHIVE_NOW_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(IMMEDIATE_WORK)
            cancelUniqueWork(ARCHIVE_WORK)
        }
    }
}
