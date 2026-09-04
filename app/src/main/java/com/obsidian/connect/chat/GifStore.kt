package com.obsidian.connect.chat

import android.content.Context

/**
 * GIFs kept for later.
 *
 * Only the URL is stored, never the file. A GIF runs to megabytes and is
 * already sitting on a CDN that will serve it again - saving a copy would
 * spend real storage to avoid a request that costs nothing.
 *
 * On this phone rather than in Firestore, because a starred GIF is a personal
 * shortcut and not something the other person needs a copy of.
 */
object GifStore {

    private const val PREFS = "connect_gifs"
    private const val KEY_STARRED = "starred"

    /** Newest first, which is the order somebody expects their own list in. */
    private const val SEPARATOR = "\n"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun starred(context: Context): List<String> =
        prefs(context).getString(KEY_STARRED, "").orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }

    fun isStarred(context: Context, url: String): Boolean = url in starred(context)

    fun toggle(context: Context, url: String) {
        if (url.isBlank()) return

        val current = starred(context)
        val updated = if (url in current) current - url else listOf(url) + current

        prefs(context).edit()
            .putString(KEY_STARRED, updated.take(LIMIT).joinToString(SEPARATOR))
            .apply()
    }

    /**
     * A shortcut list stops being a shortcut past a certain size.
     *
     * Old entries drop off the end rather than being refused, so starring
     * always works and never needs explaining.
     */
    private const val LIMIT = 60
}
