package me.pngwasi.plume.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test
    fun `default translate prompt carries the placeholder`() {
        assertTrue(Prompts.TRANSLATE.contains(Prompts.TARGET_LANGUAGE))
    }

    @Test
    fun `render substitutes every occurrence of the placeholder`() {
        val rendered = Prompts.renderTranslate(Prompts.TRANSLATE, "French")

        assertFalse(rendered.contains(Prompts.TARGET_LANGUAGE))
        // The default prompt mentions the target three times; all must be replaced.
        assertEquals(3, Regex("French").findAll(rendered).count())
    }

    @Test
    fun `render appends the target when the user removed the placeholder`() {
        val custom = "Translate the text. Be brief."

        val rendered = Prompts.renderTranslate(custom, "Swahili")

        assertTrue(rendered.startsWith(custom))
        assertTrue(rendered.endsWith("Translate into Swahili."))
    }

    @Test
    fun `revise prompt forbids translating`() {
        assertTrue(Prompts.REVISE.contains("Never translate"))
    }

    @Test
    fun `blank stored prompts fall back to the defaults`() {
        assertEquals(Prompts.REVISE, ReviseSettings(systemPrompt = "").promptOrDefault())
        assertEquals(Prompts.TRANSLATE, TranslateSettings(systemPrompt = "   ").promptOrDefault())
    }

    @Test
    fun `a custom prompt is used verbatim`() {
        val custom = "Fix only accents."

        assertEquals(custom, ReviseSettings(systemPrompt = custom).promptOrDefault())
    }
}
