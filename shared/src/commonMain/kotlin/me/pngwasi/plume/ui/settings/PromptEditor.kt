package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.DEFAULT_CHARACTER_LIMIT
import me.pngwasi.plume.data.MAX_TIMEOUT_SECONDS
import me.pngwasi.plume.ui.components.PlumeSlider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.theme.PromptEditorStyle
import kotlin.math.roundToInt

/**
 * Prompt editor with a reset. Monospace, because prompts contain placeholders and indentation that
 * a proportional font makes hard to proofread.
 */
@Composable
fun PromptEditorCard(
    value: String,
    default: String,
    placeholderHint: String?,
    onChange: (String) -> Unit,
) {
    val effective = value.ifBlank { default }
    val isDefault = effective.trim() == default.trim()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("System prompt")
            if (!isDefault) {
                TextButton(onClick = { onChange("") }) { Text("Reset") }
            }
        }

        OutlinedTextField(
            value = effective,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            textStyle = PromptEditorStyle,
        )

        if (placeholderHint != null) {
            Text(
                text = placeholderHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

/** Character cap on the selection. Guards against an accidental select-all costing real money. */
@Composable
fun CharacterLimitControl(value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Maximum selection", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$value characters",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PlumeSlider(
            value = value.toFloat(),
            onValueChange = { onChange((it / 250f).roundToInt() * 250) },
            valueRange = 250f..12000f,
        )
        Text(
            text = "Longer selections are refused before any request is sent. Default is $DEFAULT_CHARACTER_LIMIT.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TimeoutControl(value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Timeout", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${value}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PlumeSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 15f..MAX_TIMEOUT_SECONDS.toFloat(),
        )
        Text(
            text = "Reasoning models can deliberate for a while before answering. Raise this if " +
                "requests time out before the model replies.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
