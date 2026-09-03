package me.pngwasi.plume.ai

import io.ktor.client.HttpClient
import me.pngwasi.plume.data.Action
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.Prompts
import me.pngwasi.plume.data.SecretStore

/**
 * Builds providers from settings and runs the two user-facing operations.
 *
 * Each action resolves its own provider, so Revise and Translate can run on different services —
 * a cheap fast model for corrections, a stronger one for translation, say.
 *
 * Free of platform types, so the whole path stays unit-testable against a fake key reader and a
 * mock HTTP engine — on every target rather than only the JVM.
 */
class TextEngine(
    private val settings: AppSettings,
    private val apiKeyFor: (String) -> String,
    private val http: HttpClient = AiHttp.shared,
) {

    constructor(settings: AppSettings, secrets: SecretStore) :
        this(settings, { id -> secrets.getKey(id) })

    suspend fun revise(text: String): String {
        val mention = mention(text)
        val providerId = mention.providerId ?: settings.providerIdFor(Action.Revise)
        val config = requireUsable(providerId)
        val input = validate(mention.text, settings.revise.characterLimit)
        val provider = build(providerId, config, settings.revise.timeoutSeconds)
        return finish(provider.complete(settings.revise.promptOrDefault(), input))
    }

    suspend fun translate(text: String, targetCode: String): String {
        val mention = mention(text)
        val providerId = mention.providerId ?: settings.providerIdFor(Action.Translate)
        val config = requireUsable(providerId)
        val input = validate(mention.text, settings.translate.characterLimit)
        val provider = build(providerId, config, settings.translate.timeoutSeconds)
        val target = Languages.resolve(targetCode).promptName()
        val prompt = Prompts.renderTranslate(settings.translate.promptOrDefault(), target)
        return finish(provider.complete(prompt, input))
    }

    /** `@openai fix this` sends one request to a named provider without changing any setting. */
    private fun mention(text: String) = parseProviderMention(
        text = text,
        providerIds = settings.providers.keys,
        enabled = settings.providerMentions,
    )

    private fun requireUsable(providerId: String): ProviderConfig {
        val config = settings.providers[providerId] ?: throw AiException(
            AiException.Kind.NotConfigured,
            "No AI provider selected. Open Plume and pick one.",
        )
        val label = label(providerId, config)
        if (config.baseUrl.isBlank()) {
            throw AiException(AiException.Kind.NotConfigured, "$label has no base URL. Finish setting it up in Plume.")
        }
        if (config.model.isBlank()) {
            throw AiException(AiException.Kind.NotConfigured, "$label has no model selected. Pick one in Plume.")
        }
        // Local runtimes take no credentials, so a key is only demanded when the provider says so.
        if (config.authRequired && apiKeyFor(providerId).isBlank()) {
            throw AiException(AiException.Kind.NotConfigured, "No API key for $label. Add one in Plume.")
        }
        return config
    }

    private fun validate(text: String, limit: Int): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw AiException(AiException.Kind.Empty, "Nothing to work with — the selection is empty.")
        }
        if (trimmed.length > limit) {
            throw AiException(
                AiException.Kind.Empty,
                "Selection is ${trimmed.length} characters, over the $limit limit. Select less, or raise the limit in Settings.",
            )
        }
        return trimmed
    }

    private fun finish(raw: String): String {
        val cleaned = ResponseCleaner.clean(raw)
        if (cleaned.isBlank()) {
            throw AiException(AiException.Kind.Empty, "The model returned an empty result.")
        }
        return cleaned
    }

    private fun build(providerId: String, config: ProviderConfig, timeoutSeconds: Int): AiProvider {
        val key = apiKeyFor(providerId)
        val label = label(providerId, config)
        return when (config.kind) {
            ProviderKind.OpenAiCompatible -> OpenAiCompatibleProvider(
                providerId, label, key, config.baseUrl, config.model, config.temperature,
                timeoutSeconds, config.reasoning, config.reasoningDialect, http,
            )
            ProviderKind.Gemini -> GeminiProvider(
                providerId, label, key, config.baseUrl, config.model, config.temperature,
                timeoutSeconds, config.reasoning, http,
            )
        }
    }

    private fun label(providerId: String, config: ProviderConfig): String =
        config.label.ifBlank { providerId }
}
