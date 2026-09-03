package com.obsidian.connect.profile

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.obsidian.connect.widget.DailyResetReceiver
import com.obsidian.connect.widget.PlaceGuard
import com.obsidian.connect.widget.WatchWidgetProvider
import com.obsidian.connect.widget.WidgetSchedule

/**
 * The morning question, asked once.
 *
 * Only appears when the daily reset is switched on and today has not been
 * answered yet. Saying no is a real answer, not a postponement - it records
 * the day as decided, so the question does not follow you around the app.
 *
 * There is no password step here on purpose: the app lock, when it is on,
 * already stands between the launcher and this screen. Asking twice would be
 * theatre rather than security.
 */
@Composable
fun DailyArmPrompt() {
    val context = LocalContext.current

    var answered by remember { mutableStateOf(WidgetSchedule.isArmedForToday(context)) }
    var asking by remember {
        mutableStateOf(
            DailyResetReceiver.isEnabled(context) && !WidgetSchedule.isArmedForToday(context),
        )
    }

    // Asked before the yes, because saying yes with the guard on and no
    // permission would arm something that cannot do the one job it was given.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    if (!asking || answered) return

    val needsLocation = PlaceGuard.isEnabled(context) && !PlaceGuard.hasPermission(context)

    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Turn the face on for today?") },
        text = {
            Text(
                if (needsLocation) {
                    "It needs location permission first, so it can still switch " +
                        "itself off at the places you saved."
                } else {
                    "It goes off again at 6am tomorrow."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (needsLocation) {
                        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        return@TextButton
                    }
                    WidgetSchedule.armForToday(context)
                    WatchWidgetProvider.refreshAll(context)
                    answered = true
                    asking = false
                },
            ) {
                Text(if (needsLocation) "Allow location" else "Yes, today")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    // Recorded as decided, so it is not asked again until
                    // tomorrow - but the face stays off.
                    WidgetSchedule.setDisabled(context, true)
                    answered = true
                    asking = false
                },
            ) {
                Text("Leave it off")
            }
        },
    )
}
