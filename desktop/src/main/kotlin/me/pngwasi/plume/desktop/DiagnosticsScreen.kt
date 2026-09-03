package me.pngwasi.plume.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.SectionLabel

/**
 * The log, in the app.
 *
 * A tray application launched from a desktop entry has no terminal, so when something stops working
 * there is nothing for the user to send. Making the log reachable without hunting for a file is the
 * difference between a useful report and "it crashed".
 */
@Composable
fun DiagnosticsScreen(onCopy: (String) -> Unit) {
    var lines by remember { mutableStateOf(PlumeLog.tail()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Text(
                text = "Plume writes what it does to ${PlumeLog.file.path}. Nothing here contains " +
                    "the text you are working on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }

        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                OutlinedButton(onClick = { lines = PlumeLog.tail() }) { Text("Refresh") }
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                OutlinedButton(
                    onClick = { onCopy(lines.joinToString("\n")) },
                    enabled = lines.isNotEmpty(),
                ) {
                    Text("Copy the log")
                }
            }
        }

        item { SectionLabel(if (lines.isEmpty()) "Nothing logged yet" else "Most recent last") }

        items(lines) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                // Log lines are long and must not be reflowed: a wrapped stack trace is unreadable.
                maxLines = 1,
                color = if (line.contains("ERROR")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
