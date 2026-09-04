package me.pngwasi.plume.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import me.pngwasi.plume.panel.ClipboardSource

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

    /**
     * The clip's description rather than its contents: reading raises the "pasted from" toast on
     * Android 12 and later, which belongs to the user asking for the clipboard, not to the panel
     * appearing.
     */
    override fun hasText(): Boolean = runCatching {
        val description = manager?.primaryClipDescription ?: return@runCatching false
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    }.getOrDefault(false)

    override fun read(): String? {
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val text = runCatching { clip.getItemAt(0).coerceToText(null)?.toString() }.getOrNull()
        return text?.takeIf { it.isNotBlank() }
    }
}
