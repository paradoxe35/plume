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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

@Composable
fun AppearanceScreen(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionLabel("Theme")
        SettingsCard {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SettingsRow(
                    title = when (mode) {
                        ThemeMode.System -> "Follow system"
                        ThemeMode.Light -> "Light"
                        ThemeMode.Dark -> "Dark"
                    },
                    icon = when (mode) {
                        ThemeMode.System -> PlumeIcons.PhoneAndroid
                        ThemeMode.Light -> PlumeIcons.LightMode
                        ThemeMode.Dark -> PlumeIcons.DarkMode
                    },
                    trailing = {
                        RadioButton(selected = current == mode, onClick = { onSelect(mode) })
                    },
                    onClick = { onSelect(mode) },
                )
                if (index != ThemeMode.entries.lastIndex) RowDivider()
            }
        }

        Text(
            text = "The theme also applies to the overlays Plume shows on top of other apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
    }
}

/**
 * The honest version of "how it works", including where it cannot work. Users otherwise read the
 * read-only limitation as a bug in Plume rather than a platform constraint.
 */
@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel("Using Plume")
        Steps(
            "Select text in any app — WhatsApp, Messages, Gmail, your browser.",
            "Tap Revise or Translate in the selection toolbar. They may sit behind the ⋮ overflow.",
            "Revise replaces your text in place. Translate asks for a language first.",
        )

        SectionLabel("Where text can be replaced")
        Paragraph(
            "When you select text you are writing — a message box, a compose field, a note — Plume " +
                "hands the result straight back and it replaces your selection.",
        )
        Paragraph(
            "When you select text you are only reading — a received message, a web page, a PDF — " +
                "Android gives apps no way to write back. Plume shows the result and offers Copy " +
                "instead. This is a platform limit, not a missing feature.",
        )
        Paragraph(
            "Some apps report even their own input fields as read-only, so Plume offers Copy there " +
                "too. The Plume keyboard can still replace text in those fields, because it works " +
                "through a different channel.",
        )

        SectionLabel("If the menu does not appear")
        Paragraph(
            "A few apps draw their own selection toolbar and ignore third-party actions. Most do " +
                "not. Check the ⋮ overflow first — Android shows only a few actions inline.",
        )

        SectionLabel("Your data")
        Paragraph(
            "Selected text goes to the AI provider you configured, and nowhere else. Plume has no " +
                "backend and no analytics. API keys are encrypted with a key held in the Android " +
                "Keystore and never leave the device.",
        )
    }
}

@Composable
private fun Steps(vararg steps: String) {
    Column(
        modifier = Modifier.padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(text = step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp),
    )
}

