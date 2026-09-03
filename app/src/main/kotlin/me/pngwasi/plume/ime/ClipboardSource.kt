package me.pngwasi.plume.ime

import android.content.ClipboardManager
import android.content.Context

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

/**
 * Android's clipboard.
 *
 * Since Android 10 only the focused app or the active input method may read the clipboard. An IME
 * is explicitly on that list, which is the only reason this feature can exist at all — a normal app
 * could not offer it.
 */
class AndroidClipboardSource(context: Context) : ClipboardSource {

    private val manager =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun read(): String? {
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val text = runCatching { clip.getItemAt(0).coerceToText(null)?.toString() }.getOrNull()
        return text?.takeIf { it.isNotBlank() }
    }
}
