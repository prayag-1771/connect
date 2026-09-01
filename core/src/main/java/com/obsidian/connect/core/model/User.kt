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
    @ServerTimestamp val createdAt: Date? = null,
) {
    val isPaired: Boolean get() = pairingId != null
}
