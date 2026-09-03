package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The XDG `Exec` quoting rule.
 *
 * jpackage installs to `/opt/plume`, which is harmless, but a user-local install under a home
 * directory with a space in it is not: an unquoted `Exec` line is silently ignored by the session
 * manager, and the symptom is "start with the system does nothing".
 */
class LaunchAtLoginTest {

    @Test
    fun `a plain path is left alone`() {
        assertEquals("/opt/plume/bin/Plume", LaunchAtLogin.quoteForDesktopEntry("/opt/plume/bin/Plume"))
    }

    @Test
    fun `a path with a space is quoted`() {
        assertEquals(
            "\"/home/ada lovelace/Plume\"",
            LaunchAtLogin.quoteForDesktopEntry("/home/ada lovelace/Plume"),
        )
    }

    @Test
    fun `reserved characters are escaped inside the quotes`() {
        val dollar = '$'

        assertEquals(
            "\"/opt/a\\${dollar}b/Plume\"",
            LaunchAtLogin.quoteForDesktopEntry("/opt/a${dollar}b/Plume"),
        )
    }

    /** Backslashes have to be doubled first, or the escaping of everything else is wrong. */
    @Test
    fun `a backslash is doubled before other escapes are applied`() {
        assertEquals(
            """"C:\\Program Files\\Plume"""",
            LaunchAtLogin.quoteForDesktopEntry("""C:\Program Files\Plume"""),
        )
    }
}
