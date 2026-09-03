package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.SettingsNavHost
import me.pngwasi.plume.ui.settings.SettingsViewModel
import me.pngwasi.plume.ui.settings.rememberSettingsStack

/**
 * The desktop settings window.
 *
 * The screens are the shared ones; what is added here is the pair that only makes sense with a tray
 * and a global shortcut behind them.
 */
@Composable
fun DesktopSettingsWindow(
    controller: DesktopController,
    settings: AppSettings,
    history: List<HistoryEntry>,
) {
    val viewModel = remember(controller) {
        SettingsViewModel(controller.repository, controller.secrets)
    }
    val scope = rememberCoroutineScope()
    val stack = rememberSettingsStack()

    SettingsNavHost(
        viewModel = viewModel,
        settings = settings,
        stack = stack,
        intro = "Select text anywhere, then press a Plume shortcut. Plume stays in the tray.",
        platformRows = { push ->
            RowDivider()
            SettingsRow(
                title = "Shortcuts",
                subtitle = shortcutSubtitle(controller),
                icon = PlumeIcons.Keyboard,
                showChevron = true,
                onClick = { push(Destination.Hotkeys) },
            )
            RowDivider()
            SettingsRow(
                title = "Recent changes",
                subtitle = if (history.isEmpty()) {
                    "Nothing yet this session"
                } else {
                    "${history.size} kept, with the original text"
                },
                icon = PlumeIcons.Refresh,
                showChevron = true,
                onClick = { push(Destination.History) },
            )
        },
        platformScreen = { destination, _ ->
            when (destination) {
                Destination.Hotkeys -> HotkeysScreen(
                    settings = settings.desktop,
                    defaults = hotkeyDefaultsFor(),
                    availability = controller.availability,
                    rejectedBindings = controller.rejectedBindings,
                    launchAtLoginAvailable = controller.launchAtLoginAvailable,
                    onSetLaunchAtLogin = LaunchAtLogin::setEnabled,
                    onChange = { updated ->
                        scope.launch {
                            controller.repository.update { it.copy(desktop = updated) }
                        }
                    },
                )

                Destination.History -> HistoryScreen(
                    history = history,
                    onCopy = { text -> controller.copyToClipboard(text) },
                )

                // Android's companion keyboard; there is no input-method list to join here.
                else -> Unit
            }
        },
    )
}

private fun shortcutSubtitle(controller: DesktopController): String =
    when (val availability = controller.availability) {
        HotkeyAvailability.Ready ->
            if (controller.rejectedBindings.isEmpty()) "Active" else "Some shortcuts were refused"
        is HotkeyAvailability.NeedsPermission -> availability.summary
        is HotkeyAvailability.Unavailable -> "Unavailable"
    }
