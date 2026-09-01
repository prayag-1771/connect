package com.obsidian.connect.widget

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Text shown alongside the photo. The image itself is not in here — it lives on
 * disk, because Glance state is serialised into the widget's stored preferences
 * and is the wrong place for hundreds of kilobytes of JPEG.
 */
object WidgetKeys {
    val CAPTION = stringPreferencesKey("caption")
    val SENDER_NAME = stringPreferencesKey("sender_name")
    val UPDATED_AT = longPreferencesKey("updated_at")
}
