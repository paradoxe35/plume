package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning the toggle on has to leave a file the session manager will actually read.
 *
 * The toggle reports success or failure from [LaunchAtLogin.setEnabled], so a mechanism that
 * quietly does nothing is the one failure the UI cannot show — which is why this drives the real
 * thing rather than a seam.
 */
class LaunchAtLoginLinuxTest {

    private val linux = DesktopOs.current == DesktopOs.Linux && System.getenv("XDG_CONFIG_HOME") == null

    private lateinit var home: File
    private lateinit var launcher: File
    private var originalHome: String? = null

    @BeforeTest
    fun setUp() {
        if (!linux) return
        home = File.createTempFile("plume-home", "").apply { delete(); mkdirs() }
        launcher = File(home, "bin/Plume").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n") }
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", home.path)
        System.setProperty("jpackage.app-path", launcher.path)
    }

    @AfterTest
    fun tearDown() {
        if (!linux) return
        originalHome?.let { System.setProperty("user.home", it) }
        System.clearProperty("jpackage.app-path")
        home.deleteRecursively()
    }

    private val entry get() = File(home, ".config/autostart/plume.desktop")

    @Test
    fun `enabling writes an autostart entry that points at the launcher`() {
        if (!linux) return

        assertTrue(LaunchAtLogin.setEnabled(true), "setEnabled reported failure")

        assertTrue(entry.exists(), "no entry at ${entry.path}")
        val text = entry.readText()
        assertTrue(text.contains("Exec=${launcher.canonicalPath}"), text)
        assertTrue(text.startsWith("[Desktop Entry]"), text)
        assertTrue(text.contains("Type=Application"), text)
        assertTrue(LaunchAtLogin.isEnabled(), "written, but not reported as enabled")
    }

    @Test
    fun `disabling removes it again`() {
        if (!linux) return
        LaunchAtLogin.setEnabled(true)

        assertTrue(LaunchAtLogin.setEnabled(false), "setEnabled(false) reported failure")

        assertFalse(entry.exists(), "the entry survived")
        assertFalse(LaunchAtLogin.isEnabled())
    }

    /** Without a packaged launcher there is nothing to register, and saying so beats a dead entry. */
    @Test
    fun `an unpackaged build reports that it cannot`() {
        if (!linux) return
        System.clearProperty("jpackage.app-path")

        assertFalse(LaunchAtLogin.setEnabled(true))
        assertFalse(entry.exists())
        assertTrue(LaunchAtLogin.diagnostics().startsWith("unavailable"), LaunchAtLogin.diagnostics())
    }
}
