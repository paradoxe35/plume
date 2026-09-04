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
    repeat(ATTEMPTS) {
        if (window.isShowing) return@repeat
        delay(ACTIVATION_SETTLE)
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

    repeat(ATTEMPTS) {
        val wasAlwaysOnTop = window.isAlwaysOnTop
        runCatching {
            window.isAlwaysOnTop = true
            window.toFront()
            window.requestFocus()
        }
        // Put back, or the window hovers over everything afterwards.
        runCatching { window.isAlwaysOnTop = wasAlwaysOnTop }
        if (window.isFocused) return
        delay(ACTIVATION_SETTLE)
    }
}

private const val ACTIVATION_SETTLE = 120L

/** Reported as working about half the time on macOS, so one attempt is not enough. */
private const val ATTEMPTS = 3
