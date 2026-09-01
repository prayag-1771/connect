package com.obsidian.connect.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The manifest entry point for the widget. Declared in AndroidManifest.xml and
 * never constructed by our own code, which is why it needs a ProGuard keep rule.
 */
class MomentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MomentWidget()
}
