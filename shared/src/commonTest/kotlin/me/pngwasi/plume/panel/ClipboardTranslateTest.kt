package me.pngwasi.plume.panel

import me.pngwasi.plume.FakeResponse
import me.pngwasi.plume.FakeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.pngwasi.plume.ai.ReasoningSupport
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.TranslateSettings
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Translating an incoming message from the clipboard.
 *
 * The rule that matters: this must never write to the field. The user is reading what someone sent
 * them while a half-typed reply may be sitting there, and overwriting it would be the opposite of
 * helpful.
 */
class ClipboardTranslateTest {

    private lateinit var server: FakeServer
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

    @BeforeTest
    fun setUp() {
        server = FakeServer()
        scope = CoroutineScope(Dispatchers.Default)
        ReasoningSupport.reset()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun settings(translate: TranslateSettings = TranslateSettings()) = AppSettings(
        defaultProvider = "test",
        providers = mapOf(
            "test" to ProviderConfig(
                label = "TestProvider",
                kind = ProviderKind.OpenAiCompatible,
                baseUrl = server.baseUrl,
                model = "test-model",
            ),
        ),
        translate = translate,
    )

    private fun reply(text: String) = FakeResponse(200)
        .setBody("""{"choices":[{"message":{"content":"$text"}}]}""")

    private fun controller(
        editor: EditorBridge,
        clip: ClipboardSource,
        settings: AppSettings = settings(),
    ) = PanelController(
        scope = scope,
        bridge = editor,
        loadSettings = { settings },
        apiKeyFor = { "sk-test" },
        clipboard = clip,
        http = server.client,
    )

    private fun PanelController.settle() = runBlocking {
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

        assertTrue((controller.state.value as PanelState.Ready).hasClipboard)
    }

    @Test
    fun `the action is not offered when the clipboard is empty`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard(null))

        controller.refresh()

        assertFalse((controller.state.value as PanelState.Ready).hasClipboard)
    }

    @Test
    fun `whitespace on the clipboard does not count as copied text`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard("   \n "))

        controller.refresh()

        assertFalse((controller.state.value as PanelState.Ready).hasClipboard)
    }

    /** The clipboard action must work even when the user has not typed anything yet. */
    @Test
    fun `the action is offered with an empty field`() {
        val controller = controller(FakeEditor(""), FakeClipboard("Hello"))

        controller.refresh()

        val state = controller.state.value as PanelState.Ready
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

        val state = controller.state.value as PanelState.Reading
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

        val body = server.takeRequest().body
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

        val state = controller.state.value as PanelState.PickLanguage
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

        assertTrue(controller.state.value is PanelState.Reading)
    }

    /**
     * The pinned-target path used to reach the work by calling readClipboard(), which begins by
     * cancelling `running` — and `running` was the job making the call. It finished by luck, so it
     * passed locally and failed under CI load. The job that starts the action must survive it.
     */
    @Test
    fun `the pinned-target path does not cancel its own job`() {
        server.enqueue(reply("Bonjour"))
        val controller = controller(
            FakeEditor("draft"),
            FakeClipboard("Hello"),
            settings(TranslateSettings(defaultTarget = "fr")),
        )

        controller.startReadClipboard()
        val started = controller.inFlight
        controller.settle()

        assertNotNull(started)
        assertFalse(started.isCancelled, "the action cancelled the job that was running it")
        assertTrue(controller.state.value is PanelState.Reading)
    }

    @Test
    fun `starting with nothing copied fails without calling the provider`() {
        val controller = controller(FakeEditor("draft"), FakeClipboard(null))

        controller.startReadClipboard()
        controller.settle()

        assertTrue(controller.state.value is PanelState.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `back returns to the actions`() {
        server.enqueue(reply("Bonjour"))
        val controller = controller(FakeEditor("draft"), FakeClipboard("Hello"))
        controller.readClipboard("fr")
        controller.settle()

        controller.closeReading()

        assertTrue(controller.state.value is PanelState.Ready)
    }

    @Test
    fun `a failure offers to retry the same language`() {
        server.enqueue(FakeResponse(500))
        val controller = controller(FakeEditor("draft"), FakeClipboard("Hello"))

        controller.readClipboard("de")
        controller.settle()

        assertEquals(
            PanelState.Retry.ReadClipboard("de"),
            (controller.state.value as PanelState.Failed).retry,
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

        assertTrue(controller.state.value is PanelState.Reading)
    }

    // --- clearing the field --------------------------------------------------------------------

    @Test
    fun `clear empties the field`() {
        val editor = FakeEditor("something to remove")
        val controller = controller(editor, FakeClipboard(null))

        controller.clearField()

        assertTrue(editor.cleared)
        assertEquals("", editor.full)
        assertNull((controller.state.value as PanelState.Ready).scope)
    }

    @Test
    fun `a clipboard that throws is treated as empty rather than crashing`() {
        val hostile = object : ClipboardSource {
            override fun read(): String = throw SecurityException("no access")
        }
        val controller = controller(FakeEditor("draft"), hostile)

        controller.refresh()

        assertFalse((controller.state.value as PanelState.Ready).hasClipboard)
    }
}
