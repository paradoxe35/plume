package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The clipboard dance, and specifically the ways MyReviser got it wrong.
 *
 * Every one of these failures is silent in production: the user sees their text replaced with
 * something plausible but wrong, or their clipboard quietly emptied. None of them shows up in a
 * happy-path manual test, which is why they lasted.
 */
class TextCaptureTest {

    /**
     * Models a real clipboard closely enough for the sequencing to matter: a copy only changes it
     * when something is selected, and both copy and paste can be made to fail or lag.
     */
    private class FakeSystem(
        var clipboard: String? = null,
        var selection: String? = null,
    ) : SystemInput {
        var saved: String? = null
        var savedCalled = false
        var restoreCalled = false
        var modifiersReleased = 0
        var pasted: String? = null
        var pastedAt = -1

        var copySucceeds = true
        var pasteSucceeds = true
        /** False models macOS giving up on a shortcut key the user never let go of. */
        var modifiersCanBeReleased = true
        /** How many polls the copy takes to show up, modelling a slow application. */
        var copyLatencyPolls = 0

        val log = mutableListOf<String>()
        private var tick = 0
        private var pendingCopy: String? = null
        private var pendingAfter = 0

        override fun clipboardText(): String? {
            tick++
            if (pendingCopy != null && tick >= pendingAfter) {
                clipboard = pendingCopy
                pendingCopy = null
            }
            return clipboard?.takeIf { it.isNotEmpty() }
        }

        override fun setClipboardText(text: String): Boolean {
            log += "set"; clipboard = text; return true
        }

        override fun clearClipboard(): Boolean {
            log += "clear"; clipboard = null; return true
        }

        override fun saveClipboard(): Boolean {
            log += "save"; savedCalled = true; saved = clipboard; return true
        }

        override fun restoreClipboard(): Boolean {
            log += "restore"; restoreCalled = true; clipboard = saved; return true
        }

        override fun releaseModifiers(): Boolean {
            log += "release"; modifiersReleased++; return modifiersCanBeReleased
        }

        override fun selectAll(): Boolean {
            log += "selectAll"; return true
        }

        override fun copy(): Boolean {
            log += "copy"
            if (!copySucceeds) return false
            val selected = selection ?: return true
            if (copyLatencyPolls == 0) clipboard = selected
            else { pendingCopy = selected; pendingAfter = tick + copyLatencyPolls }
            return true
        }

        override fun paste(): Boolean {
            log += "paste"
            if (!pasteSucceeds) return false
            pasted = clipboard
            pastedAt = log.size
            return true
        }
    }

    private fun capture(system: FakeSystem) =
        TextCapture(
            system,
            TextCapture.Timing(
                pollIntervalMillis = 1,
                copyTimeoutMillis = 20,
                selectSettleMillis = 7,
                pasteSettleMillis = 1,
            ),
        ) { millis -> system.log += "slept $millis" }

    /**
     * The bug that mattered most. MyReviser slept and then read the clipboard, so a copy that never
     * landed returned whatever was on the clipboard beforehand — which was then revised and pasted
     * over the user's selection.
     */
    @Test
    fun `a copy that never lands does not return the previous clipboard contents`() {
        val system = FakeSystem(clipboard = "something copied earlier", selection = null)

        val result = capture(system).captureSelection()

        assertIs<Capture.NothingSelected>(result)
        assertEquals("something copied earlier", system.clipboard)
    }

    @Test
    fun `a copy that is refused outright is reported rather than guessed at`() {
        val system = FakeSystem(clipboard = "earlier", selection = "selected text")
        system.copySucceeds = false

        val result = capture(system).captureSelection()

        assertIs<Capture.Failed>(result)
        assertEquals("earlier", system.clipboard)
    }

    /** The clipboard is cleared first, so an empty result is provably ours and not a stale read. */
    @Test
    fun `the clipboard is cleared before the copy`() {
        val system = FakeSystem(clipboard = "earlier", selection = "hello")

        capture(system).captureSelection()

        assertTrue(system.log.indexOf("clear") < system.log.indexOf("copy"))
        assertTrue(system.log.indexOf("save") < system.log.indexOf("clear"))
    }

    @Test
    fun `a slow application still has its copy picked up`() {
        val system = FakeSystem(clipboard = "earlier", selection = "hello")
        system.copyLatencyPolls = 5

        val result = capture(system).captureSelection()

        assertEquals(Capture.Text("hello"), result)
    }

    @Test
    fun `a copy slower than the deadline is a failure, not stale text`() {
        val system = FakeSystem(clipboard = "earlier", selection = "hello")
        system.copyLatencyPolls = 10_000

        val result = capture(system).captureSelection()

        assertIs<Capture.NothingSelected>(result)
    }

