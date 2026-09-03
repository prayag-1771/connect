package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
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
     * Whether this message was sent as a photo, regardless of whether the bytes
     * are still here.
     *
     * Needed because the bytes are deliberately temporary: once both phones
     * have written their own copy, [image] is erased from the document. Without
     * a flag that outlives it, a delivered photo would be indistinguishable
     * from an empty message, and the bubble would have nothing to look for on
     * disk.
     */
    val photo: Boolean = false,

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
     * A GIF, stored as a link rather than as data.
     *
     * GIFs run to megabytes, well past the 1MiB document limit, so the bytes
     * stay on the CDN they came from. The cost is that displaying one needs a
     * connection, unlike everything else here.
     */
    val gifUrl: String = "",

    /**
     * Who has starred this, by uid.
     *
     * A list rather than a flag because a star is a private opinion about a
     * shared object — you can keep something without deciding for the other
     * person that they have kept it too.
     */
    val starredBy: List<String> = emptyList(),

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
    /** The bytes are here, in this document, right now. */
    val hasImage: Boolean get() = image != null

    /**
     * This message is a photo — whether it still carries one or has already
     * been handed over to local storage.
     *
     * The [image] check covers messages sent before the flag existed, which
     * still hold their bytes.
     */
    @get:Exclude
    val isPhoto: Boolean get() = photo || image != null

    /** A function, not a getter, so Firestore does not treat it as a field. */
    fun isStarredBy(uid: String?): Boolean = uid != null && uid in starredBy

    val hasAudio: Boolean get() = audio != null

    val hasGif: Boolean get() = gifUrl.isNotBlank()

    val bytes: ByteArray? get() = image?.toBytes()

    val audioBytes: ByteArray? get() = audio?.toBytes()
}
