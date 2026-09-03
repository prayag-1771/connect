package com.obsidian.connect.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A message the app has been asked to go and show.
 *
 * Set from outside the conversation - the starred list, which is its own
 * screen - and read by the chat once it is on screen. A plain holder rather
 * than an activity result, because two things have to react to it: the tab bar
 * has to switch to the chat, and the chat has to scroll and highlight.
 *
 * Cleared by whoever consumes it, so returning to the chat later does not jump
 * somewhere for no reason.
 */
object ChatFocus {
    var pendingMessageId by mutableStateOf<String?>(null)
        private set

    fun request(messageId: String) {
        pendingMessageId = messageId
    }

    fun consume() {
        pendingMessageId = null
    }
}
