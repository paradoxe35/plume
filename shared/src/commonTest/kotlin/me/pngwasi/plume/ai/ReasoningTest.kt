package me.pngwasi.plume.ai

import me.pngwasi.plume.FakeResponse
import me.pngwasi.plume.FakeServer
import me.pngwasi.plume.RecordedRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.ReasoningMode
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reasoning control and its fallback.
 *
 * The fallback is the point of this feature: there is no parameter every provider accepts, so the
 * app has to recover from being told no rather than surface a 400 the user cannot act on.
 */
class ReasoningTest {

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

    private fun settings(
        kind: ProviderKind = ProviderKind.OpenAiCompatible,
        reasoning: ReasoningMode = ReasoningMode.Low,
        baseUrl: String = server.baseUrl,
        authRequired: Boolean = true,
    ) = AppSettings(
        defaultProvider = "test",
        providers = mapOf(
            "test" to ProviderConfig(
                label = "TestProvider",
                kind = kind,
                baseUrl = baseUrl,
                model = "test-model",
                reasoning = reasoning,
                authRequired = authRequired,
            ),
        ),
    )

    private fun engine(settings: AppSettings = settings(), key: String = "sk-test") =
        TextEngine(settings, { key }, server.client)

    private fun ok(text: String = "Corrigé") = FakeResponse(200)
        .setBody("""{"choices":[{"message":{"content":"$text"}}]}""")

    private fun bodyOf(request: RecordedRequest) =
        Json.parseToJsonElement(request.body).jsonObject

    @Test
    fun `openai style sends reasoning_effort low`() = runTest {
        server.enqueue(ok())

        engine().revise("text")

        val body = bodyOf(server.takeRequest())
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        // OpenRouter 400s when it sees both shapes, so only one may ever be present.
        assertNull(body["reasoning"])
    }

    /** OpenRouter has its own shape and is recognised by host, not by provider kind. */
    @Test
    fun `openrouter style sends its own reasoning object`() = runTest {
        assertEquals(
            ReasoningStyle.OpenRouterReasoning,
            Reasoning.styleFor(ProviderKind.OpenAiCompatible, "https://openrouter.ai/api/v1"),
        )
        assertEquals(
            ReasoningStyle.OpenAiEffort,
            Reasoning.styleFor(ProviderKind.OpenAiCompatible, "https://api.openai.com/v1"),
        )
        assertEquals(
            ReasoningStyle.GeminiBudget,
            Reasoning.styleFor(ProviderKind.Gemini, "https://generativelanguage.googleapis.com"),
        )
    }

    @Test
    fun `openrouter fields carry effort and exclude`() {
        val fields = Reasoning.chatFields(ReasoningStyle.OpenRouterReasoning, ReasoningMode.Low)

        val reasoning = fields["reasoning"]!!.jsonObject
        assertEquals("low", reasoning["effort"]!!.jsonPrimitive.content)
        assertTrue(reasoning["exclude"]!!.jsonPrimitive.content.toBoolean())
        assertNull(fields["reasoning_effort"])
    }

