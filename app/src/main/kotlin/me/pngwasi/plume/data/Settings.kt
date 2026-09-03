package me.pngwasi.plume.data

import kotlinx.serialization.Serializable

/**
 * Wire shape of a provider's HTTP API.
 *
 * Only two exist because almost the entire ecosystem — OpenRouter, Groq, Mistral, DeepSeek,
 * Together, Ollama, vLLM — speaks OpenAI's chat-completions format. Gemini is the one common
 * exception worth its own client.
 */
@Serializable
enum class ProviderKind { OpenAiCompatible, Gemini }

@Serializable
enum class ThemeMode { System, Light, Dark }

/** The two things Plume can do. Each may run on its own provider. */
enum class Action { Revise, Translate }

@Serializable
data class ProviderConfig(
    val label: String = "",
    val kind: ProviderKind = ProviderKind.OpenAiCompatible,
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Float = 1f,
    val isCustom: Boolean = false,
) {
    /** API keys never live here — they are held encrypted by [SecretStore], keyed by provider id. */
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()
}

@Serializable
data class ReviseSettings(
    val systemPrompt: String = "",
    val characterLimit: Int = DEFAULT_CHARACTER_LIMIT,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    fun promptOrDefault(): String = systemPrompt.ifBlank { Prompts.REVISE }
}

@Serializable
data class TranslateSettings(
    val systemPrompt: String = "",
    val characterLimit: Int = DEFAULT_CHARACTER_LIMIT,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** Pinned targets, shown first in the picker. User adds and removes these. */
    val favorites: List<String> = emptyList(),
    /** Most-recently-used targets, maintained automatically. */
    val recents: List<String> = emptyList(),
    /** Skips the picker entirely when set — for people who always translate one way. */
    val defaultTarget: String? = null,
) {
    fun promptOrDefault(): String = systemPrompt.ifBlank { Prompts.TRANSLATE }
}

@Serializable
data class AppSettings(
    /** Used by any action without its own override. */
    val defaultProvider: String = BuiltInProviders.OPENAI,
    /** Per-action overrides. Null means "follow the default", which is the common case. */
    val reviseProvider: String? = null,
    val translateProvider: String? = null,
    val providers: Map<String, ProviderConfig> = BuiltInProviders.defaults(),
    val revise: ReviseSettings = ReviseSettings(),
    val translate: TranslateSettings = TranslateSettings(),
    val theme: ThemeMode = ThemeMode.System,
    /**
     * Opt-in companion keyboard. Off by default: it adds an entry to the user's system keyboard
     * list, which nobody should get without asking for it.
     */
    val keyboardEnabled: Boolean = false,
) {
    /**
     * Resolves which provider runs [action], falling back to the default when the override points
     * at a provider that has since been deleted.
     */
    fun providerIdFor(action: Action): String {
        val override = when (action) {
            Action.Revise -> reviseProvider
            Action.Translate -> translateProvider
        }
        return override?.takeIf { providers.containsKey(it) } ?: defaultProvider
    }

    fun configFor(action: Action): ProviderConfig? = providers[providerIdFor(action)]

    fun overrideFor(action: Action): String? = when (action) {
        Action.Revise -> reviseProvider
        Action.Translate -> translateProvider
    }?.takeIf { providers.containsKey(it) }

    fun withOverride(action: Action, providerId: String?): AppSettings = when (action) {
        Action.Revise -> copy(reviseProvider = providerId)
        Action.Translate -> copy(translateProvider = providerId)
    }

    /** Built-ins first in a fixed order, then custom providers alphabetically. */
    fun providerIds(): List<String> {
        val builtIn = BuiltInProviders.ORDER.filter { providers.containsKey(it) }
        val custom = providers.filterValues { it.isCustom }.keys.sorted()
        return builtIn + custom
    }

    fun labelOf(providerId: String): String =
        providers[providerId]?.label?.ifBlank { providerId } ?: providerId
}

const val DEFAULT_CHARACTER_LIMIT = 4000
const val DEFAULT_TIMEOUT_SECONDS = 45
const val MAX_RECENT_TARGETS = 5

object BuiltInProviders {
    const val OPENAI = "openai"
    const val OPENROUTER = "openrouter"
    const val GEMINI = "gemini"

    val ORDER = listOf(OPENAI, OPENROUTER, GEMINI)

    fun isBuiltIn(id: String): Boolean = id.lowercase() in ORDER

    fun defaults(): Map<String, ProviderConfig> = mapOf(
        OPENAI to ProviderConfig(
            label = "OpenAI",
            kind = ProviderKind.OpenAiCompatible,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
        ),
        OPENROUTER to ProviderConfig(
            label = "OpenRouter",
            kind = ProviderKind.OpenAiCompatible,
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openai/gpt-4o-mini",
        ),
        GEMINI to ProviderConfig(
            label = "Gemini",
            kind = ProviderKind.Gemini,
            baseUrl = "https://generativelanguage.googleapis.com",
            model = "gemini-2.5-flash",
        ),
    )

    /** Shown while the live model list is loading, or when the provider has no /models endpoint. */
    fun fallbackModels(id: String, kind: ProviderKind): List<String> = when {
        id == OPENROUTER -> listOf(
            "openai/gpt-4o-mini",
            "google/gemini-2.5-flash",
            "anthropic/claude-haiku-4.5",
            "meta-llama/llama-3.3-70b-instruct",
        )
        kind == ProviderKind.Gemini -> listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro")
        else -> listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini")
    }
}

/** Names double as DataStore and secret-store keys, so they stay on a safe character set. */
fun validateCustomProviderName(name: String, existing: Set<String>): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Name is required"
        !trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' } ->
            "Use letters, digits, hyphens and underscores only"
        BuiltInProviders.isBuiltIn(trimmed) -> "\"$trimmed\" is a built-in provider"
        existing.any { it.equals(trimmed, ignoreCase = true) } -> "\"$trimmed\" already exists"
        else -> null
    }
}

/**
 * Field-level validation for the provider editor.
 *
 * A provider is only usable with all three of a base URL, a model and a key, so each is reported
 * individually rather than as one vague "not configured" — the user needs to know which is missing.
 */
data class ProviderValidation(
    val label: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
) {
    val isValid: Boolean get() = label == null && baseUrl == null && model == null && apiKey == null
}

fun validateProvider(
    config: ProviderConfig,
    apiKey: String,
    requireLabel: Boolean,
): ProviderValidation = ProviderValidation(
    label = if (requireLabel && config.label.isBlank()) "Display name is required" else null,
    baseUrl = when {
        config.baseUrl.isBlank() -> "Base URL is required"
        !config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://") ->
            "Must start with http:// or https://"
        else -> null
    },
    model = if (config.model.isBlank()) "Model is required" else null,
    apiKey = if (apiKey.isBlank()) "API key is required" else null,
)

/** Moves [code] to the front, removing any earlier copy, and caps the list. */
fun List<String>.withRecentTarget(code: String, max: Int = MAX_RECENT_TARGETS): List<String> =
    (listOf(code) + filterNot { it.equals(code, ignoreCase = true) }).take(max)
