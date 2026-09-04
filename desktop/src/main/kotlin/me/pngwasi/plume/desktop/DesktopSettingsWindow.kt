package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.PlatformBlocker
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
    onQuit: () -> Unit,
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
        // Outranks a missing API key: no configuration helps while the system refuses to deliver
        // the shortcut at all.
        blocker = (controller.availability as? HotkeyAvailability.NeedsPermission)?.let {
            PlatformBlocker(summary = it.summary, detail = it.instruction)
        },
        onFixBlocker = { stack.add(Destination.Hotkeys) },
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
            RowDivider()
            SettingsRow(
                title = "Logs",
                subtitle = "What Plume did, and what went wrong",
                icon = PlumeIcons.Info,
                showChevron = true,
                onClick = { push(Destination.Diagnostics) },
            )
        },
        platformFooter = {
            // Below the settings rather than among them. Closing the window only hides it — the
            // shortcuts are the product and they keep running — so there has to be a way out that
            // is not the tray, which is easy to miss and which some Linux desktops never show.
            SectionLabel("Leaving")
            SettingsCard {
                SettingsRow(
                    title = "Quit Plume",
                    subtitle = "Stops the shortcuts until Plume is started again",
                    icon = PlumeIcons.Delete,
                    onClick = onQuit,
                )
            }
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
                    onRecordingChange = controller::setListening,
                )

                Destination.Diagnostics -> DiagnosticsScreen(
                    onCopy = { text -> controller.copyToClipboard(text) },
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
