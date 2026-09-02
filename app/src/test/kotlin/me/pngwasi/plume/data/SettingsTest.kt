package me.pngwasi.plume.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    private val json = SettingsSerializer.json

    @Test
    fun `defaults ship all three built-in providers`() {
        val settings = AppSettings()

        assertEquals(3, settings.providers.size)
        assertNotNull(settings.providers[BuiltInProviders.OPENAI])
        assertNotNull(settings.providers[BuiltInProviders.OPENROUTER])
        assertNotNull(settings.providers[BuiltInProviders.GEMINI])
    }

    @Test
    fun `provider ids list built-ins in order then custom alphabetically`() {
        val settings = AppSettings(
            providers = BuiltInProviders.defaults() +
                mapOf(
                    "zeta" to ProviderConfig(isCustom = true),
                    "alpha" to ProviderConfig(isCustom = true),
                ),
        )

        assertEquals(
            listOf("openai", "openrouter", "gemini", "alpha", "zeta"),
            settings.providerIds(),
        )
    }

    @Test
    fun `a provider without a model is not considered configured`() {
        assertFalse(ProviderConfig(baseUrl = "https://x", model = "").isConfigured())
        assertFalse(ProviderConfig(baseUrl = "", model = "gpt-4o").isConfigured())
        assertTrue(ProviderConfig(baseUrl = "https://x", model = "gpt-4o").isConfigured())
    }

    // --- custom provider names -------------------------------------------------------------

    @Test
    fun `valid custom names are accepted`() {
        assertNull(validateCustomProviderName("groq", emptySet()))
        assertNull(validateCustomProviderName("my-gateway_2", emptySet()))
    }

    @Test
    fun `blank names are rejected`() {
        assertNotNull(validateCustomProviderName("   ", emptySet()))
    }

    @Test
    fun `names with spaces or symbols are rejected`() {
        assertNotNull(validateCustomProviderName("my gateway", emptySet()))
        assertNotNull(validateCustomProviderName("gate/way", emptySet()))
    }

    @Test
    fun `built-in names cannot be reused`() {
        assertNotNull(validateCustomProviderName("openai", emptySet()))
        assertNotNull(validateCustomProviderName("OpenAI", emptySet()))
    }

    @Test
    fun `duplicate names are rejected case-insensitively`() {
        assertNotNull(validateCustomProviderName("Groq", setOf("groq")))
    }

    // --- recents ---------------------------------------------------------------------------

    @Test
    fun `recent target moves to the front without duplicating`() {
        val recents = listOf("de", "fr", "es")

        assertEquals(listOf("fr", "de", "es"), recents.withRecentTarget("fr"))
    }

    @Test
    fun `recent target is added when new`() {
        assertEquals(listOf("it", "de"), listOf("de").withRecentTarget("it"))
    }

    @Test
    fun `recents are capped`() {
        val recents = listOf("a", "b", "c", "d", "e")

        val updated = recents.withRecentTarget("f")

        assertEquals(MAX_RECENT_TARGETS, updated.size)
        assertEquals("f", updated.first())
        assertFalse(updated.contains("e"))
    }

    @Test
    fun `recent target dedupes case-insensitively`() {
        assertEquals(listOf("FR"), listOf("fr").withRecentTarget("FR"))
    }

    // --- serialization ---------------------------------------------------------------------

    @Test
    fun `settings round-trip through json`() {
        val original = AppSettings(
            defaultProvider = "groq",
            translateProvider = "gemini",
            providers = BuiltInProviders.defaults() +
                ("groq" to ProviderConfig(label = "Groq", baseUrl = "https://x/v1", model = "m", isCustom = true)),
            revise = ReviseSettings(systemPrompt = "fix it", characterLimit = 800),
            translate = TranslateSettings(favorites = listOf("fr", "sw"), defaultTarget = "sw"),
            theme = ThemeMode.Dark,
        )

        val restored = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), original),
        )

        assertEquals(original, restored)
    }

    /** A settings file written by a newer build must not wipe the user's configuration. */
    @Test
    fun `unknown fields are ignored when reading`() {
        val payload = """{"defaultProvider":"gemini","futureFeature":{"x":1},"theme":"Light"}"""

        val restored = json.decodeFromString(AppSettings.serializer(), payload)

        assertEquals("gemini", restored.defaultProvider)
        assertEquals(ThemeMode.Light, restored.theme)
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val restored = json.decodeFromString(AppSettings.serializer(), """{"defaultProvider":"openai"}""")

        assertEquals(3, restored.providers.size)
        assertEquals(DEFAULT_CHARACTER_LIMIT, restored.revise.characterLimit)
        assertEquals(ThemeMode.System, restored.theme)
    }

    @Test
    fun `api keys are never part of the serialized settings`() {
        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings())

        assertFalse(encoded.contains("apiKey", ignoreCase = true))
        assertFalse(encoded.contains("api_key", ignoreCase = true))
    }
}
