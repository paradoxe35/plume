package me.pngwasi.plume.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kdroid.composetray.tray.api.Tray
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.DesktopOs
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.data.isFullyConfigured
import me.pngwasi.plume.data.keyedProviders
import me.pngwasi.plume.data.plumeConfigDirectory
import me.pngwasi.plume.native.PlumeNative
import me.pngwasi.plume.ui.icons.PlumeMark
import me.pngwasi.plume.ui.theme.PlumeTheme

fun main() {
    // Before any AWT class can initialise the rendering pipeline.
    useMetalOnMacOs()

    // First, so that anything below it is recorded — including a failure to start.
    PlumeLog.install(version = "1.0.0")

    // Before anything can touch JNA.
    NativeLibraryPath.configure()

    // Early, so a failure to start the key listener is recorded rather than printed into a void.
    PlumeNative.library?.let(NativeLogBridge::install)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = DesktopController(scope)

    // Before the tray and the shortcut listener exist: two copies would both claim the same global
    // shortcuts, and the loser is decided by whichever registered last.
    val instance = SingleInstance(File(plumeConfigDirectory()))
    if (!instance.claim(controller::requestOpen)) {
        PlumeLog.info("Handed this launch to the Plume that was already running")
        return
    }

    application {
        val settings by controller.settings.collectAsState()
        val outcome by controller.actions.outcome.collectAsState()
        val history by controller.actions.history.collectAsState()
        val loaded = settings

        // GNOME ships no tray by default; with nowhere to hide, the window has to stay visible.
        val trayAvailable = remember { isTraySupported() }

        var windowVisible by remember { mutableStateOf(false) }
        var started by remember { mutableStateOf(false) }

        // Opening is an event, not a state: a counter so the raise effect refires even when the
        // window is already visible.
        var openRequests by remember { mutableStateOf(0) }
        val requestOpen: () -> Unit = {
            windowVisible = true
            openRequests++
        }

        LaunchedEffect(loaded != null) {
            val settings = loaded ?: return@LaunchedEffect
            if (started) return@LaunchedEffect
            started = true

            // A Plume that cannot work must show itself rather than hide in the tray, or its
            // shortcuts fail silently and the tray is the last place anyone looks for the reason.
            // Two ways to be unable to work: no provider configured, and macOS withholding the
            // permissions the listener needs. Both read from outside, so not on the UI thread.
            val blocked = withContext(Dispatchers.IO) {
                val unconfigured =
                    !settings.isFullyConfigured(settings.keyedProviders(controller.secrets))
                val unpermitted = controller.availability !is HotkeyAvailability.Ready
                unconfigured || unpermitted
            }
            windowVisible = !trayAvailable || !settings.desktop.startMinimised || blocked
        }

        LaunchedEffect(loaded?.desktop) {
            loaded?.let { controller.applyHotkeys(it) }
        }

        LaunchedEffect(Unit) {
            controller.openRequests.collect { requestOpen() }
        }

        // macOS says nothing when a privilege is granted, so the only way to notice is to look.
        LaunchedEffect(Unit) { controller.watchPermissions() }

        val theme = loaded?.theme ?: ThemeMode.System
        val rounded = remember { roundedWindowSupported }
        val windowIcons = remember { appIconImages() }

        // The result lands in someone else's window, so a failure is otherwise invisible. Prefers
        // the desktop's own notification service; AWT's balloon is Java-drawn on Linux and ignores
        // do-not-disturb, so it is only the fallback.
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
                // Through the flow rather than straight to `requestOpen`: the tray calls back on
                // its own thread, and this lands the state change on the composition's.
                onOpen = controller::requestOpen,
                onQuit = controller::quit,
            )
        }

        LaunchedEffect(Unit) {
            PlumeLog.info(
                "Tray available: $trayAvailable, native input: " +
                    (if (PlumeNative.library != null) "loaded" else "unavailable") +
                    ", start with the system: " + LaunchAtLogin.diagnostics() +
                    ", Dock: " + MacDock.diagnostics(),
            )
        }

        if (windowVisible && loaded != null) {
            val size = remember { settingsWindowSize() }
            val state = rememberWindowState(
                size = size,
                position = remember(size) { centredPosition(size) },
            )
            // Closing is not quitting — the shortcuts keep working with nothing on screen, and the
            // tray brings the window back. Without a tray it must quit, or there is no way back.
            val close: () -> Unit = {
                if (trayAvailable) windowVisible = false else controller.quit()
            }

            Window(
                onCloseRequest = close,
                title = "Plume",
                // One column of rows has no second layout to widen into; resizing only stretches it.
                resizable = false,
                // The system rounds the top corners and leaves the bottom square. Matching all four
                // means drawing the frame ourselves, which needs the corners to be transparent.
                undecorated = rounded,
                transparent = rounded,
                state = state,
            ) {
                // Not the `icon` parameter: one bitmap gets resampled by the taskbar, blurry. AWT
                // picks the right size from a set. Reapplied on `windowOpened` because X11 reads
                // the icon when the peer is created, after this effect runs.
                //
                // The dock entry is taken then too, and not a moment earlier. Changing the macOS
                // activation policy makes the menu bar and Dock appear, which invalidates the very
                // screen metrics AWT queries while realising a window — putting an AppKit redisplay
                // and a blocked event thread on a collision course.
                DisposableEffect(window) {
                    window.iconImages = windowIcons
                    val opened = object : WindowAdapter() {
                        override fun windowOpened(event: WindowEvent) {
                            window.iconImages = windowIcons
                            DockPresence.windowShown()
                        }
                    }
                    window.addWindowListener(opened)
                    // Compose shows the window during composition, so on a reopen `windowOpened`
                    // can already be past by the time the listener is added. Safe here precisely
                    // because a showing window means the peer is made.
                    if (window.isShowing) DockPresence.windowShown()
                    onDispose {
                        window.removeWindowListener(opened)
                        DockPresence.windowHidden()
                    }
                }

                // On macOS an accessory app's window opens behind the active app with no focus, so
                // the Dock activation has to happen before `toFront` means anything.
                LaunchedEffect(openRequests) { raiseWindow(window, state) }

                PlumeTheme(mode = theme) {
                    val settingsUi = @Composable {
                        Box(modifier = Modifier.fillMaxSize()) {
                            DesktopSettingsWindow(
                                controller = controller,
                                settings = loaded,
                                history = history,
                                trayAvailable = trayAvailable,
                                onQuit = controller::quit,
                            )
                            ScrollAffordance()
                        }
                    }

                    if (rounded) {
                        RoundedWindowFrame(onClose = close, content = settingsUi)
                    } else {
                        settingsUi()
                    }
                }
            }
        }
    }
}

