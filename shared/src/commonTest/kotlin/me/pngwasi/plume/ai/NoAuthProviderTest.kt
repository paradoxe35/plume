package me.pngwasi.plume.ai

import me.pngwasi.plume.FakeResponse
import me.pngwasi.plume.FakeServer
import kotlinx.coroutines.test.runTest
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.ReasoningMode
import me.pngwasi.plume.data.isLocalEndpoint
import me.pngwasi.plume.data.validateProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Providers that take no credentials — Ollama, LM Studio, llama.cpp and any self-hosted gateway.
 *
 * Sending an empty `Authorization: Bearer ` header is not the same as sending none: some servers
 * reject it outright, so the header has to be absent rather than blank.
 */
class NoAuthProviderTest {

    private lateinit var server: FakeServer

    @BeforeTest
    fun setUp() {
        server = FakeServer()
        ReasoningSupport.reset()
    }

    private fun settings(authRequired: Boolean) = AppSettings(
        defaultProvider = "local",
        providers = mapOf(
            "local" to ProviderConfig(
                label = "Ollama",
                kind = ProviderKind.OpenAiCompatible,
                baseUrl = server.baseUrl,
                model = "llama3.2",
                reasoning = ReasoningMode.ProviderDefault,
                authRequired = authRequired,
            ),
        ),
    )

    @Test
    fun `a keyless provider works and sends no authorization header`() = runTest {
        server.enqueue(
            FakeResponse(200)
                .setBody("""{"choices":[{"message":{"content":"Corrigé"}}]}"""),
        )

        val result = TextEngine(settings(authRequired = false), { "" }, server.client).revise("corriger")

        assertEquals("Corrigé", result)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a provider that requires a key still refuses to run without one`() = runTest {
        val error = runCatching { TextEngine(settings(authRequired = true), { "" }, server.client).revise("x") }
            .exceptionOrNull() as AiException

        assertEquals(AiException.Kind.NotConfigured, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a key is still sent when one is present`() = runTest {
        server.enqueue(
            FakeResponse(200)
                .setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
        )

        TextEngine(settings(authRequired = false), { "sk-local" }, server.client).revise("x")

        assertEquals("Bearer sk-local", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `validation does not demand a key when the provider says none is needed`() {
        val config = ProviderConfig(
            label = "Ollama",
            baseUrl = "http://localhost:11434/v1",
            model = "llama3.2",
            authRequired = false,
        )

        assertTrue(validateProvider(config, apiKey = "", requireLabel = true).isValid)
    }

    @Test
    fun `validation still demands a key when the provider needs one`() {
        val config = ProviderConfig(
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            authRequired = true,
        )

        assertNotNull(validateProvider(config, apiKey = "", requireLabel = false).apiKey)
    }

    @Test
    fun `loopback and private addresses are recognised as local`() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:1234/v1",
            "http://192.168.1.50:8080/v1",
            "http://10.0.0.4:11434/v1",
            "http://172.16.5.2:11434/v1",
            "http://mac-studio.local:1234/v1",
        ).forEach { assertTrue(isLocalEndpoint(it), it) }
    }

    @Test
    fun `public addresses are not local`() {
        listOf(
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "https://api.groq.com/openai/v1",
            "http://172.32.0.1/v1",
        ).forEach { assertFalse(isLocalEndpoint(it), it) }
    }

    @Test
    fun `nonsense input is not mistaken for a local endpoint`() {
        assertFalse(isLocalEndpoint(""))
        assertFalse(isLocalEndpoint("not a url"))
    }
}
