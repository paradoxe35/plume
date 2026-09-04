package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An unquoted XDG `Exec` line is silently ignored by the session manager, so an install path with
 * a space in it makes "start with the system" do nothing.
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

    /**
     * macOS grants Accessibility to a bundle. Starting the executable inside it at login would run
     * something the user never granted anything to, and the shortcuts would silently do nothing.
     */
    @Test
    fun `the login item is the app bundle rather than the binary inside it`() {
        assertEquals(
            "/Applications/Plume.app",
            LaunchAtLogin.macAppBundle("/Applications/Plume.app/Contents/MacOS/Plume"),
        )
    }

    @Test
    fun `a path that is not in a bundle is left as it is`() {
        assertEquals("/usr/local/bin/plume", LaunchAtLogin.macAppBundle("/usr/local/bin/plume"))
    }

    /**
     * The Run value must keep its own quotes: unquoted, Windows tries `C:\\Program` first for a
     * path under Program Files, and starts nothing.
     */
    @Test
    fun `the registry value is quoted and its backslashes doubled`() {
        val quote = "\""
        val slash = "\\"
        val expected = quote + "Plume" + quote + "=" + quote + slash + quote +
            "C:" + slash + slash + "Program Files" + slash + slash + "Plume.exe" +
            slash + quote + quote

        val reg = LaunchAtLogin.registryFile("C:" + slash + "Program Files" + slash + "Plume.exe")

        assertTrue(reg.contains(expected), "expected\n$expected\ngot\n$reg")
    }

    @Test
    fun `the registry file names the per-user Run key`() {
        val reg = LaunchAtLogin.registryFile("C:/Plume.exe")

        assertTrue(reg.startsWith("Windows Registry Editor Version 5.00"), reg)
        assertTrue(reg.contains("""[HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run]"""), reg)
    }
}
