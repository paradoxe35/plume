package me.pngwasi.plume.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an action can run.
 *
 * The desktop now decides whether to start in the tray from this, so a wrong answer either hides an
 * app that cannot work or shows a settings window at every login to someone whose setup is fine.
 */
class ReadinessTest {

    private fun settings(config: ProviderConfig) = AppSettings(
        defaultProvider = "local",
        providers = mapOf("local" to config),
    )

    private val configured = ProviderConfig(baseUrl = "https://api.example.com/v1", model = "m")

    @Test
    fun `a provider that needs a key is not ready without one`() {
        val settings = settings(configured)

        assertFalse(settings.isReady(Action.Revise, keyedProviders = emptySet()))
        assertTrue(settings.isReady(Action.Revise, keyedProviders = setOf("local")))
    }

    /**
     * The regression this was written for: Ollama and LM Studio take no credentials, so demanding a
     * key left a working setup reporting "Setup needed" with nothing the user could do about it.
     */
    @Test
    fun `a provider that needs no key is ready without one`() {
        val settings = settings(configured.copy(authRequired = false))

        assertTrue(settings.isReady(Action.Revise, keyedProviders = emptySet()))
    }

    @Test
    fun `an unconfigured provider is never ready`() {
        assertFalse(
            settings(ProviderConfig(baseUrl = "", model = "")).isReady(Action.Revise, setOf("local")),
        )
        assertFalse(
            settings(configured.copy(model = "")).isReady(Action.Revise, setOf("local")),
        )
    }

    @Test
    fun `a missing provider is not ready`() {
        assertFalse(AppSettings(defaultProvider = "gone", providers = emptyMap()).isReady(Action.Revise, emptySet()))
    }

    /** Both actions have to be ready: one of them silently failing is the case worth surfacing. */
    @Test
    fun `fully configured means every action`() {
        val settings = AppSettings(
            defaultProvider = "local",
            translateProvider = "other",
            providers = mapOf("local" to configured, "other" to configured),
        )

        assertFalse(settings.isFullyConfigured(keyedProviders = setOf("local")))
        assertTrue(settings.isFullyConfigured(keyedProviders = setOf("local", "other")))
    }

    @Test
    fun `only providers holding a non-blank key count as keyed`() {
        val secrets = object : SecretStore {
            private val keys = mapOf("local" to "sk-real", "other" to "  ")
            override fun getKey(providerId: String) = keys[providerId].orEmpty()
            override fun setKey(providerId: String, value: String) = Unit
            override fun removeKey(providerId: String) = Unit
            override fun hasKey(providerId: String) = providerId in keys
        }
        val settings = AppSettings(providers = mapOf("local" to configured, "other" to configured))

        assertTrue(settings.keyedProviders(secrets) == setOf("local"))
    }
}
