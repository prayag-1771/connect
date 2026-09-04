package com.obsidian.connect.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether there is something unread, for the app itself to show.
 *
 * The widget already has this as a stored flag, but a SharedPreferences value
 * is not something Compose can watch - a tab badge has to change the moment a
 * message lands, not the next time something else happens to recompose.
 *
 * Kept in step with the widget flag by the same watcher, so the dot on the tab
 * and the dot on the face never disagree.
 */
object UnreadState {
    var hasUnread by mutableStateOf(false)
        private set

    fun set(unread: Boolean) {
        hasUnread = unread
    }
}
