package me.pngwasi.plume.panel

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
 * Narrow view of the focused text field, and the seam between the shared panel logic and each
 * platform's very different idea of the text being edited: `InputConnection` on Android,
 * `UITextDocumentProxy` on iOS, and clipboard round-tripping on the desktop.
 */
interface EditorBridge {
    fun read(): EditorText?

    /** Replaces the selection when there is one, otherwise the entire field. */
    fun apply(text: String): Boolean

    /** Empties the field regardless of what is selected. */
    fun clearAll(): Boolean
}
