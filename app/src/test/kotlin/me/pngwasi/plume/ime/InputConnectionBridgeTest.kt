package me.pngwasi.plume.ime

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import me.pngwasi.plume.panel.EditorBridge
import me.pngwasi.plume.panel.EditorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The read/replace contract against a real editor and a real InputConnection.
 *
 * This is the code that rewrites the user's message, so it is exercised through the actual Android
 * text machinery rather than a hand-written fake — a fake would happily agree with a wrong
 * implementation.
 */
@RunWith(RobolectricTestRunner::class)
class InputConnectionBridgeTest {

    private class Editor(text: String, selStart: Int? = null, selEnd: Int? = null) {
        val view = EditText(RuntimeEnvironment.getApplication()).apply {
            setText(text)
            if (selStart != null && selEnd != null) setSelection(selStart, selEnd)
            else setSelection(text.length)
        }
        private val connection = view.onCreateInputConnection(EditorInfo())
        val bridge: EditorBridge = InputConnectionBridge { connection }
        val content: String get() = view.text.toString()
    }

    @Test
    fun `reads the whole field when nothing is selected`() {
        val editor = Editor("jai manger une pomme")

        val read = editor.bridge.read()

        assertEquals("jai manger une pomme", read?.full)
        assertFalse(read!!.hasSelection)
    }

    @Test
    fun `reads the selection when there is one`() {
        val editor = Editor("bonjour tout le monde", selStart = 8, selEnd = 21)

        val read = editor.bridge.read()

        assertEquals("tout le monde", read?.selection)
        assertTrue(read!!.hasSelection)
        assertEquals("tout le monde", read.target)
    }

    @Test
    fun `replaces the whole field when nothing is selected`() {
        val editor = Editor("jai manger une pomme")

        assertTrue(editor.bridge.apply("J'ai mangé une pomme."))

        assertEquals("J'ai mangé une pomme.", editor.content)
    }

    @Test
    fun `replaces only the selection when there is one`() {
        val editor = Editor("bonjour tout le monde", selStart = 8, selEnd = 21)

        assertTrue(editor.bridge.apply("everyone"))

        assertEquals("bonjour everyone", editor.content)
    }

    @Test
    fun `replacing an empty field just inserts the text`() {
        val editor = Editor("")

        assertTrue(editor.bridge.apply("bonjour"))

        assertEquals("bonjour", editor.content)
    }

    @Test
    fun `replacement survives multi-line content`() {
        val editor = Editor("line one\nline two")

        assertTrue(editor.bridge.apply("fixed one\nfixed two"))

        assertEquals("fixed one\nfixed two", editor.content)
    }

    @Test
    fun `a longer replacement does not truncate`() {
        val editor = Editor("hi")

        editor.bridge.apply("a considerably longer corrected sentence")

        assertEquals("a considerably longer corrected sentence", editor.content)
    }

    @Test
    fun `unicode and accents round-trip intact`() {
        val editor = Editor("ca va")

        editor.bridge.apply("ça va très bien 🙂")

        assertEquals("ça va très bien 🙂", editor.content)
    }

    /** Every entry point must cope with the connection having gone away between frames. */
    @Test
    fun `a missing connection reads null and refuses to write`() {
        val bridge = InputConnectionBridge { null }

        assertNull(bridge.read())
        assertFalse(bridge.apply("anything"))
    }
}
