package me.pngwasi.plume.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The cache exists purely so the first frame of an overlay is already the right theme, so the
 * behaviour that matters is what a cold read returns.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeCacheTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `an unwritten cache reads as System`() {
        assertEquals(ThemeMode.System, ThemeCache.read(context))
    }

    @Test
    fun `a written theme reads back`() {
        ThemeCache.write(context, ThemeMode.Dark)

        assertEquals(ThemeMode.Dark, ThemeCache.read(context))
    }

    @Test
    fun `the latest write wins`() {
        ThemeCache.write(context, ThemeMode.Dark)
        ThemeCache.write(context, ThemeMode.Light)

        assertEquals(ThemeMode.Light, ThemeCache.read(context))
    }

    @Test
    fun `every theme survives a round trip`() {
        ThemeMode.entries.forEach { mode ->
            ThemeCache.write(context, mode)
            assertEquals(mode, ThemeCache.read(context))
        }
    }

    /** A rename or downgrade must not crash the overlay before it can draw. */
    @Test
    fun `an unrecognised stored value falls back to System`() {
        context.getSharedPreferences("plume_theme_cache", Context.MODE_PRIVATE)
            .edit().putString("theme", "Chartreuse").apply()

        assertEquals(ThemeMode.System, ThemeCache.read(context))
    }
}
