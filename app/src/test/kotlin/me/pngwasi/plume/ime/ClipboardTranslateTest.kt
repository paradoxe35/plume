package me.pngwasi.plume.ime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.pngwasi.plume.ai.ReasoningSupport
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.TranslateSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Translating an incoming message from the clipboard.
 *
 * The rule that matters: this must never write to the field. The user is reading what someone sent
 * them while a half-typed reply may be sitting there, and overwriting it would be the opposite of
 * helpful.
 */
class ClipboardTranslateTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    private class FakeEditor(var full: String = "", var selection: String = "") : EditorBridge {
        var applied: String? = null
        var cleared = false
        override fun read() = EditorText(full, selection)
        override fun apply(text: String): Boolean { applied = text; full = text; return true }
        override fun clearAll(): Boolean { cleared = true; full = ""; return true }
    }

    private class FakeClipboard(var content: String? = null) : ClipboardSource {
        override fun read() = content
    }

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
        scope = CoroutineScope(Dispatchers.Default)
        ReasoningSupport.reset()
    }

    @After
    fun tearDown() {
        scope.cancel(); server.shutdown()
    }

    private fun settings(translate: TranslateSettings = TranslateSettings()) = AppSettings(
        defaultProvider = "test",
        providers = mapOf(
            "test" to ProviderConfig(
                label = "TestProvider",
                kind = ProviderKind.OpenAiCompatible,
                baseUrl = server.url("/").toString().trimEnd('/'),
                model = "test-model",
            ),
        ),
        translate = translate,
    )

    private fun reply(text: String) = MockResponse().setResponseCode(200)
        .setBody("""{"choices":[{"message":{"content":"$text"}}]}""")

    private fun controller(
        editor: EditorBridge,
        clip: ClipboardSource,
        settings: AppSettings = settings(),
    ) = ImePanelController(
        scope = scope,
        bridge = editor,
        loadSettings = { settings },
        apiKeyFor = { "sk-test" },
        clipboard = clip,
    )

    private fun ImePanelController.settle() = runBlocking {
        repeat(5) {
            val job = inFlight ?: return@runBlocking
            job.join()
            if (inFlight === job) return@runBlocking
        }
    }

    // --- availability -------------------------------------------------------------------------

    @Test
    fun `the action is offered when something is copied`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard("Hello there"))

        controller.refresh()

        assertTrue((controller.state.value as ImeState.Ready).hasClipboard)
    }

    @Test
    fun `the action is not offered when the clipboard is empty`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard(null))

        controller.refresh()

        assertFalse((controller.state.value as ImeState.Ready).hasClipboard)
    }

    @Test
    fun `whitespace on the clipboard does not count as copied text`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard("   \n "))

        controller.refresh()

        assertFalse((controller.state.value as ImeState.Ready).hasClipboard)
    }

    /** The clipboard action must work even when the user has not typed anything yet. */
    @Test
    fun `the action is offered with an empty field`() {
        val controller = controller(FakeEditor(""), FakeClipboard("Hello"))

        controller.refresh()

        val state = controller.state.value as ImeState.Ready
        assertNull(state.scope)
        assertTrue(state.hasClipboard)
    }

    // --- the flow -----------------------------------------------------------------------------

    @Test
    fun `picking a language shows the translation in the panel`() {
        server.enqueue(reply("Bonjour"))
        val editor = FakeEditor("my draft")
        val controller = controller(editor, FakeClipboard("Hello there"))

        controller.readClipboard("fr")
        controller.settle()

        val state = controller.state.value as ImeState.Reading
        assertEquals("Bonjour", state.translated)
        assertEquals("Hello there", state.original)
    }

    /** The whole point: the user's draft is untouched. */
    @Test
    fun `the field is never written to`() {
        server.enqueue(reply("Bonjour"))
        val editor = FakeEditor("my half-typed reply")
        val controller = controller(editor, FakeClipboard("Hello there"))

        controller.readClipboard("fr")
        controller.settle()

        assertNull(editor.applied)
        assertEquals("my half-typed reply", editor.full)
    }

    @Test
    fun `the clipboard text is what gets sent, not the field`() {
        server.enqueue(reply("Bonjour"))
        val controller = controller(FakeEditor("my draft"), FakeClipboard("Hello there"))

        controller.readClipboard("fr")
        controller.settle()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("Hello there"))
        assertFalse(body.contains("my draft"))
    }

    @Test
    fun `the picker is opened for the clipboard, not the field`() {
        val controller = controller(
            FakeEditor("draft"),
            FakeClipboard("Hello"),
            settings(TranslateSettings(favorites = listOf("fr"))),
        )

        controller.startReadClipboard()
        controller.settle()

        val state = controller.state.value as ImeState.PickLanguage
        assertEquals(TranslationSubject.Clipboard, state.subject)
    }

    @Test
    fun `a pinned default target skips the picker`() {
        server.enqueue(reply("Bonjour"))
        val controller = controller(
            FakeEditor("draft"),
            FakeClipboard("Hello"),
            settings(TranslateSettings(defaultTarget = "fr")),
        )

        controller.startReadClipboard()
        controller.settle()

        assertTrue(controller.state.value is ImeState.Reading)
    }

    @Test
    fun `starting with nothing copied fails without calling the provider`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard(null))

        controller.startReadClipboard()
        controller.settle()

        assertTrue(controller.state.value is ImeState.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `back returns to the actions`() {
        server.enqueue(reply("Bonjour"))
        val controller = controller(FakeEditor("draft"), FakeClipboard("Hello"))
        controller.readClipboard("fr")
        controller.settle()

        controller.closeReading()

        assertTrue(controller.state.value is ImeState.Ready)
    }

    @Test
    fun `a failure offers to retry the same language`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val controller = controller(FakeEditor("draft"), FakeClipboard("Hello"))

        controller.readClipboard("de")
        controller.settle()

        assertEquals(
            ImeState.Retry.ReadClipboard("de"),
            (controller.state.value as ImeState.Failed).retry,
        )
    }

    /** A field update must not close a message the user is still reading. */
    @Test
    fun `typing does not dismiss the translation`() {
        server.enqueue(reply("Bonjour"))
        val editor = FakeEditor("draft")
        val controller = controller(editor, FakeClipboard("Hello"))
        controller.readClipboard("fr")
        controller.settle()

        editor.full = "draft plus more"
        controller.onFieldChanged()

        assertTrue(controller.state.value is ImeState.Reading)
    }

    // --- clearing the field --------------------------------------------------------------------

    @Test
    fun `clear empties the field`() {
        val editor = FakeEditor("something to remove")
        val controller = controller(editor, FakeClipboard(null))

        controller.clearField()

        assertTrue(editor.cleared)
        assertEquals("", editor.full)
        assertNull((controller.state.value as ImeState.Ready).scope)
    }

    @Test
    fun `a clipboard that throws is treated as empty rather than crashing`() {
        val hostile = object : ClipboardSource {
            override fun read(): String = throw SecurityException("no access")
        }
        val controller = controller(FakeEditor("draft"), hostile)

        controller.refresh()

        assertFalse((controller.state.value as ImeState.Ready).hasClipboard)
    }
}
