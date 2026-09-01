package com.obsidian.connect.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way a new photo reaches the widget.
 */
object MomentWidgetUpdater {

    /**
     * Stores the photo and redraws every placed instance of the widget.
     *
     * The image is written to disk even when no widget is currently on a home
     * screen. Someone can add the widget days later, and it should come up
     * holding the last photo rather than an empty box.
     */
    suspend fun show(
        context: Context,
        jpeg: ByteArray,
        caption: String,
        senderName: String,
    ) {
        withContext(Dispatchers.IO) { WidgetImageStore.write(context, jpeg) }

        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(MomentWidget::class.java).forEach { glanceId ->
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[WidgetKeys.CAPTION] = caption
                    this[WidgetKeys.SENDER_NAME] = senderName
                    this[WidgetKeys.UPDATED_AT] = System.currentTimeMillis()
                }
            }
        }

        MomentWidget().updateAll(context)
    }

    /** Redraws from whatever is already on disk, without changing it. */
    suspend fun refresh(context: Context) = MomentWidget().updateAll(context)
}
