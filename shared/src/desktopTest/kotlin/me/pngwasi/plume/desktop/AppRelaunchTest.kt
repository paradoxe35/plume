package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A restart has to be a handover, not an overlap.
 *
 * Starting the replacement immediately leaves two Plumes alive — two tray icons, two listeners
 * fighting over the same shortcuts — and the new one would meet the old one's instance lock and
 * stop again, so the restart button would look like it did nothing.
 */
class AppRelaunchTest {

    private val temporary = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporary.forEach { it.delete() }
    }

    @Test
    fun `the path travels as an argument, so a space in it is not shell syntax`() {
        val command = AppRelaunch.afterExit(42, "open", "/Applications/My Plume.app")

        assertEquals("sh", command.first())
        assertEquals(listOf("sh", "42", "/Applications/My Plume.app"), command.takeLast(3))
        assertFalse(
            command[2].contains("My Plume"),
            "the path was pasted into the script, where a space splits it in two",
        )
    }

    @Test
    fun `the launch waits for the process to be gone`() {
        if (DesktopOs.current == DesktopOs.Windows) return

        val marker = temporaryFile("plume-relaunch-marker")
        marker.delete()
        val script = temporaryFile("plume-relaunch-script").apply {
            writeText("#!/bin/sh\n: > \"${marker.absolutePath}\"\n")
            setExecutable(true)
        }

        val leaving = ProcessBuilder("sh", "-c", "sleep 1").start()
        ProcessBuilder(AppRelaunch.afterExit(leaving.pid(), "exec", script.absolutePath))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        Thread.sleep(300)
        assertFalse(marker.exists(), "the replacement started while the old copy was still running")

        leaving.waitFor()
        assertTrue(waitForFile(marker), "the replacement never started, so Plume would not come back")
    }

    /** No `sh` on Windows, so the wait is PowerShell's, and it still has to name this process. */
    @Test
    fun `windows waits on the same pid`() {
        val command = AppRelaunch.relaunchCommand(DesktopOs.Windows, 1234, "C:\\Plume\\Plume.exe")

        assertTrue(command.any { it.contains("Wait-Process -Id 1234") }, command.toString())
        assertTrue(command.any { it.contains("C:\\Plume\\Plume.exe") }, command.toString())
    }

    /** Single quotes end PowerShell's quoting; doubling them is how one is written literally. */
    @Test
    fun `a quote in the windows path cannot end the powershell string`() {
        val command = AppRelaunch.relaunchCommand(DesktopOs.Windows, 1, "C:\\it's\\Plume.exe")

        assertTrue(command.any { it.contains("'C:\\it''s\\Plume.exe'") }, command.toString())
    }

    private fun temporaryFile(prefix: String): File =
        File.createTempFile(prefix, "").also { temporary.add(it) }

    private fun waitForFile(file: File): Boolean {
        repeat(60) {
            if (file.exists()) return true
            Thread.sleep(100)
        }
        return false
    }
}
