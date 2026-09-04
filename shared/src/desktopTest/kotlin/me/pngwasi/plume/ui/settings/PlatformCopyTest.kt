package me.pngwasi.plume.ui.settings

import me.pngwasi.plume.data.DesktopOs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop help text used to be Android's, pointing users at a selection toolbar and a Keystore that
 * do not exist on their system. Each OS must only be shown its own instructions.
 */
class PlatformCopyTest {

    private fun prose(os: DesktopOs): String =
        desktopCopy(os).about.flatMap { it.paragraphs + it.steps }.joinToString(" ")

    /** Every string the seam carries, not just the page: the Android wording also leaked into the
     *  API key field. */
    private fun allCopy(os: DesktopOs): String = with(desktopCopy(os)) {
        listOf(prose(os), aboutSubtitle, replacementNote, keyStorageNote, themeNote).joinToString(" ")
    }

    @Test
    fun `no desktop reads about a phone`() {
        DesktopOs.entries.forEach { os ->
            val text = allCopy(os)
            listOf("Android", "iOS", "selection toolbar", "Tap ", "tap ", "your device").forEach {
                assertFalse(text.contains(it), "$os still mentions \"$it\"")
            }
        }
    }

    @Test
    fun `the key field names the store this system actually uses`() {
        assertTrue(desktopCopy(DesktopOs.MacOs).keyStorageNote.contains("keychain"))
        assertTrue(desktopCopy(DesktopOs.Windows).keyStorageNote.contains("DPAPI"))
        assertTrue(desktopCopy(DesktopOs.Linux).keyStorageNote.contains("keyring"))
    }

    @Test
    fun `each system is told how its own shortcuts are unblocked`() {
        assertTrue(prose(DesktopOs.MacOs).contains("Accessibility"))
        assertTrue(prose(DesktopOs.Linux).contains("input group"))
        assertTrue(prose(DesktopOs.Windows).contains("no permission"))
    }

    @Test
    fun `each system is told where its keys are kept`() {
        assertTrue(prose(DesktopOs.MacOs).contains("keychain"))
        assertTrue(prose(DesktopOs.Windows).contains("DPAPI"))
        assertTrue(prose(DesktopOs.Linux).contains("Secret Service"))
    }

    @Test
    fun `each system is told where Plume goes when the window closes`() {
        assertTrue(prose(DesktopOs.MacOs).contains("menu bar"))
        assertTrue(prose(DesktopOs.Windows).contains("notification area"))
        assertTrue(prose(DesktopOs.Linux).contains("GNOME ships no tray"))
    }

    @Test
    fun `every section says something`() {
        DesktopOs.entries.forEach { os ->
            desktopCopy(os).about.forEach { section ->
                assertTrue(
                    section.steps.isNotEmpty() || section.paragraphs.isNotEmpty(),
                    "$os: \"${section.title}\" is empty",
                )
            }
        }
    }

    /** The limit is enforced on the desktop, so it must not carry a note saying it does not apply. */
    @Test
    fun `the character limit applies on every desktop`() {
        DesktopOs.entries.forEach { os ->
            assertNull(desktopCopy(os).characterLimitNote, "$os claims the limit does not apply")
        }
    }
}
