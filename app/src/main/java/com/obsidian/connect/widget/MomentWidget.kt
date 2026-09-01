package com.obsidian.connect.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.obsidian.connect.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home screen widget showing the most recent photo from the other person.
 */
class MomentWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Decoded before provideContent because provideContent never returns,
        // and file I/O has no business running inside a composition.
        val bitmap = withContext(Dispatchers.IO) {
            WidgetImageStore.decode(context, MAX_DIMENSION_PX)
        }

        provideContent {
            val state = currentState<Preferences>()
            WidgetContent(
                bitmap = bitmap,
                caption = state[WidgetKeys.CAPTION].orEmpty(),
                senderName = state[WidgetKeys.SENDER_NAME].orEmpty(),
            )
        }
    }

    @Composable
    private fun WidgetContent(bitmap: Bitmap?, caption: String, senderName: String) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(BACKGROUND)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                EmptyState()
            } else {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = caption.ifEmpty { "Photo from $senderName" },
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
                if (caption.isNotEmpty() || senderName.isNotEmpty()) {
                    Caption(caption = caption, senderName = senderName)
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = "Nothing yet",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = "Tap to send the first one",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp),
            )
        }
    }

    /**
     * Sits on a translucent strip rather than directly on the photo. Text drawn
     * straight onto an arbitrary image is unreadable roughly half the time,
     * and a widget has no way to know what it's been handed.
     */
    @Composable
    private fun Caption(caption: String, senderName: String) {
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(modifier = GlanceModifier.background(SCRIM).padding(8.dp)) {
                if (senderName.isNotEmpty()) {
                    Text(
                        text = senderName,
                        style = TextStyle(
                            color = ColorProvider(MUTED),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                if (caption.isNotEmpty()) {
                    Text(
                        text = caption,
                        maxLines = 2,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp),
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * Caps the bitmap handed across the Binder to the launcher. 720px on the
         * long edge covers the largest widget on a high-density screen and still
         * leaves plenty of headroom under the transaction limit.
         */
        const val MAX_DIMENSION_PX = 720

        val BACKGROUND = Color(0xFF16161A)
        val SCRIM = Color(0xB3000000)
        val MUTED = Color(0xFFB4B4BE)
    }
}
