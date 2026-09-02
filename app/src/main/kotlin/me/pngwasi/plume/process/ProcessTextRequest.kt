package me.pngwasi.plume.process

import android.content.Intent

/**
 * The selection handed over by whichever app the user was in.
 *
 * [editable] is the flag that decides the whole interaction: when the selection came from an input
 * field we may hand corrected text back and it replaces the selection in place; when it came from
 * a read-only surface (a received message, a web page) there is no way to write back, and the
 * result has to be shown and copied instead.
 */
data class ProcessTextRequest(
    val text: String,
    val editable: Boolean,
) {
    companion object {
        fun from(intent: Intent?): ProcessTextRequest? {
            if (intent == null || intent.action != Intent.ACTION_PROCESS_TEXT) return null
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (text.isNullOrBlank()) return null
            val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            return ProcessTextRequest(text = text, editable = !readOnly)
        }

        /** The result shape the host app reads to swap the user's selection for [text]. */
        fun replacementIntent(text: String): Intent =
            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text)
    }
}
