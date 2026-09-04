package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recording a shortcut from real key presses.
 *
 * The formatting rules are MyReviser's, and they have to stay MyReviser's: the same Rust listener
 * parses the result, so a different order or a different name for the Windows key produces a
 * binding that saves cleanly and then never fires.
 */
class HotkeyRecorderTest {

    private fun recorder(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        keys: List<String> = emptyList(),
    ) = HotkeyRecorder(ctrl, alt, shift, meta, keys)

    /** Fixed order, or the same combination would produce two different strings. */
    @Test
    fun `modifiers come out in a fixed order regardless of press order`() {
        val combination = recorder(ctrl = true, alt = true, shift = true, keys = listOf("r"))

        assertEquals("ctrl+alt+shift+r", combination.format(DesktopOs.Linux))
    }

    @Test
    fun `the key comes last`() {
        assertEquals("ctrl+alt+space", recorder(ctrl = true, alt = true, keys = listOf("space")).format(DesktopOs.Linux))
    }

    @Test
    fun `the command key is named per platform`() {
        val combination = recorder(ctrl = true, meta = true)

        assertEquals("ctrl+super", combination.format(DesktopOs.Linux))
        assertEquals("ctrl+win", combination.format(DesktopOs.Windows))
        assertEquals("ctrl+cmd", combination.format(DesktopOs.MacOs))
    }

    @Test
    fun `alt is option on macOS`() {
        val combination = recorder(alt = true, ctrl = true, keys = listOf("t"))

        assertEquals("ctrl+alt+t", combination.format(DesktopOs.Linux))
        assertEquals("ctrl+option+t", combination.format(DesktopOs.MacOs))
    }

    @Test
    fun `a modifier and a key is valid`() {
        assertTrue(recorder(ctrl = true, keys = listOf("r")).isValid())
    }

    @Test
    fun `a key with no modifier would fire on ordinary typing`() {
        assertFalse(recorder(keys = listOf("r")).isValid())
    }

    @Test
    fun `nothing pressed is not valid`() {
        assertFalse(recorder().isValid())
    }

    /** MyReviser allows exactly this pair and no other modifier-only combination. */
    @Test
    fun `ctrl with the command key is the one allowed modifier-only binding`() {
        assertTrue(recorder(ctrl = true, meta = true).isValid())
    }

    @Test
    fun `other modifier-only combinations are rejected`() {
        assertFalse(recorder(ctrl = true, alt = true).isValid())
        assertFalse(recorder(ctrl = true, shift = true).isValid())
        assertFalse(recorder(alt = true, meta = true).isValid())
        assertFalse(recorder(ctrl = true).isValid())
    }

    @Test
    fun `ctrl and command with anything else added is no longer modifier-only`() {
        assertFalse(recorder(ctrl = true, meta = true, shift = true).isModifierOnlyAllowed())
    }

    /** Holding a key repeats it; the combination must not fill with copies. */
    @Test
    fun `a repeated key is only recorded once`() {
        val combination = recorder(ctrl = true).withKey("r").withKey("r").withKey("R")

        assertEquals(listOf("r"), combination.keys)
    }

    @Test
    fun `no more than three keys are recorded`() {
        val combination = recorder(ctrl = true)
            .withKey("a").withKey("b").withKey("c").withKey("d")

        assertEquals(3, combination.keys.size)
        assertFalse(combination.keys.contains("d"))
    }

    @Test
    fun `keys are lowercased so case cannot split one binding into two`() {
        assertEquals("ctrl+r", recorder(ctrl = true).withKey("R").format(DesktopOs.Linux))
    }

    @Test
    fun `an empty recorder reports itself empty`() {
        assertTrue(recorder().isEmpty)
        assertFalse(recorder(ctrl = true).isEmpty)
    }

    /** These have to match what the Rust listener parses, not what Compose calls them. */
    @Test
    fun `compose key labels map to the names the listener expects`() {
        assertEquals("space", HotkeyRecorder.keyName("Spacebar"))
        assertEquals("return", HotkeyRecorder.keyName("Enter"))
        assertEquals("escape", HotkeyRecorder.keyName("Escape"))
        assertEquals("pageup", HotkeyRecorder.keyName("Page Up"))
        assertEquals("left", HotkeyRecorder.keyName("Left Arrow"))
    }

    @Test
    fun `an ordinary letter keeps its own name`() {
        assertEquals("r", HotkeyRecorder.keyName("R"))
        assertEquals("f1", HotkeyRecorder.keyName("F1"))
    }

    /** Compose prefixes some labels; the listener would not recognise "key r". */
    @Test
    fun `the compose key prefix is stripped`() {
        assertEquals("r", HotkeyRecorder.keyName("Key R"))
    }

    /** What is recorded has to pass the validation the settings screen applies to typed input. */
    @Test
    fun `a recorded binding passes the stored-binding validation`() {
        val recorded = recorder(ctrl = true, alt = true).withKey("t").format(DesktopOs.Linux)

        assertEquals(null, me.pngwasi.plume.data.validateHotkey(recorded))
    }

    @Test
    fun `a recorded modifier-only binding also passes validation`() {
        val recorded = recorder(ctrl = true, meta = true).format(DesktopOs.Linux)

        assertEquals(null, me.pngwasi.plume.data.validateHotkey(recorded))
    }
}
