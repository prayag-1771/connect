package com.obsidian.connect.core.model

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * A photo sent to the other person's home screen.
 *
 * The JPEG is carried in the document itself rather than in Cloud Storage,
 * which needs a paid plan. That works here only because these images are
 * already tiny by necessity: Glance hands widget bitmaps to the launcher over
 * a Binder transaction capped near 1MB, so the photo is downscaled hard before
 * it is ever sent. A Firestore document holds up to 1MiB, and a 720px JPEG
 * lands comfortably inside that.
 */
data class Moment(
    @DocumentId val id: String = "",
    val pairingId: String = "",
    val senderId: String = "",
    val image: Blob? = null,
    val caption: String = "",
    @ServerTimestamp val createdAt: Date? = null,
) {
    val bytes: ByteArray? get() = image?.toBytes()

    val sizeBytes: Int get() = image?.toBytes()?.size ?: 0
}
