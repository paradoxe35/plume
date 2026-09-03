package me.pngwasi.plume.panel

/**
 * Read access to the clipboard.
 *
 * Behind an interface so the panel's logic can be tested without a live clipboard, and because the
 * desktop build will read it very differently.
 */
interface ClipboardSource {
    /** The clipboard's text, or null when it holds nothing usable. */
    fun read(): String?
}
