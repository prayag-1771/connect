package com.obsidian.connect.profile

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obsidian.connect.widget.PlaceGuard
import com.obsidian.connect.widget.WatchWidgetProvider

/**
 * Places where the face turns itself off.
 *
 * Saved as a point and a radius taken from where the phone is standing when
 * you press the button, because that is the one thing it reliably knows. There
 * is no map and no address lookup - both would mean sending a location
 * somewhere, and the entire point of this is that it stays here.
 */
@Composable
fun PlacesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(PlaceGuard.isEnabled(context)) }
    var places by remember { mutableStateOf(PlaceGuard.places(context)) }
    var naming by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    fun saveHere() {
        val here = PlaceGuard.lastKnown(context)
        if (here == null) {
            problem = "No location fix yet. Open a maps app for a moment, then try again."
            return
        }
        PlaceGuard.add(
            context,
            PlaceGuard.Place(
                label = label.trim().ifBlank { "This place" },
                latitude = here.latitude,
                longitude = here.longitude,
            ),
        )
        places = PlaceGuard.places(context)
        label = ""
        naming = false
        problem = null
        WatchWidgetProvider.refreshAll(context)
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) saveHere() else problem = "Without location this cannot tell where you are."
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Off at certain places", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "The widget goes back to being a plain clock when " +
                            "you are somewhere on this list. Nothing here leaves your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        PlaceGuard.setEnabled(context, it)
                        WatchWidgetProvider.refreshAll(context)
                    },
                )
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))

                places.forEach { place ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = place.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                PlaceGuard.remove(context, place)
                                places = PlaceGuard.places(context)
                                WatchWidgetProvider.refreshAll(context)
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Remove ${place.label}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (naming) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Call it something") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (PlaceGuard.hasPermission(context)) {
                                    saveHere()
                                } else {
                                    permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save where I am") }

                        OutlinedButton(onClick = { naming = false }) { Text("Cancel") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { naming = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add this place") }
                }

                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
