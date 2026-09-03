package me.pngwasi.plume.data

import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.test.runTest
import me.pngwasi.plume.ime.pickerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Keeping the settings screen and the keyboard picker telling the same story.
 *
 * The picker offers recents and pinned languages together, so unpinning something that was also
 * recent used to leave it on screen — the two screens visibly disagreeing about a change the user
 * had just made.
 *
 * These drive the real [SettingsRepository] over a temporary DataStore rather than re-implementing
 * its rule, so a regression in the repository actually fails here.
 */
class LanguageSyncTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository(): SettingsRepository {
        val file = File(folder.newFolder(), "settings.json")
        return SettingsRepository(
            DataStoreFactory.create(serializer = SettingsSerializer, produceFile = { file }),
        )
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
}
