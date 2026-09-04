package me.pngwasi.plume.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hotkey validation.
 *
 * A bad binding fails the same silent way a missing permission does — nothing happens when the keys
 * are pressed — so it is worth catching in the editor, where the cause is still obvious.
 */
class HotkeySettingsTest {

    @Test
    fun `a modifier plus a key is valid`() {
        assertNull(validateHotkey("ctrl+alt+r"))
        assertNull(validateHotkey("ctrl+space"))
    }

    /** Modifier-only combinations work and MyReviser shipped them, so they stay valid. */
    @Test
    fun `two modifiers are valid`() {
        assertNull(validateHotkey("ctrl+super"))
        assertNull(validateHotkey("ctrl+win"))
    }

    @Test
    fun `a single key would fire on ordinary typing`() {
        assertNotNull(validateHotkey("r"))
        assertNotNull(validateHotkey("space"))
    }

    @Test
    fun `a binding with no modifier is rejected`() {
        assertNotNull(validateHotkey("a+b"))
    }

    @Test
    fun `an empty binding is rejected`() {
        assertNotNull(validateHotkey(""))
        assertNotNull(validateHotkey("   "))
    }

    @Test
    fun `the same key listed twice is rejected`() {
        assertNotNull(validateHotkey("ctrl+ctrl"))
    }

    /** Two actions on one binding means one of them never fires, with nothing to say why. */
    @Test
    fun `two actions sharing a binding are reported`() {
        val duplicates = duplicateHotkeys(listOf("ctrl+alt+r", "ctrl+alt+t", "ctrl+alt+r"))

        assertEquals(setOf("alt+ctrl+r"), duplicates)
    }

    @Test
    fun `order and case do not disguise a collision`() {
        val duplicates = duplicateHotkeys(listOf("ctrl+alt+r", "ALT+Ctrl+R"))

        assertTrue(duplicates.isNotEmpty())
    }

    @Test
    fun `distinct bindings do not collide`() {
        assertTrue(duplicateHotkeys(listOf("ctrl+alt+r", "ctrl+alt+t")).isEmpty())
    }

    @Test
    fun `unset bindings do not collide with each other`() {
        assertTrue(duplicateHotkeys(listOf("", "", "ctrl+alt+r")).isEmpty())
    }

    @Test
    fun `an unset binding falls back to the platform default`() {
        val defaults = HotkeyDefaults("ctrl+super", "ctrl+alt+space", "ctrl+alt+t")
        val settings = DesktopSettings()

        assertEquals("ctrl+super", settings.reviseSelectionOrDefault(defaults))
        assertEquals("ctrl+alt+space", settings.reviseAllOrDefault(defaults))
        assertEquals("ctrl+alt+t", settings.translateSelectionOrDefault(defaults))
    }

    @Test
    fun `a set binding wins over the default`() {
        val defaults = HotkeyDefaults("ctrl+super", "ctrl+alt+space", "ctrl+alt+t")
        val settings = DesktopSettings(reviseSelection = "ctrl+shift+r")

        assertEquals("ctrl+shift+r", settings.reviseSelectionOrDefault(defaults))
    }

    /** Desktop settings ride in the shared document, so they must round-trip with everything else. */
    @Test
    fun `desktop settings survive a save and load`() {
        val settings = AppSettings(
            desktop = DesktopSettings(
                reviseSelection = "ctrl+shift+r",
                startOnLogin = true,
                notifyOnFinish = false,
            ),
        )

        val json = SettingsSerializer.json.encodeToString(AppSettings.serializer(), settings)
        val restored = SettingsSerializer.json.decodeFromString(AppSettings.serializer(), json)

        assertEquals(settings.desktop, restored.desktop)
    }

    /** Settings written before the desktop existed must still load. */
    @Test
    fun `a document without a desktop section loads with defaults`() {
        val restored = SettingsSerializer.json.decodeFromString(
            AppSettings.serializer(),
            """{"defaultProvider":"openai"}""",
        )

        assertEquals(DesktopSettings(), restored.desktop)
    }
}
