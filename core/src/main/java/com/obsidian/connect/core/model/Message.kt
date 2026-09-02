package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class Message(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val text: String = "",

    /**
     * An attached photo, carried in the document like a moment's is.
     *
     * Same reasoning and the same ceiling: Cloud Storage needs a paid plan, and
     * a downscaled JPEG fits inside Firestore's 1MiB document limit.
     */
    val image: com.google.firebase.firestore.Blob? = null,

    /**
     * A voice note, carried the same way.
     *
     * Audio suits this far better than video does: a minute of AAC mono at
     * 32kbps is roughly 240KB, well inside Firestore's 1MiB document limit,
     * where ten seconds of even modest video would already exceed it.
     */
    val audio: com.google.firebase.firestore.Blob? = null,
    val audioDurationMs: Long = 0L,

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
) {
    val hasImage: Boolean get() = image != null

    val hasAudio: Boolean get() = audio != null

    val bytes: ByteArray? get() = image?.toBytes()

    val audioBytes: ByteArray? get() = audio?.toBytes()
}
