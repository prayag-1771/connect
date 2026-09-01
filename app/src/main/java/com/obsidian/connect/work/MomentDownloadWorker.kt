package com.obsidian.connect.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import com.obsidian.connect.widget.MomentWidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Fetches a photo from Storage and hands it to the widget.
 *
 * This is deliberately not done inside onMessageReceived. That callback gets
 * roughly twenty seconds before the system is entitled to kill the process,
 * which is not enough to guarantee an image download on a weak connection.
 * WorkManager outlives the callback and retries on its own.
 */
@HiltWorker
class MomentDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val storage: FirebaseStorage,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val storagePath = inputData.getString(KEY_STORAGE_PATH)
            ?: return@withContext Result.failure()

        val bytes = runCatching {
            storage.reference.child(storagePath).getBytes(MAX_DOWNLOAD_BYTES).await()
        }.getOrElse {
            // Retry covers the ordinary case of a flaky connection. Past the
            // attempt limit it's more likely the file is gone than that the
            // network is bad, and retrying forever would just burn battery.
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure()
        }

        MomentWidgetUpdater.show(
            context = applicationContext,
            jpeg = bytes,
            caption = inputData.getString(KEY_CAPTION).orEmpty(),
            senderName = inputData.getString(KEY_SENDER_NAME).orEmpty(),
        )

        Result.success()
    }

    companion object {
        private const val KEY_STORAGE_PATH = "storage_path"
        private const val KEY_CAPTION = "caption"
        private const val KEY_SENDER_NAME = "sender_name"

        private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
        private const val MAX_ATTEMPTS = 4

        private const val WORK_NAME = "moment_download"

        /**
         * Replaces any download already in flight. If two photos land in quick
         * succession only the newer one matters — the widget shows one image,
         * and finishing the older download would briefly display a photo that
         * has already been superseded.
         */
        fun enqueue(
            context: Context,
            storagePath: String,
            caption: String,
            senderName: String,
        ) {
            val request = OneTimeWorkRequestBuilder<MomentDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_STORAGE_PATH, storagePath)
                        .putString(KEY_CAPTION, caption)
                        .putString(KEY_SENDER_NAME, senderName)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
