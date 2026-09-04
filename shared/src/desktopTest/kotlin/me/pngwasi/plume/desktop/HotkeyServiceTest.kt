package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The listener is suspended while a shortcut is being recorded, so suspend-and-resume is ordinary
 * rather than exotic — and a resume that never reaches the listener leaves every shortcut dead with
 * nothing on screen saying so.
 */
class HotkeyServiceTest {

    @Test
    fun `resuming reaches the listener again`() {
        val library = CountingNativeLibrary()
        val service = HotkeyService(library) {}

        assertTrue(service.start())
        service.stop()
        assertTrue(service.start())

        assertEquals(2, library.hotkeyStarts)
        assertEquals(1, library.hotkeyStops)
    }

    /** Starting an already-running listener must not reach the native side a second time. */
    @Test
    fun `starting twice is one start`() {
        val library = CountingNativeLibrary()
        val service = HotkeyService(library) {}

        service.start()
        service.start()

        assertEquals(1, library.hotkeyStarts)
    }

    @Test
    fun `stopping something that never started does nothing`() {
        val library = CountingNativeLibrary()

        HotkeyService(library) {}.stop()

        assertEquals(0, library.hotkeyStops)
    }
}
