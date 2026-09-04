package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import me.pngwasi.plume.data.normaliseHotkey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Defaults have to survive contact with the desktop they run on.
 *
 * `ctrl+alt+t` shipped as the translate default and opened a terminal on GNOME instead — the kind
 * of failure that looks like a broken app rather than a taken shortcut.
 */
class HotkeyDefaultsTest {

    /**
     * Combinations the desktop itself claims, which no application can win.
     *
     * VS Code's `ctrl+alt+c` and `ctrl+alt+f` are here too. `ctrl+alt+space` is deliberately not:
     * VS Code only binds it while the suggestion popup is open, and MyReviser has shipped it as
     * the revise-everything default on all three systems for long enough to settle the question.
     */
    private val taken = listOf(
        "ctrl+alt+t", "ctrl+alt+d", "ctrl+alt+l", "ctrl+alt+delete", "ctrl+alt+tab",
        "ctrl+alt+left", "ctrl+alt+right", "ctrl+alt+up", "ctrl+alt+down",
        "ctrl+alt+c", "ctrl+alt+f",
    ).map(::normaliseHotkey)

    private fun defaults(os: DesktopOs) = hotkeyDefaultsFor(os).let {
        listOf(it.reviseSelection, it.reviseAll, it.translateSelection)
    }

    @Test
    fun `no default is a shortcut another application already owns`() {
        DesktopOs.entries.forEach { os ->
            defaults(os).forEach { binding ->
                assertFalse(
                    normaliseHotkey(binding) in taken,
                    "$os ships $binding, which something else already claims",
                )
            }
        }
    }

    @Test
    fun `every system gets three distinct bindings`() {
        DesktopOs.entries.forEach { os ->
            val bindings = defaults(os).map(::normaliseHotkey)
            assertEquals(bindings.size, bindings.toSet().size, "$os repeats a binding")
        }
    }

    /** A binding the recorder cannot produce is one the user can never restore after changing it. */
    @Test
    fun `every default is one the recorder would accept`() {
        DesktopOs.entries.forEach { os ->
            defaults(os).forEach { binding ->
                assertTrue(
                    me.pngwasi.plume.data.validateHotkey(binding) == null,
                    "$os ships $binding, which the editor would reject: " +
                        me.pngwasi.plume.data.validateHotkey(binding),
                )
            }
        }
    }

    /** macOS says option and command; showing "alt" there would name a key that is not on the board. */
    @Test
    fun `macOS defaults use its own modifier names`() {
        val mac = defaults(DesktopOs.MacOs)

        assertTrue(mac.none { it.contains("alt") }, "macOS should say option: $mac")
        assertTrue(mac.none { it.contains("super") || it.contains("win") }, "macOS says cmd: $mac")
    }

    /**
     * Revise is MyReviser's, unchanged. It works, people have muscle memory for it, and "this
     * might clash" is not a good enough reason to move a binding that has been proven in use.
     */
    @Test
    fun `revise keeps the bindings MyReviser shipped`() {
        with(hotkeyDefaultsFor(DesktopOs.Linux)) {
            assertEquals("ctrl+super", reviseSelection)
            assertEquals("ctrl+alt+space", reviseAll)
        }
        with(hotkeyDefaultsFor(DesktopOs.Windows)) {
            assertEquals("ctrl+win", reviseSelection)
            assertEquals("ctrl+alt+space", reviseAll)
        }
        with(hotkeyDefaultsFor(DesktopOs.MacOs)) {
            assertEquals("ctrl+cmd", reviseSelection)
            assertEquals("ctrl+option+space", reviseAll)
        }
    }

    /** Three keys, like revise. A four-key chord is a shortcut people stop reaching for. */
    @Test
    fun `no default asks for more than three keys`() {
        DesktopOs.entries.forEach { os ->
            defaults(os).forEach { binding ->
                val keys = binding.split("+").size
                assertTrue(keys <= 3, "$os ships $binding, which is $keys keys")
            }
        }
    }
}
