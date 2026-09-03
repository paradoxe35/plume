package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.pngwasi.plume.data.BuiltInProviders
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.ReasoningDialect
import me.pngwasi.plume.data.ReasoningMode
import me.pngwasi.plume.data.isLocalEndpoint
import me.pngwasi.plume.ai.Reasoning
import me.pngwasi.plume.ai.ReasoningStyle
import me.pngwasi.plume.data.validateProvider
import me.pngwasi.plume.ui.components.PlumeFilterChip
import me.pngwasi.plume.ui.components.PlumeSlider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import java.util.Locale

/**
 * Edits one provider: credentials, endpoint, model and sampling, plus a live connection test.
 *
 * Required fields are marked and validated as the user types. Nothing is blocked from being saved —
 * a half-filled provider is a normal intermediate state — but the actions that need a complete
 * provider (test, set as default) stay disabled until it is.
 */
@Composable
fun ProviderEditScreen(
    providerId: String,
    initial: ProviderConfig,
    initialApiKey: String,
    isDefault: Boolean,
    probe: ProbeState,
    models: ModelsState,
    onSave: (ProviderConfig, String) -> Unit,
    onSetDefault: () -> Unit,
    onTest: () -> Unit,
    onLoadModels: (ProviderConfig, String) -> Unit,
    onDelete: (() -> Unit)?,
    onClearProbe: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label.ifBlank { providerId }) }
    var kind by remember { mutableStateOf(initial.kind) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var temperature by remember { mutableFloatStateOf(initial.temperature) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var revealKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reasoning by remember { mutableStateOf(initial.reasoning) }
    var authRequired by remember { mutableStateOf(initial.authRequired) }
    var dialect by remember { mutableStateOf(initial.reasoningDialect) }

    val builtIn = BuiltInProviders.isBuiltIn(providerId)

    fun snapshot() = initial.copy(
        label = label.trim(),
        kind = kind,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        temperature = temperature,
        reasoning = reasoning,
        reasoningDialect = dialect,
        authRequired = authRequired,
    )

    val validation = validateProvider(snapshot(), apiKey, requireLabel = !builtIn)

    // No Save button: leaving the screen must never lose work, so every edit persists immediately.
    fun commit() = onSave(snapshot(), apiKey)

    // Refetch the catalogue once the user pauses, rather than on every keystroke.
    LaunchedEffect(baseUrl, apiKey, kind) {
        delay(600)
        onLoadModels(snapshot(), apiKey)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DefaultBanner(
            isDefault = isDefault,
            enabled = validation.isValid,
            onSetDefault = onSetDefault,
        )

        SectionLabel("Credentials")
        SettingsCard {
            SettingsRow(
                title = "Requires an API key",
                subtitle = if (authRequired) {
                    "Plume will not run without one"
                } else {
                    "Off — for local runtimes like Ollama or LM Studio"
                },
                trailing = {
                    Switch(
                        checked = authRequired,
                        onCheckedChange = { authRequired = it; commit() },
                    )
                },
                onClick = { authRequired = !authRequired; commit() },
            )
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; commit() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (authRequired) "API key *" else "API key (optional)") },
            singleLine = true,
            isError = validation.apiKey != null,
            visualTransformation = if (revealKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { revealKey = !revealKey }) {
                    Icon(
                        imageVector = if (revealKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealKey) "Hide key" else "Show key",
                    )
                }
            },
            supportingText = {
                Text(validation.apiKey ?: "Encrypted with a key held in the Android Keystore.")
            },
        )

        // A local address almost always means a runtime that wants no credentials; offering the
        // switch at the moment the URL says so beats making the user find it after a 401.
        if (authRequired && isLocalEndpoint(baseUrl)) {
            TextButton(onClick = { authRequired = false; commit() }) {
                Text("This looks like a local endpoint — no API key needed")
            }
        }

        SectionLabel("Endpoint")
        if (!builtIn) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it; commit() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                label = { Text("Display name *") },
                singleLine = true,
                isError = validation.label != null,
                supportingText = validation.label?.let { { Text(it) } },
            )
            KindSelector(selected = kind, onSelect = { kind = it; commit() })
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; commit() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Base URL *") },
            singleLine = true,
            isError = validation.baseUrl != null,
            supportingText = { Text(validation.baseUrl ?: baseUrlHint(kind)) },
        )

        ModelField(
            value = model,
            state = models,
            error = validation.model,
            onValueChange = { model = it; commit() },
            onReload = { onLoadModels(snapshot(), apiKey) },
        )

        SectionLabel("Sampling")
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Temperature", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = String.format(Locale.US, "%.1f", temperature),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PlumeSlider(
                value = temperature,
                onValueChange = { temperature = it },
                onValueChangeFinished = { commit() },
                valueRange = 0f..1.5f,
                steps = 14,
            )
            Text(
                text = "Lower is more literal. Correction and translation both favour the low end.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("Reasoning")
        SettingsCard {
            SettingsRow(
                title = "Keep thinking short",
                subtitle = if (reasoning == ReasoningMode.Low) {
                    "Asks for minimal deliberation. Correcting and translating rarely need more."
                } else {
                    "Uses the provider's own default, which can be slow and costly"
                },
                trailing = {
                    Switch(
                        checked = reasoning == ReasoningMode.Low,
                        onCheckedChange = {
                            reasoning = if (it) ReasoningMode.Low else ReasoningMode.ProviderDefault
                            commit()
                        },
                    )
                },
                onClick = {
                    reasoning = if (reasoning == ReasoningMode.Low) {
                        ReasoningMode.ProviderDefault
                    } else {
                        ReasoningMode.Low
                    }
                    commit()
                },
            )
        }
        Text(
            text = "If a model rejects the setting, Plume retries without it automatically and " +
                "remembers not to send it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
        )

        // Shown for every provider, not just custom ones: detection is a heuristic everywhere, and
        // a provider that changes its API should be correctable without editing the app.
        if (reasoning == ReasoningMode.Low) {
            DialectSelector(
                selected = dialect,
                detected = Reasoning.detect(kind, baseUrl.trim()),
                onSelect = { dialect = it; commit() },
            )
        }

        SectionLabel("Check")
        SettingsCard {
            SettingsRow(
                title = "Test connection",
                subtitle = if (validation.isValid) {
                    probeSubtitle(probe)
                } else {
                    "Fill in the required fields first"
                },
                enabled = validation.isValid && probe !is ProbeState.Running,
                trailing = {
                    if (probe is ProbeState.Running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                onClick = {
                    onClearProbe()
                    commit()
                    onTest()
                },
            )
        }

        if (onDelete != null) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Remove provider")
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove $label?") },
            text = { Text("Its settings and stored API key will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DefaultBanner(isDefault: Boolean, enabled: Boolean, onSetDefault: () -> Unit) {
    if (isDefault) {
        Row(
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Default provider",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Button(
            onClick = onSetDefault,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        ) {
            Text("Make this the default provider")
        }
    }
}

@Composable
private fun DialectSelector(
    selected: ReasoningDialect,
    detected: ReasoningStyle,
    onSelect: (ReasoningDialect) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = "Reasoning parameter",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReasoningDialect.entries.forEach { option ->
                PlumeFilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = dialectLabel(option),
                )
            }
        }
        Text(
            text = if (selected == ReasoningDialect.Auto) {
                "Detected: ${styleLabel(detected)}. Set it yourself if this endpoint speaks another."
            } else {
                "Sending ${styleLabel(dialectStyle(selected))} regardless of the URL."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun dialectLabel(dialect: ReasoningDialect) = when (dialect) {
    ReasoningDialect.Auto -> "Auto"
    ReasoningDialect.OpenAi -> "OpenAI"
    ReasoningDialect.OpenRouter -> "OpenRouter"
    ReasoningDialect.Gemini -> "Gemini"
}

private fun dialectStyle(dialect: ReasoningDialect) = when (dialect) {
    ReasoningDialect.OpenRouter -> ReasoningStyle.OpenRouterReasoning
    ReasoningDialect.Gemini -> ReasoningStyle.GeminiBudget
    else -> ReasoningStyle.OpenAiEffort
}

private fun styleLabel(style: ReasoningStyle) = when (style) {
    ReasoningStyle.OpenAiEffort -> "reasoning_effort"
    ReasoningStyle.OpenRouterReasoning -> "reasoning object"
    ReasoningStyle.GeminiBudget -> "thinkingBudget"
}

@Composable
private fun KindSelector(selected: ProviderKind, onSelect: (ProviderKind) -> Unit) {
    Column {
        Text(
            text = "API format",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderKind.entries.forEach { option ->
                PlumeFilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = kindLabel(option),
                )
            }
        }
    }
}

private fun kindLabel(kind: ProviderKind) = when (kind) {
    ProviderKind.OpenAiCompatible -> "OpenAI-compatible"
    ProviderKind.Gemini -> "Gemini"
}

private fun baseUrlHint(kind: ProviderKind) = when (kind) {
    ProviderKind.OpenAiCompatible -> "Plume appends /chat/completions and /models"
    ProviderKind.Gemini -> "Plume appends /v1beta/models"
}

private fun probeSubtitle(probe: ProbeState) = when (probe) {
    ProbeState.Idle -> "Sends one short request to verify the key, URL and model"
    ProbeState.Running -> "Contacting the provider…"
    is ProbeState.Ok -> "Working — replied \"${probe.sample}\""
    is ProbeState.Failed -> probe.message
}
