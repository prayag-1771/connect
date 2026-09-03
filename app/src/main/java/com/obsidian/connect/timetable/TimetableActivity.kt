package com.obsidian.connect.timetable

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidian.connect.core.model.Timetable
import com.obsidian.connect.core.model.TimetableEntry
import com.obsidian.connect.ui.theme.ConnectTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar

/**
 * Both weeks, side by side.
 *
 * The value of this is not either timetable on its own - it is seeing them
 * together, which is why a day shows both people's slots in one column rather
 * than putting each person on their own screen.
 */
@AndroidEntryPoint
class TimetableActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TimetableScreen(onBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, TimetableActivity::class.java))
        }
    }
}

@Composable
private fun TimetableScreen(
    onBack: () -> Unit,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val mine by viewModel.mine.collectAsStateWithLifecycle()
    val theirs by viewModel.theirs.collectAsStateWithLifecycle()
    val partnerName by viewModel.partnerName.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    // Opens on the day it actually is, which is the day anyone came here for.
    var day by remember { mutableStateOf(todayName()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::readFrom) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Timetable",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (mine?.isEmpty == false) {
                OutlinedButton(onClick = viewModel::clearMine) { Text("Clear mine") }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Timetable.DAYS.forEach { name ->
                        FilterChip(
                            selected = day == name,
                            onClick = { day = name },
                            label = { Text(name.take(3)) },
                        )
                    }
                }
            }

            val yours = mine?.entriesOn(day).orEmpty()
            val hers = theirs?.entriesOn(day).orEmpty()

            if (yours.isEmpty() && hers.isEmpty()) {
                item {
                    Text(
                        text = "Nothing on $day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            if (yours.isNotEmpty()) {
                item { Heading("You") }
                items(yours) { Slot(entry = it, mine = true) }
            }

            if (hers.isNotEmpty()) {
                item { Heading(partnerName) }
                items(hers) { Slot(entry = it, mine = false) }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }

        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    viewModel.dismissStatus()
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (mine?.isEmpty == false) {
                            "Replace my timetable"
                        } else {
                            "Read mine from a photo"
                        },
                    )
                }
            }

            Text(
                text = "Only the times are kept - the photo itself is not stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Whose slot it is carries in the colour of the bar rather than a label,
 * because on a day with eight entries the labels are the noise.
 */
@Composable
private fun Slot(entry: TimetableEntry, mine: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(46.dp)
                .background(
                    if (mine) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(text = entry.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(
                    timeRange(entry).takeIf { it.isNotBlank() },
                    entry.location.takeIf { it.isNotBlank() },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun timeRange(entry: TimetableEntry): String = when {
    entry.start.isBlank() -> ""
    entry.end.isBlank() -> entry.start
    else -> "${entry.start} - ${entry.end}"
}

private fun todayName(): String {
    // Calendar counts Sunday as 1; the list starts on Monday.
    val index = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return Timetable.DAYS.getOrElse((index + 5) % 7) { Timetable.DAYS.first() }
}
