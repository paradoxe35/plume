package me.pngwasi.plume.desktop

/** What a capture attempt found, kept distinct so the user gets an accurate message. */
sealed interface Capture {
    data class Text(val value: String) : Capture

    /** The copy landed but there was nothing selected. */
    data object NothingSelected : Capture

    /** The copy never took effect: no permission, a slow app, or a compositor that refused. */
    data object CopyFailed : Capture

    data class Failed(val reason: String) : Capture
}

/**
 * Borrowing the clipboard to read the user's selection, and giving it back.
 *
 * This is the part MyReviser got wrong, and every mistake in it is invisible until it destroys
 * something. The rules here:
 *
 * The clipboard is **cleared before the copy**, so "the copy landed" is observable. Sleeping and
 * then reading meant a failed copy returned the *previous* clipboard contents, which were then
 * revised and pasted over the user's selection — text replaced with a correction of something
 * else entirely.
 *
 * Held modifiers are **released first**, because the hotkey that triggered this is still down and
 * Ctrl+A with Alt held is a different shortcut.
 *
 * The clipboard is **verified before pasting** and restored only after the paste has had time to
 * be served, since on X11 and Wayland a paste is a negotiated transfer rather than an instant one.
 */
class TextCapture(
    private val input: SystemInput,
    private val timing: Timing = Timing(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * Deliberately polled rather than fixed: a fast app finishes in a few milliseconds and a slow
     * one gets the time it needs, where a single sleep has to be wrong in one direction or other.
     */
    data class Timing(
        val pollIntervalMillis: Long = 15,
        val copyTimeoutMillis: Long = 900,
        val pasteSettleMillis: Long = 220,
    )

    /** Captures the current selection without disturbing it. */
    fun captureSelection(): Capture = capture(selectAllFirst = false)

    /** Selects the whole field first, for "revise everything I have typed". */
    fun captureAll(): Capture = capture(selectAllFirst = true)

    private fun capture(selectAllFirst: Boolean): Capture {
        if (!input.saveClipboard()) return Capture.Failed("Could not read the clipboard.")
        input.releaseModifiers()

        // The sentinel is absence: anything on the clipboard afterwards came from this copy.
        if (!input.clearClipboard()) {
            input.restoreClipboard()
            return Capture.Failed("Could not use the clipboard.")
        }

        if (selectAllFirst && !input.selectAll()) {
            input.restoreClipboard()
            return Capture.Failed("Could not select the text.")
        }

        if (!input.copy()) {
            input.restoreClipboard()
            return Capture.Failed("Could not copy the selection.")
        }

        val copied = awaitClipboard()
        if (copied == null) {
            input.restoreClipboard()
            // An empty selection and a refused copy are indistinguishable from here, and telling
            // the user the honest ambiguity beats guessing.
            return if (selectAllFirst) Capture.CopyFailed else Capture.NothingSelected
        }
        if (copied.isBlank()) {
            input.restoreClipboard()
            return Capture.NothingSelected
        }
        return Capture.Text(copied)
    }

    /**
     * Writes [text] over the selection, then puts the clipboard back.
     *
     * The selection is still active from the capture, so pasting replaces it.
     */
    fun replaceSelection(text: String): Boolean {
        if (!input.setClipboardText(text)) {
            input.restoreClipboard()
            return false
        }

        // Confirm the clipboard really holds our text before pressing paste, or a slow write means
        // pasting whatever was there before.
        if (!awaitClipboardEquals(text)) {
            input.restoreClipboard()
            return false
        }

        input.releaseModifiers()
        if (!input.paste()) {
            input.restoreClipboard()
            return false
        }

        // The paste is asynchronous: restoring immediately can make the target app receive the
        // *old* contents, which is how a correction silently turns into whatever was copied before.
        sleep(timing.pasteSettleMillis)
        input.restoreClipboard()
        return true
    }

    /** Puts the clipboard back after an action that failed part-way through. */
    fun abandon() {
        input.restoreClipboard()
    }

    private fun awaitClipboard(): String? = poll { input.clipboardText() }

    private fun awaitClipboardEquals(text: String): Boolean =
        poll { input.clipboardText()?.takeIf { it == text } } != null

    private fun poll(read: () -> String?): String? {
        var waited = 0L
        while (true) {
            read()?.let { return it }
            if (waited >= timing.copyTimeoutMillis) return null
            sleep(timing.pollIntervalMillis)
            waited += timing.pollIntervalMillis
        }
    }
}
