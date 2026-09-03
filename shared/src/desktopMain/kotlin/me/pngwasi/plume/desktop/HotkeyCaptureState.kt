package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.normaliseHotkey

/**
 * The state behind a shortcut-recording field.
 *
 * Kept out of the composable so the ordering rules can be tested. They are not obvious, and one of
 * them was wrong: clearing the pressed keys when recording ended meant that clicking Save — which
 * moves focus off the field, ending the recording — handled the click against an already-empty
 * combination. The shortcut appeared to record and then saved nothing.
 */
class HotkeyCaptureState {

    var recording: Boolean = false
        private set

    var pressed: HotkeyRecorder = HotkeyRecorder()
        private set

    var error: String? = null
        private set

    fun start() {
        pressed = HotkeyRecorder()
        error = null
        recording = true
    }

    /**
     * Ends recording but keeps what was pressed, so a click that arrives just after focus moved
     * away still has a combination to save.
     */
    fun stop() {
        recording = false
    }

    fun cancel() {
        error = null
        pressed = HotkeyRecorder()
        recording = false
    }

    fun press(
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
        key: String? = null,
    ) {
        var next = pressed.withModifiers(ctrl, alt, shift, meta)
        if (key != null) next = next.withKey(key)
        pressed = next
        error = null
    }

    /**
     * Returns the binding to store, or null when it cannot be saved — in which case [error] says
     * why, in the words the field shows.
     */
    fun save(otherBindings: List<String>): String? {
        val candidate = pressed
        if (!candidate.isValid()) {
            error = "Hold a modifier and press a key"
            return null
        }
        val formatted = candidate.format()
        val clash = otherBindings.any {
            it.isNotBlank() && normaliseHotkey(it) == normaliseHotkey(formatted)
        }
        if (clash) {
            error = "Another action already uses $formatted"
            return null
        }
        error = null
        recording = false
        pressed = HotkeyRecorder()
        return formatted
    }
}
