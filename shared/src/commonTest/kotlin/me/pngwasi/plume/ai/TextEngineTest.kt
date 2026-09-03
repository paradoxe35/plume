package me.pngwasi.plume.ai

import me.pngwasi.plume.FakeResponse
import me.pngwasi.plume.FakeServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pngwasi.plume.data.Action
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.BuiltInProviders
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.ReviseSettings
import me.pngwasi.plume.data.TranslateSettings
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * End-to-end over a local server: settings in, HTTP request out, cleaned text back. This is the
 * path both toolbar actions take, so it is the one worth covering densely.
 */
class TextEngineTest {

    private lateinit var server: FakeServer

    @BeforeTest
    fun setUp() {
        server = FakeServer()
        // Process-wide cache; reset so test order cannot change what requests carry.
        ReasoningSupport.reset()
    }

    private fun settings(
        kind: ProviderKind = ProviderKind.OpenAiCompatible,
        revise: ReviseSettings = ReviseSettings(),
        translate: TranslateSettings = TranslateSettings(),
    ) = AppSettings(
        defaultProvider = "test",
        providers = mapOf(
            "test" to ProviderConfig(
                label = "TestProvider",
                kind = kind,
                baseUrl = server.baseUrl,
                model = "test-model",
                temperature = 0.2f,
            ),
        ),
        revise = revise,
        translate = translate,
    )

    private fun engine(
        settings: AppSettings = settings(),
        key: String = "sk-test",
    ) = TextEngine(settings, { key }, server.client)

    private fun openAiReply(text: String) = FakeResponse()
        .setResponseCode(200)
        .setBody("""{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}}]}""")

    // --- revise ------------------------------------------------------------------------------

    @Test
    fun `revise sends the system prompt and the selection`() = runTest {
        server.enqueue(openAiReply("J'ai mangé une pomme."))

        val result = engine().revise("jai manger une pomme")

        assertEquals("J'ai mangé une pomme.", result)

        val body = Json.parseToJsonElement(server.takeRequest().body).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("proofreader"))
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("jai manger une pomme", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `revise sends the model and temperature from settings`() = runTest {
        server.enqueue(openAiReply("ok"))

        engine().revise("text")

        val body = Json.parseToJsonElement(server.takeRequest().body).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.2f, body["temperature"]!!.jsonPrimitive.content.toFloat(), 0.001f)
    }

    @Test
    fun `revise authenticates with a bearer token`() = runTest {
        server.enqueue(openAiReply("ok"))

        engine().revise("text")

        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `revise uses a custom prompt when one is set`() = runTest {
        server.enqueue(openAiReply("ok"))

        engine(settings(revise = ReviseSettings(systemPrompt = "Only fix accents."))).revise("text")

        val body = Json.parseToJsonElement(server.takeRequest().body).jsonObject
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("Only fix accents.", system)
    }

    @Test
    fun `revise strips a wrapping code fence from the reply`() = runTest {
        server.enqueue(openAiReply("```\nCorrigé.\n```"))

        assertEquals("Corrigé.", engine().revise("corriger"))
    }

    // --- translate ---------------------------------------------------------------------------

    @Test
    fun `translate substitutes the target language into the prompt`() = runTest {
        server.enqueue(openAiReply("Hello"))

        val result = engine().translate("Bonjour", "en")

        assertEquals("Hello", result)

        val body = Json.parseToJsonElement(server.takeRequest().body).jsonObject
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("English"))
        assertTrue(!system.contains("{{target_language}}"))
    }

