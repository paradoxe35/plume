package me.pngwasi.plume.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LanguagesTest {

    @Test
    fun `catalog has no duplicate codes`() {
        val codes = Languages.all.map { it.code.lowercase(Locale.ROOT) }

        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun `lookup is case and whitespace insensitive`() {
        assertNotNull(Languages.find("FR"))
        assertNotNull(Languages.find("  fr  "))
        assertEquals("fr", Languages.find("Fr")?.code)
    }

    @Test
    fun `unknown codes are not found`() {
        assertNull(Languages.find("qqq"))
    }

    /** A favourite saved before a catalogue change must still render, not vanish. */
    @Test
    fun `resolve falls back to the raw code for unknown languages`() {
        val resolved = Languages.resolve("qqq")

        assertEquals("qqq", resolved.code)
        assertEquals("qqq", resolved.displayName())
    }

    @Test
    fun `prompt name is English so prompts do not shift with the UI locale`() {
        assertEquals("French", Languages.resolve("fr").promptName())
        assertEquals("German", Languages.resolve("de").promptName())
    }

    @Test
    fun `empty query returns the whole catalog`() {
        assertEquals(Languages.all.size, Languages.search("").size)
        assertEquals(Languages.all.size, Languages.search("   ").size)
    }

    @Test
    fun `search matches on code prefix`() {
        val results = Languages.search("sw", Locale.ENGLISH)

        assertTrue(results.any { it.code == "sw" })
    }

    @Test
    fun `search matches on English name regardless of case`() {
        val results = Languages.search("japan", Locale.ENGLISH)

        assertEquals("ja", results.single().code)
    }

    @Test
    fun `search matches on the endonym`() {
        // "Deutsch" is how German names itself; a German speaker should find it that way.
        val results = Languages.search("deutsch", Locale.ENGLISH)

        assertTrue(results.any { it.code == "de" })
    }

    @Test
    fun `search returns nothing for gibberish`() {
        assertTrue(Languages.search("zzzzqqq", Locale.ENGLISH).isEmpty())
    }

    @Test
    fun `default favorites always include French and English`() {
        val favorites = Languages.defaultFavorites(Locale.JAPAN)

        assertTrue(favorites.contains("fr"))
        assertTrue(favorites.contains("en"))
        assertTrue(favorites.contains("ja"))
    }

    @Test
    fun `default favorites do not duplicate when the device locale is already included`() {
        val favorites = Languages.defaultFavorites(Locale.FRANCE)

        assertEquals(favorites.size, favorites.distinct().size)
        assertTrue(favorites.size >= 3)
    }
}
