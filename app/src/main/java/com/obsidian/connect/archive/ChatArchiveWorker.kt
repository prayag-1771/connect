package com.obsidian.connect.archive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MessageRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Moves the day that has just aged out into the archive file.
 *
 * Runs after midnight, which is the moment a day's worth of messages crosses
 * the four-day line together. Doing it on a schedule rather than on demand
 * means the download is instant and complete whenever it is asked for, instead
 * of being a long read at exactly the moment somebody wants their history.
 *
 * Nothing is deleted from Firestore. Ageing out of the chat and being removed
 * are different things, and quietly destroying a conversation to keep a screen
 * short would be a poor trade.
 */
@HiltWorker
class ChatArchiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val messageRepository: MessageRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val uid = authRepository.currentUid ?: return Result.success()
        val pairingId = userRepository.get(uid)?.pairingId ?: return Result.success()

        val cutoff = System.currentTimeMillis() - MessageRepository.VISIBLE_WINDOW_MS
        val from = ChatArchive.archivedUpTo(applicationContext)
        if (cutoff <= from) return Result.success()

        val aged = messageRepository.between(
            pairingId = pairingId,
            fromMillis = from,
            toMillis = cutoff,
        )
        if (aged.isEmpty()) return Result.success()

        // Both names, so the transcript reads as a conversation rather than as
        // a pair of identifiers.
        val partnerId = pairingRepository.get(pairingId)?.partnerOf(uid)
        val names = buildMap {
            put(uid, userRepository.get(uid)?.displayName.orEmpty().ifBlank { "You" })
            partnerId?.let {
                put(it, userRepository.get(it)?.displayName.orEmpty().ifBlank { "Them" })
            }
        }

        ChatArchive.append(applicationContext, aged, names)
        Result.success()
    }.getOrElse {
        // There is another run tomorrow, and the watermark did not move, so a
        // failure here loses nothing.
        Result.retry()
    }
}
