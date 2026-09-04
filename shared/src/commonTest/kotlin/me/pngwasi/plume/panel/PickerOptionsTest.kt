package me.pngwasi.plume.panel

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * What the keyboard picker offers.
 *
 * Pinning decides membership; recency only decides order. An earlier version also offered recent
 * languages, so unpinning one you had just used left it on screen — the settings screen said it was
 * gone and the keyboard disagreed. These tests pin that rule down.
 *
 * The empty case matters too: the panel has no search field and no route to settings, so an empty
 * list is a dead end rather than an inconvenience.
 */
class PickerOptionsTest {

    private val fallback = listOf("fr", "en", "es")

    /** The reported bug: Spanish unpinned in settings, still offered by the keyboard. */
    @Test
    fun `an unpinned language is not offered even when recently used`() {
        val options = pickerOptions(
            recents = listOf("es", "fr"),
            favorites = listOf("fr", "en"),
            fallback = fallback,
        )

        assertFalse(options.contains("es"))
        assertEquals(listOf("fr", "en"), options)
    }

    @Test
    fun `only pinned languages are offered`() {
        val options = pickerOptions(
            recents = listOf("de", "it", "pt"),
            favorites = listOf("fr"),
            fallback = fallback,
        )

        assertEquals(listOf("fr"), options)
    }

    @Test
    fun `recently used pinned languages come first`() {
        val options = pickerOptions(
            recents = listOf("en", "de"),
            favorites = listOf("fr", "de", "en"),
            fallback = fallback,
        )

        assertEquals(listOf("en", "de", "fr"), options)
    }

    @Test
    fun `pinned languages never used keep the order they were pinned in`() {
        val options = pickerOptions(
            recents = emptyList(),
            favorites = listOf("fr", "en", "sw"),
            fallback = fallback,
        )

        assertEquals(listOf("fr", "en", "sw"), options)
    }

    @Test
    fun `ordering matches case-insensitively`() {
        val options = pickerOptions(
            recents = listOf("EN"),
            favorites = listOf("fr", "en"),
            fallback = fallback,
        )

        assertEquals(listOf("en", "fr"), options)
    }

    @Test
    fun `a language pinned twice is offered once`() {
        val options = pickerOptions(emptyList(), listOf("fr", "FR"), fallback)

        assertEquals(listOf("fr"), options)
    }

    @Test
    fun `nothing pinned falls back to the defaults instead of offering nothing`() {
        val options = pickerOptions(recents = emptyList(), favorites = emptyList(), fallback = fallback)

        assertEquals(fallback, options)
    }

    /** Recents alone must not stand in for pinning, or the bug comes back through the fallback. */
    @Test
    fun `recents do not rescue an empty pinned list`() {
        val options = pickerOptions(recents = listOf("es"), favorites = emptyList(), fallback = fallback)

        assertEquals(fallback, options)
    }

    @Test
    fun `the list is capped so the panel keeps a predictable height`() {
        val many = (1..30).map { "l$it" }

        assertEquals(12, pickerOptions(emptyList(), many, fallback, max = 12).size)
    }

    @Test
    fun `the real default fallback is never empty`() {
        assertTrue(pickerOptions(emptyList(), emptyList()).isNotEmpty())
    }
}
