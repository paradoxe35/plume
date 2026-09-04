package me.pngwasi.plume.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.DesktopSettings
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.components.rememberTrackedScrollState
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * How Plume starts, and what it does when its window is closed.
 *
 * These used to sit under "Shortcuts", where nothing about the row leading to them suggested that
 * whether Plume survives closing its window was behind it.
 *
 * The two tray settings are shown as unavailable rather than hidden when the desktop has no tray:
 * hiding them would leave the user wondering where they went, and pretending they work would let
 * someone close the window on a system with no way to bring it back.
 */
@Composable
fun GeneralScreen(
    settings: DesktopSettings,
    launchAtLoginAvailable: Boolean,
    trayAvailable: Boolean,
    onSetLaunchAtLogin: (Boolean) -> Boolean,
    onChange: (DesktopSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTrackedScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "Plume works from the shortcuts, so it keeps running with no window on screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        SectionLabel("Starting")
        SettingsCard {
            SettingsRow(
                title = "Start with the system",
                subtitle = if (launchAtLoginAvailable) {
                    "Plume has to be running for the shortcuts to work."
                } else {
                    "Available once Plume is installed, not when run from a build."
                },
                icon = PlumeIcons.Refresh,
                trailing = {
                    Switch(
                        checked = settings.startOnLogin,
                        enabled = launchAtLoginAvailable,
                        onCheckedChange = { wanted ->
                            // Only record what actually happened: a switch that flips without the
                            // entry being written would be a straightforward lie.
                            if (onSetLaunchAtLogin(wanted)) {
                                onChange(settings.copy(startOnLogin = wanted))
                            }
                        },
                    )
                },
            )
            RowDivider()
            SettingsRow(
                title = "Start without the window",
                subtitle = if (trayAvailable) {
                    "Launch straight to the tray. The shortcuts work either way."
                } else {
                    "Needs a tray, and this desktop has none."
                },
                icon = PlumeIcons.VisibilityOff,
                trailing = {
                    Switch(
                        checked = settings.startMinimised && trayAvailable,
                        enabled = trayAvailable,
                        onCheckedChange = { onChange(settings.copy(startMinimised = it)) },
                    )
                },
            )
        }

        SectionLabel("While Plume runs")
        SettingsCard {
            SettingsRow(
                title = "Keep running when the window closes",
                subtitle = if (!trayAvailable) {
                    "Needs a tray to reopen from, and this desktop has none."
                } else if (settings.closeToTray) {
                    "Closing the window leaves the shortcuts working. Reopen it from the tray."
                } else {
                    "Closing the window quits Plume and stops the shortcuts."
                },
                icon = PlumeIcons.PushPin,
                trailing = {
                    Switch(
                        checked = settings.closeToTray && trayAvailable,
                        enabled = trayAvailable,
                        onCheckedChange = { onChange(settings.copy(closeToTray = it)) },
                    )
                },
            )
            RowDivider()
            SettingsRow(
                title = "Notify when finished",
                subtitle = "The result lands in another window, so this is how you know it worked.",
                icon = PlumeIcons.CheckCircle,
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