    @Test
    fun `translate names the language in English even for exotic targets`() = runTest {
        server.enqueue(openAiReply("Habari"))

        engine().translate("Bonjour", "sw")

        val body = Json.parseToJsonElement(server.takeRequest().body).jsonObject
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("Swahili"))
    }

    @Test
    fun `translate appends the target when the custom prompt dropped the placeholder`() = runTest {
        server.enqueue(openAiReply("Hola"))

        engine(settings(translate = TranslateSettings(systemPrompt = "Translate this.")))
            .translate("Bonjour", "es")

        val body = Json.parseToJsonElement(server.takeRequest().body).jsonObject
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.endsWith("Translate into Spanish."))
    }

    // --- provider wire formats ---------------------------------------------------------------

    @Test
    fun `gemini requests carry the key in a header and target the model path`() = runTest {
        server.enqueue(
            FakeResponse(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Fixed"}]}}]}"""),
        )

        val result = engine(settings(kind = ProviderKind.Gemini)).revise("text")

        assertEquals("Fixed", result)

        val request = server.takeRequest()
        assertEquals("/v1beta/models/test-model:generateContent", request.path)
        assertEquals("sk-test", request.getHeader("x-goog-api-key"))
        // The key must not leak into the URL, where it would land in access logs.
        assertTrue(!request.path.contains("sk-test"))
    }

    // --- per-action routing ------------------------------------------------------------------

    /** The point of per-action providers: each action must reach its own endpoint. */
    @Test
    fun `revise and translate can run on different providers`() = runTest {
        val other = server.endpoint("https://other.test/v1")
        val settings = settings().let {
            it.copy(
                providers = it.providers + (
                    "other" to it.providers.getValue("test").copy(
                        label = "Other",
                        baseUrl = other.baseUrl,
                        model = "other-model",
                    )
                    ),
                translateProvider = "other",
            )
        }

        server.enqueue(openAiReply("Corrigé"))
        other.enqueue(openAiReply("Hello"))

        val engine = engine(settings)
        assertEquals("Corrigé", engine.revise("corriger"))
        assertEquals("Hello", engine.translate("Bonjour", "en"))

        // One request each, and the translate one carried the override's model.
        assertEquals(1, server.requestCount)
        assertEquals(1, other.requestCount)
        val translated = Json.parseToJsonElement(other.takeRequest().body).jsonObject
        assertEquals("other-model", translated["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an override pointing at a deleted provider falls back to the default`() = runTest {
        server.enqueue(openAiReply("Corrigé"))

        val engine = TextEngine(settings().copy(reviseProvider = "ghost"), { "sk-test" }, server.client)

        assertEquals("Corrigé", engine.revise("corriger"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `each action uses its own timeout and limit settings`() = runTest {
        val settings = settings(
            revise = ReviseSettings(characterLimit = 5),
            translate = TranslateSettings(characterLimit = 500),
        )
        server.enqueue(openAiReply("Hello"))

        // Over the revise cap but well under the translate one.
        val text = "Bonjour tout le monde"
        val engine = engine(settings)

        val reviseError = runCatching { engine.revise(text) }.exceptionOrNull() as AiException
        assertTrue(reviseError.message!!.contains("over the 5 limit"))
        assertEquals("Hello", engine.translate(text, "en"))
    }

    // --- guard rails -------------------------------------------------------------------------

    @Test
    fun `a missing api key fails before any request is made`() = runTest {
        val error = runCatching { engine(key = "").revise("text") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.NotConfigured, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unconfigured provider fails before any request is made`() = runTest {
        val broken = settings().let {
            it.copy(providers = mapOf("test" to it.providers.getValue("test").copy(model = "")))
        }

        val error = runCatching { engine(broken).revise("text") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.NotConfigured, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an over-long selection is refused before any request is made`() = runTest {
        val small = settings(revise = ReviseSettings(characterLimit = 10))

        val error = runCatching { engine(small).revise("a".repeat(50)) }
            .exceptionOrNull() as AiException

        assertTrue(error.message!!.contains("over the 10 limit"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank selection is refused`() = runTest {
        val error = runCatching { engine().revise("   ") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Empty, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unauthorized response becomes an auth error`() = runTest {
        server.enqueue(FakeResponse(401, """{"error":{"message":"bad key"}}"""))

        val error = runCatching { engine().revise("text") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Auth, error.kind)
        assertTrue(error.message!!.contains("TestProvider"))
    }

    @Test
    fun `a rate limited response becomes a rate limit error`() = runTest {
        server.enqueue(FakeResponse(429))

        val error = runCatching { engine().revise("text") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.RateLimit, error.kind)
    }

    @Test
    fun `an empty completion is reported rather than replacing the user's text`() = runTest {
        server.enqueue(openAiReply("   "))

        val error = runCatching { engine().revise("text") }.exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Empty, error.kind)
    }
}
