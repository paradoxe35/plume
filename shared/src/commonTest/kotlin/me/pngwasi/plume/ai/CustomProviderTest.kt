package me.pngwasi.plume.ai

import me.pngwasi.plume.FakeResponse
import me.pngwasi.plume.FakeServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.ReasoningDialect
import me.pngwasi.plume.data.ReasoningMode
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Custom providers — the case auto-detection cannot cover.
 *
 * A self-hosted proxy can speak OpenRouter's dialect from a hostname that says nothing about it.
 * Without an override such a provider is rejected once and then permanently sent no reasoning
 * parameter at all, quietly losing the setting the user asked for.
 */
class CustomProviderTest {

    private lateinit var server: FakeServer

    @BeforeTest
    fun setUp() {
        server = FakeServer()
        ReasoningSupport.reset()
    }

    @AfterTest
    fun tearDown() {
        ReasoningSupport.reset()
    }

    private fun custom(
        dialect: ReasoningDialect = ReasoningDialect.Auto,
        kind: ProviderKind = ProviderKind.OpenAiCompatible,
        authRequired: Boolean = true,
    ) = AppSettings(
        defaultProvider = "gateway",
        providers = mapOf(
            "gateway" to ProviderConfig(
                label = "My Gateway",
                kind = kind,
                baseUrl = server.baseUrl,
                model = "some-model",
                isCustom = true,
                reasoning = ReasoningMode.Low,
                reasoningDialect = dialect,
                authRequired = authRequired,
            ),
        ),
    )

    private fun ok() = FakeResponse(200)
        .setBody("""{"choices":[{"message":{"content":"Corrigé"}}]}""")

    private fun lastBody() = Json.parseToJsonElement(server.takeRequest().body).jsonObject

    @Test
    fun `an unrecognised host defaults to the OpenAI parameter`() = runTest {
        server.enqueue(ok())

        TextEngine(custom(), { "k" }, server.client).revise("text")

        val body = lastBody()
        assertNotNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
    }

    /** The gap this override exists to close. */
    @Test
    fun `an explicit OpenRouter dialect is used even on an unrelated host`() = runTest {
        server.enqueue(ok())

        TextEngine(custom(ReasoningDialect.OpenRouter), { "k" }, server.client).revise("text")

        val body = lastBody()
        assertNotNull(body["reasoning"])
        // Sending both shapes is exactly what OpenRouter rejects.
        assertNull(body["reasoning_effort"])
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an explicit OpenAI dialect wins over an OpenRouter host`() {
        assertEquals(
            ReasoningStyle.OpenAiEffort,
            Reasoning.styleFor(
                ProviderKind.OpenAiCompatible,
                "https://openrouter.ai/api/v1",
                ReasoningDialect.OpenAi,
            ),
        )
    }

    @Test
    fun `auto still detects OpenRouter by host`() {
        assertEquals(
            ReasoningStyle.OpenRouterReasoning,
            Reasoning.styleFor(
                ProviderKind.OpenAiCompatible,
                "https://openrouter.ai/api/v1",
                ReasoningDialect.Auto,
            ),
        )
    }

    @Test
    fun `an overridden dialect that the provider rejects still falls back`() = runTest {
        server.enqueue(FakeResponse(400))
        server.enqueue(ok())

        val result = TextEngine(custom(ReasoningDialect.OpenRouter), { "k" }, server.client).revise("text")

        assertEquals("Corrigé", result)
        assertEquals(2, server.requestCount)

        val firstAttempt = lastBody()
        val retry = lastBody()
        assertNotNull(firstAttempt["reasoning"])
        assertNull(retry["reasoning"])
    }

    @Test
    fun `a custom gemini-shaped endpoint sends a thinking budget`() = runTest {
        server.enqueue(
            FakeResponse(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Fixed"}]}}]}"""),
        )

        TextEngine(custom(kind = ProviderKind.Gemini), { "k" }, server.client).revise("text")

        val config = lastBody()["generationConfig"]!!.jsonObject
        assertNotNull(config["thinkingConfig"])
    }

    @Test
    fun `a keyless custom provider sends no authorization header`() = runTest {
        server.enqueue(ok())

        TextEngine(custom(authRequired = false), { "" }, server.client).revise("text")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
