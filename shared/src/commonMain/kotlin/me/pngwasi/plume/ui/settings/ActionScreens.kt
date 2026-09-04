package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.rememberTrackedScrollState
import me.pngwasi.plume.data.Prompts
import me.pngwasi.plume.data.ReviseSettings
import me.pngwasi.plume.data.TranslateSettings

@Composable
fun ReviseScreen(
    settings: ReviseSettings,
    onChange: ((ReviseSettings) -> ReviseSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTrackedScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 40.dp),
    ) {
        Text(
            text = "Revise corrects the selection in the language it is already in. " +
                platformCopy().replacementNote,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        PromptEditorCard(
            value = settings.systemPrompt,
            default = Prompts.REVISE,
            placeholderHint = null,
            onChange = { prompt -> onChange { it.copy(systemPrompt = prompt) } },
        )

        // Labelled, or the two sliders sit under "System prompt" and read as part of it.
        SectionLabel("Limits")
        Column(modifier = Modifier.padding(top = 4.dp)) {
            CharacterLimitControl(settings.characterLimit) { limit ->
                onChange { it.copy(characterLimit = limit) }
            }
            TimeoutControl(settings.timeoutSeconds) { seconds ->
                onChange { it.copy(timeoutSeconds = seconds) }
            }
        }
    }
}

@Composable
fun TranslatePromptScreen(
    settings: TranslateSettings,
    onChange: ((TranslateSettings) -> TranslateSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTrackedScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 40.dp),
    ) {
        Text(
            text = "The model detects the source language itself, so only the target is asked for.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        PromptEditorCard(
            value = settings.systemPrompt,
            default = Prompts.TRANSLATE,
            placeholderHint = "${Prompts.TARGET_LANGUAGE} is replaced with the language you pick. " +
                "Remove it and Plume appends the target as a final line instead.",
            onChange = { prompt -> onChange { it.copy(systemPrompt = prompt) } },
        )

        // Labelled, or the two sliders sit under "System prompt" and read as part of it.
        SectionLabel("Limits")
        Column(modifier = Modifier.padding(top = 4.dp)) {
            CharacterLimitControl(settings.characterLimit) { limit ->
                onChange { it.copy(characterLimit = limit) }
            }
            TimeoutControl(settings.timeoutSeconds) { seconds ->
                onChange { it.copy(timeoutSeconds = seconds) }
            }
        }
    }
}
