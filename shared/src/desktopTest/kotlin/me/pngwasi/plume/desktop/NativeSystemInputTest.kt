package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Freeing a native handle twice is not an exception — the Rust side rebuilds a `Box` from the
 * pointer, so the second free hands back malloc memory that is no longer ours and macOS aborts the
 * process. Quitting, closing the window and restarting all shut the controller down, so being
 * closed twice is ordinary rather than exotic.
 */
class NativeSystemInputTest {

    @Test
    fun `closing twice frees each handle once`() {
        val library = CountingNativeLibrary()
        val input = NativeSystemInput(library)

        input.close()
        input.close()
        input.close()

        assertEquals(1, library.clipboardFrees)
        assertEquals(1, library.simulatorFrees)
    }

    /** A late hotkey action must not reach a handle that has already been given back. */
    @Test
    fun `work after closing is dropped rather than reaching a freed handle`() {
        val library = CountingNativeLibrary()
        val input = NativeSystemInput(library)
        input.close()

        assertFalse(input.setClipboardText("anything"))
        assertNull(library.lastSetText)
    }

    @Test
    fun `the hotkey listener frees its manager once as well`() {
        val library = CountingNativeLibrary()
        val service = HotkeyService(library) {}

        service.close()
        service.close()

        assertEquals(1, library.hotkeyFrees)
    }
}