    @Test
    fun `gemini sends a zero thinking budget`() = runTest {
        server.enqueue(
            FakeResponse(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Fixed"}]}}]}"""),
        )

        engine(settings(kind = ProviderKind.Gemini)).revise("text")

        val config = bodyOf(server.takeRequest())["generationConfig"]!!.jsonObject
        assertEquals(0, config["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `provider default mode sends nothing at all`() = runTest {
        server.enqueue(ok())

        engine(settings(reasoning = ReasoningMode.ProviderDefault)).revise("text")

        val body = bodyOf(server.takeRequest())
        assertNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
    }

    /** OpenAI answers 400 "Invalid 'reasoning_effort' for non-reasoning model". */
    @Test
    fun `a 400 retries once without the reasoning parameter and succeeds`() = runTest {
        server.enqueue(
            FakeResponse(400)
                .setBody("""{"error":{"message":"Invalid 'reasoning_effort' for non-reasoning model"}}"""),
        )
        server.enqueue(ok("Corrigé"))

        val result = engine().revise("corriger")

        assertEquals("Corrigé", result)
        assertEquals(2, server.requestCount)

        assertEquals("low", bodyOf(server.takeRequest())["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(bodyOf(server.takeRequest())["reasoning_effort"])
    }

    @Test
    fun `a 422 also triggers the fallback`() = runTest {
        server.enqueue(FakeResponse(422))
        server.enqueue(ok())

        engine().revise("text")

        assertEquals(2, server.requestCount)
    }

    /** Once a model has refused, later calls must not keep paying for the rejected round trip. */
    @Test
    fun `a rejection is remembered so the next call skips the parameter`() = runTest {
        server.enqueue(FakeResponse(400))
        server.enqueue(ok())
        engine().revise("first")
        assertEquals(2, server.requestCount)
        repeat(2) { server.takeRequest() }

        server.enqueue(ok())
        engine().revise("second")

        assertEquals(3, server.requestCount)
        assertNull(bodyOf(server.takeRequest())["reasoning_effort"])
    }

    @Test
    fun `the rejection cache is keyed per provider and model`() {
        val a = ReasoningSupport.key("openai", "gpt-4o")
        val b = ReasoningSupport.key("openai", "o3")

        ReasoningSupport.markRejected(a)

        assertFalse(ReasoningSupport.accepts(a))
        assertTrue(ReasoningSupport.accepts(b))
    }

    /** A 500 is not about the parameter, so retrying without it would only waste another call. */
    @Test
    fun `a server error is not retried`() = runTest {
        server.enqueue(FakeResponse(500))

        val error = runCatching { engine().revise("text") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Server, error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an auth failure is not retried`() = runTest {
        server.enqueue(FakeResponse(401))

        runCatching { engine().revise("text") }

        assertEquals(1, server.requestCount)
    }

    /** A 400 that had nothing to do with reasoning still surfaces, after one retry. */
    @Test
    fun `a persistent 400 is reported to the user`() = runTest {
        server.enqueue(FakeResponse(400, """{"error":{"message":"unknown model"}}"""))
        server.enqueue(FakeResponse(400, """{"error":{"message":"unknown model"}}"""))

        val error = runCatching { engine().revise("text") }.exceptionOrNull() as AiException

        assertTrue(error.message!!.contains("unknown model"))
        assertEquals(2, server.requestCount)
    }

    /**
     * A 400 has many causes. Caching on the status alone would let an unknown model or an exhausted
     * quota switch reasoning off for the rest of the session, on a model that never objected to it.
     */
    @Test
    fun `an unrelated 400 does not disable reasoning for later calls`() = runTest {
        // Both attempts fail: the parameter was never the problem.
        server.enqueue(FakeResponse(400, """{"error":{"message":"unknown model"}}"""))
        server.enqueue(FakeResponse(400, """{"error":{"message":"unknown model"}}"""))
        runCatching { engine().revise("text") }
        assertEquals(2, server.requestCount)
        repeat(2) { server.takeRequest() }

        // The next call must still ask for low reasoning.
        server.enqueue(ok())
        engine().revise("text")

        assertEquals("low", bodyOf(server.takeRequest())["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the rejection is only cached once dropping the parameter actually helped`() = runTest {
        server.enqueue(FakeResponse(400))
        server.enqueue(ok())
        engine().revise("text")
        repeat(2) { server.takeRequest() }

        assertFalse(ReasoningSupport.accepts(ReasoningSupport.key("test", "test-model")))
    }

    @Test
    fun `nothing is retried when reasoning was never sent`() = runTest {
        server.enqueue(FakeResponse(400))

        runCatching { engine(settings(reasoning = ReasoningMode.ProviderDefault)).revise("text") }

        assertEquals(1, server.requestCount)
    }
}
