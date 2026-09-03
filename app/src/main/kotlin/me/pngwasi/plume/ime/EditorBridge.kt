package me.pngwasi.plume.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * What the panel sees of the field it is attached to.
 *
 * The distinction that matters is [hasSelection]: with a selection the user has told us what to
 * work on, and without one they mean the whole message. Everything else follows from that.
 */
data class EditorText(
    val full: String,
    val selection: String,
) {
    val hasSelection: Boolean get() = selection.isNotBlank()

    /** The text an action should operate on. */
    val target: String get() = if (hasSelection) selection else full

    val isEmpty: Boolean get() = target.isBlank()
}

/**
 * Narrow view of the focused text field.
 *
 * The panel's logic talks to this rather than to [InputConnection] directly, which keeps the state
 * machine testable without an Android editor behind it.
 */
interface EditorBridge {
    fun read(): EditorText?

    /** Replaces the selection when there is one, otherwise the entire field. */
    fun apply(text: String): Boolean

    /** Empties the field regardless of what is selected. */
    fun clearAll(): Boolean
}

/**
 * [EditorBridge] over a live [InputConnection].
 *
 * The connection is fetched through a provider rather than held, because it is invalidated every
 * time the input target changes and a stale reference silently writes into nothing.
 */
class InputConnectionBridge(
    private val connection: () -> InputConnection?,
) : EditorBridge {

    override fun read(): EditorText? {
        val ic = connection() ?: return null
        val selection = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull().orEmpty()
        val full = readFull(ic) ?: return null
        return EditorText(full = full, selection = selection)
    }

    override fun apply(text: String): Boolean {
        val ic = connection() ?: return false
        val current = read() ?: return false

        ic.beginBatchEdit()
        try {
            // A composing region left by the previous keyboard would otherwise swallow the edit.
            ic.finishComposingText()
            if (current.hasSelection) {
                // commitText replaces the current selection.
                ic.commitText(text, 1)
            } else {
                val length = current.full.length
                ic.setSelection(length, length)
                if (length > 0) ic.deleteSurroundingText(length, 0)
                ic.commitText(text, 1)
            }
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    override fun clearAll(): Boolean {
        val ic = connection() ?: return false
        val length = read()?.full?.length ?: return false
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            ic.setSelection(length, length)
            if (length > 0) ic.deleteSurroundingText(length, 0)
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    /**
     * `getExtractedText` is the only call that returns the whole field, but editors are free to
     * return null for it. Reading around the cursor is the documented fallback.
     */
    private fun readFull(ic: InputConnection): String? {
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        extracted?.text?.let { return it.toString() }

        val before = runCatching { ic.getTextBeforeCursor(MAX_FIELD_CHARS, 0) }.getOrNull()?.toString().orEmpty()
        val after = runCatching { ic.getTextAfterCursor(MAX_FIELD_CHARS, 0) }.getOrNull()?.toString().orEmpty()
        if (before.isEmpty() && after.isEmpty()) return ""
        return "$before$after"
    }

    private companion object {
        /** Generous enough for any message, bounded so a huge document cannot stall the IME. */
        const val MAX_FIELD_CHARS = 20_000
    }
}