/**
 * `-[CGLLayer drawInCGLContext:]` has no exception handler, so a Java exception during a layer draw
 * becomes an uncatchable `NSException`. Metal is the default from JDK 19; this asks for it on 17.
 */
private fun useMetalOnMacOs() {
    if (DesktopOs.current != DesktopOs.MacOs) return
    if (System.getProperty("sun.java2d.metal") == null) {
        System.setProperty("sun.java2d.metal", "true")
    }
}

/**
 * `WindowPosition.Aligned` calls `Toolkit.getScreenInsets`, which macOS answers by blocking the
 * event thread on AppKit, uncached (JBR-2602). Bounds need no such round trip; the cost is
 * ignoring the menu bar, worth a few pixels.
 */
internal fun centredPosition(
    size: DpSize,
    screen: Rectangle = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    }.getOrDefault(Rectangle(0, 0, 1920, 1080)),
): WindowPosition.Absolute = WindowPosition.Absolute(
    x = (screen.x + (screen.width - size.width.value) / 2).coerceAtLeast(0f).dp,
    y = (screen.y + (screen.height - size.height.value) / 2).coerceAtLeast(0f).dp,
)

/**
 * Fixed size for the non-resizable settings window. Height is capped against the screen: a window
 * that cannot resize and does not fit would sit with its bottom off the desktop.
 */
internal fun settingsWindowSize(
    screenHeight: Int = runCatching { Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(1080),
): DpSize = DpSize(485.dp, (screenHeight - 160).coerceIn(520, 660).dp)

/**
 * No Revise or Translate item on purpose: opening the menu steals focus from the window the user
 * was working in, so there would be no selection left to act on. Uses the desktop status-notifier
 * protocol, not `compose.ui.window.Tray`, whose `java.awt.PopupMenu` ignores the GTK theme.
 */
@Composable
private fun ApplicationScope.PlumeTray(
    outcome: ActionOutcome,
    settings: AppSettings?,
    onOpen: () -> Unit,
    onQuit: () -> Unit,
) {
    Tray(
        // The bright teal, at rest and while working alike. Deriving a darker teal for light panels
        // reads as the theme's primary green and loses the leaf; this one keeps its shape on both.
        icon = remember { PlumeMark.vector() },
        tint = PlumeTint,
        // Render properties stay at their default: it resamples to the panel's real size, whereas
        // `withoutScalingAndAliasing` would hand over the full 192px image.
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

/** The theme's `InkTealBright`. Working is said in the tooltip, not in the colour. */
private val PlumeTint = Color(0xFF7FD1C4)

/** AWT reports this honestly, and it is false on a stock GNOME desktop. */
private fun isTraySupported(): Boolean =
    runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
