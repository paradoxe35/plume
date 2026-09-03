package me.pngwasi.plume.ime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.TranslateSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The panel's behaviour, driven against a fake editor and a local server. */
class ImePanelControllerTest {

    private lateinit var server: MockWebServer

    /** Stands in for the focused text field. */
    private class FakeEditor(
        var full: String = "",
        var selection: String = "",
        val writable: Boolean = true,
    ) : EditorBridge {
        var applied: String? = null
        override fun read() = EditorText(full, selection)
        override fun apply(text: String): Boolean {
            if (!writable) return false
            applied = text
            if (selection.isNotBlank()) {
                full = full.replace(selection, text)
                selection = ""
            } else {
                full = text
            }
            return true
        }
    }

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    /**
     * Actions do real network IO on Dispatchers.IO, which a virtual-time test scheduler cannot
     * wait for — advanceUntilIdle returns while the request is still in flight. Joining the job the
     * controller is actually running is the only reliable barrier.
     */
    private fun ImePanelController.settle() = runBlocking {
        repeat(5) {
            val job = inFlight ?: return@runBlocking
            job.join()
            if (inFlight === job) return@runBlocking
        }
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
        .setBody("""{"choices":[{"message":{"content":${quote(text)}}}]}""")

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun controller(
        editor: EditorBridge,
        settings: AppSettings = settings(),
        key: String = "sk-test",
    ) = ImePanelController(
        scope = scope,
        bridge = editor,
        loadSettings = { settings },
        apiKeyFor = { key },
    )

    // --- scope reporting ----------------------------------------------------------------------

    @Test
    fun `refresh reports the whole field when nothing is selected`() {
        val editor = FakeEditor(full = "bonjour")
        val controller = controller(editor)

        controller.refresh()

        val state = controller.state.value as ImeState.Ready
        assertEquals(ActionScope.WholeField, state.scope)
        assertEquals("bonjour", state.preview)
    }

    @Test
    fun `refresh reports the selection when there is one`() {
        val editor = FakeEditor(full = "bonjour tout le monde", selection = "tout le monde")
        val controller = controller(editor)

        controller.refresh()

        assertEquals(ActionScope.Selection, (controller.state.value as ImeState.Ready).scope)
    }

    @Test
    fun `an empty field offers no action`() {
        val controller = controller(FakeEditor(full = "   "))

        controller.refresh()

        assertNull((controller.state.value as ImeState.Ready).scope)
    }

    // --- revise -------------------------------------------------------------------------------

    @Test
    fun `revise writes the corrected text back into the field`() {
        server.enqueue(reply("J'ai mangé une pomme."))
        val editor = FakeEditor(full = "jai manger une pomme")
        val controller = controller(editor)

        controller.revise()
        controller.settle()

        assertEquals("J'ai mangé une pomme.", editor.applied)
        assertEquals("J'ai mangé une pomme.", editor.full)
    }

    @Test
    fun `revise confirms once it has replaced the text`() {
        server.enqueue(reply("Corrigé"))
        val controller = controller(FakeEditor(full = "corriger"))

        controller.revise()
        controller.settle()

        val state = controller.state.value as ImeState.Ready
        assertEquals("Revised", state.confirmation)
    }

    @Test
    fun `revise only sends the selection when one exists`() {
        server.enqueue(reply("monde"))
        val editor = FakeEditor(full = "bonjour tout le monde", selection = "tout le monde")
        val controller = controller(editor)

        controller.revise()
        controller.settle()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("tout le monde"))
        assertEquals("bonjour monde", editor.full)
    }

    @Test
    fun `revise on an empty field fails without calling the provider`() {
        val controller = controller(FakeEditor(full = ""))

        controller.revise()
        controller.settle()

        assertTrue(controller.state.value is ImeState.Failed)
        assertEquals(0, server.requestCount)
    }

    /**
     * The user keeps typing while the request is in flight. Writing the stale result back would
     * silently destroy whatever they added, so the edit is abandoned instead.
     */
    @Test
    fun `a result is discarded when the field changed while it was in flight`() {
        val editor = FakeEditor(full = "corriger")
        // Editing from the server dispatcher: by the time the request lands, the controller has
        // certainly taken its snapshot, so the change is guaranteed to be concurrent.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                editor.full = "corriger et plus encore"
                return reply("Corrigé")
            }
        }
        val controller = controller(editor)

        controller.revise()
        controller.settle()

        assertNull(editor.applied)
        val state = controller.state.value as ImeState.Failed
        assertTrue(state.message.contains("changed while"))
        assertEquals(ImeState.Retry.Revise, state.retry)
    }

    @Test
    fun `a field that refuses the write reports it`() {
        server.enqueue(reply("Corrigé"))
        val controller = controller(FakeEditor(full = "corriger", writable = false))

        controller.revise()
        controller.settle()

        assertTrue((controller.state.value as ImeState.Failed).message.contains("write back"))
    }

    // --- translate ----------------------------------------------------------------------------

    @Test
    fun `translate opens the picker when no default target is pinned`() {
            val controller = controller(
                FakeEditor(full = "bonjour"),
                settings(TranslateSettings(favorites = listOf("en", "es"))),
            )

            controller.startTranslate()
            controller.settle()

            val state = controller.state.value as ImeState.PickLanguage
            assertEquals(listOf("en", "es"), state.favorites)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `translate skips the picker when a default target is pinned`() {
        server.enqueue(reply("Hello"))
        val editor = FakeEditor(full = "bonjour")
        val controller = controller(editor, settings(TranslateSettings(defaultTarget = "en")))

        controller.startTranslate()
        controller.settle()

        assertEquals("Hello", editor.full)
    }

    @Test
    fun `picking a language translates into it`() {
        server.enqueue(reply("Hola"))
        val editor = FakeEditor(full = "bonjour")
        val controller = controller(editor)

        controller.translate("es")
        controller.settle()

        assertEquals("Hola", editor.applied)
        assertTrue(server.takeRequest().body.readUtf8().contains("Spanish"))
    }

    @Test
    fun `cancelling the picker returns to the ready state`() {
        val controller = controller(
            FakeEditor(full = "bonjour"),
            settings(TranslateSettings(favorites = listOf("en"))),
        )
        controller.startTranslate()
        controller.settle()

        controller.cancelPicker()

        assertTrue(controller.state.value is ImeState.Ready)
    }

    // --- failures -----------------------------------------------------------------------------

    @Test
    fun `a missing api key points the user at settings`() {
        val controller = controller(FakeEditor(full = "bonjour"), key = "")

        controller.revise()
        controller.settle()

        val state = controller.state.value as ImeState.Failed
        assertTrue(state.settingsFix)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an auth failure points the user at settings and offers retry`() {
            server.enqueue(MockResponse().setResponseCode(401))
            val controller = controller(FakeEditor(full = "bonjour"))

            controller.revise()
            controller.settle()

            val state = controller.state.value as ImeState.Failed
            assertTrue(state.settingsFix)
            assertEquals(ImeState.Retry.Revise, state.retry)
        }

    @Test
    fun `a translate failure offers retry with the same target`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val controller = controller(FakeEditor(full = "bonjour"))

        controller.translate("de")
        controller.settle()

        assertEquals(
            ImeState.Retry.Translate("de"),
            (controller.state.value as ImeState.Failed).retry,
        )
    }
}
