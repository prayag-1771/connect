package com.obsidian.connect.widget

import android.content.Context

/**
 * The caption and sender shared by both widget styles.
 *
 * Glance keeps its own state, but that state is only reachable through Glance's
 * APIs — the RemoteViews-based watch widget cannot read it. Rather than have
 * each widget hold its own copy and drift apart, both read this, alongside the
 * photo they already share through [WidgetImageStore].
 *
 * Also carries the unread flag behind the watch face's dot.
 */
object WidgetCaptionStore {

    private const val PREFS = "connect_widget_text"
    private const val KEY_CAPTION = "caption"
    private const val KEY_SENDER = "sender"
    private const val KEY_UNREAD = "unread"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun write(context: Context, caption: String, senderName: String) {
        prefs(context).edit()
            .putString(KEY_CAPTION, caption)
            .putString(KEY_SENDER, senderName)
            .apply()
    }

    fun read(context: Context): String? = prefs(context).getString(KEY_CAPTION, null)

    /**
     * Whether the other person has said something this phone has not seen.
     *
     * Kept here rather than derived by the widget, because a widget cannot
     * query Firestore — it renders whatever the app last left for it.
     */
    fun writeUnread(context: Context, unread: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNREAD, unread).apply()
    }

    fun hasUnread(context: Context): Boolean = prefs(context).getBoolean(KEY_UNREAD, false)

    fun readSender(context: Context): String? = prefs(context).getString(KEY_SENDER, null)

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
