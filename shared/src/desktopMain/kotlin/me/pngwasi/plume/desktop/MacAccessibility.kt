package me.pngwasi.plume.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import me.pngwasi.plume.data.DesktopOs

/**
 * Whether macOS will let Plume see key presses from other applications.
 *
 * Without Accessibility the listener starts, reports no error, and receives nothing — so the app
 * looks like it is working and every shortcut does nothing. This was previously assumed granted,
 * which meant the settings screen said "Shortcuts are active" to someone whose shortcuts were dead.
 */
object MacAccessibility {

    private interface ApplicationServices : Library {
        fun AXIsProcessTrusted(): Boolean
    }

    private val framework: ApplicationServices? by lazy {
        if (DesktopOs.current != DesktopOs.MacOs) null
        else runCatching { Native.load("ApplicationServices", ApplicationServices::class.java) }
            .getOrNull()
    }

    /**
     * True when granted, and true when it cannot be determined.
     *
     * An unreachable framework is not evidence of a missing permission, and warning about one that
     * was granted would send the user to a settings pane where Plume is already switched on.
     */
    fun isTrusted(): Boolean {
        val services = framework ?: return true
        return runCatching { services.AXIsProcessTrusted() }.getOrDefault(true)
    }

    /** Opens the pane the instruction names, so the user is not left hunting for it. */
    fun openSettings(): Boolean = runCatching {
        ProcessBuilder(
            "open",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
        ).start().waitFor() == 0
    }.getOrDefault(false)
}
