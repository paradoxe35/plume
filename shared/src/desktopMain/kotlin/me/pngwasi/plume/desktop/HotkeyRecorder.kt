package me.pngwasi.plume.desktop

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
         * Names the Rust listener expects for keys that are not a single character.
         *
         * Compose reports these with its own labels, and a mismatch here means a binding that
         * saves cleanly and then never fires.
         */
        fun keyName(composeKeyLabel: String): String = when (val key = composeKeyLabel.lowercase()) {
            "spacebar", "space" -> "space"
            "enter", "return", "numpad enter" -> "return"
            "escape", "esc" -> "escape"
            "tab" -> "tab"
            "backspace" -> "backspace"
            "delete", "forward delete" -> "delete"
            "insert" -> "insert"
            "home" -> "home"
            "end" -> "end"
            "page up" -> "pageup"
            "page down" -> "pagedown"
            "left arrow", "left" -> "left"
            "right arrow", "right" -> "right"
            "up arrow", "up" -> "up"
            "down arrow", "down" -> "down"
            else -> key.removePrefix("key ").trim()
        }
    }
}
