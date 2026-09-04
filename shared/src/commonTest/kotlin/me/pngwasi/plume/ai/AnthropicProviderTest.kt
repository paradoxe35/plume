package me.pngwasi.plume.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Anthropic's Messages API, which differs from OpenAI's in every part that matters: the reply is a
 * list of content blocks rather than one string, and only the text ones carry it.
 */
class AnthropicProviderTest {

    private fun parse(body: String) = AnthropicProvider.parse(body, "Anthropic")

    @Test
    fun `the text block is the reply`() {
        val body = """
            {"content":[{"type":"text","text":"J'ai mangé une pomme."}],"model":"claude-haiku-4-5"}
        """.trimIndent()

        assertEquals("J'ai mangé une pomme.", parse(body))
    }

    /** Long replies arrive split, and joining them is the whole job. */
    @Test
    fun `several text blocks are joined in order`() {
        val body = """
            {"content":[{"type":"text","text":"Bonjour "},{"type":"text","text":"le monde."}]}
        """.trimIndent()

        assertEquals("Bonjour le monde.", parse(body))
    }

    /**
     * A thinking or tool block has no `text`, and treating one as the reply would paste an empty
     * string over the user's selection — worse than any error.
     */
    @Test
    fun `blocks that are not text are ignored`() {
        val body = """
            {"content":[{"type":"thinking","thinking":"considering"},{"type":"text","text":"Fixed."}]}
        """.trimIndent()

        assertEquals("Fixed.", parse(body))
    }

    @Test
    fun `a reply with no text block is an error rather than an empty paste`() {
        val body = """{"content":[{"type":"thinking","thinking":"only this"}]}"""

        val failure = assertFailsWith<AiException> { parse(body) }
        assertEquals(AiException.Kind.Empty, failure.kind)
    }

    @Test
    fun `an error body is reported with the message the API gave`() {
        val body = """
            {"type":"error","error":{"type":"invalid_request_error","message":"model not found"}}
        """.trimIndent()

        val failure = assertFailsWith<AiException> { parse(body) }
        assertTrue(failure.message.orEmpty().contains("model not found"), failure.message.orEmpty())
    }

    @Test
    fun `a malformed body says so rather than throwing something unreadable`() {
        val failure = assertFailsWith<AiException> { parse("not json at all") }

        assertEquals(AiException.Kind.BadResponse, failure.kind)
    }

    /** Anthropic is not OpenAI-compatible, so it must never be built as though it were. */
    @Test
    fun `the kind maps to its own reasoning style`() {
        assertEquals(
            ReasoningStyle.None,
            Reasoning.detect(me.pngwasi.plume.data.ProviderKind.Anthropic, "https://api.anthropic.com"),
        )
        assertEquals(emptyMap(), Reasoning.chatFields(ReasoningStyle.None, me.pngwasi.plume.data.ReasoningMode.Low))
    }
}
