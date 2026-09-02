package me.pngwasi.plume.process

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.Language
import me.pngwasi.plume.data.Languages

/**
 * Asks which language to translate into.
 *
 * Ordering is the whole design: recents first (the strongest predictor of the next pick), then
 * pinned favourites, then search over the full catalogue. In the common case the target is one tap
 * away and the keyboard never opens.
 */
@Composable
fun LanguagePickerPanel(
    favorites: List<String>,
    recents: List<String>,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()

    // Recents already carry the favourites the user actually reaches for; don't show them twice.
    val quickPicks = remember(favorites, recents) {
        (recents + favorites).distinctBy { it.lowercase() }.take(8).map(Languages::resolve)
    }
    val results = remember(query) { Languages.search(query) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Translate into", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onManage) { Text("Manage") }
        }

        if (!searching && quickPicks.isNotEmpty()) {
            LanguageChips(languages = quickPicks, onPick = onPick)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search languages") },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            singleLine = true,
        )

        if (results.isEmpty()) {
            Text(
                text = "No language matches \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(results, key = { it.code }) { language ->
                    LanguageRow(language = language, onClick = { onPick(language.code) })
                }
            }
        }
    }
}

@Composable
private fun LanguageChips(languages: List<Language>, onPick: (String) -> Unit) {
    // Two rows of four keeps the sheet a predictable height regardless of how many are pinned.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        languages.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { language ->
                    AssistChip(
                        onClick = { onPick(language.code) },
                        label = {
                            Text(
                                text = language.displayName(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(language: Language, onClick: () -> Unit) {
    val name = language.displayName()
    val endonym = language.endonym()

    Box(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 10.dp,
            ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!endonym.equals(name, ignoreCase = true)) {
                    Text(
                        text = endonym,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = language.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
