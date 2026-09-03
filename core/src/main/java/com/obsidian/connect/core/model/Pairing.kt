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

    /**
     * The chat's look, shared by both of you.
     *
     * On the pairing rather than on each device because the conversation is
     * one shared place - if you repaint it, you repaint it for both. Dark mode
     * stays personal: that is about your phone and your eyes, not about the
     * room you are both sitting in.
     *
     * A name rather than colours, so the palettes can be changed in a later
     * build without every old pairing carrying a stale set of hex values.
     */
    val chatTheme: String = "",
    @ServerTimestamp val createdAt: Date? = null,
) {
    val isComplete: Boolean get() = members.size == 2

    /** The other person, or null while an invite is still outstanding. */
    fun partnerOf(uid: String): String? = members.firstOrNull { it != uid }
}
