package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId

/**
 * How far through the conversation one person has got.
 *
 * One document per person, rather than delivery and read flags written onto
 * every message. Marking a hundred messages read would otherwise be a hundred
 * writes against a daily allowance of twenty thousand, every time the chat was
 * opened. Two numbers answer the same question for the whole history: anything
 * older than the watermark has been delivered, or seen.
 *
 * Compared against a message's own client timestamp, which is why messages
 * carry one — a server timestamp would be null on the sending device and every
 * message would look unread to whoever wrote it.
 */
data class Receipt(
    @DocumentId val uid: String = "",

    /** Newest message this person's device has actually received. */
    val deliveredAtMillis: Long = 0L,

    /** Newest message this person has had on screen. */
    val seenAtMillis: Long = 0L,

    /**
     * When this person was last seen typing.
     *
     * A timestamp rather than a flag, so it expires by itself. A boolean would
     * need clearing, and the one moment nobody can rely on is the app getting a
     * chance to clean up - somebody who starts typing and locks their phone
     * would otherwise appear to be typing forever.
     */
    val typingAtMillis: Long = 0L,
) {
    /** Typing is only news for a few seconds; after that it is a stale write. */
    fun isTyping(now: Long = System.currentTimeMillis()): Boolean =
        now - typingAtMillis in 0..TYPING_FOR_MS

    private companion object {
        const val TYPING_FOR_MS = 6_000L
    }
}

/** Where a message has got to, from the sender's point of view. */
enum class DeliveryStatus { Sent, Reached, Seen }

/**
 * Sent means it reached the database. Reached means their phone has it. Seen
 * means they had the conversation open with it on screen.
 */
fun deliveryStatusOf(message: Message, partner: Receipt?): DeliveryStatus = when {
    partner == null -> DeliveryStatus.Sent
    partner.seenAtMillis >= message.createdAtMillis -> DeliveryStatus.Seen
    partner.deliveredAtMillis >= message.createdAtMillis -> DeliveryStatus.Reached
    else -> DeliveryStatus.Sent
}
