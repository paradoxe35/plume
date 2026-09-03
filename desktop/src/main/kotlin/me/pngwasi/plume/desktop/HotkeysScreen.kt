package me.pngwasi.plume.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.DesktopSettings
import me.pngwasi.plume.data.duplicateHotkeys
import me.pngwasi.plume.data.validateHotkey
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * Shortcuts, and the reason they are not working when they are not working.
 *
 * The permission state is stated before the user tries a shortcut, because every platform fails the
 * same silent way — the binding simply never fires, with nothing to distinguish "not permitted"
 * from "wrong keys".
 */
@Composable
fun HotkeysScreen(
    settings: DesktopSettings,
    defaults: me.pngwasi.plume.data.HotkeyDefaults,
    availability: HotkeyAvailability,
    rejectedBindings: List<String>,
    onChange: (DesktopSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "Plume listens for these anywhere, then works on whatever is selected in the " +
                "app you are using.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        PermissionCard(availability)

        if (rejectedBindings.isNotEmpty()) {
            SectionLabel("Not registered")
            SettingsCard {
                SettingsRow(
                    title = rejectedBindings.joinToString(", "),
                    subtitle = "The system refused these, usually because another app has them.",
                    icon = PlumeIcons.ErrorOutline,
                )
            }
        }

        SectionLabel("Shortcuts")

        val bindings = listOf(
            settings.reviseSelectionOrDefault(defaults),
            settings.reviseAllOrDefault(defaults),
            settings.translateSelectionOrDefault(defaults),
        )
        val duplicates = duplicateHotkeys(bindings)

        HotkeyField(
            label = "Revise selection",
            help = "Fix spelling and grammar in whatever is selected.",
            value = settings.reviseSelectionOrDefault(defaults),
            duplicates = duplicates,
            onValue = { onChange(settings.copy(reviseSelection = it)) },
        )
        HotkeyField(
            label = "Revise everything",
            help = "Select the whole field first, then revise it.",
            value = settings.reviseAllOrDefault(defaults),
            duplicates = duplicates,
            onValue = { onChange(settings.copy(reviseAll = it)) },
        )
        HotkeyField(
            label = "Translate selection",
            help = "Translate into your default target, or the first pinned language.",
            value = settings.translateSelectionOrDefault(defaults),
            duplicates = duplicates,
            onValue = { onChange(settings.copy(translateSelection = it)) },
        )

        SectionLabel("Behaviour")
        SettingsCard {
            SettingsRow(
                title = "Start with the system",
                subtitle = "Plume needs to be running for the shortcuts to work.",
                icon = PlumeIcons.Refresh,
                trailing = {
                    Switch(
                        checked = settings.startOnLogin,
                        onCheckedChange = { onChange(settings.copy(startOnLogin = it)) },
                    )
                },
            )
            RowDivider()
            SettingsRow(
                title = "Start in the tray",
                subtitle = "Skip the settings window on launch.",
                icon = PlumeIcons.PhoneAndroid,
                trailing = {
                    Switch(
                        checked = settings.startMinimised,
                        onCheckedChange = { onChange(settings.copy(startMinimised = it)) },
                    )
                },
            )
            RowDivider()
            SettingsRow(
                title = "Close to the tray",
                subtitle = "Closing this window leaves the shortcuts running.",
                icon = PlumeIcons.Check,
                trailing = {
                    Switch(
                        checked = settings.closeToTray,
                        onCheckedChange = { onChange(settings.copy(closeToTray = it)) },
                    )
                },
            )
            RowDivider()
            SettingsRow(
                title = "Notify when finished",
                subtitle = "The result lands in another window, so this is how you know it worked.",
                icon = PlumeIcons.Info,
                trailing = {
                    Switch(
                        checked = settings.notifyOnFinish,
                        onCheckedChange = { onChange(settings.copy(notifyOnFinish = it)) },
                    )
                },
            )
        }
    }
}

@Composable
private fun PermissionCard(availability: HotkeyAvailability) {
    when (availability) {
        HotkeyAvailability.Ready -> SettingsCard {
            SettingsRow(
                title = "Shortcuts are active",
                subtitle = "Plume can see key presses from other applications.",
                icon = PlumeIcons.CheckCircle,
            )
        }

        is HotkeyAvailability.NeedsPermission -> SettingsCard {
            SettingsRow(
                title = availability.summary,
                subtitle = availability.instruction,
                icon = PlumeIcons.ErrorOutline,
            )
        }

        is HotkeyAvailability.Unavailable -> SettingsCard {
            SettingsRow(
                title = "Shortcuts are unavailable",
                subtitle = availability.reason,
                icon = PlumeIcons.ErrorOutline,
            )
        }
    }
}

@Composable
private fun HotkeyField(
    label: String,
    help: String,
    value: String,
    duplicates: Set<String>,
    onValue: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    val formatError = validateHotkey(text)
    val duplicate = me.pngwasi.plume.data.normaliseHotkey(text) in duplicates
    val error = formatError ?: if (duplicate) "Another action already uses this shortcut" else null

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                if (validateHotkey(it) == null) onValue(it)
            },
            label = { Text(label) },
            supportingText = { Text(error ?: help) },
            isError = error != null,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
