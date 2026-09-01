package com.obsidian.connect.sync

import android.content.Context
import com.obsidian.connect.core.data.AuthRepository
import com.obsidian.connect.core.data.MomentRepository
import com.obsidian.connect.core.data.PairingRepository
import com.obsidian.connect.core.data.UserRepository
import com.obsidian.connect.core.model.Moment
import com.obsidian.connect.widget.MomentWidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the widget current while the app is actually open.
 *
 * The periodic worker has a fifteen-minute floor, which is a long time to
 * stare at a stale photo with the app right there in front of you. A Firestore
 * listener costs nothing extra here — the app is already running — and closes
 * that gap to roughly the round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WidgetLiveUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val pairingRepository: PairingRepository,
    private val momentRepository: MomentRepository,
    private val syncState: SyncState,
) {

    /**
     * Collect for as long as the UI is alive. Never completes on its own.
     */
    suspend fun run() {
        latestFromPartner().collect { (moment, partnerName) ->
            if (moment == null || moment.id == syncState.lastMomentId) return@collect

            val bytes = moment.bytes ?: return@collect
            MomentWidgetUpdater.show(
                context = context,
                jpeg = bytes,
                caption = moment.caption,
                senderName = partnerName,
            )
            syncState.lastMomentId = moment.id
        }
    }

    private fun latestFromPartner(): Flow<Pair<Moment?, String>> =
        authRepository.uidFlow
            .flatMapLatest { uid ->
                if (uid == null) flowOf(null to uid) else userRepository.observe(uid).map { it to uid }
            }
            .map { (user, uid) -> user?.pairingId to uid }
            .distinctUntilChanged()
            .flatMapLatest { (pairingId, uid) ->
                if (pairingId == null || uid == null) {
                    flowOf(null as Moment? to "")
                } else {
                    partnerFlow(pairingId, uid).flatMapLatest { partnerId ->
                        if (partnerId == null) {
                            flowOf(null as Moment? to "")
                        } else {
                            combine(
                                momentRepository.observeLatestFrom(pairingId, partnerId),
                                userRepository.observe(partnerId).map { it?.displayName.orEmpty() },
                            ) { moment, name -> moment to name }
                        }
                    }
                }
            }

    private fun partnerFlow(pairingId: String, uid: String): Flow<String?> =
        pairingRepository.observe(pairingId)
            .map { it?.partnerOf(uid) }
            .distinctUntilChanged()
}
