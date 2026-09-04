package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the "General" row says it holds.
 *
 * The one setting behind it that can leave someone unable to reach Plume again is "keep running
 * when the window closes", so turning it off has to be visible from the home screen rather than
 * discovered by closing the window and finding nothing left.
 */
class GeneralSubtitleTest {

    private val defaults = DesktopSettings()

    @Test
    fun `nothing unusual reads as a plain summary`() {
        assertEquals(
            "Startup, tray and notifications",
            generalSubtitle(defaults, trayAvailable = true),
        )
    }

    @Test
    fun `closing quitting Plume is said on the row`() {
        val subtitle = generalSubtitle(defaults.copy(closeToTray = false), trayAvailable = true)

        assertTrue(subtitle.contains("Closing quits Plume"), subtitle)
    }

    /** With no tray there is nowhere to close to, whatever the stored setting says. */
    @Test
    fun `a desktop without a tray says so instead`() {
        val subtitle = generalSubtitle(defaults.copy(closeToTray = false), trayAvailable = false)

        assertEquals("No tray on this desktop", subtitle)
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
