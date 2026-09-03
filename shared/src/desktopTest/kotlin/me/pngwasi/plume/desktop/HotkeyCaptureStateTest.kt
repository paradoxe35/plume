package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recording field's state machine.
 *
 * Most of this is ordering, and ordering is what went wrong: clicking Save moves focus off the
 * field, which ends the recording, and the click was then handled against an empty combination.
 * The shortcut recorded on screen and saved nothing.
 */
class HotkeyCaptureStateTest {

    private fun recorded(): HotkeyCaptureState = HotkeyCaptureState().apply {
        start()
        press(ctrl = true, alt = true, shift = false, meta = false, key = "t")
    }

    @Test
    fun `recording collects modifiers and a key`() {
        val state = recorded()

        assertTrue(state.recording)
        assertEquals("ctrl+alt+t", state.pressed.format(me.pngwasi.plume.data.DesktopOs.Linux))
    }

    /**
     * The bug. Clicking Save moves focus off the field, which ends the recording — so the
     * combination has to survive that, or the click saves nothing.
     */
    @Test
    fun `a save after focus loss produces the recorded binding`() {
        val state = recorded()

        state.stop()

        assertFalse(state.recording)
        assertEquals("ctrl+alt+t", state.save(emptyList()))
    }

    @Test
    fun `starting a new recording clears the previous keys`() {
        val state = recorded()
        state.stop()

        state.start()

        assertTrue(state.pressed.isEmpty)
    }

    @Test
    fun `cancelling discards what was pressed`() {
        val state = recorded()

        state.cancel()

        assertTrue(state.pressed.isEmpty)
        assertFalse(state.recording)
        assertNull(state.error)
    }

    // --- refusing a save -----------------------------------------------------------------------

    @Test
    fun `a combination with no modifier is refused with a reason`() {
        val state = HotkeyCaptureState().apply {
            start()
            press(ctrl = false, alt = false, shift = false, meta = false, key = "t")
        }

        assertNull(state.save(emptyList()))
        assertEquals("Hold a modifier and press a key", state.error)
    }

    @Test
    fun `saving nothing is refused`() {
        val state = HotkeyCaptureState().apply { start() }

        assertNull(state.save(emptyList()))
        assertNotNull(state.error)
    }

    /** Two actions on one binding means one of them silently never fires. */
    @Test
    fun `a binding another action already uses is refused`() {
        val state = recorded()

        val saved = state.save(listOf("ctrl+alt+t"))

        assertNull(saved)
        assertEquals("Another action already uses ctrl+alt+t", state.error)
    }

    @Test
    fun `a clash is detected regardless of the order the keys were written in`() {
        val state = recorded()

        assertNull(state.save(listOf("alt+ctrl+t")))
    }

    @Test
    fun `an unset binding does not count as a clash`() {
        val state = recorded()

        assertEquals("ctrl+alt+t", state.save(listOf("", "  ")))
    }

    @Test
    fun `a successful save clears the field for next time`() {
        val state = recorded()

        state.save(emptyList())

        assertTrue(state.pressed.isEmpty)
        assertFalse(state.recording)
        assertNull(state.error)
    }

    // --- the error clears ----------------------------------------------------------------------

    @Test
    fun `pressing again clears a previous error`() {
        val state = HotkeyCaptureState().apply { start() }
        state.save(emptyList())
        assertNotNull(state.error)

        state.press(ctrl = true, alt = false, shift = false, meta = false, key = "r")

        assertNull(state.error)
    }

    /** Ctrl with the command key is the one modifier-only binding the listener accepts. */
    @Test
    fun `the allowed modifier-only combination saves`() {
        val state = HotkeyCaptureState().apply {
            start()
            press(ctrl = true, alt = false, shift = false, meta = true)
        }

        assertNotNull(state.save(emptyList()))
    }
}
