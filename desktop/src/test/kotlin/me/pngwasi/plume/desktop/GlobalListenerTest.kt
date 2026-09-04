package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Shortcuts screen reports what the capture field is doing, not what the listener should do.
 *
 * Reading it the other way round is how opening that screen came to switch every shortcut off: the
 * screen says "nothing is recording" as it appears, and that was taken as "stop listening".
 */
class GlobalListenerTest {

    @Test
    fun `a recording field owns the keyboard, so the listener steps aside`() {
        assertFalse(globalListenerRuns(recordingAShortcut = true))
    }

    @Test
    fun `nothing recording means the shortcuts work`() {
        assertTrue(globalListenerRuns(recordingAShortcut = false))
    }
}
