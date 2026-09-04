package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `plume_hotkey_start` only spawns the listener thread; the system refuses the key tap afterwards,
 * on that thread. Reporting "Active" then is what makes a dead shortcut read as a wrong binding.
 */
class ShortcutSubtitleTest {

    @Test
    fun `a refused listener outranks a granted permission`() {
        assertEquals(
            "Not listening",
            shortcutSubtitle(HotkeyAvailability.Ready, rejected = emptyList(), listenerError = "no"),
        )
    }

    @Test
    fun `a listening Plume with every binding registered says so`() {
        assertEquals(
            "Active",
            shortcutSubtitle(HotkeyAvailability.Ready, rejected = emptyList(), listenerError = null),
        )
    }

    @Test
    fun `bindings the system took are still worth naming`() {
        assertEquals(
            "Some shortcuts were refused",
            shortcutSubtitle(HotkeyAvailability.Ready, listOf("ctrl+alt+g"), listenerError = null),
        )
    }

    @Test
    fun `a missing permission is reported before anything else`() {
        assertEquals(
            "Waiting on permissions",
            shortcutSubtitle(
                HotkeyAvailability.NeedsPermission("needs", "grant"),
                rejected = emptyList(),
                listenerError = null,
            ),
        )
    }
}
