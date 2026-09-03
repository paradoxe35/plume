package me.pngwasi.plume.data

import java.io.File

/** Which desktop we are on. Nearly everything platform-specific keys off this. */
enum class DesktopOs { Linux, MacOs, Windows;

    companion object {
        val current: DesktopOs by lazy {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            when {
                name.contains("win") -> Windows
                name.contains("mac") || name.contains("darwin") -> MacOs
                else -> Linux
            }
        }
    }
}

/**
 * Where Plume keeps its configuration, following each platform's own convention rather than
 * dropping a dotfile in the home directory.
 */
fun plumeConfigDirectory(): String {
    val dir = when (DesktopOs.current) {
        DesktopOs.Windows ->
            File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Plume")

        DesktopOs.MacOs ->
            File(System.getProperty("user.home"), "Library/Application Support/Plume")

        DesktopOs.Linux -> {
            val xdg = System.getenv("XDG_CONFIG_HOME")
            if (xdg.isNullOrBlank()) {
                File(System.getProperty("user.home"), ".config/plume")
            } else {
                File(xdg, "plume")
            }
        }
    }
    dir.mkdirs()
    return dir.absolutePath
}

/** Session type, which decides how hotkeys can be captured on Linux. */
fun isWaylandSession(): Boolean {
    if (DesktopOs.current != DesktopOs.Linux) return false
    val sessionType = System.getenv("XDG_SESSION_TYPE")?.lowercase()
    if (sessionType == "wayland") return true
    if (sessionType == "x11") return false
    return !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
}
