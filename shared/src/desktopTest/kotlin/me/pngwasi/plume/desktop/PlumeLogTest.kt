package me.pngwasi.plume.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log file.
 *
 * It exists so that a background application can be reported on at all: launched from a desktop
 * entry there is no terminal, and an uncaught exception otherwise kills a thread in silence. What
 * matters is that it records failures, keeps the user's own writing out, and cannot fill a disk.
 */
class PlumeLogTest {

    @AfterTest
    fun tearDown() {
        PlumeLog.file.writeText("")
    }

    @Test
    fun `a message is written with a timestamp`() {
        PlumeLog.info("Revise requested")

        val last = PlumeLog.tail().last()
        assertTrue(last.endsWith("Revise requested"), last)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(last), last)
    }

    /** Errors have to stand out, since scanning for them is the whole point of reading this. */
    @Test
    fun `an error is marked as one`() {
        PlumeLog.error("Revise failed")

        assertTrue(PlumeLog.tail().last().contains("ERROR Revise failed"))
    }

    @Test
    fun `an exception is recorded with its stack trace`() {
        PlumeLog.error("Revise failed", IllegalStateException("the clipboard was busy"))

        val logged = PlumeLog.tail().joinToString("\n")
        assertTrue(logged.contains("IllegalStateException"), logged)
        assertTrue(logged.contains("the clipboard was busy"), logged)
        assertTrue(logged.contains("at me.pngwasi.plume"), "the stack trace is missing")
    }

    @Test
    fun `the tail returns the most recent lines last`() {
        PlumeLog.info("first")
        PlumeLog.info("second")

        val tail = PlumeLog.tail()
        assertTrue(tail[tail.lastIndex - 1].endsWith("first"))
        assertTrue(tail.last().endsWith("second"))
    }

    @Test
    fun `the tail is bounded`() {
        repeat(50) { PlumeLog.info("line $it") }

        assertEquals(10, PlumeLog.tail(10).size)
    }

    @Test
    fun `reading an empty log is not an error`() {
        PlumeLog.file.writeText("")

        assertTrue(PlumeLog.tail().isEmpty())
    }

    /**
     * The log is what gets attached to a bug report, so it must not accumulate what the user was
     * writing. This is a reminder in test form: nothing in the action path logs the text itself.
     */
    @Test
    fun `the log records what happened rather than what was written`() {
        PlumeLog.info("Revise requested")
        PlumeLog.info("Revise finished")

        val logged = PlumeLog.tail().joinToString("\n")
        assertFalse(logged.contains("jai manger une pomme"))
        assertTrue(logged.contains("Revise requested"))
    }
}
