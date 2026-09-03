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
            DesktopOs.MacOs -> macAgent().exists()
            DesktopOs.Windows -> windowsQuery()
        }
    }.getOrDefault(false)

    /** Returns false when it could not be applied, so the UI can avoid claiming otherwise. */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        when (DesktopOs.current) {
            DesktopOs.Linux -> if (enabled) writeLinux() else linuxEntry().delete().let { true }
            DesktopOs.MacOs -> if (enabled) writeMac() else macAgent().delete().let { true }
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
    private fun launcherPath(): String? =
        System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    // --- Linux: an XDG autostart entry ---------------------------------------------------------

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

    // --- macOS: a LaunchAgent ------------------------------------------------------------------

    private fun macAgent() = File(
        System.getProperty("user.home"),
        "Library/LaunchAgents/me.pngwasi.plume.plist",
    )

    private fun writeMac(): Boolean {
        val path = launcherPath() ?: return false
        macAgent().parentFile?.mkdirs()
        macAgent().writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>me.pngwasi.plume</string>
                <key>ProgramArguments</key>
                <array>
                    <string>${path.replace("&", "&amp;").replace("<", "&lt;")}</string>
                </array>
                <key>RunAtLoad</key>
                <true/>
            </dict>
            </plist>
            """.trimIndent() + "\n",
        )
        return true
    }

    // --- Windows: the per-user Run key ---------------------------------------------------------

    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val RUN_NAME = "Plume"

    private fun writeWindows(): Boolean {
        val path = launcherPath() ?: return false
        return reg("add", RUN_KEY, "/v", RUN_NAME, "/t", "REG_SZ", "/d", "\"$path\"", "/f")
    }

    private fun removeWindows(): Boolean = reg("delete", RUN_KEY, "/v", RUN_NAME, "/f")

    private fun windowsQuery(): Boolean = reg("query", RUN_KEY, "/v", RUN_NAME)

    private fun reg(vararg args: String): Boolean = runCatching {
        val process = ProcessBuilder(listOf("reg") + args)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)
}
