package me.pngwasi.plume.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay

/**
 * Brings the settings window forward when the tray asks for it.
 *
 * macOS ignores `toFront` from a menu-bar app while another is frontmost
 * (compose-multiplatform#4231); only AppKit activation works, and it lands asynchronously, so the
 * window must be raised after it. Elsewhere focus-stealing prevention ignores a bare `toFront`,
 * which going briefly always-on-top gets around.
 */
suspend fun raiseWindow(window: ComposeWindow, state: WindowState) {
    state.isMinimized = false

    // Not while the peer is still being made. Activating then is what put an AppKit redisplay and a
    // blocked event thread on a collision course; the window announces itself when it is ready.
    var waited = 0
    while (!window.isShowing && waited < ATTEMPTS) {
        delay(ACTIVATION_SETTLE)
        waited++
    }

    if (MacDock.isSupported) {
        MacDock.showInDock()
        repeat(ATTEMPTS) {
            delay(ACTIVATION_SETTLE)
            window.toFront()
            window.requestFocus()
            if (window.isFocused) return
        }
        return
    }

    // Windows refuses SetForegroundWindow while another app is in use and flashes the taskbar
    // button instead; making the window briefly always-on-top is the documented way to make it
    // reconsider. Toggled once around the retries rather than per attempt, which flickers.
    val wasAlwaysOnTop = window.isAlwaysOnTop
    runCatching { window.isAlwaysOnTop = true }
    try {
        repeat(ATTEMPTS) {
            runCatching {
                window.toFront()
                window.requestFocus()
            }
            if (window.isFocused) return
            delay(ACTIVATION_SETTLE)
        }
    } finally {
        // Restored whatever happened, or the window hovers over everything afterwards.
        runCatching { window.isAlwaysOnTop = wasAlwaysOnTop }
    }
}

private const val ACTIVATION_SETTLE = 120L

/** Reported as working about half the time on macOS, so one attempt is not enough. */
private const val ATTEMPTS = 3
