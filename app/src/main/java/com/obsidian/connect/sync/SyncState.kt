package com.obsidian.connect.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what this device has already seen.
 *
 * Without a server pushing updates, the sync runs repeatedly over the same
 * data. This is what stops it from re-announcing a nudge every fifteen minutes
 * or redrawing the widget with a photo that is already on it.
 *
 * Plain SharedPreferences rather than DataStore: these are two scalar values
 * read once per sync from a background worker, and the synchronous API avoids
 * dragging coroutine scoping into the boot path.
 */
@Singleton
class SyncState @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("connect_sync", Context.MODE_PRIVATE)

    /** Id of the photo currently on the widget, so identical syncs are cheap. */
    var lastMomentId: String?
        get() = prefs.getString(KEY_LAST_MOMENT, null)
        set(value) = prefs.edit().putString(KEY_LAST_MOMENT, value).apply()

    /**
     * Watermark for nudge notifications.
     *
     * Defaults to now rather than the epoch, so a fresh install does not open
     * with a burst of notifications for every nudge ever sent.
     */
    var lastNudgeAt: Date
        get() = Date(prefs.getLong(KEY_LAST_NUDGE, System.currentTimeMillis()))
        set(value) = prefs.edit().putLong(KEY_LAST_NUDGE, value.time).apply()

    /**
     * Timestamp of the newest message this phone has seen.
     *
     * Drives the unread dot on the watch face. Stored per device rather than
     * in Firestore because "unread" here means unread *on this phone*, which
     * is what a widget on this phone should reflect.
     */
    var lastReadMessageAt: Long
        get() = prefs.getLong(KEY_LAST_READ_MESSAGE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_READ_MESSAGE, value).apply()

    /** Newest stroke this phone has already shown, so the blue light is not re-lit. */
    var lastSeenStrokeAt: Long
        get() = prefs.getLong(KEY_LAST_STROKE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_STROKE, value).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_LAST_MOMENT = "last_moment_id"
        const val KEY_LAST_NUDGE = "last_nudge_at"
        const val KEY_LAST_READ_MESSAGE = "last_read_message_at"
        const val KEY_LAST_STROKE = "last_seen_stroke_at"
    }
}
