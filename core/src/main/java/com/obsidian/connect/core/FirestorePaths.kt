package com.obsidian.connect.core

/** Collection names, in one place so a typo can't silently create a new collection. */
object FirestorePaths {
    const val USERS = "users"
    const val PAIRINGS = "pairings"
    const val MOMENTS = "moments"

    // Subcollections of a pairing document.
    const val MESSAGES = "messages"
    const val STROKES = "strokes"

    /**
     * Reminders live under a pairing when shared and under a user when
     * private, so the same name is used against two different parents.
     */
    const val REMINDERS = "reminders"
    const val NUDGES = "nudges"

    /** One document per person, holding delivered and seen watermarks. */
    const val RECEIPTS = "receipts"
}