    /** The triggering hotkey is still held, so Ctrl+A would otherwise arrive as Ctrl+Alt+A. */
    @Test
    fun `held modifiers are released before any key is simulated`() {
        val system = FakeSystem(selection = "hello")

        capture(system).captureSelection()

        assertTrue(system.log.indexOf("release") < system.log.indexOf("copy"))
    }

    @Test
    fun `select all happens after modifiers are released`() {
        val system = FakeSystem(selection = "everything")

        val result = capture(system).captureAll()

        assertEquals(Capture.Text("everything"), result)
        assertTrue(system.log.indexOf("release") < system.log.indexOf("selectAll"))
    }

    @Test
    fun `the clipboard is put back after a successful round trip`() {
        val system = FakeSystem(clipboard = "user's own copy", selection = "hello")
        val subject = capture(system)

        assertEquals(Capture.Text("hello"), subject.captureSelection())
        assertTrue(subject.replaceSelection("Hello."))

        assertEquals("user's own copy", system.clipboard)
    }

    @Test
    fun `the clipboard is put back when the capture finds nothing`() {
        val system = FakeSystem(clipboard = "user's own copy", selection = null)

        capture(system).captureSelection()

        assertTrue(system.restoreCalled)
        assertEquals("user's own copy", system.clipboard)
    }

    /** Restoring before the target app has taken the data makes it paste the wrong thing. */
    @Test
    fun `the paste happens before the clipboard is restored`() {
        val system = FakeSystem(clipboard = "earlier", selection = "hello")
        val subject = capture(system)
        subject.captureSelection()

        subject.replaceSelection("Hello.")

        assertEquals("Hello.", system.pasted)
        assertTrue(system.pastedAt < system.log.lastIndexOf("restore") + 1)
        assertEquals("restore", system.log.last())
    }

    @Test
    fun `a paste that fails still restores the clipboard`() {
        val system = FakeSystem(clipboard = "earlier", selection = "hello")
        system.pasteSucceeds = false
        val subject = capture(system)
        subject.captureSelection()

        assertFalse(subject.replaceSelection("Hello."))
        assertEquals("earlier", system.clipboard)
    }

    @Test
    fun `whitespace-only selections are treated as nothing to work on`() {
        val system = FakeSystem(selection = "   ")

        assertIs<Capture.NothingSelected>(capture(system).captureSelection())
    }

    /**
     * "Revise everything" was the only action that never worked on macOS, and it is the only one
     * that sends two keystrokes. MyReviser waits 100 ms between them and works; Plume sent them
     * back to back, so the application copied the selection it had before the select-all arrived.
     */
    @Test
    fun `the select-all is given time to land before the copy is sent`() {
        val system = FakeSystem(selection = "everything typed so far")

        assertIs<Capture.Text>(capture(system).captureAll())

        val settled = system.log.indexOf("slept 7")
        assertTrue(settled > system.log.indexOf("selectAll"), "no wait after the select-all")
        assertTrue(settled < system.log.indexOf("copy"), "the copy went out in the same breath")
    }

    /** A plain selection has nothing to wait for, and the delay would be felt on every use. */
    @Test
    fun `capturing an existing selection does not pay the select-all wait`() {
        val system = FakeSystem(selection = "hello")

        capture(system).captureSelection()

        assertFalse(system.log.contains("slept 7"))
    }

    /**
     * The message matters as much as the stopping: blaming the application for a copy the held
     * keys spoiled sends the user looking for a bug that is under their own fingers.
     */
    @Test
    fun `keys still held stop the capture and say so`() {
        val system = FakeSystem(clipboard = "user's own copy", selection = "hello")
        system.modifiersCanBeReleased = false

        val result = capture(system).captureSelection()

        assertEquals(MODIFIERS_HELD, assertIs<Capture.Failed>(result).reason)
        assertFalse(system.log.contains("copy"), "it copied with the shortcut still down")
        assertEquals("user's own copy", system.clipboard)
    }

    @Test
    fun `keys still held stop the paste rather than typing over the selection`() {
        val system = FakeSystem(clipboard = "earlier", selection = "hello")
        val subject = capture(system)
        subject.captureSelection()
        system.modifiersCanBeReleased = false

        assertFalse(subject.replaceSelection("Hello."))
        assertEquals(null, system.pasted, "it pasted while the shortcut was still down")
        assertEquals("earlier", system.clipboard)
    }

    @Test
    fun `abandoning restores the clipboard`() {
        val system = FakeSystem(clipboard = "user's own copy", selection = "hello")
        val subject = capture(system)
        subject.captureSelection()

        subject.abandon()

        assertEquals("user's own copy", system.clipboard)
    }
}
