package me.pngwasi.plume.ime

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Controls whether Plume's keyboard panel exists as far as the system is concerned.
 *
 * The service ships `android:enabled="false"`, so a default install adds nothing to the user's
 * keyboard list. Turning the feature on enables the component, which is what makes it appear in
 * system settings at all — a plain preference flag would leave a stray keyboard listed for every
 * user who never wanted it.
 */
object KeyboardComponent {

    private fun component(context: Context) =
        ComponentName(context.applicationContext, PlumeInputMethodService::class.java)

    /** Whether the component is enabled, i.e. whether the system can see the keyboard at all. */
    fun isAvailable(context: Context): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // DEFAULT falls back to the manifest, where the service is disabled.
            else -> false
        }

    fun setAvailable(context: Context, available: Boolean) {
        val state = if (available) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * Compares an input-method id against a component.
     *
     * The system writes ids with `flattenToShortString`, which abbreviates a class name sharing the
     * package prefix — `me.pngwasi.plume/.ime.PlumeInputMethodService`. Comparing that to
     * `flattenToString` output never matches, so the id is parsed back into a ComponentName instead
     * of string-matched; `unflattenFromString` expands the leading dot and both forms compare equal.
     */
    internal fun matches(imeId: String?, target: ComponentName): Boolean {
        if (imeId.isNullOrBlank()) return false
        return ComponentName.unflattenFromString(imeId) == target
    }

    /** Whether the user has switched it on in Settings → On-screen keyboards. */
    fun isEnabledInSystem(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        val target = component(context)
        return imm.enabledInputMethodList.any { matches(it.id, target) }
    }

    fun isCurrentInputMethod(context: Context): Boolean {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        return matches(current, component(context))
    }

    fun systemKeyboardSettings(): android.content.Intent =
        android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun showPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }
}
