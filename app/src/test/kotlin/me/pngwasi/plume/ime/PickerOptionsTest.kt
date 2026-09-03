package me.pngwasi.plume.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keyboard picker offers.
 *
 * The panel has no search field and no way to reach settings from inside the picker, so an empty
 * list is a dead end rather than an inconvenience — the fallback is the behaviour that matters here.
 */
class PickerOptionsTest {

    private val fallback = listOf("fr", "en", "es")

    @Test
    fun `recents come before pinned languages`() {
        val options = pickerOptions(
            recents = listOf("de", "it"),
            favorites = listOf("fr", "en"),
            fallback = fallback,
        )

        assertEquals(listOf("de", "it", "fr", "en"), options)
    }

    @Test
    fun `a language that is both recent and pinned appears once`() {
        val options = pickerOptions(recents = listOf("fr"), favorites = listOf("fr", "en"), fallback = fallback)

        assertEquals(listOf("fr", "en"), options)
    }

    @Test
    fun `duplicates are removed case-insensitively`() {
        val options = pickerOptions(recents = listOf("FR"), favorites = listOf("fr"), fallback = fallback)

        assertEquals(listOf("FR"), options)
    }

    /** The dead end this fallback exists to prevent. */
    @Test
    fun `an empty picker falls back to the defaults instead of offering nothing`() {
        val options = pickerOptions(recents = emptyList(), favorites = emptyList(), fallback = fallback)

        assertEquals(fallback, options)
        assertFalse(options.isEmpty())
    }

    @Test
    fun `the fallback is not mixed in when the user has their own languages`() {
        val options = pickerOptions(recents = emptyList(), favorites = listOf("sw"), fallback = fallback)

        assertEquals(listOf("sw"), options)
    }

    @Test
    fun `the list is capped so the panel keeps a predictable height`() {
        val many = (1..30).map { "l$it" }

        val options = pickerOptions(recents = many, favorites = emptyList(), fallback = fallback, max = 12)

        assertEquals(12, options.size)
    }

    @Test
    fun `the real default fallback is never empty`() {
        assertTrue(pickerOptions(emptyList(), emptyList()).isNotEmpty())
    }
}
