package me.pngwasi.plume.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two Plumes at once is not a cosmetic problem: both register the same global shortcuts, so which
 * one answers a keypress is a race, and both write the same settings file.
 */
class SingleInstanceTest {

    private val directory: File = createTempDirectory()
    private val instances = mutableListOf<SingleInstance>()

    @AfterTest
    fun cleanUp() {
        instances.forEach { it.release() }
        directory.deleteRecursively()
    }

    private fun instance(): SingleInstance = SingleInstance(directory).also { instances.add(it) }

    @Test
    fun `the first launch runs`() {
        assertTrue(instance().claim {})
    }

    @Test
    fun `a second launch stops, and the first is told to show itself`() {
        val shown = CountDownLatch(1)
        assertTrue(instance().claim { shown.countDown() })

        assertFalse(instance().claim {}, "two copies would fight over the same shortcuts")
        assertTrue(
            shown.await(5, TimeUnit.SECONDS),
            "the second launch stopped without handing over, so clicking the launcher did nothing",
        )
    }

    /** However the process ended, the next one has to be able to start. */
    @Test
    fun `releasing lets the next launch through`() {
        val first = instance()
        assertTrue(first.claim {})
        first.release()

        assertTrue(instance().claim {})
    }

    private fun createTempDirectory(): File =
        File.createTempFile("plume-instance", "").let {
            it.delete()
            it.mkdirs()
            it
        }
}
