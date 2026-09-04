package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import java.io.File

/**
 * Where the installed launcher is, and how to start it again.
 *
 * `jpackage.app-path` is set by the jpackage launcher and points at the real executable. Under
 * Gradle it is absent and there is nothing to relaunch, which is reported rather than guessed at.
 */
object AppRelaunch {

    /** Canonical, because the deb puts a `plume` symlink on PATH that an upgrade will move. */
    fun launcherPath(): String? =
        System.getProperty("jpackage.app-path")
            ?.takeIf { File(it).exists() }
            ?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }

    val isSupported: Boolean get() = launcherPath() != null

    /**
     * Starts a second copy and leaves it running. The caller shuts this one down.
     *
     * macOS gets `open -n` on the bundle rather than the binary inside it: launching the executable
     * directly gives it no bundle identity, and Accessibility is granted per bundle — so the new
     * copy would not hold the permission the user just granted, which is the whole reason for
     * restarting.
     */
    fun relaunch(): Boolean {
        val path = launcherPath() ?: return false
        val command = when (DesktopOs.current) {
            DesktopOs.MacOs -> listOf("open", "-n", macAppBundle(path))
            else -> listOf(path)
        }
        return runCatching { ProcessBuilder(command).start(); true }.getOrDefault(false)
    }

    /** `Plume.app`, given `Plume.app/Contents/MacOS/Plume`. */
    internal fun macAppBundle(executable: String): String {
        val marker = ".app/Contents/MacOS/"
        val index = executable.indexOf(marker)
        return if (index >= 0) executable.substring(0, index + ".app".length) else executable
    }
}
