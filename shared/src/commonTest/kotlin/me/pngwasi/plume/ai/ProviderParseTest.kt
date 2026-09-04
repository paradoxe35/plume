package me.pngwasi.plume.ai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** Response-shape handling, isolated from the network. */
class ProviderParseTest {

    @Test
    fun `openai response yields the message content`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"J'ai mangé."}}]}"""

        assertEquals("J'ai mangé.", OpenAiCompatibleProvider.parse(body, "OpenAI"))
    }

    @Test
    fun `openai response with no choices is an empty error`() {
        val error = runCatching { OpenAiCompatibleProvider.parse("""{"choices":[]}""", "OpenAI") }
            .exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Empty, error.kind)
    }

    @Test
    fun `openai error envelope surfaces the provider message`() {
        val body = """{"error":{"message":"model not found","type":"invalid_request_error"}}"""

        val error = runCatching { OpenAiCompatibleProvider.parse(body, "OpenAI") }
            .exceptionOrNull() as AiException

        assertTrue(error.message!!.contains("model not found"))
    }

    @Test
    fun `malformed json is reported as a bad response`() {
        val error = runCatching { OpenAiCompatibleProvider.parse("not json at all", "OpenAI") }
            .exceptionOrNull() as AiException

        assertEquals(AiException.Kind.BadResponse, error.kind)
    }

    @Test
    fun `gemini parts are concatenated`() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"Bon"},{"text":"jour"}]}}]}
        """.trimIndent()

        assertEquals("Bonjour", GeminiProvider.parse(body, "Gemini"))
    }

    /** A safety block returns HTTP 200 with no candidates; the user needs to know why. */
    @Test
    fun `gemini reports the block reason when the prompt was refused`() {
        val body = """{"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}"""

        val error = runCatching { GeminiProvider.parse(body, "Gemini") }
            .exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Empty, error.kind)
        assertTrue(error.message!!.contains("SAFETY"))
    }

    @Test
    fun `nested error message is extracted`() {
        assertEquals("bad key", extractMessage("""{"error":{"message":"bad key"}}"""))
    }

    @Test
    fun `string error is extracted`() {
        assertEquals("nope", extractMessage("""{"error":"nope"}"""))
    }

    @Test
    fun `top-level message is extracted`() {
        assertEquals("hi", extractMessage("""{"message":"hi"}"""))
    }

    @Test
    fun `non-json bodies yield no message`() {
        assertNull(extractMessage("<html>502 Bad Gateway</html>"))
        assertNull(extractMessage(""))
    }

    @Test
    fun `401 maps to an auth error naming the provider`() {
        val error = httpError(401, "", "OpenRouter")

        assertEquals(AiException.Kind.Auth, error.kind)
        assertTrue(error.message!!.contains("OpenRouter"))
    }

    @Test
    fun `429 maps to rate limit`() {
        assertEquals(AiException.Kind.RateLimit, httpError(429, "", "OpenAI").kind)
    }

    @Test
    fun `5xx maps to a server error`() {
        assertEquals(AiException.Kind.Server, httpError(503, "", "Gemini").kind)
    }

    @Test
    fun `404 prefers the provider's own explanation`() {
        val error = httpError(404, """{"error":{"message":"unknown model foo"}}""", "OpenAI")

        assertTrue(error.message!!.contains("unknown model foo"))
    }
}
