package me.pngwasi.plume.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop key storage.
 *
 * The encrypted file is the fallback every platform can reach, and the only backend that can be
 * exercised without a keyring daemon, a login keychain or Windows — so it is worth covering
 * properly. The round-trip probe is what decides whether a platform backend is trusted at all.
 */
class DesktopSecretStoreTest {

    private fun tempDir(): String = Files.createTempDirectory("plume-secrets").toFile().absolutePath

    // --- the fallback backend ------------------------------------------------------------------

    @Test
    fun `a key survives a write and read`() {
        val backend = EncryptedFileBackend(tempDir())

        assertTrue(backend.set("key_openai", "sk-secret"))

        assertEquals("sk-secret", backend.get("key_openai"))
    }

    @Test
    fun `an unknown entry reads as absent rather than empty`() {
        assertNull(EncryptedFileBackend(tempDir()).get("key_nothing"))
    }

    @Test
    fun `overwriting replaces the value`() {
        val backend = EncryptedFileBackend(tempDir())
        backend.set("key_openai", "first")

        backend.set("key_openai", "second")

        assertEquals("second", backend.get("key_openai"))
    }

    @Test
    fun `removing a key makes it unreadable`() {
        val backend = EncryptedFileBackend(tempDir())
        backend.set("key_openai", "sk-secret")

        backend.remove("key_openai")

        assertNull(backend.get("key_openai"))
    }

    /** Two providers must not be able to read each other's key, since the entry name is the AAD. */
    @Test
    fun `a key encrypted for one entry cannot be read as another`() {
        val dir = tempDir()
        val backend = EncryptedFileBackend(dir)
        backend.set("key_openai", "sk-openai")

        // Same file, different entry name: the AAD no longer matches and the tag check fails.
        java.io.File(dir, "secrets/key_openai.enc")
            .copyTo(java.io.File(dir, "secrets/key_gemini.enc"))

        assertNull(backend.get("key_gemini"))
    }

    /** The point of encrypting at all: the key must not be readable straight out of the file. */
    @Test
    fun `the stored file does not contain the key in the clear`() {
        val dir = tempDir()
        EncryptedFileBackend(dir).set("key_openai", "sk-plaintext-secret")

        val raw = java.io.File(dir, "secrets/key_openai.enc").readText()

        assertFalse(raw.contains("sk-plaintext-secret"))
    }

    @Test
    fun `a fresh directory gets its own master key`() {
        val first = tempDir()
        val second = tempDir()
        EncryptedFileBackend(first).set("key_openai", "x")
        EncryptedFileBackend(second).set("key_openai", "x")

        assertNotEquals(
            java.io.File(first, "secrets/master.key").readBytes().toList(),
            java.io.File(second, "secrets/master.key").readBytes().toList(),
        )
    }

    /** A store built twice over the same directory must still read what the first one wrote. */
    @Test
    fun `keys persist across instances`() {
        val dir = tempDir()
        EncryptedFileBackend(dir).set("key_openai", "sk-secret")

        assertEquals("sk-secret", EncryptedFileBackend(dir).get("key_openai"))
    }

    // --- the probe that decides whether to trust a platform backend ----------------------------

    private class FakeBackend(
        private val writeSucceeds: Boolean = true,
        private val readsBack: Boolean = true,
    ) : KeyringBackend {
        val stored = mutableMapOf<String, String>()
        override fun isAvailable() = true
        override fun get(entry: String) = if (readsBack) stored[entry] else null
        override fun set(entry: String, value: String): Boolean {
            if (!writeSucceeds) return false
            stored[entry] = value
            return true
        }
        override fun remove(entry: String) { stored.remove(entry) }
    }

    @Test
    fun `a working backend passes the probe`() {
        assertTrue(FakeBackend().roundTrips())
    }

    @Test
    fun `a backend that refuses to write fails the probe`() {
        assertFalse(FakeBackend(writeSucceeds = false).roundTrips())
    }

    /**
     * The dangerous case: the write is reported as succeeding and the value is not actually there.
     * Trusting `isAvailable()` alone would lose the user's key without a word.
     */
    @Test
    fun `a backend that writes but cannot read back fails the probe`() {
        assertFalse(FakeBackend(readsBack = false).roundTrips())
    }

    @Test
    fun `a backend that throws fails the probe rather than propagating`() {
        val hostile = object : KeyringBackend {
            override fun isAvailable() = true
            override fun get(entry: String): String? = throw IllegalStateException("keyring locked")
            override fun set(entry: String, value: String) = true
            override fun remove(entry: String) = Unit
        }

        assertFalse(hostile.roundTrips())
    }

    /** The probe must not leave its own entry behind in the user's keyring. */
    @Test
    fun `the probe cleans up after itself`() {
        val backend = FakeBackend()

        backend.roundTrips()

        assertTrue(backend.stored.isEmpty())
    }

    // --- the public store ----------------------------------------------------------------------

    /** Built on the file backend explicitly: a test must never touch the real keyring. */
    private fun store(dir: String = tempDir()) =
        DesktopSecretStore.withBackend(dir, EncryptedFileBackend(dir))

    @Test
    fun `an empty value removes the key rather than storing blank`() {
        val store = store()
        store.setKey("openai", "sk-secret")

        store.setKey("openai", "   ")

        assertEquals("", store.getKey("openai"))
        assertFalse(store.hasKey("openai"))
    }

    @Test
    fun `keys are stored per provider`() {
        val store = store()

        store.setKey("openai", "sk-one")
        store.setKey("gemini", "sk-two")

        assertEquals("sk-one", store.getKey("openai"))
        assertEquals("sk-two", store.getKey("gemini"))
    }

    @Test
    fun `provider ids are matched case-insensitively`() {
        val store = store()

        store.setKey("OpenAI", "sk-one")

        assertEquals("sk-one", store.getKey("openai"))
    }
}
