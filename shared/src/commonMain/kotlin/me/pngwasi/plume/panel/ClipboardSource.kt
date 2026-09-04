package me.pngwasi.plume.panel

/**
 * Read access to the clipboard.
 *
 * Split in two because reading is not free: iOS 16 shows a system "Allow Paste?" prompt for any
 * programmatic read, and Android 12 shows a toast. Reading merely to decide whether to offer the
 * clipboard button would interrogate the user every time the panel appeared. [hasText] answers that
 * without touching the contents; [read] runs only once the user has asked for the clipboard, where
 * a prompt belongs to an action they took.
 */
interface ClipboardSource {
    /** Whether there is text worth offering, without reading it. */
    fun hasText(): Boolean

    /** The clipboard's text, or null when it holds nothing usable. */
    fun read(): String?
}
