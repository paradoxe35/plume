package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.Language
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.TranslateSettings
import me.pngwasi.plume.ui.components.PlumeFilterChip
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow

/**
 * Manages the translate targets: which languages are pinned to the picker, and whether the picker
 * appears at all.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Pinned languages appear as one-tap chips when you translate. Everything else stays reachable through search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        SettingsCard {
            SettingsRow(
                title = "Always translate into…",
                subtitle = settings.defaultTarget
                    ?.let { "${Languages.resolve(it).displayName()} · picker skipped" }
                    ?: "Off — Plume asks each time",
                icon = Icons.Outlined.PushPin,
                trailing = {
                    Checkbox(
                        checked = settings.defaultTarget != null,
                        onCheckedChange = { checked ->
                            onSetDefaultTarget(if (checked) favorites.firstOrNull() ?: "en" else null)
                        },
                    )
                },
            )
            RowDivider()
            SettingsRow(
                title = "Prompt and limits",
                subtitle = "Translation instructions, selection cap, timeout",
                icon = Icons.Outlined.Tune,
                showChevron = true,
                onClick = onOpenPrompt,
            )
        }

        if (settings.defaultTarget != null && favorites.isNotEmpty()) {
            SectionLabel("Default target")
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

        SectionLabel("Languages · ${favorites.size} pinned")

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            placeholder = { Text("Search languages") },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            singleLine = true,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(16.dp),
                )
                .background(MaterialTheme.colorScheme.surface),
        ) {
            itemsIndexed(results, key = { _, item -> item.code }) { index, language ->
                val pinned = favorites.any { it.equals(language.code, ignoreCase = true) }
                val name = language.displayName()
                val endonym = language.endonym()

                // Pinning re-sorts the list under the user's finger. Animating the placement turns
                // that jump into visible movement, so the row reads as travelling to the top rather
                // than vanishing.
                Column(modifier = Modifier.animateItem()) {
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
}

