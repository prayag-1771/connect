package com.obsidian.connect.core.model

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Something one person is deciding about, for the other person to weigh in on.
 *
 * A photo of two shirts, three cakes, a room — you put up the options, they
 * say which they like. The verdict belongs to whoever did *not* add it: a vote
 * on your own choice would be nothing but a note to self.
 */
data class Choice(
    @DocumentId val id: String = "",
    val addedBy: String = "",
    val image: Blob? = null,
    val note: String = "",

    /** 0 undecided, 1 liked, -1 disliked. */
    val verdict: Int = 0,
    val verdictBy: String = "",

    /**
     * Ordering key from the adding device's clock, for the same reason messages
     * carry one: a server timestamp is null locally until the write completes.
     */
    val createdAtMillis: Long = 0L,

    @ServerTimestamp val createdAt: Date? = null,
) {
    val bytes: ByteArray? get() = image?.toBytes()

    val isLiked: Boolean get() = verdict > 0

    val isDisliked: Boolean get() = verdict < 0

    val isUndecided: Boolean get() = verdict == 0

    /** Only the other person's opinion is worth asking for. */
    fun canBeJudgedBy(uid: String): Boolean = addedBy != uid
}
