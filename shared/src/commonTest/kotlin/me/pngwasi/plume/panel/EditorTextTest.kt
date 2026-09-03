package me.pngwasi.plume.panel

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/** Scope resolution — the rule that decides what an action operates on. */
class EditorTextTest {

    @Test
    fun `with no selection the whole field is the target`() {
        val text = EditorText(full = "bonjour tout le monde", selection = "")

        assertFalse(text.hasSelection)
        assertEquals("bonjour tout le monde", text.target)
    }

    @Test
    fun `with a selection only the selection is the target`() {
        val text = EditorText(full = "bonjour tout le monde", selection = "tout le monde")

        assertTrue(text.hasSelection)
        assertEquals("tout le monde", text.target)
    }

    @Test
    fun `a whitespace-only selection is treated as no selection`() {
        val text = EditorText(full = "bonjour", selection = "   ")

        assertFalse(text.hasSelection)
        assertEquals("bonjour", text.target)
    }

    @Test
    fun `an empty field has nothing to act on`() {
        assertTrue(EditorText("", "").isEmpty)
        assertTrue(EditorText("   \n ", "").isEmpty)
    }

    @Test
    fun `a field with text is not empty`() {
        assertFalse(EditorText("bonjour", "").isEmpty)
    }

    @Test
    fun `preview collapses newlines and runs of spaces onto one line`() {
        assertEquals("a b c", "  a\n\n b    c \n".collapseWhitespace())
    }
}
