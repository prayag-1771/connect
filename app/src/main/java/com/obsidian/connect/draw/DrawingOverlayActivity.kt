package com.obsidian.connect.draw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.geometry.Offset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Stroke
import dagger.hilt.android.AndroidEntryPoint

/**
 * Lays the shared drawing over whatever is already on screen.
 *
 * Nothing is dimmed and nothing is boxed — only the strokes are drawn, on a
 * fully transparent window, so the home screen underneath shows through
 * exactly as it was. Tapping anywhere dismisses it.
 *
 * Strokes are painted directly rather than shown as a pre-rendered image.
 * Coordinates are normalised to 0..1, so drawing them at the real window size
 * maps them correctly to any screen, where scaling a fixed-size bitmap up to
 * full screen would stretch the drawing out of shape.
 */
@AndroidEntryPoint
class DrawingOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent { DrawingOverlay(onDismiss = { finish() }) }
    }
}

@Composable
private fun DrawingOverlay(
    onDismiss: () -> Unit,
    viewModel: DrawingOverlayViewModel = hiltViewModel(),
) {
    val strokes by viewModel.strokes.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // No ripple: this is a dismiss surface, not a button, and a ripple
            // spreading over the whole screen looks like a fault.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (strokes.isEmpty()) {
            Text(text = "Nothing drawn yet", color = Color.White)
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                strokes.forEach(::drawStroke)
            }
        }
    }
}

private fun DrawScope.drawStroke(stroke: Stroke) {
    val points = stroke.points
    if (points.isEmpty()) return

    val colour = Color(stroke.color.toInt())

    // Widths were picked against the in-app canvas, which is roughly the size
    // of the screen — so they carry over here without rescaling.
    if (points.size == 1) {
        val only = points.first()
        drawCircle(
            color = colour,
            radius = stroke.width / 2f,
            center = Offset(only.x * size.width, only.y * size.height),
        )
        return
    }

    val path = Path().apply {
        moveTo(points.first().x * size.width, points.first().y * size.height)
        points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
    }

    drawPath(
        path = path,
        color = colour,
        style = DrawStroke(
            width = stroke.width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}
