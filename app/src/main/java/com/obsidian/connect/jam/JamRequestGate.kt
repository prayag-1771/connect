package com.obsidian.connect.jam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the jam chat invitation should be put in front of somebody.
 *
 * The request itself lives in Firestore; this is only about when to ask. Two
 * things can raise it - the app noticing a pending request while it is open,
 * and the white dot on the widget being tapped - and both want the same dialog,
 * so the decision is kept in one place rather than duplicated in each.
 *
 * Declining lowers it without touching the room. Turning an invitation down is
 * not the same as ending the conversation the other person is sitting in.
 */
object JamRequestGate {

    var asking by mutableStateOf(false)
        private set

    /**
     * The request already answered, so a decline is not asked again until the
     * other person actually asks again.
     */
    private var declined: Long = 0L

    fun raise() {
        asking = true
    }

    /** Raised only if this is a newer request than the one already turned down. */
    fun raiseIfNew(requestedAtMillis: Long) {
        if (requestedAtMillis > declined) asking = true
    }

    fun accept() {
        asking = false
    }

    fun decline(requestedAtMillis: Long) {
        declined = requestedAtMillis
        asking = false
    }
}
