package com.obsidian.connect.core.model

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Something one person is deciding about, for the other person to weigh in on.
 *
 * A photo of two shirts, three cakes, a room — you put up the options, they
 * say which they like. The verdict belongs to whoever did *not* add it: a vote
 * on your own choice would be nothing but a note to self.
 */
/**
 * One time this card was talked about in the chat.
 *
 * A snapshot rather than a bare message id. The card has to be able to list
 * everything ever said about it without loading the conversation, and the
 * conversation only keeps its last two hundred messages anyway - so a
 * reference to something older would resolve to nothing.
 *
 * [messageId] is still kept, because tapping a reference jumps back to the
 * message itself when it is close enough to reach.
 */
data class ChoiceRef(
    val messageId: String = "",
    val byUid: String = "",
    val text: String = "",
    val atMillis: Long = 0L,
)

data class Choice(
    @DocumentId val id: String = "",
    val addedBy: String = "",
    val image: Blob? = null,

    /**
     * Whether this card is a photo, whether or not the bytes are still here.
     *
     * The picture is erased from the document once the other phone has filed
     * its own copy, so the card needs something more durable than [image] to
     * say that a photo belongs on it.
     */
    val photo: Boolean = false,
    val note: String = "",

    /**
     * Every message written about this card, oldest first.
     *
     * Kept on the card rather than derived by searching the chat, so the whole
     * history of a decision stays with the thing being decided - and survives
     * the messages themselves scrolling out of the loaded window.
     */
    val refs: List<ChoiceRef> = emptyList(),

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

    /** The bytes are still in the document. */
    val hasImage: Boolean get() = image != null

    /** Covers cards added before the flag existed, which still hold bytes. */
    @get:Exclude
    val isPhoto: Boolean get() = photo || image != null

    val isLiked: Boolean get() = verdict > 0

    val isDisliked: Boolean get() = verdict < 0

    val isUndecided: Boolean get() = verdict == 0

    /** Only the other person's opinion is worth asking for. */
    fun canBeJudgedBy(uid: String): Boolean = addedBy != uid
}
