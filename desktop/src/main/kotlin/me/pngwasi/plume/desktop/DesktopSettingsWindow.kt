package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.DesktopSettings
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.BlockerFix
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
    /** False on a desktop with no status area, where the tray settings cannot mean anything. */
    trayAvailable: Boolean,
    onQuit: () -> Unit,
) {
    val viewModel = remember(controller) {
        SettingsViewModel(controller.repository, controller.secrets)
    }
    val scope = rememberCoroutineScope()
    val stack = rememberSettingsStack()
    val permissions by controller.permissions.collectAsState()

    SettingsNavHost(
        viewModel = viewModel,
        settings = settings,
        stack = stack,
        intro = "Select text anywhere, then press a Plume shortcut. Plume stays in the tray.",
        // Outranks a missing API key: no configuration helps while the system refuses to deliver
        // the shortcut at all.
        blocker = permissionBlocker(
            availability = controller.availability,
            permissions = permissions,
            missingAtLaunch = controller.permissionsMissingAtLaunch,
            onGrant = MacPermissions::request,
            onRestart = { controller.restart() },
        ),
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
                title = "General",
                subtitle = generalSubtitle(settings.desktop, trayAvailable),
                icon = PlumeIcons.Settings,
                showChevron = true,
                onClick = { push(Destination.General) },
            )
        },
        // Things to read rather than things to change, which is why they are not in Configuration.
        platformHelpRows = { push ->
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
                icon = PlumeIcons.Description,
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
                    icon = PlumeIcons.PowerSettingsNew,
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
                    onChange = { updated -> saveDesktop(scope, controller, updated) },
                    onRecordingChange = controller::setRecording,
                )

                Destination.General -> GeneralScreen(
                    settings = settings.desktop,
                    launchAtLoginAvailable = controller.launchAtLoginAvailable,
                    trayAvailable = trayAvailable,
                    onSetLaunchAtLogin = LaunchAtLogin::setEnabled,
                    onChange = { updated -> saveDesktop(scope, controller, updated) },
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

/**
 * Two states, one card.
 *
 * While something is missing it lists what to grant. Once everything is granted it asks for a
 * restart, because the listener was wired at launch and a privilege given afterwards never reaches
 * it — the shortcuts stay dead, which looks exactly like the permission not having worked.
 */
internal fun permissionBlocker(
    availability: HotkeyAvailability,
    permissions: MacPermissionState,
    missingAtLaunch: Boolean,
    supported: Boolean = MacPermissions.isSupported,
    onGrant: (MacPermission) -> Unit,
    onRestart: () -> Unit,
): PlatformBlocker? {
    if (supported && permissions.allGranted) {
        if (!missingAtLaunch) return null
        return PlatformBlocker(
            summary = "Restart Plume to finish",
            detail = "The permissions are granted. Plume reads them when it starts, so the " +
                "shortcuts begin working after a restart.",
            fixes = listOf(
                BlockerFix(
                    label = "Permissions granted",
                    why = "Restart Plume so the shortcuts pick them up.",
                    action = "Restart",
                    onSelect = onRestart,
                ),
            ),
        )
    }

    val needed = availability as? HotkeyAvailability.NeedsPermission ?: return null
    return PlatformBlocker(
        summary = needed.summary,
        detail = needed.instruction,
        // One row per privilege, because macOS grants them separately: a single button leaves the
        // user guessing which switch is still off. Wayland has nothing to open, so no rows.
        fixes = permissions.missing.map { permission ->
            BlockerFix(
                label = permission.label,
                why = permission.why,
                onSelect = { onGrant(permission) },
            )
        },
    )
}

/** One writing of the settings, since two screens now edit the same record. */
private fun saveDesktop(
    scope: CoroutineScope,
    controller: DesktopController,
    updated: DesktopSettings,
) {
    scope.launch { controller.repository.update { it.copy(desktop = updated) } }
}

/** Says what is behind the row, so it does not read as an unexplained "General". */
internal fun generalSubtitle(settings: DesktopSettings, trayAvailable: Boolean): String {
    val parts = buildList {
        if (settings.startOnLogin) add("Starts with the system")
        if (!trayAvailable) add("No tray on this desktop")
    }
    return parts.joinToString(" · ").ifEmpty { "Startup, tray and notifications" }
}

private fun shortcutSubtitle(controller: DesktopController): String =
    when (val availability = controller.availability) {
        HotkeyAvailability.Ready ->
            if (controller.rejectedBindings.isEmpty()) "Active" else "Some shortcuts were refused"
        is HotkeyAvailability.NeedsPermission -> "Waiting on permissions"
        is HotkeyAvailability.Unavailable -> "Unavailable"
    }
