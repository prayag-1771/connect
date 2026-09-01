package com.obsidian.connect.core.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * The link between two people. Everything else in the app hangs off one of
 * these: moments, messages and strokes are all scoped to a pairing.
 *
 * Named [Pairing] rather than Pair to stay out of the way of [kotlin.Pair].
 */
data class Pairing(
    @DocumentId val id: String = "",
    val members: List<String> = emptyList(),
    val inviteCode: String = "",
    @ServerTimestamp val createdAt: Date? = null,
) {
    val isComplete: Boolean get() = members.size == 2

    /** The other person, or null while an invite is still outstanding. */
    fun partnerOf(uid: String): String? = members.firstOrNull { it != uid }
}
