package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import java.io.File
import java.util.concurrent.TimeUnit

/** How loudly to say it. Failures are worth interrupting for; successes are not. */
enum class NotificationLevel { Info, Error }

/**
 * Desktop notifications, through whatever the desktop actually uses.
 *
 * AWT's `TrayIcon.displayMessage` — which is what Compose's `TrayState.sendNotification` calls —
 * draws Java's own balloon on Linux rather than going through the desktop's notification service.
 * It looks foreign, ignores the user's do-not-disturb setting, and disappears on its own schedule.
 *
 * So Linux goes through libnotify or D-Bus, macOS through the Notification Center, and Windows
 * through a shell notification balloon. Nothing here depends on the tray: Plume's tray is the
 * desktop's own status-notifier item, which has no notification API of its own.
 */
interface SystemNotifier {
    /** False when this route is unavailable, so the caller can fall back to the tray. */
    fun notify(title: String, body: String, level: NotificationLevel): Boolean
}

/**
 * Notification bodies are one or two lines. A whole translated paragraph turns the notification
 * into a wall of text that covers the screen and tells the user nothing they cannot see already.
 */
internal fun shortenForNotification(text: String, limit: Int = 160): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    if (collapsed.length <= limit) return collapsed
    return collapsed.take(limit - 1).trimEnd() + "…"
}

/**
 * Escapes a string for embedding in an AppleScript literal.
 *
 * The text here is a model's output, so it is genuinely untrusted: a stray quote would break the
 * script, and a crafted one would let it run something else.
 */
internal fun appleScriptLiteral(text: String): String {
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        // Newlines end a statement in AppleScript; a notification is one line anyway.
        .replace("\n", " ")
        .replace("\r", " ")
    return "\"$escaped\""
}

/** libnotify. Arguments are passed as a list, so nothing goes through a shell. */
internal fun notifySendCommand(
    title: String,
    body: String,
    level: NotificationLevel,
    iconPath: String?,
): List<String> = buildList {
    add("notify-send")
    add("--app-name=Plume")
    add(if (level == NotificationLevel.Error) "--urgency=normal" else "--urgency=low")
    iconPath?.let { add("--icon=$it") }
    // Everything after this is data, and `--` stops a body that begins with a dash being read
    // as an option.
    add("--")
    add(title)
    add(body)
}

/**
 * The Notifications service directly, for desktops that have the service but not the CLI.
 *
 * The trailing arguments are the signature's: actions (empty), hints (empty), and a timeout in
 * milliseconds, with -1 meaning the server decides.
 */
internal fun gdbusNotifyCommand(
    title: String,
    body: String,
    iconPath: String?,
): List<String> = listOf(
    "gdbus", "call", "--session",
    "--dest", "org.freedesktop.Notifications",
    "--object-path", "/org/freedesktop/Notifications",
    "--method", "org.freedesktop.Notifications.Notify",
    "Plume", "0", iconPath ?: "dialog-information",
    title, body, "[]", "{}", "-1",
)

internal fun osascriptCommand(title: String, body: String): List<String> = listOf(
    "osascript", "-e",
    "display notification ${appleScriptLiteral(body)} with title ${appleScriptLiteral(title)}",
)

/**
 * Escapes a string for a single-quoted PowerShell literal, where a quote is doubled rather than
 * backslash-escaped.
 */
internal fun powerShellLiteral(text: String): String =
    "'" + text.replace("'", "''").replace("\n", " ").replace("\r", " ") + "'"

/**
 * A shell notification balloon, which Windows turns into a toast.
 *
 * `NotifyIcon` is part of Windows itself, so this needs nothing installed — unlike the newer toast
 * API, which wants a registered AppUserModelID or it attributes the notification to PowerShell.
 * The icon has to stay alive briefly for the balloon to appear, hence the sleep.
 */
internal fun windowsNotifyCommand(
    title: String,
    body: String,
    level: NotificationLevel,
): List<String> {
    val icon = if (level == NotificationLevel.Error) "Error" else "Info"
    val script = buildString {
        // The dollars are PowerShell's, not Kotlin's, hence the escaping.
        append("[reflection.assembly]::LoadWithPartialName('System.Windows.Forms')|Out-Null;")
        append("\$n=New-Object System.Windows.Forms.NotifyIcon;")
        append("\$n.Icon=[System.Drawing.SystemIcons]::$icon;")
        append("\$n.Visible=\$true;")
        append("\$n.ShowBalloonTip(6000,${powerShellLiteral(title)},${powerShellLiteral(body)},")
        append("[System.Windows.Forms.ToolTipIcon]::$icon);")
        append("Start-Sleep -Seconds 7;\$n.Dispose()")
    }
    return listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
}

/**
 * Picks a route once and remembers whether it worked.
 *
 * [iconPath] is the installed application icon where jpackage put it, so the notification carries
 * Plume's mark rather than a generic one.
 */
class PlatformNotifier(
    private val os: DesktopOs = DesktopOs.current,
    private val iconPath: String? = installedIconPath(),
    private val run: (List<String>) -> Boolean = ::runCommand,
    private val spawn: (List<String>) -> Boolean = ::spawnCommand,
) : SystemNotifier {

    override fun notify(title: String, body: String, level: NotificationLevel): Boolean {
        val short = shortenForNotification(body)
        return when (os) {
            DesktopOs.Linux ->
                run(notifySendCommand(title, short, level, iconPath)) ||
                    run(gdbusNotifyCommand(title, short, iconPath))

            DesktopOs.MacOs -> run(osascriptCommand(title, short))

            // Fire and forget: the balloon only shows while the icon lives, so the script outlives
            // the call by design and waiting for it would block the action that produced it.
            DesktopOs.Windows -> spawn(windowsNotifyCommand(title, short, level))
        }
    }
}

/** Where jpackage leaves the icon, so a notification can show Plume's mark. */
internal fun installedIconPath(): String? {
    val resources = System.getProperty("compose.application.resources.dir")
        ?: return sequenceOf("/opt/plume/lib/Plume.png")
            .firstOrNull { File(it).isFile }
    return File(resources, "Plume.png").takeIf { it.isFile }?.absolutePath
}

/** Starts a command without waiting for it, for one that is meant to outlive the call. */
private fun spawnCommand(command: List<String>): Boolean = runCatching {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    true
}.getOrDefault(false)

private fun runCommand(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    // A notification daemon that hangs must not hang the action that produced it.
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching false
    }
    process.exitValue() == 0
}.getOrDefault(false)
