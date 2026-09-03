package me.pngwasi.plume.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ui.theme.PlumeTheme

fun main() {
    // Before anything can touch JNA.
    NativeLibraryPath.configure()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = DesktopController(scope)

    application {
        val settings by controller.settings.collectAsState()
        val outcome by controller.actions.outcome.collectAsState()
        val history by controller.actions.history.collectAsState()
        val loaded = settings

        // Without a tray there is nowhere to put a hidden app, so the window has to stay: GNOME
        // ships no tray by default, and quietly vanishing would look like a crash.
        val trayAvailable = remember { isTraySupported() }

        var windowVisible by remember { mutableStateOf(false) }
        var started by remember { mutableStateOf(false) }

        LaunchedEffect(loaded != null) {
            if (loaded != null && !started) {
                started = true
                windowVisible = !trayAvailable || !loaded.desktop.startMinimised
            }
        }

        // Wired once settings are readable, and again whenever the bindings change.
        LaunchedEffect(loaded?.desktop) {
            loaded?.let { controller.applyHotkeys(it) }
        }

        LaunchedEffect(Unit) {
            controller.openRequests.collect { windowVisible = true }
        }

        // On macOS the Dock icon follows the window: a tray app with nothing on screen has no
        // business in the Dock or in Cmd-Tab, and one with a window open must be reachable there.
        LaunchedEffect(windowVisible) {
            if (!MacDock.isSupported) return@LaunchedEffect
            if (windowVisible) MacDock.showInDock() else MacDock.hideFromDock()
        }

        val theme = loaded?.theme ?: ThemeMode.System
        val trayState = rememberTrayState()

        // The result appears in someone else's window, so without this a failure is indis-
        // tinguishable from the shortcut never having fired.
        LaunchedEffect(outcome) {
            if (loaded?.desktop?.notifyOnFinish != true) return@LaunchedEffect
            when (val settled = outcome) {
                is ActionOutcome.Failed -> trayState.sendNotification(
                    Notification("Plume", settled.message, Notification.Type.Error),
                )
                is ActionOutcome.Done -> trayState.sendNotification(
                    Notification("Plume", "${settled.label} done", Notification.Type.Info),
                )
                else -> Unit
            }
        }

        if (trayAvailable) {
            PlumeTray(
                trayState = trayState,
                outcome = outcome,
                settings = loaded,
                onOpen = { windowVisible = true },
                onQuit = {
                    controller.shutdown()
                    exitApplication()
                },
                onRevise = { controller.actions.reviseSelection() },
                onReviseAll = { controller.actions.reviseEverything() },
                onTranslate = { code -> controller.actions.translateSelection(code) },
            )
        }

        if (windowVisible && loaded != null) {
            val state = rememberWindowState(
                size = DpSize(560.dp, 800.dp),
                position = WindowPosition.Aligned(Alignment.Center),
            )
            Window(
                onCloseRequest = {
                    // Closing is not quitting: the shortcuts are the product and they keep working
                    // with nothing on screen. Without a tray, closing has to mean quit, or the app
                    // would be left running with no way back to it.
                    if (loaded.desktop.closeToTray && trayAvailable) {
                        windowVisible = false
                    } else {
                        controller.shutdown()
                        exitApplication()
                    }
                },
                title = "Plume",
                state = state,
            ) {
                PlumeTheme(mode = theme) {
                    DesktopSettingsWindow(
                        controller = controller,
                        settings = loaded,
                        history = history,
                        outcome = outcome,
                    )
                }
            }
        }
    }
}

/**
 * The tray is the desktop app's real front door: the window is optional, but something has to show
 * that Plume is running, report what a shortcut just did, and offer a translate target without
 * making the user open a window for it.
 */
@Composable
private fun ApplicationScope.PlumeTray(
    trayState: androidx.compose.ui.window.TrayState,
    outcome: ActionOutcome,
    settings: AppSettings?,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
    onRevise: () -> Unit,
    onReviseAll: () -> Unit,
    onTranslate: (String) -> Unit,
) {
    val dark = when (settings?.theme ?: ThemeMode.System) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val busy = outcome is ActionOutcome.Working

    Tray(
        state = trayState,
        icon = rememberTrayIcon(dark = dark, busy = busy),
        tooltip = when (outcome) {
            is ActionOutcome.Working -> "Plume — ${outcome.label}…"
            is ActionOutcome.Failed -> "Plume — ${outcome.message}"
            else -> "Plume"
        },
        onAction = onOpen,
        menu = {
            Item("Revise selection", enabled = !busy, onClick = onRevise)
            Item("Revise everything", enabled = !busy, onClick = onReviseAll)
            Separator()
            // The pinned languages, so translating never requires opening a window.
            settings?.translate?.favorites.orEmpty().take(6).forEach { code ->
                Item(
                    text = "Translate to ${Languages.resolve(code).displayName()}",
                    enabled = !busy,
                    onClick = { onTranslate(code) },
                )
            }
            Separator()
            Item("Settings…", onClick = onOpen)
            Item("Quit Plume", onClick = onQuit)
        },
    )
}

/** AWT reports this honestly, and it is false on a stock GNOME desktop. */
private fun isTraySupported(): Boolean =
    runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
