package me.pngwasi.plume.ui.settings

import me.pngwasi.plume.data.DesktopOs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "How Plume works" on the desktop.
 *
 * This page was Android's text on every platform, which told desktop users to tap an entry in a
 * selection toolbar that does not exist and blamed the Android Keystore for keys held by DPAPI.
 * Wrong help is worse than none: it sends someone looking for a setting that was never there.
 */
class PlatformCopyTest {

    private fun prose(os: DesktopOs): String =
        desktopCopy(os).about.flatMap { it.paragraphs + it.steps }.joinToString(" ")

    /** Every string the seam carries, not just the page: the same wording leaked into the API key
     *  field, which told desktop users their key was in the Android Keystore. */
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

    /** The permission that has to be granted is different on each, and naming the wrong one wastes
     *  exactly the time of someone whose shortcuts are not working. */
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

    /** macOS has a menu bar, Windows a notification area, Linux a tray that may not be there. */
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
}
