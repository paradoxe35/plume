package me.pngwasi.plume.desktop

import androidx.compose.ui.input.key.Key
import me.pngwasi.plume.data.DesktopOs

/**
 * A shortcut as it is being pressed.
 *
 * Typing a binding into a text field is asking the user to know that Plume calls the Windows key
 * `win` on Windows, `super` on Linux and `cmd` on macOS. Recording what they actually press removes
 * the guesswork, and it is what MyReviser does.
 *
 * The rules here are MyReviser's, because a binding recorded in one has to mean the same thing to
 * the Rust listener in the other — it is the same listener.
 */
data class HotkeyRecorder(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val keys: List<String> = emptyList(),
) {

    val isEmpty: Boolean get() = !ctrl && !alt && !shift && !meta && keys.isEmpty()

    fun withModifiers(ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean) =
        copy(ctrl = ctrl, alt = alt, shift = shift, meta = meta)

    /**
     * Adds a non-modifier key, up to [MAX_KEYS].
     *
     * Repeats are ignored rather than appended: holding a key fires it repeatedly, which would
     * otherwise fill the combination with copies of itself.
     */
    fun withKey(key: String): HotkeyRecorder {
        val normalised = key.lowercase()
        if (normalised in keys || keys.size >= MAX_KEYS) return this
        return copy(keys = keys + normalised)
    }

    /**
     * The binding string the Rust listener parses.
     *
     * The order is fixed — ctrl, alt, shift, super, then keys — so that the same combination always
     * produces the same string and can be compared for duplicates.
     */
    fun format(os: DesktopOs = DesktopOs.current): String = buildList {
        if (ctrl) add("ctrl")
        if (alt) add(altName(os))
        if (shift) add("shift")
        if (meta) add(superName(os))
        addAll(keys)
    }.joinToString("+")

    /**
     * A binding needs a modifier, and then either a key or one of the modifier-only combinations
     * the listener recognises. Without a modifier it would fire on ordinary typing.
     */
    fun isValid(): Boolean {
        val hasModifier = ctrl || alt || shift || meta
        if (!hasModifier) return false
        if (keys.isNotEmpty()) return true
        return isModifierOnlyAllowed()
    }

    /**
     * Ctrl together with the platform's command key. MyReviser allows this one pair and no other:
     * it is the combination that is comfortable to hold and is not already a system shortcut.
     */
    fun isModifierOnlyAllowed(): Boolean = ctrl && meta && !alt && !shift

    companion object {
        /** Matching MyReviser, which caps a combination at three non-modifier keys. */
        const val MAX_KEYS = 3

        fun altName(os: DesktopOs = DesktopOs.current) =
            if (os == DesktopOs.MacOs) "option" else "alt"

        fun superName(os: DesktopOs = DesktopOs.current) = when (os) {
            DesktopOs.MacOs -> "cmd"
            DesktopOs.Windows -> "win"
            DesktopOs.Linux -> "super"
        }

        /**
         * The name the Rust listener knows a key by, or null for one it cannot match.
         *
         * Keyed on the Compose [Key] and never on its label. `Key.toString()` is
         * `KeyEvent.getKeyText(nativeKeyCode)`, which is localised and platform-specific: macOS
         * answers `\u2423` for the space bar, a French system answers `Espace`. Recording the
         * macOS revise-everything default that way saved `ctrl+option+\u2423` — a binding that
         * reads correctly in the field, registers, and can never fire.
         */
        fun keyName(key: Key): String? = KEY_NAMES[key]

        /** Every name a recorded binding can contain, to check against the listener matching them. */
        fun keyNames(): Set<String> = KEY_NAMES.values.toSet()

        private val KEY_NAMES: Map<Key, String> = mapOf(
            Key.A to "a", Key.B to "b", Key.C to "c", Key.D to "d", Key.E to "e",
            Key.F to "f", Key.G to "g", Key.H to "h", Key.I to "i", Key.J to "j",
            Key.K to "k", Key.L to "l", Key.M to "m", Key.N to "n", Key.O to "o",
            Key.P to "p", Key.Q to "q", Key.R to "r", Key.S to "s", Key.T to "t",
            Key.U to "u", Key.V to "v", Key.W to "w", Key.X to "x", Key.Y to "y",
            Key.Z to "z",
            Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4",
            Key.Five to "5", Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9",
            Key.F1 to "f1", Key.F2 to "f2", Key.F3 to "f3", Key.F4 to "f4", Key.F5 to "f5",
            Key.F6 to "f6", Key.F7 to "f7", Key.F8 to "f8", Key.F9 to "f9", Key.F10 to "f10",
            Key.F11 to "f11", Key.F12 to "f12",
            Key.Spacebar to "space",
            Key.Enter to "return",
            Key.NumPadEnter to "return",
            Key.Escape to "escape",
            Key.Tab to "tab",
            Key.Backspace to "backspace",
            Key.Delete to "delete",
            Key.Insert to "insert",
            Key.MoveHome to "home",
            Key.MoveEnd to "end",
            Key.PageUp to "pageup",
            Key.PageDown to "pagedown",
            Key.DirectionLeft to "left",
            Key.DirectionRight to "right",
            Key.DirectionUp to "up",
            Key.DirectionDown to "down",
        )
    }
}
