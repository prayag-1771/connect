package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * A person using the app.
 *
 * [fcmToken] is what lets the other half of a pairing reach this device. It
 * rotates on reinstall and can rotate at any time, so it is refreshed on every
 * launch rather than written once at sign-up.
 */
data class User(
    @DocumentId val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val fcmToken: String? = null,
    val pairingId: String? = null,
    /**
     * When this person last had the app in front of them.
     *
     * A timestamp rather than a flag, so it expires on its own. A boolean would
     * need clearing, and the one moment nobody can rely on is a process getting
     * a chance to tidy up - a phone that runs out of battery mid-conversation
     * would otherwise be online forever.
     */
    val onlineAtMillis: Long = 0L,

    @ServerTimestamp val createdAt: Date? = null,
) {
    val isPaired: Boolean get() = pairingId != null

    /**
     * Whether they are looking at the app now.
     *
     * The window is comfortably wider than the heartbeat, so a beat delayed by
     * a slow network does not flicker somebody offline while they are reading.
     */
    fun isOnline(now: Long = System.currentTimeMillis()): Boolean =
        onlineAtMillis > 0 && now - onlineAtMillis < ONLINE_FOR_MS

    private companion object {
        const val ONLINE_FOR_MS = 90_000L
    }
}
