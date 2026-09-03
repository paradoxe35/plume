package me.pngwasi.plume.ime

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit

/**
 * Remembers which keyboard the user actually types with, so "Keyboard" reliably goes back to it.
 *
 * Android keeps a switching history of its own, which is what
 * [android.inputmethodservice.InputMethodService.switchToPreviousInputMethod] consults, and that is
 * the best answer when it has one — it knows exactly where the user came from. It is not something
 * an app can lean on, though: it can be empty on the first switch after a restart.
 *
 * So Plume also notes whichever keyboard is selected whenever it sees a non-Plume one — every time
 * the app is opened, a selection action runs, or the user asks for the keyboard picker. Whatever
 * they actually use is what gets recorded; nothing here assumes Gboard or any particular keyboard.
 *
 * When neither source knows, the picker is shown. Guessing at a keyboard the user never chose would
 * be worse than asking.
 */
object TypingKeyboard {

    private const val PREFS = "plume_typing_keyboard"
    private const val KEY = "ime_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records the selected keyboard if it is not Plume. Cheap enough to call from any entry point. */
    fun noteCurrent(context: Context) {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        if (KeyboardComponent.matches(current, plumeComponent(context))) return
        prefs(context).edit { putString(KEY, current) }
    }

    /** The remembered keyboard, or null when there is none to return to. */
    fun resolveTarget(context: Context): String? = chooseTarget(
        remembered = prefs(context).getString(KEY, null),
        enabled = enabledIds(context),
        plume = plumeComponent(context),
    )

    /**
     * A remembered keyboard is only usable while it is still enabled — it may have been uninstalled
     * or switched off since. Anything else, including Plume itself, is not an answer.
     */
    internal fun chooseTarget(
        remembered: String?,
        enabled: List<String>,
        plume: ComponentName,
    ): String? {
        if (remembered.isNullOrBlank()) return null
        if (KeyboardComponent.matches(remembered, plume)) return null
        return remembered.takeIf { candidate -> enabled.any { it == candidate } }
    }

    private fun plumeComponent(context: Context) =
        ComponentName(context.applicationContext, PlumeInputMethodService::class.java)

    private fun enabledIds(context: Context): List<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptyList()
        return runCatching { imm.enabledInputMethodList.map { it.id } }.getOrDefault(emptyList())
    }
}
