package me.pngwasi.plume.ai

import kotlin.test.assertEquals
import kotlin.test.Test

class ResponseCleanerTest {

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("Bonjour le monde", ResponseCleaner.clean("Bonjour le monde"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Bonjour", ResponseCleaner.clean("\n  Bonjour  \n"))
    }

    @Test
    fun `a wrapping code fence is removed`() {
        val raw = "```\nJ'ai mangé une pomme.\n```"

        assertEquals("J'ai mangé une pomme.", ResponseCleaner.clean(raw))
    }

    @Test
    fun `a language-tagged fence is removed`() {
        val raw = "```text\nHello there\n```"

        assertEquals("Hello there", ResponseCleaner.clean(raw))
    }

    @Test
    fun `multi-line content inside a fence keeps its line breaks`() {
        val raw = "```\nline one\nline two\n```"

        assertEquals("line one\nline two", ResponseCleaner.clean(raw))
    }

    /** A genuine snippet the user selected must survive — only wrapping is stripped. */
    @Test
    fun `a fence that does not close at the end is left alone`() {
        val raw = "```kotlin\nval x = 1\n```\nand then some prose"

        assertEquals(raw, ResponseCleaner.clean(raw))
    }

    @Test
    fun `wrapping double quotes are removed`() {
        assertEquals("Bonjour", ResponseCleaner.clean("\"Bonjour\""))
    }

    @Test
    fun `wrapping curly quotes are removed`() {
        assertEquals("Bonjour", ResponseCleaner.clean("“Bonjour”"))
    }

    @Test
    fun `wrapping french guillemets are removed`() {
        assertEquals("Bonjour", ResponseCleaner.clean("«Bonjour»"))
    }

    @Test
    fun `quotes inside the text are preserved`() {
        val raw = "Il a dit \"oui\" puis il est parti."

        assertEquals(raw, ResponseCleaner.clean(raw))
    }

    @Test
    fun `a quoted phrase inside longer prose is not unwrapped`() {
        val raw = "\"oui\" et \"non\""

        assertEquals(raw, ResponseCleaner.clean(raw))
    }

    @Test
    fun `an apostrophe at the edges does not trigger unwrapping`() {
        // Leading and trailing apostrophes here are part of French contractions, not quoting.
        val raw = "'tis a test'"

        assertEquals("tis a test", ResponseCleaner.clean(raw))
    }

    @Test
    fun `fence and quotes together are both removed`() {
        assertEquals("Bonjour", ResponseCleaner.clean("```\n\"Bonjour\"\n```"))
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals("", ResponseCleaner.clean(""))
        assertEquals("", ResponseCleaner.clean("   \n  "))
    }
}
