package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * A photo sent to the other person's home screen.
 *
 * [storagePath] is kept alongside [downloadUrl] because deleting the underlying
 * file needs the path, and a download URL cannot be turned back into one.
 */
data class Moment(
    @DocumentId val id: String = "",
    val pairingId: String = "",
    val senderId: String = "",
    val storagePath: String = "",
    val downloadUrl: String = "",
    val caption: String = "",
    @ServerTimestamp val createdAt: Date? = null,
)
