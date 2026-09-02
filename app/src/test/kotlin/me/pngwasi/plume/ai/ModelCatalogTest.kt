package me.pngwasi.plume.ai

import kotlinx.coroutines.test.runTest
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelCatalogTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(kind: ProviderKind) = ProviderConfig(
        label = "TestProvider",
        kind = kind,
        baseUrl = server.url("/").toString().trimEnd('/'),
        model = "whatever",
    )

    // --- parsing ------------------------------------------------------------------------------

    @Test
    fun `openai catalog is read from the data array and sorted`() {
        val body = """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"},{"id":"babbage"}]}"""

        assertEquals(listOf("babbage", "gpt-4o", "gpt-4o-mini"), ModelCatalog.parseOpenAi(body))
    }

    @Test
    fun `openai catalog tolerates a missing or malformed body`() {
        assertTrue(ModelCatalog.parseOpenAi("").isEmpty())
        assertTrue(ModelCatalog.parseOpenAi("<html>").isEmpty())
        assertTrue(ModelCatalog.parseOpenAi("""{"object":"list"}""").isEmpty())
    }

    @Test
    fun `gemini catalog strips the models prefix`() {
        val body = """
            {"models":[
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()

        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), ModelCatalog.parseGemini(body))
    }

    /** Embedding models share the endpoint and would fail at call time if offered. */
    @Test
    fun `gemini catalog drops models that cannot generate content`() {
        val body = """
            {"models":[
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()

        val models = ModelCatalog.parseGemini(body)

        assertEquals(listOf("gemini-2.5-flash"), models)
        assertFalse(models.contains("text-embedding-004"))
    }

    @Test
    fun `gemini catalog keeps models that do not declare their methods`() {
        val body = """{"models":[{"name":"models/gemini-x"}]}"""

        assertEquals(listOf("gemini-x"), ModelCatalog.parseGemini(body))
    }

    // --- requests -----------------------------------------------------------------------------

    @Test
    fun `openai catalog is fetched from the models endpoint with a bearer token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-4o"}]}"""))

        val models = ModelCatalog.list(config(ProviderKind.OpenAiCompatible), "sk-test")

        assertEquals(listOf("gpt-4o"), models)

        val request = server.takeRequest()
        assertEquals("/models", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
    }

    /** OpenRouter serves its catalogue unauthenticated, so the list works before a key is entered. */
    @Test
    fun `openai catalog is fetched without auth when no key is set yet`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"free-model"}]}"""))

        ModelCatalog.list(config(ProviderKind.OpenAiCompatible), "")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `gemini catalog is fetched from v1beta models with the api key header`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"models":[{"name":"models/gemini-2.5-flash"}]}"""),
        )

        val models = ModelCatalog.list(config(ProviderKind.Gemini), "sk-test")

        assertEquals(listOf("gemini-2.5-flash"), models)

        val request = server.takeRequest()
        assertEquals("/v1beta/models", request.path)
        assertEquals("sk-test", request.getHeader("x-goog-api-key"))
    }

    @Test
    fun `an unauthorized catalog request raises an auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { ModelCatalog.list(config(ProviderKind.OpenAiCompatible), "bad") }
            .exceptionOrNull() as AiException

        assertEquals(AiException.Kind.Auth, error.kind)
    }

    /** Gateways without a catalogue endpoint are normal; the caller falls back to free text. */
    @Test
    fun `a provider with no models endpoint raises rather than returning junk`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val error = runCatching { ModelCatalog.list(config(ProviderKind.OpenAiCompatible), "sk") }
            .exceptionOrNull()

        assertTrue(error is AiException)
    }
}
