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
     * Arranges for a fresh copy to start once this one is gone, and returns immediately. The caller
     * exits; the copy waits for that to happen first.
     *
     * The waiting is the point. Starting the replacement straight away leaves two Plumes alive at
     * once — two tray icons, two listeners fighting over the same shortcuts — and the new one would
     * meet the old one's instance lock and stop again. So the launch is handed to a small detached
     * shell that polls for this process to disappear.
     *
     * macOS is given the bundle rather than the binary inside it: launching the executable directly
     * gives it no bundle identity, and Accessibility is granted per bundle — so the new copy would
     * not hold the permission the user just granted, which is the whole reason for restarting.
     */
    fun relaunch(): Boolean {
        val path = launcherPath() ?: return false
        val command = relaunchCommand(DesktopOs.current, ProcessHandle.current().pid(), path)
        return runCatching {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
    }

    internal fun relaunchCommand(os: DesktopOs, pid: Long, path: String): List<String> = when (os) {
        DesktopOs.MacOs -> afterExit(pid, "open", macAppBundle(path))
        DesktopOs.Linux -> afterExit(pid, "exec", path)
        // No `sh`, and `cmd` has no wait-for-pid. A single quote is written by doubling it, so a
        // path containing one cannot end the string and become PowerShell of its own.
        DesktopOs.Windows -> listOf(
            "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
            "Wait-Process -Id $pid -ErrorAction SilentlyContinue; " +
                "Start-Process -FilePath '${path.replace("'", "''")}'",
        )
    }

    /**
     * `sh` waiting on [pid] before running `launch target`.
     *
     * The path travels as an argument rather than inside the script, so a space or a quote in it
     * cannot become shell syntax. The loop gives up after twenty seconds: a process that has not
     * exited by then is wedged, and coming back is better than never coming back.
     */
    internal fun afterExit(pid: Long, launch: String, target: String): List<String> = listOf(
        "sh",
        "-c",
        "n=0; while kill -0 \"\$1\" 2>/dev/null && [ \$n -lt 100 ]; do sleep 0.2; n=\$((n+1)); done; " +
            "$launch \"\$2\"",
        "sh",
        pid.toString(),
        target,
    )

    /** `Plume.app`, given `Plume.app/Contents/MacOS/Plume`. */
    internal fun macAppBundle(executable: String): String {
        val marker = ".app/Contents/MacOS/"
        val index = executable.indexOf(marker)
        return if (index >= 0) executable.substring(0, index + ".app".length) else executable
    }
}
