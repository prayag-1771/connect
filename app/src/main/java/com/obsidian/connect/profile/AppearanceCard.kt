package com.obsidian.connect.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obsidian.connect.ui.theme.AppearanceStore
import com.obsidian.connect.ui.theme.ChatTheme
import com.obsidian.connect.ui.theme.ThemeMode

/**
 * How the app looks.
 *
 * Two settings that deliberately behave differently. Dark mode is yours alone
 * - it is about your screen, and often about the time of day where you are.
 * The chat's palette is shared, because the conversation is one place you are
 * both looking at; repainting it repaints it for both.
 */
@Composable
fun AppearanceCard(
    chatTheme: ChatTheme,
    onChatTheme: (ChatTheme) -> Unit,
    canChangeChat: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Appearance", style = MaterialTheme.typography.titleMedium)

            Text(
                text = "Dark mode — just this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = AppearanceStore.themeMode == mode,
                        onClick = { AppearanceStore.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                    ) {
                        Text(mode.name)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = if (canChangeChat) {
                    "Chat colours — both of you see this"
                } else {
                    "Chat colours — available once you are paired"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatTheme.entries.forEach { theme ->
                    Swatch(
                        theme = theme,
                        selected = theme == chatTheme,
                        enabled = canChangeChat,
                        onClick = { onChatTheme(theme) },
                    )
                }
            }
        }
    }
}

/**
 * A theme shown as the thing it does rather than as its name.
 *
 * Two stacked halves, the colours the two of you would actually appear in.
 * A row of words would make someone try each one to find out what it looked
 * like.
 */
@Composable
private fun Swatch(
    theme: ChatTheme,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Always drawn in the light palette, whatever the phone is set to, so the
    // five sit side by side as a comparison rather than shifting together.
    val colors = theme.colors(dark = false)
    val mine = colors?.mine ?: MaterialTheme.colorScheme.primaryContainer
    val theirs = colors?.theirs ?: MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Half(mine)
            Half(theirs)
        }
        Text(
            text = theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Half(color: Color) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(color),
    )
}
