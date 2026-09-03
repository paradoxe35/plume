package me.pngwasi.plume.data

import kotlinx.coroutines.test.runTest
import me.pngwasi.plume.panel.pickerOptions
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeping the settings screen and the keyboard picker telling the same story.
 *
 * Unpinning a language that was also recent used to leave it on screen — the two screens visibly
 * disagreeing about a change the user had just made.
 *
 * These drive the real [SettingsRepository] over a real DataStore rather than re-implementing its
 * rule, so a regression in the repository actually fails here. The file system is faked, which
 * keeps the test hermetic and lets it run on every target rather than only the JVM.
 */
class LanguageSyncTest {

    // DataStore keeps a process-wide registry of active files keyed by path, and the test runner
    // builds a fresh class instance per test, so the counter has to outlive the instance or every
    // test would claim the same path.
    private companion object {
        var next = 0
    }

    private fun repository(): SettingsRepository = storeAt("/plume-sync-${next++}")

    private fun storeAt(directory: String, seedJson: String? = null): SettingsRepository {
        val fs = FakeFileSystem()
        val path = directory.toPath()
        fs.createDirectories(path)
        val file = path / SETTINGS_FILE_NAME
        seedJson?.let { json -> fs.write(file) { writeUtf8(json) } }
        return SettingsRepository(createSettingsDataStore(file, fs))
    }

    private suspend fun SettingsRepository.seed(favorites: List<String>, recents: List<String>) {
        update { it.copy(translate = it.translate.copy(favorites = favorites, recents = recents)) }
    }

    /** Reproduces the reported bug: unpin Spanish, and the keyboard still offers it. */
    @Test
    fun `unpinning a language also drops it from recents`() = runTest {
        val repo = repository()
        repo.seed(favorites = listOf("fr", "en", "es"), recents = listOf("es", "fr"))

        repo.toggleFavoriteLanguage("es")

        val after = repo.current().translate
        assertFalse(after.favorites.contains("es"))
        assertFalse(after.recents.contains("es"))
    }

    @Test
    fun `the keyboard stops offering a language once it is unpinned`() = runTest {
        val repo = repository()
        repo.seed(favorites = listOf("fr", "es"), recents = listOf("es"))

        repo.toggleFavoriteLanguage("es")

        val after = repo.current().translate
        val offered = pickerOptions(after.recents, after.favorites, fallback = emptyList())
        assertFalse(offered.contains("es"))
        assertTrue(offered.contains("fr"))
    }

    @Test
    fun `unpinning leaves other languages alone`() = runTest {
        val repo = repository()
        repo.seed(favorites = listOf("fr", "en", "es"), recents = listOf("es", "de"))

        repo.toggleFavoriteLanguage("es")

        val after = repo.current().translate
        assertEquals(listOf("fr", "en"), after.favorites)
        assertEquals(listOf("de"), after.recents)
    }

    @Test
    fun `unpinning matches regardless of case`() = runTest {
        val repo = repository()
        repo.seed(favorites = listOf("ES"), recents = listOf("es"))

        repo.toggleFavoriteLanguage("es")

        val after = repo.current().translate
        assertTrue(after.favorites.isEmpty())
        assertTrue(after.recents.isEmpty())
    }

    /** Pinning is not a removal, so it must not disturb the recent list. */
    @Test
    fun `pinning a new language keeps recents intact`() = runTest {
        val repo = repository()
        repo.seed(favorites = listOf("fr"), recents = listOf("de", "es"))

        repo.toggleFavoriteLanguage("it")

        val after = repo.current().translate
        assertEquals(listOf("fr", "it"), after.favorites)
        assertEquals(listOf("de", "es"), after.recents)
    }

    /** Using a language re-adds it to recents; unpinning is the only thing that removes it. */
    @Test
    fun `translating with a language records it as recent again`() = runTest {
        val repo = repository()
        repo.seed(favorites = listOf("fr"), recents = emptyList())

        repo.recordTranslationTarget("de")

        assertEquals(listOf("de"), repo.current().translate.recents)
    }

    /** Settings written by a build that did not know a field must still load. */
    @Test
    fun `settings survive a document written without every field`() = runTest {
        val repo = storeAt("/plume-partial", """{"defaultProvider":"openai"}""")

        assertEquals("openai", repo.current().defaultProvider)
        assertEquals(ThemeMode.System, repo.current().theme)
    }

    /** A truncated write used to make settings unreadable forever rather than resetting. */
    @Test
    fun `a corrupt document resets to defaults instead of failing every read`() = runTest {
        val repo = storeAt("/plume-corrupt", "{ this is not json")

        assertEquals(BuiltInProviders.OPENAI, repo.current().defaultProvider)
    }
}
