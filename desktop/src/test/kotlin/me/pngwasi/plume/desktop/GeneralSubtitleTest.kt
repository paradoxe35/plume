package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the "General" row says it holds, so it does not read as an unexplained "General". */
class GeneralSubtitleTest {

    private val defaults = DesktopSettings()

    @Test
    fun `nothing unusual reads as a plain summary`() {
        assertEquals(
            "Startup, tray and notifications",
            generalSubtitle(defaults, trayAvailable = true),
        )
    }

    /** With no tray there is nowhere to close to, and closing the window has to quit. */
    @Test
    fun `a desktop without a tray says so`() {
        assertEquals("No tray on this desktop", generalSubtitle(defaults, trayAvailable = false))
    }

    @Test
    fun `starting with the system is worth mentioning`() {
        val subtitle = generalSubtitle(defaults.copy(startOnLogin = true), trayAvailable = true)

        assertTrue(subtitle.contains("Starts with the system"), subtitle)
    }

    /** The default has to be off, or a first launch appears to do nothing at all. */
    @Test
    fun `Plume does not start hidden out of the box`() {
        assertEquals(false, DesktopSettings().startMinimised)
    }
}
