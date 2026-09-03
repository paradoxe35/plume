package me.pngwasi.plume.data

import android.content.Context

/**
 * A synchronously-readable mirror of the theme setting.
 *
 * Plume's surfaces draw on top of other apps and must be right on their very first frame. Reading
 * the real setting means a DataStore round trip on the IO dispatcher, so the first frames would
 * render with the system theme and then snap to the user's choice — a visible flash every single
 * time, on the one screen that is always seen cold.
 *
 * SharedPreferences is the rare store that can be read on the main thread without ceremony, so the
 * theme is mirrored here purely for that first frame. [SettingsRepository] remains the source of
 * truth; this is a cache and is self-healing when it drifts.
 */
object ThemeCache {

    private const val PREFS = "plume_theme_cache"
    private const val KEY = "theme"

    fun read(context: Context): ThemeMode {
        val name = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return ThemeMode.System
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.System)
    }

    fun write(context: Context, mode: ThemeMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, mode.name)
            .apply()
    }
}
