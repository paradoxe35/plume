package me.pngwasi.plume.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `@provider` mentions.
 *
 * The risk is over-eagerness: the same `@` that routes a request is also how people address each
 * other, so anything that is not a real provider name has to survive untouched. Eating "@sarah" out
 * of a message would be far worse than never having the feature.
 */
class ProviderMentionTest {

    private val providers = setOf("openai", "openrouter", "gemini", "my-gateway")

    @Test
    fun `a leading mention selects the provider and is removed`() {
        val result = parseProviderMention("@openai fix this sentence", providers)

        assertEquals("openai", result.providerId)
        assertEquals("fix this sentence", result.text)
    }

    @Test
    fun `the mention is matched case-insensitively but keeps the stored name`() {
        val result = parseProviderMention("@OpenAI fix this", providers)

        assertEquals("openai", result.providerId)
    }

    @Test
    fun `a custom provider name with a hyphen is matched`() {
        val result = parseProviderMention("@my-gateway fix this", providers)

        assertEquals("my-gateway", result.providerId)
        assertEquals("fix this", result.text)
    }

    /** The case that would make the feature a liability. */
    @Test
    fun `an unknown mention is left as ordinary text`() {
        val result = parseProviderMention("@sarah can you look at this", providers)

        assertNull(result.providerId)
        assertEquals("@sarah can you look at this", result.text)
    }

    @Test
    fun `a mention that is not at the start is ignored`() {
        val result = parseProviderMention("tell @openai about it", providers)

        assertNull(result.providerId)
        assertEquals("tell @openai about it", result.text)
    }

    @Test
    fun `text with no mention is untouched`() {
        val result = parseProviderMention("jai manger une pomme", providers)

        assertNull(result.providerId)
        assertEquals("jai manger une pomme", result.text)
    }

    /** Nothing left to work on, so the mention is not worth honouring. */
    @Test
    fun `a mention on its own is left alone`() {
        val result = parseProviderMention("@openai", providers)

        assertNull(result.providerId)
        assertEquals("@openai", result.text)
    }

    @Test
    fun `a bare at sign is not a mention`() {
        val result = parseProviderMention("@ something", providers)

        assertNull(result.providerId)
    }

    @Test
    fun `an email address is not a mention`() {
        val result = parseProviderMention("write to ada@example.com", providers)

        assertNull(result.providerId)
    }

    @Test
    fun `leading whitespace does not hide a mention`() {
        val result = parseProviderMention("   @gemini fix this", providers)

        assertEquals("gemini", result.providerId)
        assertEquals("fix this", result.text)
    }

    @Test
    fun `a newline after the mention still separates it`() {
        val result = parseProviderMention("@openai\nfix this", providers)

        assertEquals("openai", result.providerId)
        assertEquals("fix this", result.text)
    }

    @Test
    fun `mentions are ignored entirely when switched off`() {
        val result = parseProviderMention("@openai fix this", providers, enabled = false)

        assertNull(result.providerId)
        assertEquals("@openai fix this", result.text)
    }

    @Test
    fun `no configured providers means nothing is a mention`() {
        val result = parseProviderMention("@openai fix this", emptySet())

        assertNull(result.providerId)
        assertEquals("@openai fix this", result.text)
    }
}
