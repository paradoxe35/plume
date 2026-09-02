package me.pngwasi.plume.process

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun WorkingPanel(note: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "$note…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * Shown when the result cannot be written back — the selection came from a read-only surface, so
 * copying is the only way to get the text out. The copy action is primary for exactly that reason.
 */
@Composable
fun ResultPanel(
    title: String,
    output: String,
    onCopy: () -> Unit,
    onReplace: (() -> Unit)?,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = output, style = MaterialTheme.typography.bodyLarge)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onReplace != null) {
                Button(onClick = onReplace, modifier = Modifier.weight(1f)) { Text("Replace") }
                OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy") }
            } else {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Copy")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            } else {
                Box {}
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
fun ErrorPanel(
    message: String,
    showSettings: Boolean,
    onRetry: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showSettings) {
                Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Settings")
                }
            }
            if (onRetry != null) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
            }
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}
