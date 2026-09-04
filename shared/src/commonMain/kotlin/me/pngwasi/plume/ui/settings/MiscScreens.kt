package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.rememberTrackedScrollState
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

@Composable
fun AppearanceScreen(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val copy = remember { platformCopy() }

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
                        ThemeMode.System -> copy.systemThemeIcon
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
            text = copy.themeNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
    }
}

/**
 * The honest version of "how it works", including where it cannot work. Users otherwise read a
 * platform's own limits as a bug in Plume.
 *
 * The words come from [platformCopy]: how you reach Plume, what stands in the way and where your
 * keys are kept are different on each platform, and a page that describes someone else's is worse
 * than no page at all.
 */
@Composable
fun AboutScreen() {
    val sections = remember { platformCopy().about }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTrackedScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEach { section ->
            SectionLabel(section.title)
            if (section.steps.isNotEmpty()) Steps(section.steps)
            section.paragraphs.forEach { Paragraph(it) }
        }
    }
}

@Composable
private fun Steps(steps: List<String>) {
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

