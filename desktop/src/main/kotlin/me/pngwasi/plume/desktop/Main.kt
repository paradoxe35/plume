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
import com.kdroid.composetray.tray.api.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
        val windowIcon = rememberWindowIcon()

        // The result appears in someone else's window, so without this a failure is
        // indistinguishable from the shortcut never having fired.
        //
        // The desktop's own notification service is tried first. AWT's tray balloon is native on
        // Windows but is Java's own drawing on Linux, which looks foreign and ignores do-not-
        // disturb — so it is the fallback rather than the route.
        val notifier = remember { PlatformNotifier() }
        LaunchedEffect(outcome) {
            if (loaded?.desktop?.notifyOnFinish != true) return@LaunchedEffect
            val (body, level) = when (val settled = outcome) {
                is ActionOutcome.Failed -> settled.message to NotificationLevel.Error
                is ActionOutcome.Done -> "${settled.label} done" to NotificationLevel.Info
                else -> return@LaunchedEffect
            }
            withContext(Dispatchers.IO) { notifier.notify("Plume", body, level) }
        }

        if (trayAvailable) {
            PlumeTray(
                outcome = outcome,
                settings = loaded,
                onOpen = { windowVisible = true },
                onQuit = {
                    controller.shutdown()
                    exitApplication()
                },
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
                // Without this the window and the taskbar show Compose's own logo.
                icon = windowIcon,
                state = state,
            ) {
                PlumeTheme(mode = theme) {
                    DesktopSettingsWindow(
                        controller = controller,
                        settings = loaded,
                        history = history,
                        onQuit = {
                            controller.shutdown()
                            exitApplication()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The tray: what shows that Plume is running, and how to reach settings or quit.
 *
 * It deliberately carries no Revise or Translate action. Opening a tray menu moves the input focus
 * away from the window the user was working in, so by the time the item is clicked there is no
 * selection left to act on — the action would run against the wrong window, or nothing at all. The
 * shortcuts exist precisely because they do not steal focus, and they are the only honest way to
 * trigger the actions.
 *
 * This uses the desktop's own status-notifier protocol rather than `androidx.compose.ui.window.Tray`,
 * which goes through `java.awt.PopupMenu` — a heavyweight X11 widget drawn in Motif style that
 * ignores the GTK theme and cannot be styled.
 */
@Composable
private fun ApplicationScope.PlumeTray(
    outcome: ActionOutcome,
    settings: AppSettings?,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
) {
    val dark = when (settings?.theme ?: ThemeMode.System) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val busy = outcome is ActionOutcome.Working

    Tray(
        icon = rememberTrayIcon(dark = dark, busy = busy),
        tooltip = when (outcome) {
            is ActionOutcome.Working -> "Plume — ${outcome.label}…"
            is ActionOutcome.Failed -> "Plume — ${outcome.message}"
            else -> "Plume"
        },
        primaryAction = onOpen,
    ) {
        Item(label = "Settings…", onClick = onOpen)
        Divider()
        Item(label = "Quit Plume", onClick = onQuit)
    }
}

/** AWT reports this honestly, and it is false on a stock GNOME desktop. */
private fun isTraySupported(): Boolean =
    runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
