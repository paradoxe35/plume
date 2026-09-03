package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Building the notification commands.
 *
 * The text in a notification is a model's output, so it is untrusted in the ordinary sense: it can
 * contain quotes, newlines, and anything else the user happened to select. On Linux that is safe by
 * construction because the arguments never touch a shell; on macOS the script is a string, so the
 * escaping has to be right.
 */
class SystemNotifierTest {

    // --- shortening ----------------------------------------------------------------------------

    @Test
    fun `a short message is left alone`() {
        assertEquals("Revise done", shortenForNotification("Revise done"))
    }

    @Test
    fun `newlines are collapsed so the notification stays one line`() {
        assertEquals("one two three", shortenForNotification("one\n two\n\nthree"))
    }

    @Test
    fun `a long message is truncated with an ellipsis`() {
        val result = shortenForNotification("x".repeat(500))

        assertEquals(160, result.length)
        assertTrue(result.endsWith("…"))
    }

    // --- AppleScript escaping ------------------------------------------------------------------

    @Test
    fun `a plain string is quoted`() {
        assertEquals("\"hello\"", appleScriptLiteral("hello"))
    }

    /** An unescaped quote would end the literal and leave the rest to be interpreted. */
    @Test
    fun `quotes are escaped`() {
        assertEquals("\"say \\\"hi\\\"\"", appleScriptLiteral("say \"hi\""))
    }

    @Test
    fun `backslashes are escaped before anything else`() {
        assertEquals("\"a\\\\b\"", appleScriptLiteral("a\\b"))
    }

    /** The classic injection shape: close the string, then run something else. */
    @Test
    fun `an attempt to break out of the literal is neutralised`() {
        val hostile = "\" & (do shell script \"echo pwned\") & \""

        val literal = appleScriptLiteral(hostile)
        val inner = literal.substring(1, literal.length - 1)

        // Every quote from the payload survives only in escaped form, so the literal opens and
        // closes exactly once and nothing in between is interpreted.
        assertFalse(Regex("""(?<!\\)"""").containsMatchIn(inner))
    }

    @Test
    fun `newlines cannot end the statement`() {
        val literal = appleScriptLiteral("first\nsecond")

        assertFalse(literal.contains("\n"))
    }

    // --- command shapes ------------------------------------------------------------------------

    /** `--` is what stops a body starting with a dash being read as an option. */
    @Test
    fun `notify-send separates options from the text`() {
        val command = notifySendCommand("Plume", "-- not an option", NotificationLevel.Info, null)

        val separator = command.indexOf("--")
        assertTrue(separator > 0)
        assertEquals("Plume", command[separator + 1])
        assertEquals("-- not an option", command[separator + 2])
    }

    @Test
    fun `notify-send carries the icon when there is one`() {
        val command = notifySendCommand("Plume", "done", NotificationLevel.Info, "/opt/plume/icon.png")

        assertTrue(command.contains("--icon=/opt/plume/icon.png"))
    }

    @Test
    fun `notify-send omits the icon rather than passing an empty one`() {
        val command = notifySendCommand("Plume", "done", NotificationLevel.Info, null)

        assertTrue(command.none { it.startsWith("--icon") })
    }

    /** A failure is worth interrupting for; a success is not. */
    @Test
    fun `errors are sent at a higher urgency than successes`() {
        val error = notifySendCommand("Plume", "x", NotificationLevel.Error, null)
        val info = notifySendCommand("Plume", "x", NotificationLevel.Info, null)

        assertTrue(error.contains("--urgency=normal"))
        assertTrue(info.contains("--urgency=low"))
    }

    @Test
    fun `the dbus call carries the full Notify signature`() {
        val command = gdbusNotifyCommand("Plume", "done", null)

        assertEquals("org.freedesktop.Notifications.Notify", command[command.indexOf("--method") + 1])
        // actions, hints, timeout — the trailing three arguments the signature requires.
        assertEquals(listOf("[]", "{}", "-1"), command.takeLast(3))
    }

    @Test
    fun `the applescript command is a single display notification statement`() {
        val command = osascriptCommand("Plume", "done")

        assertEquals(listOf("osascript", "-e"), command.take(2))
        assertEquals("display notification \"done\" with title \"Plume\"", command[2])
    }

    // --- routing -------------------------------------------------------------------------------

    @Test
    fun `linux tries libnotify first and falls back to dbus`() {
        val attempts = mutableListOf<String>()
        val notifier = PlatformNotifier(DesktopOs.Linux, null) { command ->
            attempts += command.first()
            false
        }

        assertFalse(notifier.notify("Plume", "done", NotificationLevel.Info))
        assertEquals(listOf("notify-send", "gdbus"), attempts)
    }

    @Test
    fun `dbus is not tried when libnotify worked`() {
        val attempts = mutableListOf<String>()
        val notifier = PlatformNotifier(DesktopOs.Linux, null) { command ->
            attempts += command.first()
            true
        }

        assertTrue(notifier.notify("Plume", "done", NotificationLevel.Info))
        assertEquals(listOf("notify-send"), attempts)
    }

    @Test
    fun `macOS goes to the Notification Center`() {
        val attempts = mutableListOf<String>()
        val notifier = PlatformNotifier(DesktopOs.MacOs, null) { command ->
            attempts += command.first()
            true
        }

        assertTrue(notifier.notify("Plume", "done", NotificationLevel.Info))
        assertEquals(listOf("osascript"), attempts)
    }

    /** Windows already gets a native balloon through the tray, so this route declines. */
    @Test
    fun `windows defers to the tray`() {
        var called = false
        val notifier = PlatformNotifier(DesktopOs.Windows, null) { called = true; true }

        assertFalse(notifier.notify("Plume", "done", NotificationLevel.Info))
        assertFalse(called)
    }

    @Test
    fun `the body is shortened before it reaches the command`() {
        var body = ""
        val notifier = PlatformNotifier(DesktopOs.Linux, null) { command ->
            body = command.last()
            true
        }

        notifier.notify("Plume", "y".repeat(400), NotificationLevel.Info)

        assertEquals(160, body.length)
    }
}
