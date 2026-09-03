package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kdroid.composetray.tray.api.Tray
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.data.isFullyConfigured
import me.pngwasi.plume.data.keyedProviders
import me.pngwasi.plume.native.PlumeNative
import me.pngwasi.plume.ui.icons.PlumeMark
import me.pngwasi.plume.ui.theme.PlumeTheme

fun main() {
    // First, so that anything below it is recorded — including a failure to start.
    PlumeLog.install(version = "1.0.0")

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

        // Opening is an event, not a state. `windowVisible = true` when the window is already open
        // changes nothing, so nothing runs and the window stays where it was — behind whatever the
        // user was looking at, or minimised — and the tray looks like it did nothing. This counter
        // changes on every request, so the effect that raises the window always fires.
        var openRequests by remember { mutableStateOf(0) }
        val requestOpen: () -> Unit = {
            windowVisible = true
            openRequests++
        }

        LaunchedEffect(loaded != null) {
            val settings = loaded ?: return@LaunchedEffect
            if (started) return@LaunchedEffect
            started = true

            // Starting in the tray is right for a working Plume and wrong for one that cannot run
            // yet: a shortcut that fails because no key was ever entered looks like a broken app,
            // and the tray is the last place someone would look for the reason. Reads the secret
            // store, so not on the UI thread.
            val ready = withContext(Dispatchers.IO) {
                settings.isFullyConfigured(settings.keyedProviders(controller.secrets))
            }
            windowVisible = !trayAvailable || !settings.desktop.startMinimised || !ready
        }

        // Wired once settings are readable, and again whenever the bindings change.
        LaunchedEffect(loaded?.desktop) {
            loaded?.let { controller.applyHotkeys(it) }
        }

        LaunchedEffect(Unit) {
            controller.openRequests.collect { requestOpen() }
        }

        // On macOS the Dock icon follows the window: a tray app with nothing on screen has no
        // business in the Dock or in Cmd-Tab, and one with a window open must be reachable there.
        LaunchedEffect(windowVisible) {
            if (!MacDock.isSupported) return@LaunchedEffect
            if (windowVisible) MacDock.showInDock() else MacDock.hideFromDock()
        }

        val theme = loaded?.theme ?: ThemeMode.System
        val windowIcons = remember { appIconImages() }

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
                onOpen = requestOpen,
                onQuit = {
                    controller.shutdown()
                    exitApplication()
                },
            )
        }

        LaunchedEffect(Unit) {
            PlumeLog.info(
                "Tray available: $trayAvailable, native input: " +
                    (if (PlumeNative.library != null) "loaded" else "unavailable"),
            )
        }

        if (windowVisible && loaded != null) {
            val state = rememberWindowState(
                size = remember { settingsWindowSize() },
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
                // A settings window with one column of rows has no second layout to widen into,
                // and dragging it wider only stretches the rows. MyReviser fixed its window for
                // the same reason.
                resizable = false,
                state = state,
            ) {
                // Not the `icon` parameter: that carries a single bitmap, which the taskbar then
                // resamples to whatever size it wants — the oversized, blurred result. AWT picks
                // from a set instead, so every size is drawn rather than stretched.
                //
                // Applied again on `windowOpened` because the effect runs before the window is
                // realised, and X11 reads the icon when the peer is created.
                DisposableEffect(window) {
                    window.iconImages = windowIcons
                    val opened = object : WindowAdapter() {
                        override fun windowOpened(event: WindowEvent) {
                            window.iconImages = windowIcons
                        }
                    }
                    window.addWindowListener(opened)
                    onDispose { window.removeWindowListener(opened) }
                }

                // Raised on every request, including the ones where the window was already open.
                //
                // On macOS this is the whole of it: a menu-bar app is an accessory, and an
                // accessory's window opens behind the active application with no keyboard focus,
                // so the app has to be activated before `toFront` means anything. Elsewhere the
                // window may simply be minimised or buried.
                LaunchedEffect(openRequests) {
                    state.isMinimized = false
                    if (MacDock.isSupported) MacDock.showInDock()
                    window.toFront()
                    window.requestFocus()
                }

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
 * The size of the settings window, which cannot be resized.
 *
 * Narrow on purpose: one column of rows reads better than a stretched one, and the content scrolls.
 *
 * Height is capped against the screen rather than fixed outright, because a window that will not
 * fit and will not resize is one with its bottom off the desktop and no way to get it back.
 */
internal fun settingsWindowSize(
    screenHeight: Int = runCatching { Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(1080),
): DpSize = DpSize(485.dp, (screenHeight - 160).coerceIn(520, 660).dp)

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
    val busy = outcome is ActionOutcome.Working

    Tray(
        // The mark is handed over untinted so the library can adapt it to the panel's own
        // background. Colouring it from Plume's theme was wrong: a tray sits in the desktop's
        // panel, not in Plume's window, and the two are routinely opposite — which is how the icon
        // ended up dark on a dark panel and all but invisible.
        icon = remember { PlumeMark.vector() },
        tint = if (busy) BusyTint else null,
        // The render properties are left at their default, which draws a 192px scene and resamples
        // it to what the platform's panel actually wants — 24px on Linux, 32 on Windows, 44 on
        // macOS. `withoutScalingAndAliasing` skips that step and hands the panel the full 192px
        // image, which is the oversized, scaled-by-someone-else look it is meant to avoid.
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

/** Only used while working, where saying so matters more than blending into the panel. */
private val BusyTint = Color(0xFF7FD1C4)

/** AWT reports this honestly, and it is false on a stock GNOME desktop. */
private fun isTraySupported(): Boolean =
    runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
