package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class Message(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val text: String = "",

    /**
     * Ordering key, set from the sending device's clock.
     *
     * Same reason as [Stroke]: a server timestamp is null on the writing device
     * until the round trip finishes, so a query ordered by it would not match
     * the message you just sent — it would vanish from your own conversation
     * for as long as the network took.
     */
    val createdAtMillis: Long = 0L,

    @ServerTimestamp val createdAt: Date? = null,
)
