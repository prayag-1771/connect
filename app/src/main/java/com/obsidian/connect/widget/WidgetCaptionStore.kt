package com.obsidian.connect.widget

import android.content.Context

/**
 * The caption and sender shared by both widget styles.
 *
 * Glance keeps its own state, but that state is only reachable through Glance's
 * APIs — the RemoteViews-based watch widget cannot read it. Rather than have
 * each widget hold its own copy and drift apart, both read this, alongside the
 * photo they already share through [WidgetImageStore].
 */
object WidgetCaptionStore {

    private const val PREFS = "connect_widget_text"
    private const val KEY_CAPTION = "caption"
    private const val KEY_SENDER = "sender"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun write(context: Context, caption: String, senderName: String) {
        prefs(context).edit()
            .putString(KEY_CAPTION, caption)
            .putString(KEY_SENDER, senderName)
            .apply()
    }

    fun read(context: Context): String? = prefs(context).getString(KEY_CAPTION, null)

    fun readSender(context: Context): String? = prefs(context).getString(KEY_SENDER, null)

    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
