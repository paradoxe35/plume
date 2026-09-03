package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

/** What the system currently thinks of Plume's keyboard, refreshed each time the screen is shown. */
data class KeyboardStatus(
    val available: Boolean,
    val enabledInSystem: Boolean,
    val isCurrent: Boolean,
)

/**
 * The opt-in for the companion keyboard.
 *
 * Enabling it is a three-step journey the system owns most of — turn it on here, switch it on in
 * Android's keyboard settings, then select it while typing. Each step is a separate row that only
 * unlocks once the previous one is done, because a user who cannot tell which step they are on
 * simply concludes the feature is broken.
 */
@Composable
fun KeyboardScreen(
    enabled: Boolean,
    status: KeyboardStatus,
    onToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onShowPicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "An optional Plume panel you can switch to while typing. It reads the whole " +
                "message without you selecting anything, and writes the result straight back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        SettingsCard {
            SettingsRow(
                title = "Plume keyboard",
                subtitle = if (enabled) {
                    "Available in your keyboard list"
                } else {
                    "Off — nothing is added to your keyboards"
                },
                icon = PlumeIcons.Keyboard,
                trailing = { Switch(checked = enabled, onCheckedChange = onToggle) },
                onClick = { onToggle(!enabled) },
            )
        }

        if (enabled) {
            SectionLabel("Finish setting it up")
            SettingsCard {
                SettingsRow(
                    title = "1. Turn it on in Android settings",
                    subtitle = if (status.enabledInSystem) {
                        "Done — Plume appears in your on-screen keyboards"
                    } else {
                        "Open Android's keyboard list and switch Plume on"
                    },
                    icon = PlumeIcons.OpenInNew,
                    showChevron = true,
                    onClick = onOpenSystemSettings,
                )
                RowDivider()
                SettingsRow(
                    title = "2. Switch to it while typing",
                    subtitle = when {
                        status.isCurrent -> "Plume is your current keyboard"
                        status.enabledInSystem -> "Opens the keyboard picker"
                        else -> "Finish step 1 first"
                    },
                    icon = PlumeIcons.SwapHoriz,
                    enabled = status.enabledInSystem,
                    showChevron = true,
                    onClick = onShowPicker,
                )
            }

            Text(
                text = "Plume is a panel of actions, not a typing keyboard. Tap Keyboard inside it " +
                    "to go straight back to the one you normally use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )

            if (status.isCurrent) {
                Text(
                    text = "Switch to another keyboard before turning this off, or Android will " +
                        "pick a replacement for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
        }

        SectionLabel("Still works without it")
        Text(
            text = "Revise and Translate remain in the text-selection menu whether or not the " +
                "keyboard is on. The keyboard only adds a second way in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
