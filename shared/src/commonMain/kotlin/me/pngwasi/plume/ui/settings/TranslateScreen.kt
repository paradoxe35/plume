package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.Language
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.TranslateSettings
import me.pngwasi.plume.ui.components.PlumeFilterChip
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * Manages the translate targets: which languages are pinned to the picker, and whether the picker
 * appears at all.
 *
 * The whole screen is one `LazyColumn` rather than a fixed header above a scrolling list. The
 * earlier shape nested a scrollable list inside a column that could not scroll, so the soft
 * keyboard covered the very rows the search field was filtering — you could type a filter and never
 * see the result, with nothing above able to scroll out of the way. One scroll container, with the
 * host applying `imePadding`, lifts the list above the keyboard instead.
 */
@Composable
fun TranslateScreen(
    settings: TranslateSettings,
    onToggleFavorite: (String) -> Unit,
    onSetDefaultTarget: (String?) -> Unit,
    onOpenPrompt: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val favorites = settings.favorites

    // Pinned languages float to the top in the order the user pinned them; everything else keeps
    // catalogue order, since sortedWith is stable.
    val results = remember(query, favorites) {
        val pinOrder = favorites.mapIndexed { index, code -> code.lowercase() to index }.toMap()
        Languages.search(query).sortedWith(
            compareBy<Language> { pinOrder[it.code.lowercase()] ?: Int.MAX_VALUE },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Text(
                text = "Pinned languages appear as one-tap chips when you translate. Everything " +
                    "else stays reachable through search.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }

        item {
            SettingsCard {
                SettingsRow(
                    title = "Always translate into…",
                    subtitle = settings.defaultTarget
                        ?.let { "${Languages.resolve(it).displayName()} · picker skipped" }
                        ?: "Off — Plume asks each time",
                    icon = PlumeIcons.PushPin,
                    trailing = {
                        Checkbox(
                            checked = settings.defaultTarget != null,
                            onCheckedChange = { checked ->
                                onSetDefaultTarget(
                                    if (checked) favorites.firstOrNull() ?: "en" else null,
                                )
                            },
                        )
                    },
                )
                RowDivider()
                SettingsRow(
                    title = "Prompt and limits",
                    subtitle = "Translation instructions, selection cap, timeout",
                    icon = PlumeIcons.Tune,
                    showChevron = true,
                    onClick = onOpenPrompt,
                )
            }
        }

        if (settings.defaultTarget != null && favorites.isNotEmpty()) {
            item { SectionLabel("Default target") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    favorites.take(4).forEach { code ->
                        PlumeFilterChip(
                            selected = settings.defaultTarget.equals(code, ignoreCase = true),
                            onClick = { onSetDefaultTarget(code) },
                            label = Languages.resolve(code).displayName(),
                        )
                    }
                }
            }
        }

        item { SectionLabel("Languages · ${favorites.size} pinned") }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                placeholder = { Text("Search languages") },
                leadingIcon = {
                    Icon(
                        PlumeIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                singleLine = true,
            )
        }

        if (results.isEmpty()) {
            item {
                Text(
                    text = "No language matches \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        itemsIndexed(results, key = { _, item -> item.code }) { index, language ->
            val pinned = favorites.any { it.equals(language.code, ignoreCase = true) }
            val name = language.displayName()
            val endonym = language.endonym()

            // The rows still read as one card, but the list is the scroller now, so the corners and
            // border are drawn per row rather than by a container wrapped around them.
            val shape = when {
                results.size == 1 -> RoundedCornerShape(16.dp)
                index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                index == results.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                else -> RoundedCornerShape(0.dp)
            }

            // Pinning re-sorts the list under the user's finger. Animating the placement turns that
            // jump into visible movement, so the row reads as travelling to the top.
            Column(
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
            ) {
                SettingsRow(
                    title = name,
                    subtitle = if (endonym.equals(name, ignoreCase = true)) {
                        language.code
                    } else {
                        "$endonym · ${language.code}"
                    },
                    trailing = {
                        Checkbox(
                            checked = pinned,
                            onCheckedChange = { onToggleFavorite(language.code) },
                        )
                    },
                    onClick = { onToggleFavorite(language.code) },
                )
                if (index != results.lastIndex) RowDivider()
            }
        }
    }
}
