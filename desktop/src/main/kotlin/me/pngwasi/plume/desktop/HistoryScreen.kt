package me.pngwasi.plume.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard

/**
 * What Plume changed, and what it changed it from.
 *
 * This is the desktop's answer to undo. The paste lands in another application, which Plume cannot
 * reach back into, so it cannot take the change back — but it can always show the original so the
 * user can. Nothing here is persisted: it is a safety net for the current session, not a log of
 * everything the user has ever written.
 */
@Composable
fun HistoryScreen(
    history: List<HistoryEntry>,
    onCopy: (String) -> Unit,
) {
    if (history.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                text = "Nothing yet. Once a shortcut changes some text, the original will be kept " +
                    "here for the rest of the session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { SectionLabel("This session, last $MAX_HISTORY kept") }

        items(history) { entry ->
            SettingsCard(modifier = Modifier.padding(bottom = 10.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = entry.original,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = entry.result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = { onCopy(entry.original) }) {
                        Text("Copy the original")
                    }
                }
            }
        }
    }
}
