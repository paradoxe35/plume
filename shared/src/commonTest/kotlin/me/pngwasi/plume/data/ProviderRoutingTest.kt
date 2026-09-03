package me.pngwasi.plume.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** Which provider runs which action, including what happens when one is deleted. */
class ProviderRoutingTest {

    private val base = AppSettings(
        defaultProvider = "openai",
        providers = BuiltInProviders.defaults() +
            ("groq" to ProviderConfig(label = "Groq", baseUrl = "https://g/v1", model = "m", isCustom = true)),
    )

    @Test
    fun `both actions follow the default when no override is set`() {
        assertEquals("openai", base.providerIdFor(Action.Revise))
        assertEquals("openai", base.providerIdFor(Action.Translate))
    }

    @Test
    fun `an override wins for its own action only`() {
        val settings = base.copy(translateProvider = "gemini")

        assertEquals("openai", settings.providerIdFor(Action.Revise))
        assertEquals("gemini", settings.providerIdFor(Action.Translate))
    }

    @Test
    fun `each action can run on a different provider`() {
        val settings = base.copy(reviseProvider = "groq", translateProvider = "gemini")

        assertEquals("groq", settings.providerIdFor(Action.Revise))
        assertEquals("gemini", settings.providerIdFor(Action.Translate))
    }

    /** A stale override must not strand the action on a provider that no longer exists. */
    @Test
    fun `an override pointing at a deleted provider falls back to the default`() {
        val settings = base.copy(reviseProvider = "ghost")

        assertEquals("openai", settings.providerIdFor(Action.Revise))
        assertNull(settings.overrideFor(Action.Revise))
    }

    @Test
    fun `withOverride sets only the targeted action`() {
        val settings = base.withOverride(Action.Revise, "groq")

        assertEquals("groq", settings.reviseProvider)
        assertNull(settings.translateProvider)
    }

    @Test
    fun `withOverride null returns the action to the default`() {
        val settings = base.copy(reviseProvider = "groq").withOverride(Action.Revise, null)

        assertNull(settings.reviseProvider)
        assertEquals("openai", settings.providerIdFor(Action.Revise))
    }

    @Test
    fun `configFor resolves through the override`() {
        val settings = base.copy(translateProvider = "groq")

        assertEquals("m", settings.configFor(Action.Translate)?.model)
    }

    @Test
    fun `labelOf falls back to the id when a provider has no label`() {
        val settings = base.copy(providers = base.providers + ("bare" to ProviderConfig(isCustom = true)))

        assertEquals("bare", settings.labelOf("bare"))
        assertEquals("OpenAI", settings.labelOf("openai"))
        assertEquals("ghost", settings.labelOf("ghost"))
    }
}
