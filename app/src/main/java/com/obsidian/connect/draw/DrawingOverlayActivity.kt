package com.obsidian.connect.draw

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.widget.StrokeRasterizer
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetCaptionStore
import dagger.hilt.android.AndroidEntryPoint

/**
 * Shows the shared drawing over whatever is behind it.
 *
 * Opened by the blue corner light on the watch widget. Tapping anywhere closes
 * it — the same gesture that opened it, which is what was asked for and also
 * the only one that needs no explaining.
 *
 * Renders the PNG the app already wrote for this purpose rather than reading
 * Firestore, so it opens instantly from a home screen with no network wait.
 */
@AndroidEntryPoint
class DrawingOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seen, so the light goes out.
        WidgetCaptionStore.writeNewDrawing(this, false)
        WatchWidgetProvider.refreshAll(this)

        setContent { DrawingOverlay(onDismiss = { finish() }) }
    }
}

@Composable
private fun DrawingOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val bitmap = remember {
        BitmapFactory.decodeFile(StrokeRasterizer.file(context).path)?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // No ripple: this is a dismiss surface, not a button, and a ripple
            // spreading across the whole screen looks like a mistake.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(text = "Nothing drawn yet", color = Color.White)
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = "What the two of you drew",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
    }
}
