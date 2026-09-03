package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.validateCustomProviderName

/** A handful of gateways people actually add, so the common case is one tap instead of two URLs. */
private data class Preset(
    val name: String,
    val baseUrl: String,
    val model: String,
    /** Local runtimes accept anything or nothing, so they start with auth switched off. */
    val authRequired: Boolean = true,
)

private val Presets = listOf(
    Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    Preset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
    Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    Preset("Together", "https://api.together.xyz/v1", ""),
    Preset("Ollama", "http://localhost:11434/v1", "", authRequired = false),
    Preset("LM Studio", "http://localhost:1234/v1", "", authRequired = false),
)

@Composable
fun AddProviderDialog(
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, ProviderConfig) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf<Preset?>(null) }

    val error = remember(name, existingNames) {
        if (name.isBlank()) null else validateCustomProviderName(name, existingNames)
    }
    val canCreate = name.isNotBlank() && error == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Any service that speaks the OpenAI chat-completions format. Start from a preset or enter your own endpoint on the next screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Presets.take(3).forEach { option -> PresetChip(option, preset) { preset = it; name = it.name } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Presets.drop(3).forEach { option -> PresetChip(option, preset) { preset = it; name = it.name } }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; if (preset?.name != it) preset = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = {
                    val chosen = preset
                    onCreate(
                        name.trim(),
                        ProviderConfig(
                            label = name.trim(),
                            kind = ProviderKind.OpenAiCompatible,
                            baseUrl = chosen?.baseUrl.orEmpty(),
                            model = chosen?.model.orEmpty(),
                            temperature = 1f,
                            isCustom = true,
                            // A local address means a runtime that wants no key; anything else is
                            // assumed to need one until the user says otherwise.
                            authRequired = chosen?.authRequired
                                ?: !me.pngwasi.plume.data.isLocalEndpoint(chosen?.baseUrl.orEmpty()),
                        ),
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PresetChip(option: Preset, selected: Preset?, onSelect: (Preset) -> Unit) {
    FilterChip(
        selected = selected?.name == option.name,
        onClick = { onSelect(option) },
        label = { Text(option.name, style = MaterialTheme.typography.labelSmall) },
    )
}
