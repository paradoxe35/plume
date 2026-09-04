package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.Action
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.BuiltInProviders
import me.pngwasi.plume.data.isProviderReady
import me.pngwasi.plume.ui.components.PlumeFilterChip
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.components.rememberTrackedScrollState
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * Provider list plus per-action routing.
 *
 * Most people want one provider for everything, so the default is stated first and each action
 * simply follows it. The per-action rows exist for the case that motivates them: a cheap fast model
 * for corrections, a stronger one for translation.
 */
@Composable
fun ProvidersScreen(
        settings: AppSettings,
        keyedProviders: Set<String>,
        onEdit: (String) -> Unit,
        onSetActionProvider: (Action, String?) -> Unit,
        onAddCustom: () -> Unit,
        onSetProviderMentions: (Boolean) -> Unit = {},
) {
    Column(
            modifier =
                    Modifier.fillMaxSize()
                            .verticalScroll(rememberTrackedScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp),
    ) {
        Text(
                text =
                        "Keys are encrypted on this device and never leave it except to reach the provider you chose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        val ids = settings.providerIds()
        val builtIn = ids.filter { BuiltInProviders.isBuiltIn(it) }
        val custom = ids.filterNot { BuiltInProviders.isBuiltIn(it) }

        SectionLabel("Built in")
        SettingsCard {
            builtIn.forEachIndexed { index, id ->
                ProviderRow(id, settings, keyedProviders, onEdit)
                if (index != builtIn.lastIndex) RowDivider()
            }
        }

        if (custom.isNotEmpty()) {
            SectionLabel("Custom")
            SettingsCard {
                custom.forEachIndexed { index, id ->
                    ProviderRow(id, settings, keyedProviders, onEdit)
                    if (index != custom.lastIndex) RowDivider()
                }
            }
        }

        SettingsCard(modifier = Modifier.padding(top = 12.dp)) {
            SettingsRow(
                    title = "Add a provider",
                    subtitle =
                            "Any OpenAI-compatible endpoint: Groq, Mistral, Together, Ollama, your own gateway",
                    icon = PlumeIcons.Add,
                    showChevron = true,
                    onClick = onAddCustom,
            )
        }

        SectionLabel("Which provider runs what")
        Text(
                text =
                        "Both actions use ${settings.labelOf(settings.defaultProvider)} unless you point them somewhere else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )

        ActionRouting(
                action = Action.Revise,
                title = "Revise",
                settings = settings,
                onSelect = onSetActionProvider,
        )
        ActionRouting(
                action = Action.Translate,
                title = "Translate",
                settings = settings,
                onSelect = onSetActionProvider,
        )

        SectionLabel("Overriding for one request")
        SettingsCard {
            SettingsRow(
                    title = "Allow @provider in the text",
                    subtitle =
                            "Start with @${settings.providerIds().firstOrNull() ?: "openai"} to " +
                                    "send just that request elsewhere. A name that is not a provider is left alone.",
                    icon = PlumeIcons.SwapHoriz,
                    trailing = {
                        Switch(
                                checked = settings.providerMentions,
                                onCheckedChange = onSetProviderMentions,
                        )
                    },
            )
        }
    }
}

@Composable
private fun ActionRouting(
        action: Action,
        title: String,
        settings: AppSettings,
        onSelect: (Action, String?) -> Unit,
) {
    val override = settings.overrideFor(action)

    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Icon(
                    imageVector =
                            if (action == Action.Revise) {
                                PlumeIcons.AutoFixHigh
                            } else {
                                PlumeIcons.Translate
                            },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp).then(Modifier),
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
        }

        // Chips wrap into rows of three so a long provider list stays a fixed, predictable height.
        val options: List<Pair<String, String?>> = buildList {
            add("Default (${settings.labelOf(settings.defaultProvider)})" to null)
            settings.providerIds().forEach { id -> add(settings.labelOf(id) to id) }
        }

        options.chunked(2).forEach { row ->
            Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, id) ->
                    PlumeFilterChip(
                            selected = override == id,
                            onClick = { onSelect(action, id) },
                            label = label,
                            modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProviderRow(
        id: String,
        settings: AppSettings,
        keyedProviders: Set<String>,
        onEdit: (String) -> Unit,
) {
    val config = settings.providers[id] ?: return
    val isDefault = settings.defaultProvider == id
    val ready = settings.isProviderReady(id, keyedProviders)

    val uses = buildList {
        if (isDefault) add("default")
        if (settings.overrideFor(Action.Revise) == id) add("revise")
        if (settings.overrideFor(Action.Translate) == id) add("translate")
    }

    SettingsRow(
            title = config.label.ifBlank { id },
            subtitle =
                    buildString {
                        append(config.model.ifBlank { "no model" })
                        if (uses.isNotEmpty()) append(" · ${uses.joinToString(", ")}")
                    },
            icon = if (isDefault) PlumeIcons.CheckCircle else PlumeIcons.RadioButtonUnchecked,
            trailing = { ReadyPill(ready) },
            showChevron = true,
            onClick = { onEdit(id) },
    )
}
