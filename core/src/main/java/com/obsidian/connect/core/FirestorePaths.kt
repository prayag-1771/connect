package com.obsidian.connect.core

/** Collection names, in one place so a typo can't silently create a new collection. */
object FirestorePaths {
    const val USERS = "users"
    const val PAIRINGS = "pairings"
    const val MOMENTS = "moments"

    // Subcollections of a pairing document.
    const val MESSAGES = "messages"
    const val STROKES = "strokes"
}
