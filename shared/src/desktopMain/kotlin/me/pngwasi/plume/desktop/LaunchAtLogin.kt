package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import java.io.File

/**
 * Starting Plume with the session.
 *
 * The shortcuts only work while Plume is running, so for most people this is the difference between
 * a tool that works and one they have to remember to launch. Each platform has its own mechanism
 * and none of them needs elevated rights when scoped to the current user.
 *
 * The launcher path is resolved from the running process rather than assumed: jpackage installs to
 * different places per platform, and a hard-coded path silently stops working after a move.
 */
object LaunchAtLogin {

    /** True when the entry exists, which is what the settings toggle should reflect. */
    fun isEnabled(): Boolean = runCatching {
        when (DesktopOs.current) {
            DesktopOs.Linux -> linuxEntry().exists()
            DesktopOs.MacOs -> macEnabled()
            DesktopOs.Windows -> windowsQuery()
        }
    }.getOrDefault(false)

    /** Returns false when it could not be applied, so the UI can avoid claiming otherwise. */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        when (DesktopOs.current) {
            DesktopOs.Linux -> if (enabled) writeLinux() else linuxEntry().delete().let { true }
            DesktopOs.MacOs -> if (enabled) writeMac() else removeMac()
            DesktopOs.Windows -> if (enabled) writeWindows() else removeWindows()
        }
    }.getOrDefault(false)

    /**
     * The installed launcher, not the JVM.
     *
     * `jpackage.app-path` is set by the jpackage launcher and points at the real executable. Under
     * Gradle it is absent, and there is no launcher to register — reporting that honestly beats
     * writing an entry that would try to start a JVM with no classpath.
     */
    private fun launcherPath(): String? = AppRelaunch.launcherPath()

    /** What the settings screen and the log should say when the toggle cannot work. */
    fun diagnostics(): String {
        val raw = System.getProperty("jpackage.app-path")
        return when {
            raw == null -> "unavailable (not a packaged build)"
            !File(raw).exists() -> "unavailable (no launcher at $raw)"
            else -> "available (${launcherPath()})"
        }
    }

    private fun autostartDir(): File {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".config").path
        return File(config, "autostart")
    }

    private fun linuxEntry() = File(autostartDir(), "plume.desktop")

    private fun writeLinux(): Boolean {
        val path = launcherPath() ?: return false
        autostartDir().mkdirs()
        linuxEntry().writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Plume
            Comment=AI revision and translation
            Exec=${quoteForDesktopEntry(path)}
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n",
        )
        return true
    }

    /**
     * The XDG spec reserves a set of characters in `Exec`; a path containing any of them has to be
     * quoted, and the quotes themselves escaped. An unquoted path with a space silently fails.
     */
    internal fun quoteForDesktopEntry(path: String): String {
        val reserved = " \t\n\"'\\><~|&;$*?#()`"
        if (path.none { it in reserved }) return path
        val escaped = path
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("`", "\\`")
            .replace("$", "\\$")
        return "\"$escaped\""
    }

    /**
     * The `.app`, not the executable inside it.
     *
     * A LaunchAgent pointing at `Plume.app/Contents/MacOS/Plume` starts the binary rather than the
     * bundle: it gets no bundle identity, and Accessibility is granted per bundle — so the
     * permission the user already gave would not apply to the copy that starts at login.
     */
    internal fun macAppBundle(executable: String): String = AppRelaunch.macAppBundle(executable)

    private fun macName(bundle: String) = File(bundle).name.removeSuffix(".app")

    private fun writeMac(): Boolean {
        val bundle = macAppBundle(launcherPath() ?: return false)
        // "Open at Login", the same list System Settings shows, so the user can see and undo it.
        return osascript(
            "tell application \"System Events\" to make new login item with properties " +
                "{path:\"$bundle\", hidden:false} at end",
        )
    }

    private fun removeMac(): Boolean {
        if (!macEnabled()) return true
        val bundle = macAppBundle(launcherPath() ?: return false)
        return osascript(
            "tell application \"System Events\" to delete login item \"${macName(bundle)}\"",
        )
    }

    private fun macEnabled(): Boolean {
        val bundle = launcherPath()?.let(::macAppBundle) ?: return false
        val listed = run("osascript", "-e", "tell application \"System Events\" to get the name of every login item")
        return listed?.contains(macName(bundle)) == true
    }

    private fun osascript(script: String): Boolean = run("osascript", "-e", script) != null

    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val RUN_NAME = "Plume"

    /**
     * Written through a `.reg` file rather than `reg add /d`.
     *
     * The stored value has to keep its surrounding quotes, or Windows guesses at a path containing
     * spaces — `C:\Program Files\Plume\Plume.exe` would first be tried as `C:\Program`. Passing
     * literal quotes as an argument through `ProcessBuilder` on Windows is unreliable, since Java
     * re-quotes the command line on the way out. A file has no command line to mangle.
     */
    internal fun registryFile(executable: String): String {
        val escaped = executable.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            Windows Registry Editor Version 5.00

            [HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run]
            "$RUN_NAME"="\"$escaped\""
        """.trimIndent() + "\n"
    }

    private fun writeWindows(): Boolean {
        val path = launcherPath() ?: return false
        val file = File.createTempFile("plume-autostart", ".reg")
        return try {
            file.writeText(registryFile(path))
            run("reg", "import", file.absolutePath) != null
        } finally {
            file.delete()
        }
    }

    // `reg delete` fails when the value is not there, and so does the macOS equivalent. Turning
    // off something already off is not an error, and reporting one makes the toggle look broken.
    private fun removeWindows(): Boolean {
        if (!windowsQuery()) return true
        return run("reg", "delete", RUN_KEY, "/v", RUN_NAME, "/f") != null
    }

    private fun windowsQuery(): Boolean = run("reg", "query", RUN_KEY, "/v", RUN_NAME) != null

    /** Output on success, null when the command is missing or exits non-zero. */
    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
}
