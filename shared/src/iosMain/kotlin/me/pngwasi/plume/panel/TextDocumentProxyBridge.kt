package me.pngwasi.plume.panel

import platform.UIKit.UITextDocumentProxy

/**
 * [EditorBridge] over a keyboard extension's [UITextDocumentProxy].
 *
 * iOS gives a keyboard a far narrower view than Android's `InputConnection`: there is no way to ask
 * for the whole field, only for the text either side of the cursor, and the system may truncate
 * that at a paragraph boundary. So "the whole field" here means "as much as iOS will admit to",
 * which is why the panel's selection case is the one that behaves identically across platforms.
 *
 * Deleting is per character with no batching, so replacing a long field is a loop. It is bounded to
 * keep a runaway from locking up the keyboard process, which iOS kills without ceremony.
 */
class TextDocumentProxyBridge(
    private val proxy: () -> UITextDocumentProxy?,
) : EditorBridge {

    override fun read(): EditorText? {
        val p = proxy() ?: return null
        val before = p.documentContextBeforeInput.orEmpty()
        val after = p.documentContextAfterInput.orEmpty()
        val selection = p.selectedText.orEmpty()
        return EditorText(full = before + selection + after, selection = selection)
    }

    override fun apply(text: String): Boolean {
        val p = proxy() ?: return false
        val current = read() ?: return false

        if (current.hasSelection) {
            // Inserting over a selection replaces it, which is the one case iOS makes easy.
            p.insertText(text)
            return true
        }

        // No selection: clear what we can see, then write. The cursor is moved to the end first so
        // deleteBackward walks the whole visible field rather than only the part before it.
        val after = p.documentContextAfterInput.orEmpty()
        if (after.isNotEmpty()) {
            p.adjustTextPositionByCharacterOffset(after.length.toLong())
        }
        val toDelete = minOf(current.full.length, MAX_DELETE)
        repeat(toDelete) { p.deleteBackward() }
        p.insertText(text)
        return true
    }

    override fun clearAll(): Boolean {
        val p = proxy() ?: return false
        val current = read() ?: return false
        val after = p.documentContextAfterInput.orEmpty()
        if (after.isNotEmpty()) {
            p.adjustTextPositionByCharacterOffset(after.length.toLong())
        }
        repeat(minOf(current.full.length, MAX_DELETE)) { p.deleteBackward() }
        return true
    }

    private companion object {
        /** A keyboard extension that stops responding is terminated, so this is a hard bound. */
        const val MAX_DELETE = 5_000
    }
}
